# AgentRemote

Android frontend for remote coding agents. **v0.1** talks to **Grok Build** over ACP WebSocket. Codex is reserved for a later backend.

## Host (manual)

Not a boot service. In a terminal:

```zsh
grokserve
```

Defined in `~/.zshrc`. Starts:

1. **Session index** HTTP API on port **2420** (`host/session_index.py`) — lists old Grok sessions for the app home screen.
2. **Grok agent** WebSocket server on port **2419**:

```text
grok agent --always-approve serve --bind 0.0.0.0:2419 --secret <token>
```

- Secret persists in `~/.grok/agent-serve.secret` (or set `GROK_AGENT_SECRET`).
- Override agent bind with `GROK_AGENT_BIND=0.0.0.0:2419`.
- Override session API port with `GROK_SESSION_INDEX_PORT=2420`.
- Ctrl+C stops both.

### Project / working directory

The **host working directory (cwd)** is the project the agent uses for tools (files, shell, git). It is **not** the phone path.

- App default: host `$HOME` → `/home/user`
- Change in **Settings → Host project directory**
- Home screen shows the active cwd and only lists sessions for that directory
- Chat top bar also shows the cwd for the open session

Sessions on disk live under `~/.grok/sessions/<encoded-cwd>/<session-id>/`.

## Phone

1. Install AgentRemote.
2. Settings → set **LAN** and **Tailnet** base URLs (`ws://host:2419`), secret, host cwd.
3. Switch LAN / Tailnet with the chips.
4. Home lists prior sessions for that cwd (needs `grokserve` so the session API is up).
5. Tap a session to resume (`session/load` + history replay), or **+** for a new chat.

Permissions for tools are approved on the **host** (`--always-approve`), not in the app.

## Ports

| Port | Role |
| --- | --- |
| 2419 | ACP WebSocket (`ws://host:2419/ws?server-key=…`) |
| 2420 | Session list HTTP (`http://host:2420/api/sessions?cwd=…`) |

The app derives the session API host/port from the agent base URL (agent port + 1).
