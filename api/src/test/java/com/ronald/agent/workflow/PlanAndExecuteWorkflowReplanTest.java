package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.PlanAndExecuteWorkflow.Plan;
import com.ronald.agent.workflow.PlanAndExecuteWorkflow.ReplanMode;
import com.ronald.agent.workflow.PlanAndExecuteWorkflow.Step;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link PlanAndExecuteWorkflow}'s two {@link ReplanMode}s: asking the planner again
 * after every step, and asking it again only when a step fails.
 *
 * <p>{@link ReplanMode#NONE} — the unchanged, fixed-plan behaviour — is already covered by
 * {@link WorkflowExhaustionTest} and {@link WorkflowAttributeSeamTest}; this file covers only
 * what re-planning adds.</p>
 */
class PlanAndExecuteWorkflowReplanTest {

    /** Step executor that records every context it ran with and can be told to fail once. */
    private static final class ScriptedExecutor implements SubAgent<String> {

        private final List<Map<String, String>> contexts = new ArrayList<>();
        private final String failOnDescription;

        private ScriptedExecutor() {
            this(null);
        }

        private ScriptedExecutor(String failOnDescription) {
            this.failOnDescription = failOnDescription;
        }

        @Override
        public String getOutputKey() {
            return "stepResult";
        }

        @Override
        public String execute(Map<String, String> context) {
            contexts.add(Map.copyOf(context));
            String description = context.get(PlanAndExecuteWorkflow.CTX_STEP_DESCRIPTION);
            if (description.equals(failOnDescription)) {
                throw new IllegalStateException("boom");
            }
            return "did: " + description;
        }

        private List<String> descriptionsRun() {
            return contexts.stream().map(c -> c.get(PlanAndExecuteWorkflow.CTX_STEP_DESCRIPTION)).toList();
        }
    }

    /** Mocks the planner/replanner's structured-output call, scripted one {@link Plan} per call. */
    private static ChatClient chatClientReturning(List<String> renderedPrompts, Plan... plans) {
        if (plans.length == 0) {
            throw new IllegalArgumentException("at least the initial plan must be scripted");
        }
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenAnswer(invocation -> {
            renderedPrompts.add(invocation.getArgument(0, Message.class).getText());
            return spec;
        });
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call().entity(Plan.class)).thenReturn(plans[0],
                java.util.Arrays.copyOfRange(plans, 1, plans.length));
        return chatClient;
    }

    /** A synthesizer that just hands back the accumulated step results, for easy assertions. */
    private static SubAgent<String> summarizer() {
        return new SubAgent<>() {
            @Override
            public String getOutputKey() {
                return "summary";
            }

            @Override
            public String execute(Map<String, String> context) {
                return context.get(PlanAndExecuteWorkflow.CTX_STEP_RESULTS);
            }
        };
    }

    // -------------------------------------------------------------------------
    // AFTER_EACH_STEP
    // -------------------------------------------------------------------------

    @Test
    void replansAfterEveryStepUntilThePlannerReportsDone() {
        List<String> prompts = new ArrayList<>();
        ChatClient chatClient = chatClientReturning(prompts,
                new Plan(List.of(new Step("step A"))),
                new Plan(List.of(new Step("step B"))),
                new Plan(List.of()));
        ScriptedExecutor executor = new ScriptedExecutor();

        String summary = PlanAndExecuteWorkflow.<String>builder()
                .chatClient(chatClient)
                .stepExecutor(executor)
                .synthesizer(summarizer())
                .replanMode(ReplanMode.AFTER_EACH_STEP)
                .maxSteps(10)
                .build()
                .invoke("do the task");

        assertEquals(List.of("step A", "step B"), executor.descriptionsRun());
        assertTrue(summary.contains("Step 1"));
        assertTrue(summary.contains("Step 2"));
        assertTrue(summary.contains("did: step A"));
        assertTrue(summary.contains("did: step B"));

        // The replan asked after step A must have seen step A's result, and the step executor's
        // own stepId for step B must continue the numbering rather than restart at 1.
        assertEquals("2", executor.contexts.get(1).get(PlanAndExecuteWorkflow.CTX_STEP_ID));
    }

    @Test
    void replansAfterEveryStepDiscardsAnyExtraQueuedStepsFromThatSameResponse() {
        List<String> prompts = new ArrayList<>();
        // The initial plan proposes two steps, but AFTER_EACH_STEP replans after the very first
        // one, discarding "step B" before it ever gets a chance to run.
        ChatClient chatClient = chatClientReturning(prompts,
                new Plan(List.of(new Step("step A"), new Step("step B"))),
                new Plan(List.of()));
        ScriptedExecutor executor = new ScriptedExecutor();

        PlanAndExecuteWorkflow.<String>builder()
                .chatClient(chatClient)
                .stepExecutor(executor)
                .synthesizer(summarizer())
                .replanMode(ReplanMode.AFTER_EACH_STEP)
                .maxSteps(10)
                .build()
                .invoke("do the task");

        assertEquals(List.of("step A"), executor.descriptionsRun(),
                "step B from the initial plan must be discarded, not executed, once step A triggers a replan");
    }

    @Test
    void exhaustionIsMeasuredAcrossEveryReplanTogether() {
        List<String> prompts = new ArrayList<>();
        ChatClient chatClient = chatClientReturning(prompts,
                new Plan(List.of(new Step("step A"))),
                new Plan(List.of(new Step("step B"))),
                new Plan(List.of(new Step("step C"))));
        ScriptedExecutor executor = new ScriptedExecutor();

        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class, () ->
                PlanAndExecuteWorkflow.<String>builder()
                        .chatClient(chatClient)
                        .stepExecutor(executor)
                        .synthesizer(summarizer())
                        .replanMode(ReplanMode.AFTER_EACH_STEP)
                        .maxSteps(2)
                        .build()
                        .invoke("do the task"));

        assertEquals(2, ex.getLimit());
        assertEquals(List.of("step A", "step B"), executor.descriptionsRun());
        assertTrue(ex.getPartialResult().contains("did: step A"));
        assertTrue(ex.getPartialResult().contains("did: step B"),
                "every result produced before giving up must survive the failure");
    }

    @Test
    void exhaustionStillAsksOneLastTimeAtTheExactBudgetBoundary() {
        List<String> prompts = new ArrayList<>();
        // Only one step is ever scripted after the initial plan: if the workflow asked a second
        // time, Mockito would just keep returning this same Plan, which is fine — the point of
        // this test is that reaching the boundary with the planner reporting "done" is a normal
        // completion, not an exhaustion.
        ChatClient chatClient = chatClientReturning(prompts,
                new Plan(List.of(new Step("step A"))),
                new Plan(List.of()));
        ScriptedExecutor executor = new ScriptedExecutor();

        String summary = PlanAndExecuteWorkflow.<String>builder()
                .chatClient(chatClient)
                .stepExecutor(executor)
                .synthesizer(summarizer())
                .replanMode(ReplanMode.AFTER_EACH_STEP)
                .maxSteps(1)
                .build()
                .invoke("do the task");

        assertEquals(List.of("step A"), executor.descriptionsRun());
        assertTrue(summary.contains("did: step A"));
    }

    // -------------------------------------------------------------------------
    // ON_FAILURE
    // -------------------------------------------------------------------------

    @Test
    void onFailureModeContinuesTheOriginalPlanWhenEveryStepSucceeds() {
        List<String> prompts = new ArrayList<>();
        ChatClient chatClient = chatClientReturning(prompts,
                new Plan(List.of(new Step("step A"), new Step("step B"))));
        ScriptedExecutor executor = new ScriptedExecutor();

        PlanAndExecuteWorkflow.<String>builder()
                .chatClient(chatClient)
                .stepExecutor(executor)
                .synthesizer(summarizer())
                .replanMode(ReplanMode.ON_FAILURE)
                .maxSteps(10)
                .build()
                .invoke("do the task");

        assertEquals(List.of("step A", "step B"), executor.descriptionsRun());
        // Only the initial plan call — a replan would have rendered a second prompt.
        assertEquals(1, prompts.size(), "no replan should happen when nothing fails");
    }

    @Test
    void onFailureModeReplacesTheRestOfThePlanAfterAFailure() {
        List<String> prompts = new ArrayList<>();
        ChatClient chatClient = chatClientReturning(prompts,
                new Plan(List.of(new Step("step A"), new Step("step B"))),
                new Plan(List.of(new Step("step A retry"))));
        ScriptedExecutor executor = new ScriptedExecutor("step A");

        String summary = PlanAndExecuteWorkflow.<String>builder()
                .chatClient(chatClient)
                .stepExecutor(executor)
                .synthesizer(summarizer())
                .replanMode(ReplanMode.ON_FAILURE)
                .maxSteps(10)
                .build()
                .invoke("do the task");

        // "step B" from the original plan never runs: the replan after the failure supersedes
        // whatever was left of the original plan, exactly as AFTER_EACH_STEP would.
        assertEquals(List.of("step A", "step A retry"), executor.descriptionsRun());
        assertTrue(summary.contains("FAILED - IllegalStateException: boom"));
        assertTrue(summary.contains("did: step A retry"));
        assertFalse(summary.contains("step B"));
    }
}
