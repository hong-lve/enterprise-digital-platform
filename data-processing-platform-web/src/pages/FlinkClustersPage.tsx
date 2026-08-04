import { ClusterOutlined, DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Checkbox, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { createFlinkCluster, deleteFlinkCluster, listFlinkClusters, updateFlinkCluster, type FlinkClusterRecord } from '../api/flinkClusters';
import { useAuthStore } from '../store/auth';

export function FlinkClustersPage() {
  const [records, setRecords] = useState<FlinkClusterRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [editingId, setEditingId] = useState<number>();
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<Partial<FlinkClusterRecord>>();
  const mode = Form.useWatch('deploymentMode', form);
  const canManage = useAuthStore((state) => state.hasPermission('realtime:flink-cluster:manage'));

  const load = () => {
    setLoading(true);
    listFlinkClusters().then(setRecords).finally(() => setLoading(false));
  };
  useEffect(load, []);

  const edit = (record?: FlinkClusterRecord) => {
    setEditingId(record?.id);
    form.resetFields();
    form.setFieldsValue(record ?? { environment: 'DEV', deploymentMode: 'STANDALONE', enabled: true, defaultForEnvironment: false });
    setOpen(true);
  };

  const submit = (values: Partial<FlinkClusterRecord>) => {
    setSaving(true);
    (editingId ? updateFlinkCluster(editingId, values) : createFlinkCluster(values))
      .then(() => { message.success('已保存'); setOpen(false); load(); })
      .finally(() => setSaving(false));
  };

  return <div className="page-stack">
    <Space className="page-title" align="start">
      <div><Typography.Title level={3}><ClusterOutlined /> Flink 集群</Typography.Title></div>
      <Space>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
        {canManage && <Button type="primary" icon={<PlusOutlined />} onClick={() => edit()}>新建集群</Button>}
      </Space>
    </Space>
    <Table rowKey="id" loading={loading} dataSource={records} pagination={false} scroll={{ x: true }} columns={[
      { title: '名称', dataIndex: 'name' },
      { title: '环境', dataIndex: 'environment', width: 100, render: (v: string) => <Tag color={v === 'PROD' ? 'red' : v === 'STAGING' ? 'orange' : 'default'}>{v}</Tag> },
      { title: '部署模式', dataIndex: 'deploymentMode', width: 190, render: (v: string) => <Tag color={v === 'KUBERNETES_OPERATOR' ? 'blue' : 'default'}>{v}</Tag> },
      { title: '访问地址', render: (_, r: FlinkClusterRecord) => r.deploymentMode === 'KUBERNETES_OPERATOR' ? `${r.kubeApiUrl || '-'} / ${r.kubeNamespace || '-'}` : r.restUrl || '-' },
      { title: '默认', dataIndex: 'defaultForEnvironment', width: 80, render: (v: boolean) => v ? <Tag color="green">是</Tag> : '-' },
      { title: '状态', dataIndex: 'enabled', width: 90, render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag> },
      { title: '负责人', dataIndex: 'owner', width: 120, render: (v?: string) => v || '-' },
      { title: '操作', width: 120, render: (_, r: FlinkClusterRecord) => canManage && <Space>
        <Button aria-label="编辑" icon={<EditOutlined />} onClick={() => edit(r)} />
        <Popconfirm title="确认删除该集群？" onConfirm={() => deleteFlinkCluster(r.id).then(load)}><Button danger aria-label="删除" icon={<DeleteOutlined />} /></Popconfirm>
      </Space> }
    ]} />
    <Modal title={editingId ? '编辑 Flink 集群' : '新建 Flink 集群'} open={open} onCancel={() => setOpen(false)} onOk={() => form.submit()} confirmLoading={saving} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={submit}>
        <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
        <Space style={{ display: 'flex' }} align="start">
          <Form.Item name="environment" label="环境" rules={[{ required: true }]}><Select style={{ width: 150 }} options={['DEV', 'STAGING', 'PROD'].map((value) => ({ value }))} /></Form.Item>
          <Form.Item name="deploymentMode" label="部署模式" rules={[{ required: true }]}><Select style={{ width: 230 }} options={[{ value: 'STANDALONE' }, { value: 'KUBERNETES_OPERATOR' }]} /></Form.Item>
        </Space>
        {mode === 'KUBERNETES_OPERATOR' ? <>
          <Form.Item name="kubeApiUrl" label="Kubernetes API" rules={[{ required: true }]}><Input placeholder="https://kubernetes.default.svc" /></Form.Item>
          <Form.Item name="kubeNamespace" label="命名空间" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="kubeTokenEnv" label="令牌环境变量"><Input placeholder="K8S_API_TOKEN" /></Form.Item>
          <Form.Item name="flinkImage" label="Flink 镜像"><Input /></Form.Item>
          <Form.Item name="serviceAccount" label="ServiceAccount"><Input /></Form.Item>
        </> : <>
          <Form.Item name="restUrl" label="Flink REST 地址" rules={[{ required: true }]}><Input placeholder="http://flink-jobmanager:8081" /></Form.Item>
          <Form.Item name="sqlGatewayUrl" label="SQL Gateway 地址"><Input placeholder="http://flink-sql-gateway:8083" /></Form.Item>
        </>}
        <Space>
          <Form.Item name="defaultForEnvironment" valuePropName="checked"><Checkbox>设为该环境默认集群</Checkbox></Form.Item>
          <Form.Item name="enabled" valuePropName="checked"><Checkbox>启用</Checkbox></Form.Item>
        </Space>
        <Form.Item name="owner" label="负责人"><Input /></Form.Item>
      </Form>
    </Modal>
  </div>;
}
