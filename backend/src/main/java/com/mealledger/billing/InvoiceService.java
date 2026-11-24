package com.mealledger.billing;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.common.ChangeTracker;
import com.mealledger.order.Actor;

/**
 * Vendor bills.
 *
 * <pre>
 *   SUBMITTED --approve--> APPROVED --pay (bank UTR)--> PAID
 *       |  ^
 *  reject  | resubmit (kitchen fixes the lines)
 *       v  |
 *     REJECTED
 * </pre>
 *
 * A kitchen bills per day. Ops reviews a live reconciliation against confirmed receipts, with proof
 * per line (who confirmed, when, children present). Approval freezes the numbers; one bank UTR
 * can settle only one bill. Every step is written to invoice_event, which both sides can see.
 */
@Service
public class InvoiceService {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final BusinessTime time;
    private final ChangeTracker changes;

    public InvoiceService(JdbcClient jdbc, NamedParameterJdbcTemplate named, BusinessTime time, ChangeTracker changes) {
        this.jdbc = jdbc;
        this.named = named;
        this.time = time;
        this.changes = changes;
    }

    // ---------- kitchen side ----------

    public record DraftLine(long orderId, String centreName, String routeName, String mealSlot,
                            String status, Integer lockedQty, Integer receivedQty) {
    }

    public record Draft(LocalDate serviceDate, Long existingInvoiceId, String existingStatus, List<DraftLine> lines) {
    }

    /** Everything the kitchen cooked for that day (orders that were locked at some point). */
    public Draft draft(long vendorId, LocalDate date) {
        var existing = jdbc.sql("SELECT id, status FROM invoice WHERE vendor_id = :v AND service_date = :d")
                .param("v", vendorId).param("d", date)
                .query((rs, n) -> new Object[]{rs.getLong("id"), rs.getString("status")})
                .optional();
        List<DraftLine> lines = jdbc.sql("""
                SELECT o.id AS order_id, c.name AS centre_name, r.name AS route_name, o.meal_slot,
                       o.status, o.locked_qty, o.received_qty
                  FROM meal_order o JOIN centre c ON c.id = o.centre_id JOIN route r ON r.id = o.route_id
                 WHERE o.vendor_id = :v AND o.service_date = :d AND o.locked_qty IS NOT NULL
                 ORDER BY r.name, o.meal_slot, c.name""")
                .param("v", vendorId).param("d", date)
                .query(DraftLine.class)
                .list();
        return new Draft(date,
                existing.map(e -> (Long) e[0]).orElse(null),
                existing.map(e -> (String) e[1]).orElse(null),
                lines);
    }

    public record SubmitLine(long orderId, int billedQty) {
    }

    @Transactional
    public long submit(Actor kitchen, LocalDate date, List<SubmitLine> lines) {
        if (date.isAfter(time.today())) {
            throw ApiException.conflict("FUTURE_DATE", "You can bill only for a day that has started");
        }
        long existing = jdbc.sql("SELECT COUNT(*) FROM invoice WHERE vendor_id = :v AND service_date = :d")
                .param("v", kitchen.vendorId()).param("d", date).query(Long.class).single();
        if (existing > 0) {
            throw ApiException.conflict("INVOICE_EXISTS", "A bill for " + date + " was already sent");
        }
        validateLines(kitchen.vendorId(), date, lines);

        OffsetDateTime now = time.now();
        long invoiceId = jdbc.sql("""
                INSERT INTO invoice (vendor_id, service_date, status, submitted_at)
                VALUES (:v, :d, 'SUBMITTED', :now) RETURNING id""")
                .param("v", kitchen.vendorId()).param("d", date).param("now", now)
                .query(Long.class).single();
        insertLines(invoiceId, lines);
        event(invoiceId, "SUBMITTED", kitchen.label(), lines.size() + " lines, "
                + lines.stream().mapToInt(SubmitLine::billedQty).sum() + " meals", null, now);
        changes.bump();
        return invoiceId;
    }

    /** After a rejection the kitchen fixes the lines and sends the same bill again. */
    @Transactional
    public long resubmit(Actor kitchen, long invoiceId, List<SubmitLine> lines) {
        record Inv(long vendorId, LocalDate serviceDate, String status) {
        }
        Inv inv = jdbc.sql("SELECT vendor_id, service_date, status FROM invoice WHERE id = :id FOR UPDATE")
                .param("id", invoiceId)
                .query((rs, n) -> new Inv(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3)))
                .optional()
                .filter(i -> i.vendorId() == kitchen.vendorId())
                .orElseThrow(() -> ApiException.notFound("Bill"));
        if (!inv.status().equals("REJECTED")) {
            throw ApiException.conflict("NOT_REJECTED", "Only a rejected bill can be sent again");
        }
        validateLines(kitchen.vendorId(), inv.serviceDate(), lines);

        OffsetDateTime now = time.now();
        jdbc.sql("DELETE FROM invoice_line WHERE invoice_id = :id").param("id", invoiceId).update();
        insertLines(invoiceId, lines);
        jdbc.sql("UPDATE invoice SET status = 'SUBMITTED', submitted_at = :now WHERE id = :id")
                .param("now", now).param("id", invoiceId).update();
        event(invoiceId, "RESUBMITTED", kitchen.label(), lines.stream().mapToInt(SubmitLine::billedQty).sum() + " meals", null, now);
        changes.bump();
        return invoiceId;
    }

    private void validateLines(long vendorId, LocalDate date, List<SubmitLine> lines) {
        Set<Long> ids = new HashSet<>();
        for (SubmitLine l : lines) {
            if (!ids.add(l.orderId())) {
                throw ApiException.unprocessable("DUPLICATE_LINE", "Order " + l.orderId() + " appears twice");
            }
        }
        long valid = jdbc.sql("""
                SELECT COUNT(*) FROM meal_order
                 WHERE id IN (:ids) AND vendor_id = :v AND service_date = :d AND locked_qty IS NOT NULL""")
                .param("ids", ids).param("v", vendorId).param("d", date)
                .query(Long.class).single();
        if (valid != ids.size()) {
            throw ApiException.unprocessable("INVALID_LINES",
                    "Some orders are not yours, not for " + date + ", or were never locked");
        }
    }

    private void insertLines(long invoiceId, List<SubmitLine> lines) {
        named.batchUpdate("INSERT INTO invoice_line (invoice_id, order_id, billed_qty) VALUES (:i, :o, :q)",
                lines.stream().map(l -> new MapSqlParameterSource()
                                .addValue("i", invoiceId).addValue("o", l.orderId()).addValue("q", l.billedQty()))
                        .toArray(MapSqlParameterSource[]::new));
    }

    private void event(long invoiceId, String kind, String actor, String note, Long amountPaise, OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO invoice_event (invoice_id, kind, actor_label, note, amount_paise, at)
                VALUES (:i, :k, :a, :n, :amt, :at)""")
                .param("i", invoiceId).param("k", kind).param("a", actor).param("n", note)
                .param("amt", amountPaise).param("at", at)
                .update();
    }

    // ---------- views (both sides) ----------

    public record InvoiceSummary(long id, long vendorId, String vendorName, LocalDate serviceDate, String status,
                                 int lines, long billedMeals, long payableMeals, int flaggedLines,
                                 int unconfirmedLines, long payablePaise, long billedPaise, OffsetDateTime submittedAt,
                                 OffsetDateTime approvedAt, OffsetDateTime paidAt, String paymentRef, String rejectReason) {
    }

    /** One bill line with its reconciliation and the evidence behind it. */
    public record LineView(long lineId, long orderId, String centreName, String routeName, String mealSlot,
                           String orderStatus, int billedQty, Integer lockedQty, Integer receivedQty, int payableQty,
                           String flag, int ratePaise, long amountPaise,
                           String receivedBy, OffsetDateTime receivedAt, Integer childrenPresent) {
    }

    public record EventView(long id, String kind, String actorLabel, String note, Long amountPaise, OffsetDateTime at) {
    }

    public record InvoiceDetail(InvoiceSummary invoice, List<LineView> lines, List<EventView> events) {
    }

    /** Bills whose service day is in [from, to], newest first; optionally one kitchen only. */
    public List<InvoiceSummary> list(LocalDate from, LocalDate to, Long vendorId) {
        return jdbc.sql("""
                SELECT id FROM invoice
                 WHERE service_date BETWEEN :from AND :to AND (CAST(:v AS BIGINT) IS NULL OR vendor_id = :v)
                 ORDER BY service_date DESC, id""")
                .param("from", from).param("to", to).param("v", vendorId)
                .query(Long.class)
                .list()
                .stream()
                .map(id -> detail(id).invoice())
                .toList();
    }

    /** A kitchen may only open its own bills. */
    public InvoiceDetail detailForVendor(long vendorId, long invoiceId) {
        InvoiceDetail d = detail(invoiceId);
        if (d.invoice().vendorId() != vendorId) {
            throw ApiException.notFound("Bill");
        }
        return d;
    }

    public InvoiceDetail detail(long invoiceId) {
        record Header(long id, long vendorId, String vendorName, LocalDate serviceDate, String status,
                      int ratePerMealPaise, OffsetDateTime submittedAt, OffsetDateTime approvedAt,
                      OffsetDateTime paidAt, String paymentRef, String rejectReason) {
        }
        Header h = jdbc.sql("""
                SELECT i.id, i.vendor_id, v.name AS vendor_name, i.service_date, i.status, v.rate_per_meal_paise,
                       i.submitted_at, i.approved_at, i.paid_at, i.payment_ref, i.reject_reason
                  FROM invoice i JOIN vendor v ON v.id = i.vendor_id WHERE i.id = :id""")
                .param("id", invoiceId)
                .query((rs, n) -> new Header(rs.getLong("id"), rs.getLong("vendor_id"), rs.getString("vendor_name"),
                        rs.getObject("service_date", LocalDate.class), rs.getString("status"),
                        rs.getInt("rate_per_meal_paise"), rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("approved_at", OffsetDateTime.class), rs.getObject("paid_at", OffsetDateTime.class),
                        rs.getString("payment_ref"), rs.getString("reject_reason")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Bill"));
        boolean frozen = h.status().equals("APPROVED") || h.status().equals("PAID");

        List<LineView> lines = jdbc.sql("""
                SELECT l.id, l.order_id, c.name AS centre_name, r.name AS route_name, o.meal_slot, o.status,
                       l.billed_qty, o.locked_qty, o.received_qty, l.payable_qty, l.flag, l.rate_paise, l.amount_paise,
                       ev.actor_label AS received_by, o.received_at, a.present
                  FROM invoice_line l
                  JOIN meal_order o ON o.id = l.order_id
                  JOIN centre c ON c.id = o.centre_id
                  JOIN route r ON r.id = o.route_id
                  LEFT JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date
                                        AND a.meal_slot = o.meal_slot
                  LEFT JOIN LATERAL (
                      SELECT e.actor_label FROM order_event e
                       WHERE e.order_id = o.id AND e.to_status = 'RECEIVED' ORDER BY e.id DESC LIMIT 1
                  ) ev ON TRUE
                 WHERE l.invoice_id = :id
                 ORDER BY r.name, o.meal_slot, c.name""")
                .param("id", invoiceId)
                .query((rs, n) -> {
                    int billed = rs.getInt("billed_qty");
                    Integer locked = rs.getObject("locked_qty", Integer.class);
                    Integer received = rs.getObject("received_qty", Integer.class);
                    String by = rs.getString("received_by");
                    OffsetDateTime at = rs.getObject("received_at", OffsetDateTime.class);
                    Integer present = rs.getObject("present", Integer.class);
                    if (frozen) {
                        return new LineView(rs.getLong("id"), rs.getLong("order_id"), rs.getString("centre_name"),
                                rs.getString("route_name"), rs.getString("meal_slot"), rs.getString("status"),
                                billed, locked, received, rs.getInt("payable_qty"), rs.getString("flag"),
                                rs.getInt("rate_paise"), rs.getLong("amount_paise"), by, at, present);
                    }
                    Reconciler.Result r = Reconciler.reconcile(billed, rs.getString("status"), received, locked);
                    return new LineView(rs.getLong("id"), rs.getLong("order_id"), rs.getString("centre_name"),
                            rs.getString("route_name"), rs.getString("meal_slot"), rs.getString("status"),
                            billed, locked, received, r.payableQty(), r.flag().name(),
                            h.ratePerMealPaise(), (long) r.payableQty() * h.ratePerMealPaise(), by, at, present);
                })
                .list();

        List<EventView> events = jdbc.sql("""
                SELECT id, kind, actor_label, note, amount_paise, at FROM invoice_event
                 WHERE invoice_id = :id ORDER BY id""")
                .param("id", invoiceId)
                .query(EventView.class)
                .list();

        long billedMeals = lines.stream().mapToLong(LineView::billedQty).sum();
        long payableMeals = lines.stream().mapToLong(LineView::payableQty).sum();
        long payablePaise = lines.stream().mapToLong(LineView::amountPaise).sum();
        int flagged = (int) lines.stream().filter(l -> !l.flag().equals("OK")).count();
        int unconfirmed = (int) lines.stream().filter(l -> l.flag().equals("NOT_CONFIRMED")).count();
        InvoiceSummary summary = new InvoiceSummary(h.id(), h.vendorId(), h.vendorName(), h.serviceDate(), h.status(),
                lines.size(), billedMeals, payableMeals, flagged, unconfirmed, payablePaise,
                billedMeals * h.ratePerMealPaise(), h.submittedAt(), h.approvedAt(), h.paidAt(), h.paymentRef(),
                h.rejectReason());
        return new InvoiceDetail(summary, lines, events);
    }

    // ---------- ops decisions ----------

    /**
     * Ops approves. Blocked while any delivery is unconfirmed: we do not pay for what
     * we cannot confirm, and we do not silently pay zero for it either. (Ops can resolve an
     * unconfirmed delivery by confirming on the centre's behalf after a call.)
     */
    @Transactional
    public InvoiceDetail approve(Actor ops, long invoiceId) {
        String status = lockStatus(invoiceId);
        if (!status.equals("SUBMITTED")) {
            throw ApiException.conflict("NOT_SUBMITTED", "Only a bill waiting for review can be approved (it is " + status + ")");
        }
        InvoiceDetail live = detail(invoiceId);
        if (live.invoice().unconfirmedLines() > 0) {
            throw ApiException.conflict("UNCONFIRMED_DELIVERIES",
                    live.invoice().unconfirmedLines() + " deliveries are not confirmed by centres yet");
        }
        for (LineView l : live.lines()) {
            jdbc.sql("""
                    UPDATE invoice_line SET payable_qty = :p, rate_paise = :r, amount_paise = :a, flag = :f
                     WHERE id = :id""")
                    .param("p", l.payableQty()).param("r", l.ratePaise()).param("a", l.amountPaise())
                    .param("f", l.flag()).param("id", l.lineId())
                    .update();
        }
        OffsetDateTime now = time.now();
        jdbc.sql("""
                UPDATE invoice SET status = 'APPROVED', approved_at = :now, approved_by = :by,
                       total_payable_paise = :total, reject_reason = NULL
                 WHERE id = :id""")
                .param("now", now).param("by", ops.userId())
                .param("total", live.invoice().payablePaise()).param("id", invoiceId)
                .update();
        long cut = live.invoice().billedPaise() - live.invoice().payablePaise();
        event(invoiceId, "APPROVED", ops.label(),
                live.invoice().flaggedLines() + " lines adjusted" + (cut > 0 ? ", " + cut / 100 + " rupees less than billed" : ""),
                live.invoice().payablePaise(), now);
        changes.bump();
        return detail(invoiceId);
    }

    /** Ops sends the bill back with a reason the kitchen will see. */
    @Transactional
    public InvoiceDetail reject(Actor ops, long invoiceId, String reason) {
        String status = lockStatus(invoiceId);
        if (!status.equals("SUBMITTED")) {
            throw ApiException.conflict("NOT_SUBMITTED", "Only a bill waiting for review can be rejected (it is " + status + ")");
        }
        jdbc.sql("UPDATE invoice SET status = 'REJECTED', reject_reason = :r WHERE id = :id")
                .param("r", reason).param("id", invoiceId).update();
        event(invoiceId, "REJECTED", ops.label(), reason, null, time.now());
        changes.bump();
        return detail(invoiceId);
    }

    /**
     * Finance recorded the bank transfer. Idempotent for the same reference; a different
     * reference on a paid bill, or a reference already used elsewhere, is refused.
     */
    @Transactional
    public InvoiceDetail markPaid(Actor ops, long invoiceId, String paymentRef) {
        String status = lockStatus(invoiceId);
        if (status.equals("PAID")) {
            String existing = jdbc.sql("SELECT payment_ref FROM invoice WHERE id = :id").param("id", invoiceId)
                    .query(String.class).single();
            if (existing.equals(paymentRef)) {
                return detail(invoiceId);
            }
            throw ApiException.conflict("ALREADY_PAID", "Already paid with reference " + existing);
        }
        if (!status.equals("APPROVED")) {
            throw ApiException.conflict("NOT_APPROVED", "Approve the bill before paying it");
        }
        long refUsed = jdbc.sql("SELECT COUNT(*) FROM invoice WHERE payment_ref = :ref").param("ref", paymentRef)
                .query(Long.class).single();
        if (refUsed > 0) {
            throw ApiException.conflict("REF_ALREADY_USED", "Payment reference " + paymentRef + " already settled another bill");
        }
        OffsetDateTime now = time.now();
        jdbc.sql("""
                UPDATE invoice SET status = 'PAID', paid_at = :now, paid_by = :by, payment_ref = :ref WHERE id = :id""")
                .param("now", now).param("by", ops.userId()).param("ref", paymentRef).param("id", invoiceId)
                .update();
        long total = jdbc.sql("SELECT total_payable_paise FROM invoice WHERE id = :id").param("id", invoiceId)
                .query(Long.class).single();
        event(invoiceId, "PAID", ops.label(), "Bank reference " + paymentRef, total, now);
        changes.bump();
        return detail(invoiceId);
    }

    private String lockStatus(long invoiceId) {
        return jdbc.sql("SELECT status FROM invoice WHERE id = :id FOR UPDATE")
                .param("id", invoiceId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Bill"));
    }
}
