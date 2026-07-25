import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';
import type { ActionResult } from './approval';

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

// Returns PENDING_APPROVAL only when the request disables the account -
// editing a display name/email applies immediately, same as before.
export function updateSystemUser(id: number, data: { displayName: string; email?: string; status?: string }) {
  return http.put<ApiResponse<ActionResult>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteSystemUser(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

// Always returns PENDING_APPROVAL - resetting someone else's password is
// effectively an account-takeover primitive, so unlike updateSystemUser
// there's no immediate-apply case here at all.
export function resetSystemUserPassword(id: number, newPassword: string) {
  return http.post<ApiResponse<ActionResult>>(`${basePath}/${id}/reset-password`, { newPassword }).then(unwrap);
}

export function getSystemUserRoles(id: number) {
  return http.get<ApiResponse<number[]>>(`${basePath}/${id}/roles`).then(unwrap);
}

export function assignSystemUserRoles(id: number, roleIds: number[]) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}/roles`, { roleIds }).then(unwrap);
}
