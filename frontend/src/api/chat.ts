import { request } from './request';
import { streamSse } from './stream';

export interface ChatSession { id: number; title: string; pinned: boolean; providerId: string; status: string; messageCount: number; updatedAt: string; }
export interface ChatMessage { id: number; role: 'user' | 'assistant'; content: string; completed: boolean; failureCode?: string; messageOrder: number; createdAt: string; }
export interface ChatSessionDetail { session: ChatSession; messages: ChatMessage[]; page: number; size: number; totalElements: number; totalPages: number; }

export const chatApi = {
  createSession: (title?: string) => request.post<ChatSession>('/api/chat/sessions', { title }),
  listSessions: () => request.get<ChatSession[]>('/api/chat/sessions'),
  getSession: (sessionId: number, page = 0, size = 50) => request.get<ChatSessionDetail>(`/api/chat/sessions/${sessionId}?page=${page}&size=${size}`),
  listMessages: (sessionId: number) => request.get<ChatMessage[]>(`/api/chat/sessions/${sessionId}/messages`),
  setPinned: (sessionId: number, pinned: boolean) => request.patch<void>(`/api/chat/sessions/${sessionId}/pinned`, { pinned }),
  rename: (sessionId: number, title: string) => request.patch<void>(`/api/chat/sessions/${sessionId}/title`, { title }),
  archive: (sessionId: number) => request.post<void>(`/api/chat/sessions/${sessionId}/archive`),
  deleteSession: (sessionId: number) => request.delete<void>(`/api/chat/sessions/${sessionId}`),
  sendMessageStream: (sessionId: number, content: string, onMessage: (chunk: string) => void,
    onComplete: () => void, onError: (error: Error) => void) => streamSse({
      url: `/api/chat/sessions/${sessionId}/messages/stream`,
      init: { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content }) },
      onMessage, onComplete, onError, parseMode: 'event', trimDataPrefixSpace: false, dataJoiner: '',
    }),
  retryMessageStream: (sessionId: number, messageId: number, onMessage: (chunk: string) => void,
    onComplete: () => void, onError: (error: Error) => void) => streamSse({
      url: `/api/chat/sessions/${sessionId}/messages/${messageId}/retry`, init: { method: 'POST' },
      onMessage, onComplete, onError, parseMode: 'event', trimDataPrefixSpace: false, dataJoiner: '',
    }),
};
