package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.PlanAndExecuteWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Demonstrates a {@link PlanAndExecuteWorkflow} that solves a complex research task
 * by first generating an explicit plan, executing each step in sequence, and then
 * synthesizing the results into a final structured report.
 *
 * <p>The workflow uses three agents:</p>
 * <ul>
 *   <li><b>Planner</b>      — built into the workflow; decomposes the task into steps</li>
 *   <li><b>Step executor</b> — a research specialist that executes one step at a time,
 *       using the results of previous steps as context</li>
 *   <li><b>Synthesizer</b>  — combines all step results into a coherent final report</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PlanAndExecuteWorkflowExample {

    private final ChatClient chatClient;

    /**
     * Plans and executes a multi-step research task, then synthesizes the results.
     *
     * @param topic the research topic or question to investigate
     * @return a structured final report synthesized from all executed steps
     */
    public String research(String topic) {

        DefaultPromptSubAgent stepExecutor = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("stepResult")
                .systemPrompt("""
                        You are a knowledgeable research specialist executing one step of a structured research plan.
                        Focus exclusively on the current step. Use insights from previous steps where relevant.
                        Be thorough, factual, and concise.
                        """)
                .promptTemplate("""
                        Research task: {input}

                        Full plan:
                        {plan}

                        Results from previous steps:
                        {previousResults}

                        Current step {stepId}: {stepDescription}

                        Execute this step thoroughly and provide a detailed, well-structured response.
                        """)
                .build();

        DefaultPromptSubAgent synthesizer = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .systemPrompt("""
                        You are an expert analyst producing a final research report.
                        Synthesize all step results into a cohesive, well-structured report.
                        Eliminate redundancy, resolve any contradictions, and highlight key insights.
                        """)
                .promptTemplate("""
                        Research task: {input}

                        Plan that was executed:
                        {plan}

                        Step-by-step research results:
                        {stepResults}

                        Produce a comprehensive final report with clear sections, key findings,
                        and a concise summary.
                        """)
                .build();

        AgenticWorkflow<String> workflow = PlanAndExecuteWorkflow.<String>builder()
                .chatClient(chatClient)
                .stepExecutor(stepExecutor)
                .synthesizer(synthesizer)
                .maxSteps(6)
                .build();

        return workflow.invoke(topic);
    }
}