# Deploying

Two accounts, both free, neither asks for a card: [Neon](https://neon.com) for Postgres and [Render](https://render.com) for the service.

## Neon

1. Create a project named `hookrelay`. A separate project from any other work keeps the free plan's per-project quota (100 compute-hours, 0.5 GB) to itself.
2. Copy the **direct** (non-pooled) connection string. The app keeps a pool of five connections of its own, far below the direct endpoint's limit, and the direct endpoint has none of the transaction-pooler caveats around prepared statements. It looks like
   `postgresql://user:password@ep-something.region.aws.neon.tech/hookrelay?sslmode=require`.
3. Convert it to the three values this app reads. JDBC wants the credentials out of the URL:

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | `jdbc:postgresql://ep-something.region.aws.neon.tech/hookrelay?sslmode=require` |
   | `DATABASE_USERNAME` | the user from the string |
   | `DATABASE_PASSWORD` | the password from the string |

Neon suspends a free compute after five idle minutes. The first request after that waits a second or so while it wakes; nothing is lost, and the delivery poller wakes it every second anyway while the service is up.

Or, with the CLI, after `npx neonctl auth`:

```bash
npx neonctl projects create --name hookrelay --region-id aws-us-east-1 --pg-version 17 --output json
```

## Render

1. **New → Web Service → Public Git repository**, URL `https://github.com/Rahul200512/hookrelay`, runtime Docker. Render builds the `Dockerfile`; the free plan's build minutes cover it comfortably. (The CI-built image on GHCR is an artifact, not the deploy source: the package inherits the repository's visibility at first publish, so it is not pullable without a credential.)
2. Instance type **Free**, region **Virginia** (same coast as the Neon project). Add the three database variables above, plus `APP_ENCRYPTION_KEY`:

   ```bash
   openssl rand -base64 32
   ```

   Signing secrets are encrypted at rest with it and the service refuses to start without one. Keep it: change it and every secret already stored becomes unreadable, which means every receiver's verification starts failing. `APP_PUBLIC_URL` is not needed on Render: the app falls back to the `RENDER_EXTERNAL_URL` Render injects, and binds to the `PORT` Render sets.
3. Health check path `/actuator/health`.
4. Create an API key under **Account settings → API keys**. Save it as the repository secret `RENDER_API_KEY`, the service id (`srv-…`) as `RENDER_SERVICE_ID`, and the service URL as the repository variable `APP_URL`. After every green CI run on `main` the deploy workflow asks Render for a new deploy through the API; the keep-warm workflow pings health every ten minutes.

All of the Render steps can be done from the API instead of the dashboard; `scripts/deploy-render.sh` in this repository does exactly that, given the API key.

Render sleeps a free service after 15 idle minutes and takes about a minute to wake. The keep-warm cron is the honest workaround, not a fix — 750 free instance-hours a month is more than a month has, so one always-awake service stays inside the free tier.

## First run

```bash
curl -s https://<your-service>/actuator/health
```

Flyway applies the schema on the first boot. Then the three calls in the README should work against the live host.
