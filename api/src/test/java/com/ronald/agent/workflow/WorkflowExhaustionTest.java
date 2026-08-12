package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.IterativeRefinementWorkflow.EvaluatorAgent.EvaluationResponse;
import com.ronald.agent.workflow.IterativeRefinementWorkflow.EvaluatorAgent.EvaluationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that every bounded workflow honours the shared {@link ExhaustionPolicy} contract:
 * throw by default, carrying the partial result, and return that partial only when asked.
 *
 * <p>The internal planner / thinker / evaluator agents are built from a {@link ChatClient}, so
 * that one collaborator is mocked. Everything else uses stubs — no network access.</p>
 */
class WorkflowExhaustionTest {

    /** Stub step agent that records how many times it ran. */
    private static final class CountingAgent implements SubAgent<String> {
        private final String outputKey;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingAgent(String outputKey) {
            this.outputKey = outputKey;
        }

        @Override
        public String getOutputKey() {
            return outputKey;
        }

        @Override
        public String execute(Map<String, String> context) {
            return "result-" + calls.incrementAndGet();
        }
    }

    /** ReActWorkflow requires at least one registered tool. */
    static class NoopTools {
        @Tool(description = "Does nothing at all.")
        public String noop() {
            return "nothing";
        }
    }

    /** Mocks the single structured-output call the internal agents make. */
    private static ChatClient chatClientReturning(Object entity) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().messages(any(Message.class)).call().entity(any(Class.class)))
                .thenReturn(entity);
        return chatClient;
    }

    // ── PlanAndExecuteWorkflow: plan longer than the budget ──────────────────

    private static PlanAndExecuteWorkflow.Builder<String> planWorkflowWith(int plannedSteps,
                                                                          int maxSteps,
                                                                          CountingAgent stepExecutor) {
        List<PlanAndExecuteWorkflow.Step> steps = java.util.stream.IntStream.rangeClosed(1, plannedSteps)
                .mapToObj(i -> new PlanAndExecuteWorkflow.Step("step " + i))
                .toList();

        return PlanAndExecuteWorkflow.<String>builder()
                .chatClient(chatClientReturning(new PlanAndExecuteWorkflow.Plan(steps)))
                .stepExecutor(stepExecutor)
                .synthesizer(new CountingAgent("summary"))
                .maxSteps(maxSteps);
    }

    @Test
    void planExceedingBudgetThrowsByDefaultInsteadOfTruncatingSilently() {
        CountingAgent stepExecutor = new CountingAgent("stepResult");

        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class,
                () -> planWorkflowWith(5, 3, stepExecutor).build().invoke("a task"));

        assertEquals(3, ex.getLimit());
        assertTrue(ex.getMessage().contains("5 steps"), ex.getMessage());
        assertTrue(ex.getPartialResult().contains("step 5"),
                "the full plan that could not be executed must be preserved");
        assertEquals(0, stepExecutor.calls.get(), "must fail before spending calls on a doomed plan");
    }

    @Test
    void planExceedingBudgetTruncatesUnderReturnPartial() {
        CountingAgent stepExecutor = new CountingAgent("stepResult");

        planWorkflowWith(5, 3, stepExecutor)
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build()
                .invoke("a task");

        assertEquals(3, stepExecutor.calls.get(), "only the first maxSteps steps should run");
    }

    @Test
    void planWithinBudgetIsUnaffected() {
        CountingAgent stepExecutor = new CountingAgent("stepResult");

        planWorkflowWith(3, 6, stepExecutor).build().invoke("a task");

        assertEquals(3, stepExecutor.calls.get());
    }

    // ── ReActWorkflow: no final answer within maxSteps ───────────────────────

    private static ReActWorkflow.Builder reactWorkflowNeverFinishing() {
        ReActWorkflow.ReActThought thought = new ReActWorkflow.ReActThought(
                "still thinking", false, null, "missing-tool", null);

        return ReActWorkflow.builder()
                .chatClient(chatClientReturning(thought))
                .tools(new NoopTools())
                .maxSteps(2);
    }

    @Test
    void reactThrowsWhenNoFinalAnswerIsReached() {
        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class,
                () -> reactWorkflowNeverFinishing().build().invoke("a question"));

        assertEquals(2, ex.getLimit());
        assertEquals("still thinking", ex.getPartialResult(),
                "the last thought must survive the failure");
    }

    @Test
    void reactReturnsLastThoughtUnderReturnPartial() {
        String result = reactWorkflowNeverFinishing()
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build()
                .invoke("a question");

        assertEquals("still thinking", result);
    }

    // ── IterativeRefinementWorkflow: never passes evaluation ─────────────────

    private static IterativeRefinementWorkflow.Builder refinementNeverPassing() {
        return IterativeRefinementWorkflow.builder()
                .evaluatorAgent(chatClientReturning(
                        new EvaluationResponse(EvaluationStatus.FAIL, "needs work")))
                .refinerAgent(new CountingAgent("refinedContent"))
                .criteria("must be perfect")
                .maxAttempts(3);
    }

    @Test
    void refinementThrowsWhenNothingPassesEvaluation() {
        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class,
                () -> refinementNeverPassing().build().invoke("write something"));

        assertEquals(3, ex.getLimit());
        assertEquals("result-3", ex.getPartialResult(),
                "the last draft must survive the failure rather than being discarded");
    }

    @Test
    void refinementReturnsLastDraftUnderReturnPartial() {
        String result = refinementNeverPassing()
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build()
                .invoke("write something");

        assertEquals("result-3", result);
    }

    @Test
    void refinementReturnsEarlyWhenEvaluationPasses() {
        String result = IterativeRefinementWorkflow.builder()
                .evaluatorAgent(chatClientReturning(
                        new EvaluationResponse(EvaluationStatus.PASS, "good")))
                .refinerAgent(new CountingAgent("refinedContent"))
                .criteria("must be perfect")
                .maxAttempts(3)
                .build()
                .invoke("write something");

        assertEquals("result-1", result, "should stop at the first passing draft");
    }

    // ── Shared contract ─────────────────────────────────────────────────────

    @Test
    void exhaustionRemainsCatchableAsIllegalStateException() {
        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class,
                () -> reactWorkflowNeverFinishing().build().invoke("a question"));

        assertInstanceOf(IllegalStateException.class, ex,
                "callers written against the previous ReAct contract must keep working");
    }
}
