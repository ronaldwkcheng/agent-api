package com.ronald.agent.subagent.guardrail;

/**
 * An automated check run on a delegate's input before it executes &mdash; PII in the request, a
 * policy violation, anything worth catching before paying for a model call.
 *
 * <p>Implementations must be thread-safe: a {@link GuardrailedSubAgent} placed in a parallel
 * branch is checked concurrently with its siblings.</p>
 *
 * <p>{@link GuardrailDecision.Retry} has no meaning here &mdash; the delegate has not run yet
 * &mdash; and {@link GuardrailedSubAgent} treats returning it as a misconfiguration.</p>
 */
@FunctionalInterface
public interface InputGuardrail {

    /**
     * Checks one piece of input.
     *
     * @param check what is being checked, for which step
     * @return the verdict; must not be null
     */
    GuardrailDecision check(InputGuardrailCheck check);
}
