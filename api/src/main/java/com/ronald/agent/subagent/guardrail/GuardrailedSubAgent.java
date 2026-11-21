package com.ronald.agent.subagent.guardrail;

import com.ronald.agent.subagent.SubAgent;
import com.ronald.agent.workflow.ExhaustionPolicy;
import com.ronald.agent.workflow.WorkflowExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link SubAgent} decorator that puts automated input and output checks around another
 * agent's execution &mdash; the automated counterpart of
 * {@code com.ronald.agent.subagent.review.HumanReviewSubAgent}, run by code instead of a person,
 * and sharing its four-outcome {@link GuardrailDecision} shape (pass / modify / retry / block
 * mirrors approve / edit / revise / reject).
 *
 * <p>Input guardrails run once, against the {@code "input"} context key, before the delegate
 * executes. Output guardrails run against the delegate's result after it executes, and may ask
 * for a retry: the delegate runs again with feedback in context, exactly as a human reviewer's
 * {@code revise} does. Because every workflow in this library composes {@code SubAgent}s,
 * wrapping one step adds guardrails to any pattern without the workflow knowing.</p>
 *
 * <pre>{@code
 * SubAgent<String> guarded = GuardrailedSubAgent.builder()
 *         .delegate(draftReplyAgent)
 *         .inputGuardrail(new PiiGuardrail())
 *         .outputGuardrail(new PiiGuardrail())
 *         .outputGuardrail(new MaxLengthGuardrail(400))
 *         .maxRetries(1)
 *         .build();
 * }</pre>
 *
 * <h2>Decisions</h2>
 * <ul>
 *   <li><b>pass</b> &mdash; the content moves on unchanged.</li>
 *   <li><b>modify</b> &mdash; the guardrail's own fix replaces the content; no further model
 *       call. Visible to later guardrails in the same pass; earlier guardrails are not re-run
 *       against it.</li>
 *   <li><b>retry</b> &mdash; <em>output guardrails only.</em> The delegate runs again with
 *       {@value #CTX_FEEDBACK} and {@value #CTX_PREVIOUS_OUTPUT} set, and every output guardrail
 *       re-checks the new output from the start. An input guardrail returning this is a
 *       misconfiguration and fails fast with {@link IllegalStateException}.</li>
 *   <li><b>block</b> &mdash; {@link GuardrailViolationException} is thrown and the workflow
 *       stops.</li>
 * </ul>
 *
 * <h2>Context keys</h2>
 * <p>The delegate receives a copy of the workflow's context with two keys added, both present on
 * every attempt (empty on the first) so a prompt template may reference them unconditionally
 * &mdash; Spring AI's {@code PromptTemplate} fails on a placeholder with no value.</p>
 *
 * <h2>Exhaustion</h2>
 * <p>At most {@code maxRetries} retries are run. A {@code retry} beyond that follows the
 * library's {@link ExhaustionPolicy}: {@code THROW} (default) raises
 * {@link WorkflowExhaustedException} carrying the last output; {@code RETURN_PARTIAL} returns
 * that last, <em>unvalidated</em> output &mdash; choose it only if bypassing the gate is
 * acceptable downstream.</p>
 *
 * <p>Only {@code String}-returning agents can be wrapped: the checks shipped with this library
 * (and the sensible ones to write) work on text.</p>
 */
public final class GuardrailedSubAgent implements SubAgent<String> {

    private static final Logger log = LoggerFactory.getLogger(GuardrailedSubAgent.class);

    private static final String CTX_INPUT = "input";

    /** Context key carrying the output guardrail feedback on the previous attempt. */
    public static final String CTX_FEEDBACK = "guardrailFeedback";

    /** Context key carrying the output the feedback was about. */
    public static final String CTX_PREVIOUS_OUTPUT = "previousOutput";

    private final SubAgent<String> delegate;
    private final List<InputGuardrail> inputGuardrails;
    private final List<OutputGuardrail> outputGuardrails;
    private final String stepName;
    private final int maxRetries;
    private final ExhaustionPolicy exhaustionPolicy;

    private GuardrailedSubAgent(Builder builder) {
        this.delegate = builder.delegate;
        this.inputGuardrails = List.copyOf(builder.inputGuardrails);
        this.outputGuardrails = List.copyOf(builder.outputGuardrails);
        this.stepName = builder.stepName;
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
     * Runs the input guardrails, then the delegate, then the output guardrails &mdash; retrying
     * the delegate while a retry is requested and the budget allows.
     *
     * @throws GuardrailViolationException if a guardrail blocks the input or the output
     * @throws WorkflowExhaustedException  if retries exceed {@code maxRetries} under
     *                                     {@link ExhaustionPolicy#THROW}
     */
    @Override
    public String execute(Map<String, String> context) {
        Map<String, String> attemptContext = new HashMap<>(context);
        attemptContext.put(CTX_INPUT, checkInput(attemptContext));
        attemptContext.put(CTX_FEEDBACK, "");
        attemptContext.put(CTX_PREVIOUS_OUTPUT, "");

        for (int attempt = 1; ; attempt++) {
            String output = Objects.requireNonNull(delegate.execute(Collections.unmodifiableMap(attemptContext)),
                    "delegate of step '" + stepName + "' returned null");

            OutputPass pass = checkOutput(output, attemptContext, attempt);
            if (!pass.retry()) {
                return pass.content();
            }

            int retriesDone = attempt - 1;
            if (retriesDone >= maxRetries) {
                return exhausted(pass.content());
            }
            attemptContext.put(CTX_FEEDBACK, pass.feedback());
            attemptContext.put(CTX_PREVIOUS_OUTPUT, pass.content());
        }
    }

    private String checkInput(Map<String, String> context) {
        String input = context.getOrDefault(CTX_INPUT, "");
        for (InputGuardrail guardrail : inputGuardrails) {
            GuardrailDecision decision = Objects.requireNonNull(
                    guardrail.check(new InputGuardrailCheck(stepName, input, context)),
                    "input guardrail returned null for step '" + stepName + "'");
            log.info("input_guardrail_decision step={} guardrail={} decision={}",
                    stepName, guardrail.getClass().getSimpleName(), decision.getClass().getSimpleName());
            switch (decision) {
                case GuardrailDecision.Pass ignored -> { }
                case GuardrailDecision.Modify m -> input = m.replacement();
                case GuardrailDecision.Block b ->
                        throw new GuardrailViolationException(stepName, GuardrailStage.INPUT, b.reason(), input);
                case GuardrailDecision.Retry ignored -> throw new IllegalStateException(
                        "input guardrail '" + guardrail.getClass().getSimpleName() + "' for step '" + stepName
                                + "' returned retry(), which has no meaning before the delegate has run; "
                                + "return pass(), modify(...) or block(...) instead");
            }
        }
        return input;
    }

    private OutputPass checkOutput(String output, Map<String, String> context, int attempt) {
        String current = output;
        for (OutputGuardrail guardrail : outputGuardrails) {
            GuardrailDecision decision = Objects.requireNonNull(
                    guardrail.check(new OutputGuardrailCheck(stepName, current, context, attempt)),
                    "output guardrail returned null for step '" + stepName + "'");
            log.info("output_guardrail_decision step={} attempt={} guardrail={} decision={}",
                    stepName, attempt, guardrail.getClass().getSimpleName(), decision.getClass().getSimpleName());
            switch (decision) {
                case GuardrailDecision.Pass ignored -> { }
                case GuardrailDecision.Modify m -> current = m.replacement();
                case GuardrailDecision.Block b ->
                        throw new GuardrailViolationException(stepName, GuardrailStage.OUTPUT, b.reason(), current);
                case GuardrailDecision.Retry r -> {
                    return OutputPass.retry(current, r.feedback());
                }
            }
        }
        return OutputPass.done(current);
    }

    private String exhausted(String lastOutput) {
        String message = "Step '" + stepName + "' output still failed output guardrails after "
                + maxRetries + " retr" + (maxRetries == 1 ? "y" : "ies");
        if (exhaustionPolicy == ExhaustionPolicy.RETURN_PARTIAL) {
            log.warn("{}; returning the last unvalidated output", message);
            return lastOutput;
        }
        throw new WorkflowExhaustedException(message, maxRetries, lastOutput);
    }

    /** Outcome of one pass over the output guardrails. */
    private record OutputPass(boolean retry, String content, String feedback) {
        static OutputPass done(String content) {
            return new OutputPass(false, content, null);
        }

        static OutputPass retry(String content, String feedback) {
            return new OutputPass(true, content, feedback);
        }
    }

    /**
     * Builder for {@link GuardrailedSubAgent}. {@code delegate} is required, and at least one
     * input or output guardrail must be configured.
     */
    public static final class Builder {

        private SubAgent<String> delegate;
        private final List<InputGuardrail> inputGuardrails = new ArrayList<>();
        private final List<OutputGuardrail> outputGuardrails = new ArrayList<>();
        private String stepName;
        private int maxRetries = 1;
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
         * Adds one input guardrail, run against {@code "input"} before the delegate executes.
         *
         * @param guardrail the guardrail to add
         * @return this builder
         */
        public Builder inputGuardrail(InputGuardrail guardrail) {
            this.inputGuardrails.add(Objects.requireNonNull(guardrail, "guardrail must not be null"));
            return this;
        }

        /**
         * Adds input guardrails, run in the given order.
         *
         * @param guardrails the guardrails to add
         * @return this builder
         */
        public Builder inputGuardrails(InputGuardrail... guardrails) {
            Arrays.stream(guardrails).forEach(this::inputGuardrail);
            return this;
        }

        /**
         * Adds one output guardrail, run against the delegate's result after it executes.
         *
         * @param guardrail the guardrail to add
         * @return this builder
         */
        public Builder outputGuardrail(OutputGuardrail guardrail) {
            this.outputGuardrails.add(Objects.requireNonNull(guardrail, "guardrail must not be null"));
            return this;
        }

        /**
         * Adds output guardrails, run in the given order.
         *
         * @param guardrails the guardrails to add
         * @return this builder
         */
        public Builder outputGuardrails(OutputGuardrail... guardrails) {
            Arrays.stream(guardrails).forEach(this::outputGuardrail);
            return this;
        }

        /**
         * Sets the label used in {@link InputGuardrailCheck} and {@link OutputGuardrailCheck}.
         * Defaults to the delegate's output key, or its class name when it has none.
         *
         * @param stepName a human-readable step label
         * @return this builder
         */
        public Builder stepName(String stepName) {
            this.stepName = stepName;
            return this;
        }

        /**
         * Sets how many times an output guardrail may send a result back for a retry. Zero means
         * the first output must pass (or be modified by) every output guardrail. Defaults to 1.
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
         * @throws IllegalStateException    if neither an input nor an output guardrail was added
         * @throws IllegalArgumentException if maxRetries is negative
         */
        public GuardrailedSubAgent build() {
            Objects.requireNonNull(delegate, "delegate must not be null");
            Objects.requireNonNull(exhaustionPolicy, "exhaustionPolicy must not be null");
            if (inputGuardrails.isEmpty() && outputGuardrails.isEmpty()) {
                throw new IllegalStateException("At least one input or output guardrail must be configured.");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
            }
            if (stepName == null || stepName.isBlank()) {
                stepName = delegate.getOutputKey() != null
                        ? delegate.getOutputKey()
                        : delegate.getClass().getSimpleName();
            }
            return new GuardrailedSubAgent(this);
        }
    }
}
