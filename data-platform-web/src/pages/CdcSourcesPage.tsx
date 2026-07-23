import { DatabaseOutlined, DeleteOutlined, EditOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import {
  createCdcSource,
  deleteCdcSource,
  pageCdcSources,
  refreshCdcSourceStatus,
  startCdcSource,
  stopCdcSource,
  updateCdcSource,
  type CdcSourceRecord
} from '../api/cdcSources';
import { pageDataSources, type DataSourceRecord } from '../api/dataSources';
import { useAuthStore } from '../store/auth';

const statusColor: Record<string, string> = {
  DRAFT: 'default',
  RUNNING: 'green',
  PAUSED: 'orange',
  FAILED: 'red',
  UNKNOWN: 'default'
};

const statusLabel: Record<string, string> = {
  DRAFT: '未启动',
  RUNNING: '运行中',
  PAUSED: '已停止',
  FAILED: '失败',
  UNKNOWN: '未知'
};

// Logical tags only - see V9__environment_field.sql. Not a physical
// cluster distinction, purely UI labeling + the PROD RBAC gate below.
const environmentColor: Record<string, string> = {
  DEV: 'default',
  STAGING: 'orange',
  PROD: 'red'
};

// "距最近一条 CDC 消息过去了多久" - a freshness proxy, not true binlog-position
// lag (see CdcLagInspector.java for why). Idle periods with no MySQL writes
// look identical to a genuinely stuck connector under this metric.
function formatLag(seconds?: number): string {
  if (seconds === undefined || seconds === null) return '从未收到消息';
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  return `${Math.floor(seconds / 3600)} 小时前`;
}

export function CdcSourcesPage() {
  const [sources, setSources] = useState<CdcSourceRecord[]>([]);
  const [cdcDataSources, setCdcDataSources] = useState<DataSourceRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [form] = Form.useForm<Partial<CdcSourceRecord>>();
  const can = useAuthStore((state) => state.hasPermission);

  const dataSourceById = new Map(cdcDataSources.map((item) => [item.id, item]));

  const load = () => {
    setLoading(true);
    pageCdcSources({ current: 1, pageSize: 100 })
      .then((data) => setSources(data.records))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    pageDataSources({ current: 1, pageSize: 100 }).then((data) => setCdcDataSources(data.records.filter((item) => item.type === 'MYSQL' || item.type === 'ORACLE')));
  }, []);

  const openCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({ environment: 'DEV' });
    setModalOpen(true);
  };

  const openEdit = (record: CdcSourceRecord) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const submit = (values: Partial<CdcSourceRecord>) => {
    setSaving(true);
    const request = editingId ? updateCdcSource(editingId, values) : createCdcSource(values);
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
      .then(() => {
        message.success(successText);
        load();
      })
      .finally(() => setBusyId(null));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><DatabaseOutlined /> CDC 数据源</Typography.Title>
          <Typography.Paragraph type="secondary">
            配置要抓取 binlog 变更的 MySQL 库表，启动后由 Debezium 通过 Kafka Connect 把变更事件写入 Kafka。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建数据源</Button>
        </Space>
      </Space>

      <Table<CdcSourceRecord>
        rowKey="id"
        loading={loading}
        dataSource={sources}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '名称', dataIndex: 'name', ellipsis: true },
          {
            title: '数据源',
            dataIndex: 'dataSourceId',
            render: (value: number) => {
              const dataSource = dataSourceById.get(value);
              return dataSource ? `${dataSource.name}（${dataSource.host}:${dataSource.port}）` : `#${value}`;
            }
          },
          { title: '数据库/表', dataIndex: 'databaseName', render: (value: string, record) => <Tooltip title={record.tableIncludeList}>{value}</Tooltip> },
          { title: 'Topic 前缀', dataIndex: 'topicPrefix' },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            render: (value: string, record) => (
              <Tooltip title={record.lastError}>
                <Tag color={statusColor[value] || 'default'}>{statusLabel[value] || value}</Tag>
              </Tooltip>
            )
          },
          {
            title: '延迟',
            dataIndex: 'lagSeconds',
            width: 140,
            render: (value: number | undefined) => (
              // Not an alert signal - see CdcSourceStatusScheduler's checkLag()
              // comment: it can't tell a stuck connector apart from a source
              // table that just hasn't been written to in a while, so it's
              // shown as a plain neutral number, never highlighted red.
              <Tag>{formatLag(value)}</Tag>
            )
          },
          {
            title: '环境',
            dataIndex: 'environment',
            width: 90,
            render: (value: string) => <Tag color={environmentColor[value] || 'default'}>{value}</Tag>
          },
          { title: '负责人', dataIndex: 'owner', render: (value?: string) => value || '-' },
          {
            title: '操作',
            width: 260,
            render: (_, record) => {
              // PROD-tagged rows need realtime:env:prod-operate on top of the
              // usual per-action permission - see EnvironmentGuard.java.
              // Disabled+tooltip rather than hidden, matching this page's
              // existing Tooltip-heavy style for explaining row state.
              const locked = record.environment === 'PROD' && !can('realtime:env:prod-operate');
              const lockedTip = locked ? '该记录属于生产环境，需要生产环境操作权限' : undefined;
              return (
                <Space size="small">
                  <Tooltip title={lockedTip}>
                    <Button size="small" icon={<EditOutlined />} disabled={locked} onClick={() => openEdit(record)}>编辑</Button>
                  </Tooltip>
                  {record.status === 'RUNNING' ? (
                    <Tooltip title={lockedTip}>
                      <Button size="small" icon={<PauseCircleOutlined />} disabled={locked} loading={busyId === record.id} onClick={() => runAction(record.id, stopCdcSource, '已停止')}>停止</Button>
                    </Tooltip>
                  ) : (
                    <Tooltip title={lockedTip}>
                      <Button size="small" icon={<PlayCircleOutlined />} disabled={locked} loading={busyId === record.id} onClick={() => runAction(record.id, startCdcSource, '已启动')}>启动</Button>
                    </Tooltip>
                  )}
                  <Button size="small" icon={<ReloadOutlined />} loading={busyId === record.id} onClick={() => runAction(record.id, refreshCdcSourceStatus, '状态已刷新')}>状态</Button>
                  <Tooltip title={lockedTip}>
                    <Popconfirm title="确定删除这个数据源？" disabled={locked} onConfirm={() => runAction(record.id, deleteCdcSource, '已删除')}>
                      <Button size="small" danger icon={<DeleteOutlined />} disabled={locked}>删除</Button>
                    </Popconfirm>
                  </Tooltip>
                </Space>
              );
            }
          }
        ]}
      />

      <Modal
        title={editingId ? '编辑 CDC 数据源' : '新建 CDC 数据源'}
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
          <Form.Item
            name="dataSourceId"
            label="数据源"
            rules={[{ required: true, message: '请选择数据源' }]}
            extra={cdcDataSources.length ? 'Kafka Connect 跑在容器里，要连宿主机 MySQL 请在数据源配置里把地址填成 host.docker.internal，不要填 localhost；Oracle 数据源需要额外配置 Flink 可达地址和 PDB 名称' : '还没有 MySQL/Oracle 类型的数据源，请先去"数据源配置"页面新建一个'}
          >
            <Select
              placeholder="选择 MySQL/Oracle 数据源"
              options={cdcDataSources.map((item) => ({ value: item.id, label: `${item.name}（${item.type} ${item.host}:${item.port}）` }))}
            />
          </Form.Item>
          <Form.Item name="databaseName" label="数据库名" rules={[{ required: true, message: '请输入数据库名' }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="tableIncludeList"
            label="表清单"
            rules={[{ required: true, message: '请输入要捕获的表' }]}
            extra="逗号分隔，格式为 数据库名.表名，例如 data_platform_db.biz_item,data_platform_db.sys_data_source"
          >
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="topicPrefix" label="Topic 前缀" rules={[{ required: true, message: '请输入 Topic 前缀' }]} extra="生成的 Kafka topic 名是 前缀.数据库名.表名">
            <Input />
          </Form.Item>
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
