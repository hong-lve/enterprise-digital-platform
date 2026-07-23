-- Version history for flink_jar: re-uploading a jar under the same name
-- keeps every previously-uploaded file (and its physical storage path)
-- around so an older version can be restored, rather than clobbering it.
-- flink_jar itself keeps mirroring whichever version is currently active,
-- so every existing read path (job submission, download, jar dropdowns)
-- needs no changes.
CREATE TABLE flink_jar_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  jar_id BIGINT NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  stored_name VARCHAR(100) NOT NULL,
  storage_path VARCHAR(500) NOT NULL,
  size_bytes BIGINT NOT NULL,
  uploader VARCHAR(100),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_flink_jar_version_jar_id (jar_id)
);

-- Backfill: every existing flink_jar row's current file becomes its first
-- recorded version.
INSERT INTO flink_jar_version (jar_id, original_name, stored_name, storage_path, size_bytes, uploader, created_at)
SELECT id, original_name, stored_name, storage_path, size_bytes, uploader, created_at FROM flink_jar;
