create table if not exists approval_request (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    session_id varchar(64) not null,
    agent_id varchar(128) not null,
    tool_name varchar(128) not null,
    arguments_json text,
    reason text,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_approval_request_run_id on approval_request(run_id);
create index if not exists idx_approval_request_status on approval_request(status);
