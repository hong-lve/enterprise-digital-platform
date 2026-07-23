import { http } from './http';
import type { ApiResponse } from './auth';

export type SystemMenuType = 'MENU' | 'BUTTON';

export interface SystemMenuRecord {
  id: number;
  parentId: number;
  title: string;
  path?: string;
  component?: string;
  icon?: string;
  permission?: string;
  type: SystemMenuType;
  sortOrder: number;
  visible: string;
}

export interface SystemMenuTreeNode extends SystemMenuRecord {
  children: SystemMenuTreeNode[];
}

const basePath = '/system/menus';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function listSystemMenus() {
  return http.get<ApiResponse<SystemMenuRecord[]>>(basePath).then(unwrap);
}

export function createSystemMenu(data: Partial<SystemMenuRecord>) {
  return http.post<ApiResponse<void>>(basePath, data).then(unwrap);
}

export function updateSystemMenu(id: number, data: Partial<SystemMenuRecord>) {
  return http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
}

export function deleteSystemMenu(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
}

export function buildMenuTree(menus: SystemMenuRecord[]): SystemMenuTreeNode[] {
  const index = new Map<number, SystemMenuTreeNode>();
  const roots: SystemMenuTreeNode[] = [];
  menus.forEach((menu) => index.set(menu.id, { ...menu, children: [] }));
  index.forEach((node) => {
    if (node.parentId && index.has(node.parentId)) {
      index.get(node.parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}
