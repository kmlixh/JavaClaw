create table if not exists knowledge_entry (
    id varchar(64) primary key,
    agent_id varchar(128) not null,
    title varchar(255) not null,
    content text not null,
    content_type varchar(64) not null default 'markdown',
    source varchar(64) not null default 'database',
    tags_json text,
    enabled boolean not null default true,
    version integer not null default 1,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create index if not exists idx_knowledge_entry_agent_enabled
    on knowledge_entry(agent_id, enabled, updated_at desc);

create table if not exists tool_definition (
    id varchar(64) primary key,
    agent_id varchar(128) not null,
    tool_name varchar(128) not null,
    display_name varchar(255) not null,
    description text,
    schema_json text,
    tool_type varchar(64) not null default 'builtin',
    config_json text,
    enabled boolean not null default true,
    approval_required boolean not null default false,
    version integer not null default 1,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create unique index if not exists uk_tool_definition_agent_tool
    on tool_definition(agent_id, tool_name);

create index if not exists idx_tool_definition_agent_enabled
    on tool_definition(agent_id, enabled, updated_at desc);

create table if not exists skill_definition (
    id varchar(64) primary key,
    agent_id varchar(128) not null,
    skill_name varchar(128) not null,
    description text,
    prompt_template text not null,
    config_json text,
    enabled boolean not null default true,
    version integer not null default 1,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create unique index if not exists uk_skill_definition_agent_skill
    on skill_definition(agent_id, skill_name);

create index if not exists idx_skill_definition_agent_enabled
    on skill_definition(agent_id, enabled, updated_at desc);
