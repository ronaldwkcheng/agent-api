package com.ronald.agent.subagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Verifies builder validation for {@link CorrectiveRagWorkflow} — no model calls, no network.
 */
class CorrectiveRagWorkflowBuilderTest {

    private static final class NoopVectorStore implements VectorStore {
        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            throw new UnsupportedOperationException("must not be called during builder validation");
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

    private static RagSubAgent ragSubAgent() {
        return RagSubAgent.builder()
                .chatClient(mock(ChatClient.class))
                .vectorStore(new NoopVectorStore())
                .build();
    }

    @Test
    void rejectsMissingRagSubAgent() {
        assertThrows(NullPointerException.class, () ->
                CorrectiveRagWorkflow.builder()
                        .chatClient(mock(ChatClient.class))
                        .build());
    }

    @Test
    void rejectsMissingChatClient() {
        assertThrows(NullPointerException.class, () ->
                CorrectiveRagWorkflow.builder()
                        .ragSubAgent(ragSubAgent())
                        .build());
    }

    @Test
    void rejectsNonPositiveMaxAttempts() {
        assertThrows(IllegalArgumentException.class, () ->
                CorrectiveRagWorkflow.builder()
                        .ragSubAgent(ragSubAgent())
                        .chatClient(mock(ChatClient.class))
                        .maxAttempts(0)
                        .build());
    }

    @Test
    void rejectsNullExhaustionPolicy() {
        assertThrows(NullPointerException.class, () ->
                CorrectiveRagWorkflow.builder().exhaustionPolicy(null));
    }
}
