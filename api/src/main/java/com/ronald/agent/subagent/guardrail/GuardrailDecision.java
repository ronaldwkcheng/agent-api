package com.ronald.agent.subagent.guardrail;

import java.util.Objects;

/**
 * A {@link Guardrail}'s verdict on one piece of content &mdash; the same four-outcome shape as
 * {@code com.ronald.agent.subagent.review.ReviewDecision}, but handed down by code instead of a
 * person. Create instances through the static factories; the four outcomes are exhaustive, so a
 * {@code switch} over them needs no default.
 *
 * <ul>
 *   <li>{@link #pass()} &mdash; the content is fine; continue unchanged.</li>
 *   <li>{@link #modify(String)} &mdash; the guardrail's own fix replaces the content (for example,
 *       PII redacted in place); no further model call.</li>
 *   <li>{@link #retry(String)} &mdash; <b>output guardrails only.</b> The delegate runs again,
 *       guided by this feedback. Returning it from an {@link InputGuardrail} is a misconfiguration:
 *       nothing has executed yet to retry.</li>
 *   <li>{@link #block(String)} &mdash; the content is unacceptable; {@link GuardrailViolationException}
 *       propagates and the step does not complete.</li>
 * </ul>
 */
public sealed interface GuardrailDecision {

    /** The content passes the check unchanged. */
    record Pass() implements GuardrailDecision {}

    /** The content is replaced by the guardrail's own corrected version. */
    record Modify(String replacement) implements GuardrailDecision {
        public Modify {
            Objects.requireNonNull(replacement, "replacement must not be null");
        }
    }

    /** The delegate should run again, guided by this feedback. Output guardrails only. */
    record Retry(String feedback) implements GuardrailDecision {
        public Retry {
            if (feedback == null || feedback.isBlank()) {
                throw new IllegalArgumentException("feedback must not be blank: it is the only thing "
                        + "that tells the delegate what to change");
            }
        }
    }

    /** The content is unacceptable and the step must not complete. */
    record Block(String reason) implements GuardrailDecision {
        public Block {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    static GuardrailDecision pass() {
        return new Pass();
    }

    static GuardrailDecision modify(String replacement) {
        return new Modify(replacement);
    }

    static GuardrailDecision retry(String feedback) {
        return new Retry(feedback);
    }

    static GuardrailDecision block(String reason) {
        return new Block(reason);
    }
}
