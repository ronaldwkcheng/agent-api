package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the build-time output-key validation of {@link ParallelAgentOrchestrator.Builder}.
 * <p>Colliding keys would otherwise overwrite each other silently during fan-in, discarding
 * a completed branch's result with no error.</p>
 */
class ParallelAgentOrchestratorBuilderTest {

    /** Runs branches inline so no real executor or LLM is involved. */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    /** Minimal sub-agent that echoes a fixed value under a given output key. */
    private record StubAgent(String outputKey) implements SubAgent<String> {
        @Override
        public String getOutputKey() {
            return outputKey;
        }

        @Override
        public String execute(Map<String, String> context) {
            return "result-of-" + outputKey;
        }
    }

    private static ParallelAgentOrchestrator.Builder<String> builderWith(String... outputKeys) {
        ParallelAgentOrchestrator.Builder<String> builder = ParallelAgentOrchestrator.builder();
        for (String key : outputKeys) {
            builder.addSubAgent(new StubAgent(key));
        }
        return builder
                .aggregator(new StubAgent("summary"))
                .executor(DIRECT_EXECUTOR);
    }

    @Test
    void buildsWhenOutputKeysAreDistinct() {
        assertDoesNotThrow(() -> builderWith("sentiment", "safety", "translation").build());
    }

    @Test
    void rejectsDuplicateOutputKeys() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> builderWith("sentiment", "safety", "sentiment").build());

        assertTrue(ex.getMessage().contains("Duplicate output key 'sentiment'"), ex.getMessage());
    }

    @Test
    void rejectsOutputKeyCollidingWithInput() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> builderWith("safety", "input").build());

        assertTrue(ex.getMessage().contains("reserved output key 'input'"), ex.getMessage());
    }

    @Test
    void rejectsOutputKeyCollidingWithDefaultReportsKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> builderWith("safety", "reports").build());

        assertTrue(ex.getMessage().contains("reserved output key 'reports'"), ex.getMessage());
    }

    @Test
    void rejectsOutputKeyCollidingWithCustomReportsKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> builderWith("safety", "digest").reportsKey("digest").build());

        assertTrue(ex.getMessage().contains("reserved output key 'digest'"), ex.getMessage());
    }

    @Test
    void allowsDefaultReportsKeyOnceItIsRenamed() {
        assertDoesNotThrow(() -> builderWith("safety", "reports").reportsKey("digest").build());
    }

    @Test
    void rejectsNullOutputKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> builderWith("safety", null).build());

        assertTrue(ex.getMessage().contains("non-blank getOutputKey()"), ex.getMessage());
    }

    @Test
    void rejectsBlankOutputKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> builderWith("safety", "   ").build());

        assertTrue(ex.getMessage().contains("non-blank getOutputKey()"), ex.getMessage());
    }
}