import { request } from './request';

export interface LearningSource { id: number; skillId: string; categoryKey: string; displayName: string; description: string; status: string; }
export interface LearningPlan { id: number; title: string; goal?: string; startDate: string; endDate: string; status: string; }
export interface LearningTask { id: number; planId: number; skillSourceId?: number; title: string; dueDate: string; estimatedMinutes: number; status: string; }
export interface LearningReminder { id: number; taskId: number; remindAt: string; channel: string; status: string; }
export interface LearningProgress { id: number; sourceId: number; masteryScore: number; status: string; lastPracticedAt?: string; }
export interface LearningRecommendation { sourceId: number; skillId: string; categoryKey: string; displayName: string; masteryScore: number; reason: string; }
export interface InterviewRecommendationPreview { reportSummary: string; completedRounds: number; totalRounds: number; recommendations: LearningRecommendation[]; }

export const learningApi = {
  listSources: () => request.get<LearningSource[]>('/api/learning/sources'),
  listPlans: () => request.get<LearningPlan[]>('/api/learning/plans'),
  createPlan: (payload: Pick<LearningPlan, 'title' | 'goal' | 'startDate' | 'endDate'>) => request.post<LearningPlan>('/api/learning/plans', payload),
  activatePlan: (planId: number) => request.post<LearningPlan>(`/api/learning/plans/${planId}/activate`),
  listTasks: (planId: number) => request.get<LearningTask[]>(`/api/learning/plans/${planId}/tasks`),
  createTask: (planId: number, payload: { title: string; dueDate: string; estimatedMinutes: number; skillSourceId?: number; remindAt?: string }) => request.post<LearningTask>(`/api/learning/plans/${planId}/tasks`, payload),
  completeTask: (taskId: number) => request.post<void>(`/api/learning/tasks/${taskId}/complete`),
  readyReminders: () => request.get<LearningReminder[]>('/api/learning/reminders/ready'),
  acknowledgeReminder: (reminderId: number, status: 'DELIVERED' | 'FAILED', failureReason?: string) => request.post<void>(`/api/learning/reminders/${reminderId}/delivery`, { status, failureReason }),
  listProgress: () => request.get<LearningProgress[]>('/api/learning/progress'),
  adjustProgress: (sourceId: number, delta: number) => request.post<LearningProgress>(`/api/learning/sources/${sourceId}/progress`, { delta }),
  pathRecommendation: () => request.get<LearningRecommendation[]>('/api/learning/path-recommendation'),
  recommendationsFromInterview: (sessionId: string) => request.post<InterviewRecommendationPreview>(`/api/learning/recommendations/from-interview/${sessionId}`),
};
