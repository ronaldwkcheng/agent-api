package com.ronald.agent.workflow;

import com.ronald.agent.subagent.AbstractPromptSubAgent;
import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link AgenticWorkflow} that implements the Plan-and-Execute agentic pattern.
 *
 * <p>The workflow proceeds in three phases:</p>
 * <ol>
 *   <li><b>Plan</b>    — a planner LLM decomposes the task into an ordered {@link Plan}
 *       consisting of discrete {@link Step}s</li>
 *   <li><b>Execute</b> — each step is executed in sequence by a {@link SubAgent stepExecutor},
 *       with all preceding results available as context</li>
 *   <li><b>Synthesize</b> — a typed synthesizer {@link SubAgent} combines the accumulated
 *       step results into the final output of type {@code T}</li>
 * </ol>
 *
 * <p>Context keys passed to the step executor on each iteration:</p>
 * <ul>
 *   <li>{@code input}           — the original user task</li>
 *   <li>{@code plan}            — the full formatted plan</li>
 *   <li>{@code stepId}          — the current step number (1-based)</li>
 *   <li>{@code stepDescription} — the description of the current step</li>
 *   <li>{@code previousResults} — concatenated results of all preceding steps</li>
 * </ul>
 *
 * <p>Context keys passed to the synthesizer:</p>
 * <ul>
 *   <li>{@code input}       — the original user task</li>
 *   <li>{@code plan}        — the full formatted plan</li>
 *   <li>{@code stepResults} — all step results labelled by step number and description</li>
 * </ul>
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * AgenticWorkflow<String> workflow = PlanAndExecuteWorkflow.<String>builder()
 *     .chatClient(chatClient)
 *     .stepExecutor(executorAgent)
 *     .synthesizer(synthesizerAgent)
 *     .maxSteps(8)
 *     .build();
 * String result = workflow.invoke("Research and summarise the key trends in renewable energy.");
 * }</pre>
 *
 * @param <T> the type of the final synthesized result
 */
public class PlanAndExecuteWorkflow<T> implements AgenticWorkflow<T> {

    private static final Logger log = LoggerFactory.getLogger(PlanAndExecuteWorkflow.class);

    /** Context key for the original user task. */
    public static final String CTX_INPUT            = "input";
    /** Context key for the formatted full plan. */
    public static final String CTX_PLAN             = "plan";
    /** Context key for the current step number (1-based). */
    public static final String CTX_STEP_ID          = "stepId";
    /** Context key for the current step description. */
    public static final String CTX_STEP_DESCRIPTION = "stepDescription";
    /** Context key for the concatenated results of all preceding steps. */
    public static final String CTX_PREVIOUS_RESULTS = "previousResults";
    /** Context key for all step results passed to the synthesizer. */
    public static final String CTX_STEP_RESULTS     = "stepResults";

    private static final String DEFAULT_PLANNER_PROMPT_TEMPLATE = """
            You are an expert task planner. Decompose the following task into a clear, ordered list of concrete steps.

            Requirements:
            - Each step must be independently executable and build on the results of previous steps.
            - Produce between 2 and {maxSteps} steps total.
            - Keep each step description concise but specific enough to act on.

            Task: {input}
            """;

    private final PlannerAgent     plannerAgent;
    private final SubAgent<String> stepExecutor;
    private final SubAgent<T>      synthesizer;
    private final int              maxSteps;
    private final ExhaustionPolicy exhaustionPolicy;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private PlanAndExecuteWorkflow(Builder<T> builder) {
        this.plannerAgent     = new PlannerAgent(builder.chatClient, builder.plannerPromptTemplate);
        this.stepExecutor     = builder.stepExecutor;
        this.synthesizer      = builder.synthesizer;
        this.maxSteps         = builder.maxSteps;
        this.exhaustionPolicy = builder.exhaustionPolicy;
    }

    /**
     * Creates a new {@link Builder} for constructing a {@code PlanAndExecuteWorkflow}.
     *
     * @param <T> the type returned by the synthesizer agent
     * @return a fresh builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Runs the plan-and-execute workflow for the given task.
     *
     * <p>First the planner decomposes {@code input} into a {@link Plan}. Then each
     * {@link Step} is executed in order by the step executor, with accumulated context
     * passed forward. Finally, the synthesizer combines all step results into the
     * typed return value.</p>
     *
     * @param input the user task description
     * @return the synthesized result of type {@code T}
     * @throws NullPointerException       if {@code input} is null
     * @throws IllegalStateException      if the planner produces an empty plan
     * @throws WorkflowExhaustedException if the plan needs more than {@code maxSteps} steps and
     *                                    the policy is {@link ExhaustionPolicy#THROW}; the full
     *                                    plan that could not be executed is available via
     *                                    {@link WorkflowExhaustedException#getPartialResult()}
     */
    @Override
    public T invoke(String input) {
        Objects.requireNonNull(input, "input must not be null");
        log.debug("plan_and_execute_start input=\"{}\"", input);

        // ── Phase 1: Plan ────────────────────────────────────────────────────
        Map<String, String> plannerContext = Map.of(
                CTX_INPUT, input,
                "maxSteps", String.valueOf(maxSteps)
        );

        Plan plan = plannerAgent.execute(plannerContext);

        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            throw new IllegalStateException("Planner produced an empty plan for input: " + input);
        }

        // A plan longer than the budget cannot be executed in full. Truncating it silently would
        // hand the synthesizer a partial plan and let it present the report as complete.
        List<Step> plannedSteps = plan.steps();
        if (plannedSteps.size() > maxSteps) {
            if (exhaustionPolicy == ExhaustionPolicy.THROW) {
                log.error("plan_exceeds_budget planned={} maxSteps={} policy=THROW",
                        plannedSteps.size(), maxSteps);
                throw new WorkflowExhaustedException(
                        "Planner produced " + plannedSteps.size() + " steps, exceeding maxSteps="
                                + maxSteps + "; the task cannot be completed within the configured budget",
                        maxSteps, formatPlan(plannedSteps));
            }
            log.warn("plan_truncated planned={} maxSteps={} dropped={} policy=RETURN_PARTIAL — "
                            + "the final report will cover only the first {} steps",
                    plannedSteps.size(), maxSteps, plannedSteps.size() - maxSteps, maxSteps);
        }

        List<Step> steps = plannedSteps.stream().limit(maxSteps).toList();
        String formattedPlan = formatPlan(steps);
        log.debug("plan_created steps={}", steps.size());

        // ── Phase 2: Execute ─────────────────────────────────────────────────
        StringBuilder previousResults = new StringBuilder();
        StringBuilder allStepResults  = new StringBuilder();

        for (int i = 0; i < steps.size(); i++) {
            Step   step   = steps.get(i);
            String stepId = String.valueOf(i + 1);
            log.debug("plan_execute_step stepId={} description=\"{}\"", stepId, step.description());

            Map<String, String> stepContext = Map.of(
                    CTX_INPUT, input,
                    CTX_PLAN, formattedPlan,
                    CTX_STEP_ID, stepId,
                    CTX_STEP_DESCRIPTION, step.description(),
                    CTX_PREVIOUS_RESULTS, previousResults.toString());

            String result = stepExecutor.execute(stepContext);
            log.debug("plan_step_complete stepId={}", stepId);

            previousResults.append("Step ").append(stepId).append(": ").append(result).append("\n\n");
            allStepResults.append("Step ").append(stepId)
                          .append(" — ").append(step.description()).append(":\n")
                          .append(result).append("\n\n");
        }

        // ── Phase 3: Synthesize ──────────────────────────────────────────────
        Map<String, String> synthesizerContext = Map.of(
                CTX_INPUT,        input,
                CTX_PLAN,         formattedPlan,
                CTX_STEP_RESULTS, allStepResults.toString()
        );

        T result = synthesizer.execute(synthesizerContext);
        log.debug("plan_and_execute_complete");
        return result;
    }

    private String formatPlan(List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i).description()).append("\n");
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Public records: Plan and Step
    // -------------------------------------------------------------------------

    /**
     * A structured plan produced by the planner agent.
     *
     * @param steps the ordered list of steps to execute
     */
    public record Plan(List<Step> steps) {}

    /**
     * A single executable step within a {@link Plan}.
     *
     * @param description a concise, actionable description of what this step should do
     */
    public record Step(String description) {}

    // -------------------------------------------------------------------------
    // Inner class: PlannerAgent
    // -------------------------------------------------------------------------

    /**
     * Internal agent that produces a structured {@link Plan} from the user's task
     * via Spring AI structured output.
     */
    private static class PlannerAgent extends AbstractPromptSubAgent<Plan> {

        private final String promptTemplate;

        PlannerAgent(ChatClient chatClient, String promptTemplate) {
            super(chatClient, Plan.class);
            this.promptTemplate = promptTemplate;
        }

        @Override
        public String getOutputKey() {
            return "plan";
        }

        @Override
        public String getPromptTemplate() {
            return promptTemplate;
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link PlanAndExecuteWorkflow}.
     *
     * @param <T> the result type of the workflow
     */
    public static final class Builder<T> {

        private ChatClient         chatClient;
        private SubAgent<String>   stepExecutor;
        private SubAgent<T>        synthesizer;
        private int                maxSteps             = 10;
        private String             plannerPromptTemplate = DEFAULT_PLANNER_PROMPT_TEMPLATE;
        private ExhaustionPolicy   exhaustionPolicy      = ExhaustionPolicy.THROW;

        private Builder() {}

        /**
         * Sets what happens when the planner produces more steps than {@code maxSteps}.
         * Defaults to {@link ExhaustionPolicy#THROW}; {@link ExhaustionPolicy#RETURN_PARTIAL}
         * executes the first {@code maxSteps} steps and logs what was dropped.
         *
         * @param exhaustionPolicy the policy to apply when the plan exceeds the budget
         * @return this builder
         * @throws NullPointerException if exhaustionPolicy is null
         */
        public Builder<T> exhaustionPolicy(ExhaustionPolicy exhaustionPolicy) {
            this.exhaustionPolicy = Objects.requireNonNull(exhaustionPolicy, "exhaustionPolicy must not be null");
            return this;
        }

        /**
         * Sets the {@link ChatClient} used by the internal planner agent.
         *
         * @param chatClient the chat client; must not be null
         * @return this builder
         */
        public Builder<T> chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        /**
         * Sets the {@link SubAgent} that executes each step of the plan.
         * Its prompt template should reference {@code {input}}, {@code {plan}},
         * {@code {stepId}}, {@code {stepDescription}}, and {@code {previousResults}}.
         *
         * @param stepExecutor the step-executor agent; must not be null
         * @return this builder
         */
        public Builder<T> stepExecutor(SubAgent<String> stepExecutor) {
            this.stepExecutor = stepExecutor;
            return this;
        }

        /**
         * Sets the {@link SubAgent} that synthesizes all step results into the final output.
         * Its prompt template should reference {@code {input}}, {@code {plan}},
         * and {@code {stepResults}}.
         *
         * @param synthesizer the synthesizer agent; must not be null
         * @return this builder
         */
        public Builder<T> synthesizer(SubAgent<T> synthesizer) {
            this.synthesizer = synthesizer;
            return this;
        }

        /**
         * Sets the maximum number of steps the workflow will execute.
         * If the planner produces more steps, the excess are silently truncated.
         * Defaults to {@code 10}.
         *
         * @param maxSteps maximum step count; must be at least 1
         * @return this builder
         */
        public Builder<T> maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        /**
         * Overrides the default planner prompt template.
         * The template must contain the {@code {input}} and {@code {maxSteps}} placeholders.
         *
         * @param plannerPromptTemplate the custom planner prompt template
         * @return this builder
         */
        public Builder<T> plannerPromptTemplate(String plannerPromptTemplate) {
            this.plannerPromptTemplate = plannerPromptTemplate;
            return this;
        }

        /**
         * Builds the {@link PlanAndExecuteWorkflow}.
         *
         * @return the configured workflow
         * @throws NullPointerException  if {@code chatClient}, {@code stepExecutor},
         *                               or {@code synthesizer} is not set
         * @throws IllegalArgumentException if {@code maxSteps} is less than 1
         */
        public PlanAndExecuteWorkflow<T> build() {
            Objects.requireNonNull(chatClient,    "chatClient must not be null");
            Objects.requireNonNull(stepExecutor,  "stepExecutor must not be null");
            Objects.requireNonNull(synthesizer,   "synthesizer must not be null");
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps must be at least 1");
            }
            return new PlanAndExecuteWorkflow<>(this);
        }
    }
}