package com.ronald.agent.subagent.rag;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link RagSubAgent}'s two retrieval extensions: a per-call metadata filter read from
 * the context, and multi-query retrieval via an optional query-rewriter agent.
 *
 * <p>Stub collaborators only — a recording {@link VectorStore} and a mocked {@code ChatClient} —
 * so nothing here needs an API key, a running vector store, or the network.</p>
 */
class RagSubAgentQueryFeaturesTest {

    private static final String ANSWER = "the answer";

    /** Vector store that records every request and replays results keyed by the query text. */
    private static final class RecordingVectorStore implements VectorStore {

        private final Map<String, List<Document>> resultsByQuery;
        private final List<SearchRequest> requests = new ArrayList<>();

        private RecordingVectorStore(Map<String, List<Document>> resultsByQuery) {
            this.resultsByQuery = resultsByQuery;
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            requests.add(request);
            return resultsByQuery.getOrDefault(request.getQuery(), List.of());
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

    private static Document passage(String id, String text, double score) {
        return Document.builder().id(id).text(text).metadata(Map.of("source", id)).score(score).build();
    }

    /** A ChatClient that always answers generation with a fixed string. */
    private static ChatClient generationChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call().content()).thenReturn(ANSWER);
        return chatClient;
    }

    /** A ChatClient whose structured-output call returns the given query variants in sequence. */
    private static ChatClient rewriterChatClient(RagSubAgent.QueryVariants... responses) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call().entity(RagSubAgent.QueryVariants.class)).thenReturn(responses[0],
                java.util.Arrays.copyOfRange(responses, 1, responses.length));
        return chatClient;
    }

    // -------------------------------------------------------------------------
    // Metadata filter
    // -------------------------------------------------------------------------

    @Test
    void filterExpressionFromContextIsAppliedToTheSearch() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of("a question", List.of(passage("d1", "text", 0.9))));

        RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .build()
                .execute(Map.of(RagSubAgent.CTX_INPUT, "a question",
                        RagSubAgent.CTX_FILTER_EXPRESSION, "source == 'd1'"));

        assertEquals(1, store.requests.size());
        assertNotNull(store.requests.get(0).getFilterExpression());
    }

    @Test
    void blankOrAbsentFilterExpressionAppliesNoFilter() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of("a question", List.of(passage("d1", "text", 0.9))));
        RagSubAgent agent = RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .build();

        agent.execute(Map.of(RagSubAgent.CTX_INPUT, "a question"));
        agent.execute(Map.of(RagSubAgent.CTX_INPUT, "a question", RagSubAgent.CTX_FILTER_EXPRESSION, ""));

        assertEquals(2, store.requests.size());
        assertNull(store.requests.get(0).getFilterExpression());
        assertNull(store.requests.get(1).getFilterExpression());
    }

    @Test
    void retrieveTwoArgOverloadAppliesTheGivenFilter() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of("a question", List.of(passage("d1", "text", 0.9))));
        RagSubAgent agent = RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .build();

        agent.retrieve("a question", "source == 'd1'");
        agent.retrieve("a question");

        assertNotNull(store.requests.get(0).getFilterExpression());
        assertNull(store.requests.get(1).getFilterExpression());
    }

    // -------------------------------------------------------------------------
    // Multi-query retrieval
    // -------------------------------------------------------------------------

    @Test
    void withoutARewriterTheRawQuestionIsSearchedOnce() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of("a question", List.of(passage("d1", "text", 0.9))));

        RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .build()
                .retrieve("a question");

        assertEquals(1, store.requests.size());
        assertEquals("a question", store.requests.get(0).getQuery());
    }

    @Test
    void rewriterExpandsIntoMultipleQueriesAndMergesByBestScore() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of(
                "variant one", List.of(passage("d1", "text one", 0.60), passage("shared", "shared text", 0.70)),
                "variant two", List.of(passage("d2", "text two", 0.80), passage("shared", "shared text", 0.95))));

        List<Document> results = RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .queryRewriter(rewriterChatClient(
                        new RagSubAgent.QueryVariants(List.of("variant one", "variant two"))))
                .topK(10)
                .build()
                .retrieve("the original question");

        assertEquals(2, store.requests.size(), "one search per rewritten variant");
        assertEquals(List.of("variant one", "variant two"),
                store.requests.stream().map(SearchRequest::getQuery).toList());

        assertEquals(3, results.size(), "d1, d2 and the deduplicated shared document");
        assertEquals("shared", results.get(0).getId(), "highest score (0.95) sorts first");
        assertEquals(0.95, results.get(0).getScore(), 1e-9,
                "the better-scoring occurrence of the duplicate must win, not the first one seen");
    }

    @Test
    void mergedResultsAreCappedToTopK() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of(
                "variant one", List.of(passage("d1", "t1", 0.9), passage("d2", "t2", 0.8)),
                "variant two", List.of(passage("d3", "t3", 0.95), passage("d4", "t4", 0.7))));

        List<Document> results = RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .queryRewriter(rewriterChatClient(
                        new RagSubAgent.QueryVariants(List.of("variant one", "variant two"))))
                .topK(2)
                .build()
                .retrieve("the original question");

        assertEquals(2, results.size());
        assertEquals("d3", results.get(0).getId());
        assertEquals("d1", results.get(1).getId());
    }

    @Test
    void rewriterReturningASingleQueryStillSearchesOnlyThatQuery() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of("rewritten", List.of(passage("d1", "t", 0.9))));

        RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .queryRewriter(rewriterChatClient(new RagSubAgent.QueryVariants(List.of("rewritten"))))
                .build()
                .retrieve("the original question");

        assertEquals(1, store.requests.size());
        assertEquals("rewritten", store.requests.get(0).getQuery(),
                "the rewriter's query must be used, not the original question");
    }

    @Test
    void emptyRewriterResponseFallsBackToTheOriginalQuestion() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of("the original question", List.of(passage("d1", "t", 0.9))));

        RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .queryRewriter(rewriterChatClient(new RagSubAgent.QueryVariants(List.of())))
                .build()
                .retrieve("the original question");

        assertEquals(1, store.requests.size());
        assertEquals("the original question", store.requests.get(0).getQuery());
    }

    @Test
    void nullRewriterResponseFallsBackToTheOriginalQuestion() {
        RecordingVectorStore store = new RecordingVectorStore(Map.of("the original question", List.of(passage("d1", "t", 0.9))));
        ChatClient rewriter = rewriterChatClient(new RagSubAgent.QueryVariants(List.of()));
        // Override the stubbed sequence to return a null QueryVariants entirely.
        when(rewriter.prompt().call().entity(RagSubAgent.QueryVariants.class)).thenReturn(null);

        RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(store)
                .queryRewriter(rewriter)
                .build()
                .retrieve("the original question");

        assertEquals(1, store.requests.size());
        assertEquals("the original question", store.requests.get(0).getQuery());
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsNonPositiveQueryVariants() {
        assertThrows(IllegalArgumentException.class, () -> RagSubAgent.builder()
                .chatClient(generationChatClient())
                .vectorStore(new RecordingVectorStore(Map.of()))
                .queryVariants(0)
                .build());
    }
}
