package com.ronald.agent.subagent.route;

import com.ronald.agent.subagent.AbstractPromptSubAgent;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Abstract base class for routable sub-agents.
 * Inherits prompt execution logic from AbstractPromptSubAgent.
 */
public abstract class AbstractPromptRoutableAgent<T> extends AbstractPromptSubAgent<T> implements RoutableSubAgent<T> {

    /**
     * Constructs a routable sub-agent with the given chat client and output type.
     *
     * @param chatClient the ChatClient used to invoke prompts
     * @param outputType the class of the expected output type {@code T}
     */
    public AbstractPromptRoutableAgent(ChatClient chatClient, Class<T> outputType) {
        super(chatClient, outputType);
    }

    // getChatClient(), execute(), and templating logic are now fully inherited!
}