import { http } from './http';
import type { ApiResponse } from './auth';

export type RecoveryEntityType = 'CDC_SOURCE' | 'FLINK_JOB';

export interface RecoveryStateRecord {
  entityType: string;
  entityId: number;
  tier: number;
  attemptsInTier: number;
  lastAttemptAt?: string;
  circuitState: string;
}

export interface RecoveryEventRecord {
  id: number;
  entityType: string;
  entityId: number;
  entityName?: string;
  eventType: string;
  detail?: string;
  occurredAt: string;
}

export interface RecoveryStatusResponse {
  state: RecoveryStateRecord;
  timeline: RecoveryEventRecord[];
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function fetchRecoveryStatus(entityType: RecoveryEntityType, entityId: number) {
  return http.get<ApiResponse<RecoveryStatusResponse>>(`/realtime/recovery/${entityType}/${entityId}`).then(unwrap);
}

export function manualTakeoverRecovery(entityType: RecoveryEntityType, entityId: number, entityName?: string) {
  return http.post<ApiResponse<void>>(`/realtime/recovery/${entityType}/${entityId}/manual-takeover`, null, { params: { entityName } }).then(unwrap);
}
