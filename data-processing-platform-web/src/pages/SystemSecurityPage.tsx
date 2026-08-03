import { KeyOutlined, SafetyOutlined } from '@ant-design/icons';
import { Button, Descriptions, Popconfirm, Space, Switch, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { isPendingApproval } from '../api/approval';
import {
  getEncryptionKeyStatus,
  getTwoFactorSetting,
  rotateEncryptionKeys,
  updateTwoFactorSetting,
  type EncryptionKeyRotationEventRecord,
  type EncryptionKeyStatus
} from '../api/systemSecurity';
import { useAuthStore } from '../store/auth';

const eventStatusColor: Record<string, string> = { RUNNING: 'blue', SUCCEEDED: 'green', FAILED: 'red' };
const eventStatusLabel: Record<string, string> = { RUNNING: '进行中', SUCCEEDED: '成功', FAILED: '失败' };

function versionCountsText(counts: Record<string, number>): string {
  const entries = Object.entries(counts);
  if (entries.length === 0) return '无数据';
  return entries.map(([version, count]) => `${version}: ${count} 行`).join('，');
}

export function SystemSecurityPage() {
  const [enabled, setEnabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const can = useAuthStore((state) => state.hasPermission);

  const [keyStatus, setKeyStatus] = useState<EncryptionKeyStatus | null>(null);
  const [keyStatusLoading, setKeyStatusLoading] = useState(false);
  const [rotating, setRotating] = useState(false);

  const loadKeyStatus = () => {
    setKeyStatusLoading(true);
    getEncryptionKeyStatus().then(setKeyStatus).finally(() => setKeyStatusLoading(false));
  };

  useEffect(() => {
    setLoading(true);
    getTwoFactorSetting()
      .then((setting) => setEnabled(setting.enabled))
      .catch(() => message.error('加载安全设置失败'))
      .finally(() => setLoading(false));
    loadKeyStatus();
  }, []);

  const toggle = (checked: boolean) => {
    setSaving(true);
    updateTwoFactorSetting(checked)
      .then((setting) => {
        setEnabled(setting.enabled);
        message.success(setting.enabled ? '已开启全局双因子登录' : '已关闭全局双因子登录');
      })
      .catch(() => message.error('保存失败'))
      .finally(() => setSaving(false));
  };

  const rotate = () => {
    setRotating(true);
    rotateEncryptionKeys()
      .then((result) => {
        if (isPendingApproval(result)) {
          message.info(`已提交审批（申请编号 #${result.approvalRequestId}），审批通过后才会实际轮换`);
        } else {
          message.success('密钥轮换已完成');
        }
        loadKeyStatus();
      })
      .finally(() => setRotating(false));
  };

  return (
    <div className="page-stack">
      <div>
        <Typography.Title level={3}><SafetyOutlined /> 安全设置</Typography.Title>
        <Typography.Paragraph type="secondary">
          开启后，所有账号登录都需要用 TOTP 身份验证器（Google Authenticator / Microsoft Authenticator 等）App 生成的 6 位动态验证码完成第二步验证。还没绑定过的账号首次登录时会先展示二维码完成绑定。
        </Typography.Paragraph>
      </div>
      <Space>
        <Switch checked={enabled} loading={loading || saving} disabled={!can('system:security:update')} onChange={toggle} />
        <span>{enabled ? '已开启全局双因子登录' : '未开启全局双因子登录'}</span>
      </Space>

      <div>
        <Typography.Title level={4}><KeyOutlined /> 加密密钥版本与轮换</Typography.Title>
        <Typography.Paragraph type="secondary">
          数据源密码和 TOTP 密钥都用 AES-256-GCM 加密存储，密钥本身只存在于配置/环境变量里，从不落库。轮换会把所有已加密数据重新用当前配置的密钥版本加密一遍——旧版本密钥只要还留在配置里就能继续解密尚未轮换的数据，确认下面两张表都只剩当前版本后再从配置里移除旧密钥。
        </Typography.Paragraph>
      </div>
      {keyStatus && (
        <>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="当前密钥版本"><Tag color="blue">{keyStatus.currentVersion}</Tag></Descriptions.Item>
            <Descriptions.Item label="已配置的版本">{keyStatus.configuredVersions.map((v) => <Tag key={v}>{v}</Tag>)}</Descriptions.Item>
            <Descriptions.Item label="数据源密码按版本分布" span={2}>{versionCountsText(keyStatus.dataSourceRowsByVersion)}</Descriptions.Item>
            <Descriptions.Item label="TOTP 密钥按版本分布" span={2}>{versionCountsText(keyStatus.totpRowsByVersion)}</Descriptions.Item>
          </Descriptions>
          <Space>
            <Button icon={<KeyOutlined />} loading={keyStatusLoading} onClick={loadKeyStatus}>刷新</Button>
            {can('system:security:update') && (
              <Popconfirm
                title="确定立即轮换？"
                description="会重新加密所有数据源密码和 TOTP 密钥，过程中被访问的数据仍然可用（旧密钥版本仍保留在配置中）"
                onConfirm={rotate}
              >
                <Button type="primary" danger loading={rotating}>立即轮换</Button>
              </Popconfirm>
            )}
          </Space>
          <Typography.Title level={5}>最近轮换记录</Typography.Title>
          <Table<EncryptionKeyRotationEventRecord>
            rowKey="id"
            size="small"
            dataSource={keyStatus.recentEvents}
            pagination={false}
            locale={{ emptyText: '暂无轮换记录' }}
            columns={[
              { title: '目标版本', dataIndex: 'toVersion', width: 90, render: (value: string) => <Tag color="blue">{value}</Tag> },
              { title: '状态', dataIndex: 'status', width: 90, render: (value: string) => <Tag color={eventStatusColor[value] || 'default'}>{eventStatusLabel[value] || value}</Tag> },
              { title: '数据源重加密行数', dataIndex: 'dataSourceRowsReencrypted', width: 130 },
              { title: 'TOTP 重加密行数', dataIndex: 'totpRowsReencrypted', width: 120 },
              { title: '触发人', dataIndex: 'triggeredBy', width: 100, render: (value?: string) => value || '-' },
              { title: '开始时间', dataIndex: 'startedAt', width: 170 },
              { title: '结束时间', dataIndex: 'finishedAt', width: 170, render: (value?: string) => value || '-' },
              { title: '错误信息', dataIndex: 'errorMessage', render: (value?: string) => value || '-' }
            ]}
          />
        </>
      )}
    </div>
  );
}
