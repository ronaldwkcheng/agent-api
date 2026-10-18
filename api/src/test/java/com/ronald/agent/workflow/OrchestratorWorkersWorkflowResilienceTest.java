package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies per-subtask timeout and failure isolation in {@link OrchestratorWorkersWorkflow}.
 *
 * <p>A single worker instance is invoked once per subtask, concurrently, so unlike
 * {@link ParallelAgentOrchestratorResilienceTest} the worker's behaviour is driven by the subtask
 * description rather than by using a different stub per branch.</p>
 *
 * <p>Only the internal orchestrator agent talks to a {@link ChatClient}, so that one collaborator
 * is mocked. No network access anywhere in this test.</p>
 */
class OrchestratorWorkersWorkflowResilienceTest {

    private static final String OK_SUBTASK   = "ok subtask";
    private static final String FAIL_SUBTASK = "fail subtask";
    private static final String SLOW_SUBTASK = "slow subtask";

    /** Behaves according to the subtask description it is handed. */
    private static final class ConditionalWorker implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return "workerResult";
        }

        @Override
        public String execute(Map<String, String> context) {
            String subtask = context.get("subtask");
            if (FAIL_SUBTASK.equals(subtask)) {
                throw new IllegalStateException("upstream exploded");
            }
            if (SLOW_SUBTASK.equals(subtask)) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "never observed";
            }
            return "done:" + subtask;
        }
    }

    /** Captures the context it was handed and echoes the results blob. */
    private static final class CapturingSynthesizer implements SubAgent<String> {
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile Map<String, String> captured;

        @Override
        public String getOutputKey() {
            return "summary";
        }

        @Override
        public String execute(Map<String, String> context) {
            invocations.incrementAndGet();
            captured = context;
            return context.get("results");
        }
    }

    /** Mocks the single structured-output call the orchestrator agent makes. */
    private static ChatClient chatClientReturning(Object entity) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call().entity(any(Class.class))).thenReturn(entity);
        return chatClient;
    }

    private static ChatClient orchestratorReturning(String... subtaskDescriptions) {
        List<OrchestratorWorkersWorkflow.Subtask> subtasks = List.of(subtaskDescriptions).stream()
                .map(OrchestratorWorkersWorkflow.Subtask::new)
                .toList();
        return chatClientReturning(new OrchestratorWorkersWorkflow.Breakdown(subtasks));
    }

    /** Real async executor — a same-thread executor would complete subtasks before orTimeout applies. */
    private static ExecutorService executor;

    private static Executor executor() {
        if (executor == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }
        return executor;
    }

    @Test
    void synthesizesNormallyWhenAllSubtasksSucceed() {
        CapturingSynthesizer synthesizer = new CapturingSynthesizer();

        String result = OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(orchestratorReturning(OK_SUBTASK, OK_SUBTASK))
                .worker(new ConditionalWorker())
                .synthesizer(synthesizer)
                .executor(executor())
                .build()
                .invoke("some text");

        assertEquals(1, synthesizer.invocations.get());
        assertTrue(result.contains("done:" + OK_SUBTASK), result);
    }

    @Test
    void failFastPropagatesSubtaskFailureAndSkipsSynthesizer() {
        CapturingSynthesizer synthesizer = new CapturingSynthesizer();

        CompletionException ex = assertThrows(CompletionException.class, () ->
                OrchestratorWorkersWorkflow.<String>builder()
                        .chatClient(orchestratorReturning(OK_SUBTASK, FAIL_SUBTASK))
                        .worker(new ConditionalWorker())
                        .synthesizer(synthesizer)
                        .executor(executor())
                        .build()
                        .invoke("some text"));

        assertTrue(ex.getMessage().contains("Subtask 2"), ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(0, synthesizer.invocations.get(), "synthesizer must not run when a subtask fails");
    }

    @Test
    void degradeKeepsSurvivingSubtasksAndMarksTheFailedOne() {
        CapturingSynthesizer synthesizer = new CapturingSynthesizer();

        String result = OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(orchestratorReturning(OK_SUBTASK, FAIL_SUBTASK))
                .worker(new ConditionalWorker())
                .synthesizer(synthesizer)
                .executor(executor())
                .failurePolicy(OrchestratorWorkersWorkflow.BranchFailurePolicy.DEGRADE)
                .build()
                .invoke("some text");

        assertEquals(1, synthesizer.invocations.get());
        assertTrue(result.contains("done:" + OK_SUBTASK), result);
        assertTrue(result.contains("UNAVAILABLE"), result);
        assertTrue(result.contains("upstream exploded"), result);
    }

    @Test
    void timeoutBoundsASlowSubtaskUnderFailFast() {
        long startedAt = System.nanoTime();

        CompletionException ex = assertThrows(CompletionException.class, () ->
                OrchestratorWorkersWorkflow.<String>builder()
                        .chatClient(orchestratorReturning(OK_SUBTASK, SLOW_SUBTASK))
                        .worker(new ConditionalWorker())
                        .synthesizer(new CapturingSynthesizer())
                        .executor(executor())
                        .subtaskTimeout(Duration.ofMillis(250))
                        .build()
                        .invoke("some text"));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertTrue(elapsedMs < 10_000,
                "must fail on the timeout, not wait out the 30s subtask; took " + elapsedMs + "ms");
    }

    @Test
    void timeoutDegradesASlowSubtaskWithoutLosingSiblings() {
        CapturingSynthesizer synthesizer = new CapturingSynthesizer();

        String result = OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(orchestratorReturning(OK_SUBTASK, SLOW_SUBTASK))
                .worker(new ConditionalWorker())
                .synthesizer(synthesizer)
                .executor(executor())
                .subtaskTimeout(Duration.ofMillis(250))
                .failurePolicy(OrchestratorWorkersWorkflow.BranchFailurePolicy.DEGRADE)
                .build()
                .invoke("some text");

        assertTrue(result.contains("done:" + OK_SUBTASK), result);
        assertTrue(result.contains("TimeoutException"), result);
    }
}
