-- A second request key may reuse a completed/active job, but must remain bound to that request.
create table translation_job_submission (
    user_id varchar(255) not null,
    idempotency_key uuid not null,
    job_id uuid not null references translation_job(id) on delete cascade,
    primary key(user_id, idempotency_key)
);
