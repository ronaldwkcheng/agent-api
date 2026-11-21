package com.ronald.agent.subagent.rag;

import com.ronald.agent.subagent.AbstractAgentBuilder;
import com.ronald.agent.subagent.AbstractPromptSubAgent;
import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A retrieval-augmented generation agent: it semantic-searches a {@link VectorStore} for passages
 * relevant to the question, renders them into a prompt, and returns the model's grounded answer.
 *
 * <p>Two steps, in order:</p>
 * <ol>
 *   <li><b>Retrieve</b> — the question becomes a {@link SearchRequest} against the
 *       {@link VectorStore}, bounded by {@link Builder#topK topK} and
 *       {@link Builder#similarityThreshold similarityThreshold}</li>
 *   <li><b>Generate</b> — the retrieved passages are formatted into the {@code documents}
 *       context key and the prompt template is rendered and sent to the {@code ChatClient}</li>
 * </ol>
 *
 * <p>The agent is both a {@link SubAgent} and an {@link AgenticWorkflow}, so it works standalone
 * through {@link #invoke(String)} and as one step of any workflow in this library through
 * {@link #execute(Map)}. Composed into a {@code SequentialAgentChain}, for instance, it reads its
 * question from {@link Builder#queryKey queryKey} and writes its answer under
 * {@link Builder#outputKey outputKey}.</p>
 *
 * <p>Retrieving nothing short-circuits: the agent returns
 * {@link Builder#noDocumentsAnswer noDocumentsAnswer} without calling the model, because a
 * grounded answer has nothing to be grounded in and the call would be billed for a guess.</p>
 *
 * <p>Context keys:</p>
 * <ul>
 *   <li>{@code input} — the question to retrieve for, read from {@link Builder#queryKey queryKey}
 *       (which names this key by default)</li>
 *   <li>{@code filterExpression} — optional: a declarative, SQL-like metadata filter (Spring AI's
 *       portable filter syntax), read before every search; absent or blank means no filter</li>
 *   <li>{@code documents} — <em>written</em> by this agent: the formatted passages, visible to
 *       the prompt template</li>
 *   <li>{@code output} — the grounded answer, under {@link Builder#outputKey outputKey}</li>
 * </ul>
 *
 * <p>By default the question is searched for verbatim. Configuring
 * {@link Builder#queryRewriter(ChatClient) queryRewriter} turns retrieval into multi-query: an
 * internal agent rewrites the question into up to {@link Builder#queryVariants queryVariants}
 * search queries, every one is searched, and the results are merged — deduplicated by document
 * id (keeping the best score seen), sorted by score, and capped to {@code topK} overall, so the
 * prompt cost stays the same as a single search regardless of how many queries ran.</p>
 *
 * <p>This class names no vector store implementation. It compiles against the
 * {@link VectorStore} interface alone, so the application chooses Chroma, pgvector, Redis or
 * anything else, exactly as it chooses the model provider.</p>
 *
 * <p>Retrieval and generation are also exposed as two separate steps — {@link #retrieve(String)}
 * and {@link #generate(Map, List)} — for a caller that needs to act on the retrieved set before
 * deciding whether, or over what, to generate. {@link CorrectiveRagWorkflow} is built on exactly
 * this: it grades the retrieved passages and retries with a rewritten query before ever calling
 * {@code generate}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * RagSubAgent agent = RagSubAgent.builder()
 *     .chatClient(chatClient)
 *     .vectorStore(vectorStore)
 *     .topK(4)
 *     .similarityThreshold(0.5)
 *     .build();
 * String answer = agent.invoke("How do I configure retries?");
 * }</pre>
 */
public class RagSubAgent extends AbstractPromptSubAgent<String> implements AgenticWorkflow<String> {

    private static final Logger log = LoggerFactory.getLogger(RagSubAgent.class);

    /** Context key holding the question to retrieve for; the default {@code queryKey}. */
    public static final String CTX_INPUT     = "input";
    /** Context key this agent writes the formatted passages to before rendering the prompt. */
    public static final String CTX_DOCUMENTS = "documents";
    /** Optional context key for a declarative metadata filter, read before every search. */
    public static final String CTX_FILTER_EXPRESSION = "filterExpression";
    /** Default context key the grounded answer is published under. */
    public static final String DEFAULT_OUTPUT_KEY = "output";

    /** Default answer when retrieval comes back empty and no model call is made. */
    public static final String DEFAULT_NO_DOCUMENTS_ANSWER = "(no relevant passages found)";

    /** Default number of passages retrieved per question. */
    public static final int DEFAULT_TOP_K = 4;

    /**
     * Default similarity floor. {@code 0.0} accepts everything the store returns, which is the
     * same default {@link SearchRequest} uses — deliberately permissive, because the useful
     * threshold depends on the embedding model and the corpus.
     */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;

    /** Default number of search queries a configured {@code queryRewriter} may produce. */
    public static final int DEFAULT_QUERY_VARIANTS = 3;

    private static final String DEFAULT_RAG_PROMPT_TEMPLATE = """
            You are a question-answering assistant. Answer the question using only the reference
            passages below.

            Instructions:
            - Ground every claim in the passages. Do not draw on outside knowledge.
            - If the passages do not contain the answer, say so plainly rather than guessing.
            - Cite the passages you used by their bracketed number, like [1].

            Reference passages:
            {documents}

            Question: {input}

            Answer:
            """;

    private static final String DEFAULT_QUERY_REWRITER_PROMPT_TEMPLATE = """
            You are rewriting a question into effective search queries for a vector similarity
            search.

            Question: {input}

            Write between 1 and {queryVariants} search queries that, together, are likely to
            retrieve the passages needed to answer the question. Prefer several queries with
            different phrasing or focus when the question is broad or ambiguous; one query is
            enough when it is already specific.
            """;

    /**
     * Structured output of the internal query-rewriter agent.
     *
     * @param queries one or more search queries to run instead of the raw question
     */
    public record QueryVariants(List<String> queries) {}

    private final VectorStore        vectorStore;
    private final int                topK;
    private final double             similarityThreshold;
    private final String             queryKey;
    private final String             outputKey;
    private final String             noDocumentsAnswer;
    private final String             promptTemplate;
    private final String             systemPrompt;
    private final QueryRewriterAgent queryRewriterAgent;

    private RagSubAgent(Builder builder) {
        super(builder.getChatClient(), String.class, builder.getAdvisorCustomizer());
        this.vectorStore         = builder.vectorStore;
        this.topK                = builder.topK;
        this.similarityThreshold = builder.similarityThreshold;
        this.queryKey            = builder.queryKey;
        this.outputKey           = builder.outputKey;
        this.noDocumentsAnswer   = builder.noDocumentsAnswer;
        this.promptTemplate      = builder.getPromptTemplate() != null
                ? builder.getPromptTemplate()
                : DEFAULT_RAG_PROMPT_TEMPLATE;
        this.systemPrompt        = builder.getSystemPrompt();
        this.queryRewriterAgent  = builder.queryRewriterChatClient != null
                ? new QueryRewriterAgent(builder.queryRewriterChatClient, builder.queryVariants,
                        builder.queryRewriterPromptTemplate)
                : null;
    }

    /**
     * Creates a new {@link Builder} for constructing a {@code RagSubAgent}.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Answers the question by retrieving from the vector store and generating over what came back.
     *
     * @param input      the question; must not be null
     * @param attributes caller-supplied context entries, seeded before the question
     * @return the grounded answer, or {@code noDocumentsAnswer} if retrieval found nothing
     * @throws NullPointerException if {@code input} or {@code attributes} is null
     */
    @Override
    public String invoke(String input, Map<String, String> attributes) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");

        Map<String, String> context = new HashMap<>(attributes);
        context.put(queryKey, input);
        return execute(Collections.unmodifiableMap(context));
    }

    /**
     * Retrieves for the question found under {@code queryKey}, then generates the answer.
     *
     * <p>The passages are added to a copy of the context under {@link #CTX_DOCUMENTS}, so the
     * caller's map is left untouched and any upstream keys stay visible to the prompt template.</p>
     *
     * @param context must contain a non-null entry for the configured {@code queryKey}
     * @return the grounded answer, or {@code noDocumentsAnswer} if retrieval found nothing
     * @throws NullPointerException if {@code context} is null or holds no question
     */
    @Override
    public String execute(Map<String, String> context) {
        Objects.requireNonNull(context, "context must not be null");
        String query = Objects.requireNonNull(context.get(queryKey),
                () -> "context must contain the question under the key \"" + queryKey + "\"");

        List<Document> documents = retrieve(query, context.get(CTX_FILTER_EXPRESSION));
        if (documents.isEmpty()) {
            log.warn("rag_no_documents query=\"{}\" topK={} threshold={} — returning the "
                    + "no-documents answer without calling the model", query, topK, similarityThreshold);
        }
        return generate(context, documents);
    }

    /**
     * Generates an answer by rendering the given, already-retrieved passages into the prompt —
     * skipping retrieval entirely.
     *
     * <p>Exposed for callers that have their own reason to hold a retrieved set before generating
     * over it, such as a workflow that grades passages before deciding whether to answer from
     * them. An empty list is treated exactly like empty retrieval in {@link #execute(Map)}:
     * {@code noDocumentsAnswer} is returned without a model call.</p>
     *
     * @param context   the context the prompt template renders against; must contain whatever
     *                  the template needs, e.g. {@code input} for the default template
     * @param documents the passages to generate over, closest first; may be empty
     * @return the grounded answer, or {@code noDocumentsAnswer} if {@code documents} is empty
     * @throws NullPointerException if {@code context} or {@code documents} is null
     */
    public String generate(Map<String, String> context, List<Document> documents) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(documents, "documents must not be null");
        if (documents.isEmpty()) {
            return noDocumentsAnswer;
        }

        Map<String, String> augmented = new HashMap<>(context);
        augmented.put(CTX_DOCUMENTS, formatDocuments(documents));
        return super.execute(augmented);
    }

    /**
     * Runs the semantic search alone, without generating an answer, and without a metadata
     * filter.
     *
     * <p>Exposed because the retrieved set is what makes a RAG answer explainable: callers that
     * need to show sources, or to judge whether the corpus can answer at all, should not have to
     * pay for a model call to find out.</p>
     *
     * @param query the question to search for; must not be null
     * @return the matching documents, closest first; never null, possibly empty
     * @throws NullPointerException if {@code query} is null
     */
    public List<Document> retrieve(String query) {
        return retrieve(query, null);
    }

    /**
     * Runs the semantic search alone, without generating an answer.
     *
     * <p>Without a configured {@link Builder#queryRewriter(ChatClient) queryRewriter}, this is
     * one search for {@code query} verbatim. With one, {@code query} is first rewritten into up
     * to {@link Builder#queryVariants queryVariants} search queries; every one is searched and
     * the results are merged — deduplicated by document id (keeping the best score seen), sorted
     * by score, and capped to {@code topK} overall.</p>
     *
     * @param query            the question to search for; must not be null
     * @param filterExpression a declarative, SQL-like metadata filter, or {@code null} (or blank)
     *                         for none — see Spring AI's {@code SearchRequest.filterExpression(String)}
     * @return the matching documents, closest first; never null, possibly empty
     * @throws NullPointerException if {@code query} is null
     */
    public List<Document> retrieve(String query, String filterExpression) {
        Objects.requireNonNull(query, "query must not be null");

        if (queryRewriterAgent == null) {
            return search(query, filterExpression);
        }

        List<String> queries = expandQuery(query);
        if (queries.size() == 1) {
            return search(queries.get(0), filterExpression);
        }

        List<Document> merged = mergeByBestScore(queries.stream()
                .map(q -> search(q, filterExpression))
                .toList());
        log.debug("rag_multi_query query=\"{}\" variants={} merged={}", query, queries.size(), merged.size());
        return merged;
    }

    /** Runs one similarity search, treating a {@code null}/blank filter as none. */
    private List<Document> search(String query, String filterExpression) {
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold);
        if (filterExpression != null && !filterExpression.isBlank()) {
            requestBuilder.filterExpression(filterExpression);
        }

        List<Document> documents = vectorStore.similaritySearch(requestBuilder.build());
        // A VectorStore may return null rather than an empty list; downstream treats "found
        // nothing" one way only.
        documents = documents != null ? documents : List.of();

        log.debug("rag_retrieved query=\"{}\" topK={} threshold={} filter={} found={}",
                query, topK, similarityThreshold, filterExpression, documents.size());
        return documents;
    }

    /** Rewrites the question into one or more search queries; fails open to the original. */
    private List<String> expandQuery(String query) {
        QueryVariants variants = queryRewriterAgent.execute(Map.of(
                CTX_INPUT, query,
                "queryVariants", String.valueOf(queryRewriterAgent.queryVariants)));
        if (variants == null || variants.queries() == null || variants.queries().isEmpty()) {
            log.warn("rag_query_rewriter_empty query=\"{}\" — falling back to the original question", query);
            return List.of(query);
        }
        return variants.queries();
    }

    /**
     * Merges several searches' results, keeping one copy of each document (its best-scoring
     * occurrence), sorted by score and capped to {@code topK} — so a multi-query search costs no
     * more prompt tokens than a single one.
     */
    private List<Document> mergeByBestScore(List<List<Document>> perQueryResults) {
        Map<String, Document> bestById = new LinkedHashMap<>();
        for (List<Document> documents : perQueryResults) {
            for (Document document : documents) {
                Document existing = bestById.get(document.getId());
                if (existing == null || score(document) > score(existing)) {
                    bestById.put(document.getId(), document);
                }
            }
        }
        return bestById.values().stream()
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(topK)
                .toList();
    }

    private double score(Document document) {
        Double score = document.getScore();
        return score != null ? score : 0.0;
    }

    /**
     * Renders the retrieved passages into the block the prompt template sees.
     *
     * <p>Each passage is numbered so the model can cite it, and labelled with its source and
     * score so a reader can trace an answer back to what produced it.</p>
     *
     * @param documents the documents to format
     * @return the formatted passage block
     */
    protected String formatDocuments(List<Document> documents) {
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            formatted.append("[").append(i + 1).append("] ")
                     .append(describe(document)).append("\n")
                     .append(document.getText()).append("\n\n");
        }
        return formatted.toString().stripTrailing();
    }

    /** Builds the {@code source=..., score=...} label for one passage. */
    private static String describe(Document document) {
        Object source = document.getMetadata().get("source");
        StringBuilder label = new StringBuilder("source=")
                .append(source != null ? source : document.getId());
        if (document.getScore() != null) {
            label.append(", score=").append(String.format("%.3f", document.getScore()));
        }
        return label.toString();
    }

    /**
     * Returns the context key the grounded answer is published under.
     *
     * @return the output key
     */
    @Override
    public String getOutputKey() {
        return outputKey;
    }

    /**
     * Returns the prompt template rendered for the generation step.
     *
     * @return the prompt template string
     */
    @Override
    public String getPromptTemplate() {
        return promptTemplate;
    }

    /**
     * Returns the system prompt.
     *
     * @return the system prompt, or null
     */
    @Override
    public String getSystemPrompt() {
        return systemPrompt;
    }

    // -------------------------------------------------------------------------
    // Inner class: QueryRewriterAgent
    // -------------------------------------------------------------------------

    /** Internal agent that expands a question into one or more search queries. */
    private static class QueryRewriterAgent extends AbstractPromptSubAgent<QueryVariants> {

        private final int    queryVariants;
        private final String promptTemplate;

        QueryRewriterAgent(ChatClient chatClient, int queryVariants, String promptTemplate) {
            super(chatClient, QueryVariants.class);
            this.queryVariants  = queryVariants;
            this.promptTemplate = promptTemplate;
        }

        @Override
        public String getOutputKey() {
            return "queryVariants";
        }

        @Override
        public String getPromptTemplate() {
            return promptTemplate;
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link RagSubAgent}.
     *
     * <p>Extends {@link AbstractAgentBuilder} for the shared {@code chatClient},
     * {@code promptTemplate} and {@code systemPrompt} fields, and adds the retrieval
     * configuration. Unlike most agents here the prompt template is optional: leaving it unset
     * uses the built-in grounded-answer template.</p>
     */
    public static final class Builder extends AbstractAgentBuilder<Builder, RagSubAgent> {

        private VectorStore vectorStore;
        private int         topK                = DEFAULT_TOP_K;
        private double      similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
        private String      queryKey            = CTX_INPUT;
        private String      outputKey           = DEFAULT_OUTPUT_KEY;
        private String      noDocumentsAnswer   = DEFAULT_NO_DOCUMENTS_ANSWER;
        private ChatClient   queryRewriterChatClient;
        private int          queryVariants               = DEFAULT_QUERY_VARIANTS;
        private String       queryRewriterPromptTemplate  = DEFAULT_QUERY_REWRITER_PROMPT_TEMPLATE;

        private Builder() {}

        /**
         * Sets the vector store searched for relevant passages.
         *
         * @param vectorStore the store to retrieve from; must not be null
         * @return this builder
         */
        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        /**
         * Sets how many passages to retrieve per question. Defaults to {@value #DEFAULT_TOP_K}.
         *
         * <p>Every passage is rendered into the prompt in full, so this is the main lever on both
         * answer quality and token cost.</p>
         *
         * @param topK the maximum number of passages; must be at least 1
         * @return this builder
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets the similarity floor a passage must clear to be retrieved. Defaults to
         * {@code 0.0}, which accepts everything the store returns.
         *
         * <p>What counts as a good threshold depends on the embedding model and the corpus, so
         * there is no useful universal default — measure it against your own data.</p>
         *
         * @param similarityThreshold the floor, between 0.0 and 1.0 inclusive
         * @return this builder
         */
        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /**
         * Sets the context key the question is read from. Defaults to {@value #CTX_INPUT}.
         *
         * <p>Change it when the agent runs inside a workflow whose question arrives under another
         * name — an upstream agent's output key, for instance. The prompt template's placeholder
         * must match: the template renders straight from the context map, so a {@code queryKey}
         * of {@code topic} needs {@code {topic}} in the template.</p>
         *
         * @param queryKey the context key holding the question; must not be null or blank
         * @return this builder
         */
        public Builder queryKey(String queryKey) {
            this.queryKey = queryKey;
            return this;
        }

        /**
         * Sets the context key the answer is published under. Defaults to
         * {@value #DEFAULT_OUTPUT_KEY}.
         *
         * @param outputKey the output key
         * @return this builder
         */
        public Builder outputKey(String outputKey) {
            this.outputKey = outputKey;
            return this;
        }

        /**
         * Sets what to return when retrieval finds nothing. Defaults to
         * {@value #DEFAULT_NO_DOCUMENTS_ANSWER}.
         *
         * <p>This value is returned <em>instead of</em> calling the model, so it is the answer
         * itself and not a prompt fragment.</p>
         *
         * @param noDocumentsAnswer the answer to return on empty retrieval; must not be null
         * @return this builder
         */
        public Builder noDocumentsAnswer(String noDocumentsAnswer) {
            this.noDocumentsAnswer = noDocumentsAnswer;
            return this;
        }

        /**
         * Enables multi-query retrieval: the question is rewritten into one or more search
         * queries by an internal agent built from this {@link ChatClient}, every one is
         * searched, and the results are merged. Disabled by default — the raw question alone is
         * searched, exactly as without this method.
         *
         * @param chatClient the chat client backing the internal rewriter agent; {@code null}
         *                   (the default) disables multi-query retrieval entirely
         * @return this builder
         */
        public Builder queryRewriter(ChatClient chatClient) {
            this.queryRewriterChatClient = chatClient;
            return this;
        }

        /**
         * Sets the maximum number of search queries the rewriter may produce. Defaults to
         * {@value #DEFAULT_QUERY_VARIANTS}. Unused unless {@link #queryRewriter(ChatClient)} is
         * set.
         *
         * @param queryVariants the maximum number of queries; must be at least 1
         * @return this builder
         */
        public Builder queryVariants(int queryVariants) {
            this.queryVariants = queryVariants;
            return this;
        }

        /**
         * Overrides the default query-rewriter prompt template. Unused unless
         * {@link #queryRewriter(ChatClient)} is set.
         * The template must contain the {@code {input}} and {@code {queryVariants}} placeholders.
         *
         * @param queryRewriterPromptTemplate the custom query-rewriter prompt template
         * @return this builder
         */
        public Builder queryRewriterPromptTemplate(String queryRewriterPromptTemplate) {
            this.queryRewriterPromptTemplate = queryRewriterPromptTemplate;
            return this;
        }

        /**
         * Builds the {@link RagSubAgent}.
         *
         * @return the configured agent
         * @throws NullPointerException     if {@code chatClient}, {@code vectorStore},
         *                                  {@code queryKey} or {@code noDocumentsAnswer} is null
         * @throws IllegalArgumentException if {@code topK} is less than 1, {@code queryKey} is
         *                                  blank, {@code similarityThreshold} falls outside
         *                                  0.0..1.0, or {@code queryVariants} is less than 1
         */
        @Override
        public RagSubAgent build() {
            Objects.requireNonNull(getChatClient(), "chatClient must not be null");
            Objects.requireNonNull(vectorStore, "vectorStore must not be null");
            Objects.requireNonNull(queryKey, "queryKey must not be null");
            Objects.requireNonNull(noDocumentsAnswer, "noDocumentsAnswer must not be null");
            if (queryKey.isBlank()) {
                throw new IllegalArgumentException("queryKey must not be blank");
            }
            if (topK < 1) {
                throw new IllegalArgumentException("topK must be at least 1");
            }
            if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
                throw new IllegalArgumentException(
                        "similarityThreshold must be between 0.0 and 1.0, but was " + similarityThreshold);
            }
            if (queryVariants < 1) {
                throw new IllegalArgumentException("queryVariants must be at least 1");
            }
            return new RagSubAgent(this);
        }
    }
}
