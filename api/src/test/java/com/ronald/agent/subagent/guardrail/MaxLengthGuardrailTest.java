package com.ronald.agent.subagent.guardrail;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxLengthGuardrailTest {

    private static OutputGuardrailCheck outputOf(String text) {
        return new OutputGuardrailCheck("step", text, Map.of(), 1);
    }

    @Test
    void passesOutputAtOrUnderTheLimit() {
        MaxLengthGuardrail guardrail = new MaxLengthGuardrail(10);

        assertInstanceOf(GuardrailDecision.Pass.class, guardrail.check(outputOf("1234567890")));
        assertInstanceOf(GuardrailDecision.Pass.class, guardrail.check(outputOf("short")));
    }

    @Test
    void retriesOverLongOutputByDefault() {
        MaxLengthGuardrail guardrail = new MaxLengthGuardrail(5);

        GuardrailDecision decision = guardrail.check(outputOf("too long"));

        GuardrailDecision.Retry retry = assertInstanceOf(GuardrailDecision.Retry.class, decision);
        assertTrue(retry.feedback().contains("5"), retry.feedback());
    }

    @Test
    void blockModeRejectsInsteadOfRetrying() {
        MaxLengthGuardrail guardrail = new MaxLengthGuardrail(5, MaxLengthGuardrail.OnViolation.BLOCK);

        GuardrailDecision decision = guardrail.check(outputOf("too long"));

        assertInstanceOf(GuardrailDecision.Block.class, decision);
    }

    @Test
    void rejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new MaxLengthGuardrail(0));
        assertThrows(IllegalArgumentException.class, () -> new MaxLengthGuardrail(-1));
    }

    @Test
    void wiredIntoGuardrailedSubAgentDrivesARetryLoop() {
        var agent = new SubAgent<String>() {
            private int calls = 0;

            @Override
            public String getOutputKey() {
                return "draft";
            }

            @Override
            public String execute(Map<String, String> context) {
                calls++;
                return calls == 1 ? "a very very long first draft" : "short";
            }
        };

        String result = GuardrailedSubAgent.builder().delegate(agent)
                .outputGuardrail(new MaxLengthGuardrail(10))
                .maxRetries(1)
                .build().execute(Map.of("input", "hi"));

        assertEquals("short", result);
        assertEquals(2, agent.calls);
    }
}
