-- Extending the maker-checker gate (V29) beyond delete/stop on PROD
-- resources - role permission changes and user disable/password-reset are
-- always sensitive (no DEV/PROD distinction applies to them the way it does
-- to a CDC source), and unlike delete/stop these need to carry the
-- *proposed* new state forward until approved (the new menu ID list, the
-- new password hash), since there's nothing to re-derive from the target
-- row's current state alone.
ALTER TABLE change_request ADD COLUMN payload TEXT;
