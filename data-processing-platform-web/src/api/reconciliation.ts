import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export interface ReconciliationCheckRecord {
  id: number;
  name: string;
  sourceDataSourceId: number;
  sourceDatabase?: string;
  sourceTable: string;
  targetDataSourceId: number;
  targetDatabase?: string;
  /** Table name for the JDBC target. */
  targetTable: string;
  tolerance: number;
  enabled: boolean;
  /** ROW_COUNT (default) or AGGREGATE - see DataReconciliationService. */
  checkType?: string;
  /** Numeric column to SUM and compare - required when checkType is AGGREGATE. */
  aggregateColumn?: string;
  /** Optional - breaks the comparison into a per-partition-value GROUP BY instead of one table-wide number. */
  partitionColumn?: string;
  lastSourceAggregate?: number;
  lastTargetAggregate?: number;
  partitionDriftSummary?: string;
  lastSourceCount?: number;
  lastTargetCount?: number;
  lastCheckedAt?: string;
  /** OK/DRIFT/ERROR */
  lastState?: string;
  lastError?: string;
  createdAt?: string;
  updatedAt?: string;
}

const basePath = '/realtime/reconciliation';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageReconciliationChecks(params: { current: number; pageSize: number }) {
  return http.get<ApiResponse<PageResult<ReconciliationCheckRecord>>>(basePath, { params }).then(unwrap);
}

export function createReconciliationCheck(data: Partial<ReconciliationCheckRecord>) {
  return http.post<ApiResponse<ReconciliationCheckRecord>>(basePath, data).then(unwrap);
}

export function updateReconciliationCheck(id: number, data: Partial<ReconciliationCheckRecord>) {
  return http.put<ApiResponse<ReconciliationCheckRecord>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteReconciliationCheck(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function runReconciliationCheck(id: number) {
  return http.post<ApiResponse<ReconciliationCheckRecord>>(`${basePath}/${id}/run`).then(unwrap);
}
