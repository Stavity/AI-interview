import { useEffect, useState } from 'react';
import { Loader2, Search, X } from 'lucide-react';
import { questionTagApi, type QuestionTagNode } from '../../api/questionTags';
import { INPUT_CLASS } from '../../constants/knowledgebaseInterview';

export interface QuestionTagOption {
  code: string;
  name: string;
}

interface QuestionKnowledgeTagPickerProps {
  primary: QuestionTagOption | null;
  secondary: QuestionTagOption[];
  onChange: (primary: QuestionTagOption | null, secondary: QuestionTagOption[]) => void;
}

export default function QuestionKnowledgeTagPicker({ primary, secondary, onChange }: QuestionKnowledgeTagPickerProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<QuestionTagNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const normalized = query.trim();
    if (normalized.length < 2) {
      setResults([]);
      setError('');
      return;
    }
    let cancelled = false;
    const timer = setTimeout(async () => {
      setLoading(true);
      setError('');
      try {
        const response = await questionTagApi.search(normalized);
        if (!cancelled) setResults(response.results.map(result => result.node));
      } catch (cause) {
        if (!cancelled) {
          setResults([]);
          setError(cause instanceof Error ? cause.message : '知识点查询失败');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query]);

  const setPrimary = (node: QuestionTagNode) => {
    const selected = { code: node.code, name: node.name };
    onChange(selected, secondary.filter(item => item.code !== selected.code));
  };

  const addSecondary = (node: QuestionTagNode) => {
    if (secondary.length >= 4 || primary?.code === node.code || secondary.some(item => item.code === node.code)) return;
    onChange(primary, [...secondary, { code: node.code, name: node.name }]);
  };

  return (
    <div className="space-y-3">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
        <input
          value={query}
          onChange={event => setQuery(event.target.value)}
          className={`${INPUT_CLASS} pl-9`}
          placeholder="搜索知识点名称、别名或 code（至少 2 个字符）"
        />
        {loading && <Loader2 className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 animate-spin text-slate-400" />}
      </div>

      {(primary || secondary.length > 0) && (
        <div className="space-y-2 rounded-lg bg-slate-50 dark:bg-slate-800/70 p-3">
          {primary ? (
            <SelectedTag label="主知识点" item={primary} onRemove={() => onChange(null, secondary)} />
          ) : (
            <p className="text-xs text-amber-600 dark:text-amber-400">尚未选择主知识点；启用题目时必须设置。</p>
          )}
          {secondary.map(item => (
            <SelectedTag
              key={item.code}
              label="次知识点"
              item={item}
              onRemove={() => onChange(primary, secondary.filter(value => value.code !== item.code))}
            />
          ))}
        </div>
      )}

      {error && <p className="text-xs text-red-500">{error}</p>}
      {results.length > 0 && (
        <div className="max-h-64 overflow-y-auto rounded-lg border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-700">
          {results.map(node => (
            <div key={node.code} className="flex items-center gap-3 px-3 py-2">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-slate-800 dark:text-slate-100">{node.name}</p>
                <p className="text-[11px] text-slate-400 break-all">{node.code}</p>
              </div>
              <button type="button" onClick={() => setPrimary(node)} className="text-xs text-primary-600 hover:underline">设为主</button>
              <button
                type="button"
                disabled={secondary.length >= 4 || primary?.code === node.code || secondary.some(item => item.code === node.code)}
                onClick={() => addSecondary(node)}
                className="text-xs text-slate-600 dark:text-slate-300 hover:underline disabled:opacity-40 disabled:no-underline"
              >
                加为次
              </button>
            </div>
          ))}
        </div>
      )}
      {query.trim().length >= 2 && !loading && results.length === 0 && !error && (
        <p className="text-xs text-slate-400">没有匹配的可挂载知识点。</p>
      )}
    </div>
  );
}

function SelectedTag({ label, item, onRemove }: { label: string; item: QuestionTagOption; onRemove: () => void }) {
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="shrink-0 rounded bg-primary-100 dark:bg-primary-900/40 px-2 py-1 text-primary-700 dark:text-primary-300">{label}</span>
      <span className="min-w-0 flex-1 truncate text-slate-700 dark:text-slate-200" title={item.code}>{item.name} <span className="text-slate-400">· {item.code}</span></span>
      <button type="button" aria-label={`移除${label}`} onClick={onRemove} className="p-1 text-slate-400 hover:text-red-500"><X className="w-3.5 h-3.5" /></button>
    </div>
  );
}
