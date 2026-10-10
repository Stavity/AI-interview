import { request } from './request';

export interface LearningSource { id: number; skillId: string; categoryKey: string; displayName: string; description: string; status: string; }
export interface LearningPlan { id: number; title: string; goal?: string; startDate: string; endDate: string; status: string; }
export interface LearningTask { id: number; planId: number; skillSourceId?: number; title: string; dueDate: string; estimatedMinutes: number; status: string; }
export interface LearningReminder { id: number; taskId: number; remindAt: string; channel: string; status: string; }
export interface LearningProgress { id: number; sourceId: number; masteryScore: number; status: string; lastPracticedAt?: string; }
export interface LearningRecommendation { sourceId: number; skillId: string; categoryKey: string; displayName: string; masteryScore: number; reason: string; }
export interface KnowledgeProgressNode { code: string; name: string; parentCode?: string; assignable: boolean; sortOrder: number; masteryScore: number; evidenceCount: number; distinctSessionCount: number; consecutiveCorrectCount: number; consecutiveIncorrectCount: number; confidenceScore: number; status: 'NOT_STARTED' | 'LEARNING' | 'MASTERED'; lastPracticedAt?: string; }
export interface KnowledgeProgressSnapshot { taxonomyVersion: number; aggregationRuleVersion: number; aggregatedAt?: string; nodes: KnowledgeProgressNode[]; }
export interface LearningEvidence { id: number; sourceType: 'TEXT_INTERVIEW' | 'VOICE_INTERVIEW'; sourceSessionId: string; sourceAnswerId?: number; sourceVoiceMessageId?: number; evaluationVersion: number; questionIndex: number; taxonomyVersion: number; nodeCode: string; tagRole: 'PRIMARY' | 'SECONDARY'; score: number; assessedAt: string; }
export interface PersonalizedLearningPathItem { id: number; sequenceNo: number; nodeCode: string; nodeName: string; masteryScore: number; evidenceCount: number; difficulty: 'FOUNDATION' | 'REINFORCEMENT' | 'PRACTICE'; estimatedMinutes: number; prerequisite: boolean; recommendationReason: string; status: 'PENDING' | 'COMPLETED' | 'SKIPPED'; completedAt?: string; }
export interface PersonalizedLearningPath { id?: number; taxonomyVersion: number; routeRuleVersion: number; goal?: string; triggerReason?: string; summary: string; status: 'EMPTY' | 'ACTIVE'; createdAt?: string; items: PersonalizedLearningPathItem[]; }

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
  knowledgeProgress: (taxonomyVersion?: number) => request.get<KnowledgeProgressSnapshot>('/api/learning/knowledge-progress', { params: { taxonomyVersion } }),
  rebuildKnowledgeProgress: (taxonomyVersion?: number) => request.post<KnowledgeProgressSnapshot>('/api/learning/knowledge-progress/rebuild', undefined, { params: { taxonomyVersion } }),
  listEvidence: (params?: { taxonomyVersion?: number; nodeCode?: string; limit?: number }) => request.get<LearningEvidence[]>('/api/learning/evidence', { params }),
  personalizedPath: () => request.get<PersonalizedLearningPath>('/api/learning/personalized-path'),
  regeneratePersonalizedPath: (goal?: string) => request.post<PersonalizedLearningPath>('/api/learning/personalized-path/regenerate', { goal }),
  completePersonalizedPathItem: (itemId: number) => request.post<PersonalizedLearningPath>(`/api/learning/personalized-path/items/${itemId}/complete`),
};
