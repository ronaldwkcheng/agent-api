package com.ronald.agent.subagent.review;

import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.ExhaustionPolicy;
import com.ronald.agent.workflow.WorkflowExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link SubAgent} decorator that puts a human approval gate on another agent's output.
 *
 * <p>The wrapped agent runs, its proposal goes to a {@link HumanReviewer}, and only what the
 * reviewer lets through is returned to the workflow. Because every workflow in this library
 * composes {@code SubAgent}s, wrapping one step adds human-in-the-loop to any pattern without
 * the workflow knowing &mdash; a sequential step, a parallel branch, the plan step executor, a
 * synthesizer or aggregator.</p>
 *
 * <pre>{@code
 * SubAgent<String> reviewed = HumanReviewSubAgent.builder()
 *         .delegate(draftReplyAgent)
 *         .reviewer(request -> askOperator(request))
 *         .maxRevisions(2)
 *         .build();
 * }</pre>
 *
 * <h2>Decisions</h2>
 * <ul>
 *   <li><b>approve</b> &mdash; the proposal is returned unchanged.</li>
 *   <li><b>edit</b> &mdash; the reviewer's text is returned instead; no further model call.</li>
 *   <li><b>revise</b> &mdash; the delegate runs again with {@value #CTX_FEEDBACK} and
 *       {@value #CTX_PREVIOUS_DRAFT} set, and its new proposal is reviewed in turn.</li>
 *   <li><b>reject</b> &mdash; {@link ReviewRejectedException} is thrown and the workflow stops.</li>
 * </ul>
 *
 * <h2>Context keys</h2>
 * <p>The delegate receives a copy of the workflow's context with two keys added. On the first
 * attempt both are the empty string, so a prompt template may reference them unconditionally
 * &mdash; Spring AI's {@code PromptTemplate} fails on a placeholder with no value.</p>
 *
 * <h2>Exhaustion</h2>
 * <p>At most {@code maxRevisions} revisions are run. A {@code revise} beyond that follows the
 * library's {@link ExhaustionPolicy}: {@code THROW} (default) raises
 * {@link WorkflowExhaustedException} carrying the last proposal; {@code RETURN_PARTIAL} returns
 * that last, <em>unapproved</em> proposal &mdash; choose it only if an unapproved result is
 * acceptable downstream.</p>
 *
 * <p>Only {@code String}-returning agents can be wrapped: a human reviews and edits text.</p>
 */
public final class HumanReviewSubAgent implements SubAgent<String> {

    private static final Logger log = LoggerFactory.getLogger(HumanReviewSubAgent.class);

    /** Context key carrying the reviewer's feedback on the previous attempt. */
    public static final String CTX_FEEDBACK = "reviewFeedback";

    /** Context key carrying the proposal the reviewer asked to have revised. */
    public static final String CTX_PREVIOUS_DRAFT = "previousDraft";

    private final SubAgent<String> delegate;
    private final HumanReviewer reviewer;
    private final String stepName;
    private final int maxRevisions;
    private final ExhaustionPolicy exhaustionPolicy;

    private HumanReviewSubAgent(Builder builder) {
        this.delegate = builder.delegate;
        this.reviewer = builder.reviewer;
        this.stepName = builder.stepName;
        this.maxRevisions = builder.maxRevisions;
        this.exhaustionPolicy = builder.exhaustionPolicy;
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the delegate's output key, so wrapping a step does not change where the workflow
     * files its result.
     */
    @Override
    public String getOutputKey() {
        return delegate.getOutputKey();
    }

    /**
     * Runs the delegate and loops until the reviewer approves, edits or rejects, or the revision
     * budget is spent.
     *
     * @throws ReviewRejectedException    if the reviewer rejects a proposal
     * @throws WorkflowExhaustedException if revisions exceed {@code maxRevisions} under
     *                                    {@link ExhaustionPolicy#THROW}
     */
    @Override
    public String execute(Map<String, String> context) {
        Map<String, String> attemptContext = new HashMap<>(context);
        attemptContext.put(CTX_FEEDBACK, "");
        attemptContext.put(CTX_PREVIOUS_DRAFT, "");

        for (int attempt = 1; ; attempt++) {
            String proposal = Objects.requireNonNull(delegate.execute(Collections.unmodifiableMap(attemptContext)),
                    "delegate of step '" + stepName + "' returned null");

            ReviewDecision decision = Objects.requireNonNull(
                    reviewer.review(new ReviewRequest(stepName, proposal, attemptContext, attempt)),
                    "reviewer returned null for step '" + stepName + "'");
            log.info("review_decision step={} attempt={} decision={}",
                    stepName, attempt, decision.getClass().getSimpleName());

            switch (decision) {
                case ReviewDecision.Approve a -> {
                    return proposal;
                }
                case ReviewDecision.Edit e -> {
                    return e.replacement();
                }
                case ReviewDecision.Reject r ->
                        throw new ReviewRejectedException(stepName, r.reason(), proposal);
                case ReviewDecision.Revise r -> {
                    int revisionsDone = attempt - 1;
                    if (revisionsDone >= maxRevisions) {
                        return exhausted(proposal);
                    }
                    attemptContext.put(CTX_FEEDBACK, r.feedback());
                    attemptContext.put(CTX_PREVIOUS_DRAFT, proposal);
                }
            }
        }
    }

    private String exhausted(String lastProposal) {
        String message = "Step '" + stepName + "' was still not approved after "
                + maxRevisions + " revision(s)";
        if (exhaustionPolicy == ExhaustionPolicy.RETURN_PARTIAL) {
            log.warn("{}; returning the last unapproved proposal", message);
            return lastProposal;
        }
        throw new WorkflowExhaustedException(message, maxRevisions, lastProposal);
    }

    /**
     * Builder for {@link HumanReviewSubAgent}. {@code delegate} and {@code reviewer} are required.
     */
    public static final class Builder {

        private SubAgent<String> delegate;
        private HumanReviewer reviewer;
        private String stepName;
        private int maxRevisions = 3;
        private ExhaustionPolicy exhaustionPolicy = ExhaustionPolicy.THROW;

        private Builder() {}

        /**
         * @param delegate the agent whose output is reviewed
         * @return this builder
         */
        public Builder delegate(SubAgent<String> delegate) {
            this.delegate = delegate;
            return this;
        }

        /**
         * @param reviewer the human-facing channel that decides on each proposal
         * @return this builder
         */
        public Builder reviewer(HumanReviewer reviewer) {
            this.reviewer = reviewer;
            return this;
        }

        /**
         * Sets the label shown to the reviewer. Defaults to the delegate's output key, or its
         * class name when it has none.
         *
         * @param stepName a human-readable step label
         * @return this builder
         */
        public Builder stepName(String stepName) {
            this.stepName = stepName;
            return this;
        }

        /**
         * Sets how many times a reviewer may send a proposal back for revision. Zero means the
         * first proposal must be approved, edited or rejected. Defaults to 3.
         *
         * @param maxRevisions the revision budget; must be non-negative
         * @return this builder
         */
        public Builder maxRevisions(int maxRevisions) {
            this.maxRevisions = maxRevisions;
            return this;
        }

        /**
         * Sets what happens when the revision budget runs out. Defaults to
         * {@link ExhaustionPolicy#THROW}.
         *
         * @param exhaustionPolicy the policy
         * @return this builder
         */
        public Builder exhaustionPolicy(ExhaustionPolicy exhaustionPolicy) {
            this.exhaustionPolicy = exhaustionPolicy;
            return this;
        }

        /**
         * @return the configured agent
         * @throws NullPointerException     if delegate, reviewer or exhaustionPolicy is null
         * @throws IllegalArgumentException if maxRevisions is negative
         */
        public HumanReviewSubAgent build() {
            Objects.requireNonNull(delegate, "delegate must not be null");
            Objects.requireNonNull(reviewer, "reviewer must not be null");
            Objects.requireNonNull(exhaustionPolicy, "exhaustionPolicy must not be null");
            if (maxRevisions < 0) {
                throw new IllegalArgumentException("maxRevisions must be >= 0, was " + maxRevisions);
            }
            if (stepName == null || stepName.isBlank()) {
                stepName = delegate.getOutputKey() != null
                        ? delegate.getOutputKey()
                        : delegate.getClass().getSimpleName();
            }
            return new HumanReviewSubAgent(this);
        }
    }
}
