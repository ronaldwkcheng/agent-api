package com.ronald.agent.workflow;

import com.ronald.agent.subagent.SubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * An {@link AgenticWorkflow} that runs several candidate {@link SubAgent} invocations
 * concurrently (fan-out) over the same input, then picks a final result either by majority
 * vote or by handing every surviving candidate to a typed judge agent.
 *
 * <p>Unlike {@link ParallelAgentOrchestrator}, whose branches are distinct agents producing
 * distinct parts of a report, every sample here answers the <em>same</em> question and the
 * workflow decides which answer to keep. A sample is one invocation of one candidate agent;
 * {@link Builder#addCandidate(SubAgent, int)} either runs the same agent several times (relying
 * on the model's own sampling variance for diversity) or runs different agents once each (an
 * ensemble across prompts or models) &mdash; the two bullet points of this pattern collapse into
 * one mechanism, since both just add entries to the same candidate list.</p>
 *
 * <p>Every sample is bounded by a per-sample timeout (60 seconds by default) and recovered
 * according to a {@link BranchFailurePolicy}: {@link BranchFailurePolicy#FAIL_FAST} (the default)
 * aborts the workflow on any failure; {@link BranchFailurePolicy#DEGRADE} drops the failed sample
 * from the vote instead of giving it a placeholder answer &mdash; a losing or wrong-looking
 * placeholder must never accidentally win a vote or be read by the judge as a real candidate.</p>
 *
 * <p>Candidates are grouped by exact text match (after {@link Builder#voteNormalizer}, which
 * trims whitespace by default). Exactly one aggregation strategy must be configured:</p>
 * <ul>
 *   <li>{@link Builder#majorityVote()} &mdash; the group with the most members wins; ties go to
 *       whichever answer appeared first. No further model call. Requires {@code T} to be
 *       {@code String}: the winning candidate's own text is returned as is.</li>
 *   <li>{@link Builder#judge(SubAgent)} &mdash; every distinct candidate, annotated with how many
 *       samples agreed, is handed to a typed judge agent that produces the final {@code T}. Use
 *       this when candidates vary enough in wording that exact-match voting would split a real
 *       consensus into near-identical groups.</li>
 * </ul>
 *
 * <p>Use {@link #builder()} to construct instances.</p>
 *
 * <p>Example &mdash; majority vote over five runs of the same classifier:</p>
 * <pre>{@code
 * AgenticWorkflow<String> workflow = VotingAgentOrchestrator.<String>builder()
 *         .addCandidate(classifierAgent, 5)
 *         .majorityVote()
 *         .executor(executor)
 *         .build();
 * }</pre>
 *
 * <p>Example &mdash; an ensemble of two differently-prompted agents, judged by a third:</p>
 * <pre>{@code
 * AgenticWorkflow<String> workflow = VotingAgentOrchestrator.<String>builder()
 *         .addCandidate(carefulAgent)
 *         .addCandidate(creativeAgent)
 *         .judge(judgeAgent)
 *         .executor(executor)
 *         .build();
 * }</pre>
 *
 * @param <T> the type of the final result &mdash; must be {@code String} for {@code majorityVote()}
 */
public class VotingAgentOrchestrator<T> implements AgenticWorkflow<T> {

    private static final Logger log = LoggerFactory.getLogger(VotingAgentOrchestrator.class);

    /** Context key for the original input, passed to every sample and to the judge. */
    public static final String CTX_INPUT = "input";
    /** Context key for the current sample's 1-based position, passed to each sample. */
    public static final String CTX_SAMPLE_INDEX = "sampleIndex";
    /** Context key for the total number of samples, passed to each sample. */
    public static final String CTX_SAMPLE_COUNT = "sampleCount";
    /** Context key for the formatted, vote-annotated candidate list, passed to the judge only. */
    public static final String CTX_CANDIDATES = "candidates";

    /**
     * Determines what happens when a single sample fails or times out.
     *
     * <p>Mirrors {@link ParallelAgentOrchestrator.BranchFailurePolicy} in shape and intent, kept
     * as a separate type for the same reason {@link OrchestratorWorkersWorkflow.BranchFailurePolicy}
     * is: independent patterns that should not have to share a public type just because their
     * fan-out mechanics look alike.</p>
     */
    public enum BranchFailurePolicy {
        /**
         * Abort the whole workflow; the failure propagates out of {@link #invoke(String)}.
         * This is the default, and matches the behaviour of a plain {@code allOf(...).join()}.
         */
        FAIL_FAST,
        /**
         * Drop the failed sample from the vote and keep going with the rest. Unlike the
         * {@code DEGRADE} of this library's other fan-out patterns, a dropped sample contributes
         * no placeholder text: a placeholder could win a vote on an otherwise-tied field, or be
         * read by the judge as a genuine (if useless) candidate.
         */
        DEGRADE
    }

    private final List<SubAgent<String>> samples;
    private final UnaryOperator<String> voteNormalizer;
    private final SubAgent<T> judge;
    private final Executor executor;
    private final Duration sampleTimeout;
    private final BranchFailurePolicy failurePolicy;

    /**
     * Private constructor — use {@link #builder()} instead.
     *
     * @param builder the fully populated builder
     */
    private VotingAgentOrchestrator(Builder<T> builder) {
        this.samples        = List.copyOf(builder.samples);
        this.voteNormalizer = builder.voteNormalizer;
        this.judge           = builder.judge;
        this.executor        = builder.executor;
        this.sampleTimeout   = builder.sampleTimeout;
        this.failurePolicy   = builder.failurePolicy;
    }

    /**
     * Creates a new {@link Builder} for constructing a {@code VotingAgentOrchestrator}.
     *
     * @param <T> the type of the final result
     * @return a fresh builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Runs every configured sample concurrently and aggregates the survivors.
     *
     * @param input      the input every sample answers
     * @param attributes caller-supplied context entries, seeded before each sample's own keys
     * @return the winning candidate (majority vote) or the judge's typed result
     * @throws NullPointerException  if input or attributes is null
     * @throws IllegalStateException if every sample fails under {@link BranchFailurePolicy#DEGRADE},
     *                                leaving nothing to aggregate
     * @throws CompletionException   under {@link BranchFailurePolicy#FAIL_FAST}, if any sample
     *                                fails or exceeds the sample timeout
     */
    @Override
    public T invoke(String input, Map<String, String> attributes) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");

        int sampleCount = samples.size();
        log.info("voting_start samples={} timeout={} policy={}", sampleCount, sampleTimeout, failurePolicy);

        List<CompletableFuture<SampleResult>> futures = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            int sampleIndex = i + 1;
            SubAgent<String> candidate = samples.get(i);
            Map<String, String> sampleContext = seeded(attributes, Map.of(
                    CTX_INPUT, input,
                    CTX_SAMPLE_INDEX, String.valueOf(sampleIndex),
                    CTX_SAMPLE_COUNT, String.valueOf(sampleCount)));

            futures.add(withTimeout(
                    CompletableFuture.supplyAsync(() -> executeSample(sampleIndex, candidate, sampleContext), executor))
                    .handle((result, error) -> error == null ? result : recoverSample(sampleIndex, error)));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<String> survivors = new ArrayList<>(sampleCount);
        for (CompletableFuture<SampleResult> future : futures) {
            String value = future.join().value();
            if (value != null) {
                survivors.add(value);
            }
        }

        if (survivors.isEmpty()) {
            throw new IllegalStateException("All " + sampleCount + " samples failed; nothing to aggregate.");
        }

        List<CandidateGroup> groups = group(survivors);
        log.debug("voting_fan_in survivors={} distinctCandidates={}", survivors.size(), groups.size());

        if (judge == null) {
            CandidateGroup winner = majorityWinner(groups);
            log.info("voting_complete mode=majorityVote winnerVotes={}/{}", winner.count(), survivors.size());
            @SuppressWarnings("unchecked")
            T winningText = (T) winner.representative();
            return winningText;
        }

        Map<String, String> judgeContext = seeded(attributes, Map.of(
                CTX_INPUT, input,
                CTX_CANDIDATES, formatCandidates(groups, survivors.size())));
        T result = judge.execute(Collections.unmodifiableMap(judgeContext));
        log.info("voting_complete mode=judge distinctCandidates={}", groups.size());
        return result;
    }

    /**
     * Executes a single sample synchronously.
     *
     * @param sampleIndex the 1-based sample number, used only for logging and error messages
     * @param candidate   the candidate agent to invoke
     * @param context     the context this sample receives
     * @return the sample result holding its value
     * @throws NullPointerException if the candidate returns null
     */
    private SampleResult executeSample(int sampleIndex, SubAgent<String> candidate, Map<String, String> context) {
        log.debug("sample_start index={} agent={}", sampleIndex, candidate.getClass().getSimpleName());
        String result = candidate.execute(context);
        Objects.requireNonNull(result, "sample " + sampleIndex + " returned null");
        log.debug("sample_complete index={} result=\n{}", sampleIndex, result);
        return new SampleResult(result);
    }

    /**
     * Bounds a sample future by the configured timeout, if one is set.
     *
     * <p>Note that {@link CompletableFuture#orTimeout} completes the <em>future</em> exceptionally
     * but cannot interrupt the in-flight call; the underlying HTTP request runs to completion and
     * its result is discarded. The timeout therefore bounds how long the workflow waits, not how
     * long the agent runs.</p>
     *
     * @param future the sample future to bound
     * @return the bounded future, or the original future if no timeout is configured
     */
    private CompletableFuture<SampleResult> withTimeout(CompletableFuture<SampleResult> future) {
        if (sampleTimeout.isZero()) {
            return future;
        }
        return future.orTimeout(sampleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Applies the configured {@link BranchFailurePolicy} to a failed or timed-out sample.
     *
     * @param sampleIndex the 1-based sample number
     * @param error       the failure, possibly wrapped in a {@link CompletionException}
     * @return a {@link SampleResult} with a null value under {@link BranchFailurePolicy#DEGRADE},
     *         excluding the sample from the vote
     * @throws CompletionException under {@link BranchFailurePolicy#FAIL_FAST}, always
     */
    private SampleResult recoverSample(int sampleIndex, Throwable error) {
        Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                ? error.getCause()
                : error;
        String reason = cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");

        if (failurePolicy == BranchFailurePolicy.FAIL_FAST) {
            log.error("sample_failed index={} reason={} policy=FAIL_FAST", sampleIndex, reason);
            throw new CompletionException("Sample " + sampleIndex + " failed: " + reason, cause);
        }

        log.warn("sample_dropped index={} reason={} policy=DEGRADE", sampleIndex, reason);
        return new SampleResult(null);
    }

    /**
     * Groups surviving candidates by {@link #voteNormalizer}, preserving first-seen order so
     * ties resolve to whichever answer appeared first.
     *
     * @param survivors the candidate values that did not fail or time out
     * @return one group per distinct normalized value, in first-seen order
     */
    private List<CandidateGroup> group(List<String> survivors) {
        Map<String, CandidateGroup> byKey = new LinkedHashMap<>();
        for (String value : survivors) {
            String key = voteNormalizer.apply(value);
            byKey.merge(key, new CandidateGroup(value, 1), (existing, fresh) ->
                    new CandidateGroup(existing.representative(), existing.count() + 1));
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Picks the group with the most members, breaking ties by first-seen order.
     *
     * @param groups the candidate groups, in first-seen order
     * @return the winning group
     */
    private CandidateGroup majorityWinner(List<CandidateGroup> groups) {
        CandidateGroup best = groups.get(0);
        for (CandidateGroup group : groups) {
            if (group.count() > best.count()) {
                best = group;
            }
        }
        return best;
    }

    /**
     * Formats the candidate groups for the judge, each annotated with how many of the surviving
     * samples agreed on it.
     *
     * @param groups          the candidate groups, in first-seen order
     * @param totalSurvivors  the number of samples the groups were built from
     * @return the formatted candidate list
     */
    private String formatCandidates(List<CandidateGroup> groups, int totalSurvivors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            CandidateGroup group = groups.get(i);
            sb.append("Candidate ").append(i + 1)
                    .append(" (").append(group.count()).append("/").append(totalSurvivors)
                    .append(" samples agreed):\n")
                    .append(group.representative()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Merges caller-supplied attributes under a sample's or the judge's own context entries.
     *
     * @param attributes   the caller's attributes; may be empty
     * @param ownEntries   the entries this invocation contributes; take precedence on collision
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

    /**
     * Holds the result of a single sample.
     *
     * @param value the sample's output, or null if it was dropped under {@link BranchFailurePolicy#DEGRADE}
     */
    private record SampleResult(String value) {}

    /**
     * One distinct answer and how many surviving samples produced it.
     *
     * @param representative the first candidate text seen for this group
     * @param count          how many samples normalized to this group's key
     */
    private record CandidateGroup(String representative, int count) {}

    /**
     * Builder for {@link VotingAgentOrchestrator}. At least two candidate samples and exactly one
     * aggregation strategy ({@link #majorityVote()} or {@link #judge(SubAgent)}) are required.
     */
    public static final class Builder<T> {

        private static final Duration DEFAULT_SAMPLE_TIMEOUT = Duration.ofSeconds(60);

        private final List<SubAgent<String>> samples = new ArrayList<>();
        private UnaryOperator<String> voteNormalizer = String::strip;
        private SubAgent<T> judge;
        private boolean majorityVote;
        private Executor executor;
        private Duration sampleTimeout = DEFAULT_SAMPLE_TIMEOUT;
        private BranchFailurePolicy failurePolicy = BranchFailurePolicy.FAIL_FAST;

        private Builder() {}

        /**
         * Adds one sample: {@code candidate} is invoked once.
         *
         * @param candidate the agent to invoke
         * @return this builder
         */
        public Builder<T> addCandidate(SubAgent<String> candidate) {
            return addCandidate(candidate, 1);
        }

        /**
         * Adds {@code times} samples of the same candidate agent, invoked independently and
         * concurrently. Call this more than once with different agents to ensemble across
         * prompts or models instead of (or alongside) repeating one agent.
         *
         * @param candidate the agent to invoke
         * @param times     how many independent invocations to add; must be at least 1
         * @return this builder
         * @throws NullPointerException     if candidate is null
         * @throws IllegalArgumentException if times is less than 1
         */
        public Builder<T> addCandidate(SubAgent<String> candidate, int times) {
            Objects.requireNonNull(candidate, "candidate must not be null");
            if (times < 1) {
                throw new IllegalArgumentException("times must be at least 1, was " + times);
            }
            for (int i = 0; i < times; i++) {
                this.samples.add(candidate);
            }
            return this;
        }

        /**
         * Selects majority-vote aggregation: the group of surviving candidates with the most
         * members wins, with no further model call. Mutually exclusive with {@link #judge}.
         * Requires {@code T} to be {@code String}.
         *
         * @return this builder
         */
        public Builder<T> majorityVote() {
            this.majorityVote = true;
            return this;
        }

        /**
         * Selects judge aggregation: every distinct surviving candidate, annotated with how many
         * samples agreed, is handed to {@code judge} to produce the final result. Mutually
         * exclusive with {@link #majorityVote()}.
         *
         * @param judge the judge agent; its prompt template should reference {@code {input}} and
         *              {@code {candidates}}
         * @return this builder
         * @throws NullPointerException if judge is null
         */
        public Builder<T> judge(SubAgent<T> judge) {
            this.judge = Objects.requireNonNull(judge, "judge must not be null");
            return this;
        }

        /**
         * Overrides how candidate text is normalized before grouping for the vote. Defaults to
         * {@link String#strip()}, so surrounding whitespace does not split an otherwise identical
         * answer into two groups. The winning group's returned text is always the original,
         * un-normalized candidate &mdash; this only changes how equality is judged.
         *
         * @param voteNormalizer the normalizer; must not be null
         * @return this builder
         */
        public Builder<T> voteNormalizer(UnaryOperator<String> voteNormalizer) {
            this.voteNormalizer = Objects.requireNonNull(voteNormalizer, "voteNormalizer must not be null");
            return this;
        }

        /**
         * Sets the {@link Executor} used to run samples concurrently.
         *
         * @param executor the executor; must not be null
         * @return this builder
         */
        public Builder<T> executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        /**
         * Sets how long to wait for any single sample before treating it as failed.
         * Defaults to 60 seconds. Bounds the wait, not the underlying call — see
         * {@link VotingAgentOrchestrator#withTimeout}.
         *
         * @param sampleTimeout the per-sample timeout; {@link Duration#ZERO} waits indefinitely
         * @return this builder
         * @throws NullPointerException     if sampleTimeout is null
         * @throws IllegalArgumentException if sampleTimeout is negative
         */
        public Builder<T> sampleTimeout(Duration sampleTimeout) {
            Objects.requireNonNull(sampleTimeout, "sampleTimeout must not be null");
            if (sampleTimeout.isNegative()) {
                throw new IllegalArgumentException("sampleTimeout must not be negative: " + sampleTimeout);
            }
            this.sampleTimeout = sampleTimeout;
            return this;
        }

        /**
         * Waits indefinitely for every sample, restoring the pre-timeout behaviour.
         * Prefer {@link #sampleTimeout(Duration)} — an unbounded LLM call can hang the workflow.
         *
         * @return this builder
         */
        public Builder<T> noSampleTimeout() {
            return sampleTimeout(Duration.ZERO);
        }

        /**
         * Sets what happens when a sample fails or times out. Defaults to
         * {@link BranchFailurePolicy#FAIL_FAST}.
         *
         * @param failurePolicy the policy to apply to failed samples
         * @return this builder
         * @throws NullPointerException if failurePolicy is null
         */
        public Builder<T> failurePolicy(BranchFailurePolicy failurePolicy) {
            this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
            return this;
        }

        /**
         * Builds the {@link VotingAgentOrchestrator}.
         *
         * @return the configured workflow
         * @throws IllegalStateException if fewer than 2 candidate samples were added, or if
         *                                aggregation strategy is not configured exactly once
         * @throws NullPointerException   if executor is not set
         */
        public VotingAgentOrchestrator<T> build() {
            if (samples.size() < 2) {
                throw new IllegalStateException(
                        "At least 2 candidate samples are required to vote; add more with addCandidate(...).");
            }
            Objects.requireNonNull(executor, "executor must not be null");
            if (majorityVote == (judge != null)) {
                throw new IllegalStateException(
                        "Configure exactly one aggregation strategy: majorityVote() or judge(...).");
            }
            return new VotingAgentOrchestrator<>(this);
        }
    }
}
