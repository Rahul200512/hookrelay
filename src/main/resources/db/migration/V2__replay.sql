-- Replaying a dead delivery gives it a fresh retry budget, but the attempt log stays
-- append-only: attempt_no is unique per delivery, so the counter can never be reset.
-- This column records where the current round started; the budget is measured from it.
alter table deliveries add column attempts_at_replay integer not null default 0;
