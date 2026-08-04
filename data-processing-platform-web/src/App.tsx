import { Button, Result, Spin } from 'antd';
import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { getMeSilently } from './api/auth';
import { AppShell } from './components/AppShell';
import { useAuthStore } from './store/auth';

const AlertHistoryPage = lazy(() => import('./pages/AlertHistoryPage').then((m) => ({ default: m.AlertHistoryPage })));
const AlertOpsPage = lazy(() => import('./pages/AlertOpsPage').then((m) => ({ default: m.AlertOpsPage })));
const ApprovalCenterPage = lazy(() => import('./pages/ApprovalCenterPage').then((m) => ({ default: m.ApprovalCenterPage })));
const AuditLogPage = lazy(() => import('./pages/AuditLogPage').then((m) => ({ default: m.AuditLogPage })));
const CdcSourcesPage = lazy(() => import('./pages/CdcSourcesPage').then((m) => ({ default: m.CdcSourcesPage })));
const ContainerMonitoringPage = lazy(() => import('./pages/ContainerMonitoringPage').then((m) => ({ default: m.ContainerMonitoringPage })));
const DataSourcesPage = lazy(() => import('./pages/DataSourcesPage').then((m) => ({ default: m.DataSourcesPage })));
const FlinkClustersPage = lazy(() => import('./pages/FlinkClustersPage').then((m) => ({ default: m.FlinkClustersPage })));
const FlinkSqlJobsPage = lazy(() => import('./pages/FlinkSqlJobsPage').then((m) => ({ default: m.FlinkSqlJobsPage })));
const FlinkSqlPage = lazy(() => import('./pages/FlinkSqlPage').then((m) => ({ default: m.FlinkSqlPage })));
const FlinkStreamJobsPage = lazy(() => import('./pages/FlinkStreamJobsPage').then((m) => ({ default: m.FlinkStreamJobsPage })));
const JarPackagesPage = lazy(() => import('./pages/JarPackagesPage').then((m) => ({ default: m.JarPackagesPage })));
const LineagePage = lazy(() => import('./pages/LineagePage').then((m) => ({ default: m.LineagePage })));
const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })));
const DataQualityRulesPage = lazy(() => import('./pages/DataQualityRulesPage').then((m) => ({ default: m.DataQualityRulesPage })));
const RealtimeOverviewPage = lazy(() => import('./pages/RealtimeOverviewPage').then((m) => ({ default: m.RealtimeOverviewPage })));
const RealtimeQueryPage = lazy(() => import('./pages/RealtimeQueryPage').then((m) => ({ default: m.RealtimeQueryPage })));
const ReconciliationPage = lazy(() => import('./pages/ReconciliationPage').then((m) => ({ default: m.ReconciliationPage })));
const SystemMenusPage = lazy(() => import('./pages/SystemMenusPage').then((m) => ({ default: m.SystemMenusPage })));
const SystemSecurityPage = lazy(() => import('./pages/SystemSecurityPage').then((m) => ({ default: m.SystemSecurityPage })));
const SystemRolesPage = lazy(() => import('./pages/SystemRolesPage').then((m) => ({ default: m.SystemRolesPage })));
const SystemUsersPage = lazy(() => import('./pages/SystemUsersPage').then((m) => ({ default: m.SystemUsersPage })));

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
    <Suspense fallback={<div className="center-page"><Spin /></div>}><Routes>
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
        <Route path="realtime/flink-clusters" element={<FlinkClustersPage />} />
        <Route path="realtime/jars" element={<JarPackagesPage />} />
        <Route path="realtime/query" element={<RealtimeQueryPage />} />
        <Route path="realtime/flink-sql" element={<FlinkSqlPage />} />
        <Route path="realtime/lineage" element={<LineagePage />} />
        <Route path="realtime/sql-jobs" element={<FlinkSqlJobsPage />} />
        <Route path="realtime/alert-history" element={<AlertHistoryPage />} />
        <Route path="realtime/oncall" element={<AlertOpsPage />} />
        <Route path="realtime/reconciliation" element={<ReconciliationPage />} />
        <Route path="realtime/data-quality" element={<DataQualityRulesPage />} />
        <Route path="realtime/container-monitoring" element={<ContainerMonitoringPage />} />
        <Route path="system/users" element={<SystemUsersPage />} />
        <Route path="system/roles" element={<SystemRolesPage />} />
        <Route path="system/menus" element={<SystemMenusPage />} />
        <Route path="system/security" element={<SystemSecurityPage />} />
        <Route path="system/audit-log" element={<AuditLogPage />} />
        <Route path="system/approval-center" element={<ApprovalCenterPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes></Suspense>
  );
}
