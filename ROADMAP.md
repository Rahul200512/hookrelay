# Roadmap

Each step ships deployed before the next starts.

- [x] **v0 — the loop works, live.** Tenants and API keys, endpoints, events with idempotency keys, deliveries with backoff and dead-lettering, Standard Webhooks signatures, an SSRF guard, a built-in sink so the demo needs nothing but curl. Problem Details errors, OpenAPI, Flyway, Actuator. Testcontainers integration tests. Live on Render, Postgres on Neon.
- [x] **v1 — guarantees you can measure.** 10 000 due rows and 8 workers: 10 000 claims, 10 000 distinct, 0 duplicates. A crashed worker's lease lapses and the row comes back, attempt counted, nothing lost. Paused endpoints return to rotation after a cooldown, with the next real event as the probe. Replay gives a dead delivery a fresh budget while the attempt log stays append-only.
- [x] **v2 — security and tenancy.** Secret rotation with a 24 h overlap: deliveries carry both signatures, so a receiver can be updated mid-window and never sees a failure. Signing secrets encrypted at rest with AES-GCM, and no plaintext fallback. API keys issued and revoked separately so a rotation has no gap, with the last active key protected. Per-tenant publish limits, demo tenants purged after seven days. Receiver verification snippets in Java and Node.
- [ ] **v3 — performance and cold start, measured.** Virtual threads against a platform-thread pool on a 200 ms receiver: 158/s against 70/s, with the latency and dispatch-spread figures that explain why. Done. A GraalVM native image, to see whether a free-tier cold start can be made not to matter, is the remaining half.
- [x] **v4 — first real producer.** [BumpCheck](https://github.com/Rahul200512/bumpcheck) publishes a `package.breaking_change` event here, citation included, as its pipeline finds each one. Keyed on the release's content hash, so a re-run of that pipeline announces nothing.

Java 25 is the newer LTS; this targets 21 because that is what most postings and most production fleets run today. Moving is a one-line change once the toolchain and base images are boring.

## Deliberately not planned

- **Kafka / RabbitMQ.** The queue is a Postgres table claimed with `SELECT … FOR UPDATE SKIP LOCKED` and a lease. That gives at-least-once delivery, crash recovery and exactly-one-worker-per-row with no second system to run, and it is the call I can defend at this scale.
- **Kubernetes / Terraform.** One container, one database, a free tier. Manifests that only ever run in CI don't prove anything; a Dockerfile and a real deploy do.
- **Ordered delivery per endpoint.** Stripe and GitHub don't promise it either. Receivers get an event timestamp and a stable id; ordering is theirs to apply if they need it.
- **A web UI.** Swagger UI and the sink are the interface. A status page might come later; it is not a plan.
