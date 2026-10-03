# rpzAgent — standalone coding-agent 

A generic, opencode-like coding agent: sessions, a streaming tool loop
(read/write/edit/glob/grep/list/bash/ast), permission prompts, JGit checkpoints,
context compaction, an HTTP+SSE API and a small opencode-like web UI.

## Implemented (namespace `com.rpzsoft.agent.*`)

| Package | Contents |
|---|---|
| `common` | `Json`, `ConfigRegistry`, `IdGen`, `DT`, `Buffer`, `Sanitizer`; ports `EventSink`, `SecretMasker`, `UsageSink`; `EventBus` (in-process fan-out) and `LoggingUsageSink` |
| `ai` | model clients `ModelClient`/`OpenAiModelClient`/`MockModelClient`/`FailoverModelClient`, `ModelStream`, `ModelException`; `Tool`, `ToolContext`, `ToolRegistry`, `ToolLimits`, `ToolOutput`, `AgentSession`, `PermissionGate`; `ContextBuilder`, `PermissionEngine`, `ValidationService`, `AgentRunner` |
| `ai.tools` | `read`, `write`, `edit`, `ast_edit`, `list_dir`, `glob`, `grep`, `bash`; `DefaultTools` installer, `FileWalker` |
| `sandbox` | `SandboxProvider`, `Sandbox`, `ExecResult`, `ProcessSandboxProvider`, `SandboxManager` (per-session workspace binding) |
| `workspace` | `FileWorkspace` (+ `Local` impl), `WorkspaceProvider` (+ `Local`), `CheckpointService` (JGit) |
| `session` | `SessionStore` (+ `FileStore` impl) |
| `http` | `AgentHttpServer` — Netty HTTP/1.1 + SSE, JSON via `common.Json`, optional bearer token, static UI serving (`/` from classpath `ui/` or `rapps.agent.ui.dir`); plus the legacy HTTP client classes pulled in by `Json` (`HttpClient`, `HttpMethod`, …) |
| `cli` | `AgentServer` main (config -> start HTTP) |
| `ui` | dependency-free vanilla-JS coding-agent front end (`index.html`, `css/app.css`, `js/app.js`) served at `/` |

## Build, test, package, run

```bash
cd /work
mvn -q -DskipTests compile          # build gate
mvn test                            # 18 tests (JUnit 4)
mvn -q -DskipTests package          # -> target/agent.jar (shaded, runnable)
java -jar target/agent.jar --host 10.1.1.10 --port 5011 --workspace . --data data
# open http://10.1.1.10:5011/ for the bundled web UI
```

CLI flags: `--host`, `--port`, `--workspace`, `--data`, `--config <file>`,
`--help`. Configuration is classpath `app.properties` (see
`src/main/resources/app.properties`) overridden by `--config`; keys use the
`rapps.agent.*` prefix. The UI in `ui/` is copied into the jar at build time
(classpath `/ui`); set `rapps.agent.ui.dir=ui` to serve the source directory
directly during UI development (no rebuild needed).

| Key | Default | Meaning |
|---|---|---|
| `rapps.agent.http.host` / `port` | `127.0.0.1` / `5011` | bind address |
| `rapps.agent.http.token` | empty | optional bearer token (off for local use) |
| `rapps.agent.ui.dir` | empty | serve UI files from this directory instead of classpath `/ui` |
| `rapps.agent.workspace` | `.` | base directory; sessions may use a subdirectory |
| `rapps.agent.data` | `data` | session store directory |
| `rapps.agent.maxTurns` | `24` | model turns per run |
| `rapps.agent.model.provider` | `mock` | `mock` or any OpenAI-compatible endpoint |
| `rapps.agent.model.baseUrl` / `apiKey` / `name` / `maxTokens` | — | provider settings |
| `rapps.agent.model.retries` / `retry.backoffMs` | `2` / `1000` | transient-failure retries |
| `rapps.agent.permissions.<tool>` | `bash=ask`, rest `allow` | `allow`/`ask`/`deny` |
| `rapps.agent.permissions.timeoutMs` | `120000` | permission wait before auto-deny |
| `rapps.agent.tools.*`, `rapps.agent.history.toolBytes` | see file | output truncation limits |

## HTTP API

Base `http://127.0.0.1:5011`.

| Method | Path | Meaning |
|---|---|---|
| GET | `/` | bundled web UI (`index.html`); `/css/*`, `/js/*`, `/favicon.svg` are served unauthenticated |
| GET | `/app` | agent info: version, cwd, model, tools |
| GET | `/config` | effective `rapps.agent.*` config |
| GET | `/health` | liveness |
| GET | `/session` | list sessions |
| POST | `/session` | create session `{title?, directory?}` |
| GET / DELETE | `/session/{id}` | metadata / delete |
| GET | `/session/{id}/message` | history |
| POST | `/session/{id}/message` | prompt `{text, model?, agent?, userId?}` -> `202` |
| POST | `/session/{id}/abort` | cancel the running turn |
| POST | `/session/{id}/compact` | fold older history into a summary |
| POST | `/session/{id}/revert` | `{messageId}` revert history + workspace |
| GET | `/session/{id}/permission` | pending permission requests |
| POST | `/session/{id}/permission/{pid}` | `{allow: bool}` |
| GET | `/event` | SSE: `message.start/delta/end`, `tool.start/end`, `permission.request`, `error`, `session.*` |

```bash
curl localhost:5011/health
curl -N localhost:5011/event &            # SSE
curl -X POST localhost:5011/session -d '{"title":"demo"}'
curl -X POST localhost:5011/session/<id>/message -d '{"text":"list the files"}'
```

## Web UI

`ui/` is a dependency-free vanilla-JS front end (opencode-like) served by the
same Netty server:

- sidebar session list with create/delete/select;
- streaming assistant output over `/event` (SSE), live tool cards with arguments
  and results;
- permission modal for `ask` tools (Allow / Deny);
- composer with Enter-to-send, Stop / Compact / revert-to-message;
- settings dialog for the optional bearer token (stored in `localStorage`).

It is copied into `target/classes/ui` at build time and included in the shaded
jar, so `java -jar target/agent.jar` serves it at `http://127.0.0.1:5011/`.
Static assets are public; everything under `/session`, `/app`, `/config` and
`/event` stays behind the bearer token when one is configured.

## Embed as a library

```java
File root = new File("/path/to/project");
SessionStore store = new SessionStore.FileStore(new File("data/sessions"));
EventBus events = new EventBus();
SandboxManager sandboxes = new SandboxManager(
        new ProcessSandboxProvider(), new FileWorkspace.Local(root), events, 600000);
AgentRunner runner = new AgentRunner(
        store, new WorkspaceProvider.Local(root), sandboxes, events,
        new CheckpointService(), DefaultTools.registry(), new PermissionEngine(),
        new PermissionGate(), modelClient, SecretMasker.NOOP, new LoggingUsageSink());

Json session = store.create(root.getAbsolutePath(), "demo");
runner.submit(session.getString("id"), "local-user");
```

Everything the core needs is a port: implement `SessionStore`, `EventSink`,
`SandboxProvider`, `SecretMasker`, `UsageSink` or supply your own
`FileWorkspace`. The core never imports `web`, `store`, `billing`, `quota`,
`audit`, `secrets`, or `Server`.

## Adding a tool

Implement `com.rpzsoft.agent.ai.Tool` (`name`/`description`/`parameters`/`execute`)
and register it in `com.rpzsoft.agent.ai.tools.DefaultTools` (or your own registry).
Tools must use `ToolContext` only (workspace, sandboxes, events, masker) — never
import host-application classes. Permission defaults come from
`rapps.agent.permissions.<tool>` (unknown tools default to `allow`).

## Notes

- `server/` remains untouched and independently green.
- Store defaults to the JSON `FileSessionStore`; checkpoints use JGit (no git
  binary). Data lives under `rapps.agent.data`.
