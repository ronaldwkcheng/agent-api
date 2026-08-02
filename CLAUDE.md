# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Run Commands

```bash
# Build
./gradlew build

# Run application (requires OPENAI_API_KEY env var)
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.ronald.agent.SomeTestClass"
```

The application requires the `openai_api_key` environment variable to be set. It starts on port 8080 using Java 21 virtual threads.

## Architecture

This project implements six **agentic workflow patterns** built on top of Spring AI and Spring Boot. The central abstractions are:

- **`AgenticWorkflow<T>`** — top-level interface: `T invoke(String input)`. All six patterns implement this.
- **`SubAgent<T>`** — executes a single LLM step given a `Map<String, String>` context; returns a typed result.
- **`RoutableSubAgent<T>`** — extends `SubAgent` with a `getRouteKey()` for use in conditional routing.

### The Six Workflow Patterns

| Pattern | Class | Description |
|---|---|---|
| Sequential | `SequentialAgentChain<T>` | Agents run one after another; each agent's output is added to a shared context map for downstream agents |
| Parallel | `ParallelAgentOrchestrator<T>` | Agents fan-out concurrently (via `Executor`), results fan-in to an aggregator agent |
| Conditional | `ConditionalAgentRouter<T>` | An LLM classifier picks a route key; the matching `RoutableSubAgent` handles the request |
| Iterative | `IterativeRefinementWorkflow` | Generate → Evaluate loop with a private `EvaluatorAgent`; exits on pass or max attempts |
| Plan & Execute | `PlanAndExecuteWorkflow<T>` | Planner decomposes input into steps; executor runs each step sequentially with accumulated context; synthesizer produces final typed output |
| ReAct | `ReActWorkflow` | Thought → Action → Observation loop; tools are registered with Spring AI's `@Tool` annotation |

### Agent Implementation Layer

- **`AbstractPromptSubAgent<T>`** — base class handling ChatClient invocation and prompt template rendering. Subclass and implement to define custom agent behavior.
- **`DefaultPromptSubAgent`** / **`DefaultPromptRoutableAgent`** — builder-configured, string-returning agents for cases where no custom subclass is needed.
- **`AbstractAgentBuilder<B, A>`** — shared builder base for system prompt + user prompt configuration.
- **`AgenticWorkflowAdvisor`** — Spring AI `BaseAdvisor` that runs a workflow pre-flight and augments the prompt before LLM call.

### Context Map Conventions

Workflows pass state through a `Map<String, String>`. Key names are workflow-specific but follow consistent conventions:
- `"input"` — always the original user input
- `"output"` — final agent output (SequentialAgentChain)
- `"plan"` — serialized step list (PlanAndExecuteWorkflow)
- `"scratchpad"` — accumulated thought/action/observation history (ReActWorkflow)
- `"criteria"`, `"content"`, `"feedback"` — refinement loop state (IterativeRefinementWorkflow)
- Custom agent output keys are set via `getOutputKey()` on each `SubAgent`

### Example Usages

Concrete end-to-end examples for all six patterns are in `src/main/java/com/ronald/agent/example/`. These show how to wire builders together and are the best reference when implementing new workflows.