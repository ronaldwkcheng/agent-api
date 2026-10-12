package com.ronald.agent.example;

import com.ronald.agent.subagent.review.HumanReviewer;
import com.ronald.agent.subagent.review.ReviewDecision;
import com.ronald.agent.subagent.review.ReviewRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;

/**
 * A {@link HumanReviewer} that asks on the terminal. The simplest possible channel to a person,
 * for the demo; a real deployment would route the request to Slack, email or a review queue.
 *
 * <p>{@code synchronized} because a reviewer must be thread-safe: two parallel branches asking
 * at once would otherwise interleave their prompts on one console.</p>
 */
public class ConsoleHumanReviewer implements HumanReviewer {

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

    @Override
    public synchronized ReviewDecision review(ReviewRequest request) {
        // Plain ASCII on purpose: System.out uses the platform charset, so box-drawing characters
        // arrive as replacement characters on a Windows console.
        System.out.println("\n--- Review step '" + request.stepName() + "' (attempt " + request.attempt() + ") ---");
        System.out.println(request.proposal());
        System.out.println("---");

        while (true) {
            System.out.print("[a]pprove, [e]dit, [r]evise, re[j]ect? ");
            String choice = readLine().trim().toLowerCase();
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
                    System.out.println("Feedback is required to revise.");
                }
                case "j" -> {
                    return ReviewDecision.reject(ask("Reason: "));
                }
                default -> System.out.println("Please type a, e, r or j.");
            }
        }
    }

    private String ask(String prompt) {
        System.out.print(prompt);
        return readLine();
    }

    private String readLine() {
        try {
            String line = in.readLine();
            if (line == null) {
                // stdin closed: no human is attached, so nothing can be approved.
                throw new IllegalStateException("No console input available; run with stdin attached");
            }
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
