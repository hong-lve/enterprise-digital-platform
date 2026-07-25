import { http } from './http';
import type { ApiResponse } from './auth';
import type { ActionResult } from './approval';

export interface SystemRoleRecord {
  id: number;
  roleKey: string;
  name: string;
  description?: string;
  status: string;
  dataScope: string;
  createdAt?: string;
  updatedAt?: string;
}

const basePath = '/system/roles';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function listSystemRoles() {
  return http.get<ApiResponse<SystemRoleRecord[]>>(basePath).then(unwrap);
}

export function createSystemRole(data: { roleKey: string; name: string; description?: string }) {
  return http.post<ApiResponse<void>>(basePath, data).then(unwrap);
}

export function updateSystemRole(id: number, data: { name: string; description?: string; status?: string }) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteSystemRole(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function getSystemRoleMenus(id: number) {
  return http.get<ApiResponse<number[]>>(`${basePath}/${id}/menus`).then(unwrap);
}

// Always returns PENDING_APPROVAL now - a role's permission set applies
// platform-wide the instant it's saved, so this always needs a second
// approver, unlike the environment-conditional gates elsewhere.
export function assignSystemRoleMenus(id: number, menuIds: number[]) {
  return http.put<ApiResponse<ActionResult>>(`${basePath}/${id}/menus`, { menuIds }).then(unwrap);
}
