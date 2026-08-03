import { http } from './http';
import type { ApiResponse } from './auth';

export interface OnCallShiftRecord {
  id: number;
  username: string;
  startsAt: string;
  endsAt: string;
  note?: string;
  createdBy?: string;
  createdAt?: string;
}

export interface OnCallResponse {
  currentOnCall?: string;
  upcoming: OnCallShiftRecord[];
}

export interface AlertSilenceWindowRecord {
  id: number;
  entityType?: string;
  entityId?: number;
  startsAt: string;
  endsAt: string;
  reason?: string;
  createdBy?: string;
  createdAt?: string;
}

export interface AlertRetryQueueRecord {
  id: number;
  title: string;
  content?: string;
  type: string;
  linkUrl?: string;
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string;
  status: string;
  lastError?: string;
  createdAt?: string;
  updatedAt?: string;
}

const basePath = '/realtime/alert-ops';

function unwrap<T>(response: { data: ApiResponse<T> }) {
  return response.data.data;
}

export function fetchOnCall() {
  return http.get<ApiResponse<OnCallResponse>>(`${basePath}/on-call`).then(unwrap);
}

export function createOnCallShift(data: Partial<OnCallShiftRecord>) {
  return http.post<ApiResponse<OnCallShiftRecord>>(`${basePath}/on-call`, data).then(unwrap);
}

export function deleteOnCallShift(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/on-call/${id}`).then(unwrap);
}

export function fetchSilenceWindows() {
  return http.get<ApiResponse<AlertSilenceWindowRecord[]>>(`${basePath}/silence-windows`).then(unwrap);
}

export function createSilenceWindow(data: Partial<AlertSilenceWindowRecord>) {
  return http.post<ApiResponse<AlertSilenceWindowRecord>>(`${basePath}/silence-windows`, data).then(unwrap);
}

export function deleteSilenceWindow(id: number) {
  return http.delete<ApiResponse<void>>(`${basePath}/silence-windows/${id}`).then(unwrap);
}

export function fetchRetryQueue() {
  return http.get<ApiResponse<AlertRetryQueueRecord[]>>(`${basePath}/retry-queue`).then(unwrap);
}
