package com.ronald.agent.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Chroma {@link VectorStore} the RAG demo retrieves from.
 *
 * <p>Everything here is gated behind {@code agent.demo=rag} for the same reason no demo runner is
 * registered by default: a {@link ChromaVectorStore} bean is an {@code InitializingBean} that
 * connects to Chroma and creates its collection while the context starts. Registered
 * unconditionally it would make a plain {@code bootRun} — and every {@code @SpringBootTest} —
 * depend on a Chroma running on localhost. This is also why the plain {@code
 * spring-ai-chroma-store} library is on the classpath rather than
 * {@code spring-ai-starter-vector-store-chroma}, whose autoconfiguration would build that bean
 * eagerly and out of reach of this condition.</p>
 *
 * <p>Chroma itself, and everything in it, belongs to the companion project at
 * {@code E:\dev\spring_ai_workspace\chroma-doc} — that is where the {@code docker-compose.yml}
 * and the ingestion pipeline live. This module only reads:</p>
 * <pre>{@code
 * cd E:\dev\spring_ai_workspace\chroma-doc
 * docker compose up -d     # Chroma on localhost:8000
 * ./gradlew bootRun        # ingestion app on localhost:8080
 * curl -X POST "http://localhost:8080/api/ingestion?path=<your-docs>"
 *
 * # then, back here
 * ./gradlew bootRun --args='--agent.demo=rag'
 * }</pre>
 *
 * <p>So the demo assumes {@code chroma-doc} already holds documents; it queries but never writes.
 * An empty or missing collection is reported rather than silently answered — see
 * {@link RagSubAgentExample#documentCount()}.</p>
 *
 * <p>The two projects must embed with the same model, and the property paths differ between their
 * Spring AI versions — {@code application.properties} spells that out.</p>
 */
@Configuration
@ConditionalOnProperty(name = "agent.demo", havingValue = "rag")
@EnableConfigurationProperties(ChromaConfiguration.ChromaProperties.class)
public class ChromaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChromaConfiguration.class);

    /**
     * Chroma connection settings, bound from {@code agent.rag.chroma.*}.
     *
     * <p>Deliberately not the {@code spring.ai.vectorstore.chroma.*} names: those belong to the
     * starter's autoconfiguration, which this module does not use, and reusing them would suggest
     * the starter is in play.</p>
     *
     * @param url        the Chroma base URL
     * @param tenant     the Chroma tenant, created if missing
     * @param database   the Chroma database within the tenant, created if missing
     * @param collection the collection queried for passages
     */
    @ConfigurationProperties("agent.rag.chroma")
    public record ChromaProperties(String url, String tenant, String database, String collection) {}

    /**
     * The Chroma REST client. Creating this bean opens no connection; the first call does.
     *
     * @param properties the bound connection settings
     * @return the configured API client
     */
    @Bean
    public ChromaApi chromaApi(ChromaProperties properties) {
        log.info("chroma_api url={} tenant={} database={} collection={}",
                properties.url(), properties.tenant(), properties.database(), properties.collection());
        return ChromaApi.builder()
                .baseUrl(properties.url())
                .build();
    }

    /**
     * The vector store {@code RagSubAgent} retrieves through.
     *
     * <p>{@code initializeSchema(true)} makes a missing collection an empty one rather than a
     * startup failure, so a first run against a fresh Chroma reports "no documents" instead of
     * dying. The tenant and database are provisioned first, because creating a collection inside
     * a tenant Chroma has never heard of is a 404.</p>
     *
     * @param chromaApi      the Chroma client
     * @param embeddingModel the model embedding both the stored passages and each query — it must
     *                       be the same one the collection was written with, or the vectors will
     *                       not be comparable and retrieval returns noise
     * @param properties     the bound connection settings
     * @return the Chroma-backed vector store
     */
    @Bean
    public VectorStore chromaVectorStore(ChromaApi chromaApi,
                                         EmbeddingModel embeddingModel,
                                         ChromaProperties properties) {
        ensureTenantAndDatabase(chromaApi, properties);

        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .tenantName(properties.tenant())
                .databaseName(properties.database())
                .collectionName(properties.collection())
                .initializeSchema(true)
                .build();
    }

    /**
     * Creates the tenant and database if Chroma does not already have them.
     *
     * <p>Chroma answers a lookup for a missing tenant or database with an error rather than an
     * empty result, so "does it exist" is a try/catch and not a boolean.</p>
     */
    private void ensureTenantAndDatabase(ChromaApi chromaApi, ChromaProperties properties) {
        try {
            chromaApi.getTenant(properties.tenant());
            log.debug("chroma_tenant_present tenant={}", properties.tenant());
        } catch (RuntimeException e) {
            log.info("chroma_tenant_creating tenant={}", properties.tenant());
            chromaApi.createTenant(properties.tenant());
        }

        try {
            chromaApi.getDatabase(properties.tenant(), properties.database());
            log.debug("chroma_database_present database={}", properties.database());
        } catch (RuntimeException e) {
            log.info("chroma_database_creating database={}", properties.database());
            chromaApi.createDatabase(properties.tenant(), properties.database());
        }
    }
}
