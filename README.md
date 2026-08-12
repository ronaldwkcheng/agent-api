# agent-api

Six agentic workflow patterns built on Spring AI and Spring Boot — sequential, parallel,
conditional, iterative, plan-and-execute, and ReAct. Each pattern ships with a runnable demo
under `example/src/main/java/com/ronald/agent/example/`.

## Modules

| Module     | What it is                                                                  |
|------------|-----------------------------------------------------------------------------|
| `:api`     | The library — `advisor`, `subagent`, `workflow`. No model provider, no `main`. |
| `:example` | Runnable demos of each pattern plus the Spring Boot application that runs them. |

`:api` is provider-agnostic: it compiles against `spring-ai-client-chat` only. `:example` depends
on `project(":api")` and supplies the provider — `spring-ai-starter-model-openai` — so swapping in
a different Spring AI model provider is a change to `:example` alone.

## Architecture guides

This README shows how to *run* each demo. For how each pattern *works* and how to build your own,
see the library guides — each has UML class and sequence diagrams, the context keys in scope for
every agent slot, an implementation walkthrough, and the failure modes:

* **[`api/README.md`](api/README.md)** — the core abstractions, prompt rendering, context
  conventions, exhaustion semantics, and the advisor
* [Sequential chain](api/docs/sequential-agent-chain.md) ·
  [Parallel orchestrator](api/docs/parallel-agent-orchestrator.md) ·
  [Conditional router](api/docs/conditional-agent-router.md) ·
  [Iterative refinement](api/docs/iterative-refinement-workflow.md) ·
  [Plan & execute](api/docs/plan-and-execute-workflow.md) ·
  [ReAct](api/docs/react-workflow.md)

## Prerequisites

* **Java 21** (the build uses a toolchain, so Gradle will fetch it if needed)
* **An OpenAI API key**, exported as `openai_api_key`

```bash
# bash / Git Bash
export openai_api_key=sk-...
```

```powershell
# PowerShell
$env:openai_api_key = "sk-..."
```

> **The demos make real, billable OpenAI calls.** Several are multi-step — `plan-and-execute`
> runs up to 6 chained LLM calls, `iterative` up to 7 refine+evaluate rounds, and `react` up to
> 8 reasoning steps. Build and test, by contrast, need no key and make no network calls:
> `./gradlew build` is free and offline.

## Running a demo

Each pattern's demo is a `CommandLineRunner` in `AgentApiApplication`, registered only when the
`agent.demo` property selects it:

```bash
./gradlew bootRun --args='--agent.demo=<pattern>'
```

`bootRun` lives in `:example` — it is the only module with the Spring Boot plugin — so the
unqualified task name resolves there.

| `agent.demo`       | Pattern                        | Workflow class                |
|--------------------|--------------------------------|-------------------------------|
| `sequential`       | Sequential chain               | `SequentialAgentChain`        |
| `parallel`         | Parallel fan-out / fan-in      | `ParallelAgentOrchestrator`   |
| `conditional`      | LLM-classified routing         | `ConditionalAgentRouter`      |
| `iterative`        | Generate → evaluate loop       | `IterativeRefinementWorkflow` |
| `plan-and-execute` | Plan → execute → synthesize    | `PlanAndExecuteWorkflow`      |
| `react`            | Thought → action → observation | `ReActWorkflow`               |

Values are matched exactly — Spring's relaxed binding does not apply to `@ConditionalOnProperty`
values, so `plan-and-execute` will not match `planAndExecute`.

Without the property no demo is registered, so the app boots and exits immediately — there is no
web server, only the Spring context (`spring-boot-starter`, not `-web`). Results are
printed to stdout; the demo inputs are hardcoded in `AgentApiApplication`, so edit them there to
try your own.

---

### `sequential` — Sequential chain

```bash
./gradlew bootRun --args='--agent.demo=sequential'
```

Runs a customer support request through three ordered agents, each adding its output to a shared
context the next agent can read: **summarise** (`summary`) → **categorise** into Billing/Tech/Account
(`category`) → **draft a reply**, which reads both.

Input: *"I was charged twice for my subscription this month and I need a refund!"*
Output: a friendly support reply, routed as Billing. **3 LLM calls.**

### `parallel` — Parallel fan-out / fan-in

```bash
./gradlew bootRun --args='--agent.demo=parallel'
```

Three specialists analyse the same text concurrently on virtual threads — **sentiment**, **safety**,
and **French translation** — then an aggregator synthesises their reports into one executive summary.
Each branch is bounded by a 60-second timeout; see `ParallelAgentOrchestrator.BranchFailurePolicy`
for what happens when one fails.

Input: *"I love the new features in your product, but sometimes it crashes unexpectedly…"*
Output: a bullet-point analytics report. **4 LLM calls** (3 concurrent + 1 aggregation).

### `conditional` — LLM-classified routing

```bash
./gradlew bootRun --args='--agent.demo=conditional'
```

An LLM classifier picks a route key, and the matching specialist handles the request: **BILLING**,
**TECH**, or **ACCOUNT**. Unrecognised categories fall through to `SimpleRouteFallbackAgent`, which
needs no LLM call at all.

This demo sends three inputs to show all paths — a double-charge (BILLING), an upload crash (TECH),
and *"What are your business hours?"* (fallback). **5 LLM calls** — one classification per input, plus
one handler for each that matched a route; the fallback needs none.

### `iterative` — Generate → evaluate loop

```bash
./gradlew bootRun --args='--agent.demo=iterative'
```

Generates a children's story, then loops: a refiner improves the draft and a private `EvaluatorAgent`
scores it against the criteria, returning `PASS`, `NEEDS_IMPROVEMENT`, or `FAIL` with feedback that
seeds the next round. Exits early on `PASS`, otherwise after `maxAttempts` (7 here).

Criteria include a turtle protagonist, a moral lesson, and a sub-250-word limit.
Output: the story that passed, or the best draft after 7 attempts. **Up to 15 LLM calls.**

### `plan-and-execute` — Plan → execute → synthesize

```bash
./gradlew bootRun --args='--agent.demo=plan-and-execute'
```

A planner decomposes the task into steps, a research specialist executes each one in sequence with
the previous results as context, and a synthesizer produces the final report.

Input: *"The impact of artificial intelligence on the future of software engineering"*
Output: a structured research report. **Up to 8 LLM calls** (1 plan + up to 6 steps + 1 synthesis).

### `react` — Thought → action → observation

```bash
./gradlew bootRun --args='--agent.demo=react'
```

The agent reasons about what to do, calls a tool, observes the result, and repeats until it can
answer — up to 8 steps. Three tools are registered from `@Tool`-annotated methods on
`ReActWorkflowExample`: `wordCount`, `unitConverter`, and `currentDate`.

The question deliberately requires all three: counting the words in a Gettysburg Address excerpt,
converting 37 °C to Fahrenheit, and reporting today's date. Watch the scratchpad build up across
iterations at `DEBUG`. **Up to 8 LLM calls**, plus local (free) tool invocations.

---

## Build and test

```bash
./gradlew build                                              # both modules; no key, no network
./gradlew :api:test --tests "com.ronald.agent.workflow.*"    # a subset
```

Qualify `--tests` filters with the module, as above. An unqualified filter runs against *every*
module's `test` task, and Gradle fails the ones it matches nothing in — the workflow tests all
live in `:api`, so filtering for them unqualified passes `:api:test` and then fails the build on
`:example:test`:

```
> Task :api:test
> Task :example:test FAILED

* What went wrong:
Execution failed for task ':example:test'.
> No tests found for given includes: [com.ronald.agent.workflow.*](--tests filter)
```

Tests use stub `SubAgent`s rather than a real `ChatClient`, and `AgentApiApplicationTests`
overrides the API key with a placeholder — so the build never contacts a model provider.

## Reference documentation

* [Spring AI — OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
* [Spring Boot Gradle Plugin](https://docs.spring.io/spring-boot/3.5.11/gradle-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/3.5.11/gradle-plugin/packaging-oci-image.html)
* [Official Gradle documentation](https://docs.gradle.org)
