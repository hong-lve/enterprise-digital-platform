-- Tier 3 item 4 of the reliability roadmap ("密钥版本化及轮换").
-- DataSourceCredentialCrypto used to hold exactly one AES-256-GCM key,
-- process-wide, with application.yml's own comment admitting "no
-- key-versioning/rotation support - rotating it makes every already-
-- encrypted data_source.password unreadable." This closes that gap: multiple
-- named key versions can be configured at once (old ones kept around
-- read-only, for decrypting rows not yet rotated), ciphertext is now tagged
-- with whichever version encrypted it, and rotation is an on-demand action
-- that re-encrypts every row under the currently-configured version. The key
-- material itself only ever lives in config/env vars - never in the
-- database - so this table is purely an audit trail of rotation runs, not a
-- key store.
--
-- No column widening needed: data_source.password (VARCHAR(500), see
-- V24__widen_data_source_password.sql) and sys_user_totp.secret_encrypted
-- (VARCHAR(255), see V26__two_factor_auth.sql) both already have plenty of
-- headroom for the few extra bytes a short "v1:" style version-tag prefix
-- adds on top of the existing base64 IV+ciphertext+tag blob.
CREATE TABLE encryption_key_rotation_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  to_version VARCHAR(20) NOT NULL,
  data_source_rows_reencrypted INT NOT NULL DEFAULT 0,
  totp_rows_reencrypted INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCEEDED/FAILED',
  error_message VARCHAR(500) NULL,
  triggered_by VARCHAR(64) NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME NULL
);
