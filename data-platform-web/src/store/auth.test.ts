import { beforeEach, describe, expect, it } from 'vitest';
import type { CurrentUser } from '../api/auth';
import { useAuthStore } from './auth';

const user: CurrentUser = {
  id: 1,
  username: 'admin',
  displayName: '平台管理员',
  roles: ['ADMIN'],
  permissions: ['system:approval:view', 'system:approval:handle'],
  menus: []
};

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clearSession();
  });

  it('starts with no session', () => {
    const state = useAuthStore.getState();
    expect(state.ready).toBe(false);
    expect(state.token).toBe('');
    expect(state.hasPermission('system:approval:view')).toBe(false);
  });

  it('setCurrentUser populates session state from /auth/me', () => {
    useAuthStore.getState().setCurrentUser(user);
    const state = useAuthStore.getState();
    expect(state.ready).toBe(true);
    expect(state.token).not.toBe('');
    expect(state.displayName).toBe('平台管理员');
    expect(state.roles).toEqual(['ADMIN']);
  });

  it('hasPermission reflects exactly the permission list from the last login', () => {
    useAuthStore.getState().setCurrentUser(user);
    expect(useAuthStore.getState().hasPermission('system:approval:handle')).toBe(true);
    expect(useAuthStore.getState().hasPermission('system:audit:view')).toBe(false);
  });

  it('clearSession wipes every field back to its logged-out default, not just the token', () => {
    useAuthStore.getState().setCurrentUser(user);
    useAuthStore.getState().clearSession();
    const state = useAuthStore.getState();
    expect(state.token).toBe('');
    expect(state.displayName).toBe('');
    expect(state.roles).toEqual([]);
    expect(state.permissions).toEqual([]);
    expect(state.menus).toEqual([]);
    expect(state.ready).toBe(false);
  });
});
