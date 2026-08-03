import { http } from './http';
import type { ApiResponse } from './auth';
import type { ActionResult } from './approval';

export interface TwoFactorSetting {
  enabled: boolean;
}

export interface EncryptionKeyRotationEventRecord {
  id: number;
  toVersion: string;
  dataSourceRowsReencrypted: number;
  totpRowsReencrypted: number;
  status: string;
  errorMessage?: string;
  triggeredBy?: string;
  startedAt: string;
  finishedAt?: string;
}

export interface EncryptionKeyStatus {
  currentVersion: string;
  configuredVersions: string[];
  dataSourceRowsByVersion: Record<string, number>;
  totpRowsByVersion: Record<string, number>;
  recentEvents: EncryptionKeyRotationEventRecord[];
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

export function getEncryptionKeyStatus() {
  return http.get<ApiResponse<EncryptionKeyStatus>>('/system/security/encryption-keys').then(unwrap);
}

// Re-encrypts every data_source/sys_user_totp row under whichever key
// version platform.datasource-encryption-current-version currently names -
// see EncryptionKeyRotationService. Always defers to a second approver
// (ChangeApprovalService.gateAlwaysWithPayload), so this may come back
// PENDING_APPROVAL rather than APPLIED - check with isPendingApproval().
export function rotateEncryptionKeys() {
  return http.post<ApiResponse<ActionResult>>('/system/security/encryption-keys/rotate').then(unwrap);
}
