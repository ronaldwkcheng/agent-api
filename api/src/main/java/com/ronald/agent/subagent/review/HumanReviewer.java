package com.ronald.agent.subagent.review;

/**
 * The human side of a {@link HumanReviewSubAgent}: shown a proposed output, returns a verdict.
 *
 * <p>This is the seam between the library and whatever channel actually reaches a person &mdash;
 * a console prompt, a Slack approval, a ticket in a review queue. The library never assumes
 * one; the application supplies it, exactly as it supplies the model provider.</p>
 *
 * <p>{@link #review} is called synchronously on the workflow's thread and blocks it until a
 * decision is returned, so an implementation backed by an asynchronous channel should wait
 * (with a timeout of its own choosing) rather than return early.</p>
 *
 * <p><strong>Implementations must be thread-safe.</strong> A reviewed agent placed in a
 * {@code ParallelAgentOrchestrator} branch is reviewed concurrently with its siblings.</p>
 */
@FunctionalInterface
public interface HumanReviewer {

    /**
     * Reviews one proposed output.
     *
     * @param request what is being proposed, by which step, on which attempt
     * @return the verdict; must not be null
     */
    ReviewDecision review(ReviewRequest request);
}
