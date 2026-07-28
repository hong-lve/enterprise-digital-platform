import { http } from './http';
import type { ApiResponse } from './auth';

export interface AlertingItem {
  id: number;
  name: string;
  owner?: string;
  message?: string;
}

export interface RealtimeOverview {
  alertingFlinkJobs: AlertingItem[];
  alertingSqlJobs: AlertingItem[];
  alertingCdcSources: AlertingItem[];
}

export function getRealtimeOverview() {
  return http.get<ApiResponse<RealtimeOverview>>('/realtime/overview').then((response) => response.data.data);
}
