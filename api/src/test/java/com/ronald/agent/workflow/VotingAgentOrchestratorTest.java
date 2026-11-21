package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies fan-out, voting, judging and failure handling in {@link VotingAgentOrchestrator}.
 * <p>Uses stub agents only — no ChatClient, API key, or network access is involved.</p>
 */
class VotingAgentOrchestratorTest {

    /**
     * Sub-agent that returns one value from a fixed script, indexed deterministically by the
     * sample's {@link VotingAgentOrchestrator#CTX_SAMPLE_INDEX} rather than by call order — call
     * order across concurrent samples is not deterministic, but the index each sample's context
     * carries is fixed before it is ever dispatched.
     */
    private static final class ScriptedAgent implements SubAgent<String> {
        private final List<String> script;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Map<String, String>> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

        private ScriptedAgent(String... script) {
            this.script = List.of(script);
        }

        @Override
        public String getOutputKey() {
            return "candidate";
        }

        @Override
        public String execute(Map<String, String> context) {
            seen.add(context);
            calls.incrementAndGet();
            int sampleIndex = Integer.parseInt(context.get(VotingAgentOrchestrator.CTX_SAMPLE_INDEX));
            return script.get((sampleIndex - 1) % script.size());
        }
    }

    /** Sub-agent that always blows up. */
    private record FailingAgent() implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return "candidate";
        }

        @Override
        public String execute(Map<String, String> context) {
            throw new IllegalStateException("upstream exploded");
        }
    }

    /** Judge that records the context it was handed and echoes the candidates blob. */
    private static final class CapturingJudge implements SubAgent<String> {
        private final AtomicReference<Map<String, String>> captured = new AtomicReference<>();

        @Override
        public String getOutputKey() {
            return "verdict";
        }

        @Override
        public String execute(Map<String, String> context) {
            captured.set(context);
            return context.get("candidates");
        }
    }

    private static ExecutorService executor;

    private static Executor executor() {
        if (executor == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }
        return executor;
    }

    @Test
    void majorityVotePicksTheMostFrequentAnswer() {
        ScriptedAgent agent = new ScriptedAgent("B", "A", "B", "B", "A");

        String result = VotingAgentOrchestrator.<String>builder()
                .addCandidate(agent, 5)
                .majorityVote()
                .executor(executor())
                .build()
                .invoke("question");

        assertEquals("B", result);
        assertEquals(5, agent.calls.get());
    }

    @Test
    void samplesSeeTheirOwnIndexAndTheTotalCount() {
        ScriptedAgent agent = new ScriptedAgent("x");

        VotingAgentOrchestrator.<String>builder()
                .addCandidate(agent, 3)
                .majorityVote()
                .executor(executor())
                .build()
                .invoke("question");

        List<String> indexes = agent.seen.stream()
                .map(c -> c.get(VotingAgentOrchestrator.CTX_SAMPLE_INDEX))
                .sorted()
                .toList();
        assertEquals(List.of("1", "2", "3"), indexes);
        agent.seen.forEach(c -> assertEquals("3", c.get(VotingAgentOrchestrator.CTX_SAMPLE_COUNT)));
    }

    @Test
    void tiesGoToWhicheverAnswerAppearedFirst() {
        ScriptedAgent agent = new ScriptedAgent("first", "second", "first", "second");

        String result = VotingAgentOrchestrator.<String>builder()
                .addCandidate(agent, 4)
                .majorityVote()
                .executor(executor())
                .build()
                .invoke("question");

        assertEquals("first", result);
    }

    @Test
    void voteNormalizerControlsGroupingButNotTheReturnedText() {
        ScriptedAgent agent = new ScriptedAgent("  same  ", "same", "different");

        String result = VotingAgentOrchestrator.<String>builder()
                .addCandidate(agent, 3)
                .majorityVote()
                .executor(executor())
                .build()
                .invoke("question");

        assertEquals("  same  ", result, "default normalizer strips for grouping, but returns the first raw text");
    }

    @Test
    void ensembleOfDifferentAgentsEachRunsOnce() {
        ScriptedAgent agentA = new ScriptedAgent("from-a");
        ScriptedAgent agentB = new ScriptedAgent("from-b");

        VotingAgentOrchestrator.<String>builder()
                .addCandidate(agentA)
                .addCandidate(agentB)
                .majorityVote()
                .executor(executor())
                .build()
                .invoke("question");

        assertEquals(1, agentA.calls.get());
        assertEquals(1, agentB.calls.get());
    }

    @Test
    void judgeReceivesCandidatesAnnotatedWithAgreementCounts() {
        ScriptedAgent agent = new ScriptedAgent("B", "A", "B");
        CapturingJudge judge = new CapturingJudge();

        String result = VotingAgentOrchestrator.<String>builder()
                .addCandidate(agent, 3)
                .judge(judge)
                .executor(executor())
                .build()
                .invoke("question");

        assertEquals("question", judge.captured.get().get("input"));
        assertTrue(result.contains("2/3 samples agreed"), result);
        assertTrue(result.contains("B"), result);
        assertTrue(result.contains("1/3 samples agreed"), result);
        assertTrue(result.contains("A"), result);
    }

    @Test
    void failFastPropagatesSampleFailureAndSkipsAggregation() {
        CompletionException ex = assertThrows(CompletionException.class, () ->
                VotingAgentOrchestrator.<String>builder()
                        .addCandidate(new ScriptedAgent("ok"))
                        .addCandidate(new FailingAgent())
                        .majorityVote()
                        .executor(executor())
                        .build()
                        .invoke("question"));

        assertTrue(ex.getMessage().contains("Sample"), ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void degradeDropsFailedSamplesFromTheVote() {
        ScriptedAgent good = new ScriptedAgent("winner");

        String result = VotingAgentOrchestrator.<String>builder()
                .addCandidate(good, 2)
                .addCandidate(new FailingAgent())
                .majorityVote()
                .failurePolicy(VotingAgentOrchestrator.BranchFailurePolicy.DEGRADE)
                .executor(executor())
                .build()
                .invoke("question");

        assertEquals("winner", result);
    }

    @Test
    void allSamplesFailingUnderDegradeThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                VotingAgentOrchestrator.<String>builder()
                        .addCandidate(new FailingAgent())
                        .addCandidate(new FailingAgent())
                        .majorityVote()
                        .failurePolicy(VotingAgentOrchestrator.BranchFailurePolicy.DEGRADE)
                        .executor(executor())
                        .build()
                        .invoke("question"));

        assertTrue(ex.getMessage().contains("2"), ex.getMessage());
    }

    @Test
    void rejectsNegativeSampleTimeoutOnInstance() {
        assertThrows(IllegalArgumentException.class, () ->
                VotingAgentOrchestrator.<String>builder().sampleTimeout(Duration.ofSeconds(-1)));
    }
}
