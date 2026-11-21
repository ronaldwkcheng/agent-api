package com.ronald.agent.example;

import com.ronald.agent.subagent.rag.CorrectiveRagWorkflow;
import com.ronald.agent.subagent.rag.RagSubAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Demonstrates {@link CorrectiveRagWorkflow} grading and, if needed, retrying against the same
 * Chroma collection {@link RagSubAgentExample} reads from.
 *
 * <p>Reuses {@link RagSubAgentExample}'s already-built {@link RagSubAgent} and Chroma wiring
 * rather than repeating it, so this demo takes the same two flags that one does:</p>
 *
 * <pre>{@code
 * ./gradlew bootRun --args='--agent.demo=corrective-rag --spring.ai.vectorstore.type=chroma'
 * }</pre>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.demo", havingValue = "corrective-rag")
public class CorrectiveRagWorkflowExample {

    private final RagSubAgentExample ragSubAgentExample;
    private final ChatClient chatClient;

    /**
     * Answers a question through the grade-and-retry loop.
     *
     * @param question the question to answer
     * @return the grounded answer, or the best-effort answer under
     *         {@link com.ronald.agent.workflow.ExhaustionPolicy#RETURN_PARTIAL}
     * @throws IllegalStateException if no vector store was wired for this run
     */
    public String answer(String question) {
        RagSubAgent ragSubAgent = ragSubAgentExample.ragSubAgentOrNull();
        if (ragSubAgent == null) {
            throw new IllegalStateException("no VectorStore bean: run with "
                    + "--spring.ai.vectorstore.type=chroma");
        }

        CorrectiveRagWorkflow workflow = CorrectiveRagWorkflow.builder()
                .ragSubAgent(ragSubAgent)
                .chatClient(chatClient)
                .build();

        return workflow.invoke(question);
    }
}
