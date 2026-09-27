import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { authApi, CurrentUser } from '../api/auth';
import { getErrorStatus } from '../api/request';

interface AuthContextValue {
  user: CurrentUser | null;
  loading: boolean;
  error: string | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    try {
      const currentUser = await authApi.currentUser();
      setUser(currentUser);
      setError(null);
    } catch (reason) {
      if (getErrorStatus(reason) === 401) {
        setUser(null);
        setError(null);
      } else {
        setError(reason instanceof Error ? reason.message : '无法确认登录状态');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    user,
    loading,
    error,
    async signIn(email, password) {
      const authenticatedUser = await authApi.login(email, password);
      setUser(authenticatedUser);
      setError(null);
    },
    async signOut() {
      await authApi.logout();
      setUser(null);
    },
    refresh,
  }), [user, loading, error]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
