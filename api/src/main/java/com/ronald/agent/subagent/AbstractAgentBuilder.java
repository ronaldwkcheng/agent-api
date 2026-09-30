package com.ronald.agent.subagent;

import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Shared base builder for prompt-based sub-agents.
 * <p>
 * Holds the common configuration fields ({@code chatClient}, {@code promptTemplate},
 * {@code systemPrompt}, {@code advisors}) and exposes fluent setters. Concrete builders extend
 * this class and add agent-specific fields (e.g. {@code outputKey} or {@code routeKey}).
 * </p>
 *
 * @param <B> the concrete builder type, for fluent method chaining
 * @param <A> the agent type produced by {@link #build()}
 */
public abstract class AbstractAgentBuilder<B extends AbstractAgentBuilder<B, A>, A> {

    private ChatClient chatClient;
    private String promptTemplate;
    private String systemPrompt;
    private BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> advisorCustomizer =
            AbstractPromptSubAgent.NO_ADVISORS;

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

    /**
     * Sets a customizer applied to the advisor spec of every request this agent makes.
     *
     * <p>The customizer receives the context map of the request being executed, so parameters
     * that vary per call can be read from it. Wiring chat memory, for example:</p>
     *
     * <pre>{@code
     * .advisors((advisorSpec, context) ->
     *         advisorSpec.param(ChatMemory.CONVERSATION_ID,
     *                           context.get(SubAgent.CONVERSATION_ID)))
     * }</pre>
     *
     * <p>The advisor itself is registered on the {@code ChatClient} (typically through
     * {@code ChatClient.Builder#defaultAdvisors}); this setter only supplies its per-request
     * parameters. Leaving it unset sends requests with the advisor chain untouched.</p>
     *
     * @param advisorCustomizer the customizer; must not be null
     * @return this builder
     * @throws NullPointerException if advisorCustomizer is null
     */
    public B advisors(BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> advisorCustomizer) {
        this.advisorCustomizer =
                Objects.requireNonNull(advisorCustomizer, "advisorCustomizer must not be null");
        return self();
    }

    /** Returns the configured ChatClient. */
    public ChatClient getChatClient() { return chatClient; }

    /** Returns the configured prompt template. */
    public String getPromptTemplate() { return promptTemplate; }

    /** Returns the configured system prompt. */
    public String getSystemPrompt() { return systemPrompt; }

    /** Returns the advisor customizer; never null, defaulting to a no-op. */
    public BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> getAdvisorCustomizer() {
        return advisorCustomizer;
    }

    /**
     * Builds and returns the configured agent instance.
     *
     * @return the constructed agent
     */
    public abstract A build();
}
