package com.mealledger.attendance;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.common.ChangeTracker;
import com.mealledger.order.Actor;
import com.mealledger.order.MealSlot;
import com.mealledger.order.OrderRepository;
import com.mealledger.order.OrderRow;

import jakarta.validation.constraints.NotNull;

/**
 * Children present per centre per meal. We take FRAT's count, not raw face scans:
 * FRAT is its own system, and a count per centre per meal is all the meal plan needs.
 */
@Service
public class AttendanceService {

    /** FRAT can resend late data; we accept up to a week back. */
    private static final int MAX_DAYS_LATE = 7;

    private static final String UPSERT = """
            INSERT INTO attendance (centre_id, service_date, meal_slot, present, source, recorded_at)
            VALUES (:centreId, :serviceDate, :mealSlot, :present, :source, :now)
            ON CONFLICT (centre_id, service_date, meal_slot) DO UPDATE
               SET present = EXCLUDED.present, source = EXCLUDED.source,
                   revisions = attendance.revisions + 1, recorded_at = EXCLUDED.recorded_at
             WHERE attendance.present IS DISTINCT FROM EXCLUDED.present
                OR attendance.source IS DISTINCT FROM EXCLUDED.source""";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final OrderRepository orders;
    private final BusinessTime time;
    private final ChangeTracker changes;

    public AttendanceService(JdbcClient jdbc, NamedParameterJdbcTemplate named, OrderRepository orders, BusinessTime time,
                             ChangeTracker changes) {
        this.jdbc = jdbc;
        this.named = named;
        this.orders = orders;
        this.time = time;
        this.changes = changes;
    }

    public record FratEntry(long centreId, @NotNull LocalDate serviceDate, @NotNull MealSlot mealSlot, int present) {
    }

    public record Rejected(int index, String reason) {
    }

    /** accepted = new or changed rows; unchanged = exact resends (idempotent no-ops). */
    public record FratResult(int accepted, int unchanged, List<Rejected> rejected) {
    }

    /** Batch intake from FRAT. Validates every entry; bad entries are reported, good ones still land. */
    @Transactional
    public FratResult recordFrat(List<FratEntry> entries) {
        OffsetDateTime now = time.now();
        LocalDate today = time.today();
        Map<String, Integer> enrolledBySlot = loadServedSlots(entries);

        List<MapSqlParameterSource> batch = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            FratEntry e = entries.get(i);
            Integer enrolled = enrolledBySlot.get(e.centreId() + ":" + e.mealSlot());
            String problem = null;
            if (enrolled == null) {
                problem = "centre does not exist or is not served " + e.mealSlot();
            } else if (e.serviceDate().isAfter(today)) {
                problem = "date is in the future";
            } else if (e.serviceDate().isBefore(today.minusDays(MAX_DAYS_LATE))) {
                problem = "date is more than " + MAX_DAYS_LATE + " days old";
            } else if (e.present() < 0 || e.present() > enrolled * 2) {
                problem = "present " + e.present() + " is not plausible for " + enrolled + " enrolled";
            }
            if (problem != null) {
                rejected.add(new Rejected(i, problem));
                continue;
            }
            batch.add(new MapSqlParameterSource()
                    .addValue("centreId", e.centreId())
                    .addValue("serviceDate", e.serviceDate())
                    .addValue("mealSlot", e.mealSlot().name())
                    .addValue("present", e.present())
                    .addValue("source", "FRAT")
                    .addValue("now", now));
        }

        int accepted = 0;
        if (!batch.isEmpty()) {
            for (int n : named.batchUpdate(UPSERT, batch.toArray(MapSqlParameterSource[]::new))) {
                accepted += Math.max(n, 0);
            }
        }
        if (accepted > 0) {
            changes.bump();
        }
        return new FratResult(accepted, batch.size() - accepted, rejected);
    }

    /** A centre without FRAT enters the head count by hand. FRAT, when present, is the source of truth. */
    @Transactional
    public void recordManual(Actor actor, long orderId, int present) {
        OrderRow o = orders.find(orderId)
                .filter(row -> actor.centreId() != null && row.centreId() == actor.centreId())
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (time.now().isBefore(time.startOf(o.serviceDate()))) {
            throw ApiException.conflict("TOO_EARLY", "Attendance can be entered only on the service day");
        }
        String existingSource = jdbc.sql("""
                SELECT source FROM attendance
                 WHERE centre_id = :c AND service_date = :d AND meal_slot = :s""")
                .param("c", o.centreId()).param("d", o.serviceDate()).param("s", o.mealSlot().name())
                .query(String.class)
                .optional()
                .orElse(null);
        if ("FRAT".equals(existingSource)) {
            throw ApiException.conflict("FRAT_RECORDED", "FRAT already recorded attendance for this meal");
        }
        named.update(UPSERT, new MapSqlParameterSource()
                .addValue("centreId", o.centreId())
                .addValue("serviceDate", o.serviceDate())
                .addValue("mealSlot", o.mealSlot().name())
                .addValue("present", present)
                .addValue("source", "MANUAL")
                .addValue("now", time.now()));
        changes.bump();
    }

    private Map<String, Integer> loadServedSlots(List<FratEntry> entries) {
        List<Long> ids = entries.stream().map(FratEntry::centreId).distinct().toList();
        Map<String, Integer> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT s.centre_id, s.meal_slot, c.enrolled
                  FROM centre_meal_slot s JOIN centre c ON c.id = s.centre_id
                 WHERE c.active AND s.centre_id IN (:ids)""")
                .param("ids", ids)
                .query((rs, n) -> {
                    result.put(rs.getLong("centre_id") + ":" + rs.getString("meal_slot"), rs.getInt("enrolled"));
                    return null;
                })
                .list();
        return result;
    }
}
