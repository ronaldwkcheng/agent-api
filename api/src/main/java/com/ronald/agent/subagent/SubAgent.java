package com.ronald.agent.subagent;

import java.util.Map;

/**
 * Represents a sub-agent that can execute a task based on a given context and provide an output key.
 * Sub-agents are modular components used in workflows to perform specific operations.
 */
public interface SubAgent<T> {

    /**
     * Reserved context key carrying the identity of the conversation a request belongs to.
     *
     * <p>Agents do not read this key themselves. It is read by an advisor customizer
     * (see {@code AbstractAgentBuilder#advisors}) to scope chat memory to one conversation,
     * which is why it must survive a workflow end to end: a workflow that overwrote it
     * would hand a later agent a different caller's history.</p>
     *
     * <p>Workflows that build their own context maps therefore treat this key as reserved and
     * refuse to let a sub-agent's output key collide with it.</p>
     */
    String CONVERSATION_ID = "conversationId";

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
