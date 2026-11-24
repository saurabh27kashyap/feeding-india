package com.mealledger.feedback;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the LLM's reply into tags. The reply is untrusted text:
 * we keep only known tags, and treat anything unparseable as a failure (to retry later).
 */
public final class TagParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TagParser() {
    }

    public static Optional<List<FeedbackTag>> parse(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(reply.substring(start, end + 1));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        JsonNode tags = root.get("tags");
        if (tags == null || !tags.isArray()) {
            return Optional.empty();
        }
        Set<FeedbackTag> result = EnumSet.noneOf(FeedbackTag.class);
        for (JsonNode t : tags) {
            if (t.isString()) {
                try {
                    result.add(FeedbackTag.valueOf(t.asString().trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown tag: dropped on purpose.
                }
            }
        }
        if (result.size() > 1) {
            result.remove(FeedbackTag.NO_ISSUE); // "no issue" plus a complaint is still a complaint
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(result));
    }
}
