import {
  CheckOutlined,
  ClockCircleOutlined,
  PlusOutlined,
  StopOutlined
} from '@ant-design/icons';
import {
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { useCallback, useEffect, useState } from 'react';
import {
  errorMessage,
  listApiSubscriptions,
  reviewApiSubscription,
  submitApiSubscription,
  suspendApiSubscription,
  type ApiSubscriptionRecord,
  type ApplicationRecord,
  type DataApiRecord
} from './api';

interface Props {
  applications: ApplicationRecord[];
  apis: DataApiRecord[];
  canManage: boolean;
  canApprove: boolean;
  onChanged: () => Promise<void>;
}

interface SubscriptionForm {
  appId: number;
  apiId: number;
  reason?: string;
  qpsLimit: number;
  dailyLimit: number;
  validity?: [Dayjs, Dayjs];
  ipAllowlistText?: string;
}

const formatTime = (value?: string) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '-';

const parseAllowlist = (value?: string) => (value || '')
  .split(/[\s,;]+/)
  .map((item) => item.trim())
  .filter(Boolean);

export default function SubscriptionManagement({
  applications,
  apis,
  canManage,
  canApprove,
  onChanged
}: Props) {
  const [rows, setRows] = useState<ApiSubscriptionRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [reviewing, setReviewing] = useState<ApiSubscriptionRecord | null>(null);
  const [form] = Form.useForm<SubscriptionForm>();
  const [reviewForm] = Form.useForm<SubscriptionForm & { comment?: string }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await listApiSubscriptions());
    } catch (error) {
      message.error(errorMessage(error, '加载 API 订阅失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const submit = async (values: SubscriptionForm) => {
    try {
      await submitApiSubscription({
        appId: values.appId,
        apiId: values.apiId,
        reason: values.reason,
        qpsLimit: values.qpsLimit,
        dailyLimit: values.dailyLimit,
        validFrom: values.validity?.[0].toISOString(),
        validUntil: values.validity?.[1].toISOString(),
        ipAllowlist: parseAllowlist(values.ipAllowlistText)
      });
      message.success('API 订阅已提交审批');
      setCreateOpen(false);
      form.resetFields();
      await Promise.all([load(), onChanged()]);
    } catch (error) {
      message.error(errorMessage(error, '提交 API 订阅失败'));
    }
  };

  const openReview = (row: ApiSubscriptionRecord) => {
    setReviewing(row);
    reviewForm.setFieldsValue({
      qpsLimit: row.qpsLimit,
      dailyLimit: row.dailyLimit,
      validity: row.validFrom && row.validUntil
        ? [dayjs(row.validFrom), dayjs(row.validUntil)]
        : undefined,
      ipAllowlistText: row.ipAllowlist.join('\n')
    });
  };

  const review = async (action: 'APPROVE' | 'REJECT') => {
    if (!reviewing) {
      return;
    }
    try {
      const values = await reviewForm.validateFields();
      await reviewApiSubscription(reviewing.id, {
        action,
        qpsLimit: values.qpsLimit,
        dailyLimit: values.dailyLimit,
        validFrom: values.validity?.[0].toISOString(),
        validUntil: values.validity?.[1].toISOString(),
        ipAllowlist: parseAllowlist(values.ipAllowlistText),
        comment: values.comment
      });
      message.success(action === 'APPROVE' ? '订阅已批准并生效' : '订阅已拒绝');
      setReviewing(null);
      await Promise.all([load(), onChanged()]);
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) {
        return;
      }
      message.error(errorMessage(error, '审批 API 订阅失败'));
    }
  };

  const suspend = (row: ApiSubscriptionRecord) => {
    Modal.confirm({
      title: '暂停 API 订阅',
      content: '暂停后该应用将立即无法调用此 API。',
      okText: '确认暂停',
      okButtonProps: { danger: true },
      onOk: async () => {
        await suspendApiSubscription(row.id, '管理员暂停订阅');
        message.success('订阅已暂停');
        await Promise.all([load(), onChanged()]);
      }
    });
  };

  return (
    <section className="subscription-section">
      <div className="governance-heading">
        <div>
          <Typography.Title level={5}>API 订阅治理</Typography.Title>
          <Typography.Text type="secondary">按应用和 API 审批调用权限、有效期与独立配额</Typography.Text>
        </div>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            申请订阅
          </Button>
        )}
      </div>

      <Table<ApiSubscriptionRecord>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        size="small"
        scroll={{ x: 1350 }}
        pagination={{ pageSize: 10 }}
        columns={[
          {
            title: '应用',
            dataIndex: 'appName',
            width: 180,
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <span>{row.appKey}</span>
              </div>
            )
          },
          {
            title: 'API',
            dataIndex: 'apiName',
            width: 230,
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <span>{row.apiMethod} {row.apiPath}</span>
              </div>
            )
          },
          { title: 'QPS', dataIndex: 'qpsLimit', width: 80 },
          {
            title: '今日用量',
            width: 150,
            render: (_, row) => {
              const percent = row.dailyLimit
                ? Math.min(100, Math.round(row.dailyUsed * 100 / row.dailyLimit))
                : 0;
              return (
                <div className="quota-usage">
                  <span>{row.dailyUsed.toLocaleString()} / {row.dailyLimit.toLocaleString()}</span>
                  <div><i style={{ width: `${percent}%` }} /></div>
                </div>
              );
            }
          },
          {
            title: 'IP 白名单',
            dataIndex: 'ipAllowlist',
            width: 150,
            render: (value: string[]) => value.length ? `${value.length} 条` : '不限'
          },
          {
            title: '有效期',
            width: 210,
            render: (_, row) => row.validUntil
              ? `${formatTime(row.validFrom)} 至 ${formatTime(row.validUntil)}`
              : '长期'
          },
          { title: '申请人', dataIndex: 'requestedBy', width: 100 },
          {
            title: '状态',
            dataIndex: 'status',
            width: 110,
            render: (value) => (
              <Tag color={value === 'APPROVED' ? 'success' : value === 'PENDING' ? 'processing' : value === 'REJECTED' ? 'error' : 'default'}>
                {value}
              </Tag>
            )
          },
          {
            title: '操作',
            fixed: 'right',
            width: 180,
            render: (_, row) => (
              <Space>
                {canApprove && row.status === 'PENDING' && (
                  <Button type="link" icon={<CheckOutlined />} onClick={() => openReview(row)}>审批</Button>
                )}
                {canApprove && row.status === 'APPROVED' && (
                  <Button type="link" danger icon={<StopOutlined />} onClick={() => suspend(row)}>暂停</Button>
                )}
                {!canApprove && <ClockCircleOutlined title="等待审批" />}
              </Space>
            )
          }
        ]}
      />

      <Modal
        title="申请 API 订阅"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ qpsLimit: 20, dailyLimit: 100000 }}
          onFinish={submit}
        >
          <Form.Item name="appId" label="调用应用" rules={[{ required: true }]}>
            <Select options={applications.map((item) => ({ value: item.id, label: `${item.name} (${item.appKey})` }))} />
          </Form.Item>
          <Form.Item name="apiId" label="订阅 API" rules={[{ required: true }]}>
            <Select options={apis.filter((item) => item.status === 'PUBLISHED').map((item) => ({
              value: item.id,
              label: `${item.name} · ${item.method} ${item.path}`
            }))} />
          </Form.Item>
          <div className="form-grid">
            <Form.Item name="qpsLimit" label="申请 QPS" rules={[{ required: true }]}><InputNumber min={1} max={10000} /></Form.Item>
            <Form.Item name="dailyLimit" label="每日调用额度" rules={[{ required: true }]}><InputNumber min={1} max={1000000000} /></Form.Item>
          </div>
          <Form.Item name="validity" label="有效期"><DatePicker.RangePicker showTime /></Form.Item>
          <Form.Item name="ipAllowlistText" label="IP 白名单" extra="每行一个 IP 或 CIDR；留空表示不限">
            <Input.TextArea rows={3} placeholder={'10.20.30.40\n10.20.0.0/16'} />
          </Form.Item>
          <Form.Item name="reason" label="申请原因"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>

      <Modal
        title={reviewing ? `审批订阅：${reviewing.appName} → ${reviewing.apiName}` : '审批订阅'}
        open={Boolean(reviewing)}
        onCancel={() => setReviewing(null)}
        footer={[
          <Button key="reject" danger onClick={() => review('REJECT')}>拒绝</Button>,
          <Button key="approve" type="primary" onClick={() => review('APPROVE')}>批准并生效</Button>
        ]}
        destroyOnHidden
      >
        <Form form={reviewForm} layout="vertical">
          <div className="form-grid">
            <Form.Item name="qpsLimit" label="批准 QPS" rules={[{ required: true }]}><InputNumber min={1} max={10000} /></Form.Item>
            <Form.Item name="dailyLimit" label="每日调用额度" rules={[{ required: true }]}><InputNumber min={1} max={1000000000} /></Form.Item>
          </div>
          <Form.Item name="validity" label="批准有效期"><DatePicker.RangePicker showTime /></Form.Item>
          <Form.Item name="ipAllowlistText" label="IP 白名单"><Input.TextArea rows={3} /></Form.Item>
          <Form.Item name="comment" label="审批意见"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>
    </section>
  );
}
