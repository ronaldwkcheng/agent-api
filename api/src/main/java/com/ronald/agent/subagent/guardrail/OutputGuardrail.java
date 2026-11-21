package com.ronald.agent.subagent.guardrail;

/**
 * An automated check run on a delegate's output &mdash; PII leaking into a reply, a length or
 * schema rule, a policy classifier. The automated counterpart of
 * {@code com.ronald.agent.subagent.review.HumanReviewer}, minus the person.
 *
 * <p>Implementations must be thread-safe: a {@link GuardrailedSubAgent} placed in a parallel
 * branch is checked concurrently with its siblings.</p>
 */
@FunctionalInterface
public interface OutputGuardrail {

    /**
     * Checks one proposed output.
     *
     * @param check what is being checked, for which step, on which attempt
     * @return the verdict; must not be null
     */
    GuardrailDecision check(OutputGuardrailCheck check);
}
