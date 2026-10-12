package com.ronald.agent.subagent.review;

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
 * Verifies the human approval gate with a stub agent and a scripted reviewer &mdash; no model
 * calls and no network.
 */
class HumanReviewSubAgentTest {

    /** Stub agent that numbers its drafts and records the context of every call. */
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

    /** Reviewer that replays a fixed script of decisions and records what it was shown. */
    private static final class ScriptedReviewer implements HumanReviewer {
        private final Deque<ReviewDecision> script;
        private final List<ReviewRequest> seen = new ArrayList<>();

        private ScriptedReviewer(ReviewDecision... decisions) {
            this.script = new ArrayDeque<>(List.of(decisions));
        }

        @Override
        public ReviewDecision review(ReviewRequest request) {
            seen.add(request);
            return script.removeFirst();
        }
    }

    private static HumanReviewSubAgent.Builder gate(DraftingAgent agent, HumanReviewer reviewer) {
        return HumanReviewSubAgent.builder().delegate(agent).reviewer(reviewer);
    }

    @Test
    void approvedProposalPassesThroughUnchanged() {
        DraftingAgent agent = new DraftingAgent();
        ScriptedReviewer reviewer = new ScriptedReviewer(ReviewDecision.approve());

        String result = gate(agent, reviewer).build().execute(Map.of("input", "hi"));

        assertEquals("draft-1", result);
        assertEquals(1, agent.calls.size());
        ReviewRequest request = reviewer.seen.get(0);
        assertEquals("draft", request.stepName(), "step name defaults to the output key");
        assertEquals(1, request.attempt());
        assertEquals("hi", request.context().get("input"));
    }

    @Test
    void editReplacesProposalWithoutAnotherModelCall() {
        DraftingAgent agent = new DraftingAgent();

        String result = gate(agent, new ScriptedReviewer(ReviewDecision.edit("human text")))
                .build().execute(Map.of("input", "hi"));

        assertEquals("human text", result);
        assertEquals(1, agent.calls.size());
    }

    @Test
    void reviseRerunsDelegateWithFeedbackAndPreviousDraft() {
        DraftingAgent agent = new DraftingAgent();
        ScriptedReviewer reviewer = new ScriptedReviewer(
                ReviewDecision.revise("be shorter"), ReviewDecision.approve());

        String result = gate(agent, reviewer).build().execute(Map.of("input", "hi"));

        assertEquals("draft-2", result);
        assertEquals("", agent.calls.get(0).get(HumanReviewSubAgent.CTX_FEEDBACK),
                "first attempt must seed the key so templates can reference it");
        assertEquals("", agent.calls.get(0).get(HumanReviewSubAgent.CTX_PREVIOUS_DRAFT));
        assertEquals("be shorter", agent.calls.get(1).get(HumanReviewSubAgent.CTX_FEEDBACK));
        assertEquals("draft-1", agent.calls.get(1).get(HumanReviewSubAgent.CTX_PREVIOUS_DRAFT));
        assertEquals(2, reviewer.seen.get(1).attempt());
    }

    @Test
    void rejectThrowsCarryingTheRejectedProposal() {
        DraftingAgent agent = new DraftingAgent();

        ReviewRejectedException ex = assertThrows(ReviewRejectedException.class,
                () -> gate(agent, new ScriptedReviewer(ReviewDecision.reject("off-brand")))
                        .stepName("reply").build().execute(Map.of("input", "hi")));

        assertEquals("reply", ex.getStepName());
        assertEquals("draft-1", ex.getRejectedProposal());
        assertTrue(ex.getMessage().contains("off-brand"), ex.getMessage());
    }

    @Test
    void revisionsBeyondBudgetThrowByDefault() {
        DraftingAgent agent = new DraftingAgent();
        ScriptedReviewer reviewer = new ScriptedReviewer(
                ReviewDecision.revise("again"), ReviewDecision.revise("again"), ReviewDecision.revise("again"));

        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class,
                () -> gate(agent, reviewer).maxRevisions(2).build().execute(Map.of("input", "hi")));

        assertEquals(2, ex.getLimit());
        assertEquals("draft-3", ex.getPartialResult());
        assertEquals(3, agent.calls.size(), "one original attempt plus maxRevisions revisions");
    }

    @Test
    void revisionsBeyondBudgetReturnLastProposalUnderReturnPartial() {
        DraftingAgent agent = new DraftingAgent();

        String result = gate(agent, new ScriptedReviewer(ReviewDecision.revise("again")))
                .maxRevisions(0)
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build().execute(Map.of("input", "hi"));

        assertEquals("draft-1", result);
        assertEquals(1, agent.calls.size());
    }

    @Test
    void builderRejectsMisconfiguration() {
        DraftingAgent agent = new DraftingAgent();
        HumanReviewer reviewer = r -> ReviewDecision.approve();

        assertThrows(NullPointerException.class,
                () -> HumanReviewSubAgent.builder().reviewer(reviewer).build());
        assertThrows(NullPointerException.class,
                () -> HumanReviewSubAgent.builder().delegate(agent).build());
        assertThrows(IllegalArgumentException.class,
                () -> gate(agent, reviewer).maxRevisions(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ReviewDecision.revise(" "));
    }

    @Test
    void gateStopsASequentialChainBeforeLaterStepsRun() {
        DraftingAgent first = new DraftingAgent();
        DraftingAgent second = new DraftingAgent();

        SequentialAgentChain<String> chain = SequentialAgentChain.<String>builder()
                .addAgent(gate(first, new ScriptedReviewer(ReviewDecision.reject("no"))).build())
                .addAgent(second)
                .build();

        assertThrows(ReviewRejectedException.class, () -> chain.invoke("hi"));
        assertEquals(0, second.calls.size(), "a rejected step must stop the workflow");
    }

    @Test
    void editedOutputIsWhatDownstreamStepsSee() {
        DraftingAgent first = new DraftingAgent();
        DraftingAgent second = new DraftingAgent();

        SequentialAgentChain.<String>builder()
                .addAgent(gate(first, new ScriptedReviewer(ReviewDecision.edit("edited"))).build())
                .addAgent(second)
                .build()
                .invoke("hi");

        assertEquals("edited", second.calls.get(0).get("draft"));
    }
}
