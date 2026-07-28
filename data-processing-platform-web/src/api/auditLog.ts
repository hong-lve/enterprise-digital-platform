import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export interface AuditLogRecord {
  id: number;
  username?: string;
  ipAddress?: string;
  httpMethod: string;
  path: string;
  permission?: string;
  status: string;
  errorMessage?: string;
  occurredAt: string;
}

export function pageAuditLog(params: { current: number; pageSize: number; username?: string; status?: string }) {
  return http.get<ApiResponse<PageResult<AuditLogRecord>>>('/system/audit-log', { params }).then((response) => response.data.data);
}
