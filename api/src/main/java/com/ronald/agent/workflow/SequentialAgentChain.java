package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * An {@link AgenticWorkflow} that executes a list of {@link SubAgent}s in strict sequence,
 * threading the output of each step into the shared context for subsequent steps.
 *
 * <p>All intermediate agents must return {@code String}; only the final agent may return
 * the parameterised type {@code T}.</p>
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 *
 * @param <T> the return type produced by the final agent
 */
public class SequentialAgentChain<T> implements AgenticWorkflow<T> {

    private static final Logger log = LoggerFactory.getLogger(SequentialAgentChain.class);
    private static final String CTX_INPUT  = "input";
    private static final String CTX_OUTPUT = "output";

    // Changed to wildcard so the final agent can return T instead of String
    private final List<SubAgent<?>> agents;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private SequentialAgentChain(Builder<T> builder) {
        this.agents = List.copyOf(builder.agents);
    }

    /**
     * Creates a new {@link Builder} for constructing a {@code SequentialAgentChain}.
     *
     * @param <T> the type returned by the final agent in the chain
     * @return a fresh builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Invokes the sequential chain of agents.
     * Executes each agent in order, passing the context with updated outputs between steps.
     * The final agent's result is returned, potentially deserialized into the specified output type.
     *
     * @param input the initial input string
     * @return the result from the final agent
     * @throws NullPointerException if input is null
     * @throws IllegalStateException if any agent returns null
     */
    @Override
    @SuppressWarnings("unchecked")
    public T invoke(String input) {
        Objects.requireNonNull(input, "input must not be null");

        log.info("chain_start agents={} inputLength={}", agents.size(), input.length());

        Map<String, String> context = new HashMap<>();
        context.put(CTX_INPUT,  input);
        context.put(CTX_OUTPUT, input);

        // ── Intermediate steps ───────────────────────────────────────────────
        List<SubAgent<?>> intermediates = agents.subList(0, agents.size() - 1);
        for (int i = 0; i < intermediates.size(); i++) {
            SubAgent<?> agent = intermediates.get(i);
            // We know intermediate agents return String for context passing
            String result = (String) agent.execute(Collections.unmodifiableMap(context));
            Objects.requireNonNull(result, "Agent returned null at step " + i);
            log.debug("chain_step index={} agent={} result=\n{}", i, agent.getClass().getSimpleName(), result);

            updateContext(context, agent, result);
            log.debug("chain_step_done index={} resultLength={}", i, result.length());
        }

        // ── Final step ───────────────────────────────────────────────────────
        SubAgent<?> lastAgent = agents.get(agents.size() - 1);

        // No more duplicated logic! The agent handles its own return type natively.
        return (T) lastAgent.execute(Collections.unmodifiableMap(context));
    }

    /**
     * Updates the shared context after an intermediate step completes.
     * Stores the result under the generic {@code "output"} key and, if the agent
     * declares an {@link SubAgent#getOutputKey() outputKey}, under that key as well.
     *
     * @param context the mutable context map to update
     * @param agent   the agent that just executed
     * @param result  the string result produced by the agent
     */
    private void updateContext(Map<String, String> context, SubAgent<?> agent, String result) {
        context.put(CTX_OUTPUT, result);
        if (agent.getOutputKey() != null) {
            context.put(agent.getOutputKey(), result);
        }
    }

    /**
     * Builder class for SequentialAgentChain.
     * Allows adding agents and configuring output type in a fluent manner.
     */
    public static class Builder<T> {
        private final List<SubAgent<?>> agents = new ArrayList<>();

        private Builder() {}

        /**
         * Adds a single agent to the chain.
         *
         * @param agent the SubAgent to add
         * @return this Builder
         */
        public Builder<T> addAgent(SubAgent<String> agent) {
            this.agents.add(Objects.requireNonNull(agent));
            return this;
        }

        /**
         * Adds a list of agents to the chain.
         *
         * @param agents the list of SubAgents to add
         * @return this Builder
         */
        public Builder<T> addAgents(List<? extends SubAgent<String>> agents) {
            agents.forEach(this::addAgent);
            return this;
        }

        /**
         * Adds multiple agents to the chain using a varargs array.
         *
         * @param agents the array of SubAgents to add
         * @return this Builder
         */
        public Builder<T> addAgents(SubAgent<String>... agents) {
            return addAgents(Arrays.asList(agents));
        }

        /**
         * Builds the SequentialAgentChain instance with the configured agents and settings.
         *
         * @return the built SequentialAgentChain
         * @throws IllegalStateException if no agents are added or validation fails
         */
        public SequentialAgentChain<T> build() {
            if (agents.isEmpty()) throw new IllegalStateException("At least one SubAgent must be added.");
            return new SequentialAgentChain<>(this);
        }
    }
}