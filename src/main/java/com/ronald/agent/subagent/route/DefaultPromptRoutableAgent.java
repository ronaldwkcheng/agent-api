package com.ronald.agent.subagent.route;

import com.ronald.agent.subagent.AbstractAgentBuilder;

import java.util.Objects;

/**
 * A default implementation of AbstractPromptRoutableAgent that uses configurable route keys,
 * prompt templates, and system prompts. Used in routing workflows where agents are dispatched based on categories.
 */
public class DefaultPromptRoutableAgent extends AbstractPromptRoutableAgent<String> {

    private final String routeKey;
    private final String promptTemplate;
    private final String systemPrompt;

    /**
     * Private constructor used by the Builder.
     *
     * @param builder the Builder instance containing configuration
     */
    private DefaultPromptRoutableAgent(Builder builder) {
        super(builder.getChatClient(), String.class);
        this.routeKey       = Objects.requireNonNull(builder.routeKey, "routeKey must not be null");
        this.promptTemplate = Objects.requireNonNull(builder.getPromptTemplate(), "promptTemplate must not be null");
        this.systemPrompt   = builder.getSystemPrompt();
    }

    /**
     * Returns the route key that identifies this agent's category.
     *
     * @return the route key string
     */
    @Override
    public String getRouteKey() {
        return routeKey;
    }

    /**
     * Returns the prompt template.
     *
     * @return the prompt template string
     */
    @Override
    public String getPromptTemplate() {
        return promptTemplate;
    }

    /**
     * Returns the system prompt.
     *
     * @return the system prompt, or null
     */
    @Override
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Creates a new Builder for constructing DefaultPromptRoutableAgent instances.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DefaultPromptRoutableAgent}.
     * Extends {@link AbstractAgentBuilder} for the shared {@code chatClient},
     * {@code promptTemplate}, and {@code systemPrompt} fields,
     * and adds the agent-specific {@code routeKey}.
     */
    public static final class Builder extends AbstractAgentBuilder<Builder, DefaultPromptRoutableAgent> {

        private String routeKey;

        private Builder() {}

        /**
         * Sets the route key.
         *
         * @param routeKey the route key
         * @return this Builder
         */
        public Builder routeKey(String routeKey) {
            this.routeKey = routeKey;
            return this;
        }

        /**
         * Builds the DefaultPromptRoutableAgent instance.
         *
         * @return the built DefaultPromptRoutableAgent
         * @throws NullPointerException if chatClient, routeKey, or promptTemplate is null
         */
        @Override
        public DefaultPromptRoutableAgent build() {
            Objects.requireNonNull(getChatClient(), "chatClient must not be null");
            Objects.requireNonNull(routeKey, "routeKey must not be null");
            Objects.requireNonNull(getPromptTemplate(), "promptTemplate must not be null");
            return new DefaultPromptRoutableAgent(this);
        }
    }
}
