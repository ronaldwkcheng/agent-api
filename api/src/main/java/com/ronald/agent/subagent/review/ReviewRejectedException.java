package com.ronald.agent.subagent.review;

/**
 * Thrown when a {@link HumanReviewer} rejects a proposal outright.
 *
 * <p>Unchecked, and deliberately not caught by any workflow: a rejection means a person decided
 * the run must not continue, so it propagates out of {@code invoke} and no later step executes.
 * The rejected proposal is preserved on {@link #getRejectedProposal()}.</p>
 */
public class ReviewRejectedException extends RuntimeException {

    private final String stepName;
    private final transient String rejectedProposal;

    /**
     * @param stepName         the label of the step whose output was rejected
     * @param reason           the reviewer's stated reason
     * @param rejectedProposal the proposal that was rejected
     */
    public ReviewRejectedException(String stepName, String reason, String rejectedProposal) {
        super("Human reviewer rejected step '" + stepName + "': " + reason);
        this.stepName = stepName;
        this.rejectedProposal = rejectedProposal;
    }

    /**
     * @return the label of the step whose output was rejected
     */
    public String getStepName() {
        return stepName;
    }

    /**
     * @return the proposal the reviewer rejected
     */
    public String getRejectedProposal() {
        return rejectedProposal;
    }
}
