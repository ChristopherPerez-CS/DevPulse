"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const MAX_EVENTS = 100;
const FILES_SHOWN = 5;

type Push = {
  delivery: string;
  repo: string;
  branch: string;
  author: string;
  files: string[];
  receivedAt: string;
};

type Overlap = {
  repo: string;
  branch: string;
  author: string;
  otherBranch: string;
  otherAuthor: string;
  files: string[];
};

type Status = "connecting" | "live" | "reconnecting" | "offline";

const STATUS_LABEL: Record<Status, string> = {
  connecting: "Connecting",
  live: "Live",
  reconnecting: "Reconnecting",
  offline: "Offline",
};

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${API}${path}`);
  if (!res.ok) throw new Error(`${path} failed with ${res.status}`);
  return res.json();
}

// Union by delivery ID (the stream and the history can both contain the same push), newest first.
function merge(prev: Push[], next: Push[]): Push[] {
  const byId = new Map<string, Push>();
  for (const e of [...prev, ...next]) byId.set(e.delivery, e);
  return [...byId.values()]
    .sort((a, b) => Date.parse(b.receivedAt) - Date.parse(a.receivedAt))
    .slice(0, MAX_EVENTS);
}

function FileList({ files }: { files: string[] }) {
  if (files.length === 0) return <p className="muted">No file changes in this push.</p>;
  const extra = files.length - FILES_SHOWN;
  return (
    <ul className="files">
      {files.slice(0, FILES_SHOWN).map((f) => (
        <li key={f}>
          <code>{f}</code>
        </li>
      ))}
      {extra > 0 && <li className="muted">and {extra} more</li>}
    </ul>
  );
}

export default function Dashboard() {
  const [events, setEvents] = useState<Push[]>([]);
  const [overlaps, setOverlaps] = useState<Overlap[]>([]);
  const [status, setStatus] = useState<Status>("connecting");

  const loadOverlaps = useCallback(() => {
    getJson<Overlap[]>("/overlaps").then(setOverlaps).catch(console.error);
  }, []);

  useEffect(() => {
    const source = new EventSource(`${API}/events/stream`);

    // Runs on the first connection and on every automatic reconnect, which also fills any gap.
    source.onopen = () => {
      setStatus("live");
      getJson<Push[]>("/events")
        .then((list) => setEvents((prev) => merge(prev, list)))
        .catch(console.error);
      loadOverlaps();
    };
    source.onerror = () =>
      setStatus(source.readyState === EventSource.CLOSED ? "offline" : "reconnecting");

    source.addEventListener("push", (e) => {
      const push: Push = JSON.parse((e as MessageEvent<string>).data);
      setEvents((prev) => merge(prev, [push]));
    });
    // The server owns the conflict state, so re-read it instead of merging it by hand.
    source.addEventListener("overlap", loadOverlaps);

    return () => source.close();
  }, [loadOverlaps]);

  const flagged = useMemo(
    () => new Set(overlaps.flatMap((o) => [`${o.repo}|${o.branch}`, `${o.repo}|${o.otherBranch}`])),
    [overlaps],
  );

  return (
    <main className="page">
      <header className="top">
        <h1>DevPulse</h1>
        <span className={`status ${status}`} role="status">
          {STATUS_LABEL[status]}
        </span>
      </header>

      <section aria-labelledby="overlaps-heading" aria-live="polite">
        <h2 id="overlaps-heading">Overlapping work</h2>
        {overlaps.length === 0 ? (
          <p className="muted">No one is changing the same files on different branches right now.</p>
        ) : (
          <ul className="cards">
            {overlaps.map((o) => (
              <li key={`${o.repo}|${o.branch}|${o.otherBranch}`} className="card warn">
                <p>
                  <strong>{o.repo}</strong>: {o.author} on <code>{o.branch}</code> and {o.otherAuthor} on{" "}
                  <code>{o.otherBranch}</code> are both changing:
                </p>
                <FileList files={o.files} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="activity-heading">
        <h2 id="activity-heading">Activity</h2>
        {events.length === 0 ? (
          <p className="muted">No pushes yet. They appear here as soon as GitHub sends them.</p>
        ) : (
          <ul className="cards">
            {events.map((e) => (
              <li key={e.delivery} className="card">
                <div className="row">
                  <span>
                    <strong>{e.author}</strong> pushed to <code>{e.branch}</code> in {e.repo}
                  </span>
                  {flagged.has(`${e.repo}|${e.branch}`) && <span className="badge">Overlap risk</span>}
                </div>
                <time className="muted" dateTime={e.receivedAt}>
                  {new Date(e.receivedAt).toLocaleString()}
                </time>
                <FileList files={e.files} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
