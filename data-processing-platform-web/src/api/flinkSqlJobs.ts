import { http } from './http';
import type { ApiResponse } from './auth';
import type { ActionResult } from './approval';

export interface PageResult<T> {
  total: number;
  records: T[];
}

export interface FlinkSqlJobRecord {
  id: number;
  name: string;
  sqlScript: string;
  parallelism: number;
  checkpointIntervalMs: number;
  flinkJobId?: string;
  savepointPath?: string;
  status: string;
  lastError?: string;
  /** Fraction (0.0-1.0) of the last poll interval spent backpressured; undefined until two samples exist. */
  backpressureRatio?: number;
  backpressureAlertState?: string;
  /** Optional - only set if this job's SQL consumes Kafka; leave both blank to skip consumer-lag monitoring. */
  kafkaConsumerGroupId?: string;
  kafkaTopics?: string;
  consumerLagRecords?: number;
  consumerLagAlertState?: string;
  /** Optional, comma-separated - ClickHouse tables this job writes to (via 'connector'='jdbc'), for the lineage view. See V11__flink_sql_job_clickhouse_sink.sql. */
  clickhouseSinkTables?: string;
  /** Optional, comma-separated - Doris tables this job writes to (via 'connector'='doris'), for the lineage view. See V12__flink_sql_job_doris_sink.sql. */
  dorisSinkTables?: string;
  /** Optional, comma-separated - Oracle tables this job writes to (via 'connector'='jdbc'), for the lineage view. See V19__flink_sql_job_oracle_sink.sql. */
  oracleSinkTables?: string;
  /** DEV/STAGING/PROD - logical tag only, see V9__environment_field.sql. */
  environment: string;
  owner?: string;
  createdAt?: string;
  updatedAt?: string;
}

const basePath = '/realtime/sql-jobs';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageFlinkSqlJobs(params: { current: number; pageSize: number }) {
  return http.get<ApiResponse<PageResult<FlinkSqlJobRecord>>>(basePath, { params }).then(unwrap);
}

export function createFlinkSqlJob(data: Partial<FlinkSqlJobRecord>) {
  return http.post<ApiResponse<FlinkSqlJobRecord>>(basePath, data).then(unwrap);
}

export function updateFlinkSqlJob(id: number, data: Partial<FlinkSqlJobRecord>) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteFlinkSqlJob(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function startFlinkSqlJob(id: number) {
  // Backend budgets up to 8s PER STATEMENT (see
  // FlinkSqlJobSubmissionService.PER_STATEMENT_BUDGET_MILLIS) while
  // submitting each CREATE TABLE/INSERT INTO in the script - a script with
  // several statements can take longer than the shared http instance's
  // default 10s timeout (same timeout-race class of bug already hit and
  // fixed once for the interactive SQL query page).
  return http.post<ApiResponse<FlinkSqlJobRecord>>(`${basePath}/${id}/start`, undefined, { timeout: 30000 }).then(unwrap);
}

export function stopFlinkSqlJob(id: number) {
  return http.post<ApiResponse<FlinkSqlJobRecord>>(`${basePath}/${id}/stop`).then(unwrap);
}

export function refreshFlinkSqlJobStatus(id: number) {
  return http.get<ApiResponse<FlinkSqlJobRecord>>(`${basePath}/${id}/status`).then(unwrap);
}

export function clearFlinkSqlJobSavepoint(id: number) {
  return http.post<ApiResponse<FlinkSqlJobRecord>>(`${basePath}/${id}/clear-savepoint`).then(unwrap);
}

export type ReplayMode = 'NORMAL' | 'FROM_EARLIEST' | 'FROM_TIMESTAMP';

// Same extended timeout as startFlinkSqlJob() - this goes through the exact
// same per-statement-budgeted submission path (FlinkSqlJobSubmissionService).
export function replayFlinkSqlJob(id: number, mode: ReplayMode, timestampMillis?: number) {
  return http.post<ApiResponse<ActionResult>>(`${basePath}/${id}/replay`, { mode, timestampMillis }, { timeout: 30000 }).then(unwrap);
}

// See FlinkStreamJobController.rollback()/applyRollback() - identical semantics for SQL jobs.
export function rollbackFlinkSqlJob(id: number, versionNo: number) {
  return http.post<ApiResponse<ActionResult>>(`${basePath}/${id}/rollback/${versionNo}`).then(unwrap);
}
