package com.ronald.agent.workflow;

import com.ronald.agent.subagent.AbstractPromptSubAgent;
import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.Map;

/**
 * An {@link AgenticWorkflow} that repeatedly refines content until it passes evaluation
 * or the maximum number of attempts is reached.
 *
 * <p>Each iteration calls a {@link SubAgent refiner agent} to improve the content, then
 * an {@link EvaluatorAgent} to assess quality against provided criteria.
 * The loop exits early as soon as the evaluator returns {@code PASS}.</p>
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 */
public class IterativeRefinementWorkflow implements AgenticWorkflow<String> {

    private static final Logger log = LoggerFactory.getLogger(IterativeRefinementWorkflow.class);

    public static final String CTX_INPUT    = "input";
    public static final String CTX_CRITERIA = "criteria";
    public static final String CTX_CONTENT  = "content";
    public static final String CTX_FEEDBACK = "feedback";

    private final SubAgent<String> refinerAgent;
    private final SubAgent<EvaluatorAgent.EvaluationResponse> evaluatorAgent;
    private final String initialContent;
    private final String criteria;
    private final int maxAttempts;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private IterativeRefinementWorkflow(Builder builder) {
        this.refinerAgent   = builder.refinerAgent;
        this.evaluatorAgent = builder.evaluatorAgent;
        this.initialContent = builder.initialContent;
        this.criteria       = builder.criteria;
        this.maxAttempts    = builder.maxAttempts;
    }

    /**
     * Creates a new {@link Builder} for constructing an {@code IterativeRefinementWorkflow}.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs the iterative refinement loop.
     * <p>Starting from {@code initialContent} (if provided), the workflow repeatedly refines
     * and evaluates the content until it passes evaluation or {@code maxAttempts} is exhausted.</p>
     *
     * @param input the original user task/request used as context throughout refinement
     * @return the refined content string that either passed evaluation or is the best result
     *         after the maximum number of attempts
     */
    @Override
    public String invoke(String input) {
        String feedback = "Initial draft generation.";
        String content  = initialContent != null ? initialContent : "";

        Map<String, String> context = new HashMap<>();
        context.put(CTX_INPUT,    input);
        context.put(CTX_CRITERIA, criteria);
        context.put(CTX_CONTENT,  content);
        context.put(CTX_FEEDBACK, feedback);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.debug("Attempt {} / {}", attempt, maxAttempts);

            // Generate refined draft and update context so the evaluator sees the latest content
            content = refinerAgent.execute(context);
            context.put(CTX_CONTENT, content);

            // Evaluate the draft
            EvaluatorAgent.EvaluationResponse evaluationResponse;
            try {
                evaluationResponse = evaluatorAgent.execute(context);
            } catch (Exception e) {
                log.warn("Failed to parse evaluation response, assuming FAIL: {}", e.getMessage());
                evaluationResponse = new EvaluatorAgent.EvaluationResponse(
                        EvaluatorAgent.EvaluationStatus.FAIL,
                        "Evaluation parsing failed: " + e.getMessage()
                );
            }

            if (evaluationResponse.evaluationStatus() == EvaluatorAgent.EvaluationStatus.PASS) {
                log.debug("Content passed evaluation on attempt: {}", attempt);
                return content;
            }

            // Propagate evaluator feedback into context for the next refinement iteration
            feedback = evaluationResponse.feedback();
            context.put(CTX_FEEDBACK, feedback);
            log.debug("Evaluation failed, refine again... {}", evaluationResponse);
        }

        log.debug("Reached max retries. Returning last content.");
        return content;
    }

    /**
     * Builder for {@link IterativeRefinementWorkflow}.
     */
    public static class Builder {
        private SubAgent<EvaluatorAgent.EvaluationResponse> evaluatorAgent;
        private String initialContent;
        private SubAgent<String> refinerAgent;
        private String criteria;
        private int maxAttempts = 5;

        /**
         * Sets the {@link ChatClient} used to construct the internal {@link EvaluatorAgent}.
         *
         * @param chatClient the chat client; must not be null
         * @return this builder
         */
        public Builder evaluatorAgent(ChatClient chatClient) {
            this.evaluatorAgent = new EvaluatorAgent(chatClient);
            return this;
        }

        /**
         * Sets the initial content to seed the first refinement iteration.
         * If not set, the refiner will start from an empty string.
         *
         * @param initialContent the starting content, or {@code null} to begin from scratch
         * @return this builder
         */
        public Builder initialContent(String initialContent) {
            this.initialContent = initialContent;
            return this;
        }

        /**
         * Sets the {@link SubAgent} responsible for refining content on each iteration.
         *
         * @param refinerAgent the refiner agent; must not be null
         * @return this builder
         */
        public Builder refinerAgent(SubAgent<String> refinerAgent) {
            this.refinerAgent = refinerAgent;
            return this;
        }

        /**
         * Sets the maximum number of refinement attempts before giving up.
         * Defaults to {@code 5}.
         *
         * @param maxAttempts maximum iterations; must be at least 1
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the evaluation criteria string passed to the evaluator agent.
         *
         * @param criteria the criteria text; must not be null
         * @return this builder
         */
        public Builder criteria(String criteria) {
            this.criteria = criteria;
            return this;
        }

        /**
         * Builds the {@link IterativeRefinementWorkflow} with the configured settings.
         *
         * @return the constructed workflow
         * @throws IllegalStateException    if {@code refinerAgent} or {@code evaluatorAgent} is not set
         * @throws IllegalArgumentException if {@code maxAttempts} is less than 1 or {@code criteria} is null
         */
        public IterativeRefinementWorkflow build() {
            if (refinerAgent == null) {
                throw new IllegalStateException("refinerAgent must be provided");
            }
            if (evaluatorAgent == null) {
                throw new IllegalStateException("evaluatorAgent must be provided");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts can't be less than 1");
            }
            if (criteria == null) {
                throw new IllegalArgumentException("criteria must not be null");
            }
            return new IterativeRefinementWorkflow(this);
        }
    }

    // -------------------------------------------------------------------------
    // Inner class: EvaluatorAgent
    // -------------------------------------------------------------------------

    /**
     * Evaluates generated content against a set of criteria and returns a structured
     * {@link EvaluationResponse} containing a status and actionable feedback.
     */
    private static class EvaluatorAgent extends AbstractPromptSubAgent<EvaluatorAgent.EvaluationResponse> {

        /**
         * Structured result returned by the evaluator.
         *
         * @param evaluationStatus whether the content passes, needs improvement, or fails
         * @param feedback         human-readable explanation and, if applicable, improvement suggestions
         */
        public record EvaluationResponse(EvaluationStatus evaluationStatus, String feedback) {}

        /**
         * Possible outcomes of a content evaluation.
         */
        public enum EvaluationStatus {
            PASS, NEEDS_IMPROVEMENT, FAIL
        }

        EvaluatorAgent(ChatClient chatClient) {
            super(chatClient, EvaluationResponse.class);
        }

        @Override
        public String getOutputKey() {
            return "evaluation";
        }

        @Override
        public String getPromptTemplate() {
            return """
                You are an expert evaluator.
                Evaluate the provided content based on the given evaluation criteria, and provide concise and constructive feedback if applicable.

                The evaluationStatus field must be one of: "PASS", "NEEDS_IMPROVEMENT", "FAIL".
                - "PASS": when all criteria are met with no improvements needed.
                - "NEEDS_IMPROVEMENT": when the content meets some criteria but has room for improvement.
                - "FAIL": when the content does not meet all criteria or is off-topic.

                The feedback field contains the reasoning behind the evaluation. Give suggestions for improvement if the evaluationStatus is not "PASS".

                =====================
                Evaluation criteria:
                {criteria}

                Original task:
                {input}

                Last content:
                {content}

                Feedback:
                {feedback}
                """;
        }
    }
}
