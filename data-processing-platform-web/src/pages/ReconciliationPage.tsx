import { DeleteOutlined, DiffOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { pageDataSources, type DataSourceRecord } from '../api/dataSources';
import {
  createReconciliationCheck,
  deleteReconciliationCheck,
  pageReconciliationChecks,
  runReconciliationCheck,
  updateReconciliationCheck,
  type ReconciliationCheckRecord
} from '../api/reconciliation';
import { useAuthStore } from '../store/auth';

interface CheckFormValues {
  name: string;
  sourceDataSourceId: number;
  sourceDatabase?: string;
  sourceTable: string;
  targetDataSourceId: number;
  targetDatabase?: string;
  targetTable: string;
  tolerance: number;
  checkType: string;
  aggregateColumn?: string;
  partitionColumn?: string;
}

const stateColor: Record<string, string> = { OK: 'green', DRIFT: 'red', ERROR: 'orange' };
const stateLabel: Record<string, string> = { OK: '一致', DRIFT: '有差异', ERROR: '检查失败' };

export function ReconciliationPage() {
  const [records, setRecords] = useState<ReconciliationCheckRecord[]>([]);
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<CheckFormValues>();
  const checkType = Form.useWatch('checkType', form);
  const can = useAuthStore((state) => state.hasPermission);

  const load = () => {
    setLoading(true);
    pageReconciliationChecks({ current: 1, pageSize: 100 })
      .then((data) => setRecords(data.records))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    pageDataSources({ current: 1, pageSize: 200 }).then((data) => setDataSources(data.records));
  }, []);

  const dataSourceName = (id: number) => dataSources.find((d) => d.id === id)?.name ?? `#${id}`;

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ tolerance: 0, checkType: 'ROW_COUNT' });
    setModalOpen(true);
  };

  const submit = (values: CheckFormValues) => {
    setSaving(true);
    createReconciliationCheck(values)
      .then(() => {
        message.success('已创建，下一次调度会自动执行，也可以立即手动执行一次');
        setModalOpen(false);
        load();
      })
      .finally(() => setSaving(false));
  };

  const handleRun = (id: number) => {
    setBusyId(id);
    runReconciliationCheck(id)
      .then((updated) => {
        const isAggregate = updated.checkType === 'AGGREGATE';
        const comparison = isAggregate
          ? `源聚合值 ${updated.lastSourceAggregate} / 目标聚合值 ${updated.lastTargetAggregate}`
          : `源 ${updated.lastSourceCount} 行，目标 ${updated.lastTargetCount} 行`;
        message[updated.lastState === 'OK' ? 'success' : 'warning'](
          updated.lastState === 'OK' ? '一致：' + comparison : `${updated.lastState === 'ERROR' ? '检查失败：' + updated.lastError : '发现差异：' + comparison}`
        );
        load();
      })
      .finally(() => setBusyId(null));
  };

  const handleDelete = (id: number) => {
    setBusyId(id);
    deleteReconciliationCheck(id)
      .then(() => {
        message.success('已删除');
        load();
      })
      .finally(() => setBusyId(null));
  };

  const handleToggleEnabled = (record: ReconciliationCheckRecord, enabled: boolean) => {
    updateReconciliationCheck(record.id, { ...record, enabled }).then(() => load());
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><DiffOutlined /> 数据对账</Typography.Title>
          <Typography.Paragraph type="secondary">
            比对 CDC 源表和镜像目标的行数——连接器状态正常、消息也不落后，不代表中间没有消息被静默丢弃或写入失败，这个只能靠数量比对发现。每 2 分钟自动跑一遍已启用的任务，差异超过容忍值会记入告警历史。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          {can('realtime:reconciliation:create') && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建对账任务</Button>}
        </Space>
      </Space>

      <Table<ReconciliationCheckRecord>
        rowKey="id"
        loading={loading}
        dataSource={records}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '名称', dataIndex: 'name', ellipsis: true },
          {
            title: '源',
            render: (_, record) => `${dataSourceName(record.sourceDataSourceId)} / ${record.sourceTable}`
          },
          {
            title: '目标',
            render: (_, record) => `${dataSourceName(record.targetDataSourceId)} / ${record.targetTable}`
          },
          { title: '容忍差异', dataIndex: 'tolerance', width: 90, align: 'center' },
          {
            title: '类型',
            dataIndex: 'checkType',
            width: 90,
            render: (value?: string) => value === 'AGGREGATE' ? <Tag color="blue">聚合值</Tag> : <Tag>行数</Tag>
          },
          {
            title: '状态',
            width: 110,
            render: (_, record) => record.lastState ? <Tag color={stateColor[record.lastState]}>{stateLabel[record.lastState] ?? record.lastState}</Tag> : <Tag>未执行</Tag>
          },
          {
            title: '最近一次结果',
            width: 200,
            render: (_, record) => {
              if (!record.lastCheckedAt) return '-';
              const comparison = record.checkType === 'AGGREGATE'
                ? `源 ${record.lastSourceAggregate ?? '-'} / 目标 ${record.lastTargetAggregate ?? '-'}`
                : `源 ${record.lastSourceCount ?? '-'} / 目标 ${record.lastTargetCount ?? '-'}`;
              return (
                <>
                  <div>{comparison}</div>
                  {record.partitionDriftSummary && <div style={{ color: '#cf1322', fontSize: 12 }}>分区差异：{record.partitionDriftSummary}</div>}
                </>
              );
            }
          },
          { title: '最近检查时间', dataIndex: 'lastCheckedAt', width: 170, render: (value?: string) => value || '-' },
          {
            title: '启用',
            width: 80,
            render: (_, record) => (
              <Select
                size="small"
                value={record.enabled}
                style={{ width: 70 }}
                disabled={!can('realtime:reconciliation:update')}
                onChange={(value) => handleToggleEnabled(record, value)}
                options={[{ value: true, label: '是' }, { value: false, label: '否' }]}
              />
            )
          },
          {
            title: '操作',
            width: 160,
            render: (_, record) => (
              <Space size="small">
                {can('realtime:reconciliation:run') && (
                  <Button size="small" icon={<PlayCircleOutlined />} loading={busyId === record.id} onClick={() => handleRun(record.id)}>执行</Button>
                )}
                {can('realtime:reconciliation:delete') && (
                  <Popconfirm title="确定删除这个对账任务？" onConfirm={() => handleDelete(record.id)}>
                    <Button size="small" danger icon={<DeleteOutlined />} loading={busyId === record.id}>删除</Button>
                  </Popconfirm>
                )}
              </Space>
            )
          }
        ]}
      />

      <Modal
        title="新建对账任务"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        width={560}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：MySQL 订单 -> Redis 对账" />
          </Form.Item>
          <Typography.Text type="secondary">源（CDC 数据源指向的原始表）</Typography.Text>
          <Form.Item name="sourceDataSourceId" label="源数据源" rules={[{ required: true, message: '请选择源数据源' }]} style={{ marginTop: 8 }}>
            <Select
              showSearch
              optionFilterProp="label"
              options={dataSources.map((d) => ({ value: d.id, label: `${d.name}（${d.type}）` }))}
            />
          </Form.Item>
          <Form.Item name="sourceDatabase" label="源数据库/schema（留空使用数据源默认值）">
            <Input placeholder="例如：cdc_demo" />
          </Form.Item>
          <Form.Item name="sourceTable" label="源表名" rules={[{ required: true, message: '请输入源表名' }]}>
            <Input placeholder="例如：test_orders_mysql" />
          </Form.Item>
          <Typography.Text type="secondary">目标（镜像落地的位置）</Typography.Text>
          <Form.Item name="targetDataSourceId" label="目标数据源" rules={[{ required: true, message: '请选择目标数据源' }]} style={{ marginTop: 8 }}>
            <Select
              showSearch
              optionFilterProp="label"
              options={dataSources.map((d) => ({ value: d.id, label: `${d.name}（${d.type}）` }))}
            />
          </Form.Item>
          <Form.Item name="targetDatabase" label="目标数据库/schema（留空使用数据源默认值，Redis 不需要填）">
            <Input placeholder="例如：realtime_analytics" />
          </Form.Item>
          <Form.Item
            name="targetTable"
            label="目标表名 / Redis key 匹配规则"
            rules={[{ required: true, message: '请输入目标表名或 key 匹配规则' }]}
            extra="目标是 Redis 时这里填 key 的匹配规则，例如 test_orders_mysql_redis_sink:*"
          >
            <Input placeholder="例如：test_orders_mysql_ch_sink 或 test_orders_mysql_redis_sink:*" />
          </Form.Item>
          <Form.Item name="checkType" label="对账方式" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'ROW_COUNT', label: '行数比较' },
                { value: 'AGGREGATE', label: '聚合值比较（SUM）- 能发现"行数一样但数值错了"的问题' }
              ]}
            />
          </Form.Item>
          {checkType === 'AGGREGATE' && (
            <Form.Item name="aggregateColumn" label="聚合字段" rules={[{ required: true, message: '请输入要 SUM 的数值字段' }]} extra="源和目标使用同一个字段名">
              <Input placeholder="例如：amount" />
            </Form.Item>
          )}
          <Form.Item name="partitionColumn" label="分区字段（可选）" extra="填了就按这个字段分组比较，能定位到具体是哪个分区/日期出的差异，而不是一个笼统的总差异；源和目标使用同一个字段名">
            <Input placeholder="例如：order_date" />
          </Form.Item>
          <Form.Item name="tolerance" label="容忍的差异" extra="CDC 异步复制通常有短暂滞后，差异在此范围内不算异常" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
