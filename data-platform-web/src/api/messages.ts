import { http } from './http';
import type { ApiResponse } from './auth';
import type { PageResult } from './cdcSources';

export interface MessageRecord {
  id: number;
  title: string;
  content?: string;
  type?: string;
  linkUrl?: string;
  sender?: string;
  readStatus: string;
  createdAt: string;
}

export function pageMessages(params: { current: number; pageSize: number; readStatus?: string }) {
  return http.get<ApiResponse<PageResult<MessageRecord>>>('/system/messages', { params }).then((response) => response.data.data);
}

export function unreadMessageCount() {
  return http.get<ApiResponse<number>>('/system/messages/unread-count').then((response) => response.data.data);
}

export function markMessageRead(id: number) {
  return http.post<ApiResponse<void>>(`/system/messages/${id}/read`).then((response) => response.data.data);
}

export function markAllMessagesRead() {
  return http.post<ApiResponse<void>>('/system/messages/read-all').then((response) => response.data.data);
}
