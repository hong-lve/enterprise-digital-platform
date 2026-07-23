import { http } from './http';
import type { ApiResponse } from './auth';

export interface TableInfo {
  name: string;
  type: string;
  remarks?: string;
}

export interface ColumnInfo {
  name: string;
  type: string;
  size?: number;
  remarks?: string;
}

export interface QueryResult {
  columns: string[];
  rows: Array<Record<string, unknown>>;
}

export interface RedisKeyInfo {
  key: string;
  type: string;
  /** -1 means no expiry, -2 means the key doesn't exist (shouldn't happen here since this always comes from a live SCAN). */
  ttlSeconds: number;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function listRealtimeTables(dataSourceId: number, database?: string) {
  return http.get<ApiResponse<TableInfo[]>>('/realtime/query/tables', { params: { dataSourceId, database } }).then(unwrap);
}

export function listRealtimeColumns(table: string, dataSourceId: number, database?: string) {
  return http.get<ApiResponse<ColumnInfo[]>>(`/realtime/query/tables/${encodeURIComponent(table)}/columns`, { params: { dataSourceId, database } }).then(unwrap);
}

export function executeRealtimeQuery(sql: string, dataSourceId: number, database?: string, limit = 200) {
  return http.post<ApiResponse<QueryResult>>('/realtime/query/execute', { sql, dataSourceId, database, limit }).then(unwrap);
}

export function listRedisKeys(dataSourceId: number, database?: string, pattern?: string) {
  return http.get<ApiResponse<RedisKeyInfo[]>>('/realtime/query/redis/keys', { params: { dataSourceId, database, pattern } }).then(unwrap);
}

export function getRedisValue(dataSourceId: number, key: string, database?: string) {
  return http.get<ApiResponse<QueryResult>>('/realtime/query/redis/value', { params: { dataSourceId, database, key } }).then(unwrap);
}
