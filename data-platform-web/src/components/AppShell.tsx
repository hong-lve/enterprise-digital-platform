import {
  AuditOutlined,
  ClusterOutlined,
  CodeOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DiffOutlined,
  FileSearchOutlined,
  FileZipOutlined,
  FunctionOutlined,
  LogoutOutlined,
  MenuOutlined,
  NodeIndexOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { Button, Layout, Menu, Space, Typography } from 'antd';
import { useMemo } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import type { MenuNode } from '../api/auth';
import { logout } from '../api/auth';
import { useAuthStore } from '../store/auth';

const { Header, Sider, Content } = Layout;

const iconMap: Record<string, JSX.Element> = {
  AuditOutlined: <AuditOutlined />,
  ThunderboltOutlined: <ThunderboltOutlined />,
  DashboardOutlined: <DashboardOutlined />,
  DatabaseOutlined: <DatabaseOutlined />,
  ClusterOutlined: <ClusterOutlined />,
  DiffOutlined: <DiffOutlined />,
  FileSearchOutlined: <FileSearchOutlined />,
  CodeOutlined: <CodeOutlined />,
  NodeIndexOutlined: <NodeIndexOutlined />,
  SafetyCertificateOutlined: <SafetyCertificateOutlined />,
  FunctionOutlined: <FunctionOutlined />,
  FileZipOutlined: <FileZipOutlined />,
  SettingOutlined: <SettingOutlined />,
  UserOutlined: <UserOutlined />,
  TeamOutlined: <TeamOutlined />,
  MenuOutlined: <MenuOutlined />
};

// The menu tree from /auth/me is already scoped to what this user's roles
// grant (AuthController.buildTree() only includes visible, non-BUTTON menu
// rows) - filtering again against a hardcoded path list here was a second
// copy of that same allowlist that had to be kept in sync by hand every
// time a /realtime/* page was added or removed.
function toMenuItems(menus: MenuNode[]): MenuProps['items'] {
  return menus.map((menu) => ({
    key: menu.path,
    icon: menu.icon ? iconMap[menu.icon] : undefined,
    label: menu.title,
    children: menu.children?.length ? toMenuItems(menu.children) : undefined
  }));
}

export function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const displayName = useAuthStore((state) => state.displayName);
  const menus = useAuthStore((state) => state.menus);
  const clearSession = useAuthStore((state) => state.clearSession);
  const menuItems = useMemo(() => toMenuItems(menus), [menus]);

  return (
    <Layout className="app-layout">
      <Sider width={240} theme="light" className="app-sider">
        <div className="brand">实时计算</div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          defaultOpenKeys={['/realtime']}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header className="app-header">
          <Typography.Text strong>实时计算平台</Typography.Text>
          <Space>
            <Button type="link" icon={<UserOutlined />}>{displayName}</Button>
            <Button
              aria-label="退出登录"
              icon={<LogoutOutlined />}
              onClick={() => {
                logout().finally(() => {
                  clearSession();
                  navigate('/login');
                });
              }}
            />
          </Space>
        </Header>
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
