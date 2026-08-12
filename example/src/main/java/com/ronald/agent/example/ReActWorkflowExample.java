package com.ronald.agent.example;

import com.ronald.agent.workflow.ReActWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
 */
@Service
@RequiredArgsConstructor
public class ReActWorkflowExample {

    private final ChatClient chatClient;

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
        ReActWorkflow workflow = ReActWorkflow.builder()
                .chatClient(chatClient)
                .tools(this)
                .maxSteps(8)
                .build();

        return workflow.invoke(question);
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