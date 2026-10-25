package com.ronald.agent.subagent.guardrail;

/**
 * Thrown when a {@link Guardrail} blocks the input to, or the output of, a guarded step.
 *
 * <p>Unchecked, and deliberately not caught by any workflow: a block means the run must not
 * continue, so it propagates out of {@code invoke} and no later step executes. The offending text
 * is preserved on {@link #getContent()} &mdash; mind that it may be exactly what the guardrail
 * exists to keep out of logs.</p>
 */
public class GuardrailViolationException extends RuntimeException {

    private final String stepName;
    private final String guardrailName;
    private final GuardrailStage stage;
    private final String reason;
    private final transient String content;

    /**
     * @param stepName      the label of the guarded step
     * @param guardrailName the name of the guardrail that blocked
     * @param stage         whether the input or the output was blocked
     * @param reason        the guardrail's stated reason
     * @param content       the text that was blocked
     */
    public GuardrailViolationException(String stepName, String guardrailName, GuardrailStage stage,
                                       String reason, String content) {
        super("Guardrail '" + guardrailName + "' blocked the " + stage.name().toLowerCase()
                + " of step '" + stepName + "': " + reason);
        this.stepName = stepName;
        this.guardrailName = guardrailName;
        this.stage = stage;
        this.reason = reason;
        this.content = content;
    }

    /**
     * @return the label of the guarded step
     */
    public String getStepName() {
        return stepName;
    }

    /**
     * @return the name of the guardrail that blocked
     */
    public String getGuardrailName() {
        return guardrailName;
    }

    /**
     * @return whether the input or the output was blocked
     */
    public GuardrailStage getStage() {
        return stage;
    }

    /**
     * @return the guardrail's stated reason
     */
    public String getReason() {
        return reason;
    }

    /**
     * @return the text that was blocked
     */
    public String getContent() {
        return content;
    }
}
