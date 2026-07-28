import { http } from './http';
import type { ApiResponse } from './auth';

export interface PageResult<T> {
  total: number;
  records: T[];
}

export interface CdcSourceRecord {
  id: number;
  name: string;
  /** References data_source.id - must be a type='MYSQL' data source (enforced by the backend). */
  dataSourceId: number;
  databaseName: string;
  tableIncludeList: string;
  topicPrefix: string;
  connectorName?: string;
  status: string;
  lastError?: string;
  /** Seconds since the most recent CDC message across this source's topics; undefined/null if never received one. */
  lagSeconds?: number;
  lagAlertState?: string;
  /** DEV/STAGING/PROD - logical tag only, see V9__environment_field.sql. */
  environment: string;
  owner?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CdcTableDdl {
  columns: string[];
  primaryKeys: string[];
  hasPrimaryKey: boolean;
  ddl: string;
}

const basePath = '/realtime/cdc-sources';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageCdcSources(params: { current: number; pageSize: number }) {
  return http.get<ApiResponse<PageResult<CdcSourceRecord>>>(basePath, { params }).then(unwrap);
}

export function createCdcSource(data: Partial<CdcSourceRecord>) {
  return http.post<ApiResponse<CdcSourceRecord>>(basePath, data).then(unwrap);
}

export function updateCdcSource(id: number, data: Partial<CdcSourceRecord>) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteCdcSource(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function startCdcSource(id: number) {
  return http.post<ApiResponse<CdcSourceRecord>>(`${basePath}/${id}/start`).then(unwrap);
}

export function stopCdcSource(id: number) {
  return http.post<ApiResponse<CdcSourceRecord>>(`${basePath}/${id}/stop`).then(unwrap);
}

export function refreshCdcSourceStatus(id: number) {
  return http.get<ApiResponse<CdcSourceRecord>>(`${basePath}/${id}/status`).then(unwrap);
}

export function listCdcSourceTables(id: number) {
  return http.get<ApiResponse<string[]>>(`${basePath}/${id}/tables`).then(unwrap);
}

export function generateCdcSourceTableDdl(id: number, table: string) {
  return http.get<ApiResponse<CdcTableDdl>>(`${basePath}/${id}/table-ddl`, { params: { table } }).then(unwrap);
}

export function generateSinkDdl(id: number, table: string, targetDataSourceId: number, targetTable: string) {
  return http.get<ApiResponse<string>>(`${basePath}/${id}/sink-ddl`, { params: { table, targetDataSourceId, targetTable } }).then(unwrap);
}
