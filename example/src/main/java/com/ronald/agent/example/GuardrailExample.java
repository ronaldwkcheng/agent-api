package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.subagent.guardrail.GuardrailedSubAgent;
import com.ronald.agent.subagent.guardrail.MaxLengthGuardrail;
import com.ronald.agent.subagent.guardrail.PiiGuardrail;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.SequentialAgentChain;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Demonstrates automated guardrails with {@link GuardrailedSubAgent}.
 *
 * <p>The same draft-reply step as {@link HumanReviewExample}, but gated by code instead of a
 * person: a {@link PiiGuardrail} redacts anything that looks like PII out of the complaint before
 * it reaches the model, and again out of the drafted reply; a {@link MaxLengthGuardrail} asks the
 * draft step to shorten itself if it runs long, using {@code {guardrailFeedback}} the same way
 * {@link HumanReviewExample} uses {@code {reviewFeedback}}.</p>
 */
@Service
@RequiredArgsConstructor
public class GuardrailExample {

    private final ChatClient chatClient;

    /**
     * Drafts a reply to a support complaint, with PII redaction on input and output and a length
     * cap on the reply.
     *
     * @param userComplaint the raw complaint text, possibly containing PII
     * @return the guarded reply
     */
    public String draftGuardedReply(String userComplaint) {

        DefaultPromptSubAgent replyStep = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("reply")
                .systemPrompt("You are a helpful customer support agent.")
                .promptTemplate("""
                        The user sent this complaint: {input}

                        Draft a short, friendly reply letting them know we are working on it.

                        Previous draft (empty if this is the first): {previousOutput}
                        Guardrail feedback to address (empty if none): {guardrailFeedback}
                        """)
                .build();

        PiiGuardrail pii = new PiiGuardrail();
        GuardrailedSubAgent guardedReply = GuardrailedSubAgent.builder()
                .delegate(replyStep)
                .inputGuardrail(pii)
                .outputGuardrail(pii)
                .outputGuardrail(new MaxLengthGuardrail(400))
                .stepName("customer reply")
                .maxRetries(2)
                .build();

        AgenticWorkflow<String> workflow = SequentialAgentChain.<String>builder()
                .addAgent(guardedReply)
                .build();

        return workflow.invoke(userComplaint);
    }
}
