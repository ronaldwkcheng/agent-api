package com.ronald.agent.subagent.guardrail;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiGuardrailTest {

    private static InputGuardrailCheck inputOf(String text) {
        return new InputGuardrailCheck("step", text, Map.of());
    }

    private static OutputGuardrailCheck outputOf(String text) {
        return new OutputGuardrailCheck("step", text, Map.of(), 1);
    }

    @Test
    void passesCleanText() {
        GuardrailDecision decision = new PiiGuardrail().check(inputOf("How do I reset my password?"));

        assertInstanceOf(GuardrailDecision.Pass.class, decision);
    }

    @Test
    void redactsAnEmailByDefault() {
        GuardrailDecision decision = new PiiGuardrail().check(inputOf("Reach me at jane.doe@example.com please"));

        GuardrailDecision.Modify modify = assertInstanceOf(GuardrailDecision.Modify.class, decision);
        assertEquals("Reach me at [REDACTED:EMAIL] please", modify.replacement());
    }

    @Test
    void redactsASocialSecurityNumber() {
        GuardrailDecision decision = new PiiGuardrail().check(outputOf("SSN on file: 123-45-6789."));

        GuardrailDecision.Modify modify = assertInstanceOf(GuardrailDecision.Modify.class, decision);
        assertEquals("SSN on file: [REDACTED:SSN].", modify.replacement());
    }

    @Test
    void redactsAPhoneNumber() {
        GuardrailDecision decision = new PiiGuardrail().check(inputOf("Call me at 415-555-1234 tomorrow"));

        GuardrailDecision.Modify modify = assertInstanceOf(GuardrailDecision.Modify.class, decision);
        assertEquals("Call me at [REDACTED:PHONE] tomorrow", modify.replacement());
    }

    @Test
    void redactsACreditCardLookingNumber() {
        GuardrailDecision decision = new PiiGuardrail().check(inputOf("Card: 4111 1111 1111 1111 exp 12/29"));

        GuardrailDecision.Modify modify = assertInstanceOf(GuardrailDecision.Modify.class, decision);
        assertEquals("Card: [REDACTED:CREDIT_CARD] exp 12/29", modify.replacement());
    }

    @Test
    void blockModeRejectsInsteadOfRedacting() {
        GuardrailDecision decision = new PiiGuardrail(PiiGuardrail.Mode.BLOCK)
                .check(inputOf("Email me at jane.doe@example.com"));

        GuardrailDecision.Block block = assertInstanceOf(GuardrailDecision.Block.class, decision);
        assertTrue(block.reason().contains("EMAIL"), block.reason());
    }

    @Test
    void sameInstanceChecksBothInputAndOutput() {
        PiiGuardrail guardrail = new PiiGuardrail();

        assertInstanceOf(GuardrailDecision.Modify.class, guardrail.check(inputOf("a@b.com")));
        assertInstanceOf(GuardrailDecision.Modify.class, guardrail.check(outputOf("a@b.com")));
    }
}
