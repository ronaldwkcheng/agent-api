package com.ronald.agent.subagent;

import java.util.Objects;

/**
 * A default implementation of AbstractPromptSubAgent that uses configurable prompt templates,
 * system prompts, and output keys. Built using the Builder pattern.
 */
public class DefaultPromptSubAgent extends AbstractPromptSubAgent<String> {

    private final String outputKey;
    private final String promptTemplate;
    private final String systemPrompt;

    /**
     * Private constructor used by the Builder.
     *
     * @param builder the Builder instance containing configuration
     */
    private DefaultPromptSubAgent(Builder builder) {
        super(builder.getChatClient(), String.class);
        this.outputKey     = builder.outputKey;
        this.promptTemplate = Objects.requireNonNull(builder.getPromptTemplate(), "promptTemplate must not be null");
        this.systemPrompt  = builder.getSystemPrompt();
    }

    /**
     * Returns the output key for this sub-agent.
     *
     * @return the output key
     */
    @Override
    public String getOutputKey() {
        return outputKey;
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
     * Creates a new Builder for constructing DefaultPromptSubAgent instances.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DefaultPromptSubAgent}.
     * Extends {@link AbstractAgentBuilder} for the shared {@code chatClient},
     * {@code promptTemplate}, and {@code systemPrompt} fields,
     * and adds the agent-specific {@code outputKey}.
     */
    public static final class Builder extends AbstractAgentBuilder<Builder, DefaultPromptSubAgent> {

        private String outputKey;

        private Builder() {}

        /**
         * Sets the output key.
         *
         * @param outputKey the output key
         * @return this Builder
         */
        public Builder outputKey(String outputKey) {
            this.outputKey = outputKey;
            return this;
        }

        /**
         * Builds the DefaultPromptSubAgent instance.
         *
         * @return the built DefaultPromptSubAgent
         * @throws NullPointerException if chatClient or promptTemplate is null
         */
        @Override
        public DefaultPromptSubAgent build() {
            Objects.requireNonNull(getChatClient(), "chatClient must not be null");
            Objects.requireNonNull(getPromptTemplate(), "promptTemplate must not be null");
            return new DefaultPromptSubAgent(this);
        }
    }
}
