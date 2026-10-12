package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.subagent.review.HumanReviewSubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.SequentialAgentChain;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Demonstrates human-in-the-loop with {@link HumanReviewSubAgent}.
 *
 * <p>The same summarise → draft chain as {@link SequentialWorkflowExample}, except the customer
 * reply is held for a person to approve before it is returned. Asking for a revision re-runs the
 * draft step with the feedback in {@code {reviewFeedback}} and the rejected text in
 * {@code {previousDraft}}.</p>
 */
@Service
@RequiredArgsConstructor
public class HumanReviewExample {

    private final ChatClient chatClient;

    /**
     * Drafts a reply to a support complaint, pausing for console approval of the draft.
     *
     * @param userComplaint the raw complaint text
     * @return the approved (or human-edited) reply
     */
    public String draftApprovedReply(String userComplaint) {

        DefaultPromptSubAgent summarizeStep = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("summary")
                .promptTemplate("Summarize the following support request into 3 bullet points: {input}")
                .build();

        // Both review keys are always present (empty on the first attempt), so the template can
        // reference them unconditionally.
        DefaultPromptSubAgent replyStep = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("reply")
                .systemPrompt("You are a helpful customer support agent.")
                .promptTemplate("""
                        The user sent this complaint: {input}
                        Our internal summary is: {summary}

                        Draft a short, friendly reply letting them know we are working on it.

                        Previous draft (empty if this is the first): {previousDraft}
                        Reviewer feedback to address (empty if none): {reviewFeedback}
                        """)
                .build();

        HumanReviewSubAgent reviewedReply = HumanReviewSubAgent.builder()
                .delegate(replyStep)
                .reviewer(new ConsoleHumanReviewer())
                .stepName("customer reply")
                .maxRevisions(3)
                .build();

        AgenticWorkflow<String> workflow = SequentialAgentChain.<String>builder()
                .addAgents(summarizeStep, reviewedReply)
                .build();

        return workflow.invoke(userComplaint);
    }
}
