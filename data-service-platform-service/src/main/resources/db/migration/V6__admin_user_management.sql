INSERT INTO data_service_admin_permission (code, name) VALUES
  ('USER_READ', '查看管理账号'),
  ('USER_MANAGE', '管理账号与角色');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('USER_READ', 'USER_MANAGE')
WHERE role.code = 'SUPER_ADMIN';
