package com.ronald.agent.subagent.guardrail;

/**
 * Which side of the wrapped agent a {@link Guardrail} is checking.
 */
public enum GuardrailStage {

    /** The text the wrapped agent is about to be given &mdash; one context entry, {@code input} by default. */
    INPUT,

    /** The text the wrapped agent produced. */
    OUTPUT
}
