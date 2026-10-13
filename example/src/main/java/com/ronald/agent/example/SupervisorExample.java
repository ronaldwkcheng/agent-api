package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.tool.AgentToolCallback;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.ReActWorkflow;
import com.ronald.agent.workflow.SequentialAgentChain;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * Demonstrates {@link AgentToolCallback}: a {@link ReActWorkflow} acting as a supervisor that
 * delegates to other agents, each registered as a tool.
 *
 * <p>The supervisor has no {@code @Tool} methods at all. Its three tools are agents:</p>
 * <ul>
 *   <li>{@code draft_reply} — a whole {@link SequentialAgentChain} (categorise → reply) wrapped with
 *       {@link AgentToolCallback.Builder#workflow workflow(...)}. It takes the default {@code input}
 *       parameter, so this is the workflow-nesting case.</li>
 *   <li>{@code translate} — a single sub-agent wrapped with
 *       {@link AgentToolCallback.Builder#subAgent subAgent(...)}. Its two declared parameters are
 *       named after its template placeholders, so the model fills {@code {text}} and
 *       {@code {language}} directly.</li>
 *   <li>{@code summarize} — a single sub-agent with one declared parameter.</li>
 * </ul>
 *
 * <p>The supervisor decides which to call and in what order; nothing here fixes the sequence.</p>
 */
@Service
@RequiredArgsConstructor
public class SupervisorExample {

    private final ChatClient chatClient;

    /**
     * Handles a support task by letting a ReAct supervisor delegate to agent tools.
     *
     * @param task what the supervisor should achieve
     * @return the supervisor's final answer
     */
    public String handle(String task) {
        return ReActWorkflow.builder()
                .chatClient(chatClient)
                .toolCallbacks(draftReplyTool(), translateTool(), summarizeTool())
                .maxSteps(6)
                .build()
                .invoke(task);
    }

    /** A two-step workflow exposed as one tool: the supervisor sees a single call. */
    private ToolCallback draftReplyTool() {
        DefaultPromptSubAgent categorize = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .outputKey("category")
                .promptTemplate("""
                        Identify the department for this customer message (Billing, Tech, or Account):

                        {input}

                        Return only the department name, nothing else.
                        """)
                .build();

        DefaultPromptSubAgent reply = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .systemPrompt("You are a helpful customer support agent.")
                .promptTemplate("""
                        The customer wrote: {input}
                        It has been routed to the {category} department.

                        Draft a short, friendly reply (under 80 words) saying we are working on it.
                        """)
                .build();

        AgenticWorkflow<String> draftWorkflow = SequentialAgentChain.<String>builder()
                .addAgents(categorize, reply)
                .build();

        return AgentToolCallback.builder()
                .name("draft_reply")
                .description("Drafts a short, friendly English reply to a customer message. "
                        + "Pass the customer's original message as input. Returns the reply text.")
                .workflow(draftWorkflow)
                .build();
    }

    /** A sub-agent whose template placeholders are the tool's parameters. */
    private ToolCallback translateTool() {
        DefaultPromptSubAgent translator = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .promptTemplate("""
                        Translate the following text into {language}.
                        Return only the translation.

                        {text}
                        """)
                .build();

        return AgentToolCallback.builder()
                .name("translate")
                .description("Translates text into another language. Returns only the translation.")
                .subAgent(translator)
                .parameter("text", "The exact text to translate.")
                .parameter("language", "The target language, for example Spanish.")
                .build();
    }

    private ToolCallback summarizeTool() {
        DefaultPromptSubAgent summarizer = DefaultPromptSubAgent.builder()
                .chatClient(chatClient)
                .promptTemplate("Summarize the following in one sentence of at most 20 words:\n\n{text}")
                .build();

        return AgentToolCallback.builder()
                .name("summarize")
                .description("Summarizes text in one short sentence.")
                .subAgent(summarizer)
                .parameter("text", "The text to summarize.")
                .build();
    }
}
