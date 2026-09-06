import { forwardRef, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { Eye, EyeSlash, GithubLogo, GoogleLogo } from '@phosphor-icons/react';

import { PASSWORD_MIN_LENGTH } from '../services/authClient';
import { Button, Input, cx } from './primitives';

/* -------------------------------------------------------------------------- */
/* Page shell                                                                 */
/* -------------------------------------------------------------------------- */

export function AuthCard({ title, description, children, footer }) {
  return (
    <div className="flex min-h-[100dvh] flex-col bg-[var(--surface)]">
      <header className="mx-auto flex h-16 w-full max-w-[1200px] items-center gap-3 px-5">
        <Link to="/" className="flex items-center gap-3 rounded-[var(--radius-control)]">
          <img src="/logo.svg" alt="" className="h-7 w-auto" />
          <span className="text-[0.9375rem] font-semibold tracking-tight text-[var(--text)]">
            RLNA
          </span>
        </Link>
      </header>

      <main className="flex flex-1 items-start justify-center px-5 pb-16 pt-6 sm:items-center sm:pt-0">
        <div className="w-full max-w-[26rem]">
          <h1 className="text-[1.625rem] font-semibold leading-tight tracking-tight text-[var(--text)]">
            {title}
          </h1>
          {description && (
            <p className="mt-2 text-[0.9375rem] leading-relaxed text-[var(--text-muted)]">
              {description}
            </p>
          )}
          <div className="mt-7">{children}</div>
          {footer && (
            <p className="mt-6 text-center text-[0.8125rem] text-[var(--text-muted)]">{footer}</p>
          )}
        </div>
      </main>
    </div>
  );
}

export function AuthLink({ to, children }) {
  return (
    <Link
      to={to}
      className="rounded-sm font-medium text-[var(--accent)] underline-offset-2 hover:underline"
    >
      {children}
    </Link>
  );
}

/* -------------------------------------------------------------------------- */
/* Social                                                                     */
/* -------------------------------------------------------------------------- */

export function SocialButtons({ pending, onSelect }) {
  return (
    <div className="flex flex-col gap-3">
      <Button
        type="button"
        size="lg"
        variant="secondary"
        icon={GoogleLogo}
        loading={pending === 'google'}
        disabled={Boolean(pending)}
        onClick={() => onSelect('google')}
        className="w-full"
      >
        Continue with Google
      </Button>
      <Button
        type="button"
        size="lg"
        variant="secondary"
        icon={GithubLogo}
        loading={pending === 'github'}
        disabled={Boolean(pending)}
        onClick={() => onSelect('github')}
        className="w-full"
      >
        Continue with GitHub
      </Button>
    </div>
  );
}

export function OrDivider({ label = 'or' }) {
  return (
    <div className="my-6 flex items-center gap-3" aria-hidden>
      <span className="h-px flex-1 bg-[var(--border)]" />
      <span className="text-xs uppercase tracking-wide text-[var(--text-subtle)]">{label}</span>
      <span className="h-px flex-1 bg-[var(--border)]" />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Password input                                                             */
/* -------------------------------------------------------------------------- */

export const PasswordInput = forwardRef(function PasswordInput(
  { id, invalid, autoComplete = 'current-password', ...props },
  ref,
) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <Input
        ref={ref}
        id={id}
        type={visible ? 'text' : 'password'}
        autoComplete={autoComplete}
        invalid={invalid}
        className="pr-11"
        {...props}
      />
      <button
        type="button"
        // Excluded from the tab order: it is a convenience, and stopping between
        // the password field and the submit button on every form is worse for
        // keyboard users than reaching it deliberately.
        tabIndex={-1}
        onClick={() => setVisible((shown) => !shown)}
        aria-label={visible ? 'Hide password' : 'Show password'}
        aria-pressed={visible}
        className={cx(
          'absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-r-[var(--radius-control)]',
          'text-[var(--text-subtle)] transition-colors hover:text-[var(--text)]',
        )}
      >
        {visible ? <EyeSlash size={17} aria-hidden /> : <Eye size={17} aria-hidden />}
      </button>
    </div>
  );
});

/* -------------------------------------------------------------------------- */
/* Password strength                                                          */
/* -------------------------------------------------------------------------- */

// Substrings that make a password guessable no matter how it scores on length
// and character variety. Kept short and specific to this product rather than
// pulling in a dictionary package for a hint that is advisory anyway.
const WEAK_PATTERNS = [/password/i, /qwerty/i, /12345/, /letmein/i, /rlna/i, /admin/i];

/**
 * Scores a password 0-4 for display only.
 *
 * The server enforces the real rule (a minimum length). This exists to steer
 * people away from passwords that pass that rule and are still trivial, so it
 * deliberately rewards length far more than punctuation: a long ordinary
 * phrase beats a short one with a `$` in it.
 */
export function scorePassword(password) {
  if (!password) return { score: 0, label: '', hint: '' };
  if (password.length < PASSWORD_MIN_LENGTH) {
    return {
      score: 0,
      label: 'Too short',
      hint: `Use at least ${PASSWORD_MIN_LENGTH} characters.`,
    };
  }
  if (WEAK_PATTERNS.some((pattern) => pattern.test(password))) {
    return { score: 1, label: 'Weak', hint: 'Avoid common words and the site name.' };
  }

  let score = 1;
  if (password.length >= 12) score += 1;
  if (password.length >= 16) score += 1;

  const classes = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((re) => re.test(password)).length;
  if (classes >= 3) score += 1;

  score = Math.min(score, 4);
  const label = ['', 'Weak', 'Fair', 'Good', 'Strong'][score];
  const hint =
    score < 3 ? 'Longer is stronger — a few unrelated words work well.' : 'Looks good.';
  return { score, label, hint };
}

const STRENGTH_TONE = [
  'bg-[var(--border-strong)]',
  'bg-[var(--danger)]',
  'bg-[var(--warn)]',
  'bg-[var(--warn)]',
  'bg-[var(--ok)]',
];

export function PasswordStrength({ password }) {
  const id = useId();
  const { score, label, hint } = scorePassword(password);
  if (!password) return null;

  return (
    <div className="flex flex-col gap-1.5" aria-describedby={id}>
      <div className="flex gap-1" aria-hidden>
        {[1, 2, 3, 4].map((step) => (
          <span
            key={step}
            className={cx(
              'h-1 flex-1 rounded-full transition-colors duration-200',
              step <= score ? STRENGTH_TONE[score] : 'bg-[var(--border)]',
            )}
          />
        ))}
      </div>
      {/* Announced politely so a screen-reader user hears the rating change
          without it interrupting what they are typing. */}
      <p id={id} aria-live="polite" className="text-xs text-[var(--text-muted)]">
        <span className="font-medium text-[var(--text)]">{label}</span>
        {hint && ` — ${hint}`}
      </p>
    </div>
  );
}
