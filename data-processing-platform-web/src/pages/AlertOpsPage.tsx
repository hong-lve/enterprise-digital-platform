import { DeleteOutlined, PlusOutlined, ReloadOutlined, TeamOutlined } from '@ant-design/icons';
import { Button, DatePicker, Empty, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, Typography, message } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';
import {
  createOnCallShift,
  createSilenceWindow,
  deleteOnCallShift,
  deleteSilenceWindow,
  fetchOnCall,
  fetchRetryQueue,
  fetchSilenceWindows,
  type AlertRetryQueueRecord,
  type AlertSilenceWindowRecord,
  type OnCallShiftRecord
} from '../api/alertOps';
import { useAuthStore } from '../store/auth';

const DATETIME_FORMAT = 'YYYY-MM-DDTHH:mm:ss';

const entityTypeOptions = [
  { value: '', label: '全局（所有实体）' },
  { value: 'CDC_SOURCE', label: 'CDC 数据源' },
  { value: 'FLINK_JOB', label: 'Flink 流作业' }
];

const retryStatusColor: Record<string, string> = { PENDING: 'blue', SUCCEEDED: 'green', FAILED: 'red' };
const retryStatusLabel: Record<string, string> = { PENDING: '待重试', SUCCEEDED: '已送达', FAILED: '重试耗尽' };

interface ShiftFormValues {
  username: string;
  range: [Dayjs, Dayjs];
  note?: string;
}

interface SilenceFormValues {
  entityType: string;
  entityId?: number;
  range: [Dayjs, Dayjs];
  reason?: string;
}

export function AlertOpsPage() {
  const can = useAuthStore((state) => state.hasPermission);
  const canManage = can('realtime:oncall:manage');

  const [currentOnCall, setCurrentOnCall] = useState<string | undefined>();
  const [shifts, setShifts] = useState<OnCallShiftRecord[]>([]);
  const [shiftsLoading, setShiftsLoading] = useState(false);
  const [shiftModalOpen, setShiftModalOpen] = useState(false);
  const [shiftForm] = Form.useForm<ShiftFormValues>();
  const [savingShift, setSavingShift] = useState(false);

  const [windows, setWindows] = useState<AlertSilenceWindowRecord[]>([]);
  const [windowsLoading, setWindowsLoading] = useState(false);
  const [windowModalOpen, setWindowModalOpen] = useState(false);
  const [windowForm] = Form.useForm<SilenceFormValues>();
  const [savingWindow, setSavingWindow] = useState(false);
  const windowEntityType = Form.useWatch('entityType', windowForm);

  const [retryQueue, setRetryQueue] = useState<AlertRetryQueueRecord[]>([]);
  const [retryLoading, setRetryLoading] = useState(false);

  const loadShifts = () => {
    setShiftsLoading(true);
    fetchOnCall()
      .then((data) => {
        setCurrentOnCall(data.currentOnCall);
        setShifts(data.upcoming);
      })
      .finally(() => setShiftsLoading(false));
  };

  const loadWindows = () => {
    setWindowsLoading(true);
    fetchSilenceWindows().then(setWindows).finally(() => setWindowsLoading(false));
  };

  const loadRetryQueue = () => {
    setRetryLoading(true);
    fetchRetryQueue().then(setRetryQueue).finally(() => setRetryLoading(false));
  };

  useEffect(() => {
    loadShifts();
    loadWindows();
    loadRetryQueue();
  }, []);

  const openShiftModal = () => {
    shiftForm.resetFields();
    setShiftModalOpen(true);
  };

  const submitShift = (values: ShiftFormValues) => {
    setSavingShift(true);
    createOnCallShift({
      username: values.username,
      startsAt: values.range[0].format(DATETIME_FORMAT),
      endsAt: values.range[1].format(DATETIME_FORMAT),
      note: values.note
    })
      .then(() => {
        message.success('已新增值班班次');
        setShiftModalOpen(false);
        loadShifts();
      })
      .finally(() => setSavingShift(false));
  };

  const removeShift = (id: number) => {
    deleteOnCallShift(id).then(() => {
      message.success('已删除');
      loadShifts();
    });
  };

  const openWindowModal = () => {
    windowForm.resetFields();
    windowForm.setFieldsValue({ entityType: '' });
    setWindowModalOpen(true);
  };

  const submitWindow = (values: SilenceFormValues) => {
    setSavingWindow(true);
    createSilenceWindow({
      entityType: values.entityType || undefined,
      entityId: values.entityType ? values.entityId : undefined,
      startsAt: values.range[0].format(DATETIME_FORMAT),
      endsAt: values.range[1].format(DATETIME_FORMAT),
      reason: values.reason
    })
      .then(() => {
        message.success('已新增静默窗口');
        setWindowModalOpen(false);
        loadWindows();
      })
      .finally(() => setSavingWindow(false));
  };

  const removeWindow = (id: number) => {
    deleteSilenceWindow(id).then(() => {
      message.success('已删除');
      loadWindows();
    });
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><TeamOutlined /> 值班与静默</Typography.Title>
          <Typography.Paragraph type="secondary">
            值班排班决定告警发生时除了资源自身负责人之外，还会额外通知谁；静默窗口用于维护期间抑制 webhook/站内信通知（告警历史仍会照常记录）；重试队列展示 webhook 投递失败后的自动重试情况。持续告警超过阈值时间未恢复的，会自动向当前值班人再发一次升级通知。
          </Typography.Paragraph>
        </div>
      </Space>

      <Tabs
        items={[
          {
            key: 'oncall',
            label: '值班排班',
            children: (
              <div className="page-stack">
                <Space>
                  <Button icon={<ReloadOutlined />} loading={shiftsLoading} onClick={loadShifts}>刷新</Button>
                  {canManage && <Button type="primary" icon={<PlusOutlined />} onClick={openShiftModal}>新增班次</Button>}
                </Space>
                <Typography.Paragraph>
                  当前值班人：{currentOnCall ? <Tag color="green">{currentOnCall}</Tag> : <Tag>无人值班</Tag>}
                </Typography.Paragraph>
                <Table<OnCallShiftRecord>
                  rowKey="id"
                  loading={shiftsLoading}
                  dataSource={shifts}
                  pagination={false}
                  locale={{ emptyText: <Empty description="暂无排班" /> }}
                  columns={[
                    { title: '值班人', dataIndex: 'username', width: 140 },
                    { title: '开始时间', dataIndex: 'startsAt', width: 170 },
                    { title: '结束时间', dataIndex: 'endsAt', width: 170 },
                    { title: '备注', dataIndex: 'note', render: (value?: string) => value || '-' },
                    {
                      title: '操作',
                      width: 90,
                      render: (_, record) =>
                        canManage && (
                          <Popconfirm title="确定删除这个班次？" onConfirm={() => removeShift(record.id)}>
                            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                          </Popconfirm>
                        )
                    }
                  ]}
                />
              </div>
            )
          },
          {
            key: 'silence',
            label: '静默窗口',
            children: (
              <div className="page-stack">
                <Space>
                  <Button icon={<ReloadOutlined />} loading={windowsLoading} onClick={loadWindows}>刷新</Button>
                  {canManage && <Button type="primary" icon={<PlusOutlined />} onClick={openWindowModal}>新增静默窗口</Button>}
                </Space>
                <Table<AlertSilenceWindowRecord>
                  rowKey="id"
                  loading={windowsLoading}
                  dataSource={windows}
                  pagination={false}
                  locale={{ emptyText: <Empty description="暂无静默窗口" /> }}
                  columns={[
                    {
                      title: '范围',
                      render: (_, record) =>
                        record.entityType
                          ? `${entityTypeOptions.find((o) => o.value === record.entityType)?.label ?? record.entityType}${record.entityId ? ` #${record.entityId}` : '（全部）'}`
                          : '全局'
                    },
                    { title: '开始时间', dataIndex: 'startsAt', width: 170 },
                    { title: '结束时间', dataIndex: 'endsAt', width: 170 },
                    { title: '原因', dataIndex: 'reason', render: (value?: string) => value || '-' },
                    {
                      title: '操作',
                      width: 90,
                      render: (_, record) =>
                        canManage && (
                          <Popconfirm title="确定删除这个静默窗口？" onConfirm={() => removeWindow(record.id)}>
                            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                          </Popconfirm>
                        )
                    }
                  ]}
                />
              </div>
            )
          },
          {
            key: 'retry',
            label: '重试队列',
            children: (
              <div className="page-stack">
                <Space>
                  <Button icon={<ReloadOutlined />} loading={retryLoading} onClick={loadRetryQueue}>刷新</Button>
                </Space>
                <Table<AlertRetryQueueRecord>
                  rowKey="id"
                  loading={retryLoading}
                  dataSource={retryQueue}
                  pagination={false}
                  locale={{ emptyText: <Empty description="暂无重试记录" /> }}
                  columns={[
                    { title: '标题', dataIndex: 'title', ellipsis: true },
                    { title: '类型', dataIndex: 'type', width: 100 },
                    {
                      title: '状态',
                      width: 100,
                      render: (_, record) => <Tag color={retryStatusColor[record.status] || 'default'}>{retryStatusLabel[record.status] || record.status}</Tag>
                    },
                    { title: '已重试次数', render: (_, record) => `${record.attempts}/${record.maxAttempts}`, width: 100 },
                    { title: '下次重试时间', dataIndex: 'nextAttemptAt', width: 170 },
                    { title: '最近错误', dataIndex: 'lastError', render: (value?: string) => value || '-' }
                  ]}
                />
              </div>
            )
          }
        ]}
      />

      <Modal title="新增值班班次" open={shiftModalOpen} onCancel={() => setShiftModalOpen(false)} onOk={() => shiftForm.submit()} confirmLoading={savingShift} destroyOnClose>
        <Form form={shiftForm} layout="vertical" onFinish={submitShift}>
          <Form.Item name="username" label="值班人（用户名）" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="例如：admin" />
          </Form.Item>
          <Form.Item name="range" label="值班时间段" rules={[{ required: true, message: '请选择时间段' }]}>
            <DatePicker.RangePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="note" label="备注">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="新增静默窗口" open={windowModalOpen} onCancel={() => setWindowModalOpen(false)} onOk={() => windowForm.submit()} confirmLoading={savingWindow} destroyOnClose>
        <Form form={windowForm} layout="vertical" onFinish={submitWindow}>
          <Form.Item name="entityType" label="静默范围" rules={[{ required: true, message: '请选择范围' }]}>
            <Select options={entityTypeOptions} />
          </Form.Item>
          {windowEntityType && (
            <Form.Item name="entityId" label="实体 ID（留空表示该类型全部）">
              <InputNumber style={{ width: '100%' }} placeholder="可选，例如某个具体 CDC 数据源的 id" />
            </Form.Item>
          )}
          <Form.Item name="range" label="静默时间段" rules={[{ required: true, message: '请选择时间段' }]}>
            <DatePicker.RangePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label="原因">
            <Input placeholder="例如：数据库维护窗口" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
