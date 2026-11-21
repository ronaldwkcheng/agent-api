package com.ronald.agent.workflow;

import com.ronald.agent.subagent.AbstractPromptSubAgent;
import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
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
 * <p>By default ({@link ReplanMode#NONE}) the plan from phase 1 is fixed: every step executes in
 * the order the planner first gave. {@link Builder#replanMode(ReplanMode) replanMode} relaxes
 * that:</p>
 * <ul>
 *   <li>{@link ReplanMode#AFTER_EACH_STEP} — after every step completes, the planner is asked
 *       again, given every result so far, for what should happen next. An empty response means
 *       the task is already done.</li>
 *   <li>{@link ReplanMode#ON_FAILURE} — the original plan runs as given; only when a step throws
 *       is the planner asked again, given every result so far including the failure, for how to
 *       proceed. The failed step still contributes a {@code FAILED - ...} result rather than
 *       aborting the workflow.</li>
 * </ul>
 *
 * <p>Either mode still honours {@code maxSteps} as a total budget across every plan and replan,
 * and {@code exhaustionPolicy} for what happens if that budget runs out before the task is judged
 * done — see {@link #invoke(String, Map)}.</p>
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

    private static final String DEFAULT_REPLAN_PROMPT_TEMPLATE = """
            You are an expert task planner continuing a task that is already in progress.

            Original task: {input}

            Steps completed so far, and their results:
            {stepResults}

            Decide what should happen next:
            - If the task is already fully accomplished by the results above, respond with an
              empty list of steps.
            - Otherwise, give the next step or steps still needed, in order.

            Produce at most {maxSteps} further steps.
            """;

    /**
     * How {@link #invoke(String, Map)} decides whether to keep following the original plan or
     * ask the planner again partway through execution.
     */
    public enum ReplanMode {
        /** The plan from phase 1 is fixed; every step runs in the order first planned. */
        NONE,
        /** The planner is asked again after every step, given the results so far. */
        AFTER_EACH_STEP,
        /** The plan runs as given; the planner is asked again only when a step throws. */
        ON_FAILURE
    }

    private final PlannerAgent     plannerAgent;
    private final PlannerAgent     replanAgent;
    private final SubAgent<String> stepExecutor;
    private final SubAgent<T>      synthesizer;
    private final int              maxSteps;
    private final ExhaustionPolicy exhaustionPolicy;
    private final ReplanMode       replanMode;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private PlanAndExecuteWorkflow(Builder<T> builder) {
        this.plannerAgent     = new PlannerAgent(builder.chatClient, builder.plannerPromptTemplate);
        this.replanAgent      = new PlannerAgent(builder.chatClient, builder.replanPromptTemplate);
        this.stepExecutor     = builder.stepExecutor;
        this.synthesizer      = builder.synthesizer;
        this.maxSteps         = builder.maxSteps;
        this.exhaustionPolicy = builder.exhaustionPolicy;
        this.replanMode       = builder.replanMode;
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
     * <p>Under {@link ReplanMode#NONE} (the default) that first plan is exactly what runs.
     * {@link ReplanMode#AFTER_EACH_STEP} and {@link ReplanMode#ON_FAILURE} can extend it mid-run —
     * see the class documentation — but {@code maxSteps} always remains the total budget across
     * every plan and replan combined, and exhausting it is handled exactly like an over-long
     * initial plan: {@code THROW} (the default) carries every result produced so far as the
     * partial result, {@code RETURN_PARTIAL} synthesizes over them instead.</p>
     *
     * @param input the user task description
     * @return the synthesized result of type {@code T}
     * @throws NullPointerException       if {@code input} is null
     * @throws IllegalStateException      if the planner produces an empty plan
     * @throws WorkflowExhaustedException if the task is not judged done within {@code maxSteps}
     *                                    total step executions and the policy is
     *                                    {@link ExhaustionPolicy#THROW}; every result produced so
     *                                    far is available via
     *                                    {@link WorkflowExhaustedException#getPartialResult()}
     */
    @Override
    public T invoke(String input, Map<String, String> attributes) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        log.debug("plan_and_execute_start input=\"{}\"", input);

        // ── Phase 1: Plan ────────────────────────────────────────────────────
        Map<String, String> plannerContext = seeded(attributes, Map.of(
                CTX_INPUT, input,
                "maxSteps", String.valueOf(maxSteps)
        ));

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

        List<Step> initialSteps = plannedSteps.stream().limit(maxSteps).toList();
        String formattedPlan = formatPlan(initialSteps);
        Deque<Step> queue = new ArrayDeque<>(initialSteps);
        log.debug("plan_created steps={} replanMode={}", queue.size(), replanMode);

        // ── Phase 2: Execute (with optional re-planning) ─────────────────────
        StringBuilder previousResults = new StringBuilder();
        StringBuilder allStepResults  = new StringBuilder();
        int executedCount = 0;
        boolean exhausted = false;

        while (!queue.isEmpty()) {
            if (executedCount >= maxSteps) {
                exhausted = true;
                break;
            }

            Step   step   = queue.pollFirst();
            String stepId = String.valueOf(executedCount + 1);
            log.debug("plan_execute_step stepId={} description=\"{}\"", stepId, step.description());

            Map<String, String> stepContext = seeded(attributes, Map.of(
                    CTX_INPUT, input,
                    CTX_PLAN, formattedPlan,
                    CTX_STEP_ID, stepId,
                    CTX_STEP_DESCRIPTION, step.description(),
                    CTX_PREVIOUS_RESULTS, previousResults.toString()));

            String  result;
            boolean failed = false;
            if (replanMode == ReplanMode.ON_FAILURE) {
                try {
                    result = stepExecutor.execute(stepContext);
                } catch (RuntimeException e) {
                    String reason = e.getClass().getSimpleName()
                            + (e.getMessage() != null ? ": " + e.getMessage() : "");
                    log.warn("plan_step_failed stepId={} reason={} policy=ON_FAILURE — replanning",
                            stepId, reason);
                    result = "FAILED - " + reason;
                    failed = true;
                }
            } else {
                result = stepExecutor.execute(stepContext);
            }
            executedCount++;
            log.debug("plan_step_complete stepId={} failed={}", stepId, failed);

            previousResults.append("Step ").append(stepId).append(": ").append(result).append("\n\n");
            allStepResults.append("Step ").append(stepId)
                          .append(" — ").append(step.description()).append(":\n")
                          .append(result).append("\n\n");

            boolean shouldReplan = replanMode == ReplanMode.AFTER_EACH_STEP
                    || (replanMode == ReplanMode.ON_FAILURE && failed);
            if (shouldReplan) {
                queue.clear();

                // Always ask, even exactly at the budget boundary: the answer decides whether
                // the loop ends because the task is done or because the budget ran out with real
                // work still pending — skipping the question would have to guess which.
                Map<String, String> replanContext = seeded(attributes, Map.of(
                        CTX_INPUT,        input,
                        CTX_STEP_RESULTS, allStepResults.toString(),
                        "maxSteps",       String.valueOf(maxSteps - executedCount)));

                Plan replan = replanAgent.execute(replanContext);
                if (replan != null && replan.steps() != null && !replan.steps().isEmpty()) {
                    queue.addAll(replan.steps());
                    log.debug("plan_replanned afterStepId={} newSteps={}", stepId, queue.size());
                } else {
                    log.debug("plan_replanned afterStepId={} — planner reports the task done", stepId);
                }
            }
        }

        if (exhausted) {
            if (exhaustionPolicy == ExhaustionPolicy.THROW) {
                log.error("plan_and_execute_exhausted executed={} maxSteps={} policy=THROW",
                        executedCount, maxSteps);
                throw new WorkflowExhaustedException(
                        "The task was not judged done within " + maxSteps + " total step executions",
                        maxSteps, allStepResults.toString());
            }
            log.warn("plan_and_execute_exhausted executed={} maxSteps={} policy=RETURN_PARTIAL — "
                    + "synthesizing over the results produced so far", executedCount, maxSteps);
        }

        // ── Phase 3: Synthesize ──────────────────────────────────────────────
        Map<String, String> synthesizerContext = seeded(attributes, Map.of(
                CTX_INPUT,        input,
                CTX_PLAN,         formattedPlan,
                CTX_STEP_RESULTS, allStepResults.toString()
        ));

        T result = synthesizer.execute(synthesizerContext);
        log.debug("plan_and_execute_complete");
        return result;
    }

    /**
     * Merges caller-supplied attributes under a phase's own context entries.
     *
     * <p>The phase entries are applied last and therefore win: an attribute cannot displace
     * the input, the plan, or any value this workflow computes. All three phases are separate
     * requests, so each is seeded independently &mdash; otherwise the planner would carry the
     * conversation identity and the synthesizer would not.</p>
     *
     * @param attributes  the caller's attributes; may be empty
     * @param phaseEntries the entries this phase contributes; take precedence on collision
     * @return an unmodifiable merged context
     */
    private static Map<String, String> seeded(Map<String, String> attributes,
                                              Map<String, String> phaseEntries) {
        if (attributes.isEmpty()) {
            return phaseEntries;
        }
        Map<String, String> merged = new HashMap<>(attributes);
        merged.putAll(phaseEntries);
        return Collections.unmodifiableMap(merged);
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
        private String             replanPromptTemplate  = DEFAULT_REPLAN_PROMPT_TEMPLATE;
        private ExhaustionPolicy   exhaustionPolicy      = ExhaustionPolicy.THROW;
        private ReplanMode        replanMode            = ReplanMode.NONE;

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
         * Sets whether, and when, the planner is asked again partway through execution.
         * Defaults to {@link ReplanMode#NONE} — the first plan is fixed.
         *
         * @param replanMode the re-planning mode
         * @return this builder
         * @throws NullPointerException if replanMode is null
         */
        public Builder<T> replanMode(ReplanMode replanMode) {
            this.replanMode = Objects.requireNonNull(replanMode, "replanMode must not be null");
            return this;
        }

        /**
         * Overrides the default replan prompt template, used only when {@link #replanMode} is
         * not {@link ReplanMode#NONE}. The template must contain the {@code {input}},
         * {@code {stepResults}} and {@code {maxSteps}} placeholders — {@code {maxSteps}} here
         * renders as the <em>remaining</em> budget, not the original total.
         *
         * @param replanPromptTemplate the custom replan prompt template
         * @return this builder
         */
        public Builder<T> replanPromptTemplate(String replanPromptTemplate) {
            this.replanPromptTemplate = replanPromptTemplate;
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