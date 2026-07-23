import { http } from './http';
import type { ApiResponse } from './auth';

export interface TwoFactorSetting {
  enabled: boolean;
}

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function getTwoFactorSetting() {
  return http.get<ApiResponse<TwoFactorSetting>>('/system/security/two-factor').then(unwrap);
}

export function updateTwoFactorSetting(enabled: boolean) {
  return http.put<ApiResponse<TwoFactorSetting>>('/system/security/two-factor', { enabled }).then(unwrap);
}
