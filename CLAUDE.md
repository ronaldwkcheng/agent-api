# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Run Commands

```bash
# Build — needs no API key and makes no model calls
./gradlew build

# Run application (requires openai_api_key env var)
./gradlew bootRun

# Run one of the workflow demos (issues real, billable model requests)
./gradlew bootRun --args='--agent.demo=react'

# Run all tests
./gradlew test

# Run a single test class (module-qualified)
./gradlew :api:test --tests "com.ronald.agent.workflow.SomeTestClass"
```

`bootRun` exists only in `:example`, so the unqualified task name resolves there.

## Module Layout

Two Gradle subprojects:

| Module | Contents | Build plugin |
|---|---|---|
| `:api` | `advisor`, `subagent`, `workflow` — the library | `java-library` |
| `:example` | `AgentApiApplication` + `example` package + `application.properties` | `org.springframework.boot` |

`:api` is a plain library — no Spring Boot plugin, so no `bootJar` and no `bootRun`. It declares
`api("org.springframework.ai:spring-ai-client-chat")` because its public signatures expose Spring
AI types, and it deliberately names **no model provider**: choosing one is the application's job.
`:example` adds `spring-ai-starter-model-openai` for the OpenAI autoconfiguration. Keep it that
way — provider dependencies do not belong in `:api`.

Plugin and BOM versions live in `gradle.properties` (`springBootVersion`, `springAiVersion`) and
are wired into the plugin ids through `pluginManagement` in `settings.gradle.kts`, so no version
literals belong in a module build file. There is no root `build.gradle.kts`.

This is a non-web Spring Boot app — `:example` depends on `spring-boot-starter`, not `-web`. There
is no servlet container, so `bootRun` starts the context, runs whichever demo `agent.demo` selects,
and exits. It uses Java 21 virtual threads and requires the `openai_api_key` environment variable.
Do not reintroduce `spring-boot-starter-web`; Spring AI pulls in `spring-web`/`spring-webflux`
transitively for its HTTP clients and needs nothing more.

Each workflow pattern has a demo `CommandLineRunner` in `AgentApiApplication`, registered only
when `agent.demo` selects it: `sequential`, `parallel`, `conditional`, `iterative`,
`plan-and-execute`, or `react`. Values are matched exactly. Without the property no runner is
registered, which is what keeps `./gradlew build` free and offline — `@SpringBootTest` calls
`SpringApplication.run()` and would otherwise execute a runner on every build.

Tests must never require an API key or reach the network. `AgentApiApplicationTests` (in
`:example`) overrides the key with a placeholder, and the `:api` workflow tests use stub
`SubAgent`s rather than a `ChatClient`.

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

Concrete end-to-end examples for all six patterns are in `example/src/main/java/com/ronald/agent/example/`. These show how to wire builders together and are the best reference when implementing new workflows.