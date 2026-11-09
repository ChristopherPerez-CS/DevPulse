CREATE TABLE events (
    delivery_id TEXT PRIMARY KEY,
    repo        TEXT        NOT NULL,
    branch      TEXT        NOT NULL,
    author      TEXT        NOT NULL,
    files       JSONB       NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
