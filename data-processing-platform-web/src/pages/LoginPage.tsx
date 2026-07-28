import { LockOutlined, LoginOutlined, SafetyOutlined, UserOutlined } from '@ant-design/icons';
import { Button, Form, Input, Typography, message } from 'antd';
import axios from 'axios';
import QRCode from 'qrcode';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getMeSilently, login, verifyTwoFactor, type LoginResult } from '../api/auth';
import { useAuthStore } from '../store/auth';

type Step = 'password' | 'verify' | 'setup';

export function LoginPage({ successPath = '/' }: { successPath?: string }) {
  const navigate = useNavigate();
  const setCurrentUser = useAuthStore((state) => state.setCurrentUser);
  const [submitting, setSubmitting] = useState(false);
  const [step, setStep] = useState<Step>('password');
  const [pendingToken, setPendingToken] = useState('');
  const [secret, setSecret] = useState('');
  const [qrCodeDataUrl, setQrCodeDataUrl] = useState('');
  const [codeForm] = Form.useForm<{ code: string }>();

  const finishLogin = async () => {
    try {
      const user = await getMeSilently();
      setCurrentUser(user);
      navigate(successPath);
    } catch {
      message.error('登录成功，但获取用户信息失败，请重试');
    }
  };

  const enterTwoFactorStep = async (result: LoginResult) => {
    setPendingToken(result.pendingToken ?? '');
    if (result.requiresSetup) {
      setSecret(result.secret ?? '');
      setQrCodeDataUrl(result.otpAuthUri ? await QRCode.toDataURL(result.otpAuthUri) : '');
      setStep('setup');
    } else {
      setStep('verify');
    }
  };

  const submitPassword = async (values: { username: string; password: string }) => {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    try {
      const result = await login(values.username, values.password);
      if (result.requires2fa) {
        await enterTwoFactorStep(result);
      } else {
        await finishLogin();
      }
    } catch (error) {
      // A failed login can mean bad credentials (401), a rate-limit
      // lockout (429, the backend's own message names it plainly),
      // or a network/server hiccup - conflating these into "wrong
      // password" would send a locked-out or disconnected user off
      // re-typing a password that was never wrong to begin with.
      const status = axios.isAxiosError(error) ? error.response?.status : undefined;
      const serverMessage = axios.isAxiosError(error) ? error.response?.data?.message : undefined;
      const text = status === 401
        ? '用户名或密码错误'
        : status !== undefined
          ? serverMessage || '登录失败，请稍后重试'
          : '登录失败，请检查网络后重试';
      message.error(text);
    } finally {
      setSubmitting(false);
    }
  };

  const submitCode = async (values: { code: string }) => {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    try {
      await verifyTwoFactor(pendingToken, values.code);
      await finishLogin();
    } catch (error) {
      const serverMessage = axios.isAxiosError(error) ? error.response?.data?.message : undefined;
      message.error(serverMessage || '验证码错误，请重试');
      if (serverMessage?.includes('过期') || serverMessage?.includes('次数过多')) {
        setStep('password');
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (step === 'verify' || step === 'setup') {
    return (
      <main className="login-page">
        <section className="login-panel">
          <Typography.Title level={2}>双因子验证</Typography.Title>
          {step === 'setup' && (
            <>
              <Typography.Paragraph>
                首次登录需要绑定双因子验证。用 Google Authenticator / Microsoft Authenticator 等 App 扫描下方二维码，然后输入 App 显示的 6 位验证码完成绑定。
              </Typography.Paragraph>
              {qrCodeDataUrl && <img src={qrCodeDataUrl} alt="TOTP 二维码" style={{ display: 'block', margin: '0 auto 16px' }} />}
              <Typography.Paragraph type="secondary">
                无法扫码？可手动输入密钥：<Typography.Text code copyable>{secret}</Typography.Text>
              </Typography.Paragraph>
            </>
          )}
          {step === 'verify' && (
            <Typography.Paragraph>请输入身份验证器 App 中显示的 6 位验证码。</Typography.Paragraph>
          )}
          <Form form={codeForm} layout="vertical" onFinish={submitCode}>
            <Form.Item
              name="code"
              label="验证码"
              rules={[
                { required: true, message: '请输入验证码' },
                { pattern: /^[0-9]{6}$/, message: '验证码为 6 位数字' }
              ]}
            >
              <Input prefix={<SafetyOutlined />} maxLength={6} autoFocus />
            </Form.Item>
            <Button type="primary" htmlType="submit" icon={<LoginOutlined />} loading={submitting} disabled={submitting} block>
              验证并登录
            </Button>
          </Form>
        </section>
      </main>
    );
  }

  return (
    <main className="login-page">
      <section className="login-panel">
        <Typography.Title level={2}>实时计算平台登录</Typography.Title>
        <Form layout="vertical" initialValues={{ username: 'admin', password: 'admin123' }} onFinish={submitPassword}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} />
          </Form.Item>
          <Button type="primary" htmlType="submit" icon={<LoginOutlined />} loading={submitting} disabled={submitting} block>
            登录
          </Button>
        </Form>
      </section>
    </main>
  );
}
