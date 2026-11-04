# DevPulse
 
A live dashboard for small dev teams. It pulls GitHub activity (who pushed what, which files changed) into one feed and warns teammates when two people are editing the same files before it becomes a merge conflict.
 
## Status
 
Work in progress. Done so far: the Spring Boot webhook receiver. Events are held in memory for now.
 
| Piece | State |
|---|---|
| Webhook receiver (HMAC verification, dedup) | Done |
| PostgreSQL storage | Planned |
| File-overlap detection across open branches | Planned |
| Live feed (SSE) + Next.js UI | Planned |
| Diff view (GitHub API) | Planned |
| Docker + GitHub Actions | Planned |
 
## Stack
 
Spring Boot, PostgreSQL, Next.js, Docker, GitHub Actions.
 
## Setup
 
Requires Java 17+.
 
```sh
export GITHUB_WEBHOOK_SECRET=<your-secret>
./mvnw spring-boot:run
```
 
Then add a webhook in your GitHub repo: payload URL `https://<host>/webhook`, content type `application/json`, the same secret, and the **push** event.
 
## Webhook behavior
 
`POST /webhook`
 
| Response | Meaning |
|---|---|
| `401` | Missing or invalid `X-Hub-Signature-256` |
| `400` | Missing delivery ID or event type, or malformed payload |
| `200` | Valid but ignored (non-push events, tag pushes) or a duplicate delivery |
| `201` | New push event stored |
 
Each stored event records the delivery ID, repo, branch, author, changed files, and received time. Deliveries are deduplicated by `X-GitHub-Delivery`, so GitHub retries are safe.
