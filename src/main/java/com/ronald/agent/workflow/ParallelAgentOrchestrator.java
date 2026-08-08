package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An {@link AgenticWorkflow} that executes multiple {@link SubAgent}s concurrently (fan-out),
 * collects their results into a shared context (fan-in), and delegates the final synthesis
 * to a typed aggregator agent.
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 *
 * @param <T> the return type produced by the aggregator agent
 */
public class ParallelAgentOrchestrator<T> implements AgenticWorkflow<T> {

    private static final Logger log = LoggerFactory.getLogger(ParallelAgentOrchestrator.class);

    private static final String CTX_INPUT = "input";

    private final List<SubAgent<String>> subAgents;
    private final SubAgent<T> aggregator; // Strongly typed to T
    private final Executor executor;
    private final String reportsKey;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private ParallelAgentOrchestrator(Builder<T> builder) {
        this.subAgents  = List.copyOf(builder.subAgents);
        this.aggregator = builder.aggregator;
        this.executor   = builder.executor;
        this.reportsKey = builder.reportsKey;
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
     * @param input the input string to process
     * @return the aggregated result from the aggregator agent
     * @throws NullPointerException if input is null
     * @throws IllegalStateException if any sub-agent or aggregator returns null
     */
    @Override
    public T invoke(String input) {
        Objects.requireNonNull(input, "input must not be null");
        log.info("fan_out_start subAgents={} inputLength={}", subAgents.size(), input.length());

        Map<String, String> fanOutContext = Map.of(CTX_INPUT, input);

        // ── Fan-out ──────────────────────────────────────────────────────────
        List<CompletableFuture<BranchResult>> futures = subAgents.stream()
                .map(agent -> CompletableFuture
                        .supplyAsync(() -> executeBranch(agent, fanOutContext), executor)
                )
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
        private final List<SubAgent<String>> subAgents = new ArrayList<>();
        private SubAgent<T> aggregator;
        private Executor executor;
        private String reportsKey = DEFAULT_REPORTS_KEY;

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