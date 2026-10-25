package com.ronald.agent.subagent.guardrail;

import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.ExhaustionPolicy;
import com.ronald.agent.workflow.WorkflowExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link SubAgent} decorator that runs automated checks on the text going into, and coming out
 * of, another agent.
 *
 * <p>Input guardrails see one context entry ({@code input} by default) before the wrapped agent
 * runs; output guardrails see what it produced. Because every workflow in this library composes
 * {@code SubAgent}s, wrapping one step adds guardrails to any pattern without the workflow knowing
 * &mdash; a sequential step, a parallel branch, a worker, a synthesizer. It is the automated
 * counterpart of {@code HumanReviewSubAgent}, and the two compose: a guarded agent can be
 * reviewed, or a reviewed one guarded.</p>
 *
 * <pre>{@code
 * SubAgent<String> guarded = GuardrailSubAgent.builder()
 *         .delegate(replyAgent)
 *         .inputGuardrail(Guardrails.redactMatching("email", EMAIL, "[email]"))
 *         .outputGuardrails(Guardrails.maxLength(800), noRefundPromises)
 *         .maxRetries(2)
 *         .build();
 * }</pre>
 *
 * <h2>Decisions</h2>
 * <p>Guardrails of a stage run in the order they were added, and each sees the text as the one
 * before it left it.</p>
 * <ul>
 *   <li><b>pass</b> &mdash; the text continues unchanged to the next guardrail.</li>
 *   <li><b>rewrite</b> &mdash; the guardrail's text replaces it; for the input stage, the wrapped
 *       agent receives the rewritten value under the input key.</li>
 *   <li><b>retry</b> &mdash; output stage only. The delegate runs again with {@value #CTX_FEEDBACK}
 *       and {@value #CTX_PREVIOUS_OUTPUT} set, and every output guardrail checks the new output
 *       from the first. An input guardrail returning it is a programming error.</li>
 *   <li><b>block</b> &mdash; {@link GuardrailViolationException} is thrown and the workflow stops.</li>
 * </ul>
 *
 * <h2>Context keys</h2>
 * <p>The delegate receives a copy of the workflow's context with two keys added. On the first
 * attempt both are the empty string, so a prompt template may reference them unconditionally
 * &mdash; Spring AI's {@code PromptTemplate} fails on a placeholder with no value.</p>
 *
 * <h2>Exhaustion</h2>
 * <p>At most {@code maxRetries} retries are run. A {@code retry} beyond that follows the
 * library's {@link ExhaustionPolicy}: {@code THROW} (default) raises
 * {@link WorkflowExhaustedException} carrying the last output; {@code RETURN_PARTIAL} returns that
 * last output, which <em>failed</em> a guardrail &mdash; choose it only if that is acceptable
 * downstream.</p>
 *
 * <p>Only {@code String}-returning agents can be wrapped: guardrails check and rewrite text.</p>
 */
public final class GuardrailSubAgent implements SubAgent<String> {

    private static final Logger log = LoggerFactory.getLogger(GuardrailSubAgent.class);

    /** Context key carrying the feedback of the output guardrail that asked for a retry. */
    public static final String CTX_FEEDBACK = "guardrailFeedback";

    /** Context key carrying the output that an output guardrail sent back. */
    public static final String CTX_PREVIOUS_OUTPUT = "previousOutput";

    /** The context key input guardrails check unless told otherwise. */
    public static final String DEFAULT_INPUT_KEY = "input";

    private final SubAgent<String> delegate;
    private final String stepName;
    private final String inputKey;
    private final List<Guardrail> inputGuardrails;
    private final List<Guardrail> outputGuardrails;
    private final int maxRetries;
    private final ExhaustionPolicy exhaustionPolicy;

    private GuardrailSubAgent(Builder builder) {
        this.delegate = builder.delegate;
        this.stepName = builder.stepName;
        this.inputKey = builder.inputKey;
        this.inputGuardrails = List.copyOf(builder.inputGuardrails);
        this.outputGuardrails = List.copyOf(builder.outputGuardrails);
        this.maxRetries = builder.maxRetries;
        this.exhaustionPolicy = builder.exhaustionPolicy;
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the delegate's output key, so wrapping a step does not change where the workflow
     * files its result.
     */
    @Override
    public String getOutputKey() {
        return delegate.getOutputKey();
    }

    /**
     * Checks the input, runs the delegate, and checks its output, retrying until every output
     * guardrail passes or the retry budget is spent.
     *
     * @throws GuardrailViolationException if a guardrail blocks the input or an output
     * @throws IllegalStateException       if input guardrails are configured but the context has no
     *                                     value under the input key, or one returns {@code retry}
     * @throws WorkflowExhaustedException  if retries exceed {@code maxRetries} under
     *                                     {@link ExhaustionPolicy#THROW}
     */
    @Override
    public String execute(Map<String, String> context) {
        Map<String, String> attemptContext = new HashMap<>(context);
        if (!inputGuardrails.isEmpty()) {
            attemptContext.put(inputKey, checkInput(attemptContext));
        }
        attemptContext.put(CTX_FEEDBACK, "");
        attemptContext.put(CTX_PREVIOUS_OUTPUT, "");

        for (int attempt = 1; ; attempt++) {
            String output = Objects.requireNonNull(delegate.execute(Collections.unmodifiableMap(attemptContext)),
                    "delegate of step '" + stepName + "' returned null");

            OutputCheck check = checkOutput(output, attemptContext, attempt);
            if (check.retryFeedback() == null) {
                return check.text();
            }
            int retriesDone = attempt - 1;
            if (retriesDone >= maxRetries) {
                return exhausted(check.text());
            }
            attemptContext.put(CTX_FEEDBACK, check.retryFeedback());
            attemptContext.put(CTX_PREVIOUS_OUTPUT, check.text());
        }
    }

    private String checkInput(Map<String, String> context) {
        String text = context.get(inputKey);
        if (text == null) {
            // Fail closed: an unchecked input must never reach the delegate just because the
            // guardrail was pointed at the wrong key.
            throw new IllegalStateException("Step '" + stepName + "' has input guardrails but the context "
                    + "has no value under '" + inputKey + "'");
        }
        for (Guardrail guardrail : inputGuardrails) {
            GuardrailDecision decision = decide(guardrail,
                    new GuardrailRequest(stepName, GuardrailStage.INPUT, text, context, 1));
            switch (decision) {
                case GuardrailDecision.Pass p -> { }
                case GuardrailDecision.Rewrite r -> text = r.replacement();
                case GuardrailDecision.Block b -> throw new GuardrailViolationException(
                        stepName, guardrail.name(), GuardrailStage.INPUT, b.reason(), text);
                case GuardrailDecision.Retry r -> throw new IllegalStateException("Input guardrail '"
                        + guardrail.name() + "' of step '" + stepName + "' returned retry; an input "
                        + "cannot be regenerated, so return rewrite or block instead");
            }
        }
        return text;
    }

    /**
     * Runs every output guardrail in order. Stops at the first retry, returning the text as that
     * guardrail saw it together with its feedback; otherwise returns the fully checked text.
     */
    private OutputCheck checkOutput(String output, Map<String, String> context, int attempt) {
        String text = output;
        for (Guardrail guardrail : outputGuardrails) {
            GuardrailDecision decision = decide(guardrail,
                    new GuardrailRequest(stepName, GuardrailStage.OUTPUT, text, context, attempt));
            switch (decision) {
                case GuardrailDecision.Pass p -> { }
                case GuardrailDecision.Rewrite r -> text = r.replacement();
                case GuardrailDecision.Block b -> throw new GuardrailViolationException(
                        stepName, guardrail.name(), GuardrailStage.OUTPUT, b.reason(), text);
                case GuardrailDecision.Retry r -> {
                    return new OutputCheck(text, r.feedback());
                }
            }
        }
        return new OutputCheck(text, null);
    }

    private GuardrailDecision decide(Guardrail guardrail, GuardrailRequest request) {
        GuardrailDecision decision = Objects.requireNonNull(guardrail.check(request),
                "guardrail '" + guardrail.name() + "' returned null for step '" + stepName + "'");
        if (decision instanceof GuardrailDecision.Pass) {
            log.debug("guardrail_decision step={} stage={} guardrail={} attempt={} decision=Pass",
                    stepName, request.stage(), guardrail.name(), request.attempt());
        } else {
            log.info("guardrail_decision step={} stage={} guardrail={} attempt={} decision={}",
                    stepName, request.stage(), guardrail.name(), request.attempt(),
                    decision.getClass().getSimpleName());
        }
        return decision;
    }

    private String exhausted(String lastOutput) {
        String message = "Step '" + stepName + "' still failed its output guardrails after "
                + maxRetries + " retr" + (maxRetries == 1 ? "y" : "ies");
        if (exhaustionPolicy == ExhaustionPolicy.RETURN_PARTIAL) {
            log.warn("{}; returning the last output, which did not pass", message);
            return lastOutput;
        }
        throw new WorkflowExhaustedException(message, maxRetries, lastOutput);
    }

    /** The output as checked so far, and the feedback of the guardrail that wants a retry, if any. */
    private record OutputCheck(String text, String retryFeedback) {}

    /**
     * Builder for {@link GuardrailSubAgent}. {@code delegate} and at least one guardrail are
     * required.
     */
    public static final class Builder {

        private SubAgent<String> delegate;
        private String stepName;
        private String inputKey = DEFAULT_INPUT_KEY;
        private final List<Guardrail> inputGuardrails = new ArrayList<>();
        private final List<Guardrail> outputGuardrails = new ArrayList<>();
        private int maxRetries = 2;
        private ExhaustionPolicy exhaustionPolicy = ExhaustionPolicy.THROW;

        private Builder() {}

        /**
         * @param delegate the agent whose input and output are checked
         * @return this builder
         */
        public Builder delegate(SubAgent<String> delegate) {
            this.delegate = delegate;
            return this;
        }

        /**
         * Sets the label used in logs and exceptions. Defaults to the delegate's output key, or
         * its class name when it has none.
         *
         * @param stepName a human-readable step label
         * @return this builder
         */
        public Builder stepName(String stepName) {
            this.stepName = stepName;
            return this;
        }

        /**
         * Sets the context key input guardrails check and rewrite. Defaults to
         * {@value #DEFAULT_INPUT_KEY}. Point it at another key to guard a step whose real input
         * is an earlier step's output.
         *
         * @param inputKey the context key; must not be blank or {@link SubAgent#CONVERSATION_ID}
         * @return this builder
         */
        public Builder inputKey(String inputKey) {
            this.inputKey = inputKey;
            return this;
        }

        /**
         * Adds a check run on the input before the delegate executes.
         *
         * @param guardrail the check
         * @return this builder
         */
        public Builder inputGuardrail(Guardrail guardrail) {
            inputGuardrails.add(Objects.requireNonNull(guardrail, "input guardrail must not be null"));
            return this;
        }

        /**
         * Adds checks run on the input, in order.
         *
         * @param guardrails the checks
         * @return this builder
         */
        public Builder inputGuardrails(Guardrail... guardrails) {
            for (Guardrail guardrail : guardrails) {
                inputGuardrail(guardrail);
            }
            return this;
        }

        /**
         * Adds a check run on the delegate's output.
         *
         * @param guardrail the check
         * @return this builder
         */
        public Builder outputGuardrail(Guardrail guardrail) {
            outputGuardrails.add(Objects.requireNonNull(guardrail, "output guardrail must not be null"));
            return this;
        }

        /**
         * Adds checks run on the delegate's output, in order.
         *
         * @param guardrails the checks
         * @return this builder
         */
        public Builder outputGuardrails(Guardrail... guardrails) {
            for (Guardrail guardrail : guardrails) {
                outputGuardrail(guardrail);
            }
            return this;
        }

        /**
         * Sets how many times output guardrails may send the delegate back to try again. Zero means
         * a retry decision is immediately exhaustion. Defaults to 2.
         *
         * @param maxRetries the retry budget; must be non-negative
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets what happens when the retry budget runs out. Defaults to
         * {@link ExhaustionPolicy#THROW}.
         *
         * @param exhaustionPolicy the policy
         * @return this builder
         */
        public Builder exhaustionPolicy(ExhaustionPolicy exhaustionPolicy) {
            this.exhaustionPolicy = exhaustionPolicy;
            return this;
        }

        /**
         * @return the configured agent
         * @throws NullPointerException     if delegate or exhaustionPolicy is null
         * @throws IllegalArgumentException if no guardrail was added, maxRetries is negative, or
         *                                  inputKey is blank or the reserved conversation id key
         */
        public GuardrailSubAgent build() {
            Objects.requireNonNull(delegate, "delegate must not be null");
            Objects.requireNonNull(exhaustionPolicy, "exhaustionPolicy must not be null");
            if (inputGuardrails.isEmpty() && outputGuardrails.isEmpty()) {
                throw new IllegalArgumentException("at least one input or output guardrail is required");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
            }
            if (inputKey == null || inputKey.isBlank()) {
                throw new IllegalArgumentException("inputKey must not be blank");
            }
            if (SubAgent.CONVERSATION_ID.equals(inputKey)) {
                throw new IllegalArgumentException("inputKey must not be the reserved '"
                        + SubAgent.CONVERSATION_ID + "' key: a rewrite would hand the delegate "
                        + "another conversation's memory");
            }
            if (stepName == null || stepName.isBlank()) {
                stepName = delegate.getOutputKey() != null
                        ? delegate.getOutputKey()
                        : delegate.getClass().getSimpleName();
            }
            return new GuardrailSubAgent(this);
        }
    }
}
