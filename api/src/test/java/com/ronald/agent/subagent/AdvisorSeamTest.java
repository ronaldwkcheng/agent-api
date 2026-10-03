package com.ronald.agent.subagent;

import com.ronald.agent.subagent.route.DefaultPromptRoutableAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the per-request advisor seam on {@link AbstractPromptSubAgent}.
 *
 * <p>The seam exists so that advisor parameters which vary per call &mdash; a conversation id for
 * {@code MessageChatMemoryAdvisor} above all &mdash; can be read from the context of the request
 * being executed. Agents here are typically long-lived singletons, so a customizer fixed when the
 * agent was built could only ever carry one conversation's identity; every caller would then share
 * a single memory. These tests pin the two properties that prevent that: the customizer runs on
 * <em>every</em> execution, and it sees the context of <em>that</em> execution.</p>
 *
 * <p>Note what is deliberately not asserted here: nothing calls a method on the
 * {@link ChatClient.AdvisorSpec} handed to the customizer. What the customizer does with the spec
 * is Spring AI's contract, not this library's; the seam's own contract is only that the spec and
 * the live context both arrive.</p>
 */
class AdvisorSeamTest {

    private static final String TEMPLATE = "Answer the question: {input}";
    private static final String ANSWER = "an answer";

    /**
     * A ChatClient stub whose fluent chain returns the same spec at every step, so the assertions
     * stay attached to one object. The consumer passed to {@code advisors(...)} is actually run
     * against {@code advisorSpec} &mdash; a customizer that is registered but never invoked is
     * precisely the silent failure these tests exist to catch.
     */
    @SuppressWarnings("unchecked")
    private static ChatClient chatClientCapturing(ChatClient.AdvisorSpec advisorSpec) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(advisorSpec);
            return spec;
        });
        when(spec.call().content()).thenReturn(ANSWER);
        return chatClient;
    }

    /** Records every context map the seam hands to the customizer, in call order. */
    private record Recorder(List<Map<String, String>> contexts,
                            List<ChatClient.AdvisorSpec> specs)
            implements BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> {

        Recorder() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        @Override
        public void accept(ChatClient.AdvisorSpec advisorSpec, Map<String, String> context) {
            specs.add(advisorSpec);
            contexts.add(Map.copyOf(context));
        }
    }

    // -------------------------------------------------------------------------
    // The customizer is invoked, with the live context
    // -------------------------------------------------------------------------

    @Test
    void theCustomizerSeesTheContextOfTheRequestBeingExecuted() {
        Recorder recorder = new Recorder();

        DefaultPromptSubAgent.builder()
                .chatClient(chatClientCapturing(mock(ChatClient.AdvisorSpec.class)))
                .promptTemplate(TEMPLATE)
                .advisors(recorder)
                .build()
                .execute(Map.of("input", "how do I configure retries?",
                                "conversationId", "conversation-42"));

        assertEquals(1, recorder.contexts().size(), "the customizer should run once per execution");
        Map<String, String> seen = recorder.contexts().get(0);
        assertEquals("conversation-42", seen.get("conversationId"),
                "the per-request conversation id must reach the advisor chain");
        assertEquals("how do I configure retries?", seen.get("input"),
                "the customizer sees the whole context, not just the keys the template used");
    }

    @Test
    void theCustomizerReceivesTheAdvisorSpecOfTheChain() {
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        Recorder recorder = new Recorder();

        DefaultPromptSubAgent.builder()
                .chatClient(chatClientCapturing(advisorSpec))
                .promptTemplate(TEMPLATE)
                .advisors(recorder)
                .build()
                .execute(Map.of("input", "x"));

        assertEquals(1, recorder.specs().size());
        assertNotNull(recorder.specs().get(0), "the advisor spec must not be null");
        assertSame(advisorSpec, recorder.specs().get(0),
                "the customizer must be handed the chain's own advisor spec");
    }

    @Test
    void eachExecutionCarriesItsOwnConversation() {
        Recorder recorder = new Recorder();

        SubAgent<String> agent = DefaultPromptSubAgent.builder()
                .chatClient(chatClientCapturing(mock(ChatClient.AdvisorSpec.class)))
                .promptTemplate(TEMPLATE)
                .advisors(recorder)
                .build();

        // One agent instance, two callers: the whole point of resolving the parameter per request
        // rather than at build time.
        agent.execute(Map.of("input", "first", "conversationId", "alice"));
        agent.execute(Map.of("input", "second", "conversationId", "bob"));

        assertEquals(2, recorder.contexts().size(), "the customizer should run on every execution");
        assertEquals("alice", recorder.contexts().get(0).get("conversationId"));
        assertEquals("bob", recorder.contexts().get(1).get("conversationId"),
                "a second caller must not inherit the first caller's conversation");
    }

    @Test
    void theSystemPromptDoesNotDisplaceTheCustomizer() {
        Recorder recorder = new Recorder();

        DefaultPromptSubAgent.builder()
                .chatClient(chatClientCapturing(mock(ChatClient.AdvisorSpec.class)))
                .promptTemplate(TEMPLATE)
                .systemPrompt("You are terse.")
                .advisors(recorder)
                .build()
                .execute(Map.of("input", "x", "conversationId", "c-1"));

        assertEquals(1, recorder.contexts().size(),
                "the seam must survive the optional system-prompt branch of execute()");
    }

    // -------------------------------------------------------------------------
    // Pass-through down the builder hierarchy
    // -------------------------------------------------------------------------

    @Test
    void routableAgentsThreadTheCustomizerToo() {
        Recorder recorder = new Recorder();

        DefaultPromptRoutableAgent.builder()
                .chatClient(chatClientCapturing(mock(ChatClient.AdvisorSpec.class)))
                .promptTemplate(TEMPLATE)
                .routeKey("BILLING")
                .advisors(recorder)
                .build()
                .execute(Map.of("input", "where is my invoice?", "conversationId", "c-7"));

        assertEquals(1, recorder.contexts().size(),
                "the route/ subclasses must pass the customizer through their constructor");
        assertEquals("c-7", recorder.contexts().get(0).get("conversationId"));
    }

    // -------------------------------------------------------------------------
    // Defaults and guards
    // -------------------------------------------------------------------------

    @Test
    void anAgentWithoutACustomizerStillExecutes() {
        String result = DefaultPromptSubAgent.builder()
                .chatClient(chatClientCapturing(mock(ChatClient.AdvisorSpec.class)))
                .promptTemplate(TEMPLATE)
                .build()
                .execute(Map.of("input", "x"));

        // NO_ADVISORS is a no-op, not an absent call: the request goes through the same path and
        // returns normally, which is what keeps this seam backwards compatible.
        assertEquals(ANSWER, result, "the default customizer must leave the request untouched");
    }

    @Test
    void theDefaultCustomizerIsPresentRatherThanNull() {
        BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> customizer =
                DefaultPromptSubAgent.builder().getAdvisorCustomizer();

        assertNotNull(customizer, "the builder must default to a customizer, never to null");
        assertSame(AbstractPromptSubAgent.NO_ADVISORS, customizer);
        // NO_ADVISORS runs on every default agent's every call, so it must tolerate being invoked.
        assertDoesNotThrow(() ->
                customizer.accept(mock(ChatClient.AdvisorSpec.class), Map.of("input", "x")));
    }

    @Test
    void aNullCustomizerIsRejectedAtConfigurationTime() {
        // The cast disambiguates the lambda-free null for the compiler; the point of the test is
        // that the builder refuses it outright rather than storing a null that would blow up
        // later, mid-request, inside execute().
        BiConsumer<ChatClient.AdvisorSpec, Map<String, String>> noCustomizer = null;

        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> DefaultPromptSubAgent.builder().advisors(noCustomizer));

        assertTrue(ex.getMessage().contains("advisorCustomizer"), ex.getMessage());
    }
}
