import { create } from 'zustand';
import type { CurrentUser, MenuNode } from '../api/auth';

interface AuthState {
  token: string;
  displayName: string;
  roles: string[];
  permissions: string[];
  menus: MenuNode[];
  ready: boolean;
  setCurrentUser: (user: CurrentUser) => void;
  clearSession: () => void;
  hasPermission: (permission: string) => boolean;
}

export const useAuthStore = create<AuthState>()((set, get) => ({
  token: '',
  displayName: '',
  roles: [],
  permissions: [],
  menus: [],
  ready: false,
  setCurrentUser: (user) => {
    set({
      token: 'session',
      displayName: user.displayName,
      roles: user.roles,
      permissions: user.permissions,
      menus: user.menus,
      ready: true
    });
  },
  clearSession: () => {
    set({ token: '', displayName: '', roles: [], permissions: [], menus: [], ready: false });
  },
  hasPermission: (permission) => get().permissions.includes(permission)
}));
