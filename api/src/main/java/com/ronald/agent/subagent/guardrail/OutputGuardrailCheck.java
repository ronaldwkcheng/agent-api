package com.ronald.agent.subagent.guardrail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Everything an {@link OutputGuardrail} is shown about one proposed output.
 *
 * @param stepName a human-readable label for the step being guarded
 * @param output   the delegate's output under check &mdash; as it stands after any earlier output
 *                 guardrail's {@link GuardrailDecision.Modify} in this same pass; never null
 * @param context  the context the delegate was executed with, read-only &mdash; includes the
 *                 original {@code input}, so a check can judge the output against the request
 * @param attempt  1 for the delegate's first output, incremented on each {@link GuardrailDecision.Retry}
 */
public record OutputGuardrailCheck(String stepName, String output, Map<String, String> context, int attempt) {

    public OutputGuardrailCheck {
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(output, "output must not be null");
        context = Collections.unmodifiableMap(new HashMap<>(context));
    }
}
