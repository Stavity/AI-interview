import { request } from './request';

export type AlgorithmLanguage = 'JAVA' | 'PYTHON' | 'CPP';
export interface AlgorithmTemplate { language: AlgorithmLanguage; templateVersion: string; sourceCode: string; notice: string; }
export interface AlgorithmSubmission { id: number; questionId: number; language: AlgorithmLanguage; sourceCode: string; templateVersion: string; evaluationStatus: 'PENDING' | 'COMPLETED' | 'FAILED'; evaluationErrorCode?: string; evaluation: string; notice: string; }
export interface AlgorithmStaticReview { score: number; correctnessPassed: boolean; complexityPassed: boolean; requiredTimeComplexity: string; requiredSpaceComplexity: string; approach: string; timeComplexity: string; spaceComplexity: string; boundaryCases: string[]; blockingIssues: string[]; qualityIssues: string[]; improvements: string[]; notice: string; criteriaAvailable?: boolean; }
export interface MultiRoundInterview { id: number; status: string; currentRoundNo: number; providerId: string; }
export interface MultiRoundDetail { interview: MultiRoundInterview; rounds: Array<{ id: number; roundNo: number; roundType: string; status: string; questionLimit: number }>; }
export interface MultiRoundReport { status: string; completedRounds: number; totalRounds: number; summary: string; source: string; rounds: Array<{ roundNo: number; roundType: string; questionCount: number; answeredQuestionCount: number; questionTypes: Record<string, number> }>; }
export interface MultiRoundQuestion { id: number; sequenceNo: number; questionType: string; categoryKey?: string; question: string; status: string; answer?: string; problemJson?: string; }
export interface MultiRoundRound { id: number; roundNo: number; roundType: string; status: string; questionLimit: number; }
export interface MultiRoundWorkflow { executionMode: 'STATIC' | 'GOOGLE_ADK' | 'STATIC_FALLBACK'; status: 'READY' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED'; checkpointKey: string; adkSessionId?: string; }
export interface AgentRunTrace { id: number; triggerType: string; status: string; errorCode?: string; runtimeType?: 'LEGACY' | 'GOOGLE_ADK'; runtimeVersion?: string; policyVersion?: string; startedAt: string; finishedAt?: string; steps: Array<{ stepNo: number; eventType?: string; toolName: string; status: string; outputSummary?: string; durationMs?: number; createdAt: string }>; }
export interface AgentRuntime { runtime: 'LEGACY' | 'SHADOW' | 'GOOGLE_ADK'; policyVersion: string; }
export interface StartMultiRoundInterviewRequest { skillId: string; difficulty: 'junior' | 'mid' | 'senior'; jdText?: string; restart?: boolean; }
export interface MultiRoundBootstrapResponse { sessionId: string; interviewId: number; status: string; currentRoundNo: number; resumed: boolean; }
const withIdempotencyKey = (idempotencyKey: string) => ({ headers: { 'Idempotency-Key': idempotencyKey } });

export const multiRoundApi = {
  templates: () => request.get<AlgorithmTemplate[]>('/api/multi-round/algorithm/templates'),
  submitAlgorithm: (questionId: number, idempotencyKey: string, language: AlgorithmLanguage, sourceCode: string) => request.post<AlgorithmSubmission>(`/api/multi-round/questions/${questionId}/algorithm-submissions`, { language, sourceCode }, withIdempotencyKey(idempotencyKey)),
  getAlgorithmSubmission: (submissionId: number) => request.get<AlgorithmSubmission>(`/api/multi-round/algorithm-submissions/${submissionId}`),
  getAlgorithmSubmissionForQuestion: (questionId: number) => request.get<AlgorithmSubmission | null>(`/api/multi-round/questions/${questionId}/algorithm-submissions`),
  reviewAlgorithm: (submissionId: number) => request.post<AlgorithmStaticReview>(`/api/multi-round/algorithm-submissions/${submissionId}/review`),
  startForResume: (resumeId: number, payload: StartMultiRoundInterviewRequest, idempotencyKey: string) => request.post<MultiRoundBootstrapResponse>(`/api/resumes/${resumeId}/multi-round-interviews`, payload, withIdempotencyKey(idempotencyKey)),
  getActiveForResume: (resumeId: number, payload: Pick<StartMultiRoundInterviewRequest, 'skillId' | 'difficulty' | 'jdText'>) => {
    const query = new URLSearchParams({ skillId: payload.skillId, difficulty: payload.difficulty });
    if (payload.jdText?.trim()) query.set('jdText', payload.jdText.trim());
    return request.get<MultiRoundBootstrapResponse | null>(`/api/resumes/${resumeId}/multi-round-interviews/active?${query.toString()}`);
  },
  getInterview: (sessionId: string) => request.get<MultiRoundDetail>(`/api/multi-round/interviews/${sessionId}`),
  getWorkflow: (sessionId: string) => request.get<MultiRoundWorkflow>(`/api/multi-round/interviews/${sessionId}/workflow`),
  heartbeat: (sessionId: string) => request.post<MultiRoundWorkflow>(`/api/multi-round/interviews/${sessionId}/presence`),
  listQuestions: (roundId: number) => request.get<MultiRoundQuestion[]>(`/api/multi-round/rounds/${roundId}/questions`),
  answerQuestion: (questionId: number, idempotencyKey: string, answer: string) => request.post<void>(`/api/multi-round/questions/${questionId}/answer`, { answer }, withIdempotencyKey(idempotencyKey)),
  getReport: (sessionId: string) => request.get<MultiRoundReport>(`/api/multi-round/interviews/${sessionId}/report`),
  getAgentTrace: (sessionId: string) => request.get<AgentRunTrace[]>(`/api/multi-round/interviews/${sessionId}/agent-trace`),
  getAgentRuntime: () => request.get<AgentRuntime>('/api/multi-round/agent-runtime'),
  getRoundReport: (roundId: number) => request.get<{ roundNo: number; roundType: string; questionCount: number; answeredQuestionCount: number; questionTypes: Record<string, number> }>(`/api/multi-round/rounds/${roundId}/report`),
  agentPlan: (sessionId: string, trigger: string, context: string) => request.post<{ roundNo: number; roundType: string; followUpCount: number; categoryKeys: string[]; stepCount: number }>(`/api/multi-round/interviews/${sessionId}/agent-plan`, { trigger, context }),
};
