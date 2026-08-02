package com.ronald.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.ronald.agent.subagent.AbstractPromptSubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.*;
import java.util.stream.Collectors;

/**
 * An {@link AgenticWorkflow} that implements the ReAct (Reasoning + Acting) pattern.
 *
 * <p>ReAct interleaves reasoning and acting in a loop:</p>
 * <ol>
 *   <li><b>Thought</b> — the agent reasons about what to do next</li>
 *   <li><b>Action</b> — the agent selects and calls a Spring AI {@link ToolCallback}</li>
 *   <li><b>Observation</b> — the tool result is appended to the reasoning trace</li>
 * </ol>
 * <p>The loop repeats until the agent signals {@code finalAnswer = true} in a
 * {@link ReActThought} or {@link Builder#maxSteps maxSteps} is exhausted.</p>
 *
 * <p>Tools are registered via objects whose methods are annotated with Spring AI's
 * {@code @Tool} annotation. The workflow extracts tool definitions (name, description,
 * and input schema) from those annotations and exposes them to the LLM at reasoning time.</p>
 *
 * <p>Context keys used internally:</p>
 * <ul>
 *   <li>{@code input} — the original user question</li>
 *   <li>{@code tools} — formatted name, description, and input schema of all registered tools</li>
 *   <li>{@code scratchpad} — accumulated thought / action / observation trace</li>
 * </ul>
 *
 * <p>Example:</p>
 * <pre>{@code
 * AgenticWorkflow<String> workflow = ReActWorkflow.builder()
 *     .chatClient(chatClient)
 *     .tools(myToolsObject)   // any object with @Tool-annotated methods
 *     .maxSteps(10)
 *     .build();
 * String answer = workflow.invoke("How many days until New Year's Eve?");
 * }</pre>
 */
public class ReActWorkflow implements AgenticWorkflow<String> {

    private static final Logger log = LoggerFactory.getLogger(ReActWorkflow.class);

    /** Context key for the original user input. */
    public static final String CTX_INPUT      = "input";
    /** Context key for the formatted description of available tools. */
    public static final String CTX_TOOLS      = "tools";
    /** Context key for the accumulated thought/action/observation trace. */
    public static final String CTX_SCRATCHPAD = "scratchpad";

    private static final String DEFAULT_REACT_PROMPT_TEMPLATE = """
            You are a ReAct (Reasoning + Acting) AI agent that solves problems step by step.

            You have access to the following tools:
            {tools}

            Instructions:
            - Set "thought" to your reasoning about what to do next.
            - Set "finalAnswer" to true ONLY when you have gathered enough information to fully answer the question.
            - When "finalAnswer" is true: provide the complete answer in "answer" and set "toolName" and "actionInput" to null.
            - When "finalAnswer" is false: set "toolName" to the exact tool name, set "actionInput" to a JSON object matching the tool's input schema, and set "answer" to null.

            Question: {input}

            Reasoning trace so far:
            {scratchpad}

            Provide the next reasoning step:
            """;

    private final ReActThinkerAgent         thinkerAgent;
    private final Map<String, ToolCallback> tools;
    private final int                       maxSteps;

    private ReActWorkflow(Builder builder) {
        this.thinkerAgent = new ReActThinkerAgent(builder.chatClient, builder.reactPromptTemplate);
        this.tools        = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tools));
        this.maxSteps     = builder.maxSteps;
    }

    /**
     * Creates a new {@link Builder} for constructing a {@code ReActWorkflow}.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs the ReAct reasoning loop for the given question.
     *
     * <p>Each iteration asks the LLM for the next {@link ReActThought}. If the thought
     * signals a {@code finalAnswer}, the answer is returned immediately. Otherwise the
     * indicated tool is called with the JSON {@code actionInput} and its result is
     * appended to the scratchpad before the next iteration begins.</p>
     *
     * @param input the user's question or task
     * @return the agent's final answer string
     * @throws NullPointerException  if {@code input} is null
     * @throws IllegalStateException if no final answer is produced within {@code maxSteps}
     */
    @Override
    public String invoke(String input) {
        Objects.requireNonNull(input, "input must not be null");
        log.debug("react_start input=\"{}\" maxSteps={}", input, maxSteps);

        Map<String, String> context = new HashMap<>();
        context.put(CTX_INPUT,      input);
        context.put(CTX_TOOLS,      buildToolsDescription());
        context.put(CTX_SCRATCHPAD, "");

        StringBuilder scratchpad = new StringBuilder();

        for (int step = 1; step <= maxSteps; step++) {
            log.debug("react_step step={}/{}", step, maxSteps);

            ReActThought thought = thinkerAgent.execute(Collections.unmodifiableMap(context));
            log.debug("react_thought step={} thought=\"{}\" finalAnswer={}",
                    step, thought.thought(), thought.finalAnswer());

            if (thought.finalAnswer()) {
                log.debug("react_complete steps={} answer=\"{}\"", step, thought.answer());
                return thought.answer();
            }

            String       toolName    = thought.toolName();
            String       actionInput = thought.actionInput() != null ? thought.actionInput().toString() : "{}";
            ToolCallback tool        = tools.get(toolName);
            String       observation;

            if (tool == null) {
                observation = "Error: tool '" + toolName + "' not found. Available tools: "
                        + String.join(", ", tools.keySet());
                log.warn("react_tool_not_found step={} tool=\"{}\"", step, toolName);
            } else {
                observation = tool.call(actionInput);
                log.debug("react_tool_called step={} tool=\"{}\" observation=\"{}\"",
                        step, toolName, observation);
            }

            scratchpad.append("Thought: ").append(thought.thought()).append("\n")
                      .append("Action: ").append(toolName).append("\n")
                      .append("Action Input: ").append(actionInput).append("\n")
                      .append("Observation: ").append(observation).append("\n\n");

            context.put(CTX_SCRATCHPAD, scratchpad.toString());
        }

        throw new IllegalStateException(
                "ReAct workflow did not reach a final answer within " + maxSteps + " steps");
    }

    private String buildToolsDescription() {
        return tools.values().stream()
                .map(cb -> {
                    ToolDefinition def = cb.getToolDefinition();
                    return "- " + def.name() + ": " + def.description()
                            + " (input schema: " + def.inputSchema() + ")";
                })
                .collect(Collectors.joining("\n"));
    }

    // -------------------------------------------------------------------------
    // Inner record: ReActThought
    // -------------------------------------------------------------------------

    /**
     * Structured output representing a single reasoning step produced by the LLM.
     *
     * @param thought     the agent's reasoning for this step
     * @param finalAnswer {@code true} when the agent has enough information to answer
     * @param answer      the final answer (set when {@code finalAnswer} is {@code true})
     * @param toolName    the tool name to invoke (set when {@code finalAnswer} is {@code false})
     * @param actionInput JSON node matching the tool's input schema — kept as {@link JsonNode}
     *                    so Jackson can deserialize both object and null values without error
     */
    public record ReActThought(
            String   thought,
            boolean  finalAnswer,
            String   answer,
            String   toolName,
            JsonNode actionInput
    ) {}

    // -------------------------------------------------------------------------
    // Inner class: ReActThinkerAgent
    // -------------------------------------------------------------------------

    /**
     * Internal agent that performs a single reasoning step in the ReAct loop,
     * producing a structured {@link ReActThought} via Spring AI structured output.
     */
    private static class ReActThinkerAgent extends AbstractPromptSubAgent<ReActThought> {

        private final String promptTemplate;

        ReActThinkerAgent(ChatClient chatClient, String promptTemplate) {
            super(chatClient, ReActThought.class);
            this.promptTemplate = promptTemplate;
        }

        @Override
        public String getOutputKey() {
            return "thought";
        }

        @Override
        public String getPromptTemplate() {
            return promptTemplate;
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link ReActWorkflow}.
     */
    public static class Builder {

        private ChatClient                   chatClient;
        private final Map<String, ToolCallback> tools              = new LinkedHashMap<>();
        private int                          maxSteps             = 10;
        private String                       reactPromptTemplate  = DEFAULT_REACT_PROMPT_TEMPLATE;

        /**
         * Sets the {@link ChatClient} used for all reasoning steps.
         *
         * @param chatClient the chat client; must not be null
         * @return this builder
         */
        public Builder chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        /**
         * Registers tools from objects whose methods are annotated with {@code @Tool}.
         * Spring AI's {@link MethodToolCallbackProvider} is used to extract
         * {@link ToolCallback} instances from the annotated methods.
         *
         * @param toolSources objects containing {@code @Tool}-annotated methods
         * @return this builder
         */
        public Builder tools(Object... toolSources) {
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(toolSources)
                    .build()
                    .getToolCallbacks();
            for (ToolCallback callback : callbacks) {
                this.tools.put(callback.getToolDefinition().name(), callback);
            }
            return this;
        }

        /**
         * Sets the maximum number of thought-action-observation iterations.
         * Defaults to {@code 10}.
         *
         * @param maxSteps maximum step count; must be at least 1
         * @return this builder
         */
        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        /**
         * Overrides the default ReAct prompt template.
         * The template must contain the {@code {input}}, {@code {tools}},
         * and {@code {scratchpad}} placeholders.
         *
         * @param reactPromptTemplate the custom prompt template
         * @return this builder
         */
        public Builder reactPromptTemplate(String reactPromptTemplate) {
            this.reactPromptTemplate = reactPromptTemplate;
            return this;
        }

        /**
         * Builds the {@link ReActWorkflow}.
         *
         * @return the configured workflow
         * @throws NullPointerException     if {@code chatClient} is not set
         * @throws IllegalArgumentException if no tools are registered or {@code maxSteps} is less than 1
         */
        public ReActWorkflow build() {
            Objects.requireNonNull(chatClient, "chatClient must not be null");
            if (tools.isEmpty()) {
                throw new IllegalArgumentException("At least one @Tool-annotated method must be registered");
            }
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps must be at least 1");
            }
            return new ReActWorkflow(this);
        }
    }
}
