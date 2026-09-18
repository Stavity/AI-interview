import { useEffect, useRef, useState } from 'react';
import { Code2, RotateCcw, Save } from 'lucide-react';
import CodeMirror from '@uiw/react-codemirror';
import { java } from '@codemirror/lang-java';
import { python } from '@codemirror/lang-python';
import { cpp } from '@codemirror/lang-cpp';
import { multiRoundApi, type AlgorithmLanguage, type AlgorithmStaticReview, type AlgorithmTemplate } from '../../api/multiRound';

export default function AlgorithmEditor({ questionId }: { questionId: number }) {
  const [templates, setTemplates] = useState<AlgorithmTemplate[]>([]);
  const [language, setLanguage] = useState<AlgorithmLanguage>('JAVA');
  const [code, setCode] = useState('');
  const [notice, setNotice] = useState('AI 静态评审，未执行代码');
  const [saving, setSaving] = useState(false);
  const [review, setReview] = useState<AlgorithmStaticReview | null>(null);
  const [evaluationStatus, setEvaluationStatus] = useState<'PENDING' | 'COMPLETED' | 'FAILED' | null>(null);
  const mounted = useRef(true);

  useEffect(() => () => { mounted.current = false; }, []);

  useEffect(() => {
    let cancelled = false;
    multiRoundApi.templates()
      .then(values => {
        if (cancelled) return;
        setTemplates(values);
        setCode(values.find(value => value.language === 'JAVA')?.sourceCode ?? '');
      })
      .catch(() => { if (!cancelled) setNotice('模板加载失败'); });
    return () => { cancelled = true; };
  }, []);

  const selectLanguage = (value: AlgorithmLanguage) => {
    setLanguage(value);
    setCode(templates.find(item => item.language === value)?.sourceCode ?? '');
  };

  const languageExtension = language === 'JAVA' ? java() : language === 'PYTHON' ? python() : cpp();

  /**
   * 提交代码并静态评审。
   * <p>
   * 幂等键每次点击重新生成：原实现用 useRef 只在挂载时生成一次并全程复用，
   * 后端命中幂等键后直接返回旧提交，用户修改过的代码被静默丢弃、
   * 评审结果仍来自旧代码。后端 submit 本身是「有则更新」，支持重复提交。
   */
  const submit = async () => {
    if (saving) return;
    setSaving(true);
    setReview(null);
    setEvaluationStatus(null);
    let submissionId: number | null = null;
    try {
      const submissionKey = `algorithm-submit:${questionId}:${crypto.randomUUID()}`;
      const result = await multiRoundApi.submitAlgorithm(questionId, submissionKey, language, code);
      submissionId = result.id;
      const evaluated = await multiRoundApi.reviewAlgorithm(result.id);
      if (!mounted.current) return;
      setReview(evaluated);
      setEvaluationStatus('COMPLETED');
      setNotice(evaluated.notice);
    } catch {
      if (!mounted.current) return;
      if (submissionId) {
        // 代码已落库，仅评审失败，可重试
        try {
          const latest = await multiRoundApi.getAlgorithmSubmission(submissionId);
          if (mounted.current) setEvaluationStatus(latest.evaluationStatus);
        } catch {
          if (mounted.current) setEvaluationStatus('FAILED');
        }
        if (mounted.current) setNotice('代码快照已保存，但静态评审失败，可点击按钮重试');
      } else {
        // 提交本身失败，什么都没存下来。原实现这里也提示「已保存」，误导用户
        setEvaluationStatus('FAILED');
        setNotice('代码提交失败，请检查网络后重试');
      }
    } finally {
      if (mounted.current) setSaving(false);
    }
  };

  return (
    <section className="rounded-2xl border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4 dark:border-slate-800">
        <div className="flex items-center gap-2">
          <Code2 className="w-5 h-5 text-primary-600" />
          <h2 className="font-semibold text-slate-900 dark:text-white">算法代码草稿</h2>
        </div>
        <div className="flex gap-2">
          {(['JAVA', 'PYTHON', 'CPP'] as AlgorithmLanguage[]).map(item => (
            <button
              key={item}
              type="button"
              disabled={saving}
              onClick={() => selectLanguage(item)}
              className={`rounded-lg px-3 py-1.5 text-xs font-medium disabled:opacity-50 ${language === item ? 'bg-primary-600 text-white' : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'}`}
            >
              {item}
            </button>
          ))}
        </div>
      </header>
      <div className="p-5">
        <div className="overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700">
          <CodeMirror
            value={code}
            height="20rem"
            theme="dark"
            extensions={[languageExtension]}
            onChange={setCode}
            basicSetup={{ lineNumbers: true, highlightActiveLine: true, foldGutter: true }}
          />
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-xs text-amber-600 dark:text-amber-400">{notice}</p>
            {evaluationStatus && (
              <p className={`mt-1 text-xs ${evaluationStatus === 'FAILED' ? 'text-red-600' : 'text-slate-500'}`}>
                评审状态：{evaluationStatus === 'PENDING' ? '处理中' : evaluationStatus === 'COMPLETED' ? '已完成' : '失败，可重试'}
              </p>
            )}
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={saving}
              onClick={() => setCode(templates.find(item => item.language === language)?.sourceCode ?? '')}
              className="inline-flex items-center gap-1 rounded-lg px-3 py-2 text-sm text-slate-600 hover:bg-slate-100 disabled:opacity-50 dark:text-slate-300 dark:hover:bg-slate-800"
            >
              <RotateCcw className="w-4 h-4" />重置模板
            </button>
            <button
              type="button"
              disabled={saving || !code.trim()}
              onClick={submit}
              className="inline-flex items-center gap-1 rounded-lg bg-primary-600 px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
            >
              <Save className="w-4 h-4" />{saving ? '评审中' : '保存并静态评审'}
            </button>
          </div>
        </div>
        {review && (
          <div className="mt-4 rounded-xl bg-slate-50 p-4 text-sm dark:bg-slate-800">
            <p className="font-medium text-slate-900 dark:text-white">静态评审得分：{review.score}/100</p>
            <p className="mt-2 text-slate-600 dark:text-slate-300">思路：{review.approach}</p>
            <p className="mt-1 text-slate-600 dark:text-slate-300">时间 {review.timeComplexity} · 空间 {review.spaceComplexity}</p>
            {review.improvements.length > 0 && (
              <p className="mt-1 text-slate-600 dark:text-slate-300">改进：{review.improvements.join('；')}</p>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
