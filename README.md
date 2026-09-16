# hookrelay

Send a webhook and know what happened to it. Signed with [Standard Webhooks](https://www.standardwebhooks.com), retried on a backoff, dead-lettered when a receiver never comes back, and every attempt readable over the API.

**Live:** https://hookrelay-u7ml.onrender.com · **API docs:** https://hookrelay-u7ml.onrender.com/swagger-ui.html

[![CI](https://github.com/Rahul200512/hookrelay/actions/workflows/ci.yml/badge.svg)](https://github.com/Rahul200512/hookrelay/actions/workflows/ci.yml)

## Why

I built [BumpCheck](https://github.com/Rahul200512/bumpcheck) to find breaking changes in dependency upgrades. It works, and nobody finds out unless they open the site. The obvious next feature was to tell people: *your project depends on Alembic, Alembic 1.20 dropped something you use, here it is.*

Which means calling a URL someone gave me. That turns out to be the whole problem:

- The receiver is down when the event happens. Retry, but not forever, and not all at once.
- The receiver is slow. One slow customer must not stall deliveries to everyone else.
- We crashed after sending and before recording it. The receiver sees the same event twice and needs to know it's the same one.
- The receiver has to know the request is really from us, and we have to be able to change the secret without dropping deliveries.
- The receiver's domain expired six months ago and every attempt still costs us a connection and a timeout.

Every one of those is a design decision with a defensible answer, and none of them are visible in a `POST` that returns 200. This is that layer, built on its own.

## Try it

Three calls against the live service. No account, no setup: it hosts a test receiver, so you can watch a signed delivery arrive without standing one up.

It is on a free instance that sleeps after 15 idle minutes, so the first call may take a minute to answer while it wakes.

```bash
HOST=https://hookrelay-u7ml.onrender.com

KEY=$(curl -s -X POST $HOST/v1/tenants -H 'content-type: application/json' \
  -d '{"name":"demo"}' | jq -r .apiKey)

SINK=$(curl -s -X POST $HOST/v1/sinks -H "authorization: Bearer $KEY")
SINK_URL=$(echo $SINK | jq -r .url); SINK_ID=$(echo $SINK | jq -r .id)

curl -s -X POST $HOST/v1/endpoints -H "authorization: Bearer $KEY" \
  -H 'content-type: application/json' \
  -d "{\"url\":\"$SINK_URL\",\"eventTypes\":[\"order.paid\"]}" | jq .

curl -s -X POST $HOST/v1/events -H "authorization: Bearer $KEY" \
  -H 'content-type: application/json' -H 'idempotency-key: demo-1' \
  -d '{"type":"order.paid","payload":{"orderId":"ord_8891","amount":"49.00"}}' | jq .

sleep 2 && curl -s $HOST/v1/sinks/$SINK_ID/requests -H "authorization: Bearer $KEY" | jq .
```

The last call shows what the receiver actually got:

```json
[
  {
    "receivedAt": "2026-09-14T15:36:41.313738Z",
    "headers": {
      "webhook-id": "9fd674b5-dea6-4d4e-9e2e-70ed54cb0479",
      "webhook-timestamp": "1789400201",
      "webhook-signature": "v1,KcGKunCPomn9ulXQRCmzjrbZeRZv5YMWY0X9vKxPYzY=",
      "content-type": "application/json",
      "user-agent": "hookrelay/0.1 (+https://github.com/Rahul200512/hookrelay)"
    },
    "body": "{\"id\":\"918949fb-...\",\"type\":\"order.paid\",\"timestamp\":\"2026-09-14T15:36:41.153663Z\",\"data\":{\"amount\":\"49.00\",\"orderId\":\"ord_8891\"}}"
  }
]
```

`GET /v1/deliveries/{id}/attempts` gives you the other half — every attempt, its status code, how long it took, and what went wrong:

```json
[ { "attemptNo": 1, "startedAt": "2026-09-14T15:36:41.268603Z", "durationMs": 64, "statusCode": 200, "error": null } ]
```

Interactive docs are at `/swagger-ui.html`.

## What's here (v0)

**The queue is a Postgres table.** A poller claims due rows in one statement:

```sql
UPDATE deliveries SET status='RUNNING', attempt_count = attempt_count + 1,
       lease_expires_at = now() + make_interval(secs => :leaseSeconds)
 WHERE id IN (SELECT id FROM deliveries
               WHERE (status='PENDING' AND next_attempt_at <= now())
                  OR (status='RUNNING' AND lease_expires_at < now())
               ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT :batch)
RETURNING id, event_id, endpoint_id, attempt_count
```

`SKIP LOCKED` means two workers can never take the same row. The lease means a worker that dies mid-delivery loses nothing — when the lease lapses the row is due again. The attempt is counted at *claim* time, not at completion, so a worker that crashes on the same poisonous row forever still runs out of attempts instead of looping until the heat death of the universe.

**No database connection is held across the HTTP call.** Read what the call needs, release; call, holding nothing; persist in a second short transaction. I learned that one the expensive way on BumpCheck, where a slow provider held a transaction open long enough for Neon to kill the connection and take the whole run down with it.

**Each delivery runs on a virtual thread**, bounded twice: a global in-flight cap so the process can't exhaust sockets, and a per-endpoint cap of four, so one receiver that takes ten seconds to answer can't occupy the whole budget while everyone else waits.

**What a response means:**

| Receiver says | We do |
|---|---|
| 2xx | Succeeded. The endpoint's failure counter resets. |
| 410 Gone | The receiver is telling us to stop. Disable the endpoint. |
| Other 4xx (not 408/425/429) | Dead-letter now. A 404 or a 401 won't fix itself, and retrying it eight times is just noise in someone's logs. |
| 429 | Retry, honouring `Retry-After` up to an hour. |
| 5xx, timeout, connection refused | Retry on the schedule. |
| 50 consecutive failures | Pause the endpoint. It stops receiving new deliveries until it's re-enabled. |

Retries wait 10s, 1m, 5m, 30m, 2h, 6h, 12h — eight attempts over about twenty hours — with **equal jitter**: half the base delay plus a random half. Full jitter can produce a zero-length wait, which retries a receiver that just said "not now" immediately; half the base is the floor.

**Idempotency is a unique index, not a check-then-insert.** `POST /v1/events` with an `Idempotency-Key` header relies on `unique (tenant_id, idempotency_key)`. Two concurrent requests with the same key both try to insert; one loses on the constraint and is answered with the winner's row and a 200 instead of a 201. There is no window between the check and the write because there is no check.

**Signatures follow the Standard Webhooks spec**, so receivers can verify with an off-the-shelf library in any language rather than something I invented. `webhook-id` is the delivery id and is identical across retries, which is what lets a receiver dedupe. The unit tests assert against the vector published with the spec.

**Every target URL is checked twice** — at registration and again immediately before each send, because DNS changes in between. HTTPS only, no embedded credentials, and nothing that resolves to a loopback, private, link-local, CGNAT, or cloud-metadata address. Redirects are never followed, because a redirect is a second URL nobody validated. Without this the service is an open proxy into its own network, on behalf of strangers.

**Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problems** with stable `type` URIs, listed in [docs/problems.md](docs/problems.md).

90 tests, 92% line coverage. The integration tests run the real application against a real Postgres in Testcontainers, and cover the delivery loop, signature verification, idempotency, retries, dead-lettering, endpoint pausing, 410, and timeouts. ArchUnit enforces that the domain doesn't reach upwards into the web layer. gitleaks scans the whole history on every push.

## Guarantees, measured (v1)

The v0 section claims `SKIP LOCKED` stops two workers taking the same row, and that a lease returns a crashed worker's row to the queue. Those are the two things the whole service rests on, so they are tests that print numbers rather than sentences I wrote.

```bash
./mvnw test -Dtest=QueueGuaranteesIT
```

| Claim | How it's tested | Result |
|---|---|---|
| Two workers never take the same row | 10 000 due deliveries, 8 threads claiming in batches of 100 until the table is drained | **10 000 claims, 10 000 distinct, 0 duplicates**, 358 ms |
| A crashed worker loses nothing | Claim a row, record no outcome, expire the lease, claim again | Same row returns, **0 lost**, attempt count 1 → 2 |
| A held lease hides the row | Claim, then claim again immediately | Second claim returns nothing |

The crashed attempt is still counted, which matters more than it sounds: a row that kills whatever picks it up would otherwise be retried forever. Counting at claim time means a crash loop ends in dead-lettering like any other failure.

The background poller is switched off in that test (`hookrelay.delivery.poller-enabled=false`). With it running there would be a third worker competing for the same rows, and "claimed exactly once" would be a statement about a race nobody could see. That switch is not test scaffolding: it is what would let API nodes and worker nodes be scaled separately.

**Replay gives a dead delivery a fresh budget without losing its history.** Attempt numbers are unique per delivery, so replay cannot reset the counter — the old attempt rows are still there and would collide. Instead the delivery records where the current round began, and the retry budget is measured from that mark. Replay a delivery that failed three times and succeeds on the next try, and the log reads 500, 500, 500, 200 as attempts one through four.

```bash
curl -X POST $HOST/v1/deliveries/$ID/replay -H "authorization: Bearer $KEY"
```

Refused with a 422 if the delivery is still pending or running, or if its endpoint is switched off — replaying into a disabled endpoint would just burn the new budget.

**A paused endpoint gets back in on its own.** After a cooldown (15 minutes by default) the pause is lifted and normal traffic resumes. There is no synthetic ping: sending a made-up request to someone's production URL to see if they answer is rude, and it proves less than it looks, since a receiver can answer a probe and still reject real events. The probe is the next real event. If the receiver is still broken the endpoint simply pauses again, so a dead URL costs one failed delivery per cooldown instead of a permanent retry storm.

Pauses the service inflicted on itself are lifted this way. An endpoint the user disabled, or one that answered 410 Gone, stays off until the user turns it back on.


## Secrets and tenancy (v2)

**A secret can change without dropping a delivery.** `POST /v1/endpoints/{id}/rotate-secret` issues a new secret and keeps the old one signing alongside it for 24 hours. Deliveries in that window carry both signatures, space delimited as the spec allows:

```
webhook-signature: v1,DmQ6JhEuXeGjCkVPtvV8tKUwD1yFqCJhKDzHIXxNoCU= v1,KcGKunCPomn9ulXQRCmzjrbZeRZv5YMWY0X9vKxPYzY=
```

A receiver verifies if *any* one matches, so it can be updated at any point inside the window and never sees a failure. Rotate twice inside one window and you still get two: the header cannot grow without bound.

**Signing secrets are encrypted at rest** with AES-GCM, key from the environment. A leaked database dump is bad; a leaked database dump that lets someone forge deliveries every receiver accepts as genuine is worse. Stored values carry a `v1:` prefix so the scheme can change later — an unprefixed blob can only be guessed at.

There is no fallback to plaintext. With no key configured the service refuses to start and tells you how to make one, because a service that quietly stops encrypting when a variable goes missing is worse than one that stops.

**API keys rotate the same way.** `POST /v1/api-keys` issues another, `DELETE /v1/api-keys/{id}` revokes one: issue, deploy, revoke, no gap. Only the SHA-256 is stored, so listing keys gives you prefixes and nothing else. Revoking your only active key is refused — it would lock the tenant out with no way back in.

**Limits, and what they actually enforce.** Five endpoints and five active keys per tenant, five signups an hour per address, 120 events a minute per tenant. The counters live in memory, so with more than one instance the real limit is that times the instance count. They protect a free-tier database from a script; they are not a global quota, and calling them one would be a lie. A shared counter is the fix and it needs somewhere shared to put it.

**Anyone can create a tenant**, which is what makes the live demo work without an account, and also what would fill half a gigabyte of free Postgres with strangers' test data. Demo tenants are deleted after seven days and everything they own goes with them through the foreign keys.

Verification snippets for receivers, in [Java](docs/VerifySignature.java) and [Node](docs/verify-signature.js). Both check the timestamp as well as the signature: without that, a signature captured once stays valid forever.


## The thread model, measured (v3)

The v0 section says delivery runs on virtual threads because the work is almost all waiting. That was an assertion. The thread model is now a setting, so the same code can be run both ways and the claim checked:

```bash
./mvnw test -Dtest='*BenchmarkIT'
```

500 deliveries to one receiver that takes 200 ms to answer, identical in every respect except the executor:

| Executor | Wall time | Throughput | Call latency (avg / max) | Dispatch spread |
|---|---|---|---|---|
| Virtual thread per delivery, 256 in flight | **3,156 ms** | **158/s** | 254 ms / 775 ms | 2,643 ms |
| Fixed pool of 16 platform threads | 7,191 ms | 70/s | 203 ms / 235 ms | 6,844 ms |

Read the last two columns together, because they say something the throughput number alone doesn't.

The platform pool's calls are *cleaner* — 203 ms average against a receiver that takes 200 ms, almost no overhead — and it is still less than half the speed. Only sixteen calls can be outstanding at once, so the work is served sixteen at a time and the dispatch spread stretches to 6.8 seconds. `500 × 200 ms ÷ 16` is 6.25 seconds, so it is running within 15% of the best it could possibly do. The pool size *is* the throughput.

Virtual threads get all 256 out much closer together, and pay for it in per-call latency: 254 ms average, 775 ms worst. That extra time is contention, and it is mostly an artefact of the measurement — the receiver is a controller in this same JVM competing for the same cores, so 256 genuinely simultaneous 200 ms calls cannot all come back in 200 ms. Against real receivers on other people's servers the ceiling would be higher. The comparison is still fair, because both runs pay it.

**Why sixteen and not five hundred.** The honest version of "just make the pool bigger" is that a platform thread reserves its stack whether it is working or waiting, and here it is always waiting. Sixteen is about what you would give a 512 MB free-tier container. That is the trade virtual threads remove: the in-flight cap can now be a number chosen from what receivers and sockets can stand, rather than from what a thread costs to have.

The benchmark is kept out of `mvn verify`. It takes a minute, and it measures a machine as much as it measures the code.

## What's next

A native image, to see whether a free-tier cold start can be made not to matter. Tracked in [ROADMAP.md](ROADMAP.md).

## Run locally

Needs Java 21 and Docker.

```bash
docker run -d --name hookrelay-pg -p 5432:5432 \
  -e POSTGRES_USER=hookrelay -e POSTGRES_PASSWORD=hookrelay -e POSTGRES_DB=hookrelay \
  postgres:17-alpine

export APP_ENCRYPTION_KEY=$(openssl rand -base64 32)
./mvnw spring-boot:run
```

Flyway creates the schema on startup. The encryption key is required: signing secrets are encrypted at rest and the service will not start without one. Keep the same key between restarts or the secrets already stored become unreadable. To point endpoints at `localhost` while experimenting, set `hookrelay.security.allow-private-targets=true` — it is false everywhere else for the reason described above.

`./mvnw verify` runs everything, including the Testcontainers tests. Without Docker they skip rather than fail, and the build stays green on the unit tests alone — so check the skip count if you expected them to run. On Colima or another non-default Docker socket, point Testcontainers at it:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Layout

```
src/main/java/io/github/rahul200512/hookrelay/
  api/         controllers, request/response records, problem types
  domain/      entities, repositories, BackoffPolicy, WebhookSigner
  delivery/    DeliveryQueue (the claim), Dispatcher (virtual threads),
               Client (signing + timeouts), Outcomes (what a response means)
  crypto/      AES-GCM for secrets at rest, below everything else
  security/    API key filter, SSRF guard
  events/      publish + fan-out + idempotency
  sink/        the built-in test receiver
  tenancy/     signup
src/main/resources/db/migration/   Flyway
src/test/java/.../it/              Testcontainers integration tests
```

MIT licensed.
