import { DeleteOutlined, EditOutlined, KeyOutlined, PlusOutlined, ReloadOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import {
  assignSystemUserRoles,
  createSystemUser,
  deleteSystemUser,
  getSystemUserRoles,
  pageSystemUsers,
  resetSystemUserPassword,
  updateSystemUser,
  type SystemUserRecord
} from '../api/systemUsers';
import { listSystemRoles, type SystemRoleRecord } from '../api/systemRoles';
import { useAuthStore } from '../store/auth';

const statusColor: Record<string, string> = { ENABLED: 'green', DISABLED: 'default' };
const statusLabel: Record<string, string> = { ENABLED: '启用', DISABLED: '停用' };

export function SystemUsersPage() {
  const [users, setUsers] = useState<SystemUserRecord[]>([]);
  const [roles, setRoles] = useState<SystemRoleRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SystemUserRecord | null>(null);
  const [saving, setSaving] = useState(false);
  const [passwordModalUser, setPasswordModalUser] = useState<SystemUserRecord | null>(null);
  const [roleModalUser, setRoleModalUser] = useState<SystemUserRecord | null>(null);
  const [roleModalLoading, setRoleModalLoading] = useState(false);
  const [form] = Form.useForm();
  const [passwordForm] = Form.useForm();
  const [roleForm] = Form.useForm<{ roleIds: number[] }>();
  const can = useAuthStore((state) => state.hasPermission);

  const load = () => {
    setLoading(true);
    pageSystemUsers({ current: 1, pageSize: 100 })
      .then((data) => {
        setUsers(data.records);
        setTotal(data.total);
      })
      .catch(() => message.error('加载用户列表失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    listSystemRoles().then(setRoles).catch(() => message.error('加载角色列表失败'));
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (record: SystemUserRecord) => {
    setEditing(record);
    form.setFieldsValue({ displayName: record.displayName, email: record.email, status: record.status });
    setModalOpen(true);
  };

  const submit = (values: { username?: string; displayName: string; email?: string; password?: string; roleIds?: number[]; status?: string }) => {
    setSaving(true);
    const request = editing
      ? updateSystemUser(editing.id, { displayName: values.displayName, email: values.email, status: values.status })
      : createSystemUser({
          username: values.username!,
          displayName: values.displayName,
          email: values.email,
          password: values.password!,
          roleIds: values.roleIds
        });
    request
      .then(() => {
        message.success(editing ? '已保存' : '已新建');
        setModalOpen(false);
        load();
      })
      .catch(() => message.error(editing ? '保存失败' : '新建失败'))
      .finally(() => setSaving(false));
  };

  const submitPassword = (values: { newPassword: string }) => {
    if (!passwordModalUser) return;
    setSaving(true);
    resetSystemUserPassword(passwordModalUser.id, values.newPassword)
      .then(() => {
        message.success('密码已重置');
        setPasswordModalUser(null);
      })
      .catch(() => message.error('重置密码失败'))
      .finally(() => setSaving(false));
  };

  const openRoleModal = (record: SystemUserRecord) => {
    setRoleModalUser(record);
    setRoleModalLoading(true);
    getSystemUserRoles(record.id)
      .then((roleIds) => roleForm.setFieldsValue({ roleIds }))
      .catch(() => message.error('加载角色分配失败'))
      .finally(() => setRoleModalLoading(false));
  };

  const submitRoles = (values: { roleIds: number[] }) => {
    if (!roleModalUser) return;
    setSaving(true);
    assignSystemUserRoles(roleModalUser.id, values.roleIds)
      .then(() => {
        message.success('已保存');
        setRoleModalUser(null);
        load();
      })
      .catch((error) => message.error(error?.response?.data?.message || '保存失败'))
      .finally(() => setSaving(false));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><UserOutlined /> 用户管理</Typography.Title>
          <Typography.Paragraph type="secondary">管理登录账号，以及每个账号持有的角色。</Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          {can('system:user:create') && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建用户</Button>}
        </Space>
      </Space>

      <Table<SystemUserRecord>
        rowKey="id"
        loading={loading}
        dataSource={users}
        pagination={{ total, pageSize: 100, hideOnSinglePage: true }}
        scroll={{ x: true }}
        columns={[
          { title: '用户名', dataIndex: 'username' },
          { title: '显示名称', dataIndex: 'displayName' },
          { title: '邮箱', dataIndex: 'email', render: (value?: string) => value || '-' },
          {
            title: '角色',
            dataIndex: 'roleNames',
            render: (value: string[]) => (value.length ? value.map((name) => <Tag key={name}>{name}</Tag>) : <Typography.Text type="secondary">无</Typography.Text>)
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            align: 'center',
            render: (value: string) => <Tag color={statusColor[value] || 'default'}>{statusLabel[value] || value}</Tag>
          },
          {
            title: '操作',
            width: 280,
            render: (_, record) => (
              <Space size="small">
                {can('system:user:update') && <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>}
                {can('system:user:assign-role') && <Button size="small" icon={<TeamOutlined />} onClick={() => openRoleModal(record)}>分配角色</Button>}
                {can('system:user:reset-password') && (
                  <Button size="small" icon={<KeyOutlined />} onClick={() => { setPasswordModalUser(record); passwordForm.resetFields(); }}>重置密码</Button>
                )}
                {can('system:user:delete') && (
                  <Popconfirm title="确定删除这个用户？" onConfirm={() => deleteSystemUser(record.id).then(() => { message.success('已删除'); load(); }).catch((error) => message.error(error?.response?.data?.message || '删除失败'))}>
                    <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                  </Popconfirm>
                )}
              </Space>
            )
          }
        ]}
      />

      <Modal title={editing ? '编辑用户' : '新建用户'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()} confirmLoading={saving} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={submit}>
          {!editing && (
            <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input />
            </Form.Item>
          )}
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true, message: '请输入显示名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }]}>
            <Input />
          </Form.Item>
          {!editing && (
            <>
              <Form.Item name="password" label="初始密码" rules={[{ required: true, message: '请输入初始密码' }]}>
                <Input.Password />
              </Form.Item>
              <Form.Item name="roleIds" label="初始角色">
                <Select mode="multiple" allowClear options={roles.map((role) => ({ value: role.id, label: role.name }))} />
              </Form.Item>
            </>
          )}
          {editing && (
            <Form.Item name="status" label="状态">
              <Select options={[{ value: 'ENABLED', label: '启用' }, { value: 'DISABLED', label: '停用' }]} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Modal title={`重置密码 - ${passwordModalUser?.username ?? ''}`} open={!!passwordModalUser} onCancel={() => setPasswordModalUser(null)} onOk={() => passwordForm.submit()} confirmLoading={saving} destroyOnClose>
        <Form form={passwordForm} layout="vertical" onFinish={submitPassword}>
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true, message: '请输入新密码' }]}>
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`分配角色 - ${roleModalUser?.username ?? ''}`} open={!!roleModalUser} onCancel={() => setRoleModalUser(null)} onOk={() => roleForm.submit()} confirmLoading={saving || roleModalLoading} destroyOnClose>
        <Form form={roleForm} layout="vertical" onFinish={submitRoles}>
          <Form.Item name="roleIds" label="角色" rules={[{ required: true, message: '至少保留一个角色' }]}>
            <Select mode="multiple" options={roles.map((role) => ({ value: role.id, label: role.name }))} loading={roleModalLoading} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
