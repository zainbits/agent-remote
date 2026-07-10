# AgentsRemote

Android frontend for remote coding agents, with independent **Grok** and **Codex** session tabs.

## Host (manual)

Not a boot service. In a terminal:

```zsh
grokserve
```

To print the current Grok secret without starting the server (for **Settings → Grok secret**):

```zsh
grokserve --show-token
```

To replace the persistent secret before starting both endpoints:

```zsh
grokserve --rotate-secret
```

The rotated value is used immediately and must also be updated in AgentRemote's Grok secret setting. If `GROK_AGENT_SECRET` is set, unset it before using `--rotate-secret` because an environment-provided credential cannot be rotated by the wrapper.

Defined in `~/.zshrc`. Starts:

1. **Session index** HTTP API on port **2420** (`host/session_index.py`) — lists old Grok sessions for the app home screen.
2. **Grok agent** WebSocket server on port **2419**:

```text
grok agent --always-approve serve --bind 0.0.0.0:2419 --secret <token>
```

- Secret persists in `~/.grok/agent-serve.secret` (or set `GROK_AGENT_SECRET`).
- `--show-token` prints the secret that would be used (env or secret file) and exits without starting servers.
- `--rotate-secret` securely replaces that file before the server starts.
- Override agent bind with `GROK_AGENT_BIND=0.0.0.0:2419`.
- Override session API port with `GROK_SESSION_INDEX_PORT=2420`.
- Ctrl+C stops both.

### Codex app-server

Run the checked-in foreground helper:

```zsh
host/codexserve
# or, if ~/.zshrc is loaded:
codexserve
```

It creates a mode-`600` bearer-token file when needed and starts Codex app-server on port **2430** using capability-token authentication. Copy the token into **Settings → Codex bearer token**:

```zsh
codexserve --show-token
```

Override the bind or token path with `CODEX_AGENT_BIND` and `CODEX_AGENT_TOKEN_FILE`.
Codex WebSocket transport is experimental. Use `wss://` or an authenticated encrypted tunnel when the connection leaves a trusted LAN/Tailnet; never expose an unauthenticated listener publicly.

### Project / working directory

The **host working directory (cwd)** is the project the agent uses for tools (files, shell, git). It is **not** the phone path.

- App default: host `$HOME` → `/home/user`
- Change in **Settings → Host project directory**
- Home screen shows the active cwd and only lists sessions for that directory
- Chat top bar also shows the cwd for the open session

Grok sessions live under `~/.grok/sessions/<encoded-cwd>/<session-id>/`. Codex threads use Codex's own persisted thread store and native `thread/list` / `thread/resume` APIs.

## Phone

### Install from GitHub release

On the build machine (no phone required):

```zsh
cd ~/AndroidStudioProjects/AgentRemote
androidrun --publish
```

Then open the release URL, download `AgentRemote-v…-release.apk` on the phone, and install (sideload; signed with the Android debug keystore).

Only the **newest 3** releases are kept; older ones (and their tags) are deleted automatically (`ANDROIDRUN_RELEASE_KEEP=3`, set `0` to keep all).
### Configure

1. Settings → configure the Grok URLs/secret and Codex URLs/bearer token, plus the shared host cwd.
2. Switch LAN / Tailnet with the chips.
3. Use the bottom bar to switch between the independent **Grok** and **Codex** session lists.
4. Start `grokserve` and/or `host/codexserve` for the selected tab.
5. Tap a session to resume it, or **+** for a new chat.

Session-card message totals count the user/assistant conversation shown by AgentRemote. Grok bootstrap context, synthetic instructions, reasoning/tool-loop records, and hidden slash-command turns are excluded.

Permissions for tools are approved on the **host** (`--always-approve`), not in the app.

### Chat controls

- While an agent is responding, use the **Stop** button or `/stop` (alias: `/cancel`) to cancel the current turn without dropping the connection.
- Type `/` in the composer to browse slash commands. The list combines AgentRemote actions with the commands advertised by the connected Grok agent; tap one to complete it, then send it.
- Slash-command results appear in a dismissible panel above the composer and are not added to the chat timeline.
- AgentsRemote handles `/new` (alias: `/clear`), `/home`, `/disconnect`, `/help`, `/context`, and `/usage` locally. `/context` and `/usage` show the latest model and context-window metadata reported by the selected agent. Grok also advertises its host slash commands dynamically.
- If the active LAN or Tailnet WebSocket drops, AgentRemote retries and reloads the same ACP session. Deliberate disconnects stay disconnected; use the link button in the header to reconnect without leaving the chat.

On a session screen, Android's predictive back gesture scales and fades the live session as the swipe progresses, revealing that agent's session list beneath it. Completing the gesture returns to the list; cancelling springs the session back into place.

## Ports

| Port | Role |
| --- | --- |
| 2419 | ACP WebSocket (`ws://host:2419/ws?server-key=…`) |
| 2420 | Session list HTTP (`http://host:2420/api/sessions?cwd=…`) |
| 2430 | Codex app-server WebSocket (`ws://host:2430`) |

The app derives the session API host/port from the agent base URL (agent port + 1).
