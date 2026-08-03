import { http } from './http';
import type { ApiResponse } from './auth';

export type JobVersionEntityType = 'FLINK_STREAM_JOB' | 'FLINK_SQL_JOB';

export interface JobVersionSnapshotRecord {
  id: number;
  entityType: JobVersionEntityType;
  entityId: number;
  versionNo: number;
  configJson: string;
  savepointPath?: string;
  flinkJobId?: string;
  changeSummary?: string;
  rollbackOfVersion?: number;
  createdBy?: string;
  createdAt?: string;
}

export interface JobVersionFieldChange {
  field: string;
  oldValue?: string;
  newValue?: string;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function fetchJobVersionHistory(entityType: JobVersionEntityType, entityId: number) {
  return http.get<ApiResponse<JobVersionSnapshotRecord[]>>(`/realtime/job-versions/${entityType}/${entityId}`).then(unwrap);
}

export function diffJobVersions(entityType: JobVersionEntityType, entityId: number, fromVersion: number, toVersion: number) {
  return http.get<ApiResponse<JobVersionFieldChange[]>>(`/realtime/job-versions/${entityType}/${entityId}/diff`, {
    params: { fromVersion, toVersion }
  }).then(unwrap);
}
