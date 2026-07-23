import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export interface SystemUserRecord {
  id: number;
  username: string;
  displayName: string;
  email?: string;
  status: string;
  roleNames: string[];
  createdAt?: string;
}

const basePath = '/system/users';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function pageSystemUsers(params: { current: number; pageSize: number; username?: string }) {
  return http.get<ApiResponse<PageResult<SystemUserRecord>>>(basePath, { params }).then(unwrap);
}

export function createSystemUser(data: { username: string; displayName: string; email?: string; password: string; roleIds?: number[] }) {
  return http.post<ApiResponse<void>>(basePath, data).then(unwrap);
}

export function updateSystemUser(id: number, data: { displayName: string; email?: string; status?: string }) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteSystemUser(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function resetSystemUserPassword(id: number, newPassword: string) {
  return http.post<ApiResponse<void>>(`${basePath}/${id}/reset-password`, { newPassword }).then(unwrap);
}

export function getSystemUserRoles(id: number) {
  return http.get<ApiResponse<number[]>>(`${basePath}/${id}/roles`).then(unwrap);
}

export function assignSystemUserRoles(id: number, roleIds: number[]) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}/roles`, { roleIds }).then(unwrap);
}
