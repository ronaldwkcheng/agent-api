package com.ronald.agent.subagent.review;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * A {@link HumanReviewer} that asks a person on a text console.
 *
 * <p>The default reviewer for interactive runs &mdash; a CLI, a local development session, an
 * integration harness. The no-argument constructor reads {@code System.in} and writes
 * {@code System.out}; the two-argument one takes any {@link Reader} and {@link PrintStream}, which
 * serves a socket or SSH session as well as a test driving it with scripted input.</p>
 *
 * <p><strong>Interactive use only.</strong> In a web service or a scheduled job standard input is
 * closed or shared, so the first review hits end-of-input and, by default, fails the run. That is
 * deliberate: a gate with no human attached must not approve anything.</p>
 *
 * <h2>Extending</h2>
 * <p>{@link #review} is final so the safety rules below hold for every subclass. Customise
 * through the hooks instead:</p>
 * <ul>
 *   <li>{@link #renderProposal} &mdash; what the person sees before choosing.</li>
 *   <li>{@link #readDecision} &mdash; how the choice is read; build on {@link #ask}.</li>
 *   <li>{@link #onEndOfInput} &mdash; what happens when input closes mid-review.</li>
 * </ul>
 *
 * <h2>Rules the base class keeps</h2>
 * <ul>
 *   <li><b>One review at a time.</b> {@code review} is {@code synchronized}, so gated parallel
 *       branches queue up instead of interleaving their prompts on one console.</li>
 *   <li><b>Revisions need feedback.</b> Blank feedback is re-asked, not sent.</li>
 *   <li><b>No human, no approval.</b> End-of-input never turns into an approve.</li>
 *   <li><b>ASCII output.</b> {@code System.out} uses the platform charset, so non-ASCII
 *       decoration arrives as replacement characters on a Windows console.</li>
 * </ul>
 */
public class ConsoleHumanReviewer implements HumanReviewer {

    private final BufferedReader in;
    private final PrintStream out;

    /**
     * Creates a reviewer on the process console: {@code System.in} and {@code System.out}.
     */
    public ConsoleHumanReviewer() {
        this(new InputStreamReader(System.in), System.out);
    }

    /**
     * Creates a reviewer on the given streams.
     *
     * @param in  where the person's answers are read from
     * @param out where proposals and prompts are written
     */
    public ConsoleHumanReviewer(Reader in, PrintStream out) {
        Objects.requireNonNull(in, "in must not be null");
        this.in = in instanceof BufferedReader buffered ? buffered : new BufferedReader(in);
        this.out = Objects.requireNonNull(out, "out must not be null");
    }

    @Override
    public final synchronized ReviewDecision review(ReviewRequest request) {
        try {
            renderProposal(request);
            return Objects.requireNonNull(readDecision(request), "readDecision returned null");
        } catch (EndOfInputException e) {
            return Objects.requireNonNull(onEndOfInput(request), "onEndOfInput returned null");
        }
    }

    /**
     * Shows the proposal to the person. Override to add context &mdash; the original
     * {@code input} from {@link ReviewRequest#context()}, say &mdash; or to truncate long drafts.
     *
     * @param request the proposal under review
     */
    protected void renderProposal(ReviewRequest request) {
        out.println();
        out.println("--- Review step '" + request.stepName() + "' (attempt " + request.attempt() + ") ---");
        out.println(request.proposal());
        out.println("---");
    }

    /**
     * Reads the person's decision. The default offers approve, edit, revise and reject on single
     * keys and re-asks on anything else, including blank revision feedback.
     *
     * @param request the proposal under review
     * @return the decision; must not be null
     */
    protected ReviewDecision readDecision(ReviewRequest request) {
        while (true) {
            String choice = ask("[a]pprove, [e]dit, [r]evise, re[j]ect? ").trim().toLowerCase();
            switch (choice) {
                case "a" -> {
                    return ReviewDecision.approve();
                }
                case "e" -> {
                    return ReviewDecision.edit(ask("Replacement text: "));
                }
                case "r" -> {
                    String feedback = ask("What should change? ");
                    if (!feedback.isBlank()) {
                        return ReviewDecision.revise(feedback);
                    }
                    out.println("Feedback is required to revise.");
                }
                case "j" -> {
                    return ReviewDecision.reject(ask("Reason: "));
                }
                default -> out.println("Please type a, e, r or j.");
            }
        }
    }

    /**
     * Decides what a review returns when input closes before a decision is made. The default
     * throws, failing the run; override to return {@link ReviewDecision#reject} for a quieter
     * stop. Must never return {@link ReviewDecision#approve()} &mdash; nobody approved anything.
     *
     * @param request the proposal that was under review
     * @return the decision to use instead
     * @throws IllegalStateException by default
     */
    protected ReviewDecision onEndOfInput(ReviewRequest request) {
        throw new IllegalStateException("No console input available while reviewing step '"
                + request.stepName() + "'; run with stdin attached");
    }

    /**
     * Prints a prompt and reads one line of answer. For use by {@link #readDecision} overrides.
     *
     * @param prompt the text shown before the cursor
     * @return the line read, without its terminator
     */
    protected final String ask(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) {
                throw new EndOfInputException();
            }
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the stream proposals and prompts are written to, for {@link #renderProposal} overrides
     */
    protected final PrintStream out() {
        return out;
    }

    /** Unwinds from any depth of {@link #readDecision} to {@link #review} when input closes. */
    private static final class EndOfInputException extends RuntimeException {
        private EndOfInputException() {
            super(null, null, false, false);
        }
    }
}
