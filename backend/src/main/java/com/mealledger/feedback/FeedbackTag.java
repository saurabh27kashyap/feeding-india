package com.mealledger.feedback;

/** The only tags the LLM may produce. Anything else it returns is dropped. */
public enum FeedbackTag {
    /** Too little (or too much) food. */
    QUANTITY,
    /** Cooking quality: watery, undercooked, burnt, cold. */
    QUALITY,
    /** Food arrived late. */
    LATE,
    /** Dirty, hair, insects, bad smell, spoiled. */
    HYGIENE,
    /** Children did not like it or did not finish it. */
    TASTE,
    /** Praise, or nothing wrong. */
    NO_ISSUE,
    /** A real problem that fits none of the above. */
    OTHER
}
