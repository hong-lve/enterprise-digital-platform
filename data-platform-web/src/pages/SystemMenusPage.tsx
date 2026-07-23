import { DeleteOutlined, EditOutlined, MenuOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  buildMenuTree,
  createSystemMenu,
  deleteSystemMenu,
  listSystemMenus,
  updateSystemMenu,
  type SystemMenuRecord,
  type SystemMenuTreeNode
} from '../api/systemMenus';
import { useAuthStore } from '../store/auth';

export function SystemMenusPage() {
  const [menus, setMenus] = useState<SystemMenuRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SystemMenuRecord | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<Partial<SystemMenuRecord> & { visibleFlag?: boolean }>();
  const selectedType = Form.useWatch('type', form);
  const can = useAuthStore((state) => state.hasPermission);

  const tree = useMemo(() => buildMenuTree(menus), [menus]);
  const parentOptions = useMemo(
    () => [{ value: 0, label: '无（顶级）' }, ...menus.filter((menu) => menu.type === 'MENU').map((menu) => ({ value: menu.id, label: menu.title }))],
    [menus]
  );

  const load = () => {
    setLoading(true);
    listSystemMenus()
      .then(setMenus)
      .catch(() => message.error('加载菜单列表失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const openCreate = (parentId?: number) => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ parentId: parentId ?? 0, type: 'BUTTON', sortOrder: 0, visibleFlag: true });
    setModalOpen(true);
  };

  const openEdit = (record: SystemMenuRecord) => {
    setEditing(record);
    form.setFieldsValue({ ...record, visibleFlag: record.visible === 'Y' });
    setModalOpen(true);
  };

  const submit = (values: Partial<SystemMenuRecord> & { visibleFlag?: boolean }) => {
    setSaving(true);
    const { visibleFlag, ...rest } = values;
    const payload: Partial<SystemMenuRecord> = { ...rest, visible: visibleFlag ? 'Y' : 'N' };
    const request = editing ? updateSystemMenu(editing.id, payload) : createSystemMenu(payload);
    request
      .then(() => {
        message.success(editing ? '已保存' : '已新建');
        setModalOpen(false);
        load();
      })
      .catch((error) => message.error(error?.response?.data?.message || (editing ? '保存失败' : '新建失败')))
      .finally(() => setSaving(false));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><MenuOutlined /> 菜单管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            维护菜单/按钮的标题、排序、可见性和权限标识。新增菜单类型的条目只是登记元数据供角色分配 - 页面能否打开仍取决于前端是否已经写好对应路由，这里改不了实际页面。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          {can('system:menu:create') && <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate()}>新建</Button>}
        </Space>
      </Space>

      <Table<SystemMenuTreeNode>
        rowKey="id"
        loading={loading}
        dataSource={tree}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '标题', dataIndex: 'title' },
          { title: '类型', dataIndex: 'type', width: 90, align: 'center', render: (value: string) => <Tag color={value === 'MENU' ? 'blue' : 'default'}>{value === 'MENU' ? '菜单' : '按钮'}</Tag> },
          { title: '路径', dataIndex: 'path', render: (value?: string) => value || '-' },
          { title: '权限标识', dataIndex: 'permission', render: (value?: string) => value || '-' },
          { title: '排序', dataIndex: 'sortOrder', width: 70, align: 'center' },
          { title: '可见', dataIndex: 'visible', width: 70, align: 'center', render: (value: string) => (value === 'Y' ? <Tag color="green">是</Tag> : <Tag>否</Tag>) },
          {
            title: '操作',
            width: 220,
            render: (_, record) => (
              <Space size="small">
                {can('system:menu:create') && record.type === 'MENU' && (
                  <Button size="small" icon={<PlusOutlined />} onClick={() => openCreate(record.id)}>新建子项</Button>
                )}
                {can('system:menu:update') && <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>}
                {can('system:menu:delete') && (
                  <Popconfirm title="确定删除？（有子项/按钮需先删除）" onConfirm={() => deleteSystemMenu(record.id).then(() => { message.success('已删除'); load(); }).catch((error) => message.error(error?.response?.data?.message || '删除失败'))}>
                    <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                  </Popconfirm>
                )}
              </Space>
            )
          }
        ]}
      />

      <Modal title={editing ? '编辑菜单/按钮' : '新建菜单/按钮'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()} confirmLoading={saving} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="parentId" label="上级">
            <Select options={parentOptions} disabled={!!editing} />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select options={[{ value: 'MENU', label: '菜单（导航页面）' }, { value: 'BUTTON', label: '按钮（操作权限点）' }]} />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="permission" label="权限标识" extra="供 @PreAuthorize / 角色分配使用，例如 system:user:view">
            <Input placeholder="module:resource:action" />
          </Form.Item>
          {selectedType === 'MENU' && (
            <>
              <Form.Item name="path" label="前端路由路径" extra="需要前端已实现对应 <Route>，否则打开是空白页">
                <Input placeholder="/system/xxx" />
              </Form.Item>
              <Form.Item name="component" label="前端组件名">
                <Input placeholder="SystemXxxPage" />
              </Form.Item>
              <Form.Item name="icon" label="图标名（antd icon 组件名）">
                <Input placeholder="SettingOutlined" />
              </Form.Item>
            </>
          )}
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="visibleFlag" label="可见" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
