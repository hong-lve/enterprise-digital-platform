import axios from 'axios';

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface DatasetRecord {
  id: number;
  name: string;
  description?: string;
  sourceType: string;
  sourceName: string;
  connectionMode: string;
  connectionId?: number;
  tableName: string;
  owner?: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface DatasetColumnPolicy {
  columnName: string;
  action: 'MASK' | 'HIDE';
  maskType?: 'FULL' | 'PARTIAL' | 'EMAIL' | 'PHONE' | 'HASH';
}

export interface DatasetAccessPolicy {
  datasetId: number;
  rowFilterSql?: string;
  columns: DatasetColumnPolicy[];
  updatedBy?: string;
  updatedAt?: string;
}

export interface DataSourceRecord {
  id: number;
  name: string;
  engineType: 'MYSQL' | 'ORACLE' | 'DORIS' | 'CLICKHOUSE';
  host: string;
  port: number;
  databaseName: string;
  username: string;
  passwordConfigured: boolean;
  poolMinIdle: number;
  poolMaxSize: number;
  connectionTimeoutMs: number;
  queryTimeoutSeconds: number;
  environment: 'DEV' | 'TEST' | 'PROD';
  owner?: string;
  status: 'DRAFT' | 'ACTIVE' | 'DISABLED';
  lastTestStatus?: 'SUCCESS' | 'FAILED';
  lastTestMessage?: string;
  lastTestAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface SaveDataSourceRequest {
  name: string;
  engineType: DataSourceRecord['engineType'];
  host: string;
  port: number;
  databaseName: string;
  username: string;
  password?: string;
  poolMinIdle: number;
  poolMaxSize: number;
  connectionTimeoutMs: number;
  queryTimeoutSeconds: number;
  environment: DataSourceRecord['environment'];
  owner?: string;
}

export interface ApiParameter {
  name: string;
  location: 'QUERY' | 'HEADER' | 'BODY';
  type: 'STRING' | 'INTEGER' | 'LONG' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'DATETIME';
  required: boolean;
  defaultValue?: string;
  description?: string;
}

export interface DataApiRecord {
  id: number;
  datasetId: number;
  name: string;
  description?: string;
  path: string;
  method: string;
  querySql: string;
  parameters: ApiParameter[];
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE';
  version: number;
  latestVersionStatus: 'DRAFT' | 'PENDING_APPROVAL' | 'REJECTED' | 'PUBLISHED' | 'ARCHIVED';
  publishedVersion?: number;
  cacheTtlSeconds?: number;
  maxPageSize: number;
  publishedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ApiVersionRecord {
  id: number;
  apiId: number;
  versionNo: number;
  datasetId: number;
  name: string;
  description?: string;
  path: string;
  method: string;
  querySql: string;
  parameters: ApiParameter[];
  cacheTtlSeconds?: number;
  maxPageSize: number;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'REJECTED' | 'PUBLISHED' | 'ARCHIVED';
  changeSummary?: string;
  createdBy: string;
  submittedBy?: string;
  submittedAt?: string;
  reviewedBy?: string;
  reviewedAt?: string;
  reviewComment?: string;
  publishedAt?: string;
  sourceVersionId?: number;
  createdAt: string;
}

export interface CallLogRecord {
  id: number;
  apiId?: number;
  requestId?: string;
  traceId?: string;
  appKey?: string;
  apiPath: string;
  method: string;
  statusCode: number;
  elapsedMs: number;
  rowCount?: number;
  testCall: boolean;
  clientIp?: string;
  errorMessage?: string;
  occurredAt: string;
}

export interface ApplicationRecord {
  id: number;
  appKey: string;
  name: string;
  description?: string;
  status: 'ENABLED' | 'DISABLED';
  qpsLimit: number;
  secretVersion: number;
  lastRotatedAt?: string;
  createdAt: string;
  updatedAt: string;
  authorizedApiIds: number[];
}

export interface ApiSubscriptionRecord {
  id: number;
  appId: number;
  appName: string;
  appKey: string;
  apiId: number;
  apiName: string;
  apiPath: string;
  apiMethod: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
  requestReason?: string;
  qpsLimit: number;
  dailyLimit: number;
  dailyUsed: number;
  validFrom?: string;
  validUntil?: string;
  ipAllowlist: string[];
  requestedBy: string;
  requestedAt: string;
  reviewedBy?: string;
  reviewedAt?: string;
  reviewComment?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreatedApplication {
  application: ApplicationRecord;
  appSecret: string;
}

export interface ApplicationSecretVersion {
  id: number;
  appId: number;
  secretVersion: number;
  status: 'ACTIVE' | 'GRACE' | 'REVOKED';
  expiresAt?: string;
  lastUsedAt?: string;
  createdBy: string;
  createdAt: string;
  revokedBy?: string;
  revokedAt?: string;
}

export interface OpenApiHealth {
  service: string;
  time: string;
}

export interface AdminUser {
  id: number;
  username: string;
  displayName: string;
  status: string;
  lastLoginAt?: string;
  roles: string[];
  permissions: string[];
}

export interface AdminSession {
  expiresAt: string;
  user: AdminUser;
}

export interface AdminRole {
  code: string;
  name: string;
  description?: string;
}

export interface ExecutionResult {
  requestId: string;
  traceId?: string;
  apiId: number;
  apiName: string;
  page: number;
  pageSize: number;
  rowCount: number;
  elapsedMs: number;
  cacheStatus: 'HIT' | 'MISS' | 'STALE' | 'BYPASS';
  degraded: boolean;
  rows: Array<Record<string, unknown>>;
}

export interface RuntimeSnapshot {
  cache: {
    hits: number;
    misses: number;
    staleFallbacks: number;
    bypasses: number;
    hitRate: number;
    redisAvailable: boolean;
    lastRedisError?: string;
  };
  resilience: {
    concurrencyRejected: number;
    circuitRejected: number;
    circuits: Array<{
      apiId: number;
      status: 'CLOSED' | 'OPEN' | 'HALF_OPEN';
      activeRequests: number;
      consecutiveFailures: number;
      openUntil?: string;
    }>;
  };
}

export interface SloRuleRecord {
  id: number;
  apiId: number;
  name: string;
  enabled: boolean;
  windowMinutes: number;
  minRequests: number;
  minSuccessRate: number;
  maxP95Ms: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface AlertEventRecord {
  id: number;
  ruleId: number;
  apiId: number;
  alertType: 'SUCCESS_RATE' | 'LATENCY_P95';
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
  observedValue: number;
  thresholdValue: number;
  sampleCount: number;
  message: string;
  acknowledgedBy?: string;
  acknowledgedAt?: string;
  resolvedAt?: string;
  openedAt: string;
  updatedAt: string;
}

export interface ChangeRequestRecord {
  id: number;
  actionType: string;
  targetType: string;
  targetId: number;
  targetSummary: string;
  environment: string;
  payloadJson: string;
  requester: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  approver?: string;
  decisionComment?: string;
  decidedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface PendingChangeResult {
  pendingApproval: true;
  changeRequest: ChangeRequestRecord;
}

export interface NotificationChannelRecord {
  id: number;
  name: string;
  channelType: 'WEBHOOK' | 'DINGTALK' | 'WECHAT';
  endpointConfigured: boolean;
  enabled: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface NotificationDeliveryRecord {
  id: number;
  channelId: number;
  alertEventId?: number;
  eventType: string;
  status: 'PENDING' | 'PROCESSING' | 'RETRY' | 'SENT' | 'DEAD';
  attempts: number;
  nextAttemptAt: string;
  lastError?: string;
  sentAt?: string;
  createdAt: string;
}

export interface OperationAuditRecord {
  id: number;
  actor?: string;
  clientIp?: string;
  traceId?: string;
  httpMethod: string;
  requestPath: string;
  operation?: string;
  resourceId?: string;
  status: 'SUCCESS' | 'FAILURE';
  statusCode: number;
  errorMessage?: string;
  previousHash?: string;
  recordHash: string;
  occurredAt: string;
}

export interface AuditIntegrity {
  valid: boolean;
  checkedRecords: number;
  brokenRecordId?: number;
}

export interface ApiTestRequest {
  parameters: Record<string, unknown>;
  page: number;
  pageSize: number;
}

const admin = axios.create({
  baseURL: '/data-service-admin',
  timeout: 15000,
  withCredentials: true
});

admin.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401
        && !String(error.config?.url).includes('/auth/login')) {
      window.dispatchEvent(new Event('data-service-auth-expired'));
    }
    return Promise.reject(error);
  }
);

const openapi = axios.create({
  baseURL: '/openapi',
  timeout: 10000,
  headers: { 'X-App-Key': 'dev-console' }
});

openapi.interceptors.request.use((config) => {
  config.headers.set('X-Timestamp', String(Date.now()));
  return config;
});

function unwrap<T>(response: { data: ApiResponse<T> }) {
  if (response.data.code !== 0) {
    throw new Error(response.data.message);
  }
  return response.data.data;
}

export function errorMessage(error: unknown, fallback: string) {
  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    return error.response?.data?.message || error.message || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

export function listDatasets() {
  return admin.get<ApiResponse<DatasetRecord[]>>('/datasets').then(unwrap);
}

export function loginAdmin(username: string, password: string) {
  return admin
    .post<ApiResponse<AdminSession>>('/auth/login', { username, password })
    .then(unwrap);
}

export function currentAdmin() {
  return admin.get<ApiResponse<AdminUser>>('/auth/me').then(unwrap);
}

export function logoutAdmin() {
  return admin.post<ApiResponse<void>>('/auth/logout').then(unwrap);
}

export function listAdminUsers() {
  return admin.get<ApiResponse<AdminUser[]>>('/admin-users').then(unwrap);
}

export function listAdminRoles() {
  return admin.get<ApiResponse<AdminRole[]>>('/admin-users/roles').then(unwrap);
}

export function createAdminUser(payload: {
  username: string;
  displayName: string;
  password: string;
  roleCodes: string[];
}) {
  return admin.post<ApiResponse<AdminUser>>('/admin-users', payload).then(unwrap);
}

export function replaceAdminUserRoles(id: number, roleCodes: string[]) {
  return admin
    .post<ApiResponse<AdminUser>>(`/admin-users/${id}/roles`, { roleCodes })
    .then(unwrap);
}

export function changeAdminUserStatus(id: number, action: 'ENABLE' | 'DISABLE') {
  return admin
    .post<ApiResponse<AdminUser>>(`/admin-users/${id}/status`, { action })
    .then(unwrap);
}

export function resetAdminUserPassword(id: number, password: string) {
  return admin
    .post<ApiResponse<void>>(`/admin-users/${id}/reset-password`, { password })
    .then(unwrap);
}

export function createDataset(payload: Partial<DatasetRecord>) {
  return admin.post<ApiResponse<DatasetRecord>>('/datasets', payload).then(unwrap);
}

export function getDatasetAccessPolicy(id: number) {
  return admin
    .get<ApiResponse<DatasetAccessPolicy>>(`/datasets/${id}/policy`)
    .then(unwrap);
}

export function updateDatasetAccessPolicy(
  id: number,
  payload: Pick<DatasetAccessPolicy, 'rowFilterSql' | 'columns'>
) {
  return admin
    .put<ApiResponse<DatasetAccessPolicy | PendingChangeResult>>(`/datasets/${id}/policy`, payload)
    .then(unwrap);
}

export function listDataSources() {
  return admin.get<ApiResponse<DataSourceRecord[]>>('/data-sources').then(unwrap);
}

export function createDataSource(payload: SaveDataSourceRequest) {
  return admin.post<ApiResponse<DataSourceRecord>>('/data-sources', payload).then(unwrap);
}

export function updateDataSource(id: number, payload: SaveDataSourceRequest) {
  return admin.put<ApiResponse<DataSourceRecord>>(`/data-sources/${id}`, payload).then(unwrap);
}

export function testDataSource(id: number) {
  return admin.post<ApiResponse<DataSourceRecord>>(`/data-sources/${id}/test`).then(unwrap);
}

export function changeDataSourceStatus(id: number, action: 'ENABLE' | 'DISABLE') {
  return admin.post<ApiResponse<DataSourceRecord>>(`/data-sources/${id}/status`, { action }).then(unwrap);
}

export function listApis() {
  return admin.get<ApiResponse<DataApiRecord[]>>('/apis').then(unwrap);
}

export function createApi(payload: Partial<DataApiRecord>) {
  return admin.post<ApiResponse<DataApiRecord>>('/apis', payload).then(unwrap);
}

export function updateApi(id: number, payload: Partial<DataApiRecord> & { changeSummary: string }) {
  return admin.put<ApiResponse<DataApiRecord>>(`/apis/${id}`, payload).then(unwrap);
}

export function listApiVersions(id: number) {
  return admin.get<ApiResponse<ApiVersionRecord[]>>(`/apis/${id}/versions`).then(unwrap);
}

export function submitApiForApproval(id: number) {
  return admin.post<ApiResponse<ApiVersionRecord>>(`/apis/${id}/submit`).then(unwrap);
}

export function reviewApiVersion(
  id: number,
  versionNo: number,
  action: 'APPROVE' | 'REJECT',
  comment?: string
) {
  return admin
    .post<ApiResponse<DataApiRecord | ApiVersionRecord>>(
      `/apis/${id}/versions/${versionNo}/review`,
      { action, comment }
    )
    .then(unwrap);
}

export function rollbackApiVersion(id: number, versionNo: number, changeSummary: string) {
  return admin
    .post<ApiResponse<DataApiRecord>>(
      `/apis/${id}/versions/${versionNo}/rollback`,
      { changeSummary }
    )
    .then(unwrap);
}

export function changeApiStatus(id: number, action: 'OFFLINE') {
  return admin.post<ApiResponse<DataApiRecord>>(`/apis/${id}/status`, { action }).then(unwrap);
}

export function testApi(id: number, payload: ApiTestRequest) {
  return admin.post<ApiResponse<ExecutionResult>>(`/apis/${id}/test`, payload).then(unwrap);
}

export function listCallLogs(limit = 100) {
  return admin.get<ApiResponse<CallLogRecord[]>>('/call-logs', { params: { limit } }).then(unwrap);
}

export function getRuntimeMetrics() {
  return admin.get<ApiResponse<RuntimeSnapshot>>('/runtime/metrics').then(unwrap);
}

export function evictApiCache(apiId: number) {
  return admin
    .post<ApiResponse<{ apiId: number; cacheEpoch: number }>>(`/runtime/cache/apis/${apiId}/evict`)
    .then(unwrap);
}

export function listSloRules() {
  return admin.get<ApiResponse<SloRuleRecord[]>>('/slo/rules').then(unwrap);
}

export function createSloRule(payload: Omit<SloRuleRecord, 'id' | 'createdBy' | 'createdAt' | 'updatedAt'>) {
  return admin.post<ApiResponse<SloRuleRecord>>('/slo/rules', payload).then(unwrap);
}

export function updateSloRule(
  id: number,
  payload: Omit<SloRuleRecord, 'id' | 'createdBy' | 'createdAt' | 'updatedAt'>
) {
  return admin.put<ApiResponse<SloRuleRecord>>(`/slo/rules/${id}`, payload).then(unwrap);
}

export function evaluateSloRule(id: number) {
  return admin.post<ApiResponse<{
    ruleId: number;
    statistics: { sampleCount: number; successRate: number; p95Ms: number };
    evaluated: boolean;
    breachCount: number;
  }>>(`/slo/rules/${id}/evaluate`).then(unwrap);
}

export function listSloAlerts(limit = 200) {
  return admin
    .get<ApiResponse<AlertEventRecord[]>>('/slo/alerts', { params: { limit } })
    .then(unwrap);
}

export function acknowledgeSloAlert(id: number) {
  return admin
    .post<ApiResponse<AlertEventRecord>>(`/slo/alerts/${id}/acknowledge`)
    .then(unwrap);
}

export function resolveSloAlert(id: number) {
  return admin
    .post<ApiResponse<AlertEventRecord>>(`/slo/alerts/${id}/resolve`)
    .then(unwrap);
}

export function listNotificationChannels() {
  return admin.get<ApiResponse<NotificationChannelRecord[]>>('/governance/channels').then(unwrap);
}

export function saveNotificationChannel(
  id: number | undefined,
  payload: {
    name: string;
    channelType: NotificationChannelRecord['channelType'];
    endpoint?: string;
    enabled: boolean;
  }
) {
  return id
    ? admin.put<ApiResponse<NotificationChannelRecord>>(`/governance/channels/${id}`, payload).then(unwrap)
    : admin.post<ApiResponse<NotificationChannelRecord>>('/governance/channels', payload).then(unwrap);
}

export function testNotificationChannel(id: number) {
  return admin.post<ApiResponse<void>>(`/governance/channels/${id}/test`).then(unwrap);
}

export function listNotificationDeliveries(limit = 100) {
  return admin
    .get<ApiResponse<NotificationDeliveryRecord[]>>('/governance/deliveries', { params: { limit } })
    .then(unwrap);
}

export function listOperationAudits(limit = 100) {
  return admin
    .get<ApiResponse<OperationAuditRecord[]>>('/governance/audits', { params: { limit } })
    .then(unwrap);
}

export function getAuditIntegrity() {
  return admin
    .get<ApiResponse<AuditIntegrity>>('/governance/audits/integrity')
    .then(unwrap);
}

export function listChangeRequests(limit = 100) {
  return admin
    .get<ApiResponse<ChangeRequestRecord[]>>('/change-requests', { params: { limit } })
    .then(unwrap);
}

export function decideChangeRequest(id: number, action: 'approve' | 'reject', comment?: string) {
  return admin
    .post<ApiResponse<ChangeRequestRecord>>(`/change-requests/${id}/${action}`, { comment })
    .then(unwrap);
}

export function listApplications() {
  return admin.get<ApiResponse<ApplicationRecord[]>>('/applications').then(unwrap);
}

export function createApplication(payload: {
  appKey?: string;
  name: string;
  description?: string;
  qpsLimit: number;
}) {
  return admin.post<ApiResponse<CreatedApplication>>('/applications', payload).then(unwrap);
}

export function changeApplicationStatus(id: number, action: 'ENABLE' | 'DISABLE') {
  return admin.post<ApiResponse<ApplicationRecord>>(`/applications/${id}/status`, { action }).then(unwrap);
}

export function rotateApplicationSecret(id: number, graceHours = 24) {
  return admin
    .post<ApiResponse<CreatedApplication>>(`/applications/${id}/rotate-secret`, { graceHours })
    .then(unwrap);
}

export function listApplicationCredentials(id: number) {
  return admin
    .get<ApiResponse<ApplicationSecretVersion[]>>(`/applications/${id}/credentials`)
    .then(unwrap);
}

export function revokeApplicationCredential(id: number, version: number) {
  return admin
    .post<ApiResponse<ApplicationSecretVersion>>(`/applications/${id}/credentials/${version}/revoke`)
    .then(unwrap);
}

export function replaceApplicationAuthorizations(id: number, apiIds: number[]) {
  return admin
    .post<ApiResponse<ApplicationRecord>>(`/applications/${id}/authorizations`, { apiIds })
    .then(unwrap);
}

export function listApiSubscriptions() {
  return admin.get<ApiResponse<ApiSubscriptionRecord[]>>('/subscriptions').then(unwrap);
}

export function submitApiSubscription(payload: {
  appId: number;
  apiId: number;
  reason?: string;
  qpsLimit: number;
  dailyLimit: number;
  validFrom?: string;
  validUntil?: string;
  ipAllowlist: string[];
}) {
  return admin.post<ApiResponse<ApiSubscriptionRecord>>('/subscriptions', payload).then(unwrap);
}

export function reviewApiSubscription(
  id: number,
  payload: {
    action: 'APPROVE' | 'REJECT';
    qpsLimit: number;
    dailyLimit: number;
    validFrom?: string;
    validUntil?: string;
    ipAllowlist: string[];
    comment?: string;
  }
) {
  return admin.post<ApiResponse<ApiSubscriptionRecord>>(`/subscriptions/${id}/review`, payload).then(unwrap);
}

export function suspendApiSubscription(id: number, comment?: string) {
  return admin
    .post<ApiResponse<ApiSubscriptionRecord>>(`/subscriptions/${id}/suspend`, { comment })
    .then(unwrap);
}

export function checkOpenApiHealth() {
  return openapi.get<ApiResponse<OpenApiHealth>>('/health').then(unwrap);
}

export function listDeveloperPortalApis() {
  return admin.get<ApiResponse<DataApiRecord[]>>('/developer-portal/apis').then(unwrap);
}

export function getDeveloperPortalOpenApi(id: number) {
  return admin
    .get<ApiResponse<Record<string, unknown>>>(`/developer-portal/apis/${id}/openapi`)
    .then(unwrap);
}
