package com.ronald.agent.subagent.guardrail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a {@link Guardrail} is shown about one piece of text.
 *
 * @param stepName a human-readable label for the guarded step
 * @param stage    whether {@code content} is the wrapped agent's input or its output
 * @param content  the text to check; never null. For the input stage, earlier input guardrails'
 *                 rewrites are already applied; likewise for the output stage.
 * @param context  the context the wrapped agent runs with, read-only &mdash; includes the
 *                 original {@code input}, so an output check can judge the answer against the request
 * @param attempt  1 for the first output, incremented on each retry; always 1 for the input stage
 */
public record GuardrailRequest(String stepName, GuardrailStage stage, String content,
                               Map<String, String> context, int attempt) {

    public GuardrailRequest {
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(content, "content must not be null");
        context = Collections.unmodifiableMap(new HashMap<>(context));
    }
}
