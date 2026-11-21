package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.VotingAgentOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * Demonstrates {@link VotingAgentOrchestrator}'s two aggregation strategies.
 */
@Service
@RequiredArgsConstructor
public class VotingWorkflowExample {

    private final ChatClient chatClient;
    private final Executor executor;

    /**
     * Classifies a review's sentiment by running the same classifier five times and taking the
     * majority label — useful when a single call is an unreliable coin flip on a borderline case,
     * and no further model call is needed once the votes are in.
     *
     * @param review the text to classify
     * @return the majority-vote label, exactly as the classifier wrote it
     */
    public String classifyByMajorityVote(String review) {

        DefaultPromptSubAgent classifier = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("label")
                .systemPrompt("You are a sentiment classifier.")
                .promptTemplate("""
                        Classify the sentiment of the review below as exactly one word:
                        POSITIVE, NEUTRAL, or NEGATIVE. Reply with that one word only.

                        Review: {input}
                        """)
                .build();

        AgenticWorkflow<String> workflow = VotingAgentOrchestrator.<String>builder()
                .addCandidate(classifier, 5)
                .majorityVote()
                .executor(executor)
                .build();

        return workflow.invoke(review);
    }

    /**
     * Drafts a reply two different ways — one careful and apologetic, one brisk and solution-first
     * — and has a judge agent pick (or blend) the better one. An ensemble of differently-prompted
     * agents, rather than the same agent run several times, since the point here is stylistic
     * variety the model's own sampling would not reliably produce.
     *
     * @param complaint the customer complaint to reply to
     * @return the judge's chosen or blended reply
     */
    public String draftByJudge(String complaint) {

        DefaultPromptSubAgent carefulDraft = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("careful")
                .systemPrompt("You are a careful, apologetic customer support agent.")
                .promptTemplate("Draft a short reply to this complaint, leading with an apology: {input}")
                .build();

        DefaultPromptSubAgent briskDraft = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("brisk")
                .systemPrompt("You are a brisk, solution-first customer support agent.")
                .promptTemplate("Draft a short reply to this complaint, leading with the fix: {input}")
                .build();

        DefaultPromptSubAgent judge = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .systemPrompt("You are a senior support lead choosing the best reply to send.")
                .promptTemplate("""
                        A customer wrote: {input}

                        Two drafts were proposed:
                        {candidates}

                        Pick the better draft, or blend the two, and return only the final reply text.
                        """)
                .build();

        AgenticWorkflow<String> workflow = VotingAgentOrchestrator.<String>builder()
                .addCandidate(carefulDraft)
                .addCandidate(briskDraft)
                .judge(judge)
                .executor(executor)
                .build();

        return workflow.invoke(complaint);
    }
}
