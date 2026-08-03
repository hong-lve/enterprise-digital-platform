import {
  CheckCircleOutlined,
  CaretRightOutlined,
  EditOutlined,
  MinusCircleOutlined,
  PauseCircleOutlined,
  PlusOutlined,
  RocketOutlined,
  RollbackOutlined
} from '@ant-design/icons';
import {
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Progress,
  Select,
  Slider,
  Space,
  Switch,
  Table,
  Tag,
  Timeline,
  Typography,
  message
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  errorMessage,
  getApiRollouts,
  pauseApiRollout,
  promoteApiRollout,
  resumeApiRollout,
  rollbackApiRollout,
  startApiRollout,
  updateApiRollout,
  type ApiRolloutDetail,
  type ApiVersionRecord,
  type ApplicationRecord,
  type DataApiRecord
} from './api';

interface Props {
  api: DataApiRecord;
  versions: ApiVersionRecord[];
  applications: ApplicationRecord[];
  canRead: boolean;
  canManage: boolean;
  onChanged: () => Promise<void>;
}

interface RolloutForm {
  candidateVersionNo: number;
  percentage: number;
  applicationIds: number[];
  ipRulesText?: string;
  note?: string;
  automated: boolean;
  stages: Array<{ percentage: number; observationMinutes: number }>;
  minimumRequests: number;
  minimumSuccessRate: number;
  maximumErrorRate: number;
  maximumP95Ms: number;
  maximumP99Ms: number;
  failureAction: 'PAUSE' | 'ROLLBACK';
}

const rolloutStatusColor: Record<string, string> = {
  ACTIVE: 'processing',
  PAUSED: 'warning',
  PROMOTED: 'success',
  ROLLED_BACK: 'error'
};

export default function CanaryRolloutManagement({
  api,
  versions,
  applications,
  canRead,
  canManage,
  onChanged
}: Props) {
  const [detail, setDetail] = useState<ApiRolloutDetail>({
    rollouts: [],
    metrics: [],
    events: []
  });
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form] = Form.useForm<RolloutForm>();
  const percentage = Form.useWatch('percentage', form) ?? 0;
  const automated = Form.useWatch('automated', form) ?? false;

  const active = detail.rollouts.find((item) => ['ACTIVE', 'PAUSED'].includes(item.status));
  const candidates = versions.filter((version) => version.status === 'PENDING_APPROVAL');
  const applicationOptions = useMemo(
    () => applications
      .filter((application) =>
        application.status === 'ENABLED' && application.authorizedApiIds.includes(api.id)
      )
      .map((application) => ({
        value: application.id,
        label: `${application.name} (${application.appKey})`
      })),
    [api.id, applications]
  );

  const load = useCallback(async () => {
    if (!canRead) return;
    setLoading(true);
    try {
      setDetail(await getApiRollouts(api.id));
    } catch (error) {
      message.error(errorMessage(error, '加载灰度发布信息失败'));
    } finally {
      setLoading(false);
    }
  }, [api.id, canRead]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!canRead) {
    return null;
  }

  const openStart = (versionNo?: number) => {
    setEditing(false);
    form.setFieldsValue({
      candidateVersionNo: versionNo ?? candidates[0]?.versionNo,
      percentage: 5,
      applicationIds: [],
      ipRulesText: '',
      note: '',
      automated: true,
      stages: [
        { percentage: 5, observationMinutes: 10 },
        { percentage: 25, observationMinutes: 15 },
        { percentage: 50, observationMinutes: 20 },
        { percentage: 100, observationMinutes: 0 }
      ],
      minimumRequests: 100,
      minimumSuccessRate: 99,
      maximumErrorRate: 1,
      maximumP95Ms: 1000,
      maximumP99Ms: 2000,
      failureAction: 'PAUSE'
    });
    setModalOpen(true);
  };

  const openEdit = () => {
    if (!active) return;
    setEditing(true);
    form.setFieldsValue({
      candidateVersionNo: active.candidateVersionNo,
      percentage: active.percentage,
      applicationIds: active.applicationIds,
      ipRulesText: active.ipRules.join('\n'),
      note: active.note,
      automated: false
    });
    setModalOpen(true);
  };

  const save = async (values: RolloutForm) => {
    if (values.automated) {
      const stages = values.stages ?? [];
      const validStages = stages.length >= 2
        && stages.every((stage, index) =>
          stage.percentage > (index === 0 ? 0 : stages[index - 1].percentage)
          && stage.percentage <= 100
          && (index === stages.length - 1
            ? stage.observationMinutes >= 0
            : stage.observationMinutes >= 1)
        )
        && stages[stages.length - 1]?.percentage === 100;
      if (!validStages) {
        message.error('放量比例必须严格递增、最终为 100%，且观察阶段不能为 0 分钟');
        return;
      }
      if (values.maximumP99Ms < values.maximumP95Ms) {
        message.error('P99 耗时上限不能低于 P95');
        return;
      }
    }
    const payload = {
      percentage: values.percentage,
      applicationIds: values.applicationIds ?? [],
      ipRules: (values.ipRulesText ?? '')
        .split(/\r?\n|,/)
        .map((value) => value.trim())
        .filter(Boolean),
      note: values.note,
      stages: values.automated ? values.stages : undefined,
      healthPolicy: values.automated ? {
        minimumRequests: values.minimumRequests,
        minimumSuccessRate: values.minimumSuccessRate,
        maximumErrorRate: values.maximumErrorRate,
        maximumP95Ms: values.maximumP95Ms,
        maximumP99Ms: values.maximumP99Ms
      } : undefined,
      failureAction: values.automated ? values.failureAction : undefined
    };
    setLoading(true);
    try {
      if (editing && active) {
        await updateApiRollout(active.id, payload);
        message.success('灰度流量规则已更新');
      } else {
        await startApiRollout(api.id, {
          candidateVersionNo: values.candidateVersionNo,
          ...payload
        });
        message.success('灰度发布已启动');
      }
      setModalOpen(false);
      await Promise.all([load(), onChanged()]);
    } catch (error) {
      message.error(errorMessage(error, editing ? '更新灰度规则失败' : '启动灰度发布失败'));
    } finally {
      setLoading(false);
    }
  };

  const changePauseState = async () => {
    if (!active) return;
    try {
      if (active.status === 'PAUSED') {
        await resumeApiRollout(active.id);
        message.success('灰度发布已继续，观察窗口已重新计时');
      } else {
        await pauseApiRollout(active.id, '由管理员手动暂停');
        message.success('灰度发布已暂停');
      }
      await load();
    } catch (error) {
      message.error(errorMessage(error, active.status === 'PAUSED' ? '继续灰度失败' : '暂停灰度失败'));
    }
  };

  const finish = (action: 'PROMOTE' | 'ROLLBACK') => {
    if (!active) return;
    Modal.confirm({
      title: action === 'PROMOTE' ? '确认全量发布候选版本？' : '确认立即终止灰度？',
      content: action === 'PROMOTE'
        ? `v${active.candidateVersionNo} 将成为新的线上版本，原 v${active.baselineVersionNo} 会归档。`
        : `所有请求将立即恢复到 v${active.baselineVersionNo}，候选版本会归档。`,
      okText: action === 'PROMOTE' ? '全量发布' : '立即回滚',
      okButtonProps: { danger: action === 'ROLLBACK' },
      onOk: async () => {
        try {
          if (action === 'PROMOTE') {
            await promoteApiRollout(active.id);
            message.success('候选版本已全量发布');
          } else {
            await rollbackApiRollout(active.id);
            message.success('灰度已终止，流量已恢复稳定版');
          }
          await Promise.all([load(), onChanged()]);
        } catch (error) {
          message.error(errorMessage(error, action === 'PROMOTE' ? '全量发布失败' : '灰度回滚失败'));
        }
      }
    });
  };

  return (
    <section className="canary-rollouts">
      <div className="governance-heading">
        <div>
          <Typography.Title level={5}>灰度发布</Typography.Title>
          <Typography.Text type="secondary">按应用、IP 或稳定比例逐步放量</Typography.Text>
        </div>
        {canManage && !active && (
          <Button
            type="primary"
            icon={<RocketOutlined />}
            disabled={candidates.length === 0}
            onClick={() => openStart()}
          >
            启动灰度
          </Button>
        )}
      </div>

      {active ? (
        <div className="canary-active">
          <div className="canary-summary">
            <div>
              <Typography.Text type="secondary">当前流量</Typography.Text>
              <Progress
                percent={active.percentage}
                size="small"
                status="active"
                format={(value) => `${value}% 灰度`}
              />
            </div>
            <Descriptions size="small" column={3}>
              <Descriptions.Item label="稳定版本">v{active.baselineVersionNo}</Descriptions.Item>
              <Descriptions.Item label="候选版本">v{active.candidateVersionNo}</Descriptions.Item>
              <Descriptions.Item label="定向应用">{active.applicationIds.length}</Descriptions.Item>
              <Descriptions.Item label="IP 规则">{active.ipRules.length}</Descriptions.Item>
              <Descriptions.Item label="发布模式">{active.automated ? '自动推进' : '手动控制'}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={rolloutStatusColor[active.status]}>{active.status}</Tag>
              </Descriptions.Item>
              {active.automated && (
                <>
                  <Descriptions.Item label="当前阶段">
                    {active.currentStageIndex + 1}/{active.stages.length}
                  </Descriptions.Item>
                  <Descriptions.Item label="下次检查">
                    {active.nextEvaluationAt
                      ? new Date(active.nextEvaluationAt).toLocaleString('zh-CN', { hour12: false })
                      : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label="失败动作">
                    {active.failureAction === 'ROLLBACK' ? '自动回滚' : '自动暂停'}
                  </Descriptions.Item>
                </>
              )}
            </Descriptions>
          </div>
          {active.status === 'PAUSED' && active.pausedReason && (
            <div className="canary-pause-reason">
              <strong>暂停原因</strong>
              <span>{active.pausedReason}</span>
            </div>
          )}
          {canManage && (
            <Space className="canary-actions">
              {!active.automated && active.status === 'ACTIVE' && (
                <Button icon={<EditOutlined />} onClick={openEdit}>调整流量</Button>
              )}
              {active.automated && (
                <Button
                  icon={active.status === 'PAUSED' ? <CaretRightOutlined /> : <PauseCircleOutlined />}
                  onClick={changePauseState}
                >
                  {active.status === 'PAUSED' ? '继续发布' : '暂停发布'}
                </Button>
              )}
              <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => finish('PROMOTE')}>
                全量发布
              </Button>
              <Button danger icon={<RollbackOutlined />} onClick={() => finish('ROLLBACK')}>
                立即回滚
              </Button>
            </Space>
          )}
        </div>
      ) : (
        <div className="canary-empty">
          {candidates.length > 0 ? '存在待审批版本，可先用小流量验证后再全量发布。' : '当前没有活动灰度或待审批候选版本。'}
        </div>
      )}

      {active && (
        <>
          <Typography.Text strong className="canary-table-heading">实时对比</Typography.Text>
          <Table
            rowKey={(row) => row.variant}
            loading={loading}
            dataSource={detail.metrics}
            pagination={false}
            size="small"
            columns={[
              {
                title: '流量组',
                dataIndex: 'variant',
                render: (value) => <Tag color={value === 'CANARY' ? 'blue' : 'default'}>{value}</Tag>
              },
              { title: '版本', dataIndex: 'versionNo', render: (value) => `v${value}` },
              { title: '请求数', dataIndex: 'requestCount' },
              { title: '成功率', dataIndex: 'successRate', render: (value) => `${value}%` },
              { title: '平均耗时', dataIndex: 'averageElapsedMs', render: (value) => `${value} ms` },
              { title: '最大耗时', dataIndex: 'maximumElapsedMs', render: (value) => `${value} ms` }
            ]}
            locale={{ emptyText: '等待真实流量进入后生成对比数据' }}
          />
          {detail.health && active.automated && (
            <div className="canary-health-grid">
              <div><span>阶段样本</span><strong>{detail.health.requestCount}</strong></div>
              <div><span>成功率</span><strong>{detail.health.successRate}%</strong></div>
              <div><span>错误率</span><strong>{detail.health.errorRate}%</strong></div>
              <div><span>P95</span><strong>{detail.health.p95ElapsedMs} ms</strong></div>
              <div><span>P99</span><strong>{detail.health.p99ElapsedMs} ms</strong></div>
            </div>
          )}
        </>
      )}

      {detail.events.length > 0 && (
        <>
          <Typography.Text strong className="canary-table-heading">发布事件</Typography.Text>
          <Timeline
            className="canary-timeline"
            items={detail.events.map((event) => ({
              color: event.eventType.includes('ROLLBACK') ? 'red'
                : event.eventType.includes('PAUSED') ? 'orange'
                  : event.eventType.includes('PROMOTED') ? 'green' : 'blue',
              children: (
                <div className="canary-event">
                  <div>
                    <Tag>{event.eventType}</Tag>
                    {event.percentage !== undefined && <strong>{event.percentage}%</strong>}
                    <span>{event.message}</span>
                  </div>
                  <small>
                    {event.actor} · {new Date(event.occurredAt).toLocaleString('zh-CN', { hour12: false })}
                  </small>
                </div>
              )
            }))}
          />
        </>
      )}

      {detail.rollouts.length > 0 && (
        <>
          <Typography.Text strong className="canary-table-heading">发布记录</Typography.Text>
          <Table
            rowKey="id"
            loading={loading}
            dataSource={detail.rollouts}
            pagination={false}
            size="small"
            columns={[
              { title: '版本路径', render: (_, row) => `v${row.baselineVersionNo} → v${row.candidateVersionNo}` },
              { title: '灰度比例', dataIndex: 'percentage', render: (value) => `${value}%` },
              {
                title: '状态',
                dataIndex: 'status',
                render: (value) => <Tag color={rolloutStatusColor[value]}>{value}</Tag>
              },
              { title: '操作人', dataIndex: 'updatedBy' },
              {
                title: '更新时间',
                dataIndex: 'updatedAt',
                render: (value) => new Date(value).toLocaleString('zh-CN', { hour12: false })
              }
            ]}
          />
        </>
      )}

      <Modal
        title={editing ? '调整灰度流量' : '启动灰度发布'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={loading}
        width={680}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={save}>
          {!editing && (
            <>
              <Form.Item
                name="candidateVersionNo"
                label="候选版本"
                rules={[{ required: true, message: '请选择候选版本' }]}
              >
                <Select options={candidates.map((version) => ({
                  value: version.versionNo,
                  label: `v${version.versionNo} · ${version.changeSummary || '无变更说明'}`
                }))} />
              </Form.Item>
              <Form.Item name="automated" label="自动推进" valuePropName="checked">
                <Switch checkedChildren="自动" unCheckedChildren="手动" />
              </Form.Item>
            </>
          )}
          {!automated && (
            <Form.Item label="随机灰度比例" required>
              <Space.Compact block>
                <Slider
                  min={0}
                  max={99}
                  value={percentage}
                  onChange={(value) => form.setFieldValue('percentage', value)}
                  marks={{ 0: '0%', 5: '5%', 25: '25%', 50: '50%', 99: '99%' }}
                />
                <InputNumber
                  min={0}
                  max={99}
                  value={percentage}
                  addonAfter="%"
                  onChange={(value) => form.setFieldValue('percentage', value ?? 0)}
                />
              </Space.Compact>
            </Form.Item>
          )}
          {automated && (
            <div className="canary-automation-form">
              <Typography.Text strong>放量阶段</Typography.Text>
              <Form.List name="stages">
                {(fields, { add, remove }) => (
                  <div className="canary-stage-list">
                    {fields.map((field, index) => (
                      <div className="canary-stage-row" key={field.key}>
                        <span>阶段 {index + 1}</span>
                        <Form.Item
                          {...field}
                          name={[field.name, 'percentage']}
                          rules={[{ required: true, message: '请输入比例' }]}
                        >
                          <InputNumber min={1} max={100} addonAfter="%" />
                        </Form.Item>
                        <Form.Item
                          {...field}
                          name={[field.name, 'observationMinutes']}
                          rules={[{ required: true, message: '请输入观察时间' }]}
                        >
                          <InputNumber min={0} max={1440} addonAfter="分钟" />
                        </Form.Item>
                        <Button
                          type="text"
                          danger
                          icon={<MinusCircleOutlined />}
                          disabled={fields.length <= 2 || index === fields.length - 1}
                          title="删除阶段"
                          onClick={() => remove(field.name)}
                        />
                      </div>
                    ))}
                    <Button
                      type="dashed"
                      icon={<PlusOutlined />}
                      disabled={fields.length >= 10}
                      onClick={() => add(
                        { percentage: 75, observationMinutes: 20 },
                        Math.max(fields.length - 1, 0)
                      )}
                    >
                      添加阶段
                    </Button>
                  </div>
                )}
              </Form.List>
              <Typography.Text strong>健康门禁</Typography.Text>
              <div className="canary-policy-grid">
                <Form.Item name="minimumRequests" label="最小请求数" rules={[{ required: true }]}>
                  <InputNumber min={1} />
                </Form.Item>
                <Form.Item name="minimumSuccessRate" label="最低成功率" rules={[{ required: true }]}>
                  <InputNumber min={0} max={100} addonAfter="%" />
                </Form.Item>
                <Form.Item name="maximumErrorRate" label="最高错误率" rules={[{ required: true }]}>
                  <InputNumber min={0} max={100} addonAfter="%" />
                </Form.Item>
                <Form.Item name="maximumP95Ms" label="P95 上限" rules={[{ required: true }]}>
                  <InputNumber min={1} addonAfter="ms" />
                </Form.Item>
                <Form.Item name="maximumP99Ms" label="P99 上限" rules={[{ required: true }]}>
                  <InputNumber min={1} addonAfter="ms" />
                </Form.Item>
                <Form.Item name="failureAction" label="指标异常" rules={[{ required: true }]}>
                  <Select options={[
                    { value: 'PAUSE', label: '自动暂停' },
                    { value: 'ROLLBACK', label: '自动回滚' }
                  ]} />
                </Form.Item>
              </div>
            </div>
          )}
          <Form.Item name="applicationIds" label="定向应用">
            <Select
              mode="multiple"
              allowClear
              options={applicationOptions}
              placeholder="选中的应用始终进入候选版本"
            />
          </Form.Item>
          <Form.Item name="ipRulesText" label="定向 IP / CIDR">
            <Input.TextArea rows={3} placeholder={'每行一个，例如：\n10.20.8.15\n10.20.0.0/16'} />
          </Form.Item>
          <Form.Item name="note" label="发布说明">
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </section>
  );
}
