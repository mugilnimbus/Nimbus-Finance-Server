CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    username CITEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 80),
    password_hash TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE registration_invites (
    id UUID PRIMARY KEY,
    code_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE TABLE device_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    access_token_hash TEXT NOT NULL UNIQUE,
    access_expires_at TIMESTAMPTZ NOT NULL,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX device_sessions_user_active_idx ON device_sessions(user_id, revoked_at);

CREATE TABLE finance_groups (
    id UUID PRIMARY KEY,
    created_by_user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE group_members (
    group_id UUID NOT NULL REFERENCES finance_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    server_seq BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX group_members_user_active_idx ON group_members(user_id, left_at, group_id);

CREATE TABLE group_invites (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES finance_groups(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL UNIQUE,
    created_by_user_id UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE TABLE sync_entities (
    entity_type TEXT NOT NULL,
    entity_id UUID NOT NULL,
    scope_type TEXT NOT NULL CHECK (scope_type IN ('PERSONAL', 'GROUP')),
    scope_id UUID NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    version BIGINT NOT NULL CHECK (version > 0),
    server_seq BIGINT NOT NULL DEFAULT 0,
    payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    PRIMARY KEY (entity_type, entity_id, scope_type, scope_id)
);
CREATE INDEX sync_entities_scope_idx ON sync_entities(scope_type, scope_id, server_seq);

CREATE TABLE change_log (
    server_seq BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    device_session_id UUID NOT NULL REFERENCES device_sessions(id),
    scope_type TEXT NOT NULL CHECK (scope_type IN ('PERSONAL', 'GROUP')),
    scope_id UUID NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('UPSERT', 'DELETE')),
    base_version BIGINT,
    server_version BIGINT NOT NULL,
    payload JSONB,
    result_status TEXT NOT NULL DEFAULT 'ACCEPTED',
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX change_log_scope_seq_idx ON change_log(scope_type, scope_id, server_seq);

CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    event_type TEXT NOT NULL,
    detail_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
