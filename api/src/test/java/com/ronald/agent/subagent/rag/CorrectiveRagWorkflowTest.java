package com.ronald.agent.subagent.rag;

import com.ronald.agent.subagent.rag.CorrectiveRagWorkflow.GradeResult;
import com.ronald.agent.subagent.rag.CorrectiveRagWorkflow.RetrievalGrade;
import com.ronald.agent.workflow.ExhaustionPolicy;
import com.ronald.agent.workflow.WorkflowExhaustedException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link CorrectiveRagWorkflow}'s grade-and-retry loop: when retrieval is graded
 * sufficient, when it is not and the query gets rewritten, and what happens on exhaustion.
 *
 * <p>Two {@link ChatClient}s are mocked — one backing the wrapped {@link RagSubAgent}'s
 * generation, one backing the workflow's internal grader and rewriter — plus a scripted
 * {@link VectorStore}. Nothing here needs an API key or the network.</p>
 */
class CorrectiveRagWorkflowTest {

    private static final String ANSWER = "Set spring.ai.retry.max-attempts [1].";

    /** Vector store that replays one list of documents per call, in order, and records queries. */
    private static final class ScriptedVectorStore implements VectorStore {

        private final List<List<Document>> script;
        private final List<String> queries = new ArrayList<>();

        private ScriptedVectorStore(List<List<Document>> script) {
            this.script = script;
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            queries.add(request.getQuery());
            int index = Math.min(queries.size() - 1, script.size() - 1);
            return script.get(index);
        }

        @Override
        public void add(List<Document> documents) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(List<String> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Filter.Expression expression) {
            throw new UnsupportedOperationException();
        }
    }

    private static Document passage(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of("source", id + ".md")).build();
    }

    /** Mocks the ChatClient call chain, recording every rendered prompt's text. */
    private static ChatClient stubChatClient(List<String> renderedPrompts) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenAnswer(invocation -> {
            renderedPrompts.add(invocation.getArgument(0, Message.class).getText());
            return spec;
        });
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        return chatClient;
    }

    /** A ChatClient that always answers generation with a fixed string. */
    private static ChatClient ragGenerationClient(List<String> renderedPrompts) {
        ChatClient chatClient = stubChatClient(renderedPrompts);
        when(chatClient.prompt().call().content()).thenReturn(ANSWER);
        return chatClient;
    }

    private static RagSubAgent ragSubAgent(VectorStore vectorStore, List<String> generationPrompts) {
        return RagSubAgent.builder()
                .chatClient(ragGenerationClient(generationPrompts))
                .vectorStore(vectorStore)
                .build();
    }

    // -------------------------------------------------------------------------
    // Sufficient on the first attempt
    // -------------------------------------------------------------------------

    @Test
    void sufficientOnFirstAttemptNeverRewritesOrRetrievesAgain() {
        ScriptedVectorStore store = new ScriptedVectorStore(List.of(
                List.of(passage("d1", "Retries are configured per client."))));
        List<String> generationPrompts = new ArrayList<>();
        List<String> correctivePrompts = new ArrayList<>();

        ChatClient correctiveClient = stubChatClient(correctivePrompts);
        when(correctiveClient.prompt().call().entity(GradeResult.class))
                .thenReturn(new GradeResult(RetrievalGrade.SUFFICIENT, ""));

        String answer = CorrectiveRagWorkflow.builder()
                .ragSubAgent(ragSubAgent(store, generationPrompts))
                .chatClient(correctiveClient)
                .build()
                .invoke("How do I configure retries?");

        assertEquals(ANSWER, answer);
        assertEquals(1, store.queries.size(), "a sufficient first grade must not trigger a second search");
        assertEquals(1, correctivePrompts.size(), "only the grader should have run, not the rewriter");
        assertEquals(1, generationPrompts.size());
        assertTrue(generationPrompts.getFirst().contains("Retries are configured per client."));
    }

    // -------------------------------------------------------------------------
    // Insufficient, then sufficient after a rewrite
    // -------------------------------------------------------------------------

    @Test
    void insufficientGradeRewritesTheQueryAndRetrievesAgain() {
        ScriptedVectorStore store = new ScriptedVectorStore(List.of(
                List.of(passage("d1", "An unrelated passage about billing.")),
                List.of(passage("d2", "Retries default to ten attempts."))));
        List<String> generationPrompts = new ArrayList<>();
        List<String> correctivePrompts = new ArrayList<>();

        ChatClient correctiveClient = stubChatClient(correctivePrompts);
        when(correctiveClient.prompt().call().entity(GradeResult.class)).thenReturn(
                new GradeResult(RetrievalGrade.INSUFFICIENT, "off-topic passage"),
                new GradeResult(RetrievalGrade.SUFFICIENT, ""));
        when(correctiveClient.prompt().call().content()).thenReturn("retry defaults and attempts");

        String answer = CorrectiveRagWorkflow.builder()
                .ragSubAgent(ragSubAgent(store, generationPrompts))
                .chatClient(correctiveClient)
                .build()
                .invoke("How do I configure retries?");

        assertEquals(ANSWER, answer);
        assertEquals(List.of("How do I configure retries?", "retry defaults and attempts"), store.queries);
        assertEquals(3, correctivePrompts.size(), "grader, then rewriter, then grader again");
        assertTrue(correctivePrompts.get(0).contains("An unrelated passage about billing."));
        assertTrue(correctivePrompts.get(1).contains("Previous search query: How do I configure retries?"));
        assertTrue(correctivePrompts.get(1).contains("off-topic passage"));
        assertTrue(correctivePrompts.get(2).contains("Retries default to ten attempts."));
        assertTrue(generationPrompts.getFirst().contains("Retries default to ten attempts."),
                "generation must use the last attempt's passages, not the first attempt's");
    }

    // -------------------------------------------------------------------------
    // Empty retrieval skips the grader
    // -------------------------------------------------------------------------

    @Test
    void emptyRetrievalIsGradedInsufficientWithoutCallingTheGrader() {
        ScriptedVectorStore store = new ScriptedVectorStore(List.of(
                List.of(),
                List.of(passage("d1", "Retries default to ten attempts."))));
        List<String> generationPrompts = new ArrayList<>();
        List<String> correctivePrompts = new ArrayList<>();

        ChatClient correctiveClient = stubChatClient(correctivePrompts);
        when(correctiveClient.prompt().call().entity(GradeResult.class))
                .thenReturn(new GradeResult(RetrievalGrade.SUFFICIENT, ""));
        when(correctiveClient.prompt().call().content()).thenReturn("a rewritten query");

        String answer = CorrectiveRagWorkflow.builder()
                .ragSubAgent(ragSubAgent(store, generationPrompts))
                .chatClient(correctiveClient)
                .build()
                .invoke("How do I configure retries?");

        assertEquals(ANSWER, answer);
        assertEquals(2, store.queries.size());
        // Only the rewriter (attempt 1, synthetic grade) and the grader (attempt 2) should have
        // rendered a prompt — no grader call for the empty first attempt.
        assertEquals(2, correctivePrompts.size());
        assertTrue(correctivePrompts.get(0).contains("Previous search query: How do I configure retries?"));
        assertTrue(correctivePrompts.get(1).contains("Retries default to ten attempts."));
    }

    // -------------------------------------------------------------------------
    // Exhaustion
    // -------------------------------------------------------------------------

    @Test
    void exhaustedAttemptsThrowByDefaultCarryingTheBestEffortAnswer() {
        ScriptedVectorStore store = new ScriptedVectorStore(List.of(
                List.of(passage("d1", "Still not quite the right passage."))));
        List<String> generationPrompts = new ArrayList<>();
        List<String> correctivePrompts = new ArrayList<>();

        ChatClient correctiveClient = stubChatClient(correctivePrompts);
        when(correctiveClient.prompt().call().entity(GradeResult.class))
                .thenReturn(new GradeResult(RetrievalGrade.INSUFFICIENT, "still off-topic"));
        when(correctiveClient.prompt().call().content()).thenReturn("another rewritten query");

        WorkflowExhaustedException ex = assertThrows(WorkflowExhaustedException.class, () ->
                CorrectiveRagWorkflow.builder()
                        .ragSubAgent(ragSubAgent(store, generationPrompts))
                        .chatClient(correctiveClient)
                        .build()
                        .invoke("How do I configure retries?"));

        assertEquals(CorrectiveRagWorkflow.DEFAULT_MAX_ATTEMPTS, ex.getLimit());
        assertEquals(ANSWER, ex.getPartialResult(),
                "the best-effort answer must survive the failure rather than being discarded");
        assertEquals(CorrectiveRagWorkflow.DEFAULT_MAX_ATTEMPTS, store.queries.size(),
                "exactly one search per attempt, no extra search for the final generation");
    }

    @Test
    void exhaustedAttemptsReturnTheBestEffortAnswerUnderReturnPartial() {
        ScriptedVectorStore store = new ScriptedVectorStore(List.of(
                List.of(passage("d1", "Still not quite the right passage."))));
        List<String> generationPrompts = new ArrayList<>();
        List<String> correctivePrompts = new ArrayList<>();

        ChatClient correctiveClient = stubChatClient(correctivePrompts);
        when(correctiveClient.prompt().call().entity(GradeResult.class))
                .thenReturn(new GradeResult(RetrievalGrade.INSUFFICIENT, "still off-topic"));
        when(correctiveClient.prompt().call().content()).thenReturn("another rewritten query");

        String answer = CorrectiveRagWorkflow.builder()
                .ragSubAgent(ragSubAgent(store, generationPrompts))
                .chatClient(correctiveClient)
                .maxAttempts(2)
                .exhaustionPolicy(ExhaustionPolicy.RETURN_PARTIAL)
                .build()
                .invoke("How do I configure retries?");

        assertEquals(ANSWER, answer);
        assertEquals(2, store.queries.size());
    }
}
