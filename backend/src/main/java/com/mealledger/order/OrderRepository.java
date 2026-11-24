package com.mealledger.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for single orders. Every write that changes status goes through
 * {@link OrderService}, which holds a row lock while it checks the rules.
 */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lock the row until the transaction ends, so two requests cannot both pass the rule checks. */
    public Optional<OrderRow> findForUpdate(long id) {
        return jdbc.sql("SELECT " + OrderRow.COLUMNS + " FROM meal_order WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(OrderRow::map)
                .optional();
    }

    public Optional<OrderRow> find(long id) {
        return jdbc.sql("SELECT " + OrderRow.COLUMNS + " FROM meal_order WHERE id = :id")
                .param("id", id)
                .query(OrderRow::map)
                .optional();
    }

    public void setRequested(long id, int qty, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE meal_order
                   SET status = 'REQUESTED', requested_qty = :qty, closed_reason = NULL, updated_at = :now
                 WHERE id = :id""")
                .param("id", id).param("qty", qty).param("now", now)
                .update();
    }

    public void setLocked(long id, int lockedQty, OffsetDateTime now) {
        jdbc.sql("UPDATE meal_order SET status = 'LOCKED', locked_qty = :qty, updated_at = :now WHERE id = :id")
                .param("id", id).param("qty", lockedQty).param("now", now)
                .update();
    }

    public void setClosed(long id, boolean beforeLock, String reason, OffsetDateTime now) {
        // Closed before lock: nothing was cooked, so the request becomes zero.
        // Closed after lock: keep locked_qty, because that food was planned (and is now waste).
        jdbc.sql("""
                UPDATE meal_order
                   SET status = 'CLOSED',
                       requested_qty = CASE WHEN :beforeLock THEN 0 ELSE requested_qty END,
                       closed_reason = :reason, updated_at = :now
                 WHERE id = :id""")
                .param("id", id).param("beforeLock", beforeLock).param("reason", reason).param("now", now)
                .update();
    }

    public void setReceived(long id, int qty, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE meal_order
                   SET status = 'RECEIVED', received_qty = :qty, received_at = :now, updated_at = :now
                 WHERE id = :id""")
                .param("id", id).param("qty", qty).param("now", now)
                .update();
    }

    public void insertEvent(long orderId, OrderStatus from, OrderStatus to, Integer qty,
                            Actor actor, String reason, OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO order_event (order_id, from_status, to_status, qty, actor_user_id, actor_label, reason, at)
                VALUES (:orderId, :from, :to, :qty, :actorId, :actorLabel, :reason, :at)""")
                .param("orderId", orderId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("qty", qty)
                .param("actorId", actor.userId())
                .param("actorLabel", actor.label())
                .param("reason", reason)
                .param("at", at)
                .update();
    }

    public List<OrderEventView> events(long orderId) {
        return jdbc.sql("""
                SELECT id, from_status, to_status, qty, actor_label, reason, at
                  FROM order_event WHERE order_id = :orderId ORDER BY id""")
                .param("orderId", orderId)
                .query(OrderEventView.class)
                .list();
    }

    public record OrderEventView(long id, String fromStatus, String toStatus, Integer qty,
                                 String actorLabel, String reason, OffsetDateTime at) {
    }
}
