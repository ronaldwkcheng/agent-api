package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.ParallelAgentOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * Demonstrates a parallel multi-agent workflow using {@link ParallelAgentOrchestrator}.
 * <p>
 * Three specialist agents (sentiment, safety, translation) analyse the input concurrently;
 * their reports are then synthesised by a single aggregator agent into one executive summary.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ParallelWorkflowExample {

    private final ChatClient chatClient;
    private final Executor executor;

    /**
     * Runs a parallel content-analysis pipeline on the supplied text.
     * <p>The following analyses run concurrently:</p>
     * <ul>
     *   <li><b>Sentiment</b>    – classifies overall tone as POSITIVE / NEUTRAL / NEGATIVE.</li>
     *   <li><b>Safety</b>       – flags sensitive or harmful content (SAFE / CAUTION / UNSAFE).</li>
     *   <li><b>Translation</b>  – produces a French translation.</li>
     * </ul>
     * <p>An aggregator agent then combines all reports into a cohesive analytics summary.</p>
     *
     * @param text the input text to analyse
     * @return a bullet-point executive summary covering sentiment, safety, and translation quality
     */
    public String analysis(String text) {

        DefaultPromptSubAgent safetyAgent = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("safety")
                .systemPrompt("You are a content safety auditor. Be concise and flag any concerns clearly.")
                .promptTemplate("""
                         Audit the following text for sensitive, harmful, or toxic content.
                         Classify as SAFE, CAUTION, or UNSAFE, and briefly explain why.
                         Text: {input}
                        """)
                .build();

        DefaultPromptSubAgent sentimentAgent = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("sentiment")
                .systemPrompt("You are a sentiment analyzer. Be concise and focus on the overall sentiment.")
                .promptTemplate("""
                         Analyze the sentiment of the following text. Classify as POSITIVE, NEUTRAL, or NEGATIVE, and briefly explain why.
                         Text: {input}
                        """)
                .build();

        DefaultPromptSubAgent translationAgent = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("translation")
                .systemPrompt("You are a translation expert. Translate the input text to French.")
                .promptTemplate("Translate the following text into French, preserving tone and nuance: {input}")
                .build();

        DefaultPromptSubAgent aggregatorAgent = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                // no outputKey — anonymous final step
                .systemPrompt("""
                         You are a Senior Content Analyst.
                         Synthesize reports from multiple specialists into one professional executive summary.
                         Resolve any contradictions, highlight critical safety warnings, and be concise.
                        """)
                .promptTemplate("""
                        Original Text:
                        {input}
                        
                        Specialist Reports:
                        {reports}
                        
                        Produce a final cohesive Analytics Report in bullet-point form covering:
                        - Overall sentiment and emotional tone
                        - Safety classification and any warnings
                        - French translation quality note
                        """)
                .build();

        AgenticWorkflow<String> workflow = ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(sentimentAgent)     // → {sentiment}
                .addSubAgent(safetyAgent)        // → {safety}
                .addSubAgent(translationAgent)   // → {translation}
                .aggregator(aggregatorAgent)     // reads {input}, {sentiment}, {safety}, {translation}, {reports}
                .executor(executor)
                .build();

        return workflow.invoke(text);
    }
}