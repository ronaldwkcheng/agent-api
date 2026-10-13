package com.ronald.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.ExhaustionPolicy;
import com.ronald.agent.workflow.ReActWorkflow;
import com.ronald.agent.workflow.WorkflowExhaustedException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies {@link AgentToolCallback}: the input schema it advertises, how it maps tool arguments
 * onto a sub-agent's context or a workflow's input and attributes, result conversion, error
 * reporting, and — end to end — that {@link ReActWorkflow} can call a wrapped workflow as a tool.
 *
 * <p>Every agent and workflow here is a stub; the one {@code ChatClient} is a Mockito mock, so no
 * API key and no network are involved.</p>
 */
class AgentToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sub-agent stub that records the context it receives and returns a fixed result. */
    private static final class RecordingSubAgent<T> implements SubAgent<T> {

        private final T                         result;
        private final List<Map<String, String>> contexts = new ArrayList<>();

        private RecordingSubAgent(T result) {
            this.result = result;
        }

        @Override
        public String getOutputKey() {
            return "recorded";
        }

        @Override
        public T execute(Map<String, String> context) {
            contexts.add(new HashMap<>(context));
            return result;
        }
    }

    /** Workflow stub that records its input and attributes. */
    private static final class RecordingWorkflow implements AgenticWorkflow<String> {

        private final List<String>              inputs     = new ArrayList<>();
        private final List<Map<String, String>> attributes = new ArrayList<>();

        @Override
        public String invoke(String input, Map<String, String> attributes) {
            inputs.add(input);
            this.attributes.add(Map.copyOf(attributes));
            return "workflow answered: " + input;
        }
    }

    record Verdict(String label, int score) {}

    private static AgentToolCallback.Builder named(String name) {
        return AgentToolCallback.builder().name(name).description("Stub tool " + name + ".");
    }

    private static JsonNode schemaOf(AgentToolCallback tool) throws Exception {
        return MAPPER.readTree(tool.getToolDefinition().inputSchema());
    }

    // -------------------------------------------------------------------------
    // Tool definition
    // -------------------------------------------------------------------------

    @Test
    void definitionCarriesNameDescriptionAndDefaultInputSchema() throws Exception {
        AgentToolCallback tool = named("research").subAgent(new RecordingSubAgent<>("x")).build();

        assertEquals("research", tool.getToolDefinition().name());
        assertEquals("Stub tool research.", tool.getToolDefinition().description());

        JsonNode schema = schemaOf(tool);
        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.at("/properties/input/type").asText());
        assertFalse(schema.at("/properties/input/description").asText().isBlank());
        assertEquals(List.of("input"), MAPPER.convertValue(schema.get("required"), List.class));
    }

    @Test
    void declaredParametersReplaceTheDefaultAndAreAllRequiredInOrder() throws Exception {
        AgentToolCallback tool = named("translate")
                .subAgent(new RecordingSubAgent<>("x"))
                .parameter("text", "The text to translate.")
                .parameter("language", "The target language.")
                .build();

        JsonNode schema = schemaOf(tool);
        assertTrue(schema.at("/properties/input").isMissingNode(), "the default parameter must not linger");
        assertEquals("The target language.", schema.at("/properties/language/description").asText());
        assertEquals(List.of("text", "language"), MAPPER.convertValue(schema.get("required"), List.class));
    }

    // -------------------------------------------------------------------------
    // Sub-agent target
    // -------------------------------------------------------------------------

    @Test
    void subAgentReceivesEveryDeclaredArgumentAsAContextEntry() {
        RecordingSubAgent<String> agent = new RecordingSubAgent<>("bonjour");
        AgentToolCallback tool = named("translate")
                .subAgent(agent)
                .parameter("text", "The text.")
                .parameter("language", "The language.")
                .build();

        String result = tool.call("{\"text\":\"hello\",\"language\":\"French\",\"extra\":\"ignored\"}");

        assertEquals("bonjour", result, "a String result must pass through unquoted");
        assertEquals(List.of(Map.of("text", "hello", "language", "French")), agent.contexts,
                "undeclared arguments must not reach the agent");
    }

    @Test
    void nonStringArgumentsArePassedAsTheirJsonText() {
        RecordingSubAgent<String> agent = new RecordingSubAgent<>("ok");
        AgentToolCallback tool = named("filter").subAgent(agent).parameter("criteria", "Filter.").build();

        tool.call("{\"criteria\":{\"year\":2026}}");

        assertEquals("{\"year\":2026}", agent.contexts.getFirst().get("criteria"));
    }

    @Test
    void typedResultIsSerializedToJson() throws Exception {
        AgentToolCallback tool = named("judge").subAgent(new RecordingSubAgent<>(new Verdict("good", 9))).build();

        JsonNode result = MAPPER.readTree(tool.call("{\"input\":\"draft\"}"));

        assertEquals("good", result.get("label").asText());
        assertEquals(9, result.get("score").asInt());
    }

    @Test
    void customResultConverterIsApplied() {
        AgentToolCallback tool = named("judge")
                .subAgent(new RecordingSubAgent<>(new Verdict("good", 9)))
                .resultConverter((result, type) -> "label=" + ((Verdict) result).label())
                .build();

        assertEquals("label=good", tool.call("{\"input\":\"draft\"}"));
    }

    // -------------------------------------------------------------------------
    // Workflow target
    // -------------------------------------------------------------------------

    @Test
    void workflowTakesTheInputArgumentAsInputAndTheRestAsAttributes() {
        RecordingWorkflow workflow = new RecordingWorkflow();
        AgentToolCallback tool = named("plan")
                .workflow(workflow)
                .parameter("input", "The goal.")
                .parameter("audience", "Who the plan is for.")
                .build();

        String result = tool.call("{\"input\":\"ship v2\",\"audience\":\"engineers\"}");

        assertEquals("workflow answered: ship v2", result);
        assertEquals(List.of("ship v2"), workflow.inputs);
        assertEquals(List.of(Map.of("audience", "engineers")), workflow.attributes,
                "input must not be duplicated into the attributes");
    }

    @Test
    void workflowWithoutDeclaredParametersTakesOnlyInput() {
        RecordingWorkflow workflow = new RecordingWorkflow();
        AgentToolCallback tool = named("rag").workflow(workflow).build();

        tool.call("{\"input\":\"what is RAG?\"}");

        assertEquals(List.of("what is RAG?"), workflow.inputs);
        assertEquals(List.of(Map.of()), workflow.attributes);
    }

    // -------------------------------------------------------------------------
    // Conversation id
    // -------------------------------------------------------------------------

    @Test
    void conversationIdIsForwardedFromTheToolContextOnly() {
        RecordingWorkflow workflow = new RecordingWorkflow();
        AgentToolCallback tool = named("rag").workflow(workflow).build();

        tool.call("{\"input\":\"q\"}",
                new ToolContext(Map.of(SubAgent.CONVERSATION_ID, "c-42", "tenant", "acme")));

        assertEquals(List.of(Map.of(SubAgent.CONVERSATION_ID, "c-42")), workflow.attributes,
                "only the conversation id may cross from the tool context");
    }

    @Test
    void conversationIdCannotBeDeclaredAsAParameter() {
        assertThrows(IllegalArgumentException.class,
                () -> named("rag").parameter(SubAgent.CONVERSATION_ID, "Whose memory."));
    }

    @Test
    void conversationIdSentByTheModelIsIgnored() {
        RecordingSubAgent<String> agent = new RecordingSubAgent<>("ok");
        AgentToolCallback tool = named("chat").subAgent(agent).build();

        tool.call("{\"input\":\"hi\",\"conversationId\":\"someone-else\"}");

        assertFalse(agent.contexts.getFirst().containsKey(SubAgent.CONVERSATION_ID),
                "a model must not be able to pick whose chat memory is read");
    }

    // -------------------------------------------------------------------------
    // Errors
    // -------------------------------------------------------------------------

    @Test
    void missingArgumentIsAToolExecutionException() {
        AgentToolCallback tool = named("translate")
                .subAgent(new RecordingSubAgent<>("x"))
                .parameter("text", "The text.")
                .parameter("language", "The language.")
                .build();

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> tool.call("{\"text\":\"hello\"}"));

        assertEquals("translate", ex.getToolDefinition().name());
        assertTrue(ex.getMessage().contains("'language'"), ex.getMessage());
    }

    @Test
    void malformedOrNonObjectArgumentsAreToolExecutionExceptions() {
        AgentToolCallback tool = named("rag").subAgent(new RecordingSubAgent<>("x")).build();

        assertThrows(ToolExecutionException.class, () -> tool.call("{not json"));
        assertThrows(ToolExecutionException.class, () -> tool.call("[\"input\"]"));
        assertThrows(ToolExecutionException.class, () -> tool.call(""),
                "an empty argument object still lacks the required input");
    }

    @Test
    void targetFailureIsWrappedWithTheOriginalAsCause() {
        WorkflowExhaustedException exhausted = new WorkflowExhaustedException("out of steps", 3, "partial");
        AgentToolCallback tool = named("inner").workflow((input, attributes) -> {
            throw exhausted;
        }).build();

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> tool.call("{\"input\":\"q\"}"));

        assertSame(exhausted, ex.getCause());
        assertEquals("inner", ex.getToolDefinition().name());
    }

    @Test
    void nestedToolExecutionExceptionIsNotWrappedTwice() {
        AgentToolCallback inner = named("inner").subAgent(new RecordingSubAgent<>("x"))
                .parameter("text", "Text.").build();
        AgentToolCallback outer = named("outer").workflow((input, attributes) -> inner.call("{}")).build();

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> outer.call("{\"input\":\"q\"}"));

        assertEquals("inner", ex.getToolDefinition().name(), "the failing tool must stay identifiable");
    }

    @Test
    void nullResultIsAToolExecutionException() {
        AgentToolCallback tool = named("silent").subAgent(new RecordingSubAgent<String>(null)).build();

        assertThrows(ToolExecutionException.class, () -> tool.call("{\"input\":\"q\"}"));
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    void buildRejectsMissingOrInvalidConfiguration() {
        RecordingSubAgent<String> agent = new RecordingSubAgent<>("x");

        assertThrows(NullPointerException.class,
                () -> AgentToolCallback.builder().description("d").subAgent(agent).build());
        assertThrows(NullPointerException.class,
                () -> AgentToolCallback.builder().name("n").subAgent(agent).build());
        assertThrows(NullPointerException.class, () -> named("n").build());
        assertThrows(IllegalArgumentException.class, () -> named("has space").subAgent(agent).build());
        assertThrows(IllegalArgumentException.class, () -> named("x".repeat(65)).subAgent(agent).build());
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolCallback.builder().name("n").description(" ").subAgent(agent).build());
        assertThrows(IllegalArgumentException.class, () -> named("n").parameter(" ", "d"));
    }

    @Test
    void exactlyOneTargetMayBeSet() {
        assertThrows(IllegalStateException.class,
                () -> named("n").subAgent(new RecordingSubAgent<>("x")).workflow(new RecordingWorkflow()));
    }

    @Test
    void workflowParametersMustIncludeInput() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> named("plan").workflow(new RecordingWorkflow()).parameter("goal", "The goal.").build());

        assertTrue(ex.getMessage().contains("'input'"), ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // End to end: a workflow as a ReAct tool
    // -------------------------------------------------------------------------

    @Test
    void reactWorkflowCallsAWrappedWorkflowAndObservesItsAnswer() throws Exception {
        RecordingWorkflow inner = new RecordingWorkflow();
        AgentToolCallback research = named("research").workflow(inner).build();

        List<String> prompts = new ArrayList<>();
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0, Message.class).getText());
            return spec;
        });
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call().entity(any(Class.class))).thenReturn(new ReActWorkflow.ReActThought(
                "delegate to research", false, null, "research",
                MAPPER.readTree("{\"input\":\"what is RAG?\"}")));

        ReActWorkflow.builder()
                .chatClient(chatClient)
                .toolCallbacks(research)
                .maxSteps(2)
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build()
                .invoke("a question");

        assertEquals("what is RAG?", inner.inputs.getFirst(), "the inner workflow must receive the model's input");
        assertTrue(prompts.getFirst().contains(research.getToolDefinition().inputSchema()),
                "the ReAct prompt must advertise the adapter's input schema");
        assertTrue(prompts.get(1).contains("Observation: workflow answered: what is RAG?"),
                () -> "the inner answer must reach the scratchpad: " + prompts.get(1));
    }
}
