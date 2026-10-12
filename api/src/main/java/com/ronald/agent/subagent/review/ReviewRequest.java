package com.ronald.agent.subagent.review;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a {@link HumanReviewer} is shown about one proposed output.
 *
 * @param stepName  a human-readable label for the step under review
 * @param proposal  the output the wrapped agent produced; never null
 * @param context   the context the wrapped agent was executed with, read-only &mdash; includes the
 *                  original {@code input}, so a reviewer can judge the proposal against the request
 * @param attempt   1 for the first proposal, incremented on each revision
 */
public record ReviewRequest(String stepName, String proposal, Map<String, String> context, int attempt) {

    public ReviewRequest {
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        context = Collections.unmodifiableMap(new HashMap<>(context));
    }
}
