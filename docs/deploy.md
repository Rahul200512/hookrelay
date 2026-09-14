# Deploying

Two accounts, both free, neither asks for a card: [Neon](https://neon.com) for Postgres and [Render](https://render.com) for the service.

## Neon

1. Create a project named `hookrelay`. A separate project from any other work keeps the free plan's per-project quota (100 compute-hours, 0.5 GB) to itself.
2. Copy the **pooled** connection string. It looks like
   `postgresql://user:password@ep-something-pooler.region.aws.neon.tech/hookrelay?sslmode=require`.
3. Convert it to the three values this app reads. JDBC wants the credentials out of the URL:

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | `jdbc:postgresql://ep-something-pooler.region.aws.neon.tech/hookrelay?sslmode=require` |
   | `DATABASE_USERNAME` | the user from the string |
   | `DATABASE_PASSWORD` | the password from the string |

Neon suspends a free compute after five idle minutes. The first request after that waits a second or so while it wakes; nothing is lost, and the delivery poller wakes it every second anyway while the service is up.

## Render

1. **New → Web Service → Existing image**, image `ghcr.io/rahul200512/hookrelay:latest`. The package has to be public for Render to pull it without credentials: on the GitHub package page, **Package settings → Change visibility → Public**.
   Alternatively point Render at this repository and let it build the `Dockerfile`; the free plan includes enough build minutes.
2. Instance type **Free**. Add the three database variables above plus `APP_PUBLIC_URL` set to the service's own URL, e.g. `https://hookrelay.onrender.com` — sink URLs are built from it.
3. Health check path `/actuator/health`.
4. Copy the **deploy hook** URL from Settings and save it as the repository secret `RENDER_DEPLOY_HOOK`, and the service URL as the repository variable `APP_URL`. The deploy workflow pushes the image and calls the hook; the keep-warm workflow pings health every ten minutes.

Render sleeps a free service after 15 idle minutes and takes about a minute to wake. The keep-warm cron is the honest workaround, not a fix — 750 free instance-hours a month is more than a month has, so one always-awake service stays inside the free tier.

## First run

```bash
curl -s https://<your-service>/actuator/health
```

Flyway applies the schema on the first boot. Then the three calls in the README should work against the live host.
