package com.ronald.agent.workflow;

/**
 * Thrown when a bounded {@link AgenticWorkflow} exhausts its step or attempt budget without
 * reaching a completed result, and its {@link ExhaustionPolicy} is {@link ExhaustionPolicy#THROW}.
 *
 * <p>Extends {@link IllegalStateException} so that callers written against the earlier
 * {@code ReActWorkflow} contract keep working unchanged.</p>
 *
 * <p>Whatever the workflow had produced when it ran out of budget is preserved on
 * {@link #getPartialResult()}, so throwing never destroys work:</p>
 *
 * <pre>{@code
 * try {
 *     return workflow.invoke(task);
 * } catch (WorkflowExhaustedException e) {
 *     log.warn("gave up after {} rounds", e.getLimit());
 *     return e.getPartialResult();   // may be null
 * }
 * }</pre>
 */
public class WorkflowExhaustedException extends IllegalStateException {

    private final int limit;
    private final transient String partialResult;

    /**
     * @param message       description of what ran out and where
     * @param limit         the configured budget that was exhausted
     * @param partialResult the best-effort output produced before giving up, or null if none
     */
    public WorkflowExhaustedException(String message, int limit, String partialResult) {
        super(message);
        this.limit = limit;
        this.partialResult = partialResult;
    }

    /**
     * Returns the configured budget that was exhausted — {@code maxSteps} or {@code maxAttempts},
     * depending on the workflow.
     *
     * @return the budget that was exhausted
     */
    public int getLimit() {
        return limit;
    }

    /**
     * Returns the best-effort output the workflow had produced when it gave up.
     *
     * @return the partial result, or {@code null} if the workflow produced nothing usable
     */
    public String getPartialResult() {
        return partialResult;
    }
}
