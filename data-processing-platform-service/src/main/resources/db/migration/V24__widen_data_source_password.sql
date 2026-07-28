-- data_source.password now stores AES-256-GCM ciphertext (base64: 12-byte IV
-- + ciphertext + 16-byte tag, see DataSourceCredentialTypeHandler) instead of
-- the plain text password itself, which needs more room than VARCHAR(200) -
-- a password near that length would no longer fit once base64-encoded with
-- the IV/tag overhead.
ALTER TABLE data_source MODIFY COLUMN password VARCHAR(500) NOT NULL;
