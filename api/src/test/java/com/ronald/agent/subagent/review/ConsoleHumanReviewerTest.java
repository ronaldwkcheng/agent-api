package com.ronald.agent.subagent.review;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ConsoleHumanReviewer} with scripted input instead of a real console.
 */
class ConsoleHumanReviewerTest {

    private static final ReviewRequest REQUEST =
            new ReviewRequest("reply", "the draft", Map.of("input", "the complaint"), 2);

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private ConsoleHumanReviewer reviewerReading(String typed) {
        return new ConsoleHumanReviewer(new StringReader(typed),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private String printed() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void approveShowsTheProposalAndApproves() {
        ReviewDecision decision = reviewerReading("a\n").review(REQUEST);

        assertInstanceOf(ReviewDecision.Approve.class, decision);
        assertTrue(printed().contains("Review step 'reply' (attempt 2)"), printed());
        assertTrue(printed().contains("the draft"), printed());
    }

    @Test
    void editReturnsTheTypedReplacement() {
        ReviewDecision decision = reviewerReading("e\nmy own words\n").review(REQUEST);

        assertEquals(new ReviewDecision.Edit("my own words"), decision);
    }

    @Test
    void reviseReasksUntilFeedbackIsGiven() {
        ReviewDecision decision = reviewerReading("r\n   \nr\nshorter please\n").review(REQUEST);

        assertEquals(new ReviewDecision.Revise("shorter please"), decision);
        assertTrue(printed().contains("Feedback is required"), printed());
    }

    @Test
    void rejectReturnsTheReason() {
        ReviewDecision decision = reviewerReading("j\noff-brand\n").review(REQUEST);

        assertEquals(new ReviewDecision.Reject("off-brand"), decision);
    }

    @Test
    void unknownChoiceIsReaskedAndChoiceIsCaseInsensitive() {
        ReviewDecision decision = reviewerReading("x\n A \n").review(REQUEST);

        assertInstanceOf(ReviewDecision.Approve.class, decision);
        assertTrue(printed().contains("Please type a, e, r or j."), printed());
    }

    @Test
    void endOfInputFailsByDefaultRatherThanApproving() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reviewerReading("").review(REQUEST));

        assertTrue(ex.getMessage().contains("'reply'"), ex.getMessage());
    }

    @Test
    void endOfInputMidDecisionReachesTheHook() {
        // Input closes after choosing edit but before the replacement text arrives.
        ConsoleHumanReviewer reviewer = new ConsoleHumanReviewer(new StringReader("e\n"),
                new PrintStream(output, true, StandardCharsets.UTF_8)) {
            @Override
            protected ReviewDecision onEndOfInput(ReviewRequest request) {
                return ReviewDecision.reject("no reviewer attached");
            }
        };

        assertEquals(new ReviewDecision.Reject("no reviewer attached"), reviewer.review(REQUEST));
    }

    @Test
    void renderHookCanAddContext() {
        ConsoleHumanReviewer reviewer = new ConsoleHumanReviewer(new StringReader("a\n"),
                new PrintStream(output, true, StandardCharsets.UTF_8)) {
            @Override
            protected void renderProposal(ReviewRequest request) {
                out().println("Customer wrote: " + request.context().get("input"));
                super.renderProposal(request);
            }
        };

        reviewer.review(REQUEST);

        assertTrue(printed().indexOf("Customer wrote: the complaint") < printed().indexOf("the draft"),
                printed());
    }

    @Test
    void worksAsTheReviewerOfAGate() {
        HumanReviewSubAgent gate = HumanReviewSubAgent.builder()
                .delegate(new com.ronald.agent.subagent.SubAgent<>() {
                    @Override
                    public String getOutputKey() {
                        return "reply";
                    }

                    @Override
                    public String execute(Map<String, String> context) {
                        return context.get(HumanReviewSubAgent.CTX_FEEDBACK).isEmpty() ? "long draft" : "short draft";
                    }
                })
                .reviewer(reviewerReading("r\nshorter\na\n"))
                .build();

        assertEquals("short draft", gate.execute(Map.of("input", "hi")));
    }
}
