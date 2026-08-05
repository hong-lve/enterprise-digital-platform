import { http } from './http';
import type { ApiResponse } from './auth';

export interface SinkTableLineage {
  table: string;
  /** 'CLICKHOUSE' | 'DORIS' | 'ORACLE' - see LineageController.java. */
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

export interface SqlSourceColumnRef {
  table?: string;
  column: string;
}

export interface SqlColumnLineage {
  targetColumn: string;
  sourceColumns: SqlSourceColumnRef[];
  expression: string;
}

export interface SqlTableLineage {
  tableName: string;
  connectorType: string;
  physicalLocation: string;
  columns: string[];
}

export interface SqlLineageResult {
  tables: SqlTableLineage[];
  targetTable?: string;
  columnLineages: SqlColumnLineage[];
  warnings: string[];
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function getLineage() {
  return http.get<ApiResponse<LineageView>>('/realtime/lineage').then(unwrap);
}

/** Parses a SQL job's own CREATE TABLE/INSERT INTO statements on demand - see FlinkSqlLineageParser.java. */
export function fetchSqlJobColumnLineage(id: number) {
  return http.get<ApiResponse<SqlLineageResult>>(`/realtime/lineage/sql-jobs/${id}/columns`).then(unwrap);
}
