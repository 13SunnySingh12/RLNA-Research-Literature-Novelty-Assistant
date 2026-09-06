import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { CheckCircle, Info, Warning, X } from '@phosphor-icons/react';

import { authApi } from '../services/api';
import { endSession, getAccessToken } from '../services/authClient';
import { IconButton, cx } from '../components/primitives';

/* -------------------------------------------------------------------------- */
/* Theme                                                                      */
/* -------------------------------------------------------------------------- */

const ThemeContext = createContext(null);
const THEME_KEY = 'rlna.theme';

export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(() => localStorage.getItem(THEME_KEY) || 'system');

  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'system') {
      root.removeAttribute('data-theme');
      localStorage.removeItem(THEME_KEY);
    } else {
      root.setAttribute('data-theme', theme);
      localStorage.setItem(THEME_KEY, theme);
    }
  }, [theme]);

  const value = useMemo(
    () => ({
      theme,
      setTheme,
      cycle: () =>
        setTheme((current) =>
          current === 'system' ? 'light' : current === 'light' ? 'dark' : 'system',
        ),
    }),
    [theme],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export const useTheme = () => useContext(ThemeContext);

/* -------------------------------------------------------------------------- */
/* Toasts                                                                     */
/* -------------------------------------------------------------------------- */

const ToastContext = createContext(null);

const TOAST_ICON = { success: CheckCircle, error: Warning, info: Info };
const TOAST_TONE = {
  success: 'border-[var(--ok)]/30 bg-[var(--ok-soft)] text-[var(--ok)]',
  error: 'border-[var(--danger)]/30 bg-[var(--danger-soft)] text-[var(--danger)]',
  info: 'border-[var(--border)] bg-[var(--surface-raised)] text-[var(--text)]',
};

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const timers = useRef(new Map());

  const dismiss = useCallback((id) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const push = useCallback(
    (message, tone = 'info', duration = 5000) => {
      const id = crypto.randomUUID();
      setToasts((current) => [...current, { id, message, tone }]);
      // Errors stay until dismissed; a message the user missed is a message
      // that never happened.
      if (tone !== 'error') {
        timers.current.set(id, setTimeout(() => dismiss(id), duration));
      }
      return id;
    },
    [dismiss],
  );

  useEffect(() => {
    const active = timers.current;
    return () => active.forEach(clearTimeout);
  }, []);

  const value = useMemo(
    () => ({
      push,
      dismiss,
      success: (message) => push(message, 'success'),
      error: (message) => push(message, 'error'),
      info: (message) => push(message, 'info'),
    }),
    [push, dismiss],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2"
        role="region"
        aria-label="Notifications"
      >
        {toasts.map((toast) => {
          const Icon = TOAST_ICON[toast.tone];
          return (
            <div
              key={toast.id}
              role={toast.tone === 'error' ? 'alert' : 'status'}
              className={cx(
                'pointer-events-auto flex items-start gap-2.5 rounded-[var(--radius-control)] border px-3.5 py-3 shadow-sm',
                TOAST_TONE[toast.tone],
              )}
            >
              <Icon size={16} weight="fill" className="mt-0.5 shrink-0" aria-hidden />
              <p className="min-w-0 flex-1 text-[0.8125rem] leading-snug">{toast.message}</p>
              <IconButton
                label="Dismiss"
                icon={X}
                size={14}
                className="-mr-1 -mt-1 h-6 w-6"
                onClick={() => dismiss(toast.id)}
              />
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

/* -------------------------------------------------------------------------- */
/* Auth                                                                       */
/* -------------------------------------------------------------------------- */

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [state, setState] = useState({ status: 'loading', user: null });

  const load = useCallback(async () => {
    try {
      const token = await getAccessToken({ force: true });
      if (!token) {
        setState({ status: 'anonymous', user: null });
        return;
      }
      // The API is the authority on who the user is: it provisions the
      // application record and returns it.
      const user = await authApi.session();
      setState({ status: 'authenticated', user });
    } catch {
      setState({ status: 'anonymous', user: null });
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const logout = useCallback(async () => {
    // Told to the API first so it can record the event, then ended at the
    // identity provider, which is what actually invalidates the session.
    await authApi.logout().catch(() => {});
    await endSession();
    setState({ status: 'anonymous', user: null });
    window.location.assign('/');
  }, []);

  const value = useMemo(
    () => ({ ...state, refresh: load, logout, isAuthenticated: state.status === 'authenticated' }),
    [state, load, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
