import { ClusterOutlined, DeleteOutlined, EditOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { AutoComplete, Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import {
  clearFlinkStreamJobSavepoint,
  createFlinkStreamJob,
  deleteFlinkStreamJob,
  pageFlinkStreamJobs,
  refreshFlinkStreamJobStatus,
  startFlinkStreamJob,
  stopFlinkStreamJob,
  updateFlinkStreamJob,
  type FlinkStreamJobRecord
} from '../api/flinkStreamJobs';
import { listFlinkJarEntryClasses, pageFlinkJars, type FlinkJarRecord } from '../api/flinkJars';
import { useAuthStore } from '../store/auth';

const statusColor: Record<string, string> = {
  DRAFT: 'default',
  RUNNING: 'green',
  FAILED: 'red',
  CANCELED: 'orange',
  FINISHED: 'gold'
};

const statusLabel: Record<string, string> = {
  DRAFT: '未启动',
  RUNNING: '运行中',
  FAILED: '失败',
  CANCELED: '已停止',
  FINISHED: '已结束'
};

// Logical tags only - see V9__environment_field.sql. Not a physical
// cluster distinction, purely UI labeling + the PROD RBAC gate below.
const environmentColor: Record<string, string> = {
  DEV: 'default',
  STAGING: 'orange',
  PROD: 'red'
};

export function FlinkStreamJobsPage() {
  const [jobs, setJobs] = useState<FlinkStreamJobRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [form] = Form.useForm<Partial<FlinkStreamJobRecord>>();
  const restartStrategy = Form.useWatch('restartStrategy', form);
  const can = useAuthStore((state) => state.hasPermission);
  const [jars, setJars] = useState<FlinkJarRecord[]>([]);
  const jarByPath = new Map(jars.map((item) => [item.storagePath, item]));
  const [entryClassOptions, setEntryClassOptions] = useState<string[]>([]);
  const jarPath = Form.useWatch('jarPath', form);

  const load = () => {
    setLoading(true);
    pageFlinkStreamJobs({ current: 1, pageSize: 100 })
      .then((data) => setJobs(data.records))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    pageFlinkJars({ current: 1, pageSize: 100 }).then((data) => setJars(data.records));
  }, []);

  // Re-scanned whenever the selected JAR changes, so "入口类" can suggest the
  // classes that JAR actually contains instead of requiring the exact
  // fully-qualified class name to be typed from memory.
  useEffect(() => {
    const jar = jarByPath.get(jarPath ?? '');
    if (!jar) {
      setEntryClassOptions([]);
      return;
    }
    listFlinkJarEntryClasses(jar.id).then(setEntryClassOptions).catch(() => setEntryClassOptions([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jarPath, jars]);

  const openCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({ parallelism: 1, checkpointIntervalMs: 10000, restartStrategy: 'FIXED_DELAY', restartAttempts: 3, restartDelaySeconds: 10, environment: 'DEV' });
    setModalOpen(true);
  };

  const openEdit = (record: FlinkStreamJobRecord) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const submit = (values: Partial<FlinkStreamJobRecord>) => {
    setSaving(true);
    const request = editingId ? updateFlinkStreamJob(editingId, values) : createFlinkStreamJob(values);
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
          <Typography.Title level={3}><ClusterOutlined /> Flink 流作业</Typography.Title>
          <Typography.Paragraph type="secondary">
            长驻运行的 Flink 流处理作业，正常状态是一直运行；停止会保存 Savepoint，下次启动自动从上次的地方续跑。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建流作业</Button>
        </Space>
      </Space>

      <Table<FlinkStreamJobRecord>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={jobs}
        pagination={false}
        scroll={{ x: 1340 }}
        tableLayout="fixed"
        columns={[
          { title: '名称', dataIndex: 'name', ellipsis: true, width: 110 },
          {
            title: 'JAR 包',
            dataIndex: 'jarPath',
            ellipsis: true,
            width: 160,
            render: (value: string) => <Tooltip title={value}>{jarByPath.get(value)?.name || value}</Tooltip>
          },
          { title: '并行度', dataIndex: 'parallelism', width: 70, align: 'center' },
          { title: 'Checkpoint 间隔', dataIndex: 'checkpointIntervalMs', width: 130, align: 'center', render: (value: number) => `${value}ms` },
          {
            title: '状态',
            dataIndex: 'status',
            width: 150,
            align: 'center',
            render: (value: string, record) => (
              <Space size={4}>
                <Tooltip title={record.lastError}>
                  <Tag color={statusColor[value] || 'default'}>{statusLabel[value] || value}</Tag>
                </Tooltip>
                {record.savepointPath && (
                  <Tooltip title={`下次启动会从 ${record.savepointPath} 续跑`}>
                    <Tag color="blue">有保存点</Tag>
                  </Tooltip>
                )}
              </Space>
            )
          },
          {
            title: '反压',
            dataIndex: 'backpressureRatio',
            width: 76,
            align: 'center',
            render: (value: number | undefined, record) => (
              <Tag color={record.backpressureAlertState === 'ALERTING' ? 'red' : value === undefined || value === null ? 'default' : 'green'}>
                {value === undefined || value === null ? '暂无数据' : `${Math.round(value * 100)}%`}
              </Tag>
            )
          },
          {
            title: '消费延迟',
            dataIndex: 'consumerLagRecords',
            width: 92,
            align: 'center',
            render: (value: number | undefined, record) => (
              !record.kafkaConsumerGroupId ? (
                <Tag>未配置</Tag>
              ) : (
                <Tag color={record.consumerLagAlertState === 'ALERTING' ? 'red' : value === undefined || value === null ? 'default' : 'green'}>
                  {value === undefined || value === null ? '暂无数据' : `${value} 条`}
                </Tag>
              )
            )
          },
          {
            title: '环境',
            dataIndex: 'environment',
            width: 80,
            align: 'center',
            render: (value: string) => <Tag color={environmentColor[value] || 'default'}>{value}</Tag>
          },
          { title: '负责人', dataIndex: 'owner', width: 84, render: (value?: string) => value || '-' },
          {
            title: '操作',
            width: 388,
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
                      <Button size="small" icon={<PauseCircleOutlined />} disabled={locked} loading={busyId === record.id} onClick={() => runAction(record.id, stopFlinkStreamJob, '已停止')}>停止</Button>
                    </Tooltip>
                  ) : (
                    <Tooltip title={lockedTip}>
                      <Button size="small" icon={<PlayCircleOutlined />} disabled={locked} loading={busyId === record.id} onClick={() => runAction(record.id, startFlinkStreamJob, '已启动')}>启动</Button>
                    </Tooltip>
                  )}
                  <Button size="small" icon={<ReloadOutlined />} loading={busyId === record.id} onClick={() => runAction(record.id, refreshFlinkStreamJobStatus, '状态已刷新')}>状态</Button>
                  {record.savepointPath && (
                    <Tooltip title={lockedTip}>
                      <Popconfirm title="清除保存点？下次启动将从头开始，不会续跑" disabled={locked} onConfirm={() => runAction(record.id, clearFlinkStreamJobSavepoint, '已清除保存点')}>
                        <Button size="small" disabled={locked}>清除保存点</Button>
                      </Popconfirm>
                    </Tooltip>
                  )}
                  <Tooltip title={lockedTip}>
                    <Popconfirm title="确定删除这个流作业？运行中会先尝试停止" disabled={locked} onConfirm={() => runAction(record.id, deleteFlinkStreamJob, '已删除')}>
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
        title={editingId ? '编辑 Flink 流作业' : '新建 Flink 流作业'}
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
            name="jarPath"
            label="JAR 包"
            rules={[{ required: true, message: '请选择作业 JAR 包' }]}
            extra={jars.length ? undefined : '还没有上传过 JAR 包，请先去"JAR 包管理"页面上传'}
          >
            <Select
              placeholder="选择 JAR 包"
              options={jars.map((jar) => ({ value: jar.storagePath, label: jar.name }))}
            />
          </Form.Item>
          <Form.Item
            name="entryClass"
            label="入口类"
            extra={jarPath && entryClassOptions.length === 0 ? '未在该 JAR 中扫描到可执行的 main 方法，可手动填写' : undefined}
          >
            <AutoComplete
              options={entryClassOptions.map((className) => ({ value: className }))}
              placeholder="留空则使用 JAR 自带的 Main-Class"
              filterOption={(input, option) => (option?.value ?? '').toLowerCase().includes(input.toLowerCase())}
            />
          </Form.Item>
          <Form.Item name="programArgs" label="程序参数">
            <Input />
          </Form.Item>
          <Form.Item name="parallelism" label="并行度" rules={[{ required: true, message: '请输入并行度' }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="checkpointIntervalMs" label="Checkpoint 间隔(毫秒)" rules={[{ required: true, message: '请输入 Checkpoint 间隔' }]}>
            <InputNumber min={1000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="restartStrategy" label="重启策略" rules={[{ required: true, message: '请选择重启策略' }]}>
            <Select
              options={[
                { value: 'FIXED_DELAY', label: '固定间隔重试' },
                { value: 'NONE', label: '不自动重启' }
              ]}
            />
          </Form.Item>
          {restartStrategy === 'FIXED_DELAY' && (
            <>
              <Form.Item name="restartAttempts" label="最多重试次数" rules={[{ required: true, message: '请输入重试次数' }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="restartDelaySeconds" label="重试间隔(秒)" rules={[{ required: true, message: '请输入重试间隔' }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}
          <Form.Item
            name="kafkaConsumerGroupId"
            label="Kafka 消费组 ID"
            extra="可选——填了才会监控消费延迟。作业不消费 Kafka，或不需要监控，留空即可"
          >
            <Input placeholder="留空表示不监控消费延迟" />
          </Form.Item>
          <Form.Item
            name="kafkaTopics"
            label="Kafka Topic 列表"
            extra="逗号分隔，跟上面的消费组 ID 一起用，只统计这些 topic 的积压"
          >
            <Input placeholder="demo.data_platform_db.data_task_log" />
          </Form.Item>
          <Form.Item
            name="clickhouseSinkTables"
            label="ClickHouse 目标表"
            extra="可选，逗号分隔——这个作业写入的 ClickHouse 表，用于实时血缘视图"
          >
            <Input placeholder="task_execution_stats" />
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
