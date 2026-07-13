PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    backend TEXT NOT NULL CHECK (backend IN ('grok', 'codex')),
    backend_session_id TEXT,
    cwd TEXT NOT NULL,
    title TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'idle',
    active_turn_id TEXT,
    model_id TEXT,
    model_name TEXT,
    model_override TEXT,
    reasoning_effort TEXT,
    used_tokens INTEGER,
    context_window_tokens INTEGER,
    codex_full_access INTEGER NOT NULL DEFAULT 1,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS sessions_backend_identity
    ON sessions(backend, backend_session_id)
    WHERE backend_session_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS turns (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    prompt TEXT NOT NULL,
    status TEXT NOT NULL,
    stop_reason TEXT,
    error TEXT,
    cancellation_requested INTEGER NOT NULL DEFAULT 0,
    worker_pid INTEGER,
    created_at TEXT NOT NULL,
    started_at TEXT,
    completed_at TEXT
);

CREATE INDEX IF NOT EXISTS turns_session_created
    ON turns(session_id, created_at);

CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    turn_id TEXT REFERENCES turns(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    text TEXT NOT NULL DEFAULT '',
    status TEXT,
    kind TEXT,
    detail TEXT,
    ordinal INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS messages_session_created
    ON messages(session_id, created_at);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    turn_id TEXT REFERENCES turns(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS events_session_id
    ON events(session_id, id);

PRAGMA user_version = 5;
