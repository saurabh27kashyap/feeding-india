package com.mealledger.feedback;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.common.ChangeTracker;
import com.mealledger.config.MealLedgerProperties;
import com.mealledger.order.Actor;
import com.mealledger.order.OrderRepository;
import com.mealledger.order.OrderRow;
import com.mealledger.order.OrderStatus;

/**
 * Centre feedback after each meal: three taps (quantity, quality, on time) as in the
 * real partner app, plus an optional free-text note that the LLM tags later.
 *
 * Feedback is saved first and tagged in the background, so an LLM outage never
 * blocks or loses a centre's feedback.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final JdbcClient jdbc;
    private final OrderRepository orders;
    private final BusinessTime time;
    private final LlmTagger tagger;
    private final int maxAttempts;
    private final ChangeTracker changes;

    public FeedbackService(JdbcClient jdbc, OrderRepository orders, BusinessTime time, LlmTagger tagger,
                           MealLedgerProperties props, ChangeTracker changes) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.time = time;
        this.tagger = tagger;
        this.maxAttempts = props.llm().maxAttempts();
        this.changes = changes;
    }

    @Transactional
    public void submit(Actor actor, long orderId, boolean quantityOk, boolean qualityOk, boolean onTime, String note) {
        OrderRow o = orders.find(orderId)
                .filter(row -> actor.centreId() != null && row.centreId() == actor.centreId())
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (o.status() != OrderStatus.RECEIVED) {
            throw ApiException.conflict("NOT_RECEIVED", "Feedback can be given after the food is received");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        int inserted = jdbc.sql("""
                INSERT INTO feedback (order_id, quantity_ok, quality_ok, on_time, note, tag_status, created_at)
                VALUES (:o, :qty, :qual, :time, :note, :status, :now)
                ON CONFLICT (order_id) DO NOTHING""")
                .param("o", orderId).param("qty", quantityOk).param("qual", qualityOk).param("time", onTime)
                .param("note", cleanNote).param("status", cleanNote == null ? "NONE" : "PENDING")
                .param("now", time.now())
                .update();
        if (inserted == 0) {
            throw ApiException.conflict("FEEDBACK_EXISTS", "Feedback for this meal was already given");
        }
        changes.bump();
    }

    /**
     * Tag a batch of pending notes. The LLM call happens outside any transaction:
     * no database connection is held while waiting on the network.
     */
    public int tagPending(int batchSize) {
        if (!tagger.enabled()) {
            return 0;
        }
        record Pending(long id, String note, int tagAttempts) {
        }
        List<Pending> pending = jdbc.sql("""
                SELECT id, note, tag_attempts FROM feedback
                 WHERE tag_status = 'PENDING' ORDER BY id LIMIT :n""")
                .param("n", batchSize)
                .query((rs, n) -> new Pending(rs.getLong("id"), rs.getString("note"), rs.getInt("tag_attempts")))
                .list();

        int tagged = 0;
        for (Pending p : pending) {
            Optional<List<FeedbackTag>> tags = tagger.tag(p.note());
            if (tags.isPresent()) {
                String csv = tags.get().stream().map(Enum::name).collect(Collectors.joining(","));
                jdbc.sql("""
                        UPDATE feedback SET tags = STRING_TO_ARRAY(:csv, ','), tag_status = 'TAGGED',
                               tag_attempts = tag_attempts + 1
                         WHERE id = :id AND tag_status = 'PENDING'""")
                        .param("csv", csv).param("id", p.id())
                        .update();
                tagged++;
            } else {
                boolean giveUp = p.tagAttempts() + 1 >= maxAttempts;
                jdbc.sql("""
                        UPDATE feedback SET tag_attempts = tag_attempts + 1, tag_status = :status
                         WHERE id = :id AND tag_status = 'PENDING'""")
                        .param("status", giveUp ? "FAILED" : "PENDING").param("id", p.id())
                        .update();
                if (giveUp) {
                    log.warn("Gave up tagging feedback {} after {} attempts", p.id(), maxAttempts);
                }
            }
        }
        return tagged;
    }
}
