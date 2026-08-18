import { FormEvent, type MouseEvent, useEffect, useState } from 'react';
import { Archive, Bot, MessageSquarePlus, Pencil, Pin, Send, Trash2 } from 'lucide-react';
import { chatApi, type ChatMessage, type ChatSession } from '../api/chat';

const LAST_SESSION_STORAGE_KEY = 'chat:last-session-id';

export default function ChatPage() {
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [active, setActive] = useState<ChatSession | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const loadSession = async (session: ChatSession) => { const detail = await chatApi.getSession(session.id); setActive(detail.session); setMessages(detail.messages); };

  useEffect(() => { chatApi.listSessions().then(async values => { setSessions(values); const savedId = Number(window.localStorage.getItem(LAST_SESSION_STORAGE_KEY)); const saved = values.find(item => item.id === savedId); if (!saved) return; await loadSession(saved); }).catch(() => setError('加载对话列表失败')); }, []);
  const createSession = async () => {
    const session = await chatApi.createSession();
    setSessions(current => [session, ...current]); setActive(session); window.localStorage.setItem(LAST_SESSION_STORAGE_KEY, String(session.id)); setMessages([]); setError('');
  };
  const selectSession = async (session: ChatSession) => {
    setActive(session); window.localStorage.setItem(LAST_SESSION_STORAGE_KEY, String(session.id)); setMessages([]); setError('');
    try { await loadSession(session); }
    catch { setError('加载对话消息失败'); }
  };
  const pinSession = async (event: MouseEvent, session: ChatSession) => { event.stopPropagation(); await chatApi.setPinned(session.id, !session.pinned); setSessions(current => current.map(item => item.id === session.id ? { ...item, pinned: !item.pinned } : item)); };
  const archiveSession = async (event: MouseEvent, session: ChatSession) => { event.stopPropagation(); await chatApi.archive(session.id); setSessions(current => current.filter(item => item.id !== session.id)); if (active?.id === session.id) { window.localStorage.removeItem(LAST_SESSION_STORAGE_KEY); setActive(null); setMessages([]); } };
  const renameSession = async (event: MouseEvent, session: ChatSession) => { event.stopPropagation(); const title = window.prompt('会话标题', session.title)?.trim(); if (!title) return; await chatApi.rename(session.id, title); setSessions(current => current.map(item => item.id === session.id ? { ...item, title } : item)); if (active?.id === session.id) setActive({ ...active, title }); };
  const deleteSession = async (event: MouseEvent, session: ChatSession) => { event.stopPropagation(); if (!window.confirm(`删除“${session.title}”及其全部消息？`)) return; await chatApi.deleteSession(session.id); setSessions(current => current.filter(item => item.id !== session.id)); if (active?.id === session.id) { window.localStorage.removeItem(LAST_SESSION_STORAGE_KEY); setActive(null); setMessages([]); } };
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!active || !input.trim() || sending) return;
    const content = input.trim(); setInput(''); setSending(true); setError('');
    setMessages(current => [...current,
      { id: -Date.now(), role: 'user', content, completed: true, messageOrder: current.length, createdAt: '' },
      { id: -Date.now() - 1, role: 'assistant', content: '', completed: false, messageOrder: current.length + 1, createdAt: '' }]);
    await chatApi.sendMessageStream(active.id, content,
      chunk => setMessages(current => current.map((item, index) => index === current.length - 1 ? { ...item, content: item.content + chunk } : item)),
      () => setSending(false), error => { setSending(false); setError(error.message); });
  };
  const retry = async (message: ChatMessage) => {
    if (!active || sending) return; setSending(true); setError('');
    setMessages(current => current.map(item => item.id === message.id ? { ...item, content: '', completed: false, failureCode: undefined } : item));
    await chatApi.retryMessageStream(active.id, message.id,
      chunk => setMessages(current => current.map(item => item.id === message.id ? { ...item, content: item.content + chunk } : item)),
      () => setSending(false), error => { setSending(false); setMessages(current => current.map(item => item.id === message.id ? { ...item, completed: true, failureCode: 'AI_SERVICE_ERROR' } : item)); setError(error.message); });
  };
  return <div className="grid grid-cols-1 lg:grid-cols-[280px_minmax(0,1fr)] gap-5 h-[calc(100dvh-5rem)]">
    <aside className="rounded-2xl border border-slate-200 bg-white dark:bg-slate-900 dark:border-slate-700 p-3 flex flex-col min-h-0">
      <button onClick={createSession} className="flex items-center justify-center gap-2 rounded-xl bg-primary-600 px-3 py-2.5 text-sm font-semibold text-white hover:bg-primary-700 active:scale-[0.98]"><MessageSquarePlus className="w-4 h-4" />新建对话</button>
      <div className="mt-4 space-y-1 overflow-y-auto">{sessions.map(session => <div key={session.id} onClick={() => selectSession(session)} className={`group w-full cursor-pointer rounded-xl px-3 py-2.5 ${active?.id === session.id ? 'bg-primary-50 text-primary-700 dark:bg-primary-900/30 dark:text-primary-300' : 'hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-300'}`}><div className="flex items-center gap-1"><p className="min-w-0 flex-1 truncate text-sm font-medium">{session.title}</p><button title="置顶" onClick={event => pinSession(event, session)} className="p-1 opacity-0 group-hover:opacity-100"><Pin className={`w-3.5 h-3.5 ${session.pinned ? 'fill-current' : ''}`}/></button><button title="重命名" onClick={event => renameSession(event, session)} className="p-1 opacity-0 group-hover:opacity-100"><Pencil className="w-3.5 h-3.5"/></button><button title="归档" onClick={event => archiveSession(event, session)} className="p-1 opacity-0 group-hover:opacity-100"><Archive className="w-3.5 h-3.5"/></button><button title="删除" onClick={event => deleteSession(event, session)} className="p-1 opacity-0 group-hover:opacity-100 text-red-500"><Trash2 className="w-3.5 h-3.5"/></button></div><p className="mt-1 text-xs opacity-60 truncate">{session.providerId}</p></div>)}</div>
    </aside>
    <section className="rounded-2xl border border-slate-200 bg-white dark:bg-slate-900 dark:border-slate-700 flex min-h-0 flex-col">
      {!active ? <div className="m-auto text-center text-slate-500"><Bot className="w-10 h-10 mx-auto mb-3 text-primary-500" /><p className="font-medium">开始一段普通对话</p><p className="mt-1 text-sm">新建会话后，模型配置将保持固定。</p></div> : <><header className="border-b border-slate-100 dark:border-slate-800 px-6 py-4"><h1 className="font-semibold text-slate-900 dark:text-white">{active.title}</h1><p className="mt-1 text-xs text-slate-500">模型：{active.providerId}</p></header><div className="flex-1 overflow-y-auto p-6 space-y-4">{messages.map((message) => <div key={message.id} className={`max-w-[80%] rounded-2xl px-4 py-3 text-sm leading-6 ${message.role === 'user' ? 'ml-auto bg-primary-600 text-white' : 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100'}`}>{message.content || (sending ? '正在生成…' : '')}{message.failureCode && <button onClick={() => retry(message)} disabled={sending} className="ml-3 rounded-md border border-red-300 px-2 py-1 text-xs text-red-600">重试生成</button>}</div>)}</div><form onSubmit={submit} className="border-t border-slate-100 dark:border-slate-800 p-4"><div className="flex gap-2"><input value={input} onChange={e => setInput(e.target.value)} placeholder="输入问题…" className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm outline-none focus:border-primary-500 dark:border-slate-700 dark:bg-slate-800" /><button disabled={sending || !input.trim()} className="rounded-xl bg-primary-600 px-4 text-white disabled:opacity-50"><Send className="w-4 h-4" /></button></div>{error && <p className="mt-2 text-xs text-red-500">{error}</p>}</form></>}</section>
  </div>;
}
