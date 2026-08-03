import {
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  acknowledgeSloAlert,
  createSloRule,
  errorMessage,
  evaluateSloRule,
  evictApiCache,
  getRuntimeMetrics,
  listSloAlerts,
  listSloRules,
  resolveSloAlert,
  updateSloRule,
  type AlertEventRecord,
  type DataApiRecord,
  type RuntimeSnapshot,
  type SloRuleRecord
} from './api';

interface Props {
  apis: DataApiRecord[];
  canManage: boolean;
}

const circuitColors: Record<string, string> = {
  CLOSED: 'success',
  OPEN: 'error',
  HALF_OPEN: 'warning'
};

const alertColors: Record<string, string> = {
  OPEN: 'error',
  ACKNOWLEDGED: 'warning',
  RESOLVED: 'success'
};

export default function RuntimeGovernance({ apis, canManage }: Props) {
  const [snapshot, setSnapshot] = useState<RuntimeSnapshot | null>(null);
  const [rules, setRules] = useState<SloRuleRecord[]>([]);
  const [alerts, setAlerts] = useState<AlertEventRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [evicting, setEvicting] = useState<number | null>(null);
  const [evaluating, setEvaluating] = useState<number | null>(null);
  const [editingRule, setEditingRule] = useState<SloRuleRecord | null>(null);
  const [ruleModalOpen, setRuleModalOpen] = useState(false);
  const [form] = Form.useForm<SloRuleRecord>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [runtime, sloRules, sloAlerts] = await Promise.all([
        getRuntimeMetrics(),
        listSloRules(),
        listSloAlerts()
      ]);
      setSnapshot(runtime);
      setRules(sloRules);
      setAlerts(sloAlerts);
    } catch (error) {
      message.error(errorMessage(error, '加载运行治理数据失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, 10000);
    return () => window.clearInterval(timer);
  }, [load]);

  const rows = useMemo(() => apis.map((api) => {
    const circuit = snapshot?.resilience.circuits.find((item) => item.apiId === api.id);
    return {
      ...api,
      circuitStatus: circuit?.status || 'CLOSED',
      activeRequests: circuit?.activeRequests || 0,
      consecutiveFailures: circuit?.consecutiveFailures || 0,
      openUntil: circuit?.openUntil
    };
  }), [apis, snapshot]);

  const evict = async (apiId: number) => {
    setEvicting(apiId);
    try {
      await evictApiCache(apiId);
      message.success('API 缓存已失效');
      await load();
    } catch (error) {
      message.error(errorMessage(error, '清除缓存失败'));
    } finally {
      setEvicting(null);
    }
  };

  const openRuleModal = (rule?: SloRuleRecord) => {
    setEditingRule(rule || null);
    form.setFieldsValue(rule || {
      enabled: true,
      windowMinutes: 5,
      minRequests: 10,
      minSuccessRate: 99.9,
      maxP95Ms: 500
    } as SloRuleRecord);
    setRuleModalOpen(true);
  };

  const saveRule = async () => {
    try {
      const values = await form.validateFields();
      const payload = {
        apiId: values.apiId,
        name: values.name,
        enabled: values.enabled,
        windowMinutes: values.windowMinutes,
        minRequests: values.minRequests,
        minSuccessRate: values.minSuccessRate,
        maxP95Ms: values.maxP95Ms
      };
      if (editingRule) {
        await updateSloRule(editingRule.id, payload);
      } else {
        await createSloRule(payload);
      }
      message.success('SLO 规则已保存');
      setRuleModalOpen(false);
      setEditingRule(null);
      form.resetFields();
      await load();
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) {
        return;
      }
      message.error(errorMessage(error, '保存 SLO 规则失败'));
    }
  };

  const evaluate = async (ruleId: number) => {
    setEvaluating(ruleId);
    try {
      const result = await evaluateSloRule(ruleId);
      message.success(
        result.evaluated
          ? `评估完成，发现 ${result.breachCount} 项违约`
          : `样本不足，当前 ${result.statistics.sampleCount} 条`
      );
      await load();
    } catch (error) {
      message.error(errorMessage(error, 'SLO 评估失败'));
    } finally {
      setEvaluating(null);
    }
  };

  const updateAlert = async (id: number, action: 'ACK' | 'RESOLVE') => {
    try {
      if (action === 'ACK') {
        await acknowledgeSloAlert(id);
        message.success('告警已确认');
      } else {
        await resolveSloAlert(id);
        message.success('告警已恢复');
      }
      await load();
    } catch (error) {
      message.error(errorMessage(error, '更新告警失败'));
    }
  };

  const cache = snapshot?.cache;
  const resilience = snapshot?.resilience;

  return (
    <div>
      <div className="runtime-summary">
        <div>
          <span>Redis</span>
          <strong>
            <Tag color={cache?.redisAvailable ? 'success' : 'warning'} title={cache?.lastRedisError}>
              {cache?.redisAvailable ? '可用' : '本地降级'}
            </Tag>
          </strong>
        </div>
        <div><span>缓存命中率</span><strong>{cache ? `${(cache.hitRate * 100).toFixed(1)}%` : '-'}</strong></div>
        <div><span>命中 / 未命中</span><strong>{cache ? `${cache.hits} / ${cache.misses}` : '-'}</strong></div>
        <div><span>旧数据降级</span><strong>{cache?.staleFallbacks ?? '-'}</strong></div>
        <div><span>并发拒绝</span><strong>{resilience?.concurrencyRejected ?? '-'}</strong></div>
        <div><span>全局并发拒绝</span><strong>{resilience?.globalConcurrencyRejected ?? '-'}</strong></div>
        <div><span>熔断拒绝</span><strong>{resilience?.circuitRejected ?? '-'}</strong></div>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load} title="刷新运行指标" />
      </div>

      <Table
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          {
            title: 'API',
            dataIndex: 'name',
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <Typography.Text code>{row.path}</Typography.Text>
              </div>
            )
          },
          {
            title: '缓存',
            dataIndex: 'cacheTtlSeconds',
            width: 120,
            render: (value) => value ? `${value} 秒` : <Tag>未启用</Tag>
          },
          {
            title: '熔断状态',
            dataIndex: 'circuitStatus',
            width: 120,
            render: (value) => <Tag color={circuitColors[value]}>{value}</Tag>
          },
          { title: '当前并发', dataIndex: 'activeRequests', width: 100 },
          { title: '连续失败', dataIndex: 'consecutiveFailures', width: 100 },
          {
            title: '恢复时间',
            dataIndex: 'openUntil',
            width: 180,
            render: (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
          },
          {
            title: '操作',
            width: 130,
            render: (_, row) => canManage ? (
              <Button
                type="link"
                danger
                icon={<DeleteOutlined />}
                loading={evicting === row.id}
                onClick={() => evict(row.id)}
              >
                清除缓存
              </Button>
            ) : null
          }
        ]}
        locale={{ emptyText: '暂无 API 运行数据' }}
      />

      <div className="governance-heading">
        <Typography.Title level={5}>SLO 规则</Typography.Title>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openRuleModal()}>
            新建规则
          </Button>
        )}
      </div>
      <Table<SloRuleRecord>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={rules}
        pagination={false}
        columns={[
          { title: '规则', dataIndex: 'name' },
          {
            title: 'API',
            dataIndex: 'apiId',
            render: (id) => apis.find((api) => api.id === id)?.name || `#${id}`
          },
          { title: '窗口', dataIndex: 'windowMinutes', width: 90, render: (value) => `${value} 分钟` },
          { title: '最小样本', dataIndex: 'minRequests', width: 100 },
          { title: '成功率目标', dataIndex: 'minSuccessRate', width: 120, render: (value) => `≥ ${value}%` },
          { title: 'P95 目标', dataIndex: 'maxP95Ms', width: 110, render: (value) => `≤ ${value} ms` },
          { title: '状态', dataIndex: 'enabled', width: 90, render: (value) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag> },
          {
            title: '操作',
            width: 180,
            render: (_, row) => (
              <Space>
                <Button
                  type="text"
                  icon={<PlayCircleOutlined />}
                  loading={evaluating === row.id}
                  onClick={() => evaluate(row.id)}
                  title="立即评估"
                />
                {canManage && (
                  <Button type="text" icon={<EditOutlined />} onClick={() => openRuleModal(row)} title="编辑规则" />
                )}
              </Space>
            )
          }
        ]}
        locale={{ emptyText: '暂无 SLO 规则' }}
      />

      <div className="governance-heading">
        <Typography.Title level={5}>告警事件</Typography.Title>
      </div>
      <Table<AlertEventRecord>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={alerts}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        columns={[
          { title: '发生时间', dataIndex: 'openedAt', width: 170, render: (value) => new Date(value).toLocaleString('zh-CN', { hour12: false }) },
          {
            title: 'API',
            dataIndex: 'apiId',
            render: (id) => apis.find((api) => api.id === id)?.name || `#${id}`
          },
          { title: '类型', dataIndex: 'alertType', width: 130, render: (value) => <Tag>{value}</Tag> },
          { title: '状态', dataIndex: 'status', width: 130, render: (value) => <Tag color={alertColors[value]}>{value}</Tag> },
          { title: '观测值', dataIndex: 'observedValue', width: 100 },
          { title: '阈值', dataIndex: 'thresholdValue', width: 100 },
          { title: '样本', dataIndex: 'sampleCount', width: 80 },
          { title: '说明', dataIndex: 'message', ellipsis: true },
          {
            title: '操作',
            width: 120,
            render: (_, row) => canManage && row.status !== 'RESOLVED' ? (
              <Space>
                {row.status === 'OPEN' && (
                  <Button type="text" icon={<CheckOutlined />} onClick={() => updateAlert(row.id, 'ACK')} title="确认告警" />
                )}
                <Button type="text" icon={<CloseOutlined />} onClick={() => updateAlert(row.id, 'RESOLVE')} title="标记恢复" />
              </Space>
            ) : null
          }
        ]}
        locale={{ emptyText: '暂无告警事件' }}
      />

      <Modal
        title={editingRule ? '编辑 SLO 规则' : '新建 SLO 规则'}
        open={ruleModalOpen}
        onCancel={() => {
          setRuleModalOpen(false);
          setEditingRule(null);
        }}
        onOk={saveRule}
        okText="保存"
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item name="apiId" label="API" rules={[{ required: true, message: '请选择 API' }]}>
            <Select
              disabled={Boolean(editingRule)}
              options={apis.map((api) => ({ value: api.id, label: api.name }))}
            />
          </Form.Item>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input />
          </Form.Item>
          <div className="form-grid">
            <Form.Item name="windowMinutes" label="统计窗口（分钟）" rules={[{ required: true }]}>
              <InputNumber min={1} max={1440} />
            </Form.Item>
            <Form.Item name="minRequests" label="最小样本数" rules={[{ required: true }]}>
              <InputNumber min={1} max={1000000} />
            </Form.Item>
          </div>
          <div className="form-grid">
            <Form.Item name="minSuccessRate" label="最低成功率（%）" rules={[{ required: true }]}>
              <InputNumber min={0} max={100} step={0.1} />
            </Form.Item>
            <Form.Item name="maxP95Ms" label="最大 P95（ms）" rules={[{ required: true }]}>
              <InputNumber min={1} />
            </Form.Item>
          </div>
          <Form.Item name="enabled" label="启用规则" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
