package com.mealledger.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.mealledger.config.MealLedgerProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures the LLM tagger on 30 hand-labelled notes (Hindi, English, Hinglish, one injection attempt).
 * Runs only with a key:  GROQ_API_KEY=... ./mvnw test -Dtest=FeedbackTaggerEval
 *
 * Metric: a note counts as correct if the predicted tag set equals the label set exactly.
 * Per-tag hits are printed too, because "LATE + QUANTITY" vs "LATE" is a partial, not a total, miss.
 */
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class FeedbackTaggerEval {

    @Test
    void tagsHandLabelledNotes() throws Exception {
        String model = System.getenv().getOrDefault("GROQ_MODEL", "openai/gpt-oss-20b");
        LlmTagger tagger = new LlmTagger(new MealLedgerProperties("Asia/Kolkata", "20:00", "09:30", "13:30", "06:30", "11:00", 5, 120,
                "MONDAY", "k", false,
                new MealLedgerProperties.Llm("https://api.groq.com/openai/v1", System.getenv("GROQ_API_KEY"), model, 30, 3)));

        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/feedback-eval.json")) {
            cases = JsonMapper.builder().build().readTree(in);
        }

        int exact = 0, total = 0, labelled = 0, found = 0;
        for (JsonNode c : cases) {
            total++;
            Set<FeedbackTag> expected = EnumSet.noneOf(FeedbackTag.class);
            c.get("tags").forEach(t -> expected.add(FeedbackTag.valueOf(t.asString())));
            List<FeedbackTag> got = tagger.tag(c.get("note").asString()).orElse(List.of());
            Set<FeedbackTag> predicted = got.isEmpty() ? EnumSet.noneOf(FeedbackTag.class) : EnumSet.copyOf(got);

            boolean ok = predicted.equals(expected);
            exact += ok ? 1 : 0;
            labelled += expected.size();
            found += (int) expected.stream().filter(predicted::contains).count();
            System.out.printf("%s  %-60s expected=%s got=%s%n", ok ? "OK  " : "MISS", c.get("note").asString(), expected, predicted);
        }
        double exactRate = 100.0 * exact / total;
        double tagRecall = 100.0 * found / labelled;
        System.out.printf("%nModel %s: exact match %d/%d (%.0f%%), tag recall %.0f%%%n", model, exact, total, exactRate, tagRecall);

        assertThat(exactRate).as("exact-match rate").isGreaterThanOrEqualTo(70.0);
    }
}
