import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './flinkSqlJobs';

export interface AlertHistoryRecord {
  id: number;
  entityType: string;
  entityId: number;
  entityName: string;
  dimension: string;
  state: string;
  message?: string;
  occurredAt: string;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageAlertHistory(params: { current: number; pageSize: number; entityType?: string; entityId?: number }) {
  return http.get<ApiResponse<PageResult<AlertHistoryRecord>>>('/realtime/alert-history', { params }).then(unwrap);
}

export interface AlertTrendPoint {
  hour: string;
  count: number;
}

export function getAlertTrend(hours = 24) {
  return http.get<ApiResponse<AlertTrendPoint[]>>('/realtime/alert-history/trend', { params: { hours } }).then(unwrap);
}
