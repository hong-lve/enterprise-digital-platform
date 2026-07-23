import { SafetyOutlined } from '@ant-design/icons';
import { Space, Switch, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { getTwoFactorSetting, updateTwoFactorSetting } from '../api/systemSecurity';
import { useAuthStore } from '../store/auth';

export function SystemSecurityPage() {
  const [enabled, setEnabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const can = useAuthStore((state) => state.hasPermission);

  useEffect(() => {
    setLoading(true);
    getTwoFactorSetting()
      .then((setting) => setEnabled(setting.enabled))
      .catch(() => message.error('加载安全设置失败'))
      .finally(() => setLoading(false));
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
    </div>
  );
}
