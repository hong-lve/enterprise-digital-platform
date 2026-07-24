import { DeleteOutlined, EditOutlined, FunctionOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { isPendingApproval } from '../api/approval';
import {
  clearFlinkSqlJobSavepoint,
  createFlinkSqlJob,
  deleteFlinkSqlJob,
  pageFlinkSqlJobs,
  refreshFlinkSqlJobStatus,
  startFlinkSqlJob,
  stopFlinkSqlJob,
  updateFlinkSqlJob,
  type FlinkSqlJobRecord
} from '../api/flinkSqlJobs';
import { generateCdcSourceTableDdl, generateSinkDdl, listCdcSourceTables, pageCdcSources, type CdcSourceRecord } from '../api/cdcSources';
import { pageDataSources, type DataSourceRecord } from '../api/dataSources';
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

const EXAMPLE_SQL = `CREATE TABLE task_log_src (
  task_id BIGINT,
  \`result\` STRING,
  message STRING
) WITH (
  'connector' = 'kafka',
  'topic' = 'demo.data_platform_db.data_task_log',
  'properties.bootstrap.servers' = 'kafka:9092',
  'scan.startup.mode' = 'earliest-offset',
  'format' = 'debezium-json',
  'debezium-json.schema-include' = 'true'
);

-- CDC 源是 changelog 流（INSERT/UPDATE/DELETE），sink 不能用普通 kafka
-- 连接器（只认 append-only），要用 upsert-kafka 并声明 PRIMARY KEY
CREATE TABLE task_log_sink (
  task_id BIGINT,
  \`result\` STRING,
  message STRING,
  PRIMARY KEY (task_id) NOT ENFORCED
) WITH (
  'connector' = 'upsert-kafka',
  'topic' = 'task-log-sink-example',
  'properties.bootstrap.servers' = 'kafka:9092',
  'key.format' = 'json',
  'value.format' = 'json'
);

INSERT INTO task_log_sink SELECT task_id, \`result\`, message FROM task_log_src;`;

export function FlinkSqlJobsPage() {
  const [jobs, setJobs] = useState<FlinkSqlJobRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [form] = Form.useForm<Partial<FlinkSqlJobRecord>>();
  const can = useAuthStore((state) => state.hasPermission);

  const [cdcSources, setCdcSources] = useState<CdcSourceRecord[]>([]);
  const [wizardSourceId, setWizardSourceId] = useState<number | null>(null);
  const [wizardTables, setWizardTables] = useState<string[]>([]);
  const [wizardTable, setWizardTable] = useState<string | null>(null);
  const [wizardLoading, setWizardLoading] = useState(false);

  const [sinkDataSources, setSinkDataSources] = useState<DataSourceRecord[]>([]);
  const [sinkTargetDataSourceId, setSinkTargetDataSourceId] = useState<number | null>(null);
  const [sinkTargetTable, setSinkTargetTable] = useState('');
  const [sinkLoading, setSinkLoading] = useState(false);

  const load = () => {
    setLoading(true);
    pageFlinkSqlJobs({ current: 1, pageSize: 100 })
      .then((data) => setJobs(data.records))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    pageCdcSources({ current: 1, pageSize: 100 }).then((data) => setCdcSources(data.records));
    pageDataSources({ current: 1, pageSize: 100 }).then((data) => setSinkDataSources(data.records));
  }, []);

  const resetWizard = () => {
    setWizardSourceId(null);
    setWizardTables([]);
    setWizardTable(null);
    setSinkTargetDataSourceId(null);
    setSinkTargetTable('');
  };

  const openCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({ sqlScript: EXAMPLE_SQL, parallelism: 1, checkpointIntervalMs: 10000, environment: 'DEV' });
    resetWizard();
    setModalOpen(true);
  };

  const openEdit = (record: FlinkSqlJobRecord) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    resetWizard();
    setModalOpen(true);
  };

  const selectWizardSource = (sourceId: number) => {
    setWizardSourceId(sourceId);
    setWizardTable(null);
    setWizardTables([]);
    listCdcSourceTables(sourceId).then(setWizardTables);
  };

  const generateAndInsert = () => {
    if (!wizardSourceId || !wizardTable) {
      return;
    }
    setWizardLoading(true);
    generateCdcSourceTableDdl(wizardSourceId, wizardTable)
      .then((result) => {
        const current = form.getFieldValue('sqlScript') as string | undefined;
        const inserted = current && current.trim() ? `${current}\n\n${result.ddl}` : result.ddl;
        form.setFieldsValue({ sqlScript: inserted });
        if (!result.hasPrimaryKey) {
          message.info('该表没有主键，只生成了源表，sink 需要自己写');
        } else {
          message.success('已插入到 SQL 编辑框末尾');
        }
      })
      .finally(() => setWizardLoading(false));
  };

  const generateSinkAndInsert = () => {
    if (!wizardSourceId || !wizardTable || !sinkTargetDataSourceId || !sinkTargetTable.trim()) {
      return;
    }
    setSinkLoading(true);
    generateSinkDdl(wizardSourceId, wizardTable, sinkTargetDataSourceId, sinkTargetTable.trim())
      .then((ddl) => {
        const current = form.getFieldValue('sqlScript') as string | undefined;
        const inserted = current && current.trim() ? `${current}\n\n${ddl}` : ddl;
        form.setFieldsValue({ sqlScript: inserted });
        message.success('已插入到 SQL 编辑框末尾');
      })
      .finally(() => setSinkLoading(false));
  };

  const submit = (values: Partial<FlinkSqlJobRecord>) => {
    setSaving(true);
    const request = editingId ? updateFlinkSqlJob(editingId, values) : createFlinkSqlJob(values);
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
          <Typography.Title level={3}><FunctionOutlined /> SQL 流作业</Typography.Title>
          <Typography.Paragraph type="secondary">
            用 SQL（若干 CREATE TABLE + 一条 INSERT INTO）定义常驻处理逻辑，不用写 Java 打 jar。
            数据源是 Kafka，目标可以是 Kafka（upsert-kafka）、ClickHouse（'connector'='jdbc'），
            也可以是 Doris（'connector'='doris'，原生支持 upsert/删除，CDC 原样同步更省事，不用先聚合）。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建 SQL 作业</Button>
        </Space>
      </Space>

      <Table<FlinkSqlJobRecord>
        rowKey="id"
        loading={loading}
        dataSource={jobs}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '名称', dataIndex: 'name', ellipsis: true },
          { title: '并行度', dataIndex: 'parallelism', width: 80 },
          { title: 'Checkpoint 间隔', dataIndex: 'checkpointIntervalMs', width: 130, render: (value: number) => `${value}ms` },
          {
            title: '状态',
            dataIndex: 'status',
            width: 190,
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
            width: 100,
            render: (value: number | undefined, record) => (
              <Tag color={record.backpressureAlertState === 'ALERTING' ? 'red' : value === undefined || value === null ? 'default' : 'green'}>
                {value === undefined || value === null ? '暂无数据' : `${Math.round(value * 100)}%`}
              </Tag>
            )
          },
          {
            title: '消费延迟',
            dataIndex: 'consumerLagRecords',
            width: 110,
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
            width: 90,
            render: (value: string) => <Tag color={environmentColor[value] || 'default'}>{value}</Tag>
          },
          { title: '负责人', dataIndex: 'owner', render: (value?: string) => value || '-' },
          {
            title: '操作',
            width: 340,
            render: (_, record) => {
              const locked = record.environment === 'PROD' && !can('realtime:env:prod-operate');
              const lockedTip = locked ? '该记录属于生产环境，需要生产环境操作权限' : undefined;
              return (
                <Space size="small">
                  <Tooltip title={lockedTip}>
                    <Button size="small" icon={<EditOutlined />} disabled={locked} onClick={() => openEdit(record)}>编辑</Button>
                  </Tooltip>
                  {record.status === 'RUNNING' ? (
                    <Tooltip title={lockedTip}>
                      <Button size="small" icon={<PauseCircleOutlined />} disabled={locked} loading={busyId === record.id} onClick={() => runAction(record.id, stopFlinkSqlJob, '已停止')}>停止</Button>
                    </Tooltip>
                  ) : (
                    <Tooltip title={lockedTip}>
                      <Button size="small" icon={<PlayCircleOutlined />} disabled={locked} loading={busyId === record.id} onClick={() => runAction(record.id, startFlinkSqlJob, '已启动')}>启动</Button>
                    </Tooltip>
                  )}
                  <Button size="small" icon={<ReloadOutlined />} loading={busyId === record.id} onClick={() => runAction(record.id, refreshFlinkSqlJobStatus, '状态已刷新')}>状态</Button>
                  {record.savepointPath && (
                    <Tooltip title={lockedTip}>
                      <Popconfirm title="清除保存点？下次启动将从头开始，不会续跑" disabled={locked} onConfirm={() => runAction(record.id, clearFlinkSqlJobSavepoint, '已清除保存点')}>
                        <Button size="small" disabled={locked}>清除保存点</Button>
                      </Popconfirm>
                    </Tooltip>
                  )}
                  <Tooltip title={lockedTip}>
                    <Popconfirm title="确定删除这个 SQL 作业？运行中会先尝试停止" disabled={locked} onConfirm={() => runAction(record.id, deleteFlinkSqlJob, '已删除')}>
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
        title={editingId ? '编辑 SQL 流作业' : '新建 SQL 流作业'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnClose
        width={720}
      >
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Card size="small" title="从 CDC 数据源生成源表 SQL" style={{ marginBottom: 16 }}>
            <Space wrap>
              <Select
                placeholder="选择 CDC 数据源"
                style={{ width: 220 }}
                value={wizardSourceId ?? undefined}
                options={cdcSources.map((source) => ({ value: source.id, label: source.name }))}
                onChange={selectWizardSource}
              />
              <Select
                placeholder="选择表"
                style={{ width: 220 }}
                value={wizardTable ?? undefined}
                disabled={!wizardSourceId}
                options={wizardTables.map((table) => ({ value: table, label: table }))}
                onChange={setWizardTable}
              />
              <Button icon={<ThunderboltOutlined />} disabled={!wizardTable} loading={wizardLoading} onClick={generateAndInsert}>
                生成并插入
              </Button>
            </Space>
          </Card>
          <Card size="small" title="生成 sink 配置" style={{ marginBottom: 16 }}>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
              选择上面同一个 CDC 表要写到的目标数据源和表名，生成对应的 sink 建表 + INSERT 语句追加到 SQL 末尾 - 字段跟上面生成的源表一一对应。
            </Typography.Paragraph>
            <Space wrap>
              <Select
                placeholder="选择目标数据源"
                style={{ width: 220 }}
                value={sinkTargetDataSourceId ?? undefined}
                options={sinkDataSources.map((item) => ({ value: item.id, label: `${item.name}（${item.type}）` }))}
                onChange={setSinkTargetDataSourceId}
              />
              <Input
                placeholder="目标表名"
                style={{ width: 200 }}
                value={sinkTargetTable}
                onChange={(event) => setSinkTargetTable(event.target.value)}
              />
              <Button
                icon={<ThunderboltOutlined />}
                disabled={!wizardTable || !sinkTargetDataSourceId || !sinkTargetTable.trim()}
                loading={sinkLoading}
                onClick={generateSinkAndInsert}
              >
                生成并插入
              </Button>
            </Space>
            {!wizardTable && <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>请先在上面选一个 CDC 数据源和表</Typography.Paragraph>}
          </Card>
          <Form.Item
            name="sqlScript"
            label="SQL"
            rules={[{ required: true, message: '请输入 SQL' }]}
            extra="若干 CREATE TABLE（Kafka 表）后跟恰好一条 INSERT INTO，INSERT INTO 必须是最后一条语句"
          >
            <Input.TextArea rows={16} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item name="parallelism" label="并行度" rules={[{ required: true, message: '请输入并行度' }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="checkpointIntervalMs" label="Checkpoint 间隔(毫秒)" rules={[{ required: true, message: '请输入 Checkpoint 间隔' }]}>
            <InputNumber min={1000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="kafkaConsumerGroupId"
            label="Kafka 消费组 ID"
            extra="可选——填了才会监控消费延迟，需要跟 SQL 里 source 表的 'properties.group.id' 一致"
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
          <Form.Item
            name="dorisSinkTables"
            label="Doris 目标表"
            extra="可选，逗号分隔——这个作业写入的 Doris 表，用于实时血缘视图"
          >
            <Input placeholder="task_execution_stats" />
          </Form.Item>
          <Form.Item
            name="oracleSinkTables"
            label="Oracle 目标表"
            extra="可选，逗号分隔——这个作业写入的 Oracle 表，用于实时血缘视图"
          >
            <Input placeholder="task_execution_stats" />
          </Form.Item>
          <Form.Item
            name="redisSinkTables"
            label="Redis 目标表"
            extra="可选，逗号分隔——这个作业写入的 Redis 表，用于实时血缘视图"
          >
            <Input placeholder="sql_job_demo_source" />
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
