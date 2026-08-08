package com.ronald.agent.workflow;

import com.ronald.agent.subagent.route.RoutableSubAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ConditionalAgentRouter} upholds the never-null guarantee of
 * {@link AgenticWorkflow#invoke}, on every path a null could previously escape.
 */
class ConditionalAgentRouterTest {

    /** Routable agent returning a fixed value — or null, to exercise the guard. */
    private record StubRoutableAgent(String routeKey, String value) implements RoutableSubAgent<String> {
        @Override
        public String getRouteKey() {
            return routeKey;
        }

        @Override
        public String execute(Map<String, String> context) {
            return value;
        }
    }

    /** Mocks the classifier call, which returns plain content rather than a typed entity. */
    private static ChatClient classifierReturning(String category) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().messages(any(Message.class)).call().content())
                .thenReturn(category);
        return chatClient;
    }

    private static ConditionalAgentRouter.Builder<String> routerClassifiedAs(String category) {
        return ConditionalAgentRouter.<String>builder()
                .routingClient(classifierReturning(category))
                .addRoute(new StubRoutableAgent("BILLING", "billing handled"))
                .addRoute(new StubRoutableAgent("TECH", "tech handled"));
    }

    @Test
    void dispatchesToTheMatchingRoute() {
        String result = routerClassifiedAs("BILLING")
                .defaultResponse("fallback")
                .build()
                .invoke("I was charged twice");

        assertEquals("billing handled", result);
    }

    @Test
    void unmatchedCategoryFallsBackToDefaultResponse() {
        String result = routerClassifiedAs("ASTROLOGY")
                .defaultResponse("fallback")
                .build()
                .invoke("what is my star sign");

        assertEquals("fallback", result);
    }

    @Test
    void unmatchedCategoryPrefersDefaultAgentOverDefaultResponse() {
        String result = routerClassifiedAs("ASTROLOGY")
                .defaultAgent(new StubRoutableAgent("FALLBACK", "handled by fallback agent"))
                .defaultResponse("unused")
                .build()
                .invoke("what is my star sign");

        assertEquals("handled by fallback agent", result);
    }

    @Test
    void buildRejectsARouterWithNoFallback() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> routerClassifiedAs("BILLING").build());

        assertTrue(ex.getMessage().contains("fallback is required"), ex.getMessage());
    }

    @Test
    void buildAcceptsEitherKindOfFallback() {
        assertDoesNotThrow(() -> routerClassifiedAs("BILLING").defaultResponse("fallback").build());
        assertDoesNotThrow(() -> routerClassifiedAs("BILLING")
                .defaultAgent(new StubRoutableAgent("FALLBACK", "handled"))
                .build());
    }

    @Test
    void defaultResponseRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> ConditionalAgentRouter.<String>builder().defaultResponse(null));
    }

    @Test
    void nullReturningRouteIsRejectedRatherThanPropagated() {
        ConditionalAgentRouter<String> router = ConditionalAgentRouter.<String>builder()
                .routingClient(classifierReturning("BILLING"))
                .addRoute(new StubRoutableAgent("BILLING", null))
                .defaultResponse("fallback")
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> router.invoke("I was charged twice"));

        assertTrue(ex.getMessage().contains("returned null"), ex.getMessage());
        assertTrue(ex.getMessage().contains("BILLING"), ex.getMessage());
    }

    @Test
    void blankClassificationFallsBackRatherThanReturningNull() {
        String result = routerClassifiedAs("")
                .defaultResponse("fallback")
                .build()
                .invoke("something unclassifiable");

        assertEquals("fallback", result);
    }
}
