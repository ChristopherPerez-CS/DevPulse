# DevPulse

![CI](https://github.com/ChristopherPerez-CS/DevPulse/actions/workflows/ci.yml/badge.svg)

A live dashboard for small dev teams. It pulls GitHub activity (who pushed what, which files changed) into one feed and warns teammates when two people are editing the same files before it becomes a merge conflict.

## Status

Work in progress.

| Piece                                              | State   |
| -------------------------------------------------- | ------- |
| Webhook receiver (HMAC verification, dedup)        | Done    |
| PostgreSQL storage with Flyway migrations          | Done    |
| Integration tests against real Postgres (Testcontainers) | Done |
| GitHub Actions CI                                  | Done    |
| Live feed (`GET /events` + Server-Sent Events)     | Planned |
| File-overlap detection across open branches        | Planned |
| Next.js UI                                         | Planned |
| Diff view (GitHub API)                             | Planned |
| Dockerfile, full-stack compose, deployment         | Planned |

## Stack

Spring Boot, PostgreSQL, Flyway, Next.js (planned), Docker, GitHub Actions.

## Setup

Requires Java 17+, Maven, and Docker.

```
docker compose up -d
export GITHUB_WEBHOOK_SECRET=<your-secret>      # PowerShell: $env:GITHUB_WEBHOOK_SECRET="<your-secret>"
mvn spring-boot:run
```

The app refuses to start if `GITHUB_WEBHOOK_SECRET` is missing or blank. Flyway creates the `events` table on startup.

To receive real events, add a webhook in your GitHub repo: payload URL `https://<host>/webhook`, content type `application/json`, the same secret, and the **push** event. For local testing, expose the app with a tunnel such as smee.io or ngrok.

## Tests

```
mvn test
```

Docker must be running. The tests start a throwaway `postgres:16` container with Testcontainers, so deduplication is verified against a real primary-key constraint rather than a mock.

**Docker Desktop with Engine 29 on Windows:** older Testcontainers versions can fail with `Could not find a valid Docker environment` because the engine rejects their default API version. CI works around this by writing `api.version=1.44` to `~/.docker-java.properties`; do the same locally if you see the error.

## Webhook behavior

`POST /webhook`

| Response | Meaning                                                                 |
| -------- | ----------------------------------------------------------------------- |
| `401`    | Missing or invalid `X-Hub-Signature-256`                                |
| `400`    | Missing delivery ID or event type, or malformed payload                 |
| `200`    | Valid but ignored (non-push events, tag pushes) or a duplicate delivery |
| `201`    | New push event stored                                                   |
| `500`    | Storage error; GitHub will retry the delivery                           |

Each stored event records the delivery ID, repo, branch, author, changed files, and received time.

## Design notes

- **Authenticate before parsing.** The HMAC-SHA256 signature is computed over the raw request bytes and compared in constant time. Nothing is parsed or stored until it passes.
- **Fail closed.** No secret, no startup.
- **Deduplicate in the database.** `delivery_id` is the primary key and the insert uses `ON CONFLICT DO NOTHING`, so retried deliveries are safe even across restarts or multiple instances. There is no check-then-insert race.
- **Plain SQL over an ORM.** The dedup logic is a single readable statement. Changed files are stored as `jsonb` so overlap queries can be done in SQL later.
- **Schema as code.** Versioned Flyway migrations in `src/main/resources/db/migration`.
