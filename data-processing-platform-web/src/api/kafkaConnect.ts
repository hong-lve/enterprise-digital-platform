import { http } from './http';
import type { ApiResponse } from './auth';

export interface OrphanedConnector {
  connectorName: string;
  state: string;
  message?: string;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function listOrphanedConnectors() {
  return http.get<ApiResponse<OrphanedConnector[]>>('/realtime/kafka-connect/orphaned-connectors').then(unwrap);
}

export function deleteOrphanedConnector(connectorName: string) {
  return http.post<ApiResponse<void>>(`/realtime/kafka-connect/orphaned-connectors/${connectorName}/delete`).then(unwrap);
}
