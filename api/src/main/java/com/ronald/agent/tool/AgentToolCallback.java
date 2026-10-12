package com.ronald.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.util.json.JsonParser;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Adapts a {@link SubAgent} or an {@link AgenticWorkflow} to a Spring AI {@link ToolCallback}, so
 * that a model can call it as a tool.
 *
 * <p>The main consumer is {@link com.ronald.agent.workflow.ReActWorkflow}: registering an adapted
 * agent through {@code toolCallbacks(...)} lets the ReAct loop delegate to it, which turns ReAct
 * into a supervisor over other agents and lets any workflow be nested inside another. Because the
 * result is an ordinary {@code ToolCallback}, it works equally with Spring AI's native tool calling
 * ({@code ChatClient.prompt().toolCallbacks(...)}).</p>
 *
 * <h2>Arguments</h2>
 * <p>Each {@link Builder#parameter declared parameter} becomes a required string property of the
 * tool's JSON input schema. When the tool is called:</p>
 * <ul>
 *   <li>a <b>sub-agent</b> receives every argument as an entry of its context map, so a parameter
 *       named after a prompt placeholder fills that placeholder directly;</li>
 *   <li>a <b>workflow</b> receives the {@code input} argument as its input and every other argument
 *       as an attribute (see {@link AgenticWorkflow#invoke(String, Map)}).</li>
 * </ul>
 * <p>With no parameters declared, the tool takes the single parameter {@value #DEFAULT_PARAMETER}.
 * Undeclared arguments the model sends are ignored. A non-string argument is passed on as its
 * JSON text.</p>
 *
 * <p>{@link SubAgent#CONVERSATION_ID} cannot be declared as a parameter — a model must not choose
 * whose chat memory a call reads. When the caller supplies it through the {@link ToolContext}
 * instead, as native tool calling does with {@code ChatClient...toolContext(...)}, it is forwarded
 * to the agent or workflow.</p>
 *
 * <h2>Result</h2>
 * <p>A {@code String} result is returned as is. Any other result is serialized to JSON, unless a
 * {@link Builder#resultConverter custom converter} is set.</p>
 *
 * <h2>Errors</h2>
 * <p>Malformed JSON, a missing argument, and any exception the agent or workflow throws are
 * reported as a {@link ToolExecutionException} carrying this tool's definition, the same contract
 * as Spring AI's own method tools. The original exception — a
 * {@link com.ronald.agent.workflow.WorkflowExhaustedException}, say — is its cause.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * ToolCallback research = AgentToolCallback.builder()
 *     .name("research")
 *     .description("Answers questions from the product documentation.")
 *     .workflow(ragSubAgent)
 *     .build();
 *
 * ToolCallback translate = AgentToolCallback.builder()
 *     .name("translate")
 *     .description("Translates text into another language.")
 *     .subAgent(translatorAgent)                       // template uses {text} and {language}
 *     .parameter("text", "The text to translate.")
 *     .parameter("language", "The target language, e.g. French.")
 *     .build();
 *
 * ReActWorkflow supervisor = ReActWorkflow.builder()
 *     .chatClient(chatClient)
 *     .toolCallbacks(research, translate)
 *     .build();
 * }</pre>
 */
public final class AgentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(AgentToolCallback.class);

    /** The parameter a tool takes when none is declared, and the one a workflow reads its input from. */
    public static final String DEFAULT_PARAMETER = "input";

    private static final String DEFAULT_PARAMETER_DESCRIPTION =
            "The task or question for this tool, in plain language.";

    /** The tool-name rule shared by the OpenAI and Anthropic function-calling APIs. */
    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private static final ObjectMapper MAPPER = JsonParser.getObjectMapper();

    private final ToolDefinition                        definition;
    private final Map<String, String>                   parameters;
    private final Function<Map<String, String>, Object> target;
    private final ToolCallResultConverter               resultConverter;

    private AgentToolCallback(Builder builder, Map<String, String> parameters) {
        this.parameters      = Map.copyOf(parameters);
        this.target          = builder.target;
        this.resultConverter = builder.resultConverter;
        this.definition      = ToolDefinition.builder()
                .name(builder.name)
                .description(builder.description)
                .inputSchema(inputSchema(parameters))
                .build();
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    /**
     * Calls the adapted agent or workflow with the given JSON arguments.
     *
     * @param toolInput the JSON object the model produced for this tool's input schema
     * @return the converted result
     * @throws ToolExecutionException if the arguments are invalid or the agent or workflow fails
     */
    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * As {@link #call(String)}, forwarding {@link SubAgent#CONVERSATION_ID} from the tool context
     * when present. Every other tool-context entry is ignored.
     *
     * @param toolInput   the JSON object the model produced for this tool's input schema
     * @param toolContext the caller's tool context; may be null
     * @return the converted result
     * @throws ToolExecutionException if the arguments are invalid or the agent or workflow fails
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, String> arguments = parseArguments(toolInput);
        if (toolContext != null) {
            Object conversationId = toolContext.getContext().get(SubAgent.CONVERSATION_ID);
            if (conversationId != null) {
                arguments.put(SubAgent.CONVERSATION_ID, conversationId.toString());
            }
        }

        log.debug("agent_tool_call tool=\"{}\" arguments={}", definition.name(), arguments.keySet());
        Object result;
        try {
            result = target.apply(arguments);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("agent_tool_failed tool=\"{}\" error=\"{}\"", definition.name(), e.toString());
            throw new ToolExecutionException(definition, e);
        }
        if (result == null) {
            throw failure("returned null");
        }
        return resultConverter.convert(result, result.getClass());
    }

    private Map<String, String> parseArguments(String toolInput) {
        JsonNode json;
        try {
            json = MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        } catch (JsonProcessingException e) {
            throw failure("received arguments that are not valid JSON: " + toolInput);
        }
        if (!json.isObject()) {
            throw failure("expects a JSON object of arguments, got: " + toolInput);
        }

        Map<String, String> arguments = new HashMap<>();
        for (String parameter : parameters.keySet()) {
            JsonNode value = json.get(parameter);
            if (value == null || value.isNull()) {
                throw failure("is missing the required argument '" + parameter + "'");
            }
            arguments.put(parameter, value.isTextual() ? value.asText() : value.toString());
        }
        return arguments;
    }

    private ToolExecutionException failure(String problem) {
        return new ToolExecutionException(definition,
                new IllegalArgumentException("Tool '" + definition.name() + "' " + problem));
    }

    private static String inputSchema(Map<String, String> parameters) {
        ObjectNode schema     = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode  required   = schema.putArray("required");
        parameters.forEach((name, description) -> {
            properties.putObject(name).put("type", "string").put("description", description);
            required.add(name);
        });
        schema.put("additionalProperties", false);
        return schema.toString();
    }

    /**
     * Passes a {@code String} result through unchanged and serializes anything else to JSON.
     * Spring AI's {@link DefaultToolCallResultConverter} would serialize a string too, wrapping
     * agent prose in JSON quotes and escapes.
     */
    private static final class AgentResultConverter implements ToolCallResultConverter {

        private static final ToolCallResultConverter JSON = new DefaultToolCallResultConverter();

        @Override
        public String convert(Object result, Type returnType) {
            return result instanceof String text ? text : JSON.convert(result, returnType);
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link AgentToolCallback}. Exactly one of {@link #subAgent(SubAgent)} and
     * {@link #workflow(AgenticWorkflow)} must be set.
     */
    public static final class Builder {

        private String                                name;
        private String                                description;
        private Function<Map<String, String>, Object> target;
        private boolean                               isWorkflow;
        private final Map<String, String>             parameters      = new LinkedHashMap<>();
        private ToolCallResultConverter               resultConverter = new AgentResultConverter();

        private Builder() {
        }

        /**
         * Sets the tool name the model calls. Must match {@code [a-zA-Z0-9_-]{1,64}}.
         *
         * @param name the tool name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the description the model reads to decide when to call the tool. This is the only
         * thing the model knows about the agent, so say what it does and what it returns.
         *
         * @param description the tool description
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Adapts a sub-agent. Each tool argument becomes an entry of its context map.
         *
         * @param subAgent the agent to call
         * @return this builder
         * @throws NullPointerException  if subAgent is null
         * @throws IllegalStateException if a target is already set
         */
        public Builder subAgent(SubAgent<?> subAgent) {
            Objects.requireNonNull(subAgent, "subAgent must not be null");
            return target(subAgent::execute, false);
        }

        /**
         * Adapts a workflow. The {@code "input"} argument is its input; any other
         * argument is passed as an attribute.
         *
         * @param workflow the workflow to call
         * @return this builder
         * @throws NullPointerException  if workflow is null
         * @throws IllegalStateException if a target is already set
         */
        public Builder workflow(AgenticWorkflow<?> workflow) {
            Objects.requireNonNull(workflow, "workflow must not be null");
            return target(arguments -> {
                Map<String, String> attributes = new HashMap<>(arguments);
                String input = attributes.remove(DEFAULT_PARAMETER);
                return workflow.invoke(input, attributes);
            }, true);
        }

        private Builder target(Function<Map<String, String>, Object> target, boolean isWorkflow) {
            if (this.target != null) {
                throw new IllegalStateException("Set exactly one of subAgent(...) and workflow(...)");
            }
            this.target     = target;
            this.isWorkflow = isWorkflow;
            return this;
        }

        /**
         * Declares a required string parameter of the tool. Declaring the same name again replaces
         * its description. With none declared, the tool takes only {@code "input"}.
         *
         * @param name        the argument name — a context key for a sub-agent, an attribute name
         *                    (or {@code "input"}) for a workflow
         * @param description what the model should put in this argument
         * @return this builder
         * @throws NullPointerException     if either argument is null
         * @throws IllegalArgumentException if name is blank or is {@link SubAgent#CONVERSATION_ID}
         */
        public Builder parameter(String name, String description) {
            Objects.requireNonNull(name, "parameter name must not be null");
            Objects.requireNonNull(description, "parameter description must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("parameter name must not be blank");
            }
            if (SubAgent.CONVERSATION_ID.equals(name)) {
                throw new IllegalArgumentException("'" + SubAgent.CONVERSATION_ID + "' is reserved and "
                        + "cannot be a tool parameter; pass it through the ToolContext instead");
            }
            parameters.put(name, description);
            return this;
        }

        /**
         * Replaces how a non-null result becomes the tool's response text. The default passes a
         * {@code String} through and serializes anything else to JSON.
         *
         * @param resultConverter the converter
         * @return this builder
         * @throws NullPointerException if resultConverter is null
         */
        public Builder resultConverter(ToolCallResultConverter resultConverter) {
            this.resultConverter = Objects.requireNonNull(resultConverter, "resultConverter must not be null");
            return this;
        }

        /**
         * Builds the tool callback.
         *
         * @return the configured callback
         * @throws NullPointerException     if name, description or the target is not set
         * @throws IllegalArgumentException if the name is not a valid tool name, the description is
         *                                  blank, or a workflow's parameters omit
         *                                  {@code "input"}
         */
        public AgentToolCallback build() {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(description, "description must not be null");
            Objects.requireNonNull(target, "a subAgent(...) or workflow(...) must be set");
            if (!TOOL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("name must match [a-zA-Z0-9_-]{1,64}, got: " + name);
            }
            if (description.isBlank()) {
                throw new IllegalArgumentException("description must not be blank");
            }

            Map<String, String> effective = new LinkedHashMap<>(parameters);
            if (effective.isEmpty()) {
                effective.put(DEFAULT_PARAMETER, DEFAULT_PARAMETER_DESCRIPTION);
            } else if (isWorkflow && !effective.containsKey(DEFAULT_PARAMETER)) {
                throw new IllegalArgumentException("A workflow tool must declare the '" + DEFAULT_PARAMETER
                        + "' parameter, which becomes the workflow's input");
            }
            return new AgentToolCallback(this, effective);
        }
    }
}
