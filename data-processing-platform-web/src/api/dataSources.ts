import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export type DataSourceType = 'MYSQL' | 'CLICKHOUSE' | 'DORIS' | 'ORACLE';

export interface DataSourceRecord {
  id: number;
  name: string;
  type: DataSourceType;
  host: string;
  port: number;
  username: string;
  /** WRITE_ONLY on the backend - never present on records read back from the API, only sent on create/update. */
  password?: string;
  databaseName?: string;
  /** Only needed to generate a SQL 流作业 sink WITH clause - the Flink cluster's own reachable host/port, which can differ from host/port above. */
  flinkHost?: string;
  flinkPort?: number;
  /** Doris only: FE HTTP/Stream Load port, distinct from port (FE MySQL-protocol query port). */
  flinkHttpPort?: number;
  /** Oracle CDB architecture only - the pluggable database Debezium should monitor within the CDB this data source connects to (databaseName holds the CDB root service name, e.g. "FREE"). */
  pdbName?: string;
  status: string;
  lastTestError?: string;
  environment: string;
  owner?: string;
  createdAt?: string;
  updatedAt?: string;
}

const basePath = '/realtime/data-sources';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageDataSources(params: { current: number; pageSize: number; type?: string }) {
  return http.get<ApiResponse<PageResult<DataSourceRecord>>>(basePath, { params }).then(unwrap);
}

export function createDataSource(data: Partial<DataSourceRecord>) {
  return http.post<ApiResponse<DataSourceRecord>>(basePath, data).then(unwrap);
}

export function updateDataSource(id: number, data: Partial<DataSourceRecord>) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteDataSource(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function testDataSource(id: number) {
  return http.post<ApiResponse<DataSourceRecord>>(`${basePath}/${id}/test`).then(unwrap);
}

export function listDataSourceDatabases(id: number) {
  return http.get<ApiResponse<string[]>>(`${basePath}/${id}/databases`).then(unwrap);
}
