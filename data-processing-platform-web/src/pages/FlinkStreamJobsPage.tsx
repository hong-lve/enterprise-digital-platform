import { ClusterOutlined, DeleteOutlined, EditOutlined, FieldTimeOutlined, HistoryOutlined, MedicineBoxOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, AutoComplete, Button, Col, Collapse, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Row, Select, Space, Switch, Table, Tag, Tooltip, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { isPendingApproval } from '../api/approval';
import { RecoveryDrawer } from '../components/RecoveryDrawer';
import { JobVersionDrawer } from '../components/JobVersionDrawer';
import {
  clearFlinkStreamJobSavepoint,
  createFlinkStreamJob,
  deleteFlinkStreamJob,
  disposeFlinkStreamJobCheckpoint,
  fetchFlinkStreamJobCheckpoints,
  pageFlinkStreamJobs,
  refreshFlinkStreamJobStatus,
  rollbackFlinkStreamJob,
  startFlinkStreamJob,
  stopFlinkStreamJob,
  updateFlinkStreamJob,
  upgradeFlinkStreamJob,
  type FlinkCheckpointHistoryRecord,
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

const checkpointStatusColor: Record<string, string> = {
  COMPLETED: 'green',
  FAILED: 'red',
  IN_PROGRESS: 'blue'
};

function formatBytes(bytes?: number) {
  if (bytes === undefined || bytes === null) return '-';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unitIndex = -1;
  do {
    value /= 1024;
    unitIndex += 1;
  } while (value >= 1024 && unitIndex < units.length - 1);
  return `${value.toFixed(1)} ${units[unitIndex]}`;
}

function formatDuration(ms?: number) {
  if (ms === undefined || ms === null) return '-';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatTimestamp(epochMillis?: number) {
  if (!epochMillis) return '-';
  return new Date(epochMillis).toLocaleString();
}

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
  const [checkpointDrawerJob, setCheckpointDrawerJob] = useState<FlinkStreamJobRecord | null>(null);
  const [checkpointHistory, setCheckpointHistory] = useState<FlinkCheckpointHistoryRecord[]>([]);
  const [checkpointHistoryLoading, setCheckpointHistoryLoading] = useState(false);
  const [disposingId, setDisposingId] = useState<number | null>(null);
  const [recoveryTarget, setRecoveryTarget] = useState<FlinkStreamJobRecord | null>(null);
  const [versionTarget, setVersionTarget] = useState<FlinkStreamJobRecord | null>(null);
  const [editingStatus, setEditingStatus] = useState<string | null>(null);
  const isRollingUpgradeEdit = editingId !== null && editingStatus === 'RUNNING';

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
    setEditingStatus(null);
    form.resetFields();
    form.setFieldsValue({
      parallelism: 1,
      checkpointIntervalMs: 60000,
      restartStrategy: 'FIXED_DELAY',
      restartAttempts: 3,
      restartDelaySeconds: 10,
      environment: 'DEV',
      checkpointTimeoutMs: 600000,
      minPauseBetweenCheckpointsMs: 0,
      maxConcurrentCheckpoints: 1,
      tolerableFailedCheckpoints: 0,
      checkpointingMode: 'EXACTLY_ONCE',
      externalizedCheckpointRetention: 'RETAIN_ON_CANCELLATION',
      unalignedCheckpointsEnabled: false,
      savepointRetentionCount: 5
    });
    setModalOpen(true);
  };

  const openEdit = (record: FlinkStreamJobRecord) => {
    setEditingId(record.id);
    setEditingStatus(record.status);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const openCheckpoints = (record: FlinkStreamJobRecord) => {
    setCheckpointDrawerJob(record);
    setCheckpointHistoryLoading(true);
    fetchFlinkStreamJobCheckpoints(record.id)
      .then(setCheckpointHistory)
      .finally(() => setCheckpointHistoryLoading(false));
  };

  const disposeSavepoint = (checkpointId: number) => {
    if (!checkpointDrawerJob) return;
    setDisposingId(checkpointId);
    disposeFlinkStreamJobCheckpoint(checkpointDrawerJob.id, checkpointId)
      .then(() => {
        message.success('已删除保存点');
        return fetchFlinkStreamJobCheckpoints(checkpointDrawerJob.id).then(setCheckpointHistory);
      })
      .finally(() => setDisposingId(null));
  };

  const submit = (values: Partial<FlinkStreamJobRecord>) => {
    setSaving(true);
    const request = editingId
      ? (isRollingUpgradeEdit ? upgradeFlinkStreamJob(editingId, values) : updateFlinkStreamJob(editingId, values))
      : createFlinkStreamJob(values);
    request
      .then((result) => {
        if (isPendingApproval(result)) {
          message.info(`该资源属于生产环境，已提交审批（申请编号 #${result.approvalRequestId}），审批通过后才会生效`);
        } else {
          message.success(isRollingUpgradeEdit ? '已触发滚动升级：停止旧实例并从新保存点恢复' : (editingId ? '已保存' : '已新建'));
        }
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
        scroll={{ x: 1490 }}
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
            width: 610,
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
                  <Button size="small" icon={<HistoryOutlined />} onClick={() => openCheckpoints(record)}>Checkpoint</Button>
                  <Button size="small" icon={<MedicineBoxOutlined />} onClick={() => setRecoveryTarget(record)}>恢复</Button>
                  <Button size="small" icon={<FieldTimeOutlined />} onClick={() => setVersionTarget(record)}>版本</Button>
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
        title={editingId ? (isRollingUpgradeEdit ? '编辑并滚动升级 Flink 流作业' : '编辑 Flink 流作业') : '新建 Flink 流作业'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnClose
        width={780}
      >
        {/* Unlike JAR 包管理/SQL 流作业, there's no code/SQL content here to
            give room to - this modal is pure form, just too many fields
            (11-13 depending on 重启策略) stacked one-per-row in antd's
            default 520px width. Pairing the short, independent ones two to
            a row (Row/Col) cuts the scroll roughly in half without losing
            anything; the three monitoring/lineage fields collapse the same
            way SQL 流作业's equivalent ones do, since they're just as
            optional and rarely touched per job. */}
        {isRollingUpgradeEdit && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="该作业正在运行中"
            description="保存将触发滚动升级：先给旧实例创建保存点并停止，再用新配置从这个保存点恢复，中间会有短暂停机。"
          />
        )}
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
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="parallelism" label="并行度" rules={[{ required: true, message: '请输入并行度' }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="checkpointIntervalMs" label="Checkpoint 间隔(毫秒)" rules={[{ required: true, message: '请输入 Checkpoint 间隔' }]}>
                <InputNumber min={1000} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="restartStrategy" label="重启策略" rules={[{ required: true, message: '请选择重启策略' }]}>
                <Select
                  options={[
                    { value: 'FIXED_DELAY', label: '固定间隔重试' },
                    { value: 'NONE', label: '不自动重启' }
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="environment" label="环境" extra="逻辑标记，用于生产环境操作门禁，不代表独立的物理集群">
                <Select options={[{ value: 'DEV', label: 'DEV' }, { value: 'STAGING', label: 'STAGING' }, { value: 'PROD', label: 'PROD' }]} />
              </Form.Item>
            </Col>
          </Row>
          {restartStrategy === 'FIXED_DELAY' && (
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="restartAttempts" label="最多重试次数" rules={[{ required: true, message: '请输入重试次数' }]}>
                  <InputNumber min={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="restartDelaySeconds" label="重试间隔(秒)" rules={[{ required: true, message: '请输入重试间隔' }]}>
                  <InputNumber min={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          )}
          <Form.Item name="owner" label="负责人">
            <Input />
          </Form.Item>
          <Collapse
            ghost
            size="small"
            style={{ marginLeft: -12 }}
            items={[
              {
                key: 'checkpoint-governance',
                label: 'Checkpoint 治理（可选，留空使用 Flink 默认值）',
                children: (
                  <>
                    <Row gutter={16}>
                      <Col span={12}>
                        <Form.Item name="checkpointTimeoutMs" label="Checkpoint 超时(毫秒)">
                          <InputNumber min={1000} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item name="minPauseBetweenCheckpointsMs" label="最小间隔(毫秒)" extra="两次 checkpoint 之间至少间隔多久，避免连续触发">
                          <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={16}>
                      <Col span={12}>
                        <Form.Item name="maxConcurrentCheckpoints" label="最大并发数">
                          <InputNumber min={1} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item name="tolerableFailedCheckpoints" label="可容忍失败次数" extra="连续失败超过这个次数，Flink 会让作业失败">
                          <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={16}>
                      <Col span={12}>
                        <Form.Item name="checkpointingMode" label="一致性模式">
                          <Select options={[{ value: 'EXACTLY_ONCE', label: 'Exactly-once' }, { value: 'AT_LEAST_ONCE', label: 'At-least-once' }]} />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item name="externalizedCheckpointRetention" label="外部化保留策略" extra="作业取消后是否保留最后一次 checkpoint 文件">
                          <Select options={[{ value: 'RETAIN_ON_CANCELLATION', label: '取消后保留' }, { value: 'DELETE_ON_CANCELLATION', label: '取消后删除' }]} />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={16}>
                      <Col span={12}>
                        <Form.Item name="unalignedCheckpointsEnabled" label="Unaligned Checkpoint" valuePropName="checked" extra="反压严重时能加快 checkpoint 完成，但会增加 state 体积">
                          <Switch />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item name="savepointRetentionCount" label="保存点保留数量" extra="超过这个数量的旧保存点会被自动清理，当前恢复点永远不会被清理">
                          <InputNumber min={0} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                    </Row>
                  </>
                )
              },
              {
                key: 'advanced',
                label: '监控 / 血缘（可选）',
                children: (
                  <>
                    <Row gutter={16}>
                      <Col span={12}>
                        <Form.Item
                          name="kafkaConsumerGroupId"
                          label="Kafka 消费组 ID"
                          extra="填了才会监控消费延迟。作业不消费 Kafka，或不需要监控，留空即可"
                        >
                          <Input placeholder="留空表示不监控消费延迟" />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item
                          name="kafkaTopics"
                          label="Kafka Topic 列表"
                          extra="逗号分隔，跟左边的消费组 ID 一起用，只统计这些 topic 的积压"
                        >
                          <Input placeholder="demo.data_platform_db.data_task_log" />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Form.Item
                      name="clickhouseSinkTables"
                      label="ClickHouse 目标表"
                      extra="逗号分隔——这个作业写入的 ClickHouse 表，用于实时血缘视图"
                    >
                      <Input placeholder="task_execution_stats" />
                    </Form.Item>
                  </>
                )
              }
            ]}
          />
        </Form>
      </Modal>

      <Drawer
        title={`Checkpoint / 保存点 - ${checkpointDrawerJob?.name ?? ''}`}
        open={!!checkpointDrawerJob}
        onClose={() => setCheckpointDrawerJob(null)}
        width={860}
      >
        <Typography.Paragraph type="secondary">
          最近的 checkpoint/savepoint 历史（由后台定时从 Flink 同步）。保存点（类型为 SAVEPOINT）可以手动删除释放空间；当前作业下次启动会恢复到的那个保存点不能删除。
        </Typography.Paragraph>
        <Table<FlinkCheckpointHistoryRecord>
          rowKey="id"
          size="small"
          loading={checkpointHistoryLoading}
          dataSource={checkpointHistory}
          pagination={false}
          columns={[
            { title: 'ID', dataIndex: 'checkpointId', width: 60 },
            {
              title: '类型',
              dataIndex: 'checkpointType',
              width: 100,
              render: (value: string) => <Tag>{value}</Tag>
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (value: string) => <Tag color={checkpointStatusColor[value] || 'default'}>{value}</Tag>
            },
            { title: '触发时间', dataIndex: 'triggerTimestamp', width: 160, render: (value?: number) => formatTimestamp(value) },
            { title: '耗时', dataIndex: 'endToEndDurationMs', width: 80, render: (value?: number) => formatDuration(value) },
            { title: '状态大小', dataIndex: 'stateSizeBytes', width: 90, render: (value?: number) => formatBytes(value) },
            {
              title: '恢复验证',
              dataIndex: 'restoreOutcome',
              width: 90,
              render: (value?: string) => value ? <Tag color={value === 'VERIFIED' ? 'green' : 'red'}>{value === 'VERIFIED' ? '已验证可用' : '恢复失败'}</Tag> : '-'
            },
            {
              title: '失败原因',
              dataIndex: 'failureMessage',
              ellipsis: true,
              render: (value?: string) => value ? <Tooltip title={value}>{value}</Tooltip> : '-'
            },
            {
              title: '操作',
              width: 90,
              render: (_, entry) => {
                const isSavepoint = entry.checkpointType === 'SAVEPOINT' || entry.checkpointType === 'SYNC_SAVEPOINT';
                const isCurrent = !!entry.externalPath && entry.externalPath === checkpointDrawerJob?.savepointPath;
                if (!isSavepoint || !entry.externalPath) return '-';
                if (entry.disposed) return <Tag>已删除</Tag>;
                if (isCurrent) return <Tooltip title="当前恢复点，不能删除"><Button size="small" disabled>删除</Button></Tooltip>;
                if (!can('realtime:flink:checkpoint-manage')) return '-';
                return (
                  <Popconfirm title="确定删除这个保存点文件？删除后不能再用它恢复" onConfirm={() => disposeSavepoint(entry.checkpointId)}>
                    <Button size="small" danger loading={disposingId === entry.checkpointId}>删除</Button>
                  </Popconfirm>
                );
              }
            }
          ]}
        />
      </Drawer>

      <RecoveryDrawer
        entityType="FLINK_JOB"
        entityId={recoveryTarget?.id ?? null}
        entityName={recoveryTarget?.name}
        canManage={can('realtime:flink:recovery-manage')}
        onClose={() => setRecoveryTarget(null)}
      />
      <JobVersionDrawer
        entityType="FLINK_STREAM_JOB"
        entityId={versionTarget?.id ?? null}
        entityName={versionTarget?.name}
        canRollback={can('realtime:flink:update')}
        onClose={() => setVersionTarget(null)}
        onRollback={(versionNo) =>
          rollbackFlinkStreamJob(versionTarget!.id, versionNo).then((result) => {
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
