export const ROUTES = {
  chat: '/chat',
  learning: '/learning',
  multiRound: '/multi-round',
  multiRoundReport: (sessionId: string) => `/multi-round/${sessionId}/report`,
  interview: '/interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  knowledgebaseUpload: '/knowledgebase/upload',
} as const;

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
  multiRoundReport: 'multi-round/:sessionId/report',
} as const;
