package com.ronald.agent.subagent.guardrail;

/**
 * Which side of a {@link GuardrailedSubAgent} a {@link GuardrailViolationException} came from.
 */
public enum GuardrailStage {

    /** The check ran on the delegate's input, before it executed. */
    INPUT,

    /** The check ran on the delegate's output, after it executed. */
    OUTPUT
}
