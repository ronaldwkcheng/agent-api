package com.ronald.agent.subagent.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A ready-made {@link InputGuardrail} and {@link OutputGuardrail} that looks for a handful of
 * common PII shapes &mdash; email addresses, US Social Security numbers, credit card-looking
 * digit runs, and phone numbers &mdash; using plain regular expressions.
 *
 * <p>This is a basic, demonstration-level check, not a compliance tool: the patterns are
 * deliberately simple (no Luhn check on card numbers, no exhaustive phone formats) and it runs no
 * model call, so {@code :api} stays provider-free. One instance implements both guardrail
 * interfaces, so the same check can gate a step's input and its output:</p>
 *
 * <pre>{@code
 * PiiGuardrail pii = new PiiGuardrail();
 * GuardrailedSubAgent.builder()
 *         .delegate(summarizeStep)
 *         .inputGuardrail(pii)
 *         .outputGuardrail(pii)
 *         .build();
 * }</pre>
 */
public final class PiiGuardrail implements InputGuardrail, OutputGuardrail {

    /** What happens when PII is found. */
    public enum Mode {
        /** Replace each match with a {@code [REDACTED:<CATEGORY>]} placeholder. */
        REDACT,
        /** Block the content outright via {@link GuardrailDecision#block(String)}. */
        BLOCK
    }

    private record Category(String label, Pattern pattern) {}

    private static final List<Category> CATEGORIES = List.of(
            new Category("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
            new Category("SSN", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b")),
            new Category("CREDIT_CARD", Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b")),
            new Category("PHONE", Pattern.compile("\\b(?:\\+?1[-. ]?)?\\(?\\d{3}\\)?[-. ]?\\d{3}[-. ]?\\d{4}\\b")));

    private final Mode mode;

    /** Creates a guardrail that redacts matches in place. */
    public PiiGuardrail() {
        this(Mode.REDACT);
    }

    /**
     * @param mode what to do when PII is found
     */
    public PiiGuardrail(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    @Override
    public GuardrailDecision check(InputGuardrailCheck check) {
        return evaluate(check.input());
    }

    @Override
    public GuardrailDecision check(OutputGuardrailCheck check) {
        return evaluate(check.output());
    }

    private GuardrailDecision evaluate(String text) {
        List<String> found = new ArrayList<>();
        String redacted = text;
        for (Category category : CATEGORIES) {
            Matcher matcher = category.pattern().matcher(redacted);
            if (matcher.find()) {
                found.add(category.label());
                redacted = matcher.replaceAll("[REDACTED:" + category.label() + "]");
            }
        }
        if (found.isEmpty()) {
            return GuardrailDecision.pass();
        }
        if (mode == Mode.BLOCK) {
            return GuardrailDecision.block("content contains " + String.join(", ", found));
        }
        return GuardrailDecision.modify(redacted);
    }
}
