# Standalone coding-agent (`agent`) — plan

Goal: a reusable, opencode-like coding agent with its own HTTP API.
**Constraint: `server` is not modified.** Generic code is *copied* from
`server/src/main/java` into `agent` and decoupled from product concerns.
(Consequence: temporary duplication; later we can flip `server` to depend on
`agent` and delete the originals.)

> **Status correction (supersedes the "Maven layout" section):** the
> `server` parent / `server/app` module arrangement is gone. `server` is a
> standalone jar again and `agent` is an independent standalone Maven project
> that must not touch `server`. Also, `ContextBuilder`/`AgentRunner` cannot be
> `sed`-copied (they depend on `ai/skills`, `WorkspaceManager`, `Store`,
> quota/billing/audit) — re-implement them against the ports. For the current
> file list and next steps, see `README.md`.

## Namespace

Standalone project → clean root package `com.rpzsoft.agent.*`:

```
com.rpzsoft.agent.common     Json, ConfigRegistry, IdGen, Buffer/Context/Platform, ports
com.rpzsoft.agent.model      ModelClient, OpenAiModelClient, MockModelClient,
                             FailoverModelClient, ModelStream, ModelException
com.rpzsoft.agent.core       AgentRunner, AgentSession, ContextBuilder, ToolRegistry,
                             ToolContext, ToolLimits/ToolOutput, ValidationService, PermissionEngine/Gate
com.rpzsoft.agent.tools      read, write, edit, ast_edit, glob, grep, list_dir, bash
                             (+ FileWalker)
com.rpzsoft.agent.sandbox    SandboxProvider, Sandbox, ExecResult, SandboxManager, ProcessSandboxProvider
com.rpzsoft.agent.workspace  CheckpointService
com.rpzsoft.agent.http       AgentHttpServer + handlers (opencode-style serve)
com.rpzsoft.agent.cli        AgentServer main (config → start HTTP)
```

## Ports (interfaces) so the core has no product dependencies

| Port | Purpose | Default impl in `agent` | `server` equivalent (unchanged) |
|---|---|---|---|
| `EventSink` | publish agent events | in-process `EventBus` (fan-out to SSE clients) | `web.SseHub` |
| `SessionStore` | sessions/messages/history | `FileSessionStore` (JSON under data dir) | `store.Store` |
| `FileWorkspace` | dir, resolve, read/write, tree, deny-list | `LocalWorkspace` | `WorkspaceManager` |
| `SandboxProvider` | run commands / serve app | `ProcessSandboxProvider` | process/docker/firecracker |
| `SecretMasker` | mask secrets in output | `NoopMasker` | `SecretService.mask` |
| `UsageSink` | model/tool usage + cost hooks | `NoopUsageSink` (+ `LoggingUsageSink`) | quota/usage/billing/audit |
| `AgentConfig` | typed settings | `ConfigRegistry` (properties) | same keys (`rapps.agent.*`) |

The core never imports `web`, `store`, `billing`, `quota`, `audit`, `secrets`,
`auth`, `apps`, `git`, `hosting`, `preview`, or `Server`.

## Copy map (source → target package)

- `ai/AgentRunner.java`, `AgentSession.java`, `ContextBuilder.java`, `ToolRegistry.java`,
  `ToolContext.java`, `ToolLimits.java`, `ToolOutput.java`, `ValidationService.java`,
  `PermissionEngine.java`, `PermissionGate.java` → `core/` (adapt: ports instead of concrete deps)
- `ai/ModelClient.java`, `OpenAiModelClient.java`, `MockModelClient.java`,
  `FailoverModelClient.java`, `ModelStream.java`, `ModelException.java` → `model/` (as-is)
- `ai/tools/*.java` → `tools/` (adapt `ToolContext` API only)
- `common/{Json,ConfigRegistry,IdGen,Buffer,Context,Platform}.java` → `common/` (as-is)
- `sandbox/{SandboxProvider,Sandbox,ExecResult,SandboxManager,ProcessSandboxProvider}.java` → `sandbox/`
  (interface + process only; Docker/Firecracker are pluggable and stay out)
- `workspace/CheckpointService.java` → `workspace/` (as-is; JGit)
- `common/Sanitizer` logic → `SecretMasker` default impl (path scrubbing is host-specific)

Dropped/split out (not copied): `Store`/`JdbcStore`, `Templates`, `ProjectLayout`,
`Assets`, `RemixService`, `secrets`, `audit`, `metrics`, `quota`, `usage`, `billing`,
`web`, `auth`, `apps`, `git`, `hosting`, `preview`.

## HTTP API (opencode-like `serve`)

Netty HTTP/1.1 + SSE; JSON via `common.Json`. Base `http://127.0.0.1:4096`.

| Method | Path | Meaning |
|---|---|---|
| GET | `/app` | agent/app info: version, cwd, model, tools |
| GET | `/config` | effective config (keys, model, limits) |
| GET | `/session` | list sessions |
| POST | `/session` | create session `{title?, directory?}` |
| GET | `/session/{id}` | session metadata |
| DELETE | `/session/{id}` | delete session |
| GET | `/session/{id}/message` | history |
| POST | `/session/{id}/message` | prompt `{text, model?, agent?}` → `202 accepted` |
| POST | `/session/{id}/abort` | cancel the running turn |
| POST | `/session/{id}/compact` | fold older history into a summary |
| POST | `/session/{id}/revert` | revert to a user message |
| GET | `/session/{id}/permission` | pending permission requests |
| POST | `/session/{id}/permission/{pid}` | `{allow: bool}` |
| GET | `/event` | SSE stream: `message.start/delta/end`, `tool.start/end`, `permission.request`, `error`, `session.*` |
| GET | `/health` | liveness |

Auth: optional bearer token (`rapps.agent.http.token`), off by default for local use.
Config via `app.properties` on the classpath or `--config <file>`; keys keep the
`rapps.agent.*` shape for familiarity.

## Build phases (each compiles/tests standalone)

1. **Scaffold**: `pom.xml` (Java 8, Netty, JGit, slf4j/logback 1.3.x, junit), package tree, `com.rpzsoft.agent.cli.AgentServer` main that starts `:4096`.
2. **common + ports**: copy `common`, define all port interfaces + default impls (local workspace, file session store, in-process event bus, noop masker/usage).
3. **model**: copy model clients; unit-test the SSE parser.
4. **sandbox + checkpoints**: copy interface/process provider and `CheckpointService`.
5. **core + tools**: copy and adapt `AgentRunner`/`ContextBuilder`/`ToolRegistry` to ports; copy tools; wire `ToolContext` to ports.
6. **HTTP**: `AgentHttpServer` + handlers + SSE `/event`; session/permission/abort/compact/revert routes.
7. **Tests**: port the standalone-testable agent tests (context builder, tools, permissions, model parsing, checkpoint); add HTTP route tests with Netty `EmbeddedChannel`.
8. **Docs**: `README.md` (embed as a library: `AgentRunner` + ports; run as a service: `java -jar agent.jar`; HTTP examples).

## Verify

```
cd agent
JAVA_HOME=$HOME/.jdk/jdk8u504-b01 mvn -q test
JAVA_HOME=$HOME/.jdk/jdk8u504-b01 mvn -q -DskipTests package
java -jar target/agent-1.0-SNAPSHOT-shaded.jar --port 4096   # or java -cp
curl localhost:4096/health
curl -N localhost:4096/event &            # SSE
curl -X POST localhost:4096/session -d '{"title":"demo"}'
curl -X POST localhost:4096/session/<id>/message -d '{"text":"list the files"}'
```

`server/` remains untouched and independently green.

## Decisions (defaults chosen)

1. Namespace: `com.rpzsoft.agent.*` (standalone identity).
2. Sandbox: interface + process provider inside `agent`; Docker/Firecracker are server plugins.
3. Persistence: agent ships a simple `FileSessionStore`; consumers can implement `SessionStore`.
4. Checkpoints: generic `CheckpointService` (JGit) inside `agent`.
5. HTTP port default `4096`, SSE at `/event`, optional bearer token.
6. Packaging: shade plugin for a runnable fat jar (`agent.jar`).
