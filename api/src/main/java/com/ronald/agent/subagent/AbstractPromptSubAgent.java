package com.ronald.agent.subagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;
import java.util.Objects;

/**
 * Abstract base class for sub-agents that use prompt templates to interact with a ChatClient.
 * This class handles the common logic of resolving prompt templates with context variables
 * and executing them via the ChatClient.
 */
public abstract class AbstractPromptSubAgent<T> implements SubAgent<T> {

    private final ChatClient chatClient;
    private final Class<T> outputType;

    /**
     * Constructs an AbstractPromptSubAgent with the specified ChatClient and output type.
     *
     * @param chatClient the ChatClient used to execute prompts
     * @param outputType the Class of the output type T
     * @throws NullPointerException if chatClient or outputType is null
     */
    public AbstractPromptSubAgent(ChatClient chatClient, Class<T> outputType) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.outputType = Objects.requireNonNull(outputType, "outputType must not be null");
    }

    /**
     * Executes the prompt by resolving the template with the context and calling the ChatClient.
     *
     * @param context a map containing input data and previous results
     * @return the response from the ChatClient as an entity of type T
     */
    @Override
    public T execute(Map<String, String> context) {
        // Use Spring AI's native PromptTemplate
        PromptTemplate template = new PromptTemplate(getPromptTemplate());
        Message userMessage = template.createMessage(Map.copyOf(context));

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().messages(userMessage);

        String systemPrompt = getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }

        if (String.class.equals(outputType)) {
            return (T) spec.call().content();
        } else {
            return spec.call().entity(outputType);
        }
    }

    /**
     * Returns the prompt template to be used for this sub-agent.
     * The template may contain placeholders like {variable} that are replaced with context values.
     *
     * @return the prompt template string
     */
    public abstract String getPromptTemplate();

    /**
     * Returns the system prompt to be used, or null if none.
     *
     * @return the system prompt, or null
     */
    public String getSystemPrompt() {
        return null;
    }

    public ChatClient getChatClient() {
        return chatClient;
    }

}