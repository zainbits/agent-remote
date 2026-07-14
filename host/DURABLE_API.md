# Durable host API

The Android app uses this versioned HTTP API on the configured durable host URL. Every endpoint except health requires `Authorization: Bearer <server token>`.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/health` | Process readiness without exposing job data |
| `GET` | `/api/v1/models?backend=codex\|grok` | Read the backend's selectable model catalog |
| `GET` | `/api/v1/sessions?backend=codex|grok&cwd=…&limit=50` | List and lazily adopt sessions |
| `POST` | `/api/v1/sessions` | Create a durable session from `{ backend, cwd, codexFullAccess? }` |
| `GET` | `/api/v1/sessions/{id}` | Read one session, normalized messages, backend commands, and the latest event cursor |
| `PATCH` | `/api/v1/sessions/{id}` | Update one or more of `{ title, pinned, unread }` |
| `DELETE` | `/api/v1/sessions/{id}` | Permanently delete an idle durable session and its linked Grok/Codex CLI history |
| `GET` | `/api/v1/sessions/{id}/commands` | Refresh the backend-specific slash-command catalog |
| `GET` | `/api/v1/sessions/{id}/status` | Read live Codex account limits plus native thread configuration/context status |
| `POST` | `/api/v1/sessions/{id}/attachments` | Stream one image body with `Content-Type` and URL-encoded `X-File-Name`; returns durable attachment metadata |
| `POST` | `/api/v1/sessions/{id}/turns` | Queue `{ prompt, attachmentIds? }`; returns `202` immediately |
| `POST` | `/api/v1/sessions/{id}/model` | Select `{ modelId }` for future turns in an idle session |
| `GET` | `/api/v1/sessions/{id}/events?after=N&wait=20` | Long-poll replayable events after cursor `N` |
| `POST` | `/api/v1/sessions/{id}/cancel` | Request cancellation of the active turn |

Request bodies are limited to 1 MiB. API errors use `{ "error": "…" }` with an appropriate HTTP status.

## State model

Session status is one of `idle`, `queued`, `running`, `cancelling`, `failed`, or `cancelled`. One session accepts one active turn at a time; distinct sessions execute concurrently up to `AGENTREMOTE_MAX_WORKERS`.

Session deletion is permanent and is rejected while a turn is queued, running, or cancelling. For a linked session, the host first invokes the backend's official permanent-delete command (`grok sessions delete` or `codex delete --force`); only after that succeeds does SQLite cascade-delete the durable turns, messages, events, and attachment metadata. Stored attachment files are then removed. An unlinked new-session placeholder has only its durable row removed.

Session summaries include durable `pinned` and `unread` booleans. Lists place pinned sessions first, then order each group by most recent activity. A terminal turn marks its session unread; starting a later turn or an explicit `{ "unread": false }` update clears it. Manual title updates are protected from the automatic first-prompt title generation.

The server writes the user message and `turn.queued` event transactionally before acknowledging a new turn. Workers then persist `turn.started`, normalized message/tool/usage events, and exactly one terminal event:

- `turn.completed`
- `turn.failed`
- `turn.cancelled`

Up to four PNG, JPEG, GIF, or WebP images (20 MiB each) can be uploaded before a turn. Uploads are private mode-`600` files under the durable data directory, become owned by the turn transactionally, and are included in message replay metadata. Unsent uploads expire after one day. Codex receives each file through its native repeated `--image` option; Grok receives ACP resource-link content blocks.

Clients first fetch the session snapshot and its `latestEventId`, then poll strictly after that cursor. Event IDs are durable SQLite row IDs, so reconnecting never depends on an in-memory stream.

Session snapshots and `usage.updated` events include the merged model name/ID, reasoning effort, used tokens, and context capacity when the backend reports them. Grok snapshots also include headless-compatible built-ins and installed skills. Informational slash reports are persisted as ordinary assistant output so they survive observer disconnects and replay.

Model selection is session-scoped and stored as `modelOverride`. The host validates choices against Grok's ACP model state or Codex's documented `codex debug models` catalog, rejects changes while a turn is active, and passes the override to every new or resumed backend invocation. When the selected model does not support the session's prior reasoning effort, the host uses that model's advertised default effort.

`codexFullAccess` defaults to `true` and is persisted with the session. Full-access Codex sessions pass `--dangerously-bypass-approvals-and-sandbox` on both new and resumed turns. When it is `false`, workers use `workspace-write`, `approval_policy=never`, and explicit command networking instead. The Codex status endpoint reports the stored worker policy, reads current account/rate-limit snapshots through app-server, and combines them with the thread rollout's last-token/context-window data; account details are returned transiently and are not persisted in the durable database.

The service wrapper also exports non-interactive toolchain paths for Linuxbrew, `~/.local/bin`, the local Temurin JDK, and Android SDK. This lets delegated Codex login shells find `gh` and `androidrun` without depending on interactive `~/.zshrc` startup.

## Storage

`schema.sql` is the tracked schema (version 7). SQLite uses one process-wide serialized connection with WAL journaling, full synchronous commits, foreign keys, a 30-second busy timeout, a 1000-page automatic checkpoint, and an 8-MiB journal size limit. The database is mode `600` inside a mode-`700` data directory.

Legacy-session discovery is single-flight and cached for 30 seconds per backend, workspace, and list limit. `AGENTREMOTE_LEGACY_SYNC_TTL_SECONDS` can tune that interval; failed discovery is retried after at most five seconds.

The database contains prompts and agent output and must not be committed. The bearer token is also runtime-only and mode `600`.
