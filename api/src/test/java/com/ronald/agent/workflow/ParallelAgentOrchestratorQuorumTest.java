package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ParallelAgentOrchestrator}'s quorum option: proceeding to aggregation once
 * enough branches have settled, without waiting on the stragglers.
 *
 * <p>Uses stub agents only — no ChatClient, API key, or network access is involved.</p>
 */
class ParallelAgentOrchestratorQuorumTest {

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

    /** Sub-agent that blocks far longer than any quorum test should wait. */
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

    /** Aggregator that records the context it was handed. */
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

    /** Real async executor — a same-thread executor would run branches one at a time. */
    private static ExecutorService executor;

    private static Executor executor() {
        if (executor == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }
        return executor;
    }

    @Test
    void quorumProceedsOnceEnoughBranchesSettleWithoutWaitingForStragglers() {
        CapturingAggregator aggregator = new CapturingAggregator();
        long startedAt = System.nanoTime();

        ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                .addSubAgent(new SlowAgent("safety"))
                .aggregator(aggregator)
                .executor(executor())
                .failurePolicy(ParallelAgentOrchestrator.BranchFailurePolicy.DEGRADE)
                .quorum(1)
                .build()
                .invoke("some text");

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals("POSITIVE", aggregator.captured.get("sentiment"));
        assertTrue(aggregator.captured.get("safety").startsWith("UNAVAILABLE - quorum"),
                aggregator.captured.get("safety"));
        assertTrue(elapsedMs < 10_000,
                "must proceed once quorum is reached, not wait out the 30s straggler; took " + elapsedMs + "ms");
    }

    @Test
    void quorumAboveOneStillExcludesOnlyTheUnsettledBranches() {
        CapturingAggregator aggregator = new CapturingAggregator();
        long startedAt = System.nanoTime();

        ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                .addSubAgent(new OkAgent("topic", "BILLING"))
                .addSubAgent(new SlowAgent("safety"))
                .aggregator(aggregator)
                .executor(executor())
                .failurePolicy(ParallelAgentOrchestrator.BranchFailurePolicy.DEGRADE)
                .quorum(2)
                .build()
                .invoke("some text");

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals("POSITIVE", aggregator.captured.get("sentiment"));
        assertEquals("BILLING", aggregator.captured.get("topic"));
        assertTrue(aggregator.captured.get("safety").startsWith("UNAVAILABLE - quorum"),
                aggregator.captured.get("safety"));
        assertTrue(elapsedMs < 10_000, "took " + elapsedMs + "ms");
    }

    @Test
    void quorumEqualToTheBranchCountBehavesLikeTheDefault() {
        CapturingAggregator aggregator = new CapturingAggregator();

        ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                .addSubAgent(new OkAgent("safety", "SAFE"))
                .aggregator(aggregator)
                .executor(executor())
                .quorum(2)
                .build()
                .invoke("some text");

        assertEquals("POSITIVE", aggregator.captured.get("sentiment"));
        assertEquals("SAFE", aggregator.captured.get("safety"));
    }

    @Test
    void quorumBelowBranchCountRequiresDegrade() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                ParallelAgentOrchestrator.<String>builder()
                        .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                        .addSubAgent(new OkAgent("safety", "SAFE"))
                        .aggregator(new CapturingAggregator())
                        .executor(executor())
                        .quorum(1)
                        .build());

        assertTrue(ex.getMessage().contains("DEGRADE"), ex.getMessage());
    }

    @Test
    void quorumAboveTheBranchCountIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                ParallelAgentOrchestrator.<String>builder()
                        .addSubAgent(new OkAgent("sentiment", "POSITIVE"))
                        .aggregator(new CapturingAggregator())
                        .executor(executor())
                        .quorum(2)
                        .build());

        assertTrue(ex.getMessage().contains("cannot exceed"), ex.getMessage());
    }

    @Test
    void rejectsNonPositiveQuorum() {
        assertThrows(IllegalArgumentException.class, () ->
                ParallelAgentOrchestrator.builder().quorum(0));
    }
}
