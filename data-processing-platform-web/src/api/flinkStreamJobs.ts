import { http } from './http';
import type { ApiResponse } from './auth';
import type { ActionResult } from './approval';

export interface PageResult<T> {
  total: number;
  records: T[];
}

export interface FlinkStreamJobRecord {
  id: number;
  name: string;
  jarPath: string;
  entryClass?: string;
  programArgs?: string;
  parallelism: number;
  checkpointIntervalMs: number;
  restartStrategy: string;
  restartAttempts?: number;
  restartDelaySeconds?: number;
  flinkJobId?: string;
  savepointPath?: string;
  status: string;
  lastError?: string;
  /** Checkpoint governance - see FlinkStreamSubmissionClient.buildFlinkConfiguration(); all optional, server defaults to Flink's own out-of-the-box values when blank. */
  checkpointTimeoutMs?: number;
  minPauseBetweenCheckpointsMs?: number;
  maxConcurrentCheckpoints?: number;
  tolerableFailedCheckpoints?: number;
  checkpointingMode?: string;
  externalizedCheckpointRetention?: string;
  unalignedCheckpointsEnabled?: boolean;
  checkpointFailureAlertState?: string;
  /** How many of this job's most recent savepoints FlinkSavepointRetentionScheduler keeps; blank/0 disables auto-disposal. */
  savepointRetentionCount?: number;
  /** Fraction (0.0-1.0) of the last poll interval spent backpressured; undefined until two samples exist. */
  backpressureRatio?: number;
  backpressureAlertState?: string;
  /** Optional - only set if this job's jar consumes Kafka; leave both blank to skip consumer-lag monitoring. */
  kafkaConsumerGroupId?: string;
  kafkaTopics?: string;
  consumerLagRecords?: number;
  consumerLagAlertState?: string;
  /** Optional - ClickHouse table(s) this job writes to, comma-separated, for the lineage view. */
  clickhouseSinkTables?: string;
  /** DEV/STAGING/PROD - logical tag only, see V9__environment_field.sql. */
  environment: string;
  owner?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface FlinkCheckpointHistoryRecord {
  id: number;
  jobId: number;
  flinkJobId: string;
  checkpointId: number;
  checkpointType: string;
  status: string;
  triggerTimestamp?: number;
  latestAckTimestamp?: number;
  endToEndDurationMs?: number;
  stateSizeBytes?: number;
  externalPath?: string;
  failureMessage?: string;
  disposed: boolean;
  restoreOutcome?: string;
  restoreCheckedAt?: string;
}

const basePath = '/realtime/flink-jobs';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageFlinkStreamJobs(params: { current: number; pageSize: number }) {
  return http.get<ApiResponse<PageResult<FlinkStreamJobRecord>>>(basePath, { params }).then(unwrap);
}

export function createFlinkStreamJob(data: Partial<FlinkStreamJobRecord>) {
  return http.post<ApiResponse<FlinkStreamJobRecord>>(basePath, data).then(unwrap);
}

export function updateFlinkStreamJob(id: number, data: Partial<FlinkStreamJobRecord>) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
}

// For a RUNNING job: stops it with a savepoint, saves the new definition,
// then resumes from that savepoint under the new config - one guarded
// action instead of manually editing + stopping + starting. See
// FlinkStreamJobController.upgrade()'s own comment for why this exists
// (a scaled-down stand-in for Flink Kubernetes Operator's Savepoint Upgrade
// Mode, without needing an actual K8s cluster on this hardware).
export function upgradeFlinkStreamJob(id: number, data: Partial<FlinkStreamJobRecord>) {
  return http.post<ApiResponse<ActionResult>>(`${basePath}/${id}/upgrade`, data).then(unwrap);
}

export function deleteFlinkStreamJob(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function startFlinkStreamJob(id: number) {
  return http.post<ApiResponse<FlinkStreamJobRecord>>(`${basePath}/${id}/start`).then(unwrap);
}

export function stopFlinkStreamJob(id: number) {
  return http.post<ApiResponse<FlinkStreamJobRecord>>(`${basePath}/${id}/stop`).then(unwrap);
}

export function refreshFlinkStreamJobStatus(id: number) {
  return http.get<ApiResponse<FlinkStreamJobRecord>>(`${basePath}/${id}/status`).then(unwrap);
}

export function clearFlinkStreamJobSavepoint(id: number) {
  return http.post<ApiResponse<FlinkStreamJobRecord>>(`${basePath}/${id}/clear-savepoint`).then(unwrap);
}

// Redeploys an earlier version's exact recorded config, resuming from that
// version's own recorded savepoint (not wherever the current run left off) -
// see FlinkStreamJobController.rollback()/applyRollback().
export function rollbackFlinkStreamJob(id: number, versionNo: number) {
  return http.post<ApiResponse<ActionResult>>(`${basePath}/${id}/rollback/${versionNo}`).then(unwrap);
}

export function fetchFlinkStreamJobCheckpoints(id: number) {
  return http.get<ApiResponse<FlinkCheckpointHistoryRecord[]>>(`${basePath}/${id}/checkpoints`).then(unwrap);
}

export function disposeFlinkStreamJobCheckpoint(id: number, checkpointId: number) {
  return http.post<ApiResponse<void>>(`${basePath}/${id}/checkpoints/${checkpointId}/dispose`).then(unwrap);
}
