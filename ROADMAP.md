# Roadmap

Each step ships deployed before the next starts.

- [ ] **v0 — the loop works, live.** Tenants and API keys, endpoints, events with idempotency keys, deliveries with backoff and dead-lettering, Standard Webhooks signatures, an SSRF guard, a built-in sink so the demo needs nothing but curl. Problem Details errors, OpenAPI, Flyway, Actuator. Testcontainers integration tests. Live on Render, Postgres on Neon.
- [ ] **v1 — guarantees you can measure.** Crash recovery through lease expiry (kill a worker mid-delivery: 0 lost, 0 duplicated). Two workers over 10 000 due rows, every row claimed exactly once. Paused endpoints probed and re-enabled automatically. Replay of dead deliveries. Numbers in the README, with the test that produced them.
- [ ] **v2 — security and tenancy.** Secret rotation with a 24 h overlap (deliveries carry both signatures). Secrets encrypted at rest. API key rotation and revocation. Per-key rate limits. Demo tenants purged after seven days. Receiver verification snippets in Java and Node.
- [ ] **v3 — performance and cold start, measured.** Virtual threads against a platform-thread pool on a 200 ms receiver: deliveries per second and p99. GraalVM native image built in CI; JVM versus native cold start and memory on a tenth of a CPU.
- [ ] **v4 — first real producer.** [BumpCheck](https://github.com/Rahul200512/bumpcheck) publishes `package.breaking_change` events here when its pipeline finds one.

Java 25 is the newer LTS; this targets 21 because that is what most postings and most production fleets run today. Moving is a one-line change once the toolchain and base images are boring.

## Deliberately not planned

- **Kafka / RabbitMQ.** The queue is a Postgres table claimed with `SELECT … FOR UPDATE SKIP LOCKED` and a lease. That gives at-least-once delivery, crash recovery and exactly-one-worker-per-row with no second system to run, and it is the call I can defend at this scale.
- **Kubernetes / Terraform.** One container, one database, a free tier. Manifests that only ever run in CI don't prove anything; a Dockerfile and a real deploy do.
- **Ordered delivery per endpoint.** Stripe and GitHub don't promise it either. Receivers get an event timestamp and a stable id; ordering is theirs to apply if they need it.
- **A web UI.** Swagger UI and the sink are the interface. A status page might come later; it is not a plan.
