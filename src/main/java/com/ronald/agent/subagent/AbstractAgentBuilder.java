package com.ronald.agent.subagent;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Shared base builder for prompt-based sub-agents.
 * <p>
 * Holds the common configuration fields ({@code chatClient}, {@code promptTemplate},
 * {@code systemPrompt}) and exposes fluent setters. Concrete builders extend this class
 * and add agent-specific fields (e.g. {@code outputKey} or {@code routeKey}).
 * </p>
 *
 * @param <B> the concrete builder type, for fluent method chaining
 * @param <A> the agent type produced by {@link #build()}
 */
public abstract class AbstractAgentBuilder<B extends AbstractAgentBuilder<B, A>, A> {

    private ChatClient chatClient;
    private String promptTemplate;
    private String systemPrompt;

    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    /**
     * Sets the ChatClient.
     *
     * @param chatClient the ChatClient
     * @return this builder
     */
    public B chatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
        return self();
    }

    /**
     * Sets the prompt template.
     *
     * @param promptTemplate the prompt template
     * @return this builder
     */
    public B promptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
        return self();
    }

    /**
     * Sets the system prompt.
     *
     * @param systemPrompt the system prompt, or {@code null} for none
     * @return this builder
     */
    public B systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return self();
    }

    /** Returns the configured ChatClient. */
    public ChatClient getChatClient() { return chatClient; }

    /** Returns the configured prompt template. */
    public String getPromptTemplate() { return promptTemplate; }

    /** Returns the configured system prompt. */
    public String getSystemPrompt() { return systemPrompt; }

    /**
     * Builds and returns the configured agent instance.
     *
     * @return the constructed agent
     */
    public abstract A build();
}
