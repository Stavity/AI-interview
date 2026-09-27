import request, { clearCsrfToken } from './request';

export interface CurrentUser {
  publicId: string;
  email: string;
  displayName: string;
  role: 'USER' | 'ADMIN';
}

export const authApi = {
  currentUser: () => request.get<CurrentUser>('/api/auth/me'),

  async login(email: string, password: string): Promise<CurrentUser> {
    const user = await request.post<CurrentUser>('/api/auth/login', { email, password });
    clearCsrfToken();
    return user;
  },

  register: (email: string, displayName: string, password: string) =>
    request.post<void>('/api/auth/register', { email, displayName, password }),

  verifyEmail: (token: string) =>
    request.post<void>('/api/auth/email/verify', { token }),

  resendVerification: (email: string) =>
    request.post<void>('/api/auth/email/resend', { email }),

  requestPasswordReset: (email: string) =>
    request.post<void>('/api/auth/password/forgot', { email }),

  resetPassword: (token: string, newPassword: string) =>
    request.post<void>('/api/auth/password/reset', { token, newPassword }),

  async logout(): Promise<void> {
    await request.post<void>('/api/auth/logout');
    clearCsrfToken();
  },
};
