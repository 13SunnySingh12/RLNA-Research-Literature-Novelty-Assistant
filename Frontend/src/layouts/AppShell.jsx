import { useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import {
  Books,
  ChartBar,
  ChatCircleText,
  ClockCounterClockwise,
  Desktop,
  GitDiff,
  Lightbulb,
  List,
  MagnifyingGlass,
  Moon,
  SignOut,
  Sun,
  Target,
  X,
} from '@phosphor-icons/react';

import { useAuth, useTheme } from '../context/AppProviders';
import { IconButton, cx } from '../components/primitives';

const NAV = [
  { to: '/dashboard', label: 'Dashboard', icon: ChartBar },
  { to: '/library', label: 'Library', icon: Books },
  { to: '/search', label: 'Search', icon: MagnifyingGlass },
  { to: '/ask', label: 'Ask', icon: ChatCircleText },
  { to: '/compare', label: 'Compare', icon: GitDiff },
  { to: '/gaps', label: 'Research gaps', icon: Target },
  { to: '/novelty', label: 'Novelty check', icon: Lightbulb },
  { to: '/history', label: 'History', icon: ClockCounterClockwise },
];

const THEME_ICON = { system: Desktop, light: Sun, dark: Moon };
const THEME_LABEL = { system: 'Theme: system', light: 'Theme: light', dark: 'Theme: dark' };

export function AppShell({ children }) {
  const { user, logout } = useAuth();
  const { theme, cycle } = useTheme();
  const [navOpen, setNavOpen] = useState(false);
  const ThemeIcon = THEME_ICON[theme];

  // A tapped link on mobile should reveal the page rather than leave the menu
  // covering it. Closed from the click that caused it, not from an effect
  // watching the location.
  const closeNav = () => setNavOpen(false);

  return (
    <div className="min-h-[100dvh] bg-[var(--surface)]">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-[var(--radius-control)] focus:bg-[var(--surface-raised)] focus:px-4 focus:py-2 focus:text-sm focus:shadow"
      >
        Skip to content
      </a>

      <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-[var(--border)] bg-[var(--surface-raised)]/95 px-4 backdrop-blur-sm">
        <IconButton
          label={navOpen ? 'Close menu' : 'Open menu'}
          icon={navOpen ? X : List}
          className="lg:hidden"
          onClick={() => setNavOpen((open) => !open)}
        />

        <Link to="/dashboard" onClick={closeNav} className="flex items-center gap-2">
          <img src="/logo.svg" alt="" className="h-6 w-auto" />
          <span className="text-[0.9375rem] font-semibold tracking-tight text-[var(--text)]">
            RLNA
          </span>
        </Link>

        <div className="ml-auto flex items-center gap-1">
          <IconButton label={THEME_LABEL[theme]} icon={ThemeIcon} onClick={cycle} />
          <div className="mx-1 hidden h-5 w-px bg-[var(--border)] sm:block" />
          <span className="hidden max-w-[16rem] truncate px-1 text-[0.8125rem] text-[var(--text-muted)] sm:block">
            {user?.email}
          </span>
          <IconButton label="Sign out" icon={SignOut} onClick={logout} />
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-[1500px]">
        <nav
          aria-label="Sections"
          className={cx(
            'shrink-0 border-r border-[var(--border)] bg-[var(--surface)] px-3 py-4',
            'lg:sticky lg:top-14 lg:block lg:h-[calc(100dvh-3.5rem)] lg:w-56 lg:overflow-y-auto',
            navOpen
              ? 'fixed inset-x-0 top-14 z-20 block h-[calc(100dvh-3.5rem)] w-full overflow-y-auto'
              : 'hidden',
          )}
        >
          <ul className="flex flex-col gap-0.5">
            {NAV.map(({ to, label, icon: Icon }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  onClick={closeNav}
                  className={({ isActive }) =>
                    cx(
                      'flex items-center gap-2.5 rounded-[var(--radius-control)] px-3 py-2 text-[0.875rem] transition-colors duration-150',
                      isActive
                        ? 'bg-[var(--accent-soft)] font-medium text-[var(--accent)]'
                        : 'text-[var(--text-muted)] hover:bg-[var(--surface-sunken)] hover:text-[var(--text)]',
                    )
                  }
                >
                  <Icon size={17} aria-hidden />
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>

        <main id="main" className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
          {children}
        </main>
      </div>
    </div>
  );
}

/** Consistent page heading across every screen. */
export function PageHeader({ title, description, actions, breadcrumb }) {
  return (
    <div className="mb-6">
      {breadcrumb}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold tracking-tight text-[var(--text)]">{title}</h1>
          {description && (
            <p className="mt-1 max-w-2xl text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
              {description}
            </p>
          )}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}
