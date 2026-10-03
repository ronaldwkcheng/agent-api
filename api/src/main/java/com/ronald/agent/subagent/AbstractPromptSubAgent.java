package com.ronald.agent.subagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Abstract base class for sub-agents that use prompt templates to interact with a ChatClient.
 * This class handles the common logic of resolving prompt templates with context variables
 * and executing them via the ChatClient.
 */
public abstract class AbstractPromptSubAgent<T> implements SubAgent<T> {

    /**
     * The no-op advisor customizer: the request is sent exactly as it was before this seam
     * existed. This is the default for every agent, so advisor participation is opt-in.
     */
    public static final BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> NO_ADVISORS =
            (advisorSpec, context) -> { };

    private final ChatClient chatClient;
    private final Class<T> outputType;
    private final BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> advisorCustomizer;

    /**
     * Constructs an AbstractPromptSubAgent with no advisor customization.
     *
     * @param chatClient the ChatClient used to execute prompts
     * @param outputType the Class of the output type T
     * @throws NullPointerException if chatClient or outputType is null
     */
    public AbstractPromptSubAgent(ChatClient chatClient, Class<T> outputType) {
        this(chatClient, outputType, NO_ADVISORS);
    }

    /**
     * Constructs an AbstractPromptSubAgent that customizes the advisor chain per request.
     *
     * <p>The customizer is invoked on every {@link #execute(Map)} call and receives the live
     * context map, so advisor parameters that vary per request &mdash; a conversation id for
     * {@code MessageChatMemoryAdvisor}, for instance &mdash; can be read from the context the
     * caller supplied rather than frozen when the agent was built. Agents here are typically
     * long-lived singletons, so a customizer fixed at build time could only ever carry one
     * conversation's identity.</p>
     *
     * @param chatClient        the ChatClient used to execute prompts
     * @param outputType        the Class of the output type T
     * @param advisorCustomizer applied to the advisor spec of every request, with the context of
     *                          that request; use {@link #NO_ADVISORS} for none
     * @throws NullPointerException if any argument is null
     */
    public AbstractPromptSubAgent(ChatClient chatClient, Class<T> outputType,
                                  BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> advisorCustomizer) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.outputType = Objects.requireNonNull(outputType, "outputType must not be null");
        this.advisorCustomizer =
                Objects.requireNonNull(advisorCustomizer, "advisorCustomizer must not be null");
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

        // Per-request advisor parameters. Defaults to NO_ADVISORS, in which case the spec is
        // handed to the customizer untouched and comes back unchanged.
        spec = spec.advisors(advisorSpec -> advisorCustomizer.accept(advisorSpec, context));

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