import { http } from './http';
import type { ApiResponse } from './auth';

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
