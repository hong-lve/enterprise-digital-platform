import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export interface DataQualityRuleRecord {
  id: number;
  name: string;
  dataSourceId: number;
  databaseName?: string;
  tableName: string;
  /** NULL_RATE, UNIQUENESS, VALUE_RANGE, PK_DUPLICATE, or FRESHNESS */
  ruleType: string;
  /** The column being checked - for PK_DUPLICATE the primary key column, for FRESHNESS a timestamp column. */
  columnName: string;
  /** VALUE_RANGE only: minimum allowed value. */
  thresholdMin?: number;
  /** VALUE_RANGE max, or the max allowed null-rate/duplicate-rate fraction (0-1), or max allowed staleness in seconds for FRESHNESS. */
  thresholdMax?: number;
  enabled: boolean;
  /** OK/VIOLATION/ERROR */
  lastResult?: string;
  lastMetricValue?: number;
  lastViolationCount?: number;
  lastCheckedAt?: string;
  lastError?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface DataQualityViolationRecord {
  id: number;
  ruleId: number;
  rowIdentifier: string;
  detail?: string;
  detectedAt: string;
}

const basePath = '/realtime/data-quality';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageDataQualityRules(params: { current: number; pageSize: number }) {
  return http.get<ApiResponse<PageResult<DataQualityRuleRecord>>>(basePath, { params }).then(unwrap);
}

export function createDataQualityRule(data: Partial<DataQualityRuleRecord>) {
  return http.post<ApiResponse<DataQualityRuleRecord>>(basePath, data).then(unwrap);
}

export function updateDataQualityRule(id: number, data: Partial<DataQualityRuleRecord>) {
  return http.put<ApiResponse<DataQualityRuleRecord>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteDataQualityRule(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function runDataQualityRule(id: number) {
  return http.post<ApiResponse<DataQualityRuleRecord>>(`${basePath}/${id}/run`).then(unwrap);
}

export function fetchDataQualityViolations(id: number) {
  return http.get<ApiResponse<DataQualityViolationRecord[]>>(`${basePath}/${id}/violations`).then(unwrap);
}
