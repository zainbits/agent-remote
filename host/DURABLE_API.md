# Durable host API

The Android app uses this versioned HTTP API on the configured durable host URL. Every endpoint except health requires `Authorization: Bearer <server token>`.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/health` | Process readiness without exposing job data |
| `GET` | `/api/v1/sessions?backend=codex|grok&cwd=…&limit=50` | List and lazily adopt sessions |
| `POST` | `/api/v1/sessions` | Create a durable session from `{ backend, cwd }` |
| `GET` | `/api/v1/sessions/{id}` | Read one session, normalized messages, backend commands, and the latest event cursor |
| `GET` | `/api/v1/sessions/{id}/commands` | Refresh the backend-specific slash-command catalog |
| `GET` | `/api/v1/sessions/{id}/status` | Read live Codex account limits plus native thread configuration/context status |
| `POST` | `/api/v1/sessions/{id}/turns` | Queue `{ prompt }`; returns `202` immediately |
| `GET` | `/api/v1/sessions/{id}/events?after=N&wait=20` | Long-poll replayable events after cursor `N` |
| `POST` | `/api/v1/sessions/{id}/cancel` | Request cancellation of the active turn |

Request bodies are limited to 1 MiB. API errors use `{ "error": "…" }` with an appropriate HTTP status.

## State model

Session status is one of `idle`, `queued`, `running`, `cancelling`, `failed`, or `cancelled`. One session accepts one active turn at a time; distinct sessions execute concurrently up to `AGENTREMOTE_MAX_WORKERS`.

The server writes the user message and `turn.queued` event transactionally before acknowledging a new turn. Workers then persist `turn.started`, normalized message/tool/usage events, and exactly one terminal event:

- `turn.completed`
- `turn.failed`
- `turn.cancelled`

Clients first fetch the session snapshot and its `latestEventId`, then poll strictly after that cursor. Event IDs are durable SQLite row IDs, so reconnecting never depends on an in-memory stream.

Session snapshots and `usage.updated` events include the merged model name/ID, reasoning effort, used tokens, and context capacity when the backend reports them. Grok snapshots also include headless-compatible built-ins and installed skills. Informational slash reports are persisted as ordinary assistant output so they survive observer disconnects and replay.

Codex workers keep workspace-write filesystem isolation but explicitly enable command networking for both new and resumed turns. The Codex status endpoint reads current account/rate-limit snapshots through app-server and combines them with the thread rollout's last-token/context-window data; account details are returned transiently and are not persisted in the durable database.

The service wrapper also exports non-interactive toolchain paths for Linuxbrew, `~/.local/bin`, the local Temurin JDK, and Android SDK. This lets delegated Codex login shells find `gh` and `androidrun` without depending on interactive `~/.zshrc` startup.

## Storage

`schema.sql` is the tracked schema. SQLite uses WAL journaling, full synchronous commits, foreign keys, a 30-second busy timeout, and a mode-`600` database inside a mode-`700` data directory.

The database contains prompts and agent output and must not be committed. The bearer token is also runtime-only and mode `600`.
