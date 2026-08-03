import { DeleteOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined, SafetyOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Button, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import {
  createDataQualityRule,
  deleteDataQualityRule,
  fetchDataQualityViolations,
  pageDataQualityRules,
  runDataQualityRule,
  updateDataQualityRule,
  type DataQualityRuleRecord,
  type DataQualityViolationRecord
} from '../api/dataQuality';
import { pageDataSources, type DataSourceRecord } from '../api/dataSources';
import { useAuthStore } from '../store/auth';

interface RuleFormValues {
  name: string;
  dataSourceId: number;
  databaseName?: string;
  tableName: string;
  ruleType: string;
  columnName: string;
  thresholdMin?: number;
  thresholdMax?: number;
}

const resultColor: Record<string, string> = { OK: 'green', VIOLATION: 'red', ERROR: 'orange' };
const resultLabel: Record<string, string> = { OK: '正常', VIOLATION: '违规', ERROR: '检查失败' };

const ruleTypeOptions = [
  { value: 'NULL_RATE', label: '空值率' },
  { value: 'UNIQUENESS', label: '唯一率' },
  { value: 'VALUE_RANGE', label: '值域' },
  { value: 'PK_DUPLICATE', label: '主键重复' },
  { value: 'FRESHNESS', label: '数据新鲜度' }
];

const ruleTypeLabel: Record<string, string> = Object.fromEntries(ruleTypeOptions.map((option) => [option.value, option.label]));

const columnFieldLabel: Record<string, string> = {
  NULL_RATE: '检查字段',
  UNIQUENESS: '检查字段',
  VALUE_RANGE: '检查字段',
  PK_DUPLICATE: '主键字段',
  FRESHNESS: '时间字段'
};

// Rules that produce a concrete list of offending values worth a separate drawer -
// NULL_RATE/UNIQUENESS/FRESHNESS are pure aggregate metrics with nothing discrete to list.
const violationCapableTypes = new Set(['VALUE_RANGE', 'PK_DUPLICATE']);

function formatMetric(record: DataQualityRuleRecord): string {
  if (record.lastMetricValue === undefined || record.lastMetricValue === null) return '-';
  if (record.ruleType === 'FRESHNESS') return `延迟 ${record.lastMetricValue.toFixed(0)} 秒`;
  if (record.ruleType === 'NULL_RATE' || record.ruleType === 'UNIQUENESS') return `${(record.lastMetricValue * 100).toFixed(2)}%`;
  return String(record.lastMetricValue);
}

export function DataQualityRulesPage() {
  const [records, setRecords] = useState<DataQualityRuleRecord[]>([]);
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<RuleFormValues>();
  const ruleType = Form.useWatch('ruleType', form);
  const can = useAuthStore((state) => state.hasPermission);
  const [violationTarget, setViolationTarget] = useState<DataQualityRuleRecord | null>(null);
  const [violations, setViolations] = useState<DataQualityViolationRecord[]>([]);
  const [violationsLoading, setViolationsLoading] = useState(false);

  const load = () => {
    setLoading(true);
    pageDataQualityRules({ current: 1, pageSize: 100 })
      .then((data) => setRecords(data.records))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    pageDataSources({ current: 1, pageSize: 200 }).then((data) => setDataSources(data.records.filter((item) => item.type !== 'REDIS')));
  }, []);

  const dataSourceName = (id: number) => dataSources.find((d) => d.id === id)?.name ?? `#${id}`;

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ ruleType: 'NULL_RATE', thresholdMax: 0.05 });
    setModalOpen(true);
  };

  const submit = (values: RuleFormValues) => {
    setSaving(true);
    createDataQualityRule(values)
      .then(() => {
        message.success('已创建，下一次调度会自动执行，也可以立即手动执行一次');
        setModalOpen(false);
        load();
      })
      .finally(() => setSaving(false));
  };

  const handleRun = (id: number) => {
    setBusyId(id);
    runDataQualityRule(id)
      .then((updated) => {
        message[updated.lastResult === 'OK' ? 'success' : 'warning'](
          updated.lastResult === 'ERROR' ? `检查失败：${updated.lastError}` : updated.lastResult === 'OK' ? '正常：未触发规则' : `发现违规：${formatMetric(updated)}`
        );
        load();
      })
      .finally(() => setBusyId(null));
  };

  const handleDelete = (id: number) => {
    setBusyId(id);
    deleteDataQualityRule(id)
      .then(() => {
        message.success('已删除');
        load();
      })
      .finally(() => setBusyId(null));
  };

  const handleToggleEnabled = (record: DataQualityRuleRecord, enabled: boolean) => {
    updateDataQualityRule(record.id, { ...record, enabled }).then(() => load());
  };

  const openViolations = (record: DataQualityRuleRecord) => {
    setViolationTarget(record);
    setViolationsLoading(true);
    fetchDataQualityViolations(record.id).then(setViolations).finally(() => setViolationsLoading(false));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><SafetyOutlined /> 数据质量规则</Typography.Title>
          <Typography.Paragraph type="secondary">
            检查单张表自身的数据质量——空值率、唯一率、值域、主键重复、数据新鲜度，跟"数据对账"的源/目标比对是两回事。每 2 分钟自动跑一遍已启用的规则，违规会记入告警历史；主键重复和值域越界的具体记录可以在"查看违规"里看到。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          {can('realtime:data-quality:create') && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建规则</Button>}
        </Space>
      </Space>

      <Table<DataQualityRuleRecord>
        rowKey="id"
        loading={loading}
        dataSource={records}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '名称', dataIndex: 'name', ellipsis: true },
          { title: '表', render: (_, record) => `${dataSourceName(record.dataSourceId)} / ${record.tableName}` },
          { title: '规则类型', dataIndex: 'ruleType', width: 100, render: (value: string) => ruleTypeLabel[value] ?? value },
          { title: '字段', dataIndex: 'columnName', width: 120 },
          {
            title: '状态',
            width: 90,
            render: (_, record) => record.lastResult ? <Tag color={resultColor[record.lastResult]}>{resultLabel[record.lastResult] ?? record.lastResult}</Tag> : <Tag>未执行</Tag>
          },
          { title: '最近指标值', width: 120, render: (_, record) => formatMetric(record) },
          { title: '最近检查时间', dataIndex: 'lastCheckedAt', width: 170, render: (value?: string) => value || '-' },
          {
            title: '启用',
            width: 80,
            render: (_, record) => (
              <Select
                size="small"
                value={record.enabled}
                style={{ width: 70 }}
                disabled={!can('realtime:data-quality:update')}
                onChange={(value) => handleToggleEnabled(record, value)}
                options={[{ value: true, label: '是' }, { value: false, label: '否' }]}
              />
            )
          },
          {
            title: '操作',
            width: 220,
            render: (_, record) => (
              <Space size="small">
                {can('realtime:data-quality:run') && (
                  <Button size="small" icon={<PlayCircleOutlined />} loading={busyId === record.id} onClick={() => handleRun(record.id)}>执行</Button>
                )}
                {violationCapableTypes.has(record.ruleType) && (
                  <Button size="small" icon={<UnorderedListOutlined />} onClick={() => openViolations(record)}>查看违规</Button>
                )}
                {can('realtime:data-quality:delete') && (
                  <Popconfirm title="确定删除这个规则？" onConfirm={() => handleDelete(record.id)}>
                    <Button size="small" danger icon={<DeleteOutlined />} loading={busyId === record.id}>删除</Button>
                  </Popconfirm>
                )}
              </Space>
            )
          }
        ]}
      />

      <Modal
        title="新建数据质量规则"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        width={560}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：订单表主键重复检查" />
          </Form.Item>
          <Form.Item name="dataSourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]} extra="暂不支持 Redis">
            <Select showSearch optionFilterProp="label" options={dataSources.map((d) => ({ value: d.id, label: `${d.name}（${d.type}）` }))} />
          </Form.Item>
          <Form.Item name="databaseName" label="数据库/schema（留空使用数据源默认值）">
            <Input placeholder="例如：cdc_demo" />
          </Form.Item>
          <Form.Item name="tableName" label="表名" rules={[{ required: true, message: '请输入表名' }]}>
            <Input placeholder="例如：test_orders_mysql" />
          </Form.Item>
          <Form.Item name="ruleType" label="规则类型" rules={[{ required: true, message: '请选择规则类型' }]}>
            <Select options={ruleTypeOptions} />
          </Form.Item>
          <Form.Item
            name="columnName"
            label={columnFieldLabel[ruleType] ?? '字段'}
            rules={[{ required: true, message: '请输入字段名' }]}
          >
            <Input placeholder="例如：id" />
          </Form.Item>
          {ruleType === 'VALUE_RANGE' && (
            <>
              <Form.Item name="thresholdMin" label="最小允许值（留空表示不限）">
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="thresholdMax" label="最大允许值（留空表示不限）">
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}
          {(ruleType === 'NULL_RATE' || ruleType === 'UNIQUENESS') && (
            <Form.Item
              name="thresholdMax"
              label={ruleType === 'NULL_RATE' ? '最大允许空值率' : '最大允许重复率'}
              extra="0-1 之间的小数，例如 0.05 表示 5%"
              rules={[{ required: true, message: '请输入阈值' }]}
            >
              <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
            </Form.Item>
          )}
          {ruleType === 'FRESHNESS' && (
            <Form.Item name="thresholdMax" label="最大允许延迟（秒）" rules={[{ required: true, message: '请输入阈值' }]}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Drawer title={`违规记录 - ${violationTarget?.name ?? ''}`} open={!!violationTarget} onClose={() => setViolationTarget(null)} width={600}>
        <Table<DataQualityViolationRecord>
          rowKey="id"
          size="small"
          loading={violationsLoading}
          dataSource={violations}
          pagination={false}
          locale={{ emptyText: '暂无违规记录' }}
          columns={[
            { title: '违规值', dataIndex: 'rowIdentifier' },
            { title: '详情', dataIndex: 'detail' },
            { title: '检测时间', dataIndex: 'detectedAt', width: 170 }
          ]}
        />
      </Drawer>
    </div>
  );
}
