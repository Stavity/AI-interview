import { FormEvent, lazy, Suspense, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AlertCircle, ChevronRight, Clock, Loader2 } from 'lucide-react';
import { multiRoundApi, type AgentRunTrace, type MultiRoundDetail, type MultiRoundQuestion, type MultiRoundReport } from '../api/multiRound';
import { learningApi, type InterviewRecommendationPreview } from '../api/learning';

const AlgorithmEditor = lazy(() => import('../components/multiround/AlgorithmEditor'));

const ROUND_LABELS: Record<string, string> = {
  ALGORITHM: '算法笔试',
  TECHNICAL_1: '技术一面',
  TECHNICAL_2: '技术二面',
  HR: 'HR 面',
};

const ROUND_COLORS: Record<string, string> = {
  ALGORITHM: 'border-blue-300 bg-blue-50 dark:border-blue-700 dark:bg-blue-900/20',
  TECHNICAL_1: 'border-violet-300 bg-violet-50 dark:border-violet-700 dark:bg-violet-900/20',
  TECHNICAL_2: 'border-amber-300 bg-amber-50 dark:border-amber-700 dark:bg-amber-900/20',
  HR: 'border-emerald-300 bg-emerald-50 dark:border-emerald-700 dark:bg-emerald-900/20',
};

type AlgorithmProblem = {
  title: string;
  description: string;
  inputFormat: string;
  outputFormat: string;
  constraints: string[];
  examples: Array<{ input: string; output: string; explanation?: string }>;
};

function readAlgorithmProblem(problemJson?: string): AlgorithmProblem | null {
  if (!problemJson) return null;
  try {
    const value = JSON.parse(problemJson) as Partial<AlgorithmProblem>;
    if (!value.title || !value.description || !value.inputFormat || !value.outputFormat) return null;
    return { title: value.title, description: value.description, inputFormat: value.inputFormat,
      outputFormat: value.outputFormat, constraints: value.constraints ?? [], examples: value.examples ?? [] };
  } catch { return null; }
}

function AlgorithmProblemStatement({ question }: { question: MultiRoundQuestion }) {
  const problem = readAlgorithmProblem(question.problemJson);
  if (!problem) return <p className="mt-3 whitespace-pre-wrap text-slate-900 dark:text-white">{question.question}</p>;
  return <div className="mt-3 space-y-4 text-sm text-slate-800 dark:text-slate-100">
    <h3 className="text-base font-semibold text-slate-950 dark:text-white">{problem.title}</h3>
    <p className="whitespace-pre-wrap leading-6">{problem.description}</p>
    <div className="grid gap-3 sm:grid-cols-2">
      <div><p className="font-medium">输入格式</p><p className="mt-1 whitespace-pre-wrap text-slate-600 dark:text-slate-300">{problem.inputFormat}</p></div>
      <div><p className="font-medium">输出格式</p><p className="mt-1 whitespace-pre-wrap text-slate-600 dark:text-slate-300">{problem.outputFormat}</p></div>
    </div>
    {problem.constraints.length > 0 && <div><p className="font-medium">约束范围</p><ul className="mt-1 list-disc space-y-1 pl-5 text-slate-600 dark:text-slate-300">{problem.constraints.map((constraint, index) => <li key={index}>{constraint}</li>)}</ul></div>}
    {problem.examples.map((example, index) => <div key={index} className="rounded-lg bg-slate-50 p-3 font-mono text-xs dark:bg-slate-800"><p>输入：{example.input}</p><p className="mt-1">输出：{example.output}</p>{example.explanation && <p className="mt-2 whitespace-pre-wrap font-sans text-slate-600 dark:text-slate-300">说明：{example.explanation}</p>}</div>)}
  </div>;
}

export default function MultiRoundInterviewPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const initialSessionId = params.get('sessionId') ?? '';
  const [activeSessionId] = useState(initialSessionId);
  const [questions, setQuestions] = useState<MultiRoundQuestion[]>([]);
  const [answer, setAnswer] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [interview, setInterview] = useState<MultiRoundDetail | null>(null);
  const [report, setReport] = useState<MultiRoundReport | null>(null);
  const [traces, setTraces] = useState<AgentRunTrace[]>([]);
  const [learningPreview, setLearningPreview] = useState<InterviewRecommendationPreview | null>(null);
  const [activeRoundId, setActiveRoundId] = useState<number | null>(null);
  const [selectedAlgorithmQuestionId, setSelectedAlgorithmQuestionId] = useState<number | null>(null);
  const answerKeys = useRef(new Map<number, string>());

  const reloadInterview = async (sid: string) => {
    const [detail, value, trace] = await Promise.all([
      multiRoundApi.getInterview(sid),
      multiRoundApi.getReport(sid),
      multiRoundApi.getAgentTrace(sid),
    ]);
    setInterview(detail);
    setReport(value);
    setTraces(trace);
    // Auto-select the current round's questions
    const currentRound = detail.rounds.find(r => r.status === 'IN_PROGRESS');
    if (currentRound) {
      setActiveRoundId(currentRound.id);
      const qs = await multiRoundApi.listQuestions(currentRound.id);
      setQuestions(qs);
    } else {
      const nextRound = detail.rounds.find(r => r.status === 'PENDING' && r.roundNo === detail.interview.currentRoundNo + 1);
      if (nextRound) {
        setActiveRoundId(nextRound.id);
        setQuestions([]);
      } else {
        setActiveRoundId(null);
        setQuestions([]);
      }
    }
  };

  useEffect(() => {
    if (!activeSessionId) {
      navigate('/history', { replace: true });
      return;
    }
    setLoading(true);
    reloadInterview(activeSessionId)
      .catch(() => setError('多轮会话创建或加载失败'))
      .finally(() => setLoading(false));
  }, [activeSessionId, navigate]);

  useEffect(() => {
    if (!activeSessionId) return;
    const heartbeat = () => { void multiRoundApi.heartbeat(activeSessionId).catch(() => undefined); };
    heartbeat();
    const timer = window.setInterval(heartbeat, 30_000);
    const onVisibilityChange = () => { if (document.visibilityState === 'visible') heartbeat(); };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [activeSessionId]);

  const submitAnswer = async (event: FormEvent) => {
    event.preventDefault();
    const item = questions.find(q => q.status === 'PENDING');
    if (!item || !answer.trim()) return;
    const key = answerKeys.current.get(item.id) ?? `answer:${item.id}:${crypto.randomUUID()}`;
    answerKeys.current.set(item.id, key);
    try {
      await multiRoundApi.answerQuestion(item.id, key, answer);
      setQuestions(prev => prev.map(q => q.id === item.id ? { ...q, answer, status: 'ANSWERED' } : q));
      setAnswer('');
      await reloadInterview(activeSessionId);
    } catch {
      setError('提交回答失败');
    }
  };

  const selectRound = async (roundId: number) => {
    setActiveRoundId(roundId);
    try {
      const qs = await multiRoundApi.listQuestions(roundId);
      setQuestions(qs);
    } catch {
      setError('题目加载失败');
    }
  };

  const loadLearningPreview = async () => {
    if (!activeSessionId) return;
    try {
      setLearningPreview(await learningApi.recommendationsFromInterview(activeSessionId));
    } catch {
      setError('学习建议加载失败');
    }
  };

  const currentRound = interview?.rounds.find(r => r.id === activeRoundId);
  const isAlgorithmRound = currentRound?.roundType === 'ALGORITHM';
  const algorithmQuestions = isAlgorithmRound ? questions : [];
  const selectedAlgorithmQuestion = algorithmQuestions.find(question => question.id === selectedAlgorithmQuestionId)
    ?? algorithmQuestions[0];

  useEffect(() => {
    if (!isAlgorithmRound || algorithmQuestions.length === 0) {
      setSelectedAlgorithmQuestionId(null);
      return;
    }
    if (!algorithmQuestions.some(question => question.id === selectedAlgorithmQuestionId)) {
      setSelectedAlgorithmQuestionId(algorithmQuestions[0].id);
    }
  }, [isAlgorithmRound, algorithmQuestions, selectedAlgorithmQuestionId]);

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <header>
        <p className="text-sm text-primary-600">MULTI-ROUND INTERVIEW</p>
        <h1 className="mt-1 text-2xl font-bold text-slate-900 dark:text-white">多轮面试</h1>
        <p className="mt-2 text-sm text-slate-500">轮次由服务端状态机控制，算法题只进行 AI 静态评审，不执行代码。</p>
      </header>

      {loading && (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
        </div>
      )}

      {error && (
        <div className="flex items-center gap-2 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-600 dark:bg-red-900/20">
          <AlertCircle className="w-4 h-4 shrink-0" />
          {error}
        </div>
      )}

      {interview && (
        <>
          {/* Round progress bar */}
          <section className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium text-slate-900 dark:text-white">面试进度</p>
              <span className="text-xs text-slate-500">当前第 {interview.interview.currentRoundNo} 轮 · {interview.interview.status}</span>
            </div>
            <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
              {interview.rounds.map(round => (
                <button
                  key={round.id}
                  onClick={() => selectRound(round.id)}
                  className={`rounded-xl border p-3 text-left transition-all ${ROUND_COLORS[round.roundType] ?? 'border-slate-200'} ${activeRoundId === round.id ? 'ring-2 ring-primary-500' : ''}`}
                >
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-medium text-slate-700 dark:text-slate-200">{round.roundNo}. {ROUND_LABELS[round.roundType] ?? round.roundType}</p>
                    {round.status === 'IN_PROGRESS' && <Clock className="w-3 h-3 text-primary-600" />}
                  </div>
                  <p className="mt-1 text-xs text-slate-500">{round.status} · 最多 {round.questionLimit} 题</p>
                </button>
              ))}
            </div>
          </section>

          {/* Questions for active round */}
          {currentRound && questions.length > 0 && (
            <section className="space-y-4">
              <div className="flex items-center gap-2">
                <ChevronRight className="w-4 h-4 text-primary-600" />
                <h2 className="text-sm font-semibold text-slate-900 dark:text-white">
                  {ROUND_LABELS[currentRound.roundType] ?? currentRound.roundType} · 题目
                </h2>
              </div>
              {isAlgorithmRound && (
                <div role="tablist" aria-label="算法题目" className="grid grid-cols-3 gap-2">
                  {algorithmQuestions.map(question => (
                    <button
                      key={question.id}
                      role="tab"
                      aria-selected={question.id === selectedAlgorithmQuestion?.id}
                      onClick={() => setSelectedAlgorithmQuestionId(question.id)}
                      className={`rounded-lg border px-3 py-2 text-sm font-medium ${question.id === selectedAlgorithmQuestion?.id
                        ? 'border-primary-500 bg-primary-50 text-primary-700 dark:bg-primary-900/20 dark:text-primary-200'
                        : 'border-slate-200 text-slate-600 dark:border-slate-700 dark:text-slate-300'}`}
                    >
                      第 {question.sequenceNo} 题 · {question.status === 'EVALUATED' ? '已评审' : question.status === 'ANSWERED' ? '待评审' : '待完成'}
                    </button>
                  ))}
                </div>
              )}
              {(isAlgorithmRound ? selectedAlgorithmQuestion ? [selectedAlgorithmQuestion] : [] : questions).map(question => (
                <article key={question.id} className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
                  <p className="text-xs text-primary-600">第 {question.sequenceNo} 题 · {question.categoryKey || question.questionType}</p>
                  {isAlgorithmRound ? <AlgorithmProblemStatement question={question} /> : <p className="mt-3 text-slate-900 dark:text-white">{question.question}</p>}
                  {question.status === 'PENDING' && !isAlgorithmRound && (
                    <form onSubmit={submitAnswer} className="mt-4">
                      <textarea
                        value={answer}
                        onChange={e => setAnswer(e.target.value)}
                        className="h-28 w-full rounded-xl border border-slate-200 p-3 text-sm dark:border-slate-700 dark:bg-slate-800"
                        placeholder="输入你的回答…"
                      />
                      <button className="mt-2 rounded-lg bg-primary-600 px-4 py-2 text-sm text-white">提交回答</button>
                    </form>
                  )}
                  {question.answer && (
                    <p className="mt-3 rounded-lg bg-slate-50 p-3 text-sm text-slate-600 dark:bg-slate-800 dark:text-slate-300">{question.answer}</p>
                  )}
                </article>
              ))}
            </section>
          )}

          {/* Algorithm editor for algorithm round */}
          {isAlgorithmRound && currentRound && selectedAlgorithmQuestion && (
            <Suspense fallback={<div className="rounded-xl border border-slate-200 p-4 text-sm text-slate-500 dark:border-slate-700">加载代码编辑器…</div>}>
              <AlgorithmEditor
                key={selectedAlgorithmQuestion.id}
                questionId={selectedAlgorithmQuestion.id}
                onReviewed={() => {
                  setQuestions(prev => prev.map(question => question.id === selectedAlgorithmQuestion.id
                    ? { ...question, status: 'EVALUATED' }
                    : question));
                  void reloadInterview(activeSessionId);
                }}
              />
            </Suspense>
          )}

          {/* Report */}
          {report && (
            <section className="rounded-2xl border border-slate-200 bg-white p-4 text-sm dark:border-slate-700 dark:bg-slate-900">
              <p className="font-medium text-slate-900 dark:text-white">面试报告</p>
              <p className="mt-1 text-slate-600 dark:text-slate-300">{report.summary}</p>
              <p className="mt-2 text-xs text-slate-500">{report.completedRounds}/{report.totalRounds} 轮完成 · {report.source}</p>
              {report.rounds.length > 0 && (
                <div className="mt-3 grid gap-2 sm:grid-cols-2">
                  {report.rounds.map(round => (
                    <div key={round.roundNo} className="rounded-lg bg-slate-50 p-3 text-xs dark:bg-slate-800">
                      <p className="font-medium">第 {round.roundNo} 轮 · {ROUND_LABELS[round.roundType] ?? round.roundType}</p>
                      <p className="mt-1 text-slate-500">回答 {round.answeredQuestionCount}/{round.questionCount} 题</p>
                      <p className="mt-1 text-slate-500">题型：{Object.entries(round.questionTypes).map(([type, count]) => `${type} ${count}`).join('、') || '无'}</p>
                    </div>
                  ))}
                </div>
              )}
              <button onClick={loadLearningPreview} className="mt-4 rounded-lg border border-primary-300 px-3 py-2 text-xs text-primary-700 dark:border-primary-700 dark:text-primary-300">查看学习建议</button>
              {learningPreview && (
                <div className="mt-3 rounded-lg bg-primary-50 p-3 text-xs text-primary-900 dark:bg-primary-900/20 dark:text-primary-100">
                  <p>{learningPreview.reportSummary}</p>
                  <p className="mt-2 font-medium">建议优先学习：</p>
                  <p className="mt-1">{learningPreview.recommendations.slice(0, 5).map(item => `${item.displayName}（${item.masteryScore}%）`).join('、') || '暂无可用建议'}</p>
                </div>
              )}
            </section>
          )}

          {/* Agent trace */}
          {traces.length > 0 && (
            <section className="rounded-2xl border border-slate-200 bg-white p-4 text-sm dark:border-slate-700 dark:bg-slate-900">
              <p className="font-medium text-slate-900 dark:text-white">Agent 编排轨迹</p>
              {traces.map(trace => (
                <div key={trace.id} className="mt-3 rounded-lg bg-slate-50 p-3 text-xs dark:bg-slate-800">
                  <p>{trace.triggerType} · {trace.status} · {trace.runtimeType === 'GOOGLE_ADK' ? 'Google ADK' : '稳定编排'}</p>
                  {trace.policyVersion && <p className="mt-1 text-slate-500">策略：{trace.policyVersion}{trace.runtimeVersion ? ` · runtime ${trace.runtimeVersion}` : ''}</p>}
                  {trace.errorCode?.includes('FALLBACK') && <p className="mt-1 text-amber-700 dark:text-amber-300">本轮已使用稳定出题策略继续。</p>}
                  {trace.steps.map(step => (
                    <p key={step.stepNo} className="mt-1 text-slate-500">{step.stepNo}. {step.eventType ?? 'MODEL'} · {step.toolName} · {step.status}{step.outputSummary ? ` · ${step.outputSummary}` : ''}</p>
                  ))}
                </div>
              ))}
            </section>
          )}
        </>
      )}
    </div>
  );
}
