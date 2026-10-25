package com.ronald.agent.subagent.guardrail;

import java.util.Objects;

/**
 * One automated check run by a {@link GuardrailSubAgent} on the text going into, or coming out
 * of, the agent it wraps &mdash; a PII scan, a length limit, a schema check, a policy judged by a
 * model.
 *
 * <p>It is the automated counterpart of {@code HumanReviewer}: shown a piece of text, it returns a
 * {@link GuardrailDecision}. Ready-made checks live in {@link Guardrails} and
 * {@link PromptGuardrail}; anything else is a lambda.</p>
 *
 * <pre>{@code
 * Guardrail noSql = Guardrail.named("no-sql", request ->
 *         request.content().contains("DROP TABLE")
 *                 ? GuardrailDecision.block("looks like SQL")
 *                 : GuardrailDecision.pass());
 * }</pre>
 *
 * <p><strong>Implementations must be thread-safe.</strong> A guarded agent placed in a
 * {@code ParallelAgentOrchestrator} branch is checked concurrently with its siblings.</p>
 */
@FunctionalInterface
public interface Guardrail {

    /**
     * Checks one piece of text.
     *
     * @param request the text, which side of the agent it is on, and the context
     * @return the verdict; must not be null. {@link GuardrailDecision#retry} is only valid for
     *         {@link GuardrailStage#OUTPUT} &mdash; an input cannot be regenerated.
     */
    GuardrailDecision check(GuardrailRequest request);

    /**
     * Returns the label used in logs and in {@link GuardrailViolationException}. Defaults to the
     * class's simple name, or {@code "guardrail"} for a lambda; use {@link #named} to label one.
     *
     * @return the guardrail's name
     */
    default String name() {
        String simpleName = getClass().getSimpleName();
        return getClass().isHidden() || simpleName.isEmpty() ? "guardrail" : simpleName;
    }

    /**
     * Gives a guardrail &mdash; typically a lambda &mdash; a readable name.
     *
     * @param name      the label to report
     * @param guardrail the check to run
     * @return a guardrail that delegates to {@code guardrail} and reports {@code name}
     */
    static Guardrail named(String name, Guardrail guardrail) {
        Objects.requireNonNull(guardrail, "guardrail must not be null");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new Guardrail() {
            @Override
            public GuardrailDecision check(GuardrailRequest request) {
                return guardrail.check(request);
            }

            @Override
            public String name() {
                return name;
            }
        };
    }
}
