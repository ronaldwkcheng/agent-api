package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies builder validation for {@link VotingAgentOrchestrator} — no model calls, no network.
 */
class VotingAgentOrchestratorBuilderTest {

    private record OkAgent(String value) implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return "candidate";
        }

        @Override
        public String execute(Map<String, String> context) {
            return value;
        }
    }

    private record FixedJudge(String value) implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return "judge";
        }

        @Override
        public String execute(Map<String, String> context) {
            return value;
        }
    }

    private static Executor executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Test
    void rejectsFewerThanTwoSamples() {
        assertThrows(IllegalStateException.class, () ->
                VotingAgentOrchestrator.<String>builder()
                        .addCandidate(new OkAgent("a"))
                        .majorityVote()
                        .executor(executor())
                        .build());
    }

    @Test
    void rejectsMissingExecutor() {
        assertThrows(NullPointerException.class, () ->
                VotingAgentOrchestrator.<String>builder()
                        .addCandidate(new OkAgent("a"))
                        .addCandidate(new OkAgent("b"))
                        .majorityVote()
                        .build());
    }

    @Test
    void rejectsNeitherAggregationStrategy() {
        assertThrows(IllegalStateException.class, () ->
                VotingAgentOrchestrator.<String>builder()
                        .addCandidate(new OkAgent("a"))
                        .addCandidate(new OkAgent("b"))
                        .executor(executor())
                        .build());
    }

    @Test
    void rejectsBothAggregationStrategies() {
        assertThrows(IllegalStateException.class, () ->
                VotingAgentOrchestrator.<String>builder()
                        .addCandidate(new OkAgent("a"))
                        .addCandidate(new OkAgent("b"))
                        .majorityVote()
                        .judge(new FixedJudge("judged"))
                        .executor(executor())
                        .build());
    }

    @Test
    void rejectsNonPositiveTimes() {
        assertThrows(IllegalArgumentException.class, () ->
                VotingAgentOrchestrator.builder().addCandidate(new OkAgent("a"), 0));
    }

    @Test
    void rejectsNullCandidate() {
        assertThrows(NullPointerException.class, () ->
                VotingAgentOrchestrator.builder().addCandidate(null));
    }

    @Test
    void rejectsNegativeSampleTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                VotingAgentOrchestrator.builder().sampleTimeout(Duration.ofSeconds(-1)));
    }
}
