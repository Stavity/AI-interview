import { FormEvent, ReactNode, useEffect, useState } from 'react';
import { ArrowLeft, CheckCircle2, LoaderCircle, Sparkles } from 'lucide-react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '../../api/auth';
import { useAuth } from '../../auth/AuthContext';

function AuthFrame({ title, subtitle, children }: {
  title: string;
  subtitle: string;
  children: ReactNode;
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 via-indigo-50 to-sky-50 px-4 py-12 dark:from-slate-950 dark:via-slate-900 dark:to-indigo-950">
      <section className="w-full max-w-md rounded-3xl border border-white/70 bg-white/90 p-8 shadow-xl shadow-indigo-950/5 backdrop-blur dark:border-slate-700 dark:bg-slate-900/90 sm:p-10">
        <Link to="/login" className="mb-8 inline-flex items-center gap-3">
          <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-gradient-to-br from-primary-500 to-indigo-600 text-white shadow-lg shadow-primary-500/20">
            <Sparkles className="h-5 w-5" />
          </span>
          <span>
            <span className="block font-bold text-slate-900 dark:text-white">AI Interview</span>
            <span className="block text-xs text-slate-500 dark:text-slate-400">智能面试与学习助手</span>
          </span>
        </Link>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900 dark:text-white">{title}</h1>
        <p className="mb-7 mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">{subtitle}</p>
        {children}
      </section>
    </main>
  );
}

function TextField({ label, ...props }: React.InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="mb-4 block">
      <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-200">{label}</span>
      <input
        {...props}
        className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-primary-400 focus:ring-4 focus:ring-primary-500/10 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
      />
    </label>
  );
}

function SubmitButton({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <button
      type="submit"
      disabled={busy}
      className="mt-2 flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-primary-600 to-indigo-600 px-4 py-3 font-semibold text-white shadow-md shadow-primary-600/20 transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-60"
    >
      {busy && <LoaderCircle className="h-4 w-4 animate-spin" />}
      {children}
    </button>
  );
}

function FormMessage({ children, tone = 'error' }: { children: ReactNode; tone?: 'error' | 'success' }) {
  return (
    <div className={`mb-4 rounded-xl px-3.5 py-3 text-sm ${tone === 'success'
      ? 'bg-emerald-50 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300'
      : 'bg-rose-50 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300'}`}>
      {children}
    </div>
  );
}

function passwordLengthError(password: string): string | null {
  const byteLength = new TextEncoder().encode(password).length;
  if (password.length < 12) return '密码至少需要 12 个字符';
  if (byteLength > 72) return '密码过长，请控制在 72 个 UTF-8 字节以内';
  return null;
}

function useEmailActionToken(): string {
  const [token] = useState(() => new URLSearchParams(window.location.search).get('token')
    || new URLSearchParams(window.location.hash.replace(/^#/, '')).get('token')
    || '');

  useEffect(() => {
    if (token) window.history.replaceState(window.history.state, document.title, window.location.pathname);
  }, [token]);

  return token;
}

export function LoginPage() {
  const { signIn, user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showResend, setShowResend] = useState(false);
  const [searchParams] = useSearchParams();

  if (user) return <Navigate to="/history" replace />;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await signIn(email, password);
      const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
      navigate(from || '/history', { replace: true });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录失败，请稍后重试');
      setShowResend(true);
    } finally {
      setBusy(false);
    }
  };

  const resend = async () => {
    setBusy(true);
    setError('');
    try {
      await authApi.resendVerification(email);
      setNotice('如果该邮箱需要验证，验证邮件已发送，请查收');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '暂时无法发送验证邮件');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthFrame title="欢迎回来" subtitle="登录后继续你的面试练习与个性化学习。">
      {searchParams.get('passwordReset') && <FormMessage tone="success">密码已更新，请使用新密码登录。</FormMessage>}
      {notice && <FormMessage tone="success">{notice}</FormMessage>}
      {error && <FormMessage>{error}</FormMessage>}
      <form onSubmit={submit}>
        <TextField label="邮箱" type="email" autoComplete="email" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)} />
        <TextField label="密码" type="password" autoComplete="current-password" required value={password} onChange={event => setPassword(event.target.value)} />
        <div className="-mt-1 mb-5 text-right">
          <Link to="/forgot-password" className="text-sm font-medium text-primary-600 hover:text-primary-700 dark:text-primary-400">忘记密码？</Link>
        </div>
        <SubmitButton busy={busy}>登录</SubmitButton>
      </form>
      {showResend && email.trim() && (
        <button onClick={() => void resend()} disabled={busy} className="mt-4 w-full text-sm text-slate-500 underline underline-offset-4 hover:text-primary-600">
          重新发送邮箱验证
        </button>
      )}
      <p className="mt-7 text-center text-sm text-slate-500 dark:text-slate-400">
        还没有账号？ <Link to="/register" className="font-semibold text-primary-600 hover:text-primary-700 dark:text-primary-400">创建账号</Link>
      </p>
    </AuthFrame>
  );
}

export function RegisterPage() {
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [resendMessage, setResendMessage] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const passwordError = passwordLengthError(password);
    if (passwordError) return setError(passwordError);
    if (password !== confirmation) return setError('两次输入的密码不一致');
    setBusy(true);
    setError('');
    try {
      await authApi.register(email, displayName, password);
      setSubmitted(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '注册失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const resend = async () => {
    setBusy(true);
    setError('');
    setResendMessage('');
    try {
      await authApi.resendVerification(email);
      setResendMessage('如果该邮箱需要验证，验证邮件已发送，请查收');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '暂时无法发送验证邮件');
    } finally {
      setBusy(false);
    }
  };

  if (submitted) {
    return (
      <AuthFrame title="请验证你的邮箱" subtitle="我们已处理注册请求。若邮箱尚未注册，验证邮件很快会送达。">
        <div className="mb-5 flex items-start gap-3 rounded-xl bg-emerald-50 p-4 text-sm leading-6 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
          <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0" />
          <span>请打开邮件中的链接激活账号。链接 24 小时内有效；激活后即可登录。</span>
        </div>
        {resendMessage && <FormMessage tone="success">{resendMessage}</FormMessage>}
        {error && <FormMessage>{error}</FormMessage>}
        <button onClick={() => void resend()} disabled={busy} className="w-full rounded-xl border border-slate-200 px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800">
          {busy ? '正在发送…' : '重新发送验证邮件'}
        </button>
        <Link to="/login" className="mt-5 block text-center text-sm font-semibold text-primary-600 dark:text-primary-400">返回登录</Link>
      </AuthFrame>
    );
  }

  return (
    <AuthFrame title="创建你的账号" subtitle="验证邮箱后即可开始使用面试练习与学习路线。">
      {error && <FormMessage>{error}</FormMessage>}
      <form onSubmit={submit}>
        <TextField label="称呼" type="text" autoComplete="name" required maxLength={80} value={displayName} onChange={event => setDisplayName(event.target.value)} />
        <TextField label="邮箱" type="email" autoComplete="email" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)} />
        <TextField label="密码（至少 12 个字符）" type="password" autoComplete="new-password" required minLength={12} maxLength={72} value={password} onChange={event => setPassword(event.target.value)} />
        <TextField label="确认密码" type="password" autoComplete="new-password" required minLength={12} maxLength={72} value={confirmation} onChange={event => setConfirmation(event.target.value)} />
        <SubmitButton busy={busy}>注册并发送验证邮件</SubmitButton>
      </form>
      <p className="mt-7 text-center text-sm text-slate-500 dark:text-slate-400">
        已经有账号？ <Link to="/login" className="font-semibold text-primary-600 hover:text-primary-700 dark:text-primary-400">登录</Link>
      </p>
    </AuthFrame>
  );
}

export function VerifyEmailPage() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [verified, setVerified] = useState(false);
  const token = useEmailActionToken();

  const verify = async () => {
    setBusy(true);
    setError('');
    try {
      await authApi.verifyEmail(token);
      setVerified(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '验证失败，请重新申请验证邮件');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthFrame title={verified ? '邮箱已验证' : '确认邮箱地址'} subtitle={verified ? '账号已激活，现在可以登录。' : '点击下方按钮完成邮箱验证。'}>
      {error && <FormMessage>{error}</FormMessage>}
      {verified ? (
        <Link to="/login" className="block rounded-xl bg-primary-600 px-4 py-3 text-center font-semibold text-white">前往登录</Link>
      ) : token ? (
        <button onClick={() => void verify()} disabled={busy} className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary-600 px-4 py-3 font-semibold text-white disabled:opacity-60">
          {busy && <LoaderCircle className="h-4 w-4 animate-spin" />}{busy ? '正在验证…' : '确认验证邮箱'}
        </button>
      ) : (
        <p className="text-sm text-rose-600">验证链接缺少必要信息，请从邮件中重新打开。</p>
      )}
      {!verified && <Link to="/login" className="mt-5 flex items-center justify-center gap-2 text-sm text-slate-500 hover:text-primary-600"><ArrowLeft className="h-4 w-4" />返回登录</Link>}
    </AuthFrame>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await authApi.requestPasswordReset(email);
      setSubmitted(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '暂时无法处理请求，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthFrame title="找回密码" subtitle="输入注册邮箱，我们会发送密码重置链接。">
      {error && <FormMessage>{error}</FormMessage>}
      {submitted ? (
        <FormMessage tone="success">如果该邮箱对应可用账号，重置邮件已发送。请查收；链接 30 分钟内有效。</FormMessage>
      ) : (
        <form onSubmit={submit}>
          <TextField label="邮箱" type="email" autoComplete="email" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)} />
          <SubmitButton busy={busy}>发送重置链接</SubmitButton>
        </form>
      )}
      <Link to="/login" className="mt-6 block text-center text-sm font-semibold text-primary-600 dark:text-primary-400">返回登录</Link>
    </AuthFrame>
  );
}

export function ResetPasswordPage() {
  const navigate = useNavigate();
  const token = useEmailActionToken();
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const passwordError = passwordLengthError(password);
    if (passwordError) return setError(passwordError);
    if (password !== confirmation) return setError('两次输入的密码不一致');
    setBusy(true);
    setError('');
    try {
      await authApi.resetPassword(token, password);
      navigate('/login?passwordReset=1', { replace: true });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '重置失败，请重新申请密码重置链接');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthFrame title="设置新密码" subtitle="新密码至少 12 个字符，重置链接只能使用一次。">
      {!token && <FormMessage>重置链接缺少必要信息，请重新申请。</FormMessage>}
      {error && <FormMessage>{error}</FormMessage>}
      <form onSubmit={submit}>
        <TextField label="新密码" type="password" autoComplete="new-password" required minLength={12} maxLength={72} value={password} onChange={event => setPassword(event.target.value)} />
        <TextField label="确认新密码" type="password" autoComplete="new-password" required minLength={12} maxLength={72} value={confirmation} onChange={event => setConfirmation(event.target.value)} />
        <SubmitButton busy={busy}>更新密码</SubmitButton>
      </form>
      <Link to="/login" className="mt-6 block text-center text-sm font-semibold text-primary-600 dark:text-primary-400">返回登录</Link>
    </AuthFrame>
  );
}
