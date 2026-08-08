package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * An {@link AgenticWorkflow} that executes multiple {@link SubAgent}s concurrently (fan-out),
 * collects their results into a shared context (fan-in), and delegates the final synthesis
 * to a typed aggregator agent.
 *
 * <p>Every branch is bounded by a per-branch timeout (60 seconds by default) and recovered
 * according to a {@link BranchFailurePolicy}, so a single slow or failing agent cannot hang
 * the workflow indefinitely. By default a failed branch aborts the workflow
 * ({@link BranchFailurePolicy#FAIL_FAST}); configure {@link BranchFailurePolicy#DEGRADE} to
 * aggregate whatever the surviving branches produced instead.</p>
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 *
 * @param <T> the return type produced by the aggregator agent
 */
public class ParallelAgentOrchestrator<T> implements AgenticWorkflow<T> {

    private static final Logger log = LoggerFactory.getLogger(ParallelAgentOrchestrator.class);

    private static final String CTX_INPUT = "input";

    /**
     * Determines what happens when a single fan-out branch fails or times out.
     */
    public enum BranchFailurePolicy {
        /**
         * Abort the whole workflow; the failure propagates out of {@link #invoke(String)}.
         * This is the default, and matches the behaviour of a plain {@code allOf(...).join()}.
         */
        FAIL_FAST,
        /**
         * Keep going with the surviving branches. The failed branch contributes a short
         * failure marker instead of a result, so the aggregator still receives every key
         * and can reason about the gap explicitly.
         */
        DEGRADE
    }

    /** Prefix marking a branch whose result is missing under {@link BranchFailurePolicy#DEGRADE}. */
    private static final String UNAVAILABLE_PREFIX = "UNAVAILABLE - this analysis did not complete: ";

    private final List<SubAgent<String>> subAgents;
    private final SubAgent<T> aggregator; // Strongly typed to T
    private final Executor executor;
    private final String reportsKey;
    private final Duration branchTimeout;
    private final BranchFailurePolicy failurePolicy;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private ParallelAgentOrchestrator(Builder<T> builder) {
        this.subAgents     = List.copyOf(builder.subAgents);
        this.aggregator    = builder.aggregator;
        this.executor      = builder.executor;
        this.reportsKey    = builder.reportsKey;
        this.branchTimeout = builder.branchTimeout;
        this.failurePolicy = builder.failurePolicy;
    }

    /**
     * Creates a new {@link Builder} for constructing a {@code ParallelAgentOrchestrator}.
     *
     * @param <T> the type returned by the aggregator agent
     * @return a fresh builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Invokes the parallel orchestration workflow.
     * Executes all sub-agents concurrently, collects their results, and passes them to the aggregator agent.
     * The aggregator's output is returned, potentially deserialized into the specified output type.
     *
     * <p>Each branch is bounded by {@link Builder#branchTimeout(Duration)} and, if it fails or
     * times out, handled according to the configured {@link BranchFailurePolicy}.</p>
     *
     * @param input the input string to process
     * @return the aggregated result from the aggregator agent
     * @throws NullPointerException if input is null
     * @throws IllegalStateException if any sub-agent or aggregator returns null
     * @throws CompletionException   under {@link BranchFailurePolicy#FAIL_FAST}, if any branch
     *                               fails or exceeds the branch timeout
     */
    @Override
    public T invoke(String input) {
        Objects.requireNonNull(input, "input must not be null");
        log.info("fan_out_start subAgents={} inputLength={} timeout={} policy={}",
                subAgents.size(), input.length(), branchTimeout, failurePolicy);

        Map<String, String> fanOutContext = Map.of(CTX_INPUT, input);

        // ── Fan-out ──────────────────────────────────────────────────────────
        // Each branch is independently bounded and independently recovered, so one slow or
        // failing agent cannot hang the workflow or discard its siblings' completed work.
        List<CompletableFuture<BranchResult>> futures = subAgents.stream()
                .map(agent -> withTimeout(
                        CompletableFuture.supplyAsync(() -> executeBranch(agent, fanOutContext), executor))
                        .handle((result, error) -> error == null ? result : recoverBranch(agent, error)))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("fan_out_complete subAgents={}", subAgents.size());

        // ── Fan-in: build aggregator context ─────────────────────────────────
        Map<String, String> aggregatorContext = new HashMap<>();
        aggregatorContext.put(CTX_INPUT, input);

        StringJoiner reportsJoiner = new StringJoiner("\n");
        for (CompletableFuture<BranchResult> future : futures) {
            BranchResult result = future.join();
            aggregatorContext.put(result.outputKey(), result.value());
            reportsJoiner.add(result.outputKey().toUpperCase() + ": " + result.value());
            log.debug("fan_in outputKey={} valueLength={}", result.outputKey(), result.value().length());
        }
        aggregatorContext.put(reportsKey, reportsJoiner.toString());

        // ── Aggregator ───────────────────────────────────────────────────────
        // Clean, polymorphic execution. No hacky prompt injection!
        return aggregator.execute(Collections.unmodifiableMap(aggregatorContext));
    }

    /**
     * Executes a single parallel branch synchronously.
     * Wraps the agent's output in a {@link BranchResult} keyed by the agent's output key.
     *
     * @param agent   the sub-agent to execute
     * @param context the read-only fan-out context containing the input
     * @return the branch result holding the output key and value
     * @throws NullPointerException if the agent returns null
     */
    private BranchResult executeBranch(SubAgent<String> agent, Map<String, String> context) {
        String agentName = agent.getClass().getSimpleName();
        log.debug("branch_start agent={} outputKey={}", agentName, agent.getOutputKey());

        String result = agent.execute(context);
        Objects.requireNonNull(result, "ParallelSubAgent returned null");

        log.debug("branch_complete agent={} outputKey={} result=\n{}", agentName, agent.getOutputKey(), result);
        return new BranchResult(agent.getOutputKey(), result);
    }

    /**
     * Bounds a branch future by the configured timeout, if one is set.
     *
     * <p>Note that {@link CompletableFuture#orTimeout} completes the <em>future</em>
     * exceptionally but cannot interrupt the in-flight call; the underlying HTTP request
     * runs to completion and its result is discarded. The timeout therefore bounds how long
     * the workflow waits, not how long the agent runs.</p>
     *
     * @param future the branch future to bound
     * @return the bounded future, or the original future if no timeout is configured
     */
    private CompletableFuture<BranchResult> withTimeout(CompletableFuture<BranchResult> future) {
        if (branchTimeout.isZero()) {
            return future;
        }
        return future.orTimeout(branchTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Applies the configured {@link BranchFailurePolicy} to a failed or timed-out branch.
     *
     * @param agent the sub-agent whose branch failed
     * @param error the failure, possibly wrapped in a {@link CompletionException}
     * @return a placeholder {@link BranchResult} under {@link BranchFailurePolicy#DEGRADE}
     * @throws CompletionException under {@link BranchFailurePolicy#FAIL_FAST}, always
     */
    private BranchResult recoverBranch(SubAgent<String> agent, Throwable error) {
        Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                ? error.getCause()
                : error;
        String agentName = agent.getClass().getSimpleName();
        String reason = cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");

        if (failurePolicy == BranchFailurePolicy.FAIL_FAST) {
            log.error("branch_failed agent={} outputKey={} reason={} policy=FAIL_FAST",
                    agentName, agent.getOutputKey(), reason);
            throw new CompletionException(
                    "Branch '" + agent.getOutputKey() + "' (" + agentName + ") failed: " + reason, cause);
        }

        log.warn("branch_degraded agent={} outputKey={} reason={} policy=DEGRADE",
                agentName, agent.getOutputKey(), reason);
        return new BranchResult(agent.getOutputKey(), UNAVAILABLE_PREFIX + reason);
    }

    /**
     * Holds the result of a single parallel branch.
     *
     * @param outputKey the context key under which the value will be stored
     * @param value     the string output produced by the branch agent
     */
    private record BranchResult(String outputKey, String value) {}


    /**
     * Builder class for ParallelAgentOrchestrator.
     * Allows configuring sub-agents, aggregator, executor, and other settings.
     */
    public static final class Builder<T> {
        private static final String DEFAULT_REPORTS_KEY = "reports";
        private static final Duration DEFAULT_BRANCH_TIMEOUT = Duration.ofSeconds(60);

        private final List<SubAgent<String>> subAgents = new ArrayList<>();
        private SubAgent<T> aggregator;
        private Executor executor;
        private String reportsKey = DEFAULT_REPORTS_KEY;
        private Duration branchTimeout = DEFAULT_BRANCH_TIMEOUT;
        private BranchFailurePolicy failurePolicy = BranchFailurePolicy.FAIL_FAST;

        private Builder() {}

        /**
         * Adds a single sub-agent to the list of agents to execute in parallel.
         *
         * @param agent the SubAgent to add
         * @return this Builder
         * @throws NullPointerException if agent is null
         */
        public Builder<T> addSubAgent(SubAgent<String> agent) {
            this.subAgents.add(Objects.requireNonNull(agent));
            return this;
        }

        /**
         * Adds multiple sub-agents to the list using a varargs array.
         *
         * @param agents the array of SubAgents to add
         * @return this Builder
         */
        public Builder<T> addSubAgents(SubAgent<String>... agents) {
            return addSubAgents(Arrays.asList(agents));
        }

        /**
         * Adds a list of sub-agents to the list.
         *
         * @param agents the list of SubAgents to add
         * @return this Builder
         */
        public Builder<T> addSubAgents(List<? extends SubAgent<String>> agents) {
            agents.forEach(this::addSubAgent);
            return this;
        }

        /**
         * Sets the aggregator agent that will combine the results from sub-agents.
         *
         * @param aggregator the SubAgent to use as aggregator
         * @return this Builder
         * @throws NullPointerException if aggregator is null
         */
        public Builder<T> aggregator(SubAgent<T> aggregator) {
            this.aggregator = Objects.requireNonNull(aggregator);
            return this;
        }

        /**
         * Sets the Executor to use for running sub-agents in parallel.
         *
         * @param executor the Executor for parallel execution
         * @return this Builder
         * @throws NullPointerException if executor is null
         */
        public Builder<T> executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor);
            return this;
        }

        /**
         * Sets the key under which the aggregated reports from sub-agents will be stored in the context.
         * Defaults to "reports" if not set.
         *
         * @param reportsKey the key for reports in the aggregator context
         * @return this Builder
         * @throws NullPointerException if reportsKey is null
         * @throws IllegalArgumentException if reportsKey is blank
         */
        public Builder<T> reportsKey(String reportsKey) {
            this.reportsKey = Objects.requireNonNull(reportsKey);
            return this;
        }

        /**
         * Sets how long to wait for any single branch before treating it as failed.
         * Defaults to 60 seconds. Bounds the wait, not the underlying call — see
         * {@link ParallelAgentOrchestrator#withTimeout}.
         *
         * @param branchTimeout the per-branch timeout; {@link Duration#ZERO} waits indefinitely
         * @return this Builder
         * @throws NullPointerException     if branchTimeout is null
         * @throws IllegalArgumentException if branchTimeout is negative
         */
        public Builder<T> branchTimeout(Duration branchTimeout) {
            Objects.requireNonNull(branchTimeout, "branchTimeout must not be null");
            if (branchTimeout.isNegative()) {
                throw new IllegalArgumentException("branchTimeout must not be negative: " + branchTimeout);
            }
            this.branchTimeout = branchTimeout;
            return this;
        }

        /**
         * Waits indefinitely for every branch, restoring the pre-timeout behaviour.
         * Prefer {@link #branchTimeout(Duration)} — an unbounded LLM call can hang the workflow.
         *
         * @return this Builder
         */
        public Builder<T> noBranchTimeout() {
            return branchTimeout(Duration.ZERO);
        }

        /**
         * Sets what happens when a branch fails or times out.
         * Defaults to {@link BranchFailurePolicy#FAIL_FAST}.
         *
         * @param failurePolicy the policy to apply to failed branches
         * @return this Builder
         * @throws NullPointerException if failurePolicy is null
         */
        public Builder<T> failurePolicy(BranchFailurePolicy failurePolicy) {
            this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
            return this;
        }

        /**
         * Builds the ParallelAgentOrchestrator instance with the configured settings.
         *
         * @return the built ParallelAgentOrchestrator
         * @throws IllegalStateException if no sub-agents are added, aggregator or executor is not set,
         *                               or if validation fails (e.g., duplicate output keys)
         */
        public ParallelAgentOrchestrator<T> build() {
            if (subAgents.isEmpty()) throw new IllegalStateException("SubAgents required.");
            Objects.requireNonNull(aggregator, "Aggregator required.");
            Objects.requireNonNull(executor, "Executor required.");
            validateOutputKeys();
            return new ParallelAgentOrchestrator<>(this);
        }

        /**
         * Verifies that every sub-agent contributes a distinct, usable slot in the aggregator
         * context. Without this check a colliding key would silently overwrite another branch's
         * result during fan-in, discarding completed work with no error.
         *
         * @throws IllegalStateException if an output key is null, blank, duplicated, or collides
         *                               with a reserved context key
         */
        private void validateOutputKeys() {
            Set<String> seen = new HashSet<>();
            for (SubAgent<String> agent : subAgents) {
                String key = agent.getOutputKey();
                String agentName = agent.getClass().getSimpleName();

                if (key == null || key.isBlank()) {
                    throw new IllegalStateException(
                            "SubAgent " + agentName + " must return a non-blank getOutputKey().");
                }
                if (CTX_INPUT.equals(key) || reportsKey.equals(key)) {
                    throw new IllegalStateException(
                            "SubAgent " + agentName + " uses reserved output key '" + key
                                    + "'. Reserved keys are '" + CTX_INPUT + "' and '" + reportsKey + "'.");
                }
                if (!seen.add(key)) {
                    throw new IllegalStateException(
                            "Duplicate output key '" + key + "' (SubAgent " + agentName
                                    + "). Each sub-agent must contribute a unique key to the aggregator context.");
                }
            }
        }
    }
}