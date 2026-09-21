import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AlertCircle, ChevronRight, Clock, Loader2, Play, Square } from 'lucide-react';
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

export default function MultiRoundInterviewPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const initialSessionId = params.get('sessionId') ?? '';
  const [activeSessionId] = useState(initialSessionId);
  const [questions, setQuestions] = useState<MultiRoundQuestion[]>([]);
  // 每道题各自的草稿，键为 questionId。
  // 原实现是单个 answer state：一轮多题时所有输入框共享同一值，
  // 且提交时固定取第一道 PENDING 题——在第 2 题输入会写到第 1 题上。
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  // 正在进行中的操作标识，用于禁用按钮防重复提交
  const [busy, setBusy] = useState<string | null>(null);
  const [interview, setInterview] = useState<MultiRoundDetail | null>(null);
  const [report, setReport] = useState<MultiRoundReport | null>(null);
  const [traces, setTraces] = useState<AgentRunTrace[]>([]);
  const [learningPreview, setLearningPreview] = useState<InterviewRecommendationPreview | null>(null);
  const [activeRoundId, setActiveRoundId] = useState<number | null>(null);
  const answerKeys = useRef(new Map<number, string>());
  // 视图请求序号：reloadInterview 与 selectRound 都会写 activeRoundId/questions，
  // 并发时靠它丢弃过期响应，避免旧结果覆盖新状态
  const viewSeq = useRef(0);
  const mounted = useRef(true);

  // React StrictMode 在开发环境会执行一次 setup → cleanup → setup。
  // 每次 setup 都恢复标记，避免第一次 cleanup 让后续请求结果永久被忽略。
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const reloadInterview = async (sid: string) => {
    const seq = ++viewSeq.current;
    const [detail, value, trace] = await Promise.all([
      multiRoundApi.getInterview(sid),
      multiRoundApi.getReport(sid),
      multiRoundApi.getAgentTrace(sid),
    ]);
    if (seq !== viewSeq.current || !mounted.current) return;
    setInterview(detail);
    setReport(value);
    setTraces(trace);

    const currentRound = detail.rounds.find(r => r.status === 'IN_PROGRESS');
    if (currentRound) {
      const qs = await multiRoundApi.listQuestions(currentRound.id);
      if (seq !== viewSeq.current || !mounted.current) return;
      setActiveRoundId(currentRound.id);
      setQuestions(qs);
      return;
    }
    const nextRound = detail.rounds.find(r => r.status === 'PENDING' && r.roundNo === detail.interview.currentRoundNo + 1);
    setActiveRoundId(nextRound ? nextRound.id : null);
    setQuestions([]);
  };

  useEffect(() => {
    if (!activeSessionId) {
      navigate('/history', { replace: true });
      return;
    }
    setLoading(true);
    reloadInterview(activeSessionId)
      .catch(() => setError('多轮会话创建或加载失败'))
      .finally(() => { if (mounted.current) setLoading(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId, navigate]);

  const submitAnswer = async (event: FormEvent, question: MultiRoundQuestion) => {
    event.preventDefault();
    const draft = (answers[question.id] ?? '').trim();
    if (!draft || busy) return;
    setError('');
    setBusy(`answer:${question.id}`);
    // 同一题复用同一幂等键：重试提交同一答案不会重复写入
    const key = answerKeys.current.get(question.id) ?? `answer:${question.id}:${crypto.randomUUID()}`;
    answerKeys.current.set(question.id, key);
    try {
      await multiRoundApi.answerQuestion(question.id, key, draft);
      if (!mounted.current) return;
      setQuestions(prev => prev.map(q => q.id === question.id ? { ...q, answer: draft, status: 'ANSWERED' } : q));
      setAnswers(prev => { const next = { ...prev }; delete next[question.id]; return next; });
    } catch {
      if (mounted.current) setError('提交回答失败');
    } finally {
      if (mounted.current) setBusy(null);
    }
  };

  const startRound = async (id: number) => {
    if (busy) return;
    setError('');
    setBusy(`start:${id}`);
    try {
      await multiRoundApi.startRound(id, `round-start:${id}:${crypto.randomUUID()}`);
      await reloadInterview(activeSessionId);
    } catch {
      if (mounted.current) setError('轮次启动失败');
    } finally {
      if (mounted.current) setBusy(null);
    }
  };

  /**
   * 结束本轮后进入下一轮。
   * 两步分开处理：原实现把 finish 与 nextRound 串在同一个 try 里，
   * finish 成功而 nextRound 失败时会提示「当前轮次尚不能结束」（与事实相反）且跳过 reload，
   * 界面仍显示进行中，用户只能刷新页面才能恢复。
   */
  const finishRound = async (id: number) => {
    if (busy) return;
    setError('');
    setBusy(`finish:${id}`);
    let finished = false;
    try {
      await multiRoundApi.finishRound(id, `round-finish:${id}:${crypto.randomUUID()}`);
      finished = true;
      await multiRoundApi.nextRound(activeSessionId, `next-round:${id}:${crypto.randomUUID()}`);
    } catch {
      if (mounted.current) {
        setError(finished ? '本轮已结束，但进入下一轮失败，请重试' : '当前轮次尚不能结束');
      }
    } finally {
      // 无论哪一步失败都刷新，保证界面与服务端状态一致
      try {
        await reloadInterview(activeSessionId);
      } catch {
        if (mounted.current) setError('状态刷新失败，请重新加载页面');
      }
      if (mounted.current) setBusy(null);
    }
  };

  const selectRound = async (roundId: number) => {
    setError('');
    const seq = ++viewSeq.current;
    setActiveRoundId(roundId);
    try {
      const qs = await multiRoundApi.listQuestions(roundId);
      if (seq !== viewSeq.current || !mounted.current) return;
      setQuestions(qs);
    } catch {
      if (mounted.current) setError('题目加载失败');
    }
  };

  const loadLearningPreview = async () => {
    if (!activeSessionId || busy) return;
    setError('');
    setBusy('learning');
    try {
      const preview = await learningApi.recommendationsFromInterview(activeSessionId);
      if (mounted.current) setLearningPreview(preview);
    } catch {
      if (mounted.current) setError('学习建议加载失败');
    } finally {
      if (mounted.current) setBusy(null);
    }
  };

  const currentRound = interview?.rounds.find(r => r.id === activeRoundId);
  const isAlgorithmRound = currentRound?.roundType === 'ALGORITHM';
  // 算法轮取待作答题目，没有待答的再退回首题；原实现盲取 questions[0]
  const algorithmQuestion = questions.find(q => q.status === 'PENDING') ?? questions[0];

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
                /* 外层改用 div：button 内嵌 button 属非法 HTML，浏览器会提前闭合外层按钮 */
                <div
                  key={round.id}
                  role="button"
                  tabIndex={0}
                  aria-pressed={activeRoundId === round.id}
                  onClick={() => selectRound(round.id)}
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectRound(round.id); } }}
                  className={`cursor-pointer rounded-xl border p-3 text-left transition-all ${ROUND_COLORS[round.roundType] ?? 'border-slate-200'} ${activeRoundId === round.id ? 'ring-2 ring-primary-500' : ''}`}
                >
                  <div className="flex items-center justify-between">
                    <p className="text-xs font-medium text-slate-700 dark:text-slate-200">{round.roundNo}. {ROUND_LABELS[round.roundType] ?? round.roundType}</p>
                    {round.status === 'IN_PROGRESS' && <Clock className="w-3 h-3 text-primary-600" />}
                  </div>
                  <p className="mt-1 text-xs text-slate-500">{round.status} · 最多 {round.questionLimit} 题</p>
                  {round.status === 'PENDING' && round.roundNo === interview.interview.currentRoundNo + 1 && (
                    <button
                      type="button"
                      disabled={busy !== null}
                      onClick={e => { e.stopPropagation(); startRound(round.id); }}
                      className="mt-2 flex items-center gap-1 rounded-md bg-primary-600 px-2 py-1 text-xs text-white disabled:opacity-50"
                    >
                      {busy === `start:${round.id}` ? <Loader2 className="w-3 h-3 animate-spin" /> : <Play className="w-3 h-3" />}
                      {busy === `start:${round.id}` ? '启动中' : '启动本轮'}
                    </button>
                  )}
                  {round.status === 'IN_PROGRESS' && (
                    <button
                      type="button"
                      disabled={busy !== null}
                      onClick={e => { e.stopPropagation(); finishRound(round.id); }}
                      className="mt-2 flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1 text-xs disabled:opacity-50 dark:border-slate-600"
                    >
                      {busy === `finish:${round.id}` ? <Loader2 className="w-3 h-3 animate-spin" /> : <Square className="w-3 h-3" />}
                      {busy === `finish:${round.id}` ? '结束中' : '结束本轮'}
                    </button>
                  )}
                </div>
              ))}
            </div>
          </section>

          {/* 空状态：已选中轮次但尚无题目，原实现此处是一片空白 */}
          {currentRound && questions.length === 0 && !loading && (
            <section className="rounded-2xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:border-slate-700">
              {currentRound.status === 'PENDING'
                ? '本轮尚未启动，点击上方「启动本轮」开始出题。'
                : '本轮暂无题目，稍后重试或重新启动本轮。'}
            </section>
          )}

          {/* Questions for active round */}
          {currentRound && questions.length > 0 && (
            <section className="space-y-4">
              <div className="flex items-center gap-2">
                <ChevronRight className="w-4 h-4 text-primary-600" />
                <h2 className="text-sm font-semibold text-slate-900 dark:text-white">
                  {ROUND_LABELS[currentRound.roundType] ?? currentRound.roundType} · 题目
                </h2>
              </div>
              {questions.map(question => (
                <article key={question.id} className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
                  <p className="text-xs text-primary-600">第 {question.sequenceNo} 题 · {question.categoryKey || question.questionType}</p>
                  <p className="mt-3 text-slate-900 dark:text-white">{question.question}</p>
                  {question.status === 'PENDING' && !isAlgorithmRound && (
                    <form onSubmit={e => submitAnswer(e, question)} className="mt-4">
                      <textarea
                        value={answers[question.id] ?? ''}
                        onChange={e => setAnswers(prev => ({ ...prev, [question.id]: e.target.value }))}
                        className="h-28 w-full rounded-xl border border-slate-200 p-3 text-sm dark:border-slate-700 dark:bg-slate-800"
                        placeholder="输入你的回答…"
                      />
                      <button
                        type="submit"
                        disabled={busy !== null || !(answers[question.id] ?? '').trim()}
                        className="mt-2 rounded-lg bg-primary-600 px-4 py-2 text-sm text-white disabled:opacity-50"
                      >
                        {busy === `answer:${question.id}` ? '提交中…' : '提交回答'}
                      </button>
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
          {isAlgorithmRound && currentRound && algorithmQuestion && (
            <Suspense fallback={<div className="rounded-xl border border-slate-200 p-4 text-sm text-slate-500 dark:border-slate-700">加载代码编辑器…</div>}>
              <AlgorithmEditor questionId={algorithmQuestion.id} />
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
              <button
                type="button"
                disabled={busy !== null}
                onClick={loadLearningPreview}
                className="mt-4 rounded-lg border border-primary-300 px-3 py-2 text-xs text-primary-700 disabled:opacity-50 dark:border-primary-700 dark:text-primary-300"
              >
                {busy === 'learning' ? '加载中…' : '查看学习建议'}
              </button>
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
                  <p>{trace.triggerType} · {trace.status}</p>
                  {trace.steps.map(step => (
                    <p key={step.stepNo} className="mt-1 text-slate-500">{step.stepNo}. {step.toolName} · {step.status}{step.outputSummary ? ` · ${step.outputSummary}` : ''}</p>
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
