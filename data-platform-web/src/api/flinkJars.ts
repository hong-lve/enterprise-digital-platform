import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export interface FlinkJarRecord {
  id: number;
  name: string;
  originalName: string;
  storedName: string;
  storagePath: string;
  sizeBytes: number;
  description?: string;
  uploader?: string;
  createdAt?: string;
}

export interface FlinkJarVersionRecord {
  id: number;
  jarId: number;
  originalName: string;
  storedName: string;
  storagePath: string;
  sizeBytes: number;
  uploader?: string;
  createdAt?: string;
}

const basePath = '/realtime/jars';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageFlinkJars(params: { current: number; pageSize: number }) {
  return http.get<ApiResponse<PageResult<FlinkJarRecord>>>(basePath, { params }).then(unwrap);
}

export function uploadFlinkJar(file: File, name: string, description?: string) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('name', name);
  if (description) {
    formData.append('description', description);
  }
  return http.post<ApiResponse<FlinkJarRecord>>(basePath, formData, { headers: { 'Content-Type': 'multipart/form-data' } }).then(unwrap);
}

/** Same-origin browser navigation (not axios) so the download's Content-Disposition and auth cookie both just work. */
export function flinkJarDownloadUrl(id: number) {
  return `/api${basePath}/${id}/download`;
}

export function updateFlinkJar(id: number, name: string, description?: string) {
  return http.put<ApiResponse<FlinkJarRecord>>(`${basePath}/${id}`, { name, description }).then(unwrap);
}

export function deleteFlinkJar(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function reuploadFlinkJar(id: number, file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return http.post<ApiResponse<FlinkJarRecord>>(`${basePath}/${id}/reupload`, formData, { headers: { 'Content-Type': 'multipart/form-data' } }).then(unwrap);
}

export function listFlinkJarVersions(id: number) {
  return http.get<ApiResponse<FlinkJarVersionRecord[]>>(`${basePath}/${id}/versions`).then(unwrap);
}

/** Classes inside the JAR with a runnable `public static void main(String[])`, scanned server-side without executing the JAR. */
export function listFlinkJarEntryClasses(id: number) {
  return http.get<ApiResponse<string[]>>(`${basePath}/${id}/entry-classes`).then(unwrap);
}

export function restoreFlinkJarVersion(id: number, versionId: number) {
  return http.post<ApiResponse<FlinkJarRecord>>(`${basePath}/${id}/versions/${versionId}/restore`).then(unwrap);
}

export function deleteFlinkJarVersion(id: number, versionId: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}/versions/${versionId}`).then(unwrap);
}

/**
 * "目标数据源类型" - selects which pre-built module jar the compiled class(es)
 * get merged into: one of the 5 single-driver cdc-mirror-* jars (smaller,
 * ~20-26MB) when the job only needs one driver, or 'ALL' for the full
 * multi-driver buildkit jar (~38MB) when it genuinely needs more than one.
 */
export type JavaBuildTargetType = 'CLICKHOUSE' | 'ORACLE' | 'MYSQL' | 'REDIS' | 'DORIS' | 'ALL';

/**
 * "在线编写" - compiles a single Java source file server-side against a
 * fixed classpath (Flink APIs + Kafka connector + jackson + the JDBC
 * driver(s) the chosen targetType's base jar bundles + CdcMirrorSupport)
 * and stores the result exactly like a file upload would. skipErrorMessage
 * since a compile failure is expected, common, and shown inline in the
 * editor modal, not as a generic toast - see JarPackagesPage.tsx's error
 * panel.
 */
export function compileFlinkJar(name: string, description: string | undefined, className: string, sourceCode: string, targetType: JavaBuildTargetType) {
  return http.post<ApiResponse<FlinkJarRecord>>(`${basePath}/compile`, { name, description, className, sourceCode, targetType }, { skipErrorMessage: true }).then(unwrap);
}

/**
 * "调试运行" - compiles then actually runs the code for a bounded trial
 * (~15s, forcibly killed after) as a separate process on the app server's
 * own host, connecting to whatever real Kafka topic/sink the programArgs
 * point at. Nothing gets saved here. 30s client timeout since the backend
 * itself can legitimately take up to its own ~15s run window plus compile
 * time before responding.
 */
export function debugRunFlinkJar(className: string, sourceCode: string, programArgs: string, targetType: JavaBuildTargetType) {
  return http.post<ApiResponse<{ output: string }>>(`${basePath}/debug-run`, { className, sourceCode, programArgs, targetType }, { skipErrorMessage: true, timeout: 30000 }).then(unwrap);
}
