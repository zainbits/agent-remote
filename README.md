# AgentRemote

Android frontend for durable, concurrent **Grok** and **Codex** jobs running on a Linux host.

AgentRemote is only the controller and live viewer. The checked-in host service owns agent processes, so pressing Back, starting another session, losing the phone connection, or quitting Android does not cancel active work. Reopen the app later to replay persisted progress and results.

## Architecture

- `host/agentremotesrv`: installs and manages the user-level host service.
- `host/durable_jobs/`: authenticated HTTP API, worker pool, CLI event normalization, and legacy-session adoption.
- SQLite in WAL mode stores durable sessions, turns, messages, status, usage, and replay events.
- Codex runs through documented `codex exec --json` sessions and resumes by Codex thread ID.
- Grok runs through `grok --output-format streaming-json` and resumes by Grok session ID.
- The Android client long-polls persisted events. Disconnecting only removes that observer.

All implementation, schema, tests, and service configuration live in this repository. Runtime databases, tokens, logs, and agent-owned files are deliberately not committed.

## Host setup

The service uses the already authenticated `codex` and `grok` CLIs. No `sudo`, Python package installation, or separate database server is required.

From the project directory:

```zsh
host/agentremotesrv install
host/agentremotesrv status
```

`install` creates the runtime token/database, symlinks the tracked systemd unit into the user service directory, and enables and starts it. The service listens on port `2440` by default and restarts automatically if the process exits.

Print the token for Android Settings:

```zsh
host/agentremotesrv --show-token
```

Useful management commands:

```zsh
host/agentremotesrv logs -f
host/agentremotesrv --rotate-token
host/agentremotesrv uninstall
```

Rotating the token restarts the installed service, so do it only when no turn is active. Update the token in Android Settings afterwards.

### Runtime state

Defaults:

| Path / value | Purpose |
| --- | --- |
| `~/.local/share/agentremote/jobs.sqlite3` | Durable job database |
| `~/.local/share/agentremote/server.token` | Mode-`600` bearer token |
| `0.0.0.0:2440` | HTTP bind |
| `4` | Concurrent agent workers; additional turns remain queued |

Host overrides:

- `AGENTREMOTE_BIND=HOST:PORT`
- `AGENTREMOTE_DATA_DIR=/path`
- `AGENTREMOTE_DATABASE=/path/jobs.sqlite3`
- `AGENTREMOTE_TOKEN_FILE=/path/token`
- `AGENTREMOTE_MAX_WORKERS=N`
- `AGENTREMOTE_CODEX_BIN=/path/to/codex`
- `AGENTREMOTE_GROK_BIN=/path/to/grok`
- `AGENTREMOTE_IMPORT_LEGACY=0` to disable discovery of pre-durable CLI sessions

Put persistent overrides in a systemd user-service override, then restart the service.

If the service must start at boot and continue after the Linux user logs out, enable user lingering once:

```zsh
loginctl enable-linger "$USER"
```

### Durability boundary

Active turns are independent of the phone and continue with no connected Android client. Completed and failed history remains in SQLite across service restarts.

If the Linux host or host service itself stops during a turn, that process cannot continue. On restart, AgentRemote marks the orphaned turn failed instead of pretending it completed; the session remains resumable with a new prompt.

New Codex sessions use unrestricted full-host access by default, equivalent to `codex exec --dangerously-bypass-approvals-and-sandbox`. The Android Settings switch can make new Codex sessions use `workspace-write` with networking and `approval_policy=never` instead. The selected mode is stored on each durable session and reused for every resumed turn. Grok runs with `--always-approve`, matching the previous host behavior.

## Phone configuration

In **Settings** configure:

1. Host LAN URL, normally `http://<LAN-host>:2440`.
2. Host Tailnet URL, normally `http://<Tailscale-host>:2440`.
3. The token printed by `host/agentremotesrv --show-token`.
4. The absolute Linux workspace path used by both agents.
5. Whether newly created Codex sessions receive full host access (enabled by default).

The LAN/Tailnet chips select the saved URL. Tailscale encrypts Tailnet traffic; use HTTPS or another trusted encrypted tunnel if exposing the API by another route. Never expose port `2440` directly to the public internet.

Existing Grok and Codex sessions are discovered and adopted automatically. Their histories are imported lazily when first opened, and later prompts resume the original backend session ID.

## Behavior

- Back/Home detaches from the session without cancelling its active turn.
- **New session** can start while other sessions remain queued or running.
- Session cards show `Queued`, `Running`, `Stopping`, `Failed`, or `Stopped` when applicable.
- Reopening a running session restores persisted history and resumes live observation.
- **Stop**, `/stop`, and `/cancel` explicitly cancel only the open session's active turn.
- `/new`, `/clear`, `/home`, and `/disconnect` detach without stopping host work.
- The composer shows the backend's effective model and reasoning effort.
- Grok built-ins and installed skills populate slash autocomplete; Codex exposes app-local commands only.
- Grok `/context`, `/usage`, and `/session-info` return durable live usage reports, while Codex renders those reports from its persisted usage state.
- Silent Grok outcomes such as `/compact` receive a persisted completion report instead of an empty panel.

Concurrent sessions may operate on the same workspace. Their agent contexts are isolated, but filesystem writes are not automatically placed in Git worktrees; avoid assigning conflicting edits to the same checkout simultaneously.

## Build and verification

Host tests:

```zsh
python3 -m unittest host/test_session_index.py host/test_durable_jobs.py -v
```

Android checks:

```zsh
./gradlew :app:assembleDebug :app:lintDebug
```

Publish a sideloadable GitHub APK release:

```zsh
androidrun --publish
```

Only the newest three releases are retained by default.

## Legacy direct helpers

`grokserve`, `host/codexserve`, the old WebSocket clients, and the Grok session-index API remain in the repository for compatibility and diagnostics. The Android v0.2 durable path does not require those foreground servers.

## Ports

| Port | Role |
| --- | --- |
| `2440` | Durable AgentRemote HTTP API (current Android path) |
| `2419` | Legacy Grok ACP WebSocket |
| `2420` | Legacy Grok session index |
| `2430` | Legacy Codex app-server WebSocket |
