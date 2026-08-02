package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.SequentialAgentChain;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Demonstrates a sequential multi-step agentic workflow using {@link SequentialAgentChain}.
 * <p>
 * A customer support request is processed through three ordered agents:
 * summarisation → categorisation → response drafting.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SequentialWorkflowExample {

    private final ChatClient chatClient;

    /**
     * Processes a customer support complaint through a sequential chain of agents.
     * <ol>
     *   <li>Summarises the complaint into bullet points.</li>
     *   <li>Categorises it to a department (Billing, Tech, or Account).</li>
     *   <li>Drafts a friendly reply for the user.</li>
     * </ol>
     *
     * @param userComplaint the raw complaint text submitted by the customer
     * @return a friendly response string drafted for the customer
     */
    public String processSupportRequest(String userComplaint) {

        DefaultPromptSubAgent summarizeStep = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("summary")
                .promptTemplate("Summarize the following support request into 3 bullet points: {input}")
                .build();

        DefaultPromptSubAgent categorizeStep = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("category")
                .promptTemplate("""
                        Based on this summary, identify the department (Billing, Tech, or Account): 
                        
                        {summary}
                        
                        Return only the department name, nothing else.
                        """)
                .build();

        DefaultPromptSubAgent replyStep = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                // no outputKey — anonymous final step
                .systemPrompt("You are a helpful customer support agent.")
                .promptTemplate("""
                        The user sent this complaint: {input}
                        Our internal summary is: {summary}
                        This has been routed to the {category} department.
                        
                        Draft a friendly response to the user letting them know we are working on it.
                        """)
                .build();

        AgenticWorkflow<String> workflow = SequentialAgentChain.<String>builder()
                .addAgents(summarizeStep, categorizeStep, replyStep)
                .build();

        return workflow.invoke(userComplaint);
    }
}