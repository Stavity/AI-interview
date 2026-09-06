import { useEffect, useRef, useState } from 'react';
import { Code2, RotateCcw, Save } from 'lucide-react';
import CodeMirror from '@uiw/react-codemirror';
import { java } from '@codemirror/lang-java';
import { python } from '@codemirror/lang-python';
import { cpp } from '@codemirror/lang-cpp';
import { autocompletion, completeFromList, type Completion } from '@codemirror/autocomplete';
import { multiRoundApi, type AlgorithmLanguage, type AlgorithmStaticReview, type AlgorithmTemplate } from '../../api/multiRound';

const COMPLETIONS: Record<AlgorithmLanguage, Completion[]> = {
  JAVA: ['class', 'public', 'private', 'static', 'void', 'int', 'long', 'boolean', 'List', 'Map', 'HashMap', 'HashSet', 'Arrays', 'for', 'while', 'return'].map(label => ({ label, type: 'keyword' })),
  PYTHON: ['def', 'class', 'list', 'dict', 'set', 'for', 'while', 'return', 'range', 'enumerate', 'collections', 'defaultdict', 'deque'].map(label => ({ label, type: 'keyword' })),
  CPP: ['vector', 'unordered_map', 'unordered_set', 'queue', 'stack', 'sort', 'lower_bound', 'for', 'while', 'return', 'int', 'long long'].map(label => ({ label, type: 'keyword' })),
};

function parsePersistedReview(value: string): AlgorithmStaticReview | null {
  try {
    const parsed = JSON.parse(value) as Partial<AlgorithmStaticReview>;
    if (typeof parsed.score !== 'number') return null;
    const criteriaAvailable = typeof parsed.correctnessPassed === 'boolean' && typeof parsed.complexityPassed === 'boolean';
    return {
      score: parsed.score,
      correctnessPassed: parsed.correctnessPassed ?? false,
      complexityPassed: parsed.complexityPassed ?? false,
      requiredTimeComplexity: parsed.requiredTimeComplexity ?? '未说明',
      requiredSpaceComplexity: parsed.requiredSpaceComplexity ?? '未说明',
      approach: parsed.approach ?? '未说明', timeComplexity: parsed.timeComplexity ?? '未说明', spaceComplexity: parsed.spaceComplexity ?? '未说明',
      boundaryCases: parsed.boundaryCases ?? [], blockingIssues: parsed.blockingIssues ?? [], qualityIssues: parsed.qualityIssues ?? [], improvements: parsed.improvements ?? [],
      notice: '本结果为 AI 静态评审，未执行、编译或验证代码。',
      criteriaAvailable,
    };
  } catch { return null; }
}

export default function AlgorithmEditor({ questionId, onReviewed }: { questionId: number; onReviewed?: () => void }) {
  const [templates, setTemplates] = useState<AlgorithmTemplate[]>([]); const [language, setLanguage] = useState<AlgorithmLanguage>('JAVA');
  const [code, setCode] = useState(''); const [notice, setNotice] = useState('AI 静态评审，未执行代码'); const [saving, setSaving] = useState(false);
  const [review, setReview] = useState<AlgorithmStaticReview | null>(null);
  const [evaluationStatus, setEvaluationStatus] = useState<'PENDING' | 'COMPLETED' | 'FAILED' | null>(null);
  const submissionKey = useRef(`algorithm-submit:${questionId}:${crypto.randomUUID()}`);
  const draftKey = (value: AlgorithmLanguage) => `multi-round-algorithm-draft:${questionId}:${value}`;
  const languageKey = `multi-round-algorithm-language:${questionId}`;
  useEffect(() => {
    let cancelled = false;
    setReview(null); setEvaluationStatus(null); setNotice('AI 静态评审，未执行代码');
    Promise.all([multiRoundApi.templates(), multiRoundApi.getAlgorithmSubmissionForQuestion(questionId)]).then(([values, submission]) => {
      if (cancelled) return;
      const savedLanguage = sessionStorage.getItem(languageKey) as AlgorithmLanguage | null;
      const nextLanguage = submission?.language ?? (savedLanguage && ['JAVA', 'PYTHON', 'CPP'].includes(savedLanguage) ? savedLanguage : 'JAVA');
      setTemplates(values); setLanguage(nextLanguage);
      setCode(submission?.sourceCode ?? sessionStorage.getItem(draftKey(nextLanguage)) ?? values.find(value => value.language === nextLanguage)?.sourceCode ?? '');
      if (submission) {
        setEvaluationStatus(submission.evaluationStatus);
        setNotice(submission.notice);
        if (submission.evaluationStatus === 'COMPLETED') setReview(parsePersistedReview(submission.evaluation));
      }
    }).catch(() => { if (!cancelled) setNotice('编辑器数据加载失败'); });
    return () => { cancelled = true; };
  }, [questionId]);
  const selectLanguage = (value: AlgorithmLanguage) => {
    sessionStorage.setItem(draftKey(language), code);
    sessionStorage.setItem(languageKey, value);
    setLanguage(value);
    setCode(sessionStorage.getItem(draftKey(value)) ?? templates.find(item => item.language === value)?.sourceCode ?? '');
  };
  const languageExtension = language === 'JAVA' ? java() : language === 'PYTHON' ? python() : cpp();
  const completionExtension = autocompletion({ override: [completeFromList(COMPLETIONS[language])] });
  const submit = async () => { setSaving(true); setReview(null); let submissionId: number | null = null; try { const result = await multiRoundApi.submitAlgorithm(questionId, submissionKey.current, language, code); submissionId = result.id; setEvaluationStatus(result.evaluationStatus); const evaluated = await multiRoundApi.reviewAlgorithm(result.id); setReview(evaluated); setEvaluationStatus('COMPLETED'); setNotice(evaluated.notice); onReviewed?.(); } catch { if (submissionId) { try { const latest = await multiRoundApi.getAlgorithmSubmission(submissionId); setEvaluationStatus(latest.evaluationStatus); if (latest.evaluationStatus === 'COMPLETED') onReviewed?.(); } catch { setEvaluationStatus('FAILED'); } } setNotice('代码快照已保存，但静态评审失败，可点击按钮重试'); } finally { setSaving(false); } };
  return <section className="rounded-2xl border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900"><header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4 dark:border-slate-800"><div className="flex items-center gap-2"><Code2 className="w-5 h-5 text-primary-600"/><h2 className="font-semibold text-slate-900 dark:text-white">算法代码草稿</h2></div><div className="flex gap-2">{(['JAVA', 'PYTHON', 'CPP'] as AlgorithmLanguage[]).map(item => <button key={item} onClick={() => selectLanguage(item)} className={`rounded-lg px-3 py-1.5 text-xs font-medium ${language === item ? 'bg-primary-600 text-white' : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'}`}>{item}</button>)}</div></header><div className="p-5"><div className="overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700"><CodeMirror value={code} height="20rem" theme="dark" extensions={[languageExtension, completionExtension]} onChange={value => { setCode(value); sessionStorage.setItem(draftKey(language), value); }} basicSetup={{ lineNumbers: true, highlightActiveLine: true, foldGutter: true, autocompletion: false }}/></div><div className="mt-3 flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs text-amber-600 dark:text-amber-400">{notice}</p>{evaluationStatus && <p className={`mt-1 text-xs ${evaluationStatus === 'FAILED' ? 'text-red-600' : 'text-slate-500'}`}>评审状态：{evaluationStatus === 'PENDING' ? '处理中' : evaluationStatus === 'COMPLETED' ? '已完成' : '失败，可重试'}</p>}</div><div className="flex gap-2"><button onClick={() => { const template = templates.find(item => item.language === language)?.sourceCode ?? ''; setCode(template); sessionStorage.setItem(draftKey(language), template); }} className="inline-flex items-center gap-1 rounded-lg px-3 py-2 text-sm text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"><RotateCcw className="w-4 h-4"/>重置模板</button><button disabled={saving || !code.trim()} onClick={submit} className="inline-flex items-center gap-1 rounded-lg bg-primary-600 px-3 py-2 text-sm font-medium text-white disabled:opacity-50"><Save className="w-4 h-4"/>{saving ? '评审中' : '保存并静态评审'}</button></div></div>{review && <div className="mt-4 rounded-xl bg-slate-50 p-4 text-sm dark:bg-slate-800"><p className="font-medium text-slate-900 dark:text-white">静态评审得分：{review.score}/100</p>{review.criteriaAvailable !== false && <><p className="mt-2 text-slate-600 dark:text-slate-300">正确性：{review.correctnessPassed ? '通过' : '未通过'} · 复杂度：{review.complexityPassed ? '通过' : '未通过'}</p><p className="mt-1 text-slate-600 dark:text-slate-300">要求：时间 {review.requiredTimeComplexity} · 空间 {review.requiredSpaceComplexity}</p></>}<p className="mt-1 text-slate-600 dark:text-slate-300">实际：时间 {review.timeComplexity} · 空间 {review.spaceComplexity}</p><p className="mt-2 text-slate-600 dark:text-slate-300">思路：{review.approach}</p>{review.blockingIssues.length > 0 && <p className="mt-1 text-red-600 dark:text-red-400">阻塞问题：{review.blockingIssues.join('；')}</p>}{review.improvements.length > 0 && <p className="mt-1 text-slate-600 dark:text-slate-300">改进：{review.improvements.join('；')}</p>}</div>}</div></section>;
}
