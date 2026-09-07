package com.ronald.agent.example;

import com.ronald.agent.workflow.ReActWorkflow;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Demonstrates a {@link ReActWorkflow} that solves multi-step questions by reasoning
 * over a set of tools in a thought-action-observation loop.
 *
 * <p>Three tools are defined as {@code @Tool}-annotated methods on this class and
 * registered with the workflow by passing {@code this} to the builder:</p>
 * <ul>
 *   <li>{@link #wordCount(String)} — counts the words in a given text</li>
 *   <li>{@link #unitConverter(String, double)} — converts between common measurement units</li>
 *   <li>{@link #currentDate()} — returns the current date and time</li>
 * </ul>
 *
 * <p>Any MCP server tools are registered alongside them, showing the two routes into the same
 * loop. They are optional on purpose: {@code spring.ai.mcp.client.enabled} is {@code false} by
 * default, in which case no {@link ToolCallbackProvider} bean exists and the demo runs on the
 * local methods alone. Run with MCP servers attached via:</p>
 *
 * <pre>{@code
 * ./gradlew bootRun --args='--agent.demo=react --spring.ai.mcp.client.enabled=true'
 * }</pre>
 */
@Service
@RequiredArgsConstructor
public class ReActWorkflowExample {

    private static final Logger log = LoggerFactory.getLogger(ReActWorkflowExample.class);

    private final ChatClient chatClient;

    /**
     * Providers contributed by the MCP client autoconfiguration — empty when the client is
     * disabled, which is why this is an {@link ObjectProvider} rather than a direct dependency.
     */
    private final ObjectProvider<ToolCallbackProvider> mcpToolProviders;

    /**
     * The MCP tools this demo can reason over, resolved once at startup: {@code getToolCallbacks()}
     * is a round trip to every server, and the set does not change while the app runs. Empty until
     * {@link #resolveMcpTools()} runs, and permanently empty when the MCP client is disabled.
     */
    private List<ToolCallback> mcpTools = List.of();

    /** Resolves the MCP tools once and reports what the reasoning loop has to work with. */
    @PostConstruct
    void resolveMcpTools() {
        mcpTools = mcpToolProviders.stream()
                .map(ToolCallbackProvider::getToolCallbacks)
                .flatMap(Arrays::stream)
                .toList();

        if (mcpTools.isEmpty()) {
            log.info("mcp_tools_none — reasoning over the local @Tool methods only; "
                    + "start with --spring.ai.mcp.client.enabled=true to attach MCP servers");
            return;
        }

        log.info("mcp_tools_registered count={} names=[{}]", mcpTools.size(),
                mcpTools.stream()
                        .map(callback -> callback.getToolDefinition().name())
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Whether any MCP server tool is available.
     *
     * <p>Callers use this to decide what to ask: a question that only an MCP tool can answer has
     * no path to an answer without one, and the loop would spend its whole step budget
     * discovering that.</p>
     *
     * @return {@code true} if at least one MCP tool is registered
     */
    public boolean hasMcpTools() {
        return !mcpTools.isEmpty();
    }

    /**
     * Answers a question by running the ReAct reasoning loop with the tools defined
     * on this class.
     *
     * <p>The agent autonomously decides which tools to call and in what order,
     * accumulating observations until it has enough information to produce a final answer.</p>
     *
     * @param question the user's question (may require one or more tool calls to answer)
     * @return the agent's final answer string
     */
    public String answer(String question) {
        // Both registration routes feed one map, and an empty callback array is a no-op, so the
        // same chain covers the MCP-enabled and MCP-disabled runs.
        return ReActWorkflow.builder()
                .chatClient(chatClient)
                .tools(this)
                .toolCallbacks(mcpTools.toArray(ToolCallback[]::new))
                .maxSteps(8)
                .build()
                .invoke(question);
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    /**
     * Counts the number of words and characters in the given text.
     *
     * @param text the text to analyse
     * @return word and character count summary
     */
    @Tool(description = "Counts the number of words and characters in the given text.")
    public String wordCount(
            @ToolParam(description = "the text to count words in") String text) {
        if (text == null || text.isBlank()) {
            return "0 words, 0 characters";
        }
        int words = text.trim().split("\\s+").length;
        return words + " words, " + text.length() + " characters";
    }

    /**
     * Converts a numeric value between common units of measurement.
     *
     * @param conversion the conversion type
     * @param value      the numeric value to convert
     * @return the converted value with its unit
     */
    @Tool(description = "Converts a value between common units of measurement. "
            + "Supported conversions: celsius_to_fahrenheit, fahrenheit_to_celsius, km_to_miles, miles_to_km.")
    public String unitConverter(
            @ToolParam(description = "conversion type: celsius_to_fahrenheit, fahrenheit_to_celsius, km_to_miles, or miles_to_km") String conversion,
            @ToolParam(description = "the numeric value to convert") double value) {
        return switch (conversion.trim().toLowerCase()) {
            case "celsius_to_fahrenheit" -> String.format("%.2f °F", value * 9.0 / 5.0 + 32);
            case "fahrenheit_to_celsius" -> String.format("%.2f °C", (value - 32) * 5.0 / 9.0);
            case "km_to_miles"           -> String.format("%.4f miles", value * 0.621371);
            case "miles_to_km"           -> String.format("%.4f km", value * 1.60934);
            default -> "Unknown conversion '" + conversion + "'. Supported: "
                    + "celsius_to_fahrenheit, fahrenheit_to_celsius, km_to_miles, miles_to_km";
        };
    }

    /**
     * Returns the current date and time.
     *
     * @return a formatted date-time string
     */
    @Tool(description = "Returns the current date and time.")
    public String currentDate() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, HH:mm:ss"));
    }
}