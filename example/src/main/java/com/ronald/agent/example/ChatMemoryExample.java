package com.ronald.agent.example;

import com.ronald.agent.subagent.DefaultPromptSubAgent;
import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.SequentialAgentChain;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Demonstrates conversational memory across successive workflow invocations.
 *
 * <p>Three things have to line up, and they deliberately live in three different places:</p>
 *
 * <ol>
 *   <li><b>Registration</b> &mdash; {@code MessageChatMemoryAdvisor} is registered on the
 *       {@code memoryChatClient} bean, in the application class. The library knows nothing
 *       about it.</li>
 *   <li><b>Policy</b> &mdash; the {@code .advisors(...)} customizer below states <em>where</em>
 *       the conversation id is read from: the execution context, under
 *       {@link SubAgent#CONVERSATION_ID}. Fixed once, when the agent is built.</li>
 *   <li><b>Value</b> &mdash; the id itself arrives per call, through
 *       {@link AgenticWorkflow#invoke(String, Map)}.</li>
 * </ol>
 *
 * <p>That split is the whole point. The workflow below is built <em>once</em>, in the
 * constructor, and this service is a Spring singleton &mdash; so every caller in the
 * application shares this one agent instance. Because the id travels with the call rather
 * than with the agent, concurrent callers still get separate histories. Had the id been
 * fixed at build time instead, all of them would read and write one shared conversation.</p>
 *
 * <p>Nothing here is memory-specific from the library's side: the same seam carries any
 * per-request advisor parameter.</p>
 */
@Service
public class ChatMemoryExample {

    private final AgenticWorkflow<String> workflow;

    /**
     * @param memoryChatClient the client carrying the memory advisor; qualified explicitly
     *                         because the plain {@code chatClient} bean is {@code @Primary}
     *                         and would otherwise be injected here
     */
    public ChatMemoryExample(@Qualifier("memoryChatClient") ChatClient memoryChatClient) {

        DefaultPromptSubAgent assistant = DefaultPromptSubAgent.builder()
                .chatClient(memoryChatClient)
                .systemPrompt("You are a concise assistant. Answer in one or two sentences.")
                .promptTemplate("{input}")
                // Policy, fixed here. ChatMemory.CONVERSATION_ID is Spring AI's advisor
                // parameter name; SubAgent.CONVERSATION_ID is our context key. They are
                // deliberately not the same constant, and this customizer is the one place
                // the two namespaces meet.
                .advisors((advisorSpec, context) ->
                        advisorSpec.param(ChatMemory.CONVERSATION_ID,
                                          context.get(SubAgent.CONVERSATION_ID)))
                .build();

        this.workflow = SequentialAgentChain.<String>builder()
                .addAgent(assistant)
                .build();
    }

    /**
     * Answers a question within a named conversation, seeing earlier turns of that same
     * conversation and no others.
     *
     * @param conversationId identifies the conversation; two different values never share
     *                       history
     * @param question       this turn's user message
     * @return the assistant's reply
     */
    public String ask(String conversationId, String question) {
        // Value, supplied per call. One workflow instance, many conversations.
        return workflow.invoke(question, Map.of(SubAgent.CONVERSATION_ID, conversationId));
    }
}
