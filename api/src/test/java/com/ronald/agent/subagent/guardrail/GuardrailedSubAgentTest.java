package com.ronald.agent.subagent.guardrail;

import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.ExhaustionPolicy;
import com.ronald.agent.workflow.SequentialAgentChain;
import com.ronald.agent.workflow.WorkflowExhaustedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the automated guardrail gate with a stub agent and scripted guardrails &mdash; no
 * model calls and no network.
 */
class GuardrailedSubAgentTest {

    /** Stub agent that numbers its outputs and records the context of every call. */
    private static final class DraftingAgent implements SubAgent<String> {
        private final List<Map<String, String>> calls = new ArrayList<>();

        @Override
        public String getOutputKey() {
            return "draft";
        }

        @Override
        public String execute(Map<String, String> context) {
            calls.add(Map.copyOf(context));
            return "draft-" + calls.size();
        }
    }

    /** Input guardrail that replays a fixed script of decisions and records what it was shown. */
    private static final class ScriptedInputGuardrail implements InputGuardrail {
        private final Deque<GuardrailDecision> script;
        private final List<InputGuardrailCheck> seen = new ArrayList<>();

        private ScriptedInputGuardrail(GuardrailDecision... decisions) {
            this.script = new ArrayDeque<>(List.of(decisions));
        }

        @Override
        public GuardrailDecision check(InputGuardrailCheck check) {
            seen.add(check);
            return script.removeFirst();
        }
    }

    /** Output guardrail that replays a fixed script of decisions and records what it was shown. */
    private static final class ScriptedOutputGuardrail implements OutputGuardrail {
        private final Deque<GuardrailDecision> script;
        private final List<OutputGuardrailCheck> seen = new ArrayList<>();

        private ScriptedOutputGuardrail(GuardrailDecision... decisions) {
            this.script = new ArrayDeque<>(List.of(decisions));
        }

        @Override
        public GuardrailDecision check(OutputGuardrailCheck check) {
            seen.add(check);
            return script.removeFirst();
        }
    }

    @Test
    void passingGuardrailsLetTheOutputThroughUnchanged() {
        DraftingAgent agent = new DraftingAgent();
        ScriptedInputGuardrail input = new ScriptedInputGuardrail(GuardrailDecision.pass());
        ScriptedOutputGuardrail output = new ScriptedOutputGuardrail(GuardrailDecision.pass());

        String result = GuardrailedSubAgent.builder().delegate(agent)
                .inputGuardrail(input).outputGuardrail(output)
                .build().execute(Map.of("input", "hi"));

        assertEquals("draft-1", result);
        assertEquals(1, agent.calls.size());
        assertEquals("hi", input.seen.get(0).input());
        assertEquals("draft-1", output.seen.get(0).output());
        assertEquals(1, output.seen.get(0).attempt());
    }

    @Test
    void inputModifyChangesWhatTheDelegateSees() {
        DraftingAgent agent = new DraftingAgent();

        GuardrailedSubAgent.builder().delegate(agent)
                .inputGuardrail(new ScriptedInputGuardrail(GuardrailDecision.modify("redacted")))
                .build().execute(Map.of("input", "secret"));

        assertEquals("redacted", agent.calls.get(0).get("input"));
    }

    @Test
    void inputBlockStopsBeforeTheDelegateRuns() {
        DraftingAgent agent = new DraftingAgent();

        GuardrailViolationException ex = assertThrows(GuardrailViolationException.class,
                () -> GuardrailedSubAgent.builder().delegate(agent)
                        .inputGuardrail(new ScriptedInputGuardrail(GuardrailDecision.block("policy violation")))
                        .stepName("draft").build().execute(Map.of("input", "bad")));

        assertEquals("draft", ex.getStepName());
        assertEquals(GuardrailStage.INPUT, ex.getStage());
        assertEquals("bad", ex.getBlockedContent());
        assertTrue(ex.getMessage().contains("policy violation"), ex.getMessage());
        assertEquals(0, agent.calls.size(), "a blocked input must never reach the delegate");
    }

    @Test
    void inputGuardrailReturningRetryIsAMisconfiguration() {
        DraftingAgent agent = new DraftingAgent();

        assertThrows(IllegalStateException.class,
                () -> GuardrailedSubAgent.builder().delegate(agent)
                        .inputGuardrail(new ScriptedInputGuardrail(GuardrailDecision.retry("try again")))
                        .build().execute(Map.of("input", "hi")));
    }

    @Test
    void outputModifyReplacesTheResultWithoutAnotherDelegateCall() {
        DraftingAgent agent = new DraftingAgent();

        String result = GuardrailedSubAgent.builder().delegate(agent)
                .outputGuardrail(new ScriptedOutputGuardrail(GuardrailDecision.modify("cleaned")))
                .build().execute(Map.of("input", "hi"));

        assertEquals("cleaned", result);
        assertEquals(1, agent.calls.size());
    }

    @Test
    void outputRetryRerunsDelegateWithFeedbackAndPreviousOutput() {
        DraftingAgent agent = new DraftingAgent();
        ScriptedOutputGuardrail guardrail = new ScriptedOutputGuardrail(
                GuardrailDecision.retry("too long"), GuardrailDecision.pass());

        String result = GuardrailedSubAgent.builder().delegate(agent)
                .outputGuardrail(guardrail)
                .build().execute(Map.of("input", "hi"));

        assertEquals("draft-2", result);
        assertEquals("", agent.calls.get(0).get(GuardrailedSubAgent.CTX_FEEDBACK),
                "first attempt must seed the key so templates can reference it");
        assertEquals("", agent.calls.get(0).get(GuardrailedSubAgent.CTX_PREVIOUS_OUTPUT));
        assertEquals("too long", agent.calls.get(1).get(GuardrailedSubAgent.CTX_FEEDBACK));
        assertEquals("draft-1", agent.calls.get(1).get(GuardrailedSubAgent.CTX_PREVIOUS_OUTPUT));
        assertEquals(2, guardrail.seen.get(1).attempt());
    }

    @Test
    void outputBlockThrowsCarryingTheBlockedContent() {
        DraftingAgent agent = new DraftingAgent();

        GuardrailViolationException ex = assertThrows(GuardrailViolationException.class,
                () -> GuardrailedSubAgent.builder().delegate(agent)
                        .outputGuardrail(new ScriptedOutputGuardrail(GuardrailDecision.block("unsafe")))
                        .stepName("reply").build().execute(Map.of("input", "hi")));

        assertEquals("reply", ex.getStepName());
        assertEquals(GuardrailStage.OUTPUT, ex.getStage());
        assertEquals("draft-1", ex.getBlockedContent());
    }

    @Test
    void retriesBeyondBudgetThrowByDefault() {
        DraftingAgent agent = new DraftingAgent();
        ScriptedOutputGuardrail guardrail = new ScriptedOutputGuardrail(
                GuardrailDecision.retry("again"), GuardrailDecision.retry("again"), GuardrailDecision.retry("again"));

        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class,
                () -> GuardrailedSubAgent.builder().delegate(agent)
                        .outputGuardrail(guardrail).maxRetries(2)
                        .build().execute(Map.of("input", "hi")));

        assertEquals(2, ex.getLimit());
        assertEquals("draft-3", ex.getPartialResult());
        assertEquals(3, agent.calls.size(), "one original attempt plus maxRetries retries");
    }

    @Test
    void retriesBeyondBudgetReturnLastOutputUnderReturnPartial() {
        DraftingAgent agent = new DraftingAgent();

        String result = GuardrailedSubAgent.builder().delegate(agent)
                .outputGuardrail(new ScriptedOutputGuardrail(GuardrailDecision.retry("again")))
                .maxRetries(0)
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build().execute(Map.of("input", "hi"));

        assertEquals("draft-1", result);
        assertEquals(1, agent.calls.size());
    }

    @Test
    void builderRejectsMisconfiguration() {
        DraftingAgent agent = new DraftingAgent();
        InputGuardrail guardrail = c -> GuardrailDecision.pass();

        assertThrows(NullPointerException.class,
                () -> GuardrailedSubAgent.builder().inputGuardrail(guardrail).build());
        assertThrows(IllegalStateException.class,
                () -> GuardrailedSubAgent.builder().delegate(agent).build());
        assertThrows(IllegalArgumentException.class,
                () -> GuardrailedSubAgent.builder().delegate(agent).inputGuardrail(guardrail).maxRetries(-1).build());
        assertThrows(IllegalArgumentException.class, () -> GuardrailDecision.retry(" "));
    }

    @Test
    void blockStopsASequentialChainBeforeLaterStepsRun() {
        DraftingAgent first = new DraftingAgent();
        DraftingAgent second = new DraftingAgent();

        SequentialAgentChain<String> chain = SequentialAgentChain.<String>builder()
                .addAgent(GuardrailedSubAgent.builder().delegate(first)
                        .outputGuardrail(new ScriptedOutputGuardrail(GuardrailDecision.block("no")))
                        .build())
                .addAgent(second)
                .build();

        assertThrows(GuardrailViolationException.class, () -> chain.invoke("hi"));
        assertEquals(0, second.calls.size(), "a blocked step must stop the workflow");
    }

    @Test
    void modifiedOutputIsWhatDownstreamStepsSee() {
        DraftingAgent first = new DraftingAgent();
        DraftingAgent second = new DraftingAgent();

        SequentialAgentChain.<String>builder()
                .addAgent(GuardrailedSubAgent.builder().delegate(first)
                        .outputGuardrail(new ScriptedOutputGuardrail(GuardrailDecision.modify("redacted")))
                        .build())
                .addAgent(second)
                .build()
                .invoke("hi");

        assertEquals("redacted", second.calls.get(0).get("draft"));
    }
}
