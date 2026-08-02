package com.ronald.agent.subagent;

import java.util.Map;

/**
 * Represents a sub-agent that can execute a task based on a given context and provide an output key.
 * Sub-agents are modular components used in workflows to perform specific operations.
 */
public interface SubAgent<T> {
    /**
     * Returns the output key associated with this sub-agent's result.
     * This key is used to store the execution result in the context for subsequent agents.
     *
     * @return the output key, or null if no specific key is assigned
     */
    String getOutputKey();

    /**
     * Executes the sub-agent's logic using the provided context.
     *
     * @param context a map containing input data and previous results
     * @return the result of the execution as a string
     */
    T execute(Map<String, String> context);
}
