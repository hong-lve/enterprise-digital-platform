import { Button, Result, Spin } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { getMeSilently } from './api/auth';
import { AppShell } from './components/AppShell';
import { AlertHistoryPage } from './pages/AlertHistoryPage';
import { CdcSourcesPage } from './pages/CdcSourcesPage';
import { DataSourcesPage } from './pages/DataSourcesPage';
import { FlinkSqlJobsPage } from './pages/FlinkSqlJobsPage';
import { FlinkSqlPage } from './pages/FlinkSqlPage';
import { FlinkStreamJobsPage } from './pages/FlinkStreamJobsPage';
import { JarPackagesPage } from './pages/JarPackagesPage';
import { LineagePage } from './pages/LineagePage';
import { LoginPage } from './pages/LoginPage';
import { RealtimeOverviewPage } from './pages/RealtimeOverviewPage';
import { RealtimeQueryPage } from './pages/RealtimeQueryPage';
import { SystemMenusPage } from './pages/SystemMenusPage';
import { SystemSecurityPage } from './pages/SystemSecurityPage';
import { SystemRolesPage } from './pages/SystemRolesPage';
import { SystemUsersPage } from './pages/SystemUsersPage';
import { useAuthStore } from './store/auth';

function Protected({ children }: { children: JSX.Element }) {
  const token = useAuthStore((state) => state.token);
  const setCurrentUser = useAuthStore((state) => state.setCurrentUser);
  const clearSession = useAuthStore((state) => state.clearSession);
  const [loading, setLoading] = useState(true);
  const [checkFailed, setCheckFailed] = useState(false);

  const check = useCallback(() => {
    setLoading(true);
    setCheckFailed(false);
    getMeSilently()
      .then(setCurrentUser)
      .catch((error) => {
        // SecurityConfig has no formLogin/httpBasic entry point configured,
        // so Spring Security's default for an anonymous request against
        // .authenticated() is a 403 (Http403ForbiddenEntryPoint), not a 401 -
        // confirmed live. /auth/me carries no @PreAuthorize of its own
        // (any authenticated principal may call it), so for this specific
        // endpoint 403 is just as unambiguous a "not logged in" signal as
        // 401 would be. A network blip or a 5xx here is different - treating
        // those as "not logged in" too used to clear the session and drop an
        // actually-logged-in user onto the login page over a transient
        // failure, so only 401/403 clear the session; anything else surfaces
        // a retry instead of guessing.
        const status = error?.response?.status;
        if (status === 401 || status === 403) {
          clearSession();
        } else {
          setCheckFailed(true);
        }
      })
      .finally(() => setLoading(false));
  }, [clearSession, setCurrentUser]);

  useEffect(() => {
    check();
  }, [check]);

  if (loading) {
    return <div className="center-page"><Spin /></div>;
  }

  if (checkFailed) {
    return (
      <div className="center-page">
        <Result
          status="warning"
          title="无法确认登录状态"
          subTitle="网络异常或服务暂时不可用，请重试"
          extra={<Button type="primary" onClick={check}>重试</Button>}
        />
      </div>
    );
  }

  return token ? children : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage successPath="/realtime/overview" />} />
      <Route
        path="/"
        element={
          <Protected>
            <AppShell />
          </Protected>
        }
      >
        <Route index element={<Navigate to="/realtime/overview" replace />} />
        <Route path="realtime/overview" element={<RealtimeOverviewPage />} />
        <Route path="realtime/data-sources" element={<DataSourcesPage />} />
        <Route path="realtime/cdc-sources" element={<CdcSourcesPage />} />
        <Route path="realtime/flink-jobs" element={<FlinkStreamJobsPage />} />
        <Route path="realtime/jars" element={<JarPackagesPage />} />
        <Route path="realtime/query" element={<RealtimeQueryPage />} />
        <Route path="realtime/flink-sql" element={<FlinkSqlPage />} />
        <Route path="realtime/lineage" element={<LineagePage />} />
        <Route path="realtime/sql-jobs" element={<FlinkSqlJobsPage />} />
        <Route path="realtime/alert-history" element={<AlertHistoryPage />} />
        <Route path="system/users" element={<SystemUsersPage />} />
        <Route path="system/roles" element={<SystemRolesPage />} />
        <Route path="system/menus" element={<SystemMenusPage />} />
        <Route path="system/security" element={<SystemSecurityPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
