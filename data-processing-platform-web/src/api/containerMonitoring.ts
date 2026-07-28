import { http } from './http';
import type { ApiResponse } from './auth';

export interface ContainerStatusRecord {
  id: number;
  containerName: string;
  node: string;
  image?: string;
  state: string;
  statusText?: string;
  dockerRestartCount: number;
  cumulativeRestartCount: number;
  startedAt?: string;
  lastPolledAt: string;
}

export interface ContainerEventRecord {
  id: number;
  containerName: string;
  eventType: string;
  detail?: string;
  occurredAt: string;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function listContainerStatus() {
  return http.get<ApiResponse<ContainerStatusRecord[]>>('/realtime/container-monitoring').then(unwrap);
}

export function listContainerEvents(name: string, limit = 50) {
  return http
    .get<ApiResponse<ContainerEventRecord[]>>(`/realtime/container-monitoring/${encodeURIComponent(name)}/events`, { params: { limit } })
    .then(unwrap);
}
