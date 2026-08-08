package com.ronald.agent.workflow;

/**
 * Determines what a bounded {@link AgenticWorkflow} does when it exhausts its step or
 * attempt budget without reaching a completed result.
 *
 * <p>Every bounded workflow in this package honours this policy, so callers can reason
 * about exhaustion uniformly across patterns rather than learning each one's convention.</p>
 */
public enum ExhaustionPolicy {

    /**
     * Throw a {@link WorkflowExhaustedException}. This is the default: exceeding the budget
     * means the workflow did not do what was asked, and silently returning an incomplete
     * result invites callers to treat it as a finished one.
     *
     * <p>Nothing is lost by throwing — the exception carries whatever partial output was
     * produced via {@link WorkflowExhaustedException#getPartialResult()}.</p>
     */
    THROW,

    /**
     * Return the best-effort partial result instead of throwing, logging a warning.
     * Appropriate when a partial result is genuinely useful on its own — a refinement draft
     * that never quite passed evaluation, for instance — and the caller has no better
     * recourse than to use it.
     */
    RETURN_PARTIAL
}
