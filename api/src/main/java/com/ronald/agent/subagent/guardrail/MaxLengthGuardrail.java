package com.ronald.agent.subagent.guardrail;

import java.util.Objects;

/**
 * A ready-made {@link OutputGuardrail} that bounds how long an output may be.
 *
 * <p>Within the limit it passes the output through unchanged. Over the limit the default
 * ({@link OnViolation#RETRY}) asks {@link GuardrailedSubAgent} for a retry, with feedback the
 * delegate's prompt can surface via {@value GuardrailedSubAgent#CTX_FEEDBACK} &mdash; a
 * self-correction loop with no separate evaluator agent, the same shape
 * {@code IterativeRefinementWorkflow} uses for content that must pass a check before it is
 * returned. {@link OnViolation#BLOCK} rejects an over-long output outright instead.</p>
 */
public final class MaxLengthGuardrail implements OutputGuardrail {

    /** What happens when the output is over the limit. */
    public enum OnViolation {
        /** Ask for a retry, with the overage in the feedback. */
        RETRY,
        /** Block via {@link GuardrailDecision#block(String)}. */
        BLOCK
    }

    private final int maxChars;
    private final OnViolation onViolation;

    /**
     * Creates a guardrail that retries an over-long output.
     *
     * @param maxChars the maximum allowed length, in characters; must be positive
     */
    public MaxLengthGuardrail(int maxChars) {
        this(maxChars, OnViolation.RETRY);
    }

    /**
     * @param maxChars    the maximum allowed length, in characters; must be positive
     * @param onViolation what to do when the output is over the limit
     */
    public MaxLengthGuardrail(int maxChars, OnViolation onViolation) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive, was " + maxChars);
        }
        this.maxChars = maxChars;
        this.onViolation = Objects.requireNonNull(onViolation, "onViolation must not be null");
    }

    @Override
    public GuardrailDecision check(OutputGuardrailCheck check) {
        String output = check.output();
        if (output.length() <= maxChars) {
            return GuardrailDecision.pass();
        }
        String reason = "output is " + output.length() + " characters, over the " + maxChars + "-character limit";
        if (onViolation == OnViolation.BLOCK) {
            return GuardrailDecision.block(reason);
        }
        return GuardrailDecision.retry(reason + "; shorten it to at most " + maxChars + " characters");
    }
}
