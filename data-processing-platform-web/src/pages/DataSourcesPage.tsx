import { ClusterOutlined, DeleteOutlined, EditOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { isPendingApproval } from '../api/approval';
import {
  createDataSource,
  deleteDataSource,
  pageDataSources,
  testDataSource,
  updateDataSource,
  type DataSourceRecord,
  type DataSourceType
} from '../api/dataSources';
import { useAuthStore } from '../store/auth';

const statusColor: Record<string, string> = {
  UNTESTED: 'default',
  OK: 'green',
  FAILED: 'red'
};

const statusLabel: Record<string, string> = {
  UNTESTED: '未测试',
  OK: '连接正常',
  FAILED: '连接失败'
};

const typeOptions: Array<{ value: DataSourceType; label: string }> = [
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'CLICKHOUSE', label: 'ClickHouse' },
  { value: 'DORIS', label: 'Doris' },
  { value: 'ORACLE', label: 'Oracle' },
];

// Logical tags only - see V9__environment_field.sql.
const environmentColor: Record<string, string> = {
  DEV: 'default',
  STAGING: 'orange',
  PROD: 'red'
};

export function DataSourcesPage() {
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [form] = Form.useForm<Partial<DataSourceRecord>>();
  const selectedType = Form.useWatch('type', form);
  const can = useAuthStore((state) => state.hasPermission);

  const load = () => {
    setLoading(true);
    pageDataSources({ current: 1, pageSize: 100 })
      .then((data) => setDataSources(data.records))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const openCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({ type: 'MYSQL', environment: 'DEV' });
    setModalOpen(true);
  };

  const openEdit = (record: DataSourceRecord) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const submit = (values: Partial<DataSourceRecord>) => {
    setSaving(true);
    const request = editingId ? updateDataSource(editingId, values) : createDataSource(values);
    request
      .then(() => {
        message.success(editingId ? '已保存' : '已新建');
        setModalOpen(false);
        load();
      })
      .finally(() => setSaving(false));
  };

  const runAction = (id: number, action: (id: number) => Promise<unknown>, successText: string) => {
    setBusyId(id);
    action(id)
      .then((result) => {
        if (isPendingApproval(result)) {
          message.info(`该资源属于生产环境，已提交审批（申请编号 #${result.approvalRequestId}），审批通过后才会生效`);
        } else {
          message.success(successText);
        }
        load();
      })
      .finally(() => setBusyId(null));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><ClusterOutlined /> 数据源配置</Typography.Title>
          <Typography.Paragraph type="secondary">
            统一管理 MySQL/ClickHouse/Doris/Oracle 连接，供 CDC 数据源、实时数据查询、SQL 流作业 sink 复用，不用各自手填/手写连接信息。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          {can('realtime:datasource:create') && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建数据源</Button>}
        </Space>
      </Space>

      <Table<DataSourceRecord>
        rowKey="id"
        loading={loading}
        dataSource={dataSources}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '名称', dataIndex: 'name', ellipsis: true },
          { title: '类型', dataIndex: 'type', width: 110, align: 'center', render: (value: DataSourceType) => <Tag>{typeOptions.find((option) => option.value === value)?.label || value}</Tag> },
          { title: '地址', dataIndex: 'host', render: (value: string, record) => `${value}:${record.port}` },
          {
            title: '默认库',
            dataIndex: 'databaseName',
            render: (value: string | undefined, record) => {
              // For Oracle, databaseName is the CDB root service (e.g. "FREE") -
              // still real and still required by the CDC connector, but not
              // where any actual schema/table lives. pdbName (e.g. "FREEPDB1")
              // is what buildJdbcUrl() on the backend actually connects
              // queries to, so that's the meaningful "default" to show here.
              const effective = record.type === 'ORACLE' && record.pdbName ? record.pdbName : value;
              return effective || '-';
            }
          },
          {
            title: 'Flink 可达地址',
            dataIndex: 'flinkHost',
            render: (value: string | undefined, record) => {
              if (!value || !record.flinkPort) return <Typography.Text type="secondary">未配置</Typography.Text>;
              const port = record.type === 'DORIS' ? record.flinkHttpPort : record.flinkPort;
              return `${value}:${port ?? record.flinkPort}`;
            }
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            align: 'center',
            render: (value: string, record) => (
              <Tooltip title={record.lastTestError}>
                <Tag color={statusColor[value] || 'default'}>{statusLabel[value] || value}</Tag>
              </Tooltip>
            )
          },
          {
            title: '环境',
            dataIndex: 'environment',
            width: 90,
            align: 'center',
            render: (value: string) => <Tag color={environmentColor[value] || 'default'}>{value}</Tag>
          },
          { title: '负责人', dataIndex: 'owner', render: (value?: string) => value || '-' },
          {
            title: '操作',
            width: 260,
            render: (_, record) => {
              const locked = record.environment === 'PROD' && !can('realtime:env:prod-operate');
              const lockedTip = locked ? '该记录属于生产环境，需要生产环境操作权限' : undefined;
              return (
                <Space size="small">
                  {can('realtime:datasource:test') && (
                    <Button size="small" icon={<PlayCircleOutlined />} loading={busyId === record.id} onClick={() => runAction(record.id, testDataSource, '测试完成')}>测试连接</Button>
                  )}
                  {can('realtime:datasource:update') && (
                    <Tooltip title={lockedTip}>
                      <Button size="small" icon={<EditOutlined />} disabled={locked} onClick={() => openEdit(record)}>编辑</Button>
                    </Tooltip>
                  )}
                  {can('realtime:datasource:delete') && (
                    <Tooltip title={lockedTip}>
                      <Popconfirm title="确定删除这个数据源？" disabled={locked} onConfirm={() => runAction(record.id, deleteDataSource, '已删除')}>
                        <Button size="small" danger icon={<DeleteOutlined />} disabled={locked}>删除</Button>
                      </Popconfirm>
                    </Tooltip>
                  )}
                </Space>
              );
            }
          }
        ]}
      />

      <Modal
        title={editingId ? '编辑数据源' : '新建数据源'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select options={typeOptions} />
          </Form.Item>
          {selectedType === 'ORACLE' && (
            <Typography.Paragraph type="secondary">
              Oracle 实例跑在本环境 Docker 里（gvenzl/oracle-free），CDC 数据源和 SQL 流作业 sink 都已经打通。作为 CDC 数据源时，"地址/端口"填这里的 JVM 能连到的地址（通常 localhost），"Flink 可达地址"填 Docker 网络里的服务名（oracle），"默认库"填 CDB 根服务名（如 FREE），下面的 PDB 名称填实际要监控的可插拔数据库（如 FREEPDB1）。
            </Typography.Paragraph>
          )}
          <Form.Item name="host" label="地址" rules={[{ required: true, message: '请输入地址' }]} extra="本服务自己进程能连到的地址">
            <Input placeholder="localhost" />
          </Form.Item>
          <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
            <InputNumber min={1} max={65535} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={editingId ? [] : [{ required: true, message: '请输入密码' }]}
            extra={editingId ? '出于安全考虑不回显现有密码；留空表示不修改' : undefined}
          >
            <Input.Password placeholder={editingId ? '留空则不修改' : undefined} />
          </Form.Item>
          <Form.Item name="databaseName" label="默认库" extra="查询页仍可现查现选其他库">
            <Input />
          </Form.Item>
          <Form.Item
            name="flinkHost"
            label="Flink 可达地址"
            extra="仅 SQL 流作业生成 sink 配置时用 - Flink 集群容器连这个数据源要走的地址（跟上面的地址不同才需要填，MySQL 通常不需要）"
          >
            <Input placeholder="clickhouse / doris" />
          </Form.Item>
          <Form.Item name="flinkPort" label="Flink 端口">
            <InputNumber min={1} max={65535} style={{ width: '100%' }} />
          </Form.Item>
          {selectedType === 'DORIS' && (
            <Form.Item name="flinkHttpPort" label="Flink HTTP 端口（Doris Stream Load）" extra="Doris 专属：FE HTTP/Stream Load 端口，跟上面的端口（FE MySQL 协议查询端口）不是一个">
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
          )}
          {selectedType === 'ORACLE' && (
            <Form.Item name="pdbName" label="PDB 名称" extra="Oracle CDB 架构专属：作为 CDC 数据源时，Debezium 需要知道具体监控哪个可插拔数据库（如 FREEPDB1），跟上面默认库填的 CDB 根服务名（如 FREE）不是一个">
              <Input placeholder="FREEPDB1" />
            </Form.Item>
          )}
          <Form.Item name="environment" label="环境" extra="逻辑标记，用于生产环境操作门禁，不代表独立的物理集群">
            <Select options={[{ value: 'DEV', label: 'DEV' }, { value: 'STAGING', label: 'STAGING' }, { value: 'PROD', label: 'PROD' }]} />
          </Form.Item>
          <Form.Item name="owner" label="负责人">
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
