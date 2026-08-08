package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies per-branch timeout and failure isolation in {@link ParallelAgentOrchestrator}.
 * <p>Uses stub agents only — no ChatClient, API key, or network access is involved.</p>
 */
class ParallelAgentOrchestratorResilienceTest {

    /** Sub-agent returning a fixed value. */
    private record OkAgent(String outputKey, String value) implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return outputKey;
        }

        @Override
        public String execute(Map<String, String> context) {
            return value;
        }
    }

    /** Sub-agent that always blows up. */
    private record FailingAgent(String outputKey) implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return outputKey;
        }

        @Override
        public String execute(Map<String, String> context) {
            throw new IllegalStateException("upstream exploded");
        }
    }

    /** Sub-agent that blocks far longer than any timeout under test. */
    private record SlowAgent(String outputKey) implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return outputKey;
        }

        @Override
        public String execute(Map<String, String> context) {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "never observed";
        }
    }

    /** Aggregator that records the context it was handed and echoes the reports blob. */
    private static final class CapturingAggregator implements SubAgent<String> {
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
            return context.get("reports");
        }
    }

    /** Real async executor — a same-thread executor would complete branches before orTimeout applies. */
    private static ExecutorService executor;

    private static Executor executor() {
        if (executor == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }
        return executor;
    }

    @Test
    void aggregatesNormallyWhenAllBranchesSucceed() {
        CapturingAggregator aggregator = new CapturingAggregator();

        String result = ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                .addSubAgent(new OkAgent("safety", "SAFE"))
                .aggregator(aggregator)
                .executor(executor())
                .build()
                .invoke("some text");

        assertEquals("POSITIVE", aggregator.captured.get("sentiment"));
        assertEquals("SAFE", aggregator.captured.get("safety"));
        assertTrue(result.contains("SENTIMENT: POSITIVE"), result);
    }

    @Test
    void failFastPropagatesBranchFailureAndSkipsAggregator() {
        CapturingAggregator aggregator = new CapturingAggregator();

        CompletionException ex = assertThrows(CompletionException.class, () ->
                ParallelAgentOrchestrator.<String>builder()
                        .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                        .addSubAgent(new FailingAgent("safety"))
                        .aggregator(aggregator)
                        .executor(executor())
                        .build()
                        .invoke("some text"));

        assertTrue(ex.getMessage().contains("safety"), ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(0, aggregator.invocations.get(), "aggregator must not run when a branch fails");
    }

    @Test
    void degradeKeepsSurvivingBranchesAndMarksTheFailedOne() {
        CapturingAggregator aggregator = new CapturingAggregator();

        ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                .addSubAgent(new FailingAgent("safety"))
                .aggregator(aggregator)
                .executor(executor())
                .failurePolicy(ParallelAgentOrchestrator.BranchFailurePolicy.DEGRADE)
                .build()
                .invoke("some text");

        assertEquals(1, aggregator.invocations.get());
        assertEquals("POSITIVE", aggregator.captured.get("sentiment"), "surviving branch must be preserved");

        String degraded = aggregator.captured.get("safety");
        assertTrue(degraded.startsWith("UNAVAILABLE"), degraded);
        assertTrue(degraded.contains("upstream exploded"), degraded);
    }

    @Test
    void timeoutBoundsASlowBranchUnderFailFast() {
        long startedAt = System.nanoTime();

        CompletionException ex = assertThrows(CompletionException.class, () ->
                ParallelAgentOrchestrator.<String>builder()
                        .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                        .addSubAgent(new SlowAgent("safety"))
                        .aggregator(new CapturingAggregator())
                        .executor(executor())
                        .branchTimeout(Duration.ofMillis(250))
                        .build()
                        .invoke("some text"));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertTrue(elapsedMs < 10_000,
                "must fail on the timeout, not wait out the 30s agent; took " + elapsedMs + "ms");
    }

    @Test
    void timeoutDegradesASlowBranchWithoutLosingSiblings() {
        CapturingAggregator aggregator = new CapturingAggregator();

        ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                .addSubAgent(new SlowAgent("safety"))
                .aggregator(aggregator)
                .executor(executor())
                .branchTimeout(Duration.ofMillis(250))
                .failurePolicy(ParallelAgentOrchestrator.BranchFailurePolicy.DEGRADE)
                .build()
                .invoke("some text");

        assertEquals("POSITIVE", aggregator.captured.get("sentiment"));
        assertTrue(aggregator.captured.get("safety").contains("TimeoutException"),
                aggregator.captured.get("safety"));
    }

    @Test
    void rejectsNegativeBranchTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                ParallelAgentOrchestrator.<String>builder().branchTimeout(Duration.ofSeconds(-1)));
    }
}