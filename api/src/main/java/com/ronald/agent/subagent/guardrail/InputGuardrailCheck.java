package com.ronald.agent.subagent.guardrail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Everything an {@link InputGuardrail} is shown about the content about to reach a delegate.
 *
 * @param stepName a human-readable label for the step being guarded
 * @param input    the text under check &mdash; the {@code "input"} key as it stands after any
 *                 earlier input guardrail's {@link GuardrailDecision.Modify}; never null
 * @param context  the context the delegate is about to be executed with, read-only
 */
public record InputGuardrailCheck(String stepName, String input, Map<String, String> context) {

    public InputGuardrailCheck {
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(input, "input must not be null");
        context = Collections.unmodifiableMap(new HashMap<>(context));
    }
}
