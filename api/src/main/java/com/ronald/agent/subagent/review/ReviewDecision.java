package com.ronald.agent.subagent.review;

import java.util.Objects;

/**
 * A {@link HumanReviewer}'s verdict on one proposal. Create instances through the static
 * factories; the four outcomes are exhaustive, so a {@code switch} over them needs no default.
 *
 * <ul>
 *   <li>{@link #approve()} &mdash; the proposal passes through unchanged.</li>
 *   <li>{@link #edit(String)} &mdash; the human's text replaces the proposal; no further model call.</li>
 *   <li>{@link #revise(String)} &mdash; the wrapped agent runs again with the feedback in context.</li>
 *   <li>{@link #reject(String)} &mdash; the workflow aborts with {@link ReviewRejectedException}.</li>
 * </ul>
 */
public sealed interface ReviewDecision {

    /** The proposal is accepted as is. */
    record Approve() implements ReviewDecision {}

    /** The proposal is replaced by text the human wrote. */
    record Edit(String replacement) implements ReviewDecision {
        public Edit {
            Objects.requireNonNull(replacement, "replacement must not be null");
        }
    }

    /** The wrapped agent should try again, guided by this feedback. */
    record Revise(String feedback) implements ReviewDecision {
        public Revise {
            if (feedback == null || feedback.isBlank()) {
                throw new IllegalArgumentException("feedback must not be blank: it is the only thing "
                        + "that tells the agent what to change");
            }
        }
    }

    /** The proposal is unacceptable and the workflow must stop. */
    record Reject(String reason) implements ReviewDecision {
        public Reject {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    static ReviewDecision approve() {
        return new Approve();
    }

    static ReviewDecision edit(String replacement) {
        return new Edit(replacement);
    }

    static ReviewDecision revise(String feedback) {
        return new Revise(feedback);
    }

    static ReviewDecision reject(String reason) {
        return new Reject(reason);
    }
}
