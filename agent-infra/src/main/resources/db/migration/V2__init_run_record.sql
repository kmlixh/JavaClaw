create table if not exists run_record (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    agent_id varchar(128) not null,
    user_id varchar(128) not null,
    status varchar(64) not null,
    detail text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_run_record_session_id on run_record(session_id);
create index if not exists idx_run_record_status on run_record(status);
