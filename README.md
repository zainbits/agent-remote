# AgentRemote

Android frontend for remote coding agents. **v0.1** talks to **Grok Build** over ACP WebSocket. Codex is reserved for a later backend.

## Host (manual)

Not a boot service. In a terminal:

```zsh
grokserve
```

Defined in `~/.zshrc`. Starts:

```text
grok agent --always-approve serve --bind 0.0.0.0:2419 --secret <token>
```

- Secret persists in `~/.grok/agent-serve.secret` (or set `GROK_AGENT_SECRET`).
- Override bind with `GROK_AGENT_BIND=0.0.0.0:2419`.
- Ctrl+C stops the server.

## Phone

1. Install AgentRemote.
2. Settings → set **LAN** and **Tailnet** base URLs (`ws://host:2419`), secret, host cwd.
3. Switch LAN / Tailnet with the chips.
4. Connect → chat.

Permissions for tools are approved on the **host** (`--always-approve`), not in the app.
