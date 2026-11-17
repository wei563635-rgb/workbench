-- Run with the sys_user table owner before deploying the account-reuse release.
-- The existing account uniqueness constraint covers deleted rows; replace it
-- with a unique index that covers only accounts available for login.
BEGIN;
SET LOCAL lock_timeout = '5s';
CREATE UNIQUE INDEX IF NOT EXISTS ux_sys_user_active_account
    ON sys_user (account) WHERE deleted_at IS NULL;
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS sys_user_account_key;
COMMIT;
