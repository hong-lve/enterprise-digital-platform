import { http } from './http';
import type { ApiResponse } from './auth';

export interface SchemaSubjectSummary {
  tableRef: string;
  topic: string;
  subject: string;
  versions: number[];
  latestVersion?: number;
  compatibilityLevel?: string;
}

export interface SchemaFieldTypeChange {
  field: string;
  oldType: string;
  newType: string;
}

export interface SchemaDiffResponse {
  fromVersion: number;
  toVersion: number;
  compatible: boolean;
  compatibilityDetail?: string;
  removedFields: string[];
  addedFields: string[];
  typeChanges: SchemaFieldTypeChange[];
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function fetchCdcSourceSubjects(cdcSourceId: number) {
  return http.get<ApiResponse<SchemaSubjectSummary[]>>(`/realtime/schema/cdc-sources/${cdcSourceId}/subjects`).then(unwrap);
}

export function fetchSchemaDiff(subject: string, fromVersion: number, toVersion: number) {
  return http.get<ApiResponse<SchemaDiffResponse>>('/realtime/schema/diff', { params: { subject, fromVersion, toVersion } }).then(unwrap);
}

export function setSchemaCompatibility(subject: string, level: string) {
  return http.put<ApiResponse<void>>('/realtime/schema/compatibility', { level }, { params: { subject } }).then(unwrap);
}
