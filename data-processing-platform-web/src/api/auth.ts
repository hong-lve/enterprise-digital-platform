import { http } from './http';

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface MenuNode {
  id: number;
  parentId: number;
  title: string;
  path: string;
  icon?: string;
  children: MenuNode[];
}

export interface CurrentUser {
  id: number;
  username: string;
  displayName: string;
  roles: string[];
  permissions: string[];
  menus: MenuNode[];
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function getMe() {
  return http.get<ApiResponse<CurrentUser>>('/auth/me').then(unwrap);
}

export function getMeSilently() {
  return http.get<ApiResponse<CurrentUser>>('/auth/me', { skipErrorMessage: true }).then(unwrap);
}

export interface LoginResult {
  requires2fa: boolean;
  requiresSetup: boolean;
  pendingToken?: string;
  secret?: string;
  otpAuthUri?: string;
}

export function login(username: string, password: string) {
  // LoginPage always shows its own, more specific message for a failed
  // login (bad credentials vs. network error) - skip the global interceptor
  // toast so a failure doesn't surface twice. When the system-wide 2FA
  // toggle is on, this only confirms the password - requires2fa in the
  // response means the session isn't established yet, see verifyTwoFactor.
  return http.post<ApiResponse<LoginResult>>('/auth/login', { username, password }, { skipErrorMessage: true }).then(unwrap);
}

export function verifyTwoFactor(pendingToken: string, code: string) {
  return http.post<ApiResponse<LoginResult>>('/auth/2fa/verify', { pendingToken, code }, { skipErrorMessage: true }).then(unwrap);
}

export function logout() {
  return http.post<ApiResponse<void>>('/auth/logout').then(unwrap);
}
