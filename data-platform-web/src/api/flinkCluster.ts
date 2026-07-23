import { http } from './http';
import type { ApiResponse } from './auth';

export interface OrphanedFlinkJob {
  jobId: string;
  name: string;
  state: string;
  startTime: number;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function listOrphanedFlinkJobs() {
  return http.get<ApiResponse<OrphanedFlinkJob[]>>('/realtime/flink-cluster/orphaned-jobs').then(unwrap);
}

export function cancelOrphanedFlinkJob(jobId: string) {
  return http.post<ApiResponse<void>>(`/realtime/flink-cluster/orphaned-jobs/${jobId}/cancel`).then(unwrap);
}
