package com.ronald.agent.subagent.route;

import com.ronald.agent.subagent.AbstractPromptSubAgent;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Abstract base class for routable sub-agents.
 * Inherits prompt execution logic from AbstractPromptSubAgent.
 */
public abstract class AbstractPromptRoutableAgent<T> extends AbstractPromptSubAgent<T> implements RoutableSubAgent<T> {

    /**
     * Constructs a routable sub-agent with no advisor customization.
     *
     * @param chatClient the ChatClient used to invoke prompts
     * @param outputType the class of the expected output type {@code T}
     */
    public AbstractPromptRoutableAgent(ChatClient chatClient, Class<T> outputType) {
        super(chatClient, outputType);
    }

    /**
     * Constructs a routable sub-agent that customizes the advisor chain per request.
     *
     * @param chatClient        the ChatClient used to invoke prompts
     * @param outputType        the class of the expected output type {@code T}
     * @param advisorCustomizer applied to the advisor spec of every request, with the context of
     *                          that request; use {@link AbstractPromptSubAgent#NO_ADVISORS} for none
     */
    public AbstractPromptRoutableAgent(ChatClient chatClient, Class<T> outputType,
                                       BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> advisorCustomizer) {
        super(chatClient, outputType, advisorCustomizer);
    }

    // getChatClient(), execute(), and templating logic are now fully inherited!
}