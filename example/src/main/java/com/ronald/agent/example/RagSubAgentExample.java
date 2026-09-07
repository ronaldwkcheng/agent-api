package com.ronald.agent.example;

import com.ronald.agent.subagent.rag.RagSubAgent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Demonstrates a {@link RagSubAgent} answering questions from documents held in Chroma.
 *
 * <p>The {@link VectorStore} comes from Spring AI's own
 * {@code ChromaVectorStoreAutoConfiguration}, configured through {@code spring.ai.vectorstore.chroma.*}
 * in {@code application.properties} — the same property names the companion ingestion project
 * uses, so both address one collection.</p>
 *
 * <p>That autoconfiguration is switched off by default ({@code spring.ai.vectorstore.type=none}),
 * because its store connects to Chroma during startup and would otherwise make every build
 * depend on one running. So this demo takes two flags, the same shape the react demo uses for
 * MCP:</p>
 *
 * <pre>{@code
 * # 1. start Chroma and load documents — companion project, not this one
 * cd E:\dev\spring_ai_workspace\chroma-doc
 * docker compose up -d     # Chroma on localhost:8000
 * ./gradlew bootRun        # ingestion app on localhost:8080
 * curl -X POST "http://localhost:8080/api/ingestion?path=<your-docs>"
 *
 * # 2. then, back here
 * ./gradlew bootRun --args='--agent.demo=rag --spring.ai.vectorstore.type=chroma'
 * }</pre>
 *
 * <p>This demo <b>only reads</b>. It never writes to Chroma, so the collection must already be
 * populated — {@link #documentCount()} is checked before anything is asked, so an empty
 * collection is reported rather than costing a model call that has nothing to ground an answer
 * in.</p>
 *
 * <p>{@link ChromaVectorStoreProperties} is enabled here as well as by the autoconfiguration so
 * that the collection can be named in a message even when the store itself was never wired —
 * {@code @EnableConfigurationProperties} on an already-registered type is a no-op.</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.demo", havingValue = "rag")
@EnableConfigurationProperties({ChromaVectorStoreProperties.class, RagSubAgentExample.RagSearchProperties.class})
public class RagSubAgentExample {

    private static final Logger log = LoggerFactory.getLogger(RagSubAgentExample.class);

    /**
     * How the search is bounded, from {@code agent.rag.*}.
     *
     * <p>A local namespace rather than a {@code spring.ai.*} one, because these are the agent's
     * retrieval knobs and Spring AI has no property for either — {@code
     * spring.ai.vectorstore.chroma.*} covers only how to reach the store.</p>
     *
     * @param topK                how many passages to retrieve, and therefore how many are
     *                            rendered into the prompt in full — the main lever on token cost
     * @param similarityThreshold the floor a passage must clear, 0.0–1.0. {@code 0.0} accepts
     *                            everything the store returns; a useful floor depends on the
     *                            embedding model and the corpus, so it has to be measured rather
     *                            than guessed
     */
    @ConfigurationProperties("agent.rag")
    public record RagSearchProperties(
            @DefaultValue("4")   int    topK,
            @DefaultValue("0.0") double similarityThreshold) {}

    private final ChatClient chatClient;

    /** Always bound, so the collection can be named even with the store switched off. */
    private final ChromaVectorStoreProperties chromaProperties;

    private final RagSearchProperties searchProperties;

    /**
     * The autoconfigured Chroma beans — absent unless {@code spring.ai.vectorstore.type=chroma},
     * which is why these arrive as {@link ObjectProvider}s rather than direct dependencies.
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<ChromaApi>   chromaApiProvider;

    private ChromaApi   chromaApi;
    private RagSubAgent ragAgent;

    /**
     * Resolves the autoconfigured store once and builds the agent over it.
     *
     * <p>Building here rather than per call is what makes a bad {@code agent.rag.*} value a
     * startup failure: {@code RagSubAgent.Builder} rejects a {@code topK} below 1 or a threshold
     * outside 0.0–1.0, and finding that out now beats finding it out on the first question.</p>
     */
    @PostConstruct
    void resolveVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        this.chromaApi = chromaApiProvider.getIfAvailable();

        if (vectorStore == null) {
            log.warn("chroma_vector_store_absent — no VectorStore bean; add "
                    + "--spring.ai.vectorstore.type=chroma to wire Chroma for this run");
            return;
        }

        this.ragAgent = RagSubAgent.builder()
                .chatClient(chatClient)
                .vectorStore(vectorStore)
                .topK(searchProperties.topK())
                .similarityThreshold(searchProperties.similarityThreshold())
                .build();

        log.info("chroma_vector_store_ready collection={} tenant={} database={} topK={} threshold={}",
                chromaProperties.getCollectionName(),
                chromaProperties.getTenantName(),
                chromaProperties.getDatabaseName(),
                searchProperties.topK(),
                searchProperties.similarityThreshold());
    }

    /**
     * Whether a vector store was wired for this run.
     *
     * <p>The store's autoconfiguration is off by default, so this distinguishes "Chroma is empty"
     * from "Chroma was never connected" — two very different things to tell someone.</p>
     *
     * @return {@code true} if a {@link VectorStore} bean exists
     */
    public boolean hasVectorStore() {
        return ragAgent != null;
    }

    /**
     * Answers a question from the documents in Chroma.
     *
     * @param question the question to answer
     * @return the grounded answer, or the agent's no-documents answer if nothing was retrieved
     * @throws IllegalStateException if no vector store was wired for this run
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
     * @return the number of stored documents, or {@code 0} if the collection is missing,
     *         unreachable, or no store was wired
     */
    public long documentCount() {
        if (chromaApi == null) {
            return 0;
        }
        try {
            ChromaApi.Collection collection = chromaApi.getCollection(
                    chromaProperties.getTenantName(),
                    chromaProperties.getDatabaseName(),
                    chromaProperties.getCollectionName());
            if (collection == null) {
                log.warn("chroma_collection_missing collection={}", collectionName());
                return 0;
            }
            Long count = chromaApi.countEmbeddings(
                    chromaProperties.getTenantName(),
                    chromaProperties.getDatabaseName(),
                    collection.id());
            return count != null ? count : 0;
        } catch (RuntimeException e) {
            log.warn("chroma_count_failed collection={} — treating as empty: {}",
                    collectionName(), e.getMessage());
            return 0;
        }
    }

    /** The configured collection name, for messages that need to name it. */
    public String collectionName() {
        return chromaProperties.getCollectionName();
    }

    /** The agent built at startup, or a clear failure if no store was wired for this run. */
    private RagSubAgent ragAgent() {
        if (ragAgent == null) {
            throw new IllegalStateException("no VectorStore bean: run with "
                    + "--spring.ai.vectorstore.type=chroma");
        }
        return ragAgent;
    }

    /** The search bounds in force, for messages that need to report them. */
    public String searchBounds() {
        return "topK=%d, similarityThreshold=%s"
                .formatted(searchProperties.topK(), searchProperties.similarityThreshold());
    }
}
