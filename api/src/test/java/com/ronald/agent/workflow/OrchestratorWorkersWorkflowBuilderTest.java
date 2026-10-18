package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies build-time validation of {@link OrchestratorWorkersWorkflow.Builder} and the happy
 * path end to end: an orchestrator breakdown fans out to one worker invocation per subtask, and
 * the synthesizer receives the formatted breakdown and labelled results.
 *
 * <p>Only the internal orchestrator agent talks to a {@link ChatClient}, so that one collaborator
 * is mocked. The worker and synthesizer are stubs — no network access anywhere in this test.</p>
 */
class OrchestratorWorkersWorkflowBuilderTest {

    /** Runs subtasks inline so fan-out is deterministic and no real executor is involved. */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    /** Echoes the subtask it was given, and records every context it saw. */
    private static final class RecordingWorker implements SubAgent<String> {
        private final List<Map<String, String>> contexts = new java.util.ArrayList<>();

        @Override
        public String getOutputKey() {
            return "workerResult";
        }

        @Override
        public String execute(Map<String, String> context) {
            contexts.add(Map.copyOf(context));
            return "worked:" + context.get("subtask");
        }
    }

    /** Captures the context it was handed and echoes the results blob. */
    private static final class CapturingSynthesizer implements SubAgent<String> {
        private volatile Map<String, String> captured;

        @Override
        public String getOutputKey() {
            return "summary";
        }

        @Override
        public String execute(Map<String, String> context) {
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

    private static OrchestratorWorkersWorkflow.Builder<String> validBuilder() {
        return OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(chatClientReturning(new OrchestratorWorkersWorkflow.Breakdown(
                        List.of(new OrchestratorWorkersWorkflow.Subtask("do A")))))
                .worker(new RecordingWorker())
                .synthesizer(new CapturingSynthesizer())
                .executor(DIRECT_EXECUTOR);
    }

    // -------------------------------------------------------------------------
    // Build-time validation
    // -------------------------------------------------------------------------

    @Test
    void buildsWithAllRequiredCollaborators() {
        assertDoesNotThrow(() -> validBuilder().build());
    }

    @Test
    void requiresChatClient() {
        assertThrows(NullPointerException.class, () -> OrchestratorWorkersWorkflow.<String>builder()
                .worker(new RecordingWorker())
                .synthesizer(new CapturingSynthesizer())
                .executor(DIRECT_EXECUTOR)
                .build());
    }

    @Test
    void requiresWorker() {
        assertThrows(NullPointerException.class, () -> OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(chatClientReturning(new OrchestratorWorkersWorkflow.Breakdown(List.of())))
                .synthesizer(new CapturingSynthesizer())
                .executor(DIRECT_EXECUTOR)
                .build());
    }

    @Test
    void requiresSynthesizer() {
        assertThrows(NullPointerException.class, () -> OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(chatClientReturning(new OrchestratorWorkersWorkflow.Breakdown(List.of())))
                .worker(new RecordingWorker())
                .executor(DIRECT_EXECUTOR)
                .build());
    }

    @Test
    void requiresExecutor() {
        assertThrows(NullPointerException.class, () -> OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(chatClientReturning(new OrchestratorWorkersWorkflow.Breakdown(List.of())))
                .worker(new RecordingWorker())
                .synthesizer(new CapturingSynthesizer())
                .build());
    }

    @Test
    void executorSetterRejectsNullImmediately() {
        assertThrows(NullPointerException.class, () ->
                OrchestratorWorkersWorkflow.builder().executor(null));
    }

    @Test
    void rejectsMaxSubtasksBelowOne() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validBuilder().maxSubtasks(0).build());

        assertTrue(ex.getMessage().contains("maxSubtasks"), ex.getMessage());
    }

    @Test
    void rejectsNegativeSubtaskTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                OrchestratorWorkersWorkflow.builder().subtaskTimeout(Duration.ofSeconds(-1)));
    }

    // -------------------------------------------------------------------------
    // Happy path: breakdown → fan-out → synthesize
    // -------------------------------------------------------------------------

    @Test
    void fansOutOnceperSubtaskAndSynthesizesLabelledResults() {
        RecordingWorker worker = new RecordingWorker();
        CapturingSynthesizer synthesizer = new CapturingSynthesizer();

        ChatClient orchestratorClient = chatClientReturning(new OrchestratorWorkersWorkflow.Breakdown(List.of(
                new OrchestratorWorkersWorkflow.Subtask("write the intro"),
                new OrchestratorWorkersWorkflow.Subtask("write the conclusion"))));

        String result = OrchestratorWorkersWorkflow.<String>builder()
                .chatClient(orchestratorClient)
                .worker(worker)
                .synthesizer(synthesizer)
                .executor(DIRECT_EXECUTOR)
                .build()
                .invoke("write a short article");

        assertEquals(2, worker.contexts.size(), "worker must run once per subtask");
        assertEquals("write the intro", worker.contexts.get(0).get("subtask"));
        assertEquals("write the conclusion", worker.contexts.get(1).get("subtask"));
        assertEquals("1", worker.contexts.get(0).get("subtaskId"));
        assertEquals("2", worker.contexts.get(1).get("subtaskId"));
        // Each worker sees the whole breakdown, not just its own slice.
        assertTrue(worker.contexts.get(0).get("breakdown").contains("write the conclusion"));

        assertEquals("write a short article", synthesizer.captured.get("input"));
        assertTrue(synthesizer.captured.get("breakdown").contains("1. write the intro"));
        assertTrue(synthesizer.captured.get("breakdown").contains("2. write the conclusion"));
        assertTrue(result.contains("Subtask 1 — write the intro:\nworked:write the intro"), result);
        assertTrue(result.contains("Subtask 2 — write the conclusion:\nworked:write the conclusion"), result);
    }

    @Test
    void emptyBreakdownFailsFast() {
        ChatClient orchestratorClient = chatClientReturning(
                new OrchestratorWorkersWorkflow.Breakdown(List.of()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                OrchestratorWorkersWorkflow.<String>builder()
                        .chatClient(orchestratorClient)
                        .worker(new RecordingWorker())
                        .synthesizer(new CapturingSynthesizer())
                        .executor(DIRECT_EXECUTOR)
                        .build()
                        .invoke("a task"));

        assertTrue(ex.getMessage().contains("empty breakdown"), ex.getMessage());
    }
}
