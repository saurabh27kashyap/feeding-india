package com.mealledger.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mealledger.attendance.AttendanceService;
import com.mealledger.auth.AppPrincipal;
import com.mealledger.common.BusinessTime;
import com.mealledger.feedback.FeedbackService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Partner App, centre side: what the school or Anganwadi worker sees and does. */
@RestController
@RequestMapping("/api/centre")
public class CentreController {

    private final JdbcClient jdbc;
    private final BusinessTime time;
    private final OrderService orders;
    private final AttendanceService attendance;
    private final FeedbackService feedback;

    public CentreController(JdbcClient jdbc, BusinessTime time, OrderService orders,
                            AttendanceService attendance, FeedbackService feedback) {
        this.jdbc = jdbc;
        this.time = time;
        this.orders = orders;
        this.attendance = attendance;
        this.feedback = feedback;
    }

    public record CentreInfo(long id, String name, String type, String district, int enrolled,
                             String routeName, String vendorName) {
    }

    public record OrderLine(long id, LocalDate serviceDate, String mealSlot, String status, int suggestedQty,
                     String suggestionBasis, int requestedQty, Integer lockedQty, Integer receivedQty,
                     OffsetDateTime cutoffAt, OffsetDateTime confirmBy, String closedReason,
                     Integer present, String attendanceSource, boolean hasFeedback) {
    }

    public record CentreOrder(OrderLine order, boolean canEdit, boolean canReceive) {
    }

    public record CentreHome(CentreInfo centre, OffsetDateTime now, LocalDate today, LocalDate nextDay,
                             List<CentreOrder> orders) {
    }

    /** Today's and the next service day's meals for the logged-in centre only. */
    @GetMapping("/orders")
    public CentreHome home(@AuthenticationPrincipal AppPrincipal me) {
        OffsetDateTime now = time.now();
        LocalDate today = time.today();
        LocalDate next = time.nextOperatingDay(today);

        CentreInfo centre = jdbc.sql("""
                SELECT c.id, c.name, c.type, c.district, c.enrolled, r.name AS route_name, v.name AS vendor_name
                  FROM centre c JOIN route r ON r.id = c.route_id JOIN vendor v ON v.id = r.vendor_id
                 WHERE c.id = :id""")
                .param("id", me.centreId())
                .query(CentreInfo.class)
                .single();

        List<CentreOrder> lines = jdbc.sql("""
                SELECT o.id, o.service_date, o.meal_slot, o.status, o.suggested_qty, o.suggestion_basis,
                       o.requested_qty, o.locked_qty, o.received_qty, o.cutoff_at, o.confirm_by, o.closed_reason,
                       a.present, a.source AS attendance_source, (f.id IS NOT NULL) AS has_feedback
                  FROM meal_order o
                  LEFT JOIN attendance a ON a.centre_id = o.centre_id AND a.service_date = o.service_date
                                        AND a.meal_slot = o.meal_slot
                  LEFT JOIN feedback f ON f.order_id = o.id
                 WHERE o.centre_id = :centreId AND o.service_date IN (:today, :next)
                 ORDER BY o.service_date, o.meal_slot""")
                .param("centreId", me.centreId())
                .param("today", today)
                .param("next", next)
                .query(OrderLine.class)
                .list()
                .stream()
                .map(o -> new CentreOrder(o, canEdit(o, now), canReceive(o, now)))
                .toList();

        return new CentreHome(centre, now, today, next, lines);
    }

    private boolean canEdit(OrderLine o, OffsetDateTime now) {
        boolean open = o.status().equals("REQUESTED") || (o.status().equals("CLOSED") && o.lockedQty() == null);
        return open && now.isBefore(o.cutoffAt());
    }

    private boolean canReceive(OrderLine o, OffsetDateTime now) {
        boolean waiting = o.status().equals("LOCKED") || o.status().equals("DISPATCHED");
        return waiting && !now.isBefore(time.startOf(o.serviceDate()));
    }

    public record CountRequest(@Min(1) @Max(1000) int qty) {
    }

    public record CloseRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record ReceiveRequest(@Min(0) @Max(5000) int qty, Boolean confirmLarge) {
    }

    public record ReceiveResponse(OrderRow order, boolean replayed) {
    }

    public record AttendanceRequest(@Min(0) @Max(5000) int present) {
    }

    public record FeedbackRequest(@NotNull Boolean quantityOk, @NotNull Boolean qualityOk, @NotNull Boolean onTime,
                                  @Size(max = 500) String note) {
    }

    @PutMapping("/orders/{id}")
    public OrderRow changeCount(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                @Valid @RequestBody CountRequest req) {
        return orders.changeCount(Actor.of(me), id, req.qty());
    }

    @PostMapping("/orders/{id}/close")
    public OrderRow close(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                          @Valid @RequestBody CloseRequest req) {
        return orders.close(Actor.of(me), id, req.reason().trim());
    }

    @PostMapping("/orders/{id}/receive")
    public ReceiveResponse receive(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                   @Valid @RequestBody ReceiveRequest req) {
        OrderService.ReceiveResult r = orders.receive(Actor.of(me), id, req.qty(), Boolean.TRUE.equals(req.confirmLarge()));
        return new ReceiveResponse(r.order(), r.replayed());
    }

    /** Manual head count, for centres that do not use FRAT. */
    @PostMapping("/orders/{id}/attendance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attendance(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                           @Valid @RequestBody AttendanceRequest req) {
        attendance.recordManual(Actor.of(me), id, req.present());
    }

    @PostMapping("/orders/{id}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public void feedback(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                         @Valid @RequestBody FeedbackRequest req) {
        feedback.submit(Actor.of(me), id, req.quantityOk(), req.qualityOk(), req.onTime(), req.note());
    }
}
