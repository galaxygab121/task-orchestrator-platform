-- Jobs: the single source of truth for job state. Kafka messages only reference job ids/attempts.
create table jobs (
    id                   uuid primary key,
    title                varchar(200)  not null,
    type                 varchar(40)   not null,
    payload              jsonb         not null,
    priority             integer       not null check (priority between 1 and 5),
    estimated_runtime_ms bigint        not null check (estimated_runtime_ms >= 0),
    max_retries          integer       not null check (max_retries between 0 and 5),
    attempt              integer       not null default 0 check (attempt >= 0),
    status               varchar(20)   not null
        check (status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    result               jsonb,
    last_error           varchar(1000),
    idempotency_key      varchar(128),
    request_hash         varchar(64),
    lease_owner          varchar(200),
    lease_expires_at     timestamptz,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    started_at           timestamptz,
    completed_at         timestamptz,
    version              bigint        not null default 0,
    constraint uq_jobs_idempotency_key unique (idempotency_key)
);

-- Scheduler queue scan: SUBMITTED ordered by priority desc, created_at asc.
create index ix_jobs_dispatch on jobs (priority desc, created_at asc, id asc) where status = 'SUBMITTED';
-- Lease reaper scan.
create index ix_jobs_running_lease on jobs (lease_expires_at) where status = 'RUNNING';
-- API listing (deterministic sort) and filters.
create index ix_jobs_created on jobs (created_at desc, id desc);
create index ix_jobs_status_created on jobs (status, created_at desc, id desc);
create index ix_jobs_type_created on jobs (type, created_at desc, id desc);

-- Append-only audit trail of every status transition (also used to prove "at most one success").
create table job_events (
    id          bigserial primary key,
    job_id      uuid         not null references jobs (id),
    from_status varchar(20),
    to_status   varchar(20)  not null,
    attempt     integer      not null,
    actor       varchar(200) not null,
    detail      varchar(1000),
    occurred_at timestamptz  not null
);
create index ix_job_events_job on job_events (job_id, id);

-- Transactional outbox. Rows are written in the same DB transaction as the state change they announce,
-- then published to Kafka by OutboxPublisher (at-least-once). available_at supports delayed retries.
create table outbox_events (
    id               bigserial primary key,
    event_id         uuid          not null unique,
    aggregate_id     uuid          not null,
    topic            varchar(100)  not null,
    event_type       varchar(100)  not null,
    payload          jsonb         not null,
    created_at       timestamptz   not null,
    available_at     timestamptz   not null,
    published_at     timestamptz,
    publish_attempts integer       not null default 0,
    last_error       varchar(1000)
);
create index ix_outbox_pending on outbox_events (available_at, id) where published_at is null;
create index ix_outbox_aggregate on outbox_events (aggregate_id);
