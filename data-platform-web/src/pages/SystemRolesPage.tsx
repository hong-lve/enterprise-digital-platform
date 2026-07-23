import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SafetyOutlined, TeamOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tree, Typography, message } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useEffect, useMemo, useState } from 'react';
import { buildMenuTree, listSystemMenus, type SystemMenuRecord, type SystemMenuTreeNode } from '../api/systemMenus';
import {
  assignSystemRoleMenus,
  createSystemRole,
  deleteSystemRole,
  getSystemRoleMenus,
  listSystemRoles,
  updateSystemRole,
  type SystemRoleRecord
} from '../api/systemRoles';
import { useAuthStore } from '../store/auth';

const statusColor: Record<string, string> = { ENABLED: 'green', DISABLED: 'default' };
const statusLabel: Record<string, string> = { ENABLED: '启用', DISABLED: '停用' };

function toTreeData(nodes: SystemMenuTreeNode[]): DataNode[] {
  return nodes.map((node) => ({
    key: node.id,
    title: node.type === 'BUTTON' ? `${node.title}（${node.permission}）` : node.title,
    children: node.children.length ? toTreeData(node.children) : undefined
  }));
}

export function SystemRolesPage() {
  const [roles, setRoles] = useState<SystemRoleRecord[]>([]);
  const [menus, setMenus] = useState<SystemMenuRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SystemRoleRecord | null>(null);
  const [saving, setSaving] = useState(false);
  const [menuModalRole, setMenuModalRole] = useState<SystemRoleRecord | null>(null);
  const [menuModalLoading, setMenuModalLoading] = useState(false);
  const [checkedMenuIds, setCheckedMenuIds] = useState<number[]>([]);
  const [form] = Form.useForm();
  const can = useAuthStore((state) => state.hasPermission);

  const menuTreeData = useMemo(() => toTreeData(buildMenuTree(menus)), [menus]);

  const load = () => {
    setLoading(true);
    listSystemRoles()
      .then(setRoles)
      .catch(() => message.error('加载角色列表失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    listSystemMenus().then(setMenus).catch(() => message.error('加载菜单列表失败'));
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (record: SystemRoleRecord) => {
    setEditing(record);
    form.setFieldsValue({ roleKey: record.roleKey, name: record.name, description: record.description, status: record.status });
    setModalOpen(true);
  };

  const submit = (values: { roleKey?: string; name: string; description?: string; status?: string }) => {
    setSaving(true);
    const request = editing
      ? updateSystemRole(editing.id, { name: values.name, description: values.description, status: values.status })
      : createSystemRole({ roleKey: values.roleKey!, name: values.name, description: values.description });
    request
      .then(() => {
        message.success(editing ? '已保存' : '已新建');
        setModalOpen(false);
        load();
      })
      .catch((error) => message.error(error?.response?.data?.message || (editing ? '保存失败' : '新建失败')))
      .finally(() => setSaving(false));
  };

  const openMenuModal = (record: SystemRoleRecord) => {
    setMenuModalRole(record);
    setMenuModalLoading(true);
    getSystemRoleMenus(record.id)
      .then(setCheckedMenuIds)
      .catch(() => message.error('加载权限分配失败'))
      .finally(() => setMenuModalLoading(false));
  };

  const submitMenus = () => {
    if (!menuModalRole) return;
    setSaving(true);
    assignSystemRoleMenus(menuModalRole.id, checkedMenuIds)
      .then(() => {
        message.success('已保存');
        setMenuModalRole(null);
      })
      .catch(() => message.error('保存失败'))
      .finally(() => setSaving(false));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><TeamOutlined /> 角色管理</Typography.Title>
          <Typography.Paragraph type="secondary">管理角色，以及每个角色能看到的菜单和能操作的权限点。</Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          {can('system:role:create') && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建角色</Button>}
        </Space>
      </Space>

      <Table<SystemRoleRecord>
        rowKey="id"
        loading={loading}
        dataSource={roles}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '角色标识', dataIndex: 'roleKey' },
          { title: '角色名称', dataIndex: 'name' },
          { title: '描述', dataIndex: 'description', render: (value?: string) => value || '-' },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            align: 'center',
            render: (value: string) => <Tag color={statusColor[value] || 'default'}>{statusLabel[value] || value}</Tag>
          },
          {
            title: '操作',
            width: 240,
            render: (_, record) => (
              <Space size="small">
                {can('system:role:update') && <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>}
                {can('system:role:assign-menu') && <Button size="small" icon={<SafetyOutlined />} onClick={() => openMenuModal(record)}>分配权限</Button>}
                {can('system:role:delete') && record.roleKey !== 'ADMIN' && (
                  <Popconfirm title="确定删除这个角色？" onConfirm={() => deleteSystemRole(record.id).then(() => { message.success('已删除'); load(); }).catch((error) => message.error(error?.response?.data?.message || '删除失败'))}>
                    <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                  </Popconfirm>
                )}
              </Space>
            )
          }
        ]}
      />

      <Modal title={editing ? '编辑角色' : '新建角色'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()} confirmLoading={saving} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={submit}>
          {!editing && (
            <Form.Item name="roleKey" label="角色标识" extra="英文标识，创建后不可修改" rules={[{ required: true, message: '请输入角色标识' }]}>
              <Input placeholder="例如 OPERATOR" />
            </Form.Item>
          )}
          <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          {editing && (
            <Form.Item name="status" label="状态">
              <Select options={[{ value: 'ENABLED', label: '启用' }, { value: 'DISABLED', label: '停用' }]} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Modal
        title={`分配权限 - ${menuModalRole?.name ?? ''}`}
        open={!!menuModalRole}
        onCancel={() => setMenuModalRole(null)}
        onOk={submitMenus}
        confirmLoading={saving || menuModalLoading}
        destroyOnClose
        width={520}
      >
        <Tree
          checkable
          treeData={menuTreeData}
          checkedKeys={checkedMenuIds}
          onCheck={(checked) => setCheckedMenuIds((Array.isArray(checked) ? checked : checked.checked) as number[])}
        />
      </Modal>
    </div>
  );
}
