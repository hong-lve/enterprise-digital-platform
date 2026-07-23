import { http } from './http';
import type { ApiResponse } from './auth';

export interface SinkTableLineage {
  table: string;
  /** 'CLICKHOUSE' | 'DORIS' | 'ORACLE' | 'REDIS' - see LineageController.java. */
  sinkType: string;
}

export interface FlinkJobLineage {
  id: number;
  name: string;
  status: string;
  /** 'JAR' | 'SQL' - see LineageController.java. */
  jobType: string;
  sinkTables: SinkTableLineage[];
}

export interface TopicLineage {
  topic: string;
  consumers: FlinkJobLineage[];
}

export interface CdcSourceLineage {
  id: number;
  name: string;
  status: string;
  topics: TopicLineage[];
}

export interface LineageView {
  cdcSources: CdcSourceLineage[];
  orphanFlinkJobs: FlinkJobLineage[];
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function getLineage() {
  return http.get<ApiResponse<LineageView>>('/realtime/lineage').then(unwrap);
}
