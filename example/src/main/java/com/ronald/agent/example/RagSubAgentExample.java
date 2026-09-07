package com.ronald.agent.example;

import com.ronald.agent.subagent.rag.RagSubAgent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Demonstrates a {@link RagSubAgent} answering questions from documents held in Chroma.
 *
 * <p>The agent does the whole pattern in one call: it embeds the question, semantic-searches the
 * {@code chroma-doc} collection, renders the passages it found into a grounded-answer prompt, and
 * returns the model's reply. {@link ChromaConfiguration} supplies the {@link VectorStore}.</p>
 *
 * <p>This demo <b>only reads</b>. It assumes {@code chroma-doc} is already populated by the
 * ingestion app at {@code E:\dev\spring_ai_workspace\chroma-doc}, so run that first — the runner
 * calls {@link #documentCount()} before asking anything and prints the ingestion steps when the
 * collection is empty, rather than paying for a model call that has nothing to ground an answer
 * in.</p>
 *
 * <p>Gated on {@code agent.demo=rag} like {@link ChromaConfiguration} itself, because it depends
 * on beans that only exist for this demo.</p>
 *
 * <pre>{@code
 * cd E:\dev\spring_ai_workspace\chroma-doc
 * docker compose up -d     # Chroma on localhost:8000
 * ./gradlew bootRun        # ingestion app on localhost:8080
 * curl -X POST "http://localhost:8080/api/ingestion?path=<your-docs>"
 *
 * # then, back here
 * ./gradlew bootRun --args='--agent.demo=rag'
 * }</pre>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.demo", havingValue = "rag")
public class RagSubAgentExample {

    private static final Logger log = LoggerFactory.getLogger(RagSubAgentExample.class);

    /**
     * How many passages to put in front of the model. Each one is rendered into the prompt in
     * full, so this trades answer coverage against tokens.
     */
    private static final int TOP_K = 4;

    private final ChatClient  chatClient;
    private final VectorStore vectorStore;
    private final ChromaApi   chromaApi;
    private final ChromaConfiguration.ChromaProperties chromaProperties;

    /**
     * Answers a question from the documents in Chroma.
     *
     * @param question the question to answer
     * @return the grounded answer, or the agent's no-documents answer if nothing was retrieved
     */
    public String answer(String question) {
        return ragAgent().invoke(question);
    }

    /**
     * Shows what the answer was built from: the passages the question actually retrieved.
     *
     * <p>Worth printing alongside any RAG answer — it is the difference between "the model said
     * so" and "the model said so because of this passage".</p>
     *
     * @param question the question to retrieve for
     * @return a one-line-per-passage summary of the retrieved set
     */
    public String explainRetrieval(String question) {
        List<Document> documents = ragAgent().retrieve(question);
        if (documents.isEmpty()) {
            return "no passages retrieved";
        }
        return documents.stream()
                .map(document -> "  - %s (score %s)".formatted(
                        document.getMetadata().getOrDefault("source", document.getId()),
                        document.getScore()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Counts the embeddings in the configured collection.
     *
     * <p>Callers use this to decide whether asking anything is worthwhile: this demo does not
     * ingest, so an empty collection means every question retrieves nothing.</p>
     *
     * @return the number of stored documents, or {@code 0} if the collection is missing or
     *         unreachable
     */
    public long documentCount() {
        try {
            ChromaApi.Collection collection = chromaApi.getCollection(
                    chromaProperties.tenant(), chromaProperties.database(), chromaProperties.collection());
            if (collection == null) {
                log.warn("chroma_collection_missing collection={}", chromaProperties.collection());
                return 0;
            }
            Long count = chromaApi.countEmbeddings(
                    chromaProperties.tenant(), chromaProperties.database(), collection.id());
            return count != null ? count : 0;
        } catch (RuntimeException e) {
            log.warn("chroma_count_failed collection={} — treating as empty: {}",
                    chromaProperties.collection(), e.getMessage());
            return 0;
        }
    }

    /** The configured collection name, for messages that need to name it. */
    public String collectionName() {
        return chromaProperties.collection();
    }

    /**
     * Builds the agent.
     *
     * <p>Cheap to construct — it holds configuration, not connections — so it is built per call
     * rather than cached, which keeps the wiring visible at the point of use.</p>
     */
    private RagSubAgent ragAgent() {
        return RagSubAgent.builder()
                .chatClient(chatClient)
                .vectorStore(vectorStore)
                .topK(TOP_K)
                .build();
    }
}
