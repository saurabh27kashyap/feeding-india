package com.mealledger.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mealledger.common.BusinessTime;
import com.mealledger.feedback.FeedbackService;
import com.mealledger.order.CutoffService;
import com.mealledger.order.OrderGenerationService;

/**
 * Background jobs. Each one is idempotent: running it twice, late, or on two
 * instances gives the same result, so no distributed lock is needed.
 */
@Component
@ConditionalOnProperty(name = "mealledger.scheduling-enabled", havingValue = "true")
public class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    private final CutoffService cutoff;
    private final OrderGenerationService generation;
    private final FeedbackService feedback;
    private final BusinessTime time;

    public Jobs(CutoffService cutoff, OrderGenerationService generation, FeedbackService feedback, BusinessTime time) {
        this.cutoff = cutoff;
        this.generation = generation;
        this.feedback = feedback;
        this.time = time;
    }

    /** Lock orders whose cutoff passed. Edits are also refused by time at write, so this only formalises state. */
    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT15S")
    public void lockDueOrders() {
        run("cutoff", () -> cutoff.lockDue(time.now()));
    }

    /** Create today's and the next service day's orders (with forecasts) if missing. */
    @Scheduled(initialDelayString = "PT3S", fixedDelayString = "PT10M")
    public void generateUpcomingOrders() {
        run("generate", generation::ensureUpcoming);
    }

    /** Tag new feedback notes with the LLM, a small batch at a time. */
    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT30S")
    public void tagFeedback() {
        run("tag-feedback", () -> feedback.tagPending(20));
    }

    private void run(String name, Runnable job) {
        try {
            job.run();
        } catch (RuntimeException e) {
            log.error("Job {} failed", name, e);
        }
    }
}
