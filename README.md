# hookrelay

Send a webhook and know what happened to it. Signed with [Standard Webhooks](https://www.standardwebhooks.com), retried on a backoff, dead-lettered when a receiver never comes back, and every attempt readable over the API.

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

Three calls. No account, no setup: the service hosts a test receiver, so you can watch a delivery arrive without standing one up.

```bash
HOST=http://localhost:8080

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

64 tests, 90% line coverage. The integration tests run the real application against a real Postgres in Testcontainers, and cover the delivery loop, signature verification, idempotency, retries, dead-lettering, endpoint pausing, 410, and timeouts. ArchUnit enforces that the domain doesn't reach upwards into the web layer. gitleaks scans the whole history on every push.

## What's next

Crash recovery and two-worker contention measured rather than asserted, secret rotation with an overlap window, and a native image to see whether a free-tier cold start can be made not to matter. Tracked in [ROADMAP.md](ROADMAP.md).

## Run locally

Needs Java 21 and Docker.

```bash
docker run -d --name hookrelay-pg -p 5432:5432 \
  -e POSTGRES_USER=hookrelay -e POSTGRES_PASSWORD=hookrelay -e POSTGRES_DB=hookrelay \
  postgres:17-alpine

./mvnw spring-boot:run
```

Flyway creates the schema on startup. To point endpoints at `localhost` while experimenting, set `hookrelay.security.allow-private-targets=true` — it is false everywhere else for the reason described above.

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
  security/    API key filter, SSRF guard
  events/      publish + fan-out + idempotency
  sink/        the built-in test receiver
  tenancy/     signup
src/main/resources/db/migration/   Flyway
src/test/java/.../it/              Testcontainers integration tests
```

MIT licensed.
