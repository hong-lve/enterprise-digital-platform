import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Spin, Typography, message } from 'antd';
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode
} from 'react';
import {
  currentAdmin,
  errorMessage,
  loginAdmin,
  logoutAdmin,
  type AdminUser
} from './api';

interface AuthContextValue {
  user: AdminUser;
  logout: () => Promise<void>;
  hasPermission: (permission: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside AuthGate');
  }
  return value;
}

export default function AuthGate({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AdminUser | null>(null);
  const [checking, setChecking] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    currentAdmin()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setChecking(false));
  }, []);

  useEffect(() => {
    const expired = () => setUser(null);
    window.addEventListener('data-service-auth-expired', expired);
    return () => window.removeEventListener('data-service-auth-expired', expired);
  }, []);

  const login = async (values: { username: string; password: string }) => {
    setSubmitting(true);
    try {
      const session = await loginAdmin(values.username, values.password);
      setUser(session.user);
      message.success('登录成功');
    } catch (error) {
      message.error(errorMessage(error, '登录失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const logout = async () => {
    try {
      await logoutAdmin();
    } finally {
      setUser(null);
    }
  };

  const context = user ? {
    user,
    logout,
    hasPermission: (permission: string) => user.permissions.includes(permission)
  } : null;

  if (checking) {
    return (
      <div className="auth-loading">
        <Spin size="large" />
        <Typography.Text type="secondary">正在验证登录状态</Typography.Text>
      </div>
    );
  }

  if (!user || !context) {
    return (
      <main className="login-shell">
        <section className="login-panel">
          <div className="login-brand">
            <div className="login-mark">DS</div>
            <div>
              <Typography.Title level={2}>数据服务平台</Typography.Title>
              <Typography.Text type="secondary">管理控制台</Typography.Text>
            </div>
          </div>
          <Form
            layout="vertical"
            size="large"
            initialValues={{ username: 'admin' }}
            onFinish={login}
          >
            <Form.Item
              name="username"
              label="用户名"
              rules={[{ required: true, message: '请输入用户名' }]}
            >
              <Input prefix={<UserOutlined />} autoComplete="username" />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录
            </Button>
          </Form>
          <Alert
            type="info"
            showIcon
            message="连续登录失败 5 次后，账号将临时锁定。"
          />
        </section>
      </main>
    );
  }

  return <AuthContext.Provider value={context}>{children}</AuthContext.Provider>;
}
