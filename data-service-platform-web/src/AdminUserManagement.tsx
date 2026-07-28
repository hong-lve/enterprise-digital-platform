import {
  KeyOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  TeamOutlined
} from '@ant-design/icons';
import {
  Button,
  Checkbox,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  changeAdminUserStatus,
  createAdminUser,
  errorMessage,
  listAdminRoles,
  listAdminUsers,
  replaceAdminUserRoles,
  resetAdminUserPassword,
  type AdminRole,
  type AdminUser
} from './api';

interface Props {
  currentUser: AdminUser;
  canManage: boolean;
}

export default function AdminUserManagement({ currentUser, canManage }: Props) {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [roles, setRoles] = useState<AdminRole[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [roleUser, setRoleUser] = useState<AdminUser | null>(null);
  const [passwordUser, setPasswordUser] = useState<AdminUser | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [createForm] = Form.useForm();
  const [roleForm] = Form.useForm();
  const [passwordForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [userRows, roleRows] = await Promise.all([listAdminUsers(), listAdminRoles()]);
      setUsers(userRows);
      setRoles(roleRows);
    } catch (error) {
      message.error(errorMessage(error, '加载管理账号失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const create = async (values: {
    username: string;
    displayName: string;
    password: string;
    roleCodes: string[];
  }) => {
    setSubmitting(true);
    try {
      await createAdminUser(values);
      message.success('管理账号已创建');
      setCreateOpen(false);
      createForm.resetFields();
      await load();
    } catch (error) {
      message.error(errorMessage(error, '创建账号失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const saveRoles = async (values: { roleCodes: string[] }) => {
    if (!roleUser) return;
    setSubmitting(true);
    try {
      await replaceAdminUserRoles(roleUser.id, values.roleCodes);
      message.success('角色已更新');
      setRoleUser(null);
      await load();
    } catch (error) {
      message.error(errorMessage(error, '更新角色失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const resetPassword = async (values: { password: string }) => {
    if (!passwordUser) return;
    setSubmitting(true);
    try {
      await resetAdminUserPassword(passwordUser.id, values.password);
      message.success('密码已重置，该账号的现有会话已失效');
      setPasswordUser(null);
      passwordForm.resetFields();
    } catch (error) {
      message.error(errorMessage(error, '重置密码失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const changeStatus = async (user: AdminUser) => {
    try {
      await changeAdminUserStatus(user.id, user.status === 'ACTIVE' ? 'DISABLE' : 'ENABLE');
      message.success(user.status === 'ACTIVE' ? '账号已停用' : '账号已启用');
      await load();
    } catch (error) {
      message.error(errorMessage(error, '账号状态变更失败'));
    }
  };

  const openRoles = (user: AdminUser) => {
    setRoleUser(user);
    roleForm.setFieldsValue({ roleCodes: user.roles });
  };

  return (
    <>
      <div className="section-toolbar">
        <div>
          <Typography.Title level={5}>管理账号与角色</Typography.Title>
          <Typography.Text type="secondary">按岗位分配最小权限，停用或重置密码会撤销会话</Typography.Text>
        </div>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新增账号
          </Button>
        )}
      </div>
      <Table<AdminUser>
        rowKey="id"
        loading={loading}
        dataSource={users}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        columns={[
          {
            title: '账号',
            dataIndex: 'displayName',
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <span>{row.username}</span>
              </div>
            )
          },
          {
            title: '角色',
            dataIndex: 'roles',
            render: (values: string[]) => (
              <Space size={4} wrap>{values.map((value) => <Tag key={value}>{value}</Tag>)}</Space>
            )
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (value) => <Tag color={value === 'ACTIVE' ? 'success' : 'warning'}>{value}</Tag>
          },
          {
            title: '最近登录',
            dataIndex: 'lastLoginAt',
            width: 180,
            render: (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
          },
          {
            title: '操作',
            width: 270,
            render: (_, row) => canManage ? (
              <Space size={0}>
                <Button type="link" icon={<TeamOutlined />} onClick={() => openRoles(row)}>角色</Button>
                <Button type="link" icon={<KeyOutlined />} onClick={() => setPasswordUser(row)}>重置密码</Button>
                <Button
                  type="link"
                  danger={row.status === 'ACTIVE'}
                  icon={row.status === 'ACTIVE' ? <StopOutlined /> : <SafetyCertificateOutlined />}
                  disabled={row.id === currentUser.id}
                  onClick={() => changeStatus(row)}
                >
                  {row.status === 'ACTIVE' ? '停用' : '启用'}
                </Button>
              </Space>
            ) : '-'
          }
        ]}
      />

      <Modal
        title="新增管理账号"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical" onFinish={create}>
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="displayName" label="姓名" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item
            name="password"
            label="初始密码"
            rules={[{ required: true }, { min: 12, message: '密码至少 12 位' }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="roleCodes" label="角色" rules={[{ required: true, message: '至少选择一个角色' }]}>
            <Checkbox.Group className="role-checkboxes">
              {roles.map((role) => (
                <Checkbox key={role.code} value={role.code}>
                  <span>{role.name}</span>
                  <Typography.Text type="secondary">{role.description}</Typography.Text>
                </Checkbox>
              ))}
            </Checkbox.Group>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`分配角色 · ${roleUser?.displayName || ''}`}
        open={Boolean(roleUser)}
        onCancel={() => setRoleUser(null)}
        onOk={() => roleForm.submit()}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={roleForm} layout="vertical" onFinish={saveRoles}>
          <Form.Item name="roleCodes" label="角色" rules={[{ required: true, message: '至少选择一个角色' }]}>
            <Checkbox.Group className="role-checkboxes">
              {roles.map((role) => (
                <Checkbox key={role.code} value={role.code}>
                  <span>{role.name}</span>
                  <Typography.Text type="secondary">{role.description}</Typography.Text>
                </Checkbox>
              ))}
            </Checkbox.Group>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`重置密码 · ${passwordUser?.displayName || ''}`}
        open={Boolean(passwordUser)}
        onCancel={() => setPasswordUser(null)}
        onOk={() => passwordForm.submit()}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={passwordForm} layout="vertical" onFinish={resetPassword}>
          <Form.Item
            name="password"
            label="新密码"
            rules={[{ required: true }, { min: 12, message: '密码至少 12 位' }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
