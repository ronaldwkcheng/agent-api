package com.ronald.agent.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ReActWorkflow.Builder} accepts ready-made {@link ToolCallback}s, not only
 * objects carrying {@code @Tool} methods.
 *
 * <p>This is what puts MCP server tools in the loop: the MCP client autoconfiguration hands out
 * a {@link ToolCallbackProvider}, never annotated methods, so a callback registered that way must
 * be selectable by name and actually invoked with the model's {@code actionInput}.</p>
 *
 * <p>The thinker agent's single structured-output call is mocked, so no network access and no API
 * key are involved. The mock returns the same thought every time; each test therefore runs one
 * step under {@link ExhaustionPolicy#RETURN_PARTIAL} rather than waiting for a final answer.</p>
 */
class ReActWorkflowToolRegistrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tool callback that records every input it is called with — an MCP callback's stand-in. */
    private static final class RecordingToolCallback implements ToolCallback {

        private final ToolDefinition definition;
        private final String         result;
        private final List<String>   invocations = new ArrayList<>();

        private RecordingToolCallback(String name, String result) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description("Recording stub standing in for the MCP tool " + name + ".")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                    .build();
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            invocations.add(toolInput);
            return result;
        }
    }

    /** A local {@code @Tool} source, to prove the two registration routes coexist. */
    static class LocalTools {
        @Tool(description = "Does nothing at all.")
        public String noop() {
            return "nothing";
        }
    }

    /** Mocks the one structured-output call the internal thinker agent makes. */
    private static ChatClient chatClientThinking(ReActWorkflow.ReActThought thought) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().messages(any(Message.class)).call().entity(any(Class.class)))
                .thenReturn(thought);
        return chatClient;
    }

    /** A non-final thought that asks for {@code toolName} with the given JSON arguments. */
    private static ReActWorkflow.ReActThought thoughtCalling(String toolName, String argumentsJson) {
        JsonNode actionInput;
        try {
            actionInput = MAPPER.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("test fixture is not valid JSON: " + argumentsJson, e);
        }
        return new ReActWorkflow.ReActThought("calling " + toolName, false, null, toolName, actionInput);
    }

    /** Runs exactly one thought/action/observation step and discards the partial result. */
    private static void runOneStep(ReActWorkflow.Builder builder) {
        builder.maxSteps(1)
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build()
                .invoke("a question");
    }

    @Test
    void prebuiltCallbackIsSelectableByNameAndReceivesTheActionInput() {
        RecordingToolCallback search = new RecordingToolCallback("mcp_search", "one result");

        runOneStep(ReActWorkflow.builder()
                .chatClient(chatClientThinking(thoughtCalling("mcp_search", "{\"query\":\"spring ai\"}")))
                .toolCallbacks(search));

        assertEquals(List.of("{\"query\":\"spring ai\"}"), search.invocations,
                "the callback must be invoked with the model's actionInput verbatim");
    }

    @Test
    void providerContributesEveryCallbackItExposes() {
        RecordingToolCallback first  = new RecordingToolCallback("mcp_read", "file contents");
        RecordingToolCallback second = new RecordingToolCallback("mcp_write", "written");

        runOneStep(ReActWorkflow.builder()
                .chatClient(chatClientThinking(thoughtCalling("mcp_write", "{\"query\":\"x\"}")))
                .toolCallbackProvider(ToolCallbackProvider.from(first, second)));

        assertTrue(first.invocations.isEmpty(), "only the named tool should run");
        assertEquals(1, second.invocations.size(),
                "a callback beyond the first must still be reachable");
    }

    @Test
    void prebuiltCallbacksCoexistWithAnnotatedToolObjects() {
        RecordingToolCallback search = new RecordingToolCallback("mcp_search", "one result");

        runOneStep(ReActWorkflow.builder()
                .chatClient(chatClientThinking(thoughtCalling("mcp_search", "{\"query\":\"spring ai\"}")))
                .tools(new LocalTools())
                .toolCallbacks(search));

        assertEquals(1, search.invocations.size(),
                "registering local @Tool methods must not displace prebuilt callbacks");
    }

    @Test
    void laterRegistrationOfTheSameToolNameWins() {
        RecordingToolCallback stale   = new RecordingToolCallback("mcp_search", "stale");
        RecordingToolCallback current = new RecordingToolCallback("mcp_search", "current");

        runOneStep(ReActWorkflow.builder()
                .chatClient(chatClientThinking(thoughtCalling("mcp_search", "{\"query\":\"x\"}")))
                .toolCallbacks(stale)
                .toolCallbacks(current));

        assertTrue(stale.invocations.isEmpty(), "the replaced callback must not be called");
        assertEquals(1, current.invocations.size());
    }

    @Test
    void prebuiltCallbackSatisfiesTheAtLeastOneToolRequirement() {
        assertDoesNotThrow(() -> ReActWorkflow.builder()
                .chatClient(mock(ChatClient.class))
                .toolCallbacks(new RecordingToolCallback("mcp_search", "one result"))
                .build());
    }

    @Test
    void providerExposingNoToolsStillFailsTheBuild() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ReActWorkflow.builder()
                        .chatClient(mock(ChatClient.class))
                        .toolCallbackProvider(ToolCallbackProvider.from(List.of()))
                        .build());

        assertTrue(ex.getMessage().contains("At least one tool"), ex.getMessage());
    }

    @Test
    void rejectsNullProviderAndNullCallbacks() {
        assertThrows(NullPointerException.class,
                () -> ReActWorkflow.builder().toolCallbackProvider(null));
        assertThrows(NullPointerException.class,
                () -> ReActWorkflow.builder().toolCallbacks((ToolCallback[]) null));
        assertThrows(NullPointerException.class,
                () -> ReActWorkflow.builder().toolCallbacks(new ToolCallback[]{null}));
    }
}
