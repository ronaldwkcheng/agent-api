package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that caller-supplied attributes reach every sub-agent's context, in each engine.
 *
 * <p>The attribute map is how a per-call value &mdash; a conversation id above all &mdash; travels
 * from {@link AgenticWorkflow#invoke(String, Map)} to the advisor customizer that reads it. That
 * path runs through each workflow's private context-building code, so it is pinned per engine
 * rather than once: an engine that built its context from scratch would drop attributes silently,
 * and the symptom would be one user reading another's chat history, far from the cause.</p>
 *
 * <p>The precedence rule is pinned too. A workflow's own keys are written after the attributes,
 * so an attribute can add to a context but never displace what the workflow computes.</p>
 */
class WorkflowAttributeSeamTest {

    /** Runs branches inline, so fan-out is deterministic and no real executor is involved. */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private static final String CONVERSATION = "conversation-42";

    /** Records the context it was executed with, so assertions can inspect what the engine built. */
    private static final class RecordingAgent implements SubAgent<String> {

        private final String outputKey;
        private final List<Map<String, String>> contexts = new ArrayList<>();

        RecordingAgent(String outputKey) {
            this.outputKey = outputKey;
        }

        @Override
        public String getOutputKey() {
            return outputKey;
        }

        @Override
        public String execute(Map<String, String> context) {
            contexts.add(Map.copyOf(context));
            return "result-of-" + outputKey;
        }

        /** The context of the first (usually only) execution. */
        Map<String, String> onlyContext() {
            assertEquals(1, contexts.size(), "expected exactly one execution");
            return contexts.get(0);
        }
    }

    // -------------------------------------------------------------------------
    // Attributes reach the agents
    // -------------------------------------------------------------------------

    @Test
    void sequentialChainSeedsEveryAgentWithTheAttributes() {
        RecordingAgent first = new RecordingAgent("first");
        RecordingAgent last = new RecordingAgent("last");

        SequentialAgentChain.<String>builder()
                .addAgent(first)
                .addAgent(last)
                .build()
                .invoke("a question", Map.of(SubAgent.CONVERSATION_ID, CONVERSATION));

        // Both steps, not just the first: the id has to survive the whole chain, because the
        // later agents are the ones with the conversation's history behind them.
        assertEquals(CONVERSATION, first.onlyContext().get(SubAgent.CONVERSATION_ID));
        assertEquals(CONVERSATION, last.onlyContext().get(SubAgent.CONVERSATION_ID));
    }

    @Test
    void parallelOrchestratorSeedsBranchesAndAggregator() {
        RecordingAgent branchOne = new RecordingAgent("sentiment");
        RecordingAgent branchTwo = new RecordingAgent("safety");
        RecordingAgent aggregator = new RecordingAgent("summary");

        ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(branchOne)
                .addSubAgent(branchTwo)
                .aggregator(aggregator)
                .executor(DIRECT_EXECUTOR)
                .build()
                .invoke("some feedback", Map.of(SubAgent.CONVERSATION_ID, CONVERSATION));

        assertEquals(CONVERSATION, branchOne.onlyContext().get(SubAgent.CONVERSATION_ID));
        assertEquals(CONVERSATION, branchTwo.onlyContext().get(SubAgent.CONVERSATION_ID));
        // The aggregator context is built separately from the fan-out context, so it is a
        // distinct opportunity to lose the attributes.
        assertEquals(CONVERSATION, aggregator.onlyContext().get(SubAgent.CONVERSATION_ID));
    }

    // -------------------------------------------------------------------------
    // The workflow's own keys win
    // -------------------------------------------------------------------------

    @Test
    void attributesCannotDisplaceTheInput() {
        RecordingAgent agent = new RecordingAgent("only");

        SequentialAgentChain.<String>builder()
                .addAgent(agent)
                .build()
                .invoke("the real input", Map.of("input", "a spoofed input"));

        assertEquals("the real input", agent.onlyContext().get("input"));
    }

    @Test
    void attributesCannotDisplaceABranchResult() {
        RecordingAgent branch = new RecordingAgent("sentiment");
        RecordingAgent aggregator = new RecordingAgent("summary");

        ParallelAgentOrchestrator.<String>builder()
                .addSubAgent(branch)
                .aggregator(aggregator)
                .executor(DIRECT_EXECUTOR)
                .build()
                .invoke("some feedback", Map.of("sentiment", "a spoofed branch result"));

        // The completed branch's own output is what the aggregator must see.
        assertEquals("result-of-sentiment", aggregator.onlyContext().get("sentiment"));
    }

    // -------------------------------------------------------------------------
    // The single-argument form still works
    // -------------------------------------------------------------------------

    @Test
    void singleArgumentInvokeDelegatesWithNoAttributes() {
        RecordingAgent agent = new RecordingAgent("only");

        String result = SequentialAgentChain.<String>builder()
                .addAgent(agent)
                .build()
                .invoke("a question");

        assertEquals("result-of-only", result);
        // No attributes were supplied, so the reserved key is simply absent rather than blank.
        assertNull(agent.onlyContext().get(SubAgent.CONVERSATION_ID));
    }

    @Test
    void attributesMayBeEmpty() {
        RecordingAgent agent = new RecordingAgent("only");

        SequentialAgentChain.<String>builder()
                .addAgent(agent)
                .build()
                .invoke("a question", Map.of());

        assertEquals("a question", agent.onlyContext().get("input"));
    }

    // -------------------------------------------------------------------------
    // Null attributes are rejected, not silently treated as empty
    // -------------------------------------------------------------------------

    @Test
    void nullAttributesAreRejected() {
        AgenticWorkflow<String> chain = SequentialAgentChain.<String>builder()
                .addAgent(new RecordingAgent("only"))
                .build();

        assertThrows(NullPointerException.class, () -> chain.invoke("a question", null));
    }

    // -------------------------------------------------------------------------
    // The caller's map is not retained or mutated
    // -------------------------------------------------------------------------

    @Test
    void theCallersAttributeMapIsNotMutated() {
        RecordingAgent agent = new RecordingAgent("only");
        Map<String, String> attributes = new HashMap<>();
        attributes.put(SubAgent.CONVERSATION_ID, CONVERSATION);

        SequentialAgentChain.<String>builder()
                .addAgent(agent)
                .build()
                .invoke("a question", attributes);

        // The engines copy before writing their own keys; a workflow that wrote through to the
        // caller's map would leak "input" and "output" back to the caller and, worse, would be
        // unsafe to call concurrently with one shared attribute map.
        assertEquals(Map.of(SubAgent.CONVERSATION_ID, CONVERSATION), attributes);
        assertTrue(agent.onlyContext().containsKey("input"));
    }
}
