package com.ronald.agent.workflow;

import com.ronald.agent.subagent.AbstractPromptSubAgent;
import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * An {@link AgenticWorkflow} that implements the Orchestrator-Workers agentic pattern.
 *
 * <p>Unlike {@link ParallelAgentOrchestrator}, whose branches are a fixed list of distinct
 * agents wired at build time, the fan-out here is decided by an LLM at runtime: an
 * <b>orchestrator</b> agent reads the input and breaks it into however many independent
 * {@link Subtask}s it judges necessary (bounded by {@code maxSubtasks}), a single <b>worker</b>
 * agent is then invoked once per subtask concurrently, and a <b>synthesizer</b> agent combines
 * the results into the final typed output.</p>
 *
 * <p>The workflow proceeds in three phases:</p>
 * <ol>
 *   <li><b>Orchestrate</b> — the orchestrator LLM decomposes the task into a {@link Breakdown}
 *       of {@link Subtask}s</li>
 *   <li><b>Work</b>        — the same {@link SubAgent worker} is invoked once per subtask,
 *       concurrently, each bounded by a per-subtask timeout and recovered according to a
 *       {@link BranchFailurePolicy}</li>
 *   <li><b>Synthesize</b>  — a typed synthesizer {@link SubAgent} combines every subtask result
 *       into the final output of type {@code T}</li>
 * </ol>
 *
 * <p>Context keys passed to the worker on each invocation:</p>
 * <ul>
 *   <li>{@code input}      — the original user task</li>
 *   <li>{@code breakdown}  — the full formatted list of subtasks, so a worker can see how its
 *       own piece fits the whole</li>
 *   <li>{@code subtaskId}  — the current subtask number (1-based)</li>
 *   <li>{@code subtask}    — the description of the current subtask</li>
 * </ul>
 *
 * <p>Context keys passed to the synthesizer:</p>
 * <ul>
 *   <li>{@code input}      — the original user task</li>
 *   <li>{@code breakdown}  — the full formatted list of subtasks</li>
 *   <li>{@code results}    — all worker results labelled by subtask number and description</li>
 * </ul>
 *
 * <p>A breakdown longer than {@code maxSubtasks} is handled exactly like
 * {@link PlanAndExecuteWorkflow}'s over-long plan: {@link ExhaustionPolicy#THROW} (the default)
 * throws {@link WorkflowExhaustedException} carrying the full breakdown as its partial result;
 * {@link ExhaustionPolicy#RETURN_PARTIAL} runs only the first {@code maxSubtasks} subtasks and
 * logs what was dropped.</p>
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * AgenticWorkflow<String> workflow = OrchestratorWorkersWorkflow.<String>builder()
 *     .chatClient(chatClient)
 *     .worker(workerAgent)
 *     .synthesizer(synthesizerAgent)
 *     .executor(executor)
 *     .maxSubtasks(6)
 *     .build();
 * String result = workflow.invoke("Write a product announcement covering features, pricing, and FAQ.");
 * }</pre>
 *
 * @param <T> the type of the final synthesized result
 */
public class OrchestratorWorkersWorkflow<T> implements AgenticWorkflow<T> {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorWorkersWorkflow.class);

    /** Context key for the original user task. */
    public static final String CTX_INPUT      = "input";
    /** Context key for the formatted full breakdown of subtasks. */
    public static final String CTX_BREAKDOWN  = "breakdown";
    /** Context key for the current subtask number (1-based). */
    public static final String CTX_SUBTASK_ID = "subtaskId";
    /** Context key for the current subtask description. */
    public static final String CTX_SUBTASK    = "subtask";
    /** Context key for all worker results passed to the synthesizer. */
    public static final String CTX_RESULTS    = "results";

    /** Prefix marking a subtask whose result is missing under {@link BranchFailurePolicy#DEGRADE}. */
    private static final String UNAVAILABLE_PREFIX = "UNAVAILABLE - this subtask did not complete: ";

    private static final String DEFAULT_ORCHESTRATOR_PROMPT_TEMPLATE = """
            You are an expert task orchestrator. Break the following task down into a set of
            independent subtasks that separate specialists can work on in parallel.

            Requirements:
            - Each subtask must be self-contained: a worker sees only its own description, the
              original task, and the full list of subtasks — never another worker's output — so
              do not make one subtask depend on another's result.
            - Produce between 1 and {maxSubtasks} subtasks.
            - Keep each subtask description concise but specific enough to act on alone.

            Task: {input}
            """;

    /**
     * Determines what happens when a single worker invocation fails or times out.
     *
     * <p>Mirrors {@link ParallelAgentOrchestrator.BranchFailurePolicy} in shape and intent, kept
     * as a separate type because the two workflows are independent patterns that should not have
     * to share a public type just because their fan-out mechanics look alike.</p>
     */
    public enum BranchFailurePolicy {
        /**
         * Abort the whole workflow; the failure propagates out of {@link #invoke(String)}.
         * This is the default, and matches the behaviour of a plain {@code allOf(...).join()}.
         */
        FAIL_FAST,
        /**
         * Keep going with the surviving subtasks. The failed one contributes a short failure
         * marker instead of a result, so the synthesizer still receives every subtask and can
         * reason about the gap explicitly.
         */
        DEGRADE
    }

    private final OrchestratorAgent    orchestratorAgent;
    private final SubAgent<String>     worker;
    private final SubAgent<T>          synthesizer;
    private final Executor             executor;
    private final int                  maxSubtasks;
    private final Duration             subtaskTimeout;
    private final BranchFailurePolicy  failurePolicy;
    private final ExhaustionPolicy     exhaustionPolicy;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private OrchestratorWorkersWorkflow(Builder<T> builder) {
        this.orchestratorAgent = new OrchestratorAgent(builder.chatClient, builder.orchestratorPromptTemplate);
        this.worker            = builder.worker;
        this.synthesizer       = builder.synthesizer;
        this.executor          = builder.executor;
        this.maxSubtasks       = builder.maxSubtasks;
        this.subtaskTimeout    = builder.subtaskTimeout;
        this.failurePolicy     = builder.failurePolicy;
        this.exhaustionPolicy  = builder.exhaustionPolicy;
    }

    /**
     * Creates a new {@link Builder} for constructing an {@code OrchestratorWorkersWorkflow}.
     *
     * @param <T> the type returned by the synthesizer agent
     * @return a fresh builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Runs the orchestrator-workers workflow for the given task.
     *
     * <p>First the orchestrator decomposes {@code input} into a {@link Breakdown}. Then the
     * worker is invoked once per {@link Subtask}, concurrently, each bounded by
     * {@link Builder#subtaskTimeout(Duration)} and recovered according to the configured
     * {@link BranchFailurePolicy}. Finally the synthesizer combines every result into the typed
     * return value.</p>
     *
     * @param input the user task description
     * @return the synthesized result of type {@code T}
     * @throws NullPointerException       if {@code input} or {@code attributes} is null
     * @throws IllegalStateException      if the orchestrator produces an empty breakdown
     * @throws WorkflowExhaustedException if the breakdown needs more than {@code maxSubtasks}
     *                                    subtasks and the policy is {@link ExhaustionPolicy#THROW};
     *                                    the full breakdown that could not be executed is
     *                                    available via {@link WorkflowExhaustedException#getPartialResult()}
     * @throws CompletionException        under {@link BranchFailurePolicy#FAIL_FAST}, if any
     *                                    worker invocation fails or exceeds the subtask timeout
     */
    @Override
    public T invoke(String input, Map<String, String> attributes) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        log.debug("orchestrator_workers_start input=\"{}\"", input);

        // ── Phase 1: Orchestrate ─────────────────────────────────────────────
        Map<String, String> orchestratorContext = seeded(attributes, Map.of(
                CTX_INPUT, input,
                "maxSubtasks", String.valueOf(maxSubtasks)
        ));

        Breakdown breakdown = orchestratorAgent.execute(orchestratorContext);

        if (breakdown == null || breakdown.subtasks() == null || breakdown.subtasks().isEmpty()) {
            throw new IllegalStateException("Orchestrator produced an empty breakdown for input: " + input);
        }

        // A breakdown wider than the budget cannot run in full. Silently truncating it would hand
        // the synthesizer a partial set of results and let it present the report as complete.
        List<Subtask> plannedSubtasks = breakdown.subtasks();
        if (plannedSubtasks.size() > maxSubtasks) {
            if (exhaustionPolicy == ExhaustionPolicy.THROW) {
                log.error("breakdown_exceeds_budget planned={} maxSubtasks={} policy=THROW",
                        plannedSubtasks.size(), maxSubtasks);
                throw new WorkflowExhaustedException(
                        "Orchestrator produced " + plannedSubtasks.size() + " subtasks, exceeding "
                                + "maxSubtasks=" + maxSubtasks + "; the task cannot be completed "
                                + "within the configured budget",
                        maxSubtasks, formatBreakdown(plannedSubtasks));
            }
            log.warn("breakdown_truncated planned={} maxSubtasks={} dropped={} policy=RETURN_PARTIAL — "
                            + "the final result will cover only the first {} subtasks",
                    plannedSubtasks.size(), maxSubtasks, plannedSubtasks.size() - maxSubtasks, maxSubtasks);
        }

        List<Subtask> subtasks = plannedSubtasks.stream().limit(maxSubtasks).toList();
        String formattedBreakdown = formatBreakdown(subtasks);
        log.debug("breakdown_created subtasks={}", subtasks.size());

        // ── Phase 2: Work (fan-out / fan-in) ─────────────────────────────────
        // Each subtask is independently bounded and independently recovered, so one slow or
        // failing subtask cannot hang the workflow or discard its siblings' completed work.
        List<CompletableFuture<SubtaskResult>> futures = new ArrayList<>(subtasks.size());
        for (int i = 0; i < subtasks.size(); i++) {
            Subtask subtask = subtasks.get(i);
            String subtaskId = String.valueOf(i + 1);
            Map<String, String> subtaskContext = seeded(attributes, Map.of(
                    CTX_INPUT, input,
                    CTX_BREAKDOWN, formattedBreakdown,
                    CTX_SUBTASK_ID, subtaskId,
                    CTX_SUBTASK, subtask.description()));

            futures.add(withTimeout(
                    CompletableFuture.supplyAsync(() -> executeSubtask(subtaskId, subtask, subtaskContext), executor))
                    .handle((result, error) -> error == null ? result : recoverSubtask(subtaskId, subtask, error)));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.debug("work_complete subtasks={}", subtasks.size());

        StringJoiner allResults = new StringJoiner("\n");
        for (CompletableFuture<SubtaskResult> future : futures) {
            SubtaskResult result = future.join();
            allResults.add("Subtask " + result.subtaskId() + " — " + result.description() + ":\n" + result.value());
        }

        // ── Phase 3: Synthesize ──────────────────────────────────────────────
        Map<String, String> synthesizerContext = seeded(attributes, Map.of(
                CTX_INPUT,     input,
                CTX_BREAKDOWN, formattedBreakdown,
                CTX_RESULTS,   allResults.toString()
        ));

        T result = synthesizer.execute(synthesizerContext);
        log.debug("orchestrator_workers_complete");
        return result;
    }

    /**
     * Executes a single worker invocation synchronously.
     *
     * @param subtaskId the 1-based subtask number, used only for logging and error messages
     * @param subtask   the subtask being worked
     * @param context   the context this invocation receives
     * @return the subtask result holding the id, description, and worker output
     * @throws NullPointerException if the worker returns null
     */
    private SubtaskResult executeSubtask(String subtaskId, Subtask subtask, Map<String, String> context) {
        log.debug("subtask_start id={} description=\"{}\"", subtaskId, subtask.description());

        String result = worker.execute(context);
        Objects.requireNonNull(result, "worker returned null for subtask " + subtaskId);

        log.debug("subtask_complete id={} result=\n{}", subtaskId, result);
        return new SubtaskResult(subtaskId, subtask.description(), result);
    }

    /**
     * Bounds a subtask future by the configured timeout, if one is set.
     *
     * <p>Note that {@link CompletableFuture#orTimeout} completes the <em>future</em>
     * exceptionally but cannot interrupt the in-flight call; the underlying HTTP request
     * runs to completion and its result is discarded. The timeout therefore bounds how long
     * the workflow waits, not how long the agent runs.</p>
     *
     * @param future the subtask future to bound
     * @return the bounded future, or the original future if no timeout is configured
     */
    private CompletableFuture<SubtaskResult> withTimeout(CompletableFuture<SubtaskResult> future) {
        if (subtaskTimeout.isZero()) {
            return future;
        }
        return future.orTimeout(subtaskTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Applies the configured {@link BranchFailurePolicy} to a failed or timed-out subtask.
     *
     * @param subtaskId the 1-based subtask number
     * @param subtask   the subtask that failed
     * @param error     the failure, possibly wrapped in a {@link CompletionException}
     * @return a placeholder {@link SubtaskResult} under {@link BranchFailurePolicy#DEGRADE}
     * @throws CompletionException under {@link BranchFailurePolicy#FAIL_FAST}, always
     */
    private SubtaskResult recoverSubtask(String subtaskId, Subtask subtask, Throwable error) {
        Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                ? error.getCause()
                : error;
        String reason = cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");

        if (failurePolicy == BranchFailurePolicy.FAIL_FAST) {
            log.error("subtask_failed id={} reason={} policy=FAIL_FAST", subtaskId, reason);
            throw new CompletionException("Subtask " + subtaskId + " failed: " + reason, cause);
        }

        log.warn("subtask_degraded id={} reason={} policy=DEGRADE", subtaskId, reason);
        return new SubtaskResult(subtaskId, subtask.description(), UNAVAILABLE_PREFIX + reason);
    }

    /**
     * Merges caller-supplied attributes under a phase's own context entries.
     *
     * <p>The phase entries are applied last and therefore win: an attribute cannot displace the
     * input, the breakdown, or any value this workflow computes. The orchestrator, every worker
     * invocation, and the synthesizer are separate requests, so each is seeded independently —
     * otherwise a worker would carry the conversation identity meant for the synthesizer, or
     * vice versa.</p>
     *
     * @param attributes   the caller's attributes; may be empty
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

    private String formatBreakdown(List<Subtask> subtasks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < subtasks.size(); i++) {
            sb.append(i + 1).append(". ").append(subtasks.get(i).description()).append("\n");
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Public records: Breakdown and Subtask
    // -------------------------------------------------------------------------

    /**
     * A structured breakdown produced by the orchestrator agent.
     *
     * @param subtasks the list of independent subtasks to work in parallel
     */
    public record Breakdown(List<Subtask> subtasks) {}

    /**
     * A single independent subtask within a {@link Breakdown}.
     *
     * @param description a concise, self-contained description of what this subtask should do
     */
    public record Subtask(String description) {}

    /**
     * Holds the result of a single worker invocation.
     *
     * @param subtaskId   the 1-based subtask number
     * @param description the subtask's description, carried along for the synthesizer's prompt
     * @param value       the string output produced by the worker, or a failure marker
     */
    private record SubtaskResult(String subtaskId, String description, String value) {}

    // -------------------------------------------------------------------------
    // Inner class: OrchestratorAgent
    // -------------------------------------------------------------------------

    /**
     * Internal agent that produces a structured {@link Breakdown} from the user's task
     * via Spring AI structured output.
     */
    private static class OrchestratorAgent extends AbstractPromptSubAgent<Breakdown> {

        private final String promptTemplate;

        OrchestratorAgent(ChatClient chatClient, String promptTemplate) {
            super(chatClient, Breakdown.class);
            this.promptTemplate = promptTemplate;
        }

        @Override
        public String getOutputKey() {
            return "breakdown";
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
     * Fluent builder for {@link OrchestratorWorkersWorkflow}.
     *
     * @param <T> the result type of the workflow
     */
    public static final class Builder<T> {

        private static final Duration DEFAULT_SUBTASK_TIMEOUT = Duration.ofSeconds(60);

        private ChatClient        chatClient;
        private SubAgent<String>  worker;
        private SubAgent<T>       synthesizer;
        private Executor          executor;
        private int                maxSubtasks              = 8;
        private String             orchestratorPromptTemplate = DEFAULT_ORCHESTRATOR_PROMPT_TEMPLATE;
        private Duration           subtaskTimeout            = DEFAULT_SUBTASK_TIMEOUT;
        private BranchFailurePolicy failurePolicy            = BranchFailurePolicy.FAIL_FAST;
        private ExhaustionPolicy   exhaustionPolicy          = ExhaustionPolicy.THROW;

        private Builder() {}

        /**
         * Sets the {@link ChatClient} used by the internal orchestrator agent.
         *
         * @param chatClient the chat client; must not be null
         * @return this builder
         */
        public Builder<T> chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        /**
         * Sets the {@link SubAgent} invoked once per subtask. The same instance is invoked
         * concurrently, once per subtask the orchestrator produces.
         * Its prompt template should reference {@code {input}}, {@code {breakdown}},
         * {@code {subtaskId}}, and {@code {subtask}}.
         *
         * @param worker the worker agent; must not be null
         * @return this builder
         */
        public Builder<T> worker(SubAgent<String> worker) {
            this.worker = worker;
            return this;
        }

        /**
         * Sets the {@link SubAgent} that synthesizes all worker results into the final output.
         * Its prompt template should reference {@code {input}}, {@code {breakdown}},
         * and {@code {results}}.
         *
         * @param synthesizer the synthesizer agent; must not be null
         * @return this builder
         */
        public Builder<T> synthesizer(SubAgent<T> synthesizer) {
            this.synthesizer = synthesizer;
            return this;
        }

        /**
         * Sets the {@link Executor} used to run worker invocations concurrently.
         *
         * @param executor the executor; must not be null
         * @return this builder
         */
        public Builder<T> executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        /**
         * Sets the maximum number of subtasks the workflow will run.
         * Defaults to {@code 8}. See {@link #exhaustionPolicy(ExhaustionPolicy)} for what
         * happens when the orchestrator produces more.
         *
         * @param maxSubtasks maximum subtask count; must be at least 1
         * @return this builder
         */
        public Builder<T> maxSubtasks(int maxSubtasks) {
            this.maxSubtasks = maxSubtasks;
            return this;
        }

        /**
         * Overrides the default orchestrator prompt template.
         * The template must contain the {@code {input}} and {@code {maxSubtasks}} placeholders.
         *
         * @param orchestratorPromptTemplate the custom orchestrator prompt template
         * @return this builder
         */
        public Builder<T> orchestratorPromptTemplate(String orchestratorPromptTemplate) {
            this.orchestratorPromptTemplate = orchestratorPromptTemplate;
            return this;
        }

        /**
         * Sets how long to wait for any single worker invocation before treating it as failed.
         * Defaults to 60 seconds. Bounds the wait, not the underlying call — see
         * {@link OrchestratorWorkersWorkflow#withTimeout}.
         *
         * @param subtaskTimeout the per-subtask timeout; {@link Duration#ZERO} waits indefinitely
         * @return this builder
         * @throws NullPointerException     if subtaskTimeout is null
         * @throws IllegalArgumentException if subtaskTimeout is negative
         */
        public Builder<T> subtaskTimeout(Duration subtaskTimeout) {
            Objects.requireNonNull(subtaskTimeout, "subtaskTimeout must not be null");
            if (subtaskTimeout.isNegative()) {
                throw new IllegalArgumentException("subtaskTimeout must not be negative: " + subtaskTimeout);
            }
            this.subtaskTimeout = subtaskTimeout;
            return this;
        }

        /**
         * Waits indefinitely for every worker invocation, restoring the pre-timeout behaviour.
         * Prefer {@link #subtaskTimeout(Duration)} — an unbounded LLM call can hang the workflow.
         *
         * @return this builder
         */
        public Builder<T> noSubtaskTimeout() {
            return subtaskTimeout(Duration.ZERO);
        }

        /**
         * Sets what happens when a worker invocation fails or times out.
         * Defaults to {@link BranchFailurePolicy#FAIL_FAST}.
         *
         * @param failurePolicy the policy to apply to failed subtasks
         * @return this builder
         * @throws NullPointerException if failurePolicy is null
         */
        public Builder<T> failurePolicy(BranchFailurePolicy failurePolicy) {
            this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
            return this;
        }

        /**
         * Sets what happens when the orchestrator produces more than {@code maxSubtasks}
         * subtasks. Defaults to {@link ExhaustionPolicy#THROW}; {@link ExhaustionPolicy#RETURN_PARTIAL}
         * runs the first {@code maxSubtasks} subtasks and logs what was dropped.
         *
         * @param exhaustionPolicy the policy to apply when the breakdown exceeds the budget
         * @return this builder
         * @throws NullPointerException if exhaustionPolicy is null
         */
        public Builder<T> exhaustionPolicy(ExhaustionPolicy exhaustionPolicy) {
            this.exhaustionPolicy = Objects.requireNonNull(exhaustionPolicy, "exhaustionPolicy must not be null");
            return this;
        }

        /**
         * Builds the {@link OrchestratorWorkersWorkflow}.
         *
         * @return the configured workflow
         * @throws NullPointerException     if {@code chatClient}, {@code worker}, {@code synthesizer},
         *                                  or {@code executor} is not set
         * @throws IllegalArgumentException if {@code maxSubtasks} is less than 1
         */
        public OrchestratorWorkersWorkflow<T> build() {
            Objects.requireNonNull(chatClient,  "chatClient must not be null");
            Objects.requireNonNull(worker,      "worker must not be null");
            Objects.requireNonNull(synthesizer, "synthesizer must not be null");
            Objects.requireNonNull(executor,    "executor must not be null");
            if (maxSubtasks < 1) {
                throw new IllegalArgumentException("maxSubtasks must be at least 1");
            }
            return new OrchestratorWorkersWorkflow<>(this);
        }
    }
}
