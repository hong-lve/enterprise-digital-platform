import { DeleteOutlined, EditOutlined, FieldTimeOutlined, FunctionOutlined, HistoryOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Button, Card, Collapse, DatePicker, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import Editor, { type OnMount } from '@monaco-editor/react';
import { useEffect, useRef, useState } from 'react';
import type { Dayjs } from 'dayjs';
import { isPendingApproval } from '../api/approval';
import { JobVersionDrawer } from '../components/JobVersionDrawer';
import {
  clearFlinkSqlJobSavepoint,
  createFlinkSqlJob,
  deleteFlinkSqlJob,
  pageFlinkSqlJobs,
  refreshFlinkSqlJobStatus,
  replayFlinkSqlJob,
  rollbackFlinkSqlJob,
  startFlinkSqlJob,
  stopFlinkSqlJob,
  updateFlinkSqlJob,
  type FlinkSqlJobRecord,
  type ReplayMode
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

  // Out of the antd Form (which only really works with plain inputs) so it
  // can drive a Monaco editor instead of the old plain textarea - submit()
  // below merges this back into the values Form collects for the other fields.
  const [sqlScript, setSqlScript] = useState(EXAMPLE_SQL);
  const sqlEditorRef = useRef<Parameters<OnMount>[0] | null>(null);

  const [replayTarget, setReplayTarget] = useState<FlinkSqlJobRecord | null>(null);
  const [replayMode, setReplayMode] = useState<ReplayMode>('FROM_TIMESTAMP');
  const [replayTimestamp, setReplayTimestamp] = useState<Dayjs | null>(null);
  const [replaying, setReplaying] = useState(false);
  const [versionTarget, setVersionTarget] = useState<FlinkSqlJobRecord | null>(null);

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
    form.setFieldsValue({ parallelism: 1, checkpointIntervalMs: 60000, environment: 'DEV' });
    setSqlScript(EXAMPLE_SQL);
    resetWizard();
    setModalOpen(true);
  };

  const openEdit = (record: FlinkSqlJobRecord) => {
    setEditingId(record.id);
    form.setFieldsValue(record);
    setSqlScript(record.sqlScript ?? '');
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
        const inserted = sqlScript.trim() ? `${sqlScript}\n\n${result.ddl}` : result.ddl;
        setSqlScript(inserted);
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
        const inserted = sqlScript.trim() ? `${sqlScript}\n\n${ddl}` : ddl;
        setSqlScript(inserted);
        message.success('已插入到 SQL 编辑框末尾');
      })
      .finally(() => setSinkLoading(false));
  };

  const submit = (values: Partial<FlinkSqlJobRecord>) => {
    if (!sqlScript.trim()) {
      message.warning('请输入 SQL');
      return;
    }
    setSaving(true);
    const payload = { ...values, sqlScript };
    const request = editingId ? updateFlinkSqlJob(editingId, payload) : createFlinkSqlJob(payload);
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

  const openReplay = (record: FlinkSqlJobRecord) => {
    setReplayTarget(record);
    setReplayMode('FROM_TIMESTAMP');
    setReplayTimestamp(null);
  };

  const submitReplay = () => {
    if (!replayTarget) return;
    if (replayMode === 'FROM_TIMESTAMP' && !replayTimestamp) {
      message.warning('请选择重放起始时间');
      return;
    }
    setReplaying(true);
    replayFlinkSqlJob(replayTarget.id, replayMode, replayTimestamp?.valueOf())
      .then((result) => {
        if (isPendingApproval(result)) {
          message.info(`该资源属于生产环境，已提交审批（申请编号 #${result.approvalRequestId}），审批通过后才会生效`);
        } else {
          message.success('已提交重放');
        }
        setReplayTarget(null);
        load();
      })
      .finally(() => setReplaying(false));
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
            width: 490,
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
                  <Tooltip title={record.status === 'RUNNING' ? '运行中的作业请先停止再重放' : lockedTip}>
                    <Button size="small" icon={<HistoryOutlined />} disabled={locked || record.status === 'RUNNING'} onClick={() => openReplay(record)}>重放</Button>
                  </Tooltip>
                  <Button size="small" icon={<FieldTimeOutlined />} onClick={() => setVersionTarget(record)}>版本</Button>
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
        width={1360}
        afterOpenChange={(open) => {
          if (open) {
            sqlEditorRef.current?.layout();
          }
        }}
      >
        {/* Same reasoning as JAR 包管理's 在线编写/查看编辑代码 modals - the
            SQL is what someone actually reads/writes here, and it used to be
            one Form.Item buried in the middle of 13 stacked fields plus two
            wizard cards, in a 720px-wide modal. Sidebar for the fields
            (collapsing the six lineage/monitoring ones, which are optional
            and rarely touched per job), wizard cards + a real code editor
            filling the rest. */}
        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
          <div style={{ width: 300, flexShrink: 0 }}>
            <Form form={form} layout="vertical" onFinish={submit}>
              <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
                <Input />
              </Form.Item>
              <Form.Item name="parallelism" label="并行度" rules={[{ required: true, message: '请输入并行度' }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="checkpointIntervalMs" label="Checkpoint 间隔(毫秒)" rules={[{ required: true, message: '请输入 Checkpoint 间隔' }]}>
                <InputNumber min={1000} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="environment" label="环境" extra="逻辑标记，用于生产环境操作门禁，不代表独立的物理集群">
                <Select options={[{ value: 'DEV', label: 'DEV' }, { value: 'STAGING', label: 'STAGING' }, { value: 'PROD', label: 'PROD' }]} />
              </Form.Item>
              <Form.Item name="owner" label="负责人">
                <Input />
              </Form.Item>
              <Collapse
                ghost
                size="small"
                style={{ marginLeft: -12 }}
                items={[
                  {
                    key: 'advanced',
                    label: '监控 / 血缘（可选）',
                    children: (
                      <>
                        <Form.Item
                          name="kafkaConsumerGroupId"
                          label="Kafka 消费组 ID"
                          extra="填了才会监控消费延迟，需要跟 SQL 里 source 表的 'properties.group.id' 一致"
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
                        <Form.Item name="clickhouseSinkTables" label="ClickHouse 目标表" extra="逗号分隔——这个作业写入的 ClickHouse 表，用于实时血缘视图">
                          <Input placeholder="task_execution_stats" />
                        </Form.Item>
                        <Form.Item name="dorisSinkTables" label="Doris 目标表" extra="逗号分隔——这个作业写入的 Doris 表，用于实时血缘视图">
                          <Input placeholder="task_execution_stats" />
                        </Form.Item>
                        <Form.Item name="oracleSinkTables" label="Oracle 目标表" extra="逗号分隔——这个作业写入的 Oracle 表，用于实时血缘视图">
                          <Input placeholder="task_execution_stats" />
                        </Form.Item>
                      </>
                    )
                  }
                ]}
              />
            </Form>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Card size="small" title="从 CDC 数据源生成源表 SQL" style={{ marginBottom: 12 }}>
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
            <Card size="small" title="生成 sink 配置" style={{ marginBottom: 12 }}>
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
            <Typography.Text strong>SQL</Typography.Text>
            <div style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden', marginTop: 8 }}>
              <Editor
                height="440px"
                language="sql"
                value={sqlScript}
                onChange={(value) => setSqlScript(value ?? '')}
                onMount={(editor) => {
                  sqlEditorRef.current = editor;
                  editor.layout();
                }}
                options={{ minimap: { enabled: false }, fontSize: 13, automaticLayout: true }}
              />
            </div>
            <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
              若干 CREATE TABLE（Kafka 表）后跟恰好一条 INSERT INTO，INSERT INTO 必须是最后一条语句
            </Typography.Paragraph>
          </div>
        </div>
      </Modal>

      <Modal
        title={`重放 - ${replayTarget?.name ?? ''}`}
        open={!!replayTarget}
        onCancel={() => setReplayTarget(null)}
        onOk={submitReplay}
        confirmLoading={replaying}
        width={480}
      >
        <Typography.Paragraph type="secondary">
          让作业的 Kafka 源表从指定位置重新开始读取，而不是从消费组上次的位置续跑——用于重新处理某个历史时间窗口的数据（比如一次已修复 bug 影响的那段时间）。只改这一次提交，不会修改作业本身保存的 SQL。只对声明了 <code>'connector'='kafka'</code> 源表的作业有效。
        </Typography.Paragraph>
        <Form layout="vertical">
          <Form.Item label="重放方式">
            <Select
              value={replayMode}
              onChange={setReplayMode}
              options={[
                { value: 'FROM_TIMESTAMP', label: '从指定时间点开始' },
                { value: 'FROM_EARLIEST', label: '从最早开始（全量回溯）' }
              ]}
            />
          </Form.Item>
          {replayMode === 'FROM_TIMESTAMP' && (
            <Form.Item label="起始时间" required>
              <DatePicker showTime style={{ width: '100%' }} value={replayTimestamp} onChange={setReplayTimestamp} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <JobVersionDrawer
        entityType="FLINK_SQL_JOB"
        entityId={versionTarget?.id ?? null}
        entityName={versionTarget?.name}
        canRollback={can('realtime:sql-job:update')}
        onClose={() => setVersionTarget(null)}
        onRollback={(versionNo) =>
          rollbackFlinkSqlJob(versionTarget!.id, versionNo).then((result) => {
            if (isPendingApproval(result)) {
              message.info(`该资源属于生产环境，已提交审批（申请编号 #${result.approvalRequestId}），审批通过后才会生效`);
            } else {
              message.success(`已回滚至版本 ${versionNo}`);
            }
            load();
          })
        }
      />
    </div>
  );
}
