import { http } from './http';
import type { ApiResponse } from './auth';

export interface FlinkSqlResult {
  columns: string[];
  rows: Array<Record<string, unknown>>;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function executeFlinkSql(sql: string) {
  // Backend budgets up to 8s polling the SQL Gateway before giving up (see
  // FlinkSqlQueryService.TIME_BUDGET_MILLIS) - the shared http instance's
  // default 10s timeout doesn't leave enough margin for that plus network/
  // JSON overhead, so this call gets its own longer timeout.
  return http.post<ApiResponse<FlinkSqlResult>>('/realtime/flink-sql/execute', { sql }, { timeout: 15000 }).then(unwrap);
}
