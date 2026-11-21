package com.ronald.agent.subagent.rag;

import com.ronald.agent.subagent.AbstractPromptSubAgent;
import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.AgenticWorkflow;
import com.ronald.agent.workflow.ExhaustionPolicy;
import com.ronald.agent.workflow.WorkflowExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link AgenticWorkflow} that wraps a {@link RagSubAgent} with a grade-and-retry loop: a
 * grader judges whether the retrieved passages can actually answer the question, and if not, a
 * rewriter drafts a better search query and the workflow retrieves again — up to
 * {@code maxAttempts} times — before generating the final answer.
 *
 * <p>Plain {@link RagSubAgent} retrieves once and generates over whatever came back, good or bad.
 * This workflow adds the "corrective" half of agentic RAG: a bad first retrieval (an ambiguous
 * question, a query that does not match the corpus's vocabulary) gets a second chance before the
 * model is asked to answer from the wrong passages.</p>
 *
 * <p>Each attempt:</p>
 * <ol>
 *   <li><b>Retrieve</b> — {@link RagSubAgent#retrieve(String)} searches the vector store for the
 *       current query (the original question on the first attempt, the rewritten query after)</li>
 *   <li><b>Grade</b>    — a grader agent reads the question and the formatted passages and
 *       returns {@link RetrievalGrade#SUFFICIENT} or {@link RetrievalGrade#INSUFFICIENT}. Empty
 *       retrieval skips this call and is graded {@code INSUFFICIENT} directly, for the same
 *       reason {@link RagSubAgent} skips generation on empty retrieval: there is nothing to grade
 *       and the call would be billed for a guess.</li>
 *   <li><b>Rewrite</b>  — skipped on the final attempt — a rewriter agent reads the question, the
 *       query that just failed, and the grader's feedback, and drafts an improved query</li>
 * </ol>
 *
 * <p>Once a grade comes back {@code SUFFICIENT}, or {@code maxAttempts} is reached, the workflow
 * generates the final answer from whichever passages the last attempt retrieved, via
 * {@link RagSubAgent#generate(Map, List)} — never re-retrieving for the query that was just
 * graded. Exhausting the budget still {@code INSUFFICIENT} is handled like
 * {@link com.ronald.agent.workflow.IterativeRefinementWorkflow}'s unpassed draft: the best-effort
 * answer is computed either way, and {@link ExhaustionPolicy#THROW} (the default) throws
 * {@link WorkflowExhaustedException} carrying it as the partial result, while
 * {@link ExhaustionPolicy#RETURN_PARTIAL} returns it directly with a warning logged.</p>
 *
 * <p>Context keys:</p>
 * <ul>
 *   <li>{@code input} — the original question, unchanged across every attempt; also the key the
 *       final generation step relies on, so the wrapped {@link RagSubAgent}'s prompt template
 *       must read the question from {@code {input}} (its default template does)</li>
 *   <li>{@code query} — the current search query: the original question on attempt 1, the
 *       rewriter's output after</li>
 *   <li>{@code documents} — the current attempt's formatted passages, visible to the grader</li>
 *   <li>{@code feedback} — the grader's explanation of what was missing, visible to the rewriter</li>
 * </ul>
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * AgenticWorkflow<String> workflow = CorrectiveRagWorkflow.builder()
 *     .ragSubAgent(ragSubAgent)
 *     .chatClient(chatClient)
 *     .maxAttempts(3)
 *     .build();
 * String answer = workflow.invoke("How do I configure retries?");
 * }</pre>
 */
public class CorrectiveRagWorkflow implements AgenticWorkflow<String> {

    private static final Logger log = LoggerFactory.getLogger(CorrectiveRagWorkflow.class);

    /** Context key for the original question, unchanged across every attempt. */
    public static final String CTX_INPUT     = "input";
    /** Context key for the current attempt's search query. */
    public static final String CTX_QUERY     = "query";
    /** Context key for the current attempt's formatted passages, read by the grader. */
    public static final String CTX_DOCUMENTS = "documents";
    /** Context key for the grader's feedback, read by the rewriter. */
    public static final String CTX_FEEDBACK  = "feedback";

    /** Default number of retrieval attempts before giving up. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private static final String DEFAULT_GRADER_PROMPT_TEMPLATE = """
            You are grading whether retrieved passages are sufficient to answer a question.

            The grade field must be exactly one of: "SUFFICIENT", "INSUFFICIENT".
            - "SUFFICIENT": the passages contain enough information to answer the question
              accurately.
            - "INSUFFICIENT": the passages are off-topic, too sparse, or do not address the
              question.

            The feedback field explains what is missing or off-topic, so a search query can be
            rewritten to find better passages. Leave it empty when the grade is "SUFFICIENT".

            Question: {input}

            Retrieved passages:
            {documents}
            """;

    private static final String DEFAULT_REWRITER_PROMPT_TEMPLATE = """
            You are rewriting a search query that failed to retrieve useful passages.

            Original question: {input}
            Previous search query: {query}
            Why the retrieved passages were insufficient: {feedback}

            Write one improved search query likely to retrieve passages that answer the original
            question. Reply with the query text only — no explanation, no quotes.
            """;

    /** The two outcomes a {@link GraderAgent} can grade a retrieval as. */
    public enum RetrievalGrade {
        /** The retrieved passages contain enough information to answer the question. */
        SUFFICIENT,
        /** The retrieved passages do not address the question; the query should be rewritten. */
        INSUFFICIENT
    }

    /**
     * The grader's structured verdict on one attempt's retrieved passages.
     *
     * @param grade    whether the passages are sufficient to answer the question
     * @param feedback why they fall short, read by the rewriter; empty when {@code grade} is
     *                 {@link RetrievalGrade#SUFFICIENT}
     */
    public record GradeResult(RetrievalGrade grade, String feedback) {}

    private final RagSubAgent      ragSubAgent;
    private final GraderAgent      grader;
    private final RewriterAgent    rewriter;
    private final int              maxAttempts;
    private final ExhaustionPolicy exhaustionPolicy;

    private CorrectiveRagWorkflow(Builder builder) {
        this.ragSubAgent      = builder.ragSubAgent;
        this.grader           = new GraderAgent(builder.chatClient, builder.graderPromptTemplate);
        this.rewriter         = new RewriterAgent(builder.chatClient, builder.rewriterPromptTemplate);
        this.maxAttempts      = builder.maxAttempts;
        this.exhaustionPolicy = builder.exhaustionPolicy;
    }

    /**
     * Creates a new {@link Builder} for constructing a {@code CorrectiveRagWorkflow}.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs the grade-and-retry loop, then generates the final answer.
     *
     * @param input      the question to answer; must not be null
     * @param attributes caller-supplied context entries, seeded before this workflow's own keys
     * @return the grounded answer, or the best-effort answer if {@code maxAttempts} was exhausted
     *         and the policy is {@link ExhaustionPolicy#RETURN_PARTIAL}
     * @throws NullPointerException       if {@code input} or {@code attributes} is null
     * @throws WorkflowExhaustedException if no attempt graded {@code SUFFICIENT} within
     *                                    {@code maxAttempts} and the policy is
     *                                    {@link ExhaustionPolicy#THROW}; the best-effort answer is
     *                                    available via
     *                                    {@link WorkflowExhaustedException#getPartialResult()}
     */
    @Override
    public String invoke(String input, Map<String, String> attributes) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        log.debug("corrective_rag_start input=\"{}\"", input);

        String query = input;
        List<Document> documents = List.of();
        GradeResult grade = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            documents = ragSubAgent.retrieve(query);

            if (documents.isEmpty()) {
                log.warn("corrective_rag_no_documents attempt={} query=\"{}\"", attempt, query);
                grade = new GradeResult(RetrievalGrade.INSUFFICIENT, "Retrieval returned no passages.");
            } else {
                Map<String, String> graderContext = seeded(attributes, Map.of(
                        CTX_INPUT,     input,
                        CTX_QUERY,     query,
                        CTX_DOCUMENTS, ragSubAgent.formatDocuments(documents)));
                grade = grader.execute(graderContext);
                log.debug("corrective_rag_graded attempt={} grade={}", attempt, grade.grade());
            }

            if (grade.grade() == RetrievalGrade.SUFFICIENT || attempt == maxAttempts) {
                break;
            }

            Map<String, String> rewriterContext = seeded(attributes, Map.of(
                    CTX_INPUT,    input,
                    CTX_QUERY,    query,
                    CTX_FEEDBACK, grade.feedback() != null ? grade.feedback() : ""));
            query = rewriter.execute(rewriterContext).strip();
            log.debug("corrective_rag_rewritten attempt={} nextQuery=\"{}\"", attempt, query);
        }

        Map<String, String> generateContext = seeded(attributes, Map.of(CTX_INPUT, input));
        String answer = ragSubAgent.generate(generateContext, documents);

        if (grade.grade() == RetrievalGrade.INSUFFICIENT) {
            if (exhaustionPolicy == ExhaustionPolicy.THROW) {
                log.error("corrective_rag_exhausted attempts={} policy=THROW", maxAttempts);
                throw new WorkflowExhaustedException(
                        "Retrieved passages remained insufficient after " + maxAttempts
                                + " attempt(s)", maxAttempts, answer);
            }
            log.warn("corrective_rag_exhausted attempts={} policy=RETURN_PARTIAL — returning the "
                    + "best-effort answer over passages graded insufficient", maxAttempts);
        }

        log.debug("corrective_rag_complete");
        return answer;
    }

    /**
     * Merges caller-supplied attributes under this call's own context entries.
     *
     * <p>The call's own entries are applied last and therefore win: an attribute cannot displace
     * {@code input}, {@code query}, {@code documents} or {@code feedback}.</p>
     *
     * @param attributes the caller's attributes; may be empty
     * @param ownEntries the entries this call contributes; take precedence on collision
     * @return an unmodifiable merged context
     */
    private static Map<String, String> seeded(Map<String, String> attributes, Map<String, String> ownEntries) {
        if (attributes.isEmpty()) {
            return ownEntries;
        }
        Map<String, String> merged = new HashMap<>(attributes);
        merged.putAll(ownEntries);
        return Collections.unmodifiableMap(merged);
    }

    // -------------------------------------------------------------------------
    // Inner classes: GraderAgent and RewriterAgent
    // -------------------------------------------------------------------------

    /** Internal agent that grades retrieved passages via Spring AI structured output. */
    private static class GraderAgent extends AbstractPromptSubAgent<GradeResult> {

        private final String promptTemplate;

        GraderAgent(ChatClient chatClient, String promptTemplate) {
            super(chatClient, GradeResult.class);
            this.promptTemplate = promptTemplate;
        }

        @Override
        public String getOutputKey() {
            return "grade";
        }

        @Override
        public String getPromptTemplate() {
            return promptTemplate;
        }
    }

    /** Internal agent that drafts an improved search query after a failed retrieval. */
    private static class RewriterAgent extends AbstractPromptSubAgent<String> {

        private final String promptTemplate;

        RewriterAgent(ChatClient chatClient, String promptTemplate) {
            super(chatClient, String.class);
            this.promptTemplate = promptTemplate;
        }

        @Override
        public String getOutputKey() {
            return "rewrittenQuery";
        }

        @Override
        public String getPromptTemplate() {
            return promptTemplate;
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /** Fluent builder for {@link CorrectiveRagWorkflow}. */
    public static final class Builder {

        private RagSubAgent      ragSubAgent;
        private ChatClient       chatClient;
        private String           graderPromptTemplate   = DEFAULT_GRADER_PROMPT_TEMPLATE;
        private String           rewriterPromptTemplate = DEFAULT_REWRITER_PROMPT_TEMPLATE;
        private int              maxAttempts            = DEFAULT_MAX_ATTEMPTS;
        private ExhaustionPolicy exhaustionPolicy       = ExhaustionPolicy.THROW;

        private Builder() {}

        /**
         * Sets the {@link RagSubAgent} this workflow retrieves and generates through.
         *
         * <p>Its prompt template must read the question from {@code {input}} — the default
         * template does — because this workflow always carries the question under that key,
         * regardless of the wrapped agent's own {@code queryKey}.</p>
         *
         * @param ragSubAgent the agent to wrap; must not be null
         * @return this builder
         */
        public Builder ragSubAgent(RagSubAgent ragSubAgent) {
            this.ragSubAgent = ragSubAgent;
            return this;
        }

        /**
         * Sets the {@link ChatClient} used by the internal grader and rewriter agents.
         *
         * @param chatClient the chat client; must not be null
         * @return this builder
         */
        public Builder chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        /**
         * Overrides the default grader prompt template.
         * The template must contain the {@code {input}} and {@code {documents}} placeholders.
         *
         * @param graderPromptTemplate the custom grader prompt template
         * @return this builder
         */
        public Builder graderPromptTemplate(String graderPromptTemplate) {
            this.graderPromptTemplate = graderPromptTemplate;
            return this;
        }

        /**
         * Overrides the default query-rewriter prompt template.
         * The template must contain the {@code {input}}, {@code {query}} and {@code {feedback}}
         * placeholders.
         *
         * @param rewriterPromptTemplate the custom rewriter prompt template
         * @return this builder
         */
        public Builder rewriterPromptTemplate(String rewriterPromptTemplate) {
            this.rewriterPromptTemplate = rewriterPromptTemplate;
            return this;
        }

        /**
         * Sets the maximum number of retrieval attempts before giving up. Defaults to
         * {@value #DEFAULT_MAX_ATTEMPTS}.
         *
         * @param maxAttempts maximum attempts; must be at least 1
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets what happens when no attempt grades {@code SUFFICIENT} within {@code maxAttempts}.
         * Defaults to {@link ExhaustionPolicy#THROW}.
         *
         * @param exhaustionPolicy the policy to apply on exhaustion
         * @return this builder
         * @throws NullPointerException if exhaustionPolicy is null
         */
        public Builder exhaustionPolicy(ExhaustionPolicy exhaustionPolicy) {
            this.exhaustionPolicy = Objects.requireNonNull(exhaustionPolicy, "exhaustionPolicy must not be null");
            return this;
        }

        /**
         * Builds the {@link CorrectiveRagWorkflow}.
         *
         * @return the configured workflow
         * @throws NullPointerException     if {@code ragSubAgent} or {@code chatClient} is not set
         * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
         */
        public CorrectiveRagWorkflow build() {
            Objects.requireNonNull(ragSubAgent, "ragSubAgent must not be null");
            Objects.requireNonNull(chatClient,  "chatClient must not be null");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            return new CorrectiveRagWorkflow(this);
        }
    }
}
