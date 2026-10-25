package com.ronald.agent.subagent.guardrail;

import java.util.Objects;

/**
 * A {@link Guardrail}'s verdict on one piece of text. Create instances through the static
 * factories; the four outcomes are exhaustive, so a {@code switch} over them needs no default.
 *
 * <p>The shape deliberately mirrors {@code ReviewDecision}, its human counterpart:</p>
 *
 * <table>
 *   <caption>Guardrail decisions and their human-review equivalents</caption>
 *   <tr><th>Guardrail</th><th>Human review</th><th>Effect</th></tr>
 *   <tr><td>{@link #pass()}</td><td>approve</td><td>the text continues unchanged</td></tr>
 *   <tr><td>{@link #rewrite(String)}</td><td>edit</td><td>the guardrail's text replaces it &mdash; redaction, say</td></tr>
 *   <tr><td>{@link #retry(String)}</td><td>revise</td><td>the wrapped agent runs again with the feedback; output stage only</td></tr>
 *   <tr><td>{@link #block(String)}</td><td>reject</td><td>the workflow aborts with {@link GuardrailViolationException}</td></tr>
 * </table>
 */
public sealed interface GuardrailDecision {

    /** The text complies as is. */
    record Pass() implements GuardrailDecision {}

    /** The text is replaced, for instance with personal data redacted. */
    record Rewrite(String replacement) implements GuardrailDecision {
        public Rewrite {
            Objects.requireNonNull(replacement, "replacement must not be null");
        }
    }

    /** The wrapped agent should produce its output again, guided by this feedback. */
    record Retry(String feedback) implements GuardrailDecision {
        public Retry {
            if (feedback == null || feedback.isBlank()) {
                throw new IllegalArgumentException("feedback must not be blank: it is the only thing "
                        + "that tells the agent what to change");
            }
        }
    }

    /** The text is unacceptable and the workflow must stop. */
    record Block(String reason) implements GuardrailDecision {
        public Block {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    static GuardrailDecision pass() {
        return new Pass();
    }

    static GuardrailDecision rewrite(String replacement) {
        return new Rewrite(replacement);
    }

    static GuardrailDecision retry(String feedback) {
        return new Retry(feedback);
    }

    static GuardrailDecision block(String reason) {
        return new Block(reason);
    }
}
