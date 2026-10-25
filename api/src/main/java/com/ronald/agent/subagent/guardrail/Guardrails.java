package com.ronald.agent.subagent.guardrail;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ready-made deterministic {@link Guardrail}s: no model call, so they are cheap enough to run
 * first and in front of a {@link PromptGuardrail}.
 *
 * <pre>{@code
 * Pattern email = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
 *
 * GuardrailSubAgent.builder()
 *         .delegate(agent)
 *         .inputGuardrails(
 *                 Guardrails.blockMatching("prompt-injection",
 *                         Pattern.compile("(?i)ignore (all )?previous instructions"), "prompt injection"),
 *                 Guardrails.redactMatching("email", email, "[email]"))
 *         .outputGuardrail(Guardrails.maxLength(800))
 *         .build();
 * }</pre>
 */
public final class Guardrails {

    private Guardrails() {}

    /**
     * Limits the text to {@code maxChars} characters. An over-long output is sent back for a
     * retry with feedback to shorten it; an over-long input, which cannot be regenerated, is
     * blocked.
     *
     * @param maxChars the largest permitted length; must be positive
     * @return a guardrail named {@code maxLength}
     */
    public static Guardrail maxLength(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be > 0, was " + maxChars);
        }
        return Guardrail.named("maxLength", request -> {
            int length = request.content().length();
            if (length <= maxChars) {
                return GuardrailDecision.pass();
            }
            String problem = "is " + length + " characters, over the limit of " + maxChars;
            return request.stage() == GuardrailStage.OUTPUT
                    ? GuardrailDecision.retry("The answer " + problem + ". Shorten it to at most "
                            + maxChars + " characters.")
                    : GuardrailDecision.block("the input " + problem);
        });
    }

    /**
     * Blocks text in which {@code pattern} is found anywhere.
     *
     * @param name    the guardrail's name
     * @param pattern what must not appear
     * @param reason  the reason reported when it does
     * @return the guardrail
     */
    public static Guardrail blockMatching(String name, Pattern pattern, String reason) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return Guardrail.named(name, request -> pattern.matcher(request.content()).find()
                ? GuardrailDecision.block(reason)
                : GuardrailDecision.pass());
    }

    /**
     * Replaces every match of {@code pattern} with {@code replacement} &mdash; redacting personal
     * data before a model sees it, say. Text with no match passes unchanged. The replacement is
     * literal: {@code $} and {@code \} carry no group-reference meaning.
     *
     * @param name        the guardrail's name
     * @param pattern     what to redact
     * @param replacement what to put in its place
     * @return the guardrail
     */
    public static Guardrail redactMatching(String name, Pattern pattern, String replacement) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        String literal = Matcher.quoteReplacement(Objects.requireNonNull(replacement, "replacement must not be null"));
        return Guardrail.named(name, request -> {
            Matcher matcher = pattern.matcher(request.content());
            return matcher.find()
                    ? GuardrailDecision.rewrite(matcher.replaceAll(literal))
                    : GuardrailDecision.pass();
        });
    }
}
