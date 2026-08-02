package com.ronald.agent.workflow;

/**
 * Core abstraction for agentic workflows in this application.
 * <p>
 * An {@code AgenticWorkflow} encapsulates one or more AI agents orchestrated to
 * transform a plain-text input into a typed result. Implementations may process
 * agents sequentially, in parallel, or in an iterative refinement loop.
 * </p>
 *
 * @param <T> the type of result produced by the workflow
 */
public interface AgenticWorkflow<T> {

    /**
     * Executes the workflow with the given input and returns the result.
     *
     * @param input the plain-text input to process; must not be null
     * @return the workflow result; never null
     */
    T invoke(String input);
}
