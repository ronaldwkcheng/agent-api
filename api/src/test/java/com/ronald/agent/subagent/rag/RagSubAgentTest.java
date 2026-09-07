package com.ronald.agent.subagent.rag;

import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the two halves of {@link RagSubAgent}: the {@link SearchRequest} it builds from a
 * question, and the prompt it renders from whatever came back.
 *
 * <p>Both collaborators are stubbed — a recording {@link VectorStore} and a mocked
 * {@code ChatClient} — so nothing here needs an API key, a running vector store, or the
 * network.</p>
 */
class RagSubAgentTest {

    private static final String ANSWER = "Set spring.ai.retry.max-attempts [1].";

    /** Vector store that records every request and replays a fixed result. */
    private static final class RecordingVectorStore implements VectorStore {

        private final List<SearchRequest> requests = new ArrayList<>();
        private final List<Document>      result;

        private RecordingVectorStore(List<Document> result) {
            this.result = result;
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            requests.add(request);
            return result;
        }

        @Override
        public void add(List<Document> documents) {
            throw new UnsupportedOperationException("the agent must never write to the store");
        }

        @Override
        public void delete(List<String> ids) {
            throw new UnsupportedOperationException("the agent must never write to the store");
        }

        @Override
        public void delete(Filter.Expression expression) {
            throw new UnsupportedOperationException("the agent must never write to the store");
        }

        private SearchRequest onlyRequest() {
            assertEquals(1, requests.size(), "expected exactly one search");
            return requests.getFirst();
        }
    }

    /** A retrieved passage, with the metadata and score the formatter labels it by. */
    private static Document passage(String id, String text, String source, Double score) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(source != null ? Map.of("source", source) : Map.of())
                .score(score)
                .build();
    }

    /**
     * Mocks the {@code ChatClient} string call, recording each rendered prompt. Counting the
     * recorded prompts is how the tests tell a real generation from a short-circuit.
     */
    private static ChatClient chatClientAnswering(String answer, List<String> renderedPrompts) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.messages(any(Message.class))).thenAnswer(invocation -> {
            renderedPrompts.add(invocation.getArgument(0, Message.class).getText());
            return spec;
        });
        when(spec.call().content()).thenReturn(answer);
        return chatClient;
    }

    // -------------------------------------------------------------------------
    // Retrieval
    // -------------------------------------------------------------------------

    @Test
    void theQuestionBecomesTheSearchRequestWithTheConfiguredBounds() {
        RecordingVectorStore store = new RecordingVectorStore(
                List.of(passage("d1", "Retries are configured per client.", "retry.md", 0.82)));

        RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, new ArrayList<>()))
                .vectorStore(store)
                .topK(7)
                .similarityThreshold(0.55)
                .build()
                .invoke("How do I configure retries?");

        SearchRequest request = store.onlyRequest();
        assertEquals("How do I configure retries?", request.getQuery());
        assertEquals(7, request.getTopK());
        assertEquals(0.55, request.getSimilarityThreshold());
    }

    @Test
    void retrieveExposesTheDocumentsWithoutCallingTheModel() {
        List<String> prompts = new ArrayList<>();
        List<Document> found = List.of(passage("d1", "Retries are configured per client.", "retry.md", 0.82));

        List<Document> retrieved = RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, prompts))
                .vectorStore(new RecordingVectorStore(found))
                .build()
                .retrieve("How do I configure retries?");

        assertEquals(found, retrieved);
        assertTrue(prompts.isEmpty(), "retrieve() must not generate");
    }

    @Test
    void aStoreReturningNullIsTreatedAsFindingNothing() {
        List<String> prompts = new ArrayList<>();

        String answer = RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, prompts))
                .vectorStore(new RecordingVectorStore(null))
                .build()
                .invoke("anything");

        assertEquals(RagSubAgent.DEFAULT_NO_DOCUMENTS_ANSWER, answer);
        assertTrue(prompts.isEmpty(), "a null result must not reach the prompt as text");
    }

    // -------------------------------------------------------------------------
    // Generation
    // -------------------------------------------------------------------------

    @Test
    void everyRetrievedPassageIsRenderedIntoThePromptNumberedAndLabelled() {
        List<String> prompts = new ArrayList<>();

        String answer = RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, prompts))
                .vectorStore(new RecordingVectorStore(List.of(
                        passage("d1", "Retries are configured per client.", "retry.md", 0.82),
                        passage("d2", "The default is ten attempts.", "config.md", 0.71))))
                .build()
                .invoke("How do I configure retries?");

        assertEquals(ANSWER, answer);
        assertEquals(1, prompts.size());
        String prompt = prompts.getFirst();

        assertTrue(prompt.contains("[1] source=retry.md, score=0.820"), prompt);
        assertTrue(prompt.contains("Retries are configured per client."), prompt);
        assertTrue(prompt.contains("[2] source=config.md, score=0.710"), prompt);
        assertTrue(prompt.contains("The default is ten attempts."), prompt);
        assertTrue(prompt.contains("How do I configure retries?"), "the question must survive too");
    }

    @Test
    void aPassageWithoutSourceMetadataFallsBackToItsId() {
        List<String> prompts = new ArrayList<>();

        RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, prompts))
                .vectorStore(new RecordingVectorStore(
                        List.of(passage("doc-42", "Some text.", null, null)))) // no source, no score
                .build()
                .invoke("a question");

        String prompt = prompts.getFirst();
        assertTrue(prompt.contains("[1] source=doc-42"), prompt);
        assertFalse(prompt.contains("score="), "an absent score must not render as text: " + prompt);
    }

    @Test
    void emptyRetrievalShortCircuitsWithoutABillableCall() {
        List<String> prompts = new ArrayList<>();

        String answer = RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, prompts))
                .vectorStore(new RecordingVectorStore(List.of()))
                .noDocumentsAnswer("nothing indexed yet")
                .build()
                .invoke("How do I configure retries?");

        assertEquals("nothing indexed yet", answer);
        assertTrue(prompts.isEmpty(),
                "with no passages there is nothing to ground an answer in, so the model must not "
                        + "be called at all");
    }

    // -------------------------------------------------------------------------
    // Composition into a workflow
    // -------------------------------------------------------------------------

    @Test
    void asASubAgentItReadsTheQueryKeyAndKeepsUpstreamContextVisible() {
        List<String> prompts = new ArrayList<>();
        RecordingVectorStore store = new RecordingVectorStore(
                List.of(passage("d1", "Retries are configured per client.", "retry.md", 0.82)));

        RagSubAgent agent = RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, prompts))
                .vectorStore(store)
                .queryKey("topic")
                .outputKey("findings")
                .promptTemplate("Audience: {audience}\n{documents}\nTopic: {topic}")
                .build();

        String answer = agent.execute(Map.of("topic", "retry configuration", "audience", "operators"));

        assertEquals(ANSWER, answer);
        assertEquals("findings", agent.getOutputKey());
        assertEquals("retry configuration", store.onlyRequest().getQuery(),
                "the search must use the queryKey, not the literal \"input\" key");

        String prompt = prompts.getFirst();
        assertTrue(prompt.contains("Audience: operators"),
                "an upstream agent's context keys must still render: " + prompt);
        assertTrue(prompt.contains("Retries are configured per client."), prompt);
    }

    @Test
    void aContextWithoutTheQuestionIsRejected() {
        RagSubAgent agent = RagSubAgent.builder()
                .chatClient(mock(ChatClient.class))
                .vectorStore(new RecordingVectorStore(List.of()))
                .build();

        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> agent.execute(Map.of("something-else", "value")));

        assertTrue(ex.getMessage().contains("input"), ex.getMessage());
    }

    @Test
    void executeDoesNotMutateTheCallersContext() {
        Map<String, String> context = Map.of(RagSubAgent.CTX_INPUT, "a question");

        RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, new ArrayList<>()))
                .vectorStore(new RecordingVectorStore(List.of(passage("d1", "text", "a.md", 0.9))))
                .build()
                .execute(context);

        // An immutable input map proves the point: adding "documents" in place would have thrown.
        assertEquals(Map.of(RagSubAgent.CTX_INPUT, "a question"), context);
        assertFalse(context.containsKey(RagSubAgent.CTX_DOCUMENTS));
    }

    @Test
    void itIsUsableAsBothAnAgentAndAStandaloneWorkflow() {
        RagSubAgent agent = RagSubAgent.builder()
                .chatClient(mock(ChatClient.class))
                .vectorStore(new RecordingVectorStore(List.of()))
                .build();

        // Both entry points exist on the one object; workflows take the SubAgent view, callers
        // that just want an answer take the AgenticWorkflow view.
        assertInstanceOf(SubAgent.class, agent);
        assertInstanceOf(AgenticWorkflow.class, agent);
        assertEquals(RagSubAgent.DEFAULT_OUTPUT_KEY, agent.getOutputKey());
        assertTrue(agent.getPromptTemplate().contains("{documents}"),
                "the default template must expose the retrieved passages");
        assertTrue(agent.getPromptTemplate().contains("{input}"),
                "the default template must expose the question");
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    void chatClientAndVectorStoreAreBothRequired() {
        assertThrows(NullPointerException.class, () -> RagSubAgent.builder()
                .vectorStore(new RecordingVectorStore(List.of()))
                .build(), "a RAG agent without a ChatClient cannot generate");

        assertThrows(NullPointerException.class, () -> RagSubAgent.builder()
                .chatClient(mock(ChatClient.class))
                .build(), "a RAG agent without a VectorStore cannot retrieve");
    }

    @Test
    void retrievalBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> baseBuilder().topK(0).build());
        assertThrows(IllegalArgumentException.class, () -> baseBuilder().similarityThreshold(-0.1).build());
        assertThrows(IllegalArgumentException.class, () -> baseBuilder().similarityThreshold(1.1).build());
        assertThrows(IllegalArgumentException.class, () -> baseBuilder().queryKey(" ").build());
        assertThrows(NullPointerException.class, () -> baseBuilder().queryKey(null).build());
        assertThrows(NullPointerException.class, () -> baseBuilder().noDocumentsAnswer(null).build());
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        RecordingVectorStore store = new RecordingVectorStore(
                List.of(passage("d1", "text", "a.md", 0.9)));

        RagSubAgent.builder()
                .chatClient(chatClientAnswering(ANSWER, new ArrayList<>()))
                .vectorStore(store)
                .build()
                .invoke("a question");

        SearchRequest request = store.onlyRequest();
        assertEquals(RagSubAgent.DEFAULT_TOP_K, request.getTopK());
        assertEquals(RagSubAgent.DEFAULT_SIMILARITY_THRESHOLD, request.getSimilarityThreshold());
    }

    private static RagSubAgent.Builder baseBuilder() {
        return RagSubAgent.builder()
                .chatClient(mock(ChatClient.class))
                .vectorStore(new RecordingVectorStore(List.of()));
    }
}
