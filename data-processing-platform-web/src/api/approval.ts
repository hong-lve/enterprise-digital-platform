import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export interface ChangeRequestRecord {
  id: number;
  actionType: string;
  targetId: number;
  targetSummary?: string;
  requester: string;
  status: string;
  approver?: string;
  rejectReason?: string;
  createdAt: string;
  decidedAt?: string;
}

export interface ActionResult {
  status: 'APPLIED' | 'PENDING_APPROVAL';
  approvalRequestId?: number;
}

// Every delete/stop endpoint gated by ChangeApprovalService returns this
// shape - most of the time (a DEV resource) it's APPLIED and the caller
// proceeds exactly as it always has; a PROD resource comes back
// PENDING_APPROVAL instead of actually taking effect.
export function isPendingApproval(value: unknown): value is ActionResult {
  return !!value && typeof value === 'object' && (value as ActionResult).status === 'PENDING_APPROVAL';
}

export function pageApprovalRequests(params: { current: number; pageSize: number; status?: string }) {
  return http.get<ApiResponse<PageResult<ChangeRequestRecord>>>('/system/approval-requests', { params }).then((response) => response.data.data);
}

export function approveChangeRequest(id: number) {
  return http.post<ApiResponse<ChangeRequestRecord>>(`/system/approval-requests/${id}/approve`).then((response) => response.data.data);
}

export function rejectChangeRequest(id: number, reason: string) {
  return http.post<ApiResponse<ChangeRequestRecord>>(`/system/approval-requests/${id}/reject`, { reason }).then((response) => response.data.data);
}
