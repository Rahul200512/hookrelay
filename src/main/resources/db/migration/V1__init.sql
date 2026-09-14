-- hookrelay schema. Public identifiers are UUIDs so the API never leaks row counts.

create table tenants (
    id          uuid primary key default gen_random_uuid(),
    name        text not null,
    is_demo     boolean not null default true,
    created_at  timestamptz not null default now()
);

create table api_keys (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenants(id) on delete cascade,
    key_hash    text not null unique,          -- sha256(hex) of the raw key; the raw key is shown once
    prefix      text not null,                 -- first 8 chars, so a user can tell keys apart
    created_at  timestamptz not null default now(),
    revoked_at  timestamptz
);
create index api_keys_tenant_idx on api_keys(tenant_id);

create table endpoints (
    id                          uuid primary key default gen_random_uuid(),
    tenant_id                   uuid not null references tenants(id) on delete cascade,
    url                         text not null,
    description                 text,
    secret                      text not null,
    previous_secret             text,
    previous_secret_expires_at  timestamptz,
    event_types                 text[],        -- null = receive everything
    enabled                     boolean not null default true,
    consecutive_failures        integer not null default 0,
    paused_at                   timestamptz,
    paused_reason               text,
    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now()
);
create index endpoints_tenant_idx on endpoints(tenant_id);

create table events (
    id               uuid primary key default gen_random_uuid(),
    tenant_id        uuid not null references tenants(id) on delete cascade,
    type             text not null,
    payload          jsonb not null,
    idempotency_key  text,
    created_at       timestamptz not null default now(),
    constraint events_idempotency_unique unique (tenant_id, idempotency_key)
);
create index events_tenant_created_idx on events(tenant_id, created_at desc);

create table deliveries (
    id                uuid primary key default gen_random_uuid(),
    event_id          uuid not null references events(id) on delete cascade,
    endpoint_id       uuid not null references endpoints(id) on delete cascade,
    tenant_id         uuid not null references tenants(id) on delete cascade,
    status            text not null check (status in ('PENDING','RUNNING','SUCCEEDED','DEAD')),
    attempt_count     integer not null default 0,
    next_attempt_at   timestamptz not null default now(),
    lease_expires_at  timestamptz,
    last_status_code  integer,
    last_error        text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint deliveries_event_endpoint_unique unique (event_id, endpoint_id)
);
-- The poller's index: only rows that can still be worked.
create index deliveries_due_idx on deliveries(next_attempt_at) where status in ('PENDING','RUNNING');
create index deliveries_endpoint_idx on deliveries(endpoint_id, created_at desc);
create index deliveries_event_idx on deliveries(event_id);

create table delivery_attempts (
    id           uuid primary key default gen_random_uuid(),
    delivery_id  uuid not null references deliveries(id) on delete cascade,
    attempt_no   integer not null,
    started_at   timestamptz not null,
    duration_ms  integer not null,
    status_code  integer,
    error        text,
    constraint delivery_attempts_unique unique (delivery_id, attempt_no)
);

create table sinks (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenants(id) on delete cascade,
    token       text not null unique,
    created_at  timestamptz not null default now()
);

create table sink_requests (
    id           uuid primary key default gen_random_uuid(),
    sink_id      uuid not null references sinks(id) on delete cascade,
    headers      jsonb not null,
    body         text not null,
    received_at  timestamptz not null default now()
);
create index sink_requests_sink_idx on sink_requests(sink_id, received_at desc);
