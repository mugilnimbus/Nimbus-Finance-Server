ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'TRASHED'));
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN purge_after TIMESTAMPTZ;

ALTER TABLE registration_invites ADD COLUMN created_by_user_id UUID REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE finance_groups ALTER COLUMN created_by_user_id DROP NOT NULL;
ALTER TABLE finance_groups DROP CONSTRAINT finance_groups_created_by_user_id_fkey;
ALTER TABLE finance_groups ADD CONSTRAINT finance_groups_created_by_user_id_fkey FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE group_invites ALTER COLUMN created_by_user_id DROP NOT NULL;
ALTER TABLE group_invites DROP CONSTRAINT group_invites_created_by_user_id_fkey;
ALTER TABLE group_invites ADD CONSTRAINT group_invites_created_by_user_id_fkey FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE sync_entities ALTER COLUMN owner_user_id DROP NOT NULL;
ALTER TABLE sync_entities DROP CONSTRAINT sync_entities_owner_user_id_fkey;
ALTER TABLE sync_entities ADD CONSTRAINT sync_entities_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE change_log ALTER COLUMN actor_user_id DROP NOT NULL;
ALTER TABLE change_log ALTER COLUMN device_session_id DROP NOT NULL;
ALTER TABLE change_log DROP CONSTRAINT change_log_actor_user_id_fkey;
ALTER TABLE change_log DROP CONSTRAINT change_log_device_session_id_fkey;
ALTER TABLE change_log ADD CONSTRAINT change_log_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE change_log ADD CONSTRAINT change_log_device_session_id_fkey FOREIGN KEY (device_session_id) REFERENCES device_sessions(id) ON DELETE SET NULL;

ALTER TABLE audit_events DROP CONSTRAINT audit_events_user_id_fkey;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
