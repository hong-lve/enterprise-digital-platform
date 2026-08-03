import {
  CheckCircleOutlined,
  KeyOutlined,
  LockOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  SyncOutlined
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useState } from 'react';
import {
  changeApplicationStatus,
  createApplication,
  errorMessage,
  listApplicationCredentials,
  revokeApplicationCredential,
  rotateApplicationSecret,
  type ApplicationSecretVersion,
  type ApplicationRecord,
  type CreatedApplication
} from './api';

interface ApplicationManagementProps {
  applications: ApplicationRecord[];
  loading: boolean;
  canManage: boolean;
  onChanged: () => Promise<void>;
}

export default function ApplicationManagement({
  applications,
  loading,
  canManage,
  onChanged
}: ApplicationManagementProps) {
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [credentialApp, setCredentialApp] = useState<ApplicationRecord | null>(null);
  const [credentials, setCredentials] = useState<ApplicationSecretVersion[]>([]);
  const [credentialLoading, setCredentialLoading] = useState(false);
  const [form] = Form.useForm();

  const revealCredential = (created: CreatedApplication, rotated: boolean) => {
    Modal.info({
      title: rotated ? '新密钥已生成' : '应用凭证已生成',
      width: 650,
      okText: '我已妥善保存',
      closable: false,
      maskClosable: false,
      content: (
        <div className="credential-result">
          <Alert type="warning" showIcon message="AppSecret 仅展示一次，关闭后无法再次查看。" />
          <div className="credential-field">
            <Typography.Text type="secondary">AppKey</Typography.Text>
            <Typography.Text code copyable>{created.application.appKey}</Typography.Text>
          </div>
          <div className="credential-field">
            <Typography.Text type="secondary">AppSecret</Typography.Text>
            <Typography.Text code copyable>{created.appSecret}</Typography.Text>
          </div>
          <Typography.Text type="secondary">
            密钥版本 v{created.application.secretVersion}
          </Typography.Text>
        </div>
      )
    });
  };

  const submitCreate = async (values: {
    appKey?: string;
    name: string;
    description?: string;
    qpsLimit: number;
  }) => {
    setSubmitting(true);
    try {
      const created = await createApplication(values);
      setCreateOpen(false);
      form.resetFields();
      revealCredential(created, false);
      await onChanged();
    } catch (error) {
      message.error(errorMessage(error, '创建应用失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const updateStatus = async (application: ApplicationRecord) => {
    try {
      const action = application.status === 'ENABLED' ? 'DISABLE' : 'ENABLE';
      await changeApplicationStatus(application.id, action);
      message.success(action === 'ENABLE' ? '应用已启用' : '应用已停用');
      await onChanged();
    } catch (error) {
      message.error(errorMessage(error, '应用状态变更失败'));
    }
  };

  const rotateSecret = (application: ApplicationRecord) => {
    let graceHours = 24;
    Modal.confirm({
      title: '确认轮换应用密钥？',
      content: (
        <div className="rotation-options">
          <Alert
            type="info"
            showIcon
            message="新密钥立即生效，旧密钥在宽限期内仍可使用。"
          />
          <label>旧密钥宽限期（小时）</label>
          <InputNumber
            min={1}
            max={168}
            defaultValue={24}
            onChange={(value) => { graceHours = value || 24; }}
          />
        </div>
      ),
      okText: '轮换密钥',
      onOk: async () => {
        try {
          const created = await rotateApplicationSecret(application.id, graceHours);
          revealCredential(created, true);
          await onChanged();
        } catch (error) {
          message.error(errorMessage(error, '密钥轮换失败'));
        }
      }
    });
  };

  const openCredentials = async (application: ApplicationRecord) => {
    setCredentialApp(application);
    setCredentialLoading(true);
    try {
      setCredentials(await listApplicationCredentials(application.id));
    } catch (error) {
      message.error(errorMessage(error, '加载密钥版本失败'));
    } finally {
      setCredentialLoading(false);
    }
  };

  const revokeCredential = (version: number) => {
    if (!credentialApp) {
      return;
    }
    Modal.confirm({
      title: `立即撤销旧密钥 v${version}`,
      content: '撤销后使用该版本的调用方会立即认证失败。',
      okText: '确认撤销',
      okButtonProps: { danger: true },
      onOk: async () => {
        await revokeApplicationCredential(credentialApp.id, version);
        setCredentials(await listApplicationCredentials(credentialApp.id));
        message.success(`旧密钥 v${version} 已撤销`);
      }
    });
  };

  return (
    <>
      <div className="section-toolbar">
        <div className="security-badges">
          <Tag icon={<SafetyCertificateOutlined />} color="blue">HMAC-SHA256</Tag>
          <Tag icon={<LockOutlined />} color="green">防重放</Tag>
          <Tag icon={<CheckCircleOutlined />} color="cyan">API 授权</Tag>
        </div>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建调用应用</Button>
        )}
      </div>

      <Table<ApplicationRecord>
        rowKey="id"
        loading={loading}
        dataSource={applications}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        size="middle"
        scroll={{ x: 1100 }}
        columns={[
          {
            title: '调用应用',
            dataIndex: 'name',
            width: 220,
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <span>{row.description || '暂无描述'}</span>
              </div>
            )
          },
          { title: 'AppKey', dataIndex: 'appKey', width: 220, render: (value) => <Typography.Text code copyable>{value}</Typography.Text> },
          { title: 'QPS', dataIndex: 'qpsLimit', width: 90 },
          { title: '密钥版本', dataIndex: 'secretVersion', width: 100, render: (value) => `v${value}` },
          {
            title: '已授权 API',
            dataIndex: 'authorizedApiIds',
            width: 120,
            render: (value: number[]) => value.length
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 110,
            render: (value) => <Tag color={value === 'ENABLED' ? 'success' : 'default'}>{value}</Tag>
          },
          {
            title: '操作',
            fixed: 'right',
            width: 300,
            render: (_, row) => (
              <Space size={2}>
                <Button type="link" icon={<KeyOutlined />} onClick={() => openCredentials(row)}>密钥版本</Button>
                {canManage && (
                  <>
                    <Button type="link" icon={<SyncOutlined />} onClick={() => rotateSecret(row)}>轮换</Button>
                    <Button
                      type="link"
                      danger={row.status === 'ENABLED'}
                      icon={row.status === 'ENABLED' ? <StopOutlined /> : <CheckCircleOutlined />}
                      onClick={() => updateStatus(row)}
                    >
                      {row.status === 'ENABLED' ? '停用' : '启用'}
                    </Button>
                  </>
                )}
              </Space>
            )
          }
        ]}
        locale={{ emptyText: '还没有调用应用' }}
      />

      <Modal
        title="创建调用应用"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.submit()}
        okText="创建并生成密钥"
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" initialValues={{ qpsLimit: 50 }} onFinish={submitCreate}>
          <Form.Item name="name" label="应用名称" rules={[{ required: true, message: '请输入应用名称' }]}>
            <Input placeholder="例如 经营分析门户" />
          </Form.Item>
          <Form.Item name="appKey" label="AppKey" extra="留空由平台自动生成">
            <Input placeholder="例如 operation_portal" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="填写调用方、负责人和用途" />
          </Form.Item>
          <Form.Item name="qpsLimit" label="QPS 限制" rules={[{ required: true }]}>
            <InputNumber min={1} max={10000} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={credentialApp ? `密钥版本：${credentialApp.name}` : '密钥版本'}
        open={Boolean(credentialApp)}
        onCancel={() => setCredentialApp(null)}
        footer={<Button onClick={() => setCredentialApp(null)}>关闭</Button>}
        width={760}
      >
        <Table<ApplicationSecretVersion>
          rowKey="id"
          loading={credentialLoading}
          dataSource={credentials}
          pagination={false}
          size="small"
          columns={[
            { title: '版本', dataIndex: 'secretVersion', width: 80, render: (value) => `v${value}` },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (value, row) => {
                const expired = value === 'GRACE' && row.expiresAt && new Date(row.expiresAt) <= new Date();
                const label = expired ? 'EXPIRED' : value;
                return <Tag color={value === 'ACTIVE' ? 'success' : !expired && value === 'GRACE' ? 'processing' : 'default'}>{label}</Tag>;
              }
            },
            { title: '宽限期截止', dataIndex: 'expiresAt', width: 180, render: (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' },
            { title: '最后使用', dataIndex: 'lastUsedAt', width: 180, render: (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未使用' },
            {
              title: '操作',
              width: 90,
              render: (_, row) => canManage && row.status === 'GRACE'
                ? <Button type="link" danger onClick={() => revokeCredential(row.secretVersion)}>撤销</Button>
                : '-'
            }
          ]}
        />
      </Modal>

    </>
  );
}
