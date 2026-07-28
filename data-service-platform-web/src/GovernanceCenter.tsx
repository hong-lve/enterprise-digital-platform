import {
  CheckOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  StopOutlined
} from '@ant-design/icons';
import {
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  decideChangeRequest,
  errorMessage,
  getAuditIntegrity,
  listChangeRequests,
  listNotificationChannels,
  listNotificationDeliveries,
  listOperationAudits,
  saveNotificationChannel,
  testNotificationChannel,
  type ChangeRequestRecord,
  type AuditIntegrity,
  type NotificationChannelRecord,
  type NotificationDeliveryRecord,
  type OperationAuditRecord
} from './api';

interface Props {
  canManageChannels: boolean;
  canHandleApprovals: boolean;
}

interface ChannelForm {
  name: string;
  channelType: NotificationChannelRecord['channelType'];
  endpoint?: string;
  enabled: boolean;
}

const formatTime = (value?: string) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '-';

export default function GovernanceCenter({ canManageChannels, canHandleApprovals }: Props) {
  const [channels, setChannels] = useState<NotificationChannelRecord[]>([]);
  const [deliveries, setDeliveries] = useState<NotificationDeliveryRecord[]>([]);
  const [audits, setAudits] = useState<OperationAuditRecord[]>([]);
  const [changes, setChanges] = useState<ChangeRequestRecord[]>([]);
  const [integrity, setIntegrity] = useState<AuditIntegrity | null>(null);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<NotificationChannelRecord | null>(null);
  const [channelModal, setChannelModal] = useState(false);
  const [form] = Form.useForm<ChannelForm>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [channelRows, deliveryRows, auditRows, changeRows, integrityResult] = await Promise.all([
        listNotificationChannels(),
        listNotificationDeliveries(),
        listOperationAudits(),
        listChangeRequests(),
        getAuditIntegrity()
      ]);
      setChannels(channelRows);
      setDeliveries(deliveryRows);
      setAudits(auditRows);
      setChanges(changeRows);
      setIntegrity(integrityResult);
    } catch (error) {
      message.error(errorMessage(error, '加载治理数据失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openChannel = (channel?: NotificationChannelRecord) => {
    setEditing(channel || null);
    form.setFieldsValue(channel ? {
      name: channel.name,
      channelType: channel.channelType,
      enabled: channel.enabled,
      endpoint: undefined
    } : {
      channelType: 'WEBHOOK',
      enabled: true
    });
    setChannelModal(true);
  };

  const submitChannel = async (values: ChannelForm) => {
    try {
      await saveNotificationChannel(editing?.id, values);
      message.success(editing ? '通知渠道已更新' : '通知渠道已创建');
      setChannelModal(false);
      form.resetFields();
      await load();
    } catch (error) {
      message.error(errorMessage(error, '保存通知渠道失败'));
    }
  };

  const testChannel = async (id: number) => {
    try {
      await testNotificationChannel(id);
      message.success('测试消息已进入发送队列');
      await load();
    } catch (error) {
      message.error(errorMessage(error, '发送测试消息失败'));
    }
  };

  const decide = (row: ChangeRequestRecord, action: 'approve' | 'reject') => {
    let comment = '';
    Modal.confirm({
      title: action === 'approve' ? '批准生产变更' : '拒绝生产变更',
      content: (
        <Input.TextArea
          rows={3}
          placeholder="填写审批意见"
          onChange={(event) => { comment = event.target.value; }}
        />
      ),
      okText: action === 'approve' ? '批准并执行' : '拒绝',
      okButtonProps: { danger: action === 'reject' },
      onOk: async () => {
        await decideChangeRequest(row.id, action, comment);
        message.success(action === 'approve' ? '变更已批准并生效' : '变更已拒绝');
        await load();
      }
    });
  };

  return (
    <>
      <div className="governance-toolbar">
        <Typography.Text type="secondary">生产变更、告警通知与管理操作统一留痕</Typography.Text>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
      </div>
      <Tabs items={[
        {
          key: 'approvals',
          label: `生产审批 (${changes.filter((item) => item.status === 'PENDING').length})`,
          children: (
            <Table<ChangeRequestRecord>
              rowKey="id"
              size="small"
              loading={loading}
              dataSource={changes}
              pagination={{ pageSize: 10 }}
              columns={[
                { title: '提交时间', dataIndex: 'createdAt', width: 170, render: formatTime },
                { title: '变更对象', dataIndex: 'targetSummary' },
                { title: '环境', dataIndex: 'environment', width: 80, render: (value) => <Tag color="red">{value}</Tag> },
                { title: '发起人', dataIndex: 'requester', width: 110 },
                { title: '审批人', dataIndex: 'approver', width: 110, render: (value) => value || '-' },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 110,
                  render: (value) => <Tag color={value === 'PENDING' ? 'processing' : value === 'APPROVED' ? 'success' : 'error'}>{value}</Tag>
                },
                {
                  title: '操作',
                  width: 150,
                  render: (_, row) => canHandleApprovals && row.status === 'PENDING' ? (
                    <Space>
                      <Button type="link" icon={<CheckOutlined />} onClick={() => decide(row, 'approve')}>批准</Button>
                      <Button type="link" danger icon={<StopOutlined />} onClick={() => decide(row, 'reject')}>拒绝</Button>
                    </Space>
                  ) : '-'
                }
              ]}
            />
          )
        },
        {
          key: 'channels',
          label: '通知渠道',
          children: (
            <>
              {canManageChannels && (
                <div className="governance-table-actions">
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => openChannel()}>新增渠道</Button>
                </div>
              )}
              <Table<NotificationChannelRecord>
                rowKey="id"
                size="small"
                loading={loading}
                dataSource={channels}
                pagination={false}
                columns={[
                  { title: '渠道名称', dataIndex: 'name' },
                  { title: '类型', dataIndex: 'channelType', width: 120, render: (value) => <Tag>{value}</Tag> },
                  { title: '地址', dataIndex: 'endpointConfigured', width: 100, render: (value) => value ? '已配置' : '未配置' },
                  { title: '状态', dataIndex: 'enabled', width: 100, render: (value) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag> },
                  { title: '创建人', dataIndex: 'createdBy', width: 110 },
                  { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
                  {
                    title: '操作',
                    width: 150,
                    render: (_, row) => canManageChannels ? (
                      <Space>
                        <Button type="text" title="编辑" icon={<EditOutlined />} onClick={() => openChannel(row)} />
                        <Button type="text" title="发送测试" icon={<SendOutlined />} onClick={() => testChannel(row.id)} />
                      </Space>
                    ) : '-'
                  }
                ]}
              />
            </>
          )
        },
        {
          key: 'deliveries',
          label: '发送记录',
          children: (
            <Table<NotificationDeliveryRecord>
              rowKey="id"
              size="small"
              loading={loading}
              dataSource={deliveries}
              pagination={{ pageSize: 10 }}
              columns={[
                { title: '创建时间', dataIndex: 'createdAt', width: 170, render: formatTime },
                { title: '渠道 ID', dataIndex: 'channelId', width: 90 },
                { title: '事件', dataIndex: 'eventType', width: 150 },
                { title: '状态', dataIndex: 'status', width: 110, render: (value) => <Tag color={value === 'SENT' ? 'success' : value === 'DEAD' ? 'error' : 'processing'}>{value}</Tag> },
                { title: '尝试次数', dataIndex: 'attempts', width: 100 },
                { title: '错误', dataIndex: 'lastError', ellipsis: true, render: (value) => value || '-' },
                { title: '发送时间', dataIndex: 'sentAt', width: 170, render: formatTime }
              ]}
            />
          )
        },
        {
          key: 'audits',
          label: (
            <Space size={6}>
              <span>操作审计</span>
              <Tag color={integrity?.valid ? 'success' : 'error'}>
                {integrity?.valid ? `链完整 ${integrity.checkedRecords}` : '链异常'}
              </Tag>
            </Space>
          ),
          children: (
            <Table<OperationAuditRecord>
              rowKey="id"
              size="small"
              loading={loading}
              dataSource={audits}
              scroll={{ x: 1100 }}
              pagination={{ pageSize: 12 }}
              columns={[
                { title: '时间', dataIndex: 'occurredAt', width: 170, render: formatTime },
                { title: '操作者', dataIndex: 'actor', width: 110, render: (value) => value || '-' },
                { title: '方法', dataIndex: 'httpMethod', width: 80 },
                { title: '路径', dataIndex: 'requestPath', width: 280, render: (value) => <Typography.Text code>{value}</Typography.Text> },
                { title: '结果', dataIndex: 'status', width: 100, render: (value) => <Tag color={value === 'SUCCESS' ? 'success' : 'error'}>{value}</Tag> },
                { title: '客户端', dataIndex: 'clientIp', width: 130 },
                { title: 'Trace ID', dataIndex: 'traceId', width: 180, ellipsis: true, render: (value) => value ? <Typography.Text copyable>{value}</Typography.Text> : '-' },
                { title: '记录哈希', dataIndex: 'recordHash', width: 160, ellipsis: true }
              ]}
            />
          )
        }
      ]} />

      <Modal
        title={editing ? '编辑通知渠道' : '新增通知渠道'}
        open={channelModal}
        onCancel={() => setChannelModal(false)}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={submitChannel}>
          <Form.Item name="name" label="渠道名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="channelType" label="渠道类型" rules={[{ required: true }]}>
            <Select options={['WEBHOOK', 'DINGTALK', 'WECHAT'].map((value) => ({ value, label: value }))} />
          </Form.Item>
          <Form.Item
            name="endpoint"
            label="Webhook 地址"
            extra={editing ? '留空表示不修改，生产环境要求 HTTPS' : '生产环境要求 HTTPS'}
            rules={editing ? [] : [{ required: true }]}
          >
            <Input.Password placeholder="https://..." />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}
