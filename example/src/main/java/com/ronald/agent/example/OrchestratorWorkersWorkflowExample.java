package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.OrchestratorWorkersWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * Demonstrates {@link OrchestratorWorkersWorkflow}: an orchestrator LLM decides, at runtime, how
 * many independent subtasks a request should be split into, a single worker agent handles each
 * subtask concurrently, and a synthesizer combines the results into one document.
 *
 * <p>Unlike {@link ParallelWorkflowExample}, where the three specialist branches are fixed at
 * build time, the number and content of the subtasks here come from the model's own breakdown of
 * whatever task it is given.</p>
 */
@Service
@RequiredArgsConstructor
public class OrchestratorWorkersWorkflowExample {

    private final ChatClient chatClient;
    private final Executor executor;

    /**
     * Breaks the given writing task into independent sections, drafts each one concurrently, and
     * assembles them into a single cohesive document.
     *
     * @param task the writing task to carry out
     * @return the assembled document
     */
    public String write(String task) {

        DefaultPromptSubAgent worker = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("workerResult")
                .systemPrompt("""
                        You are a skilled writer completing one section of a larger document.
                        Focus exclusively on your assigned subtask. Stay consistent with the
                        overall breakdown, but do not repeat content that belongs to another
                        subtask.
                        """)
                .promptTemplate("""
                        Overall task: {input}

                        Full breakdown of subtasks:
                        {breakdown}

                        Your subtask ({subtaskId}): {subtask}

                        Write this section now.
                        """)
                .build();

        DefaultPromptSubAgent synthesizer = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .systemPrompt("""
                        You are an editor assembling a final document from independently drafted
                        sections. Smooth over any inconsistencies in tone, remove duplication
                        between sections, and present one cohesive result.
                        """)
                .promptTemplate("""
                        Overall task: {input}

                        Breakdown that was worked:
                        {breakdown}

                        Drafted sections:
                        {results}

                        Produce the final, cohesive document.
                        """)
                .build();

        AgenticWorkflow<String> workflow = OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(chatClient)
                .worker(worker)
                .synthesizer(synthesizer)
                .executor(executor)
                .maxSubtasks(6)
                .build();

        return workflow.invoke(task);
    }
}
