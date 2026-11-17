-- Overlap checks filter by repo and a recent time window.
CREATE INDEX events_repo_received_at_idx ON events (repo, received_at DESC);
