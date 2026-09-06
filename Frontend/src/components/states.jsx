import { ArrowClockwise, Warning, WifiSlash } from '@phosphor-icons/react';

import { Button, cx } from './primitives';

/**
 * The four states every data surface has to handle (Section 23.2).
 *
 * Skeletons mirror the shape of the content that is coming, so the page does
 * not jump when it arrives. Empty states say what to do next rather than
 * announcing that there is nothing. Errors say what happened, what to do, and
 * carry a trace id the user can quote.
 */

export function Skeleton({ className }) {
  return <div className={cx('skeleton', className)} aria-hidden />;
}

export function SkeletonText({ lines = 3, className }) {
  return (
    <div className={cx('flex flex-col gap-2', className)} aria-hidden>
      {Array.from({ length: lines }).map((_, index) => (
        <Skeleton
          key={index}
          className={cx('h-3.5', index === lines - 1 ? 'w-2/3' : 'w-full')}
        />
      ))}
    </div>
  );
}

export function SkeletonRows({ rows = 4 }) {
  return (
    <div className="divide-y divide-[var(--border)]" aria-busy="true" aria-live="polite">
      <span className="sr-only">Loading</span>
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="flex items-center gap-4 px-4 py-3.5">
          <div className="min-w-0 flex-1 space-y-2">
            <Skeleton className="h-4 w-3/5" />
            <Skeleton className="h-3 w-2/5" />
          </div>
          <Skeleton className="h-5 w-16 shrink-0" />
        </div>
      ))}
    </div>
  );
}

export function EmptyState({ icon: Icon, title, description, action, className }) {
  return (
    <div className={cx('flex flex-col items-center px-6 py-14 text-center', className)}>
      {Icon && (
        <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-full bg-[var(--surface-sunken)] text-[var(--text-subtle)]">
          <Icon size={22} aria-hidden />
        </span>
      )}
      <h3 className="text-[0.9375rem] font-semibold text-[var(--text)]">{title}</h3>
      {description && (
        <p className="mt-1.5 max-w-sm text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
          {description}
        </p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, className }) {
  const offline = error?.status === 0;
  const Icon = offline ? WifiSlash : Warning;

  return (
    <div className={cx('flex flex-col items-center px-6 py-12 text-center', className)}>
      <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-full bg-[var(--danger-soft)] text-[var(--danger)]">
        <Icon size={22} aria-hidden />
      </span>
      <h3 className="text-[0.9375rem] font-semibold text-[var(--text)]">
        {offline ? 'Could not reach the server' : 'Something went wrong'}
      </h3>
      <p className="mt-1.5 max-w-sm text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
        {error?.message ?? 'Please try again.'}
      </p>
      <div className="mt-5 flex items-center gap-3">
        {onRetry && (
          <Button variant="secondary" icon={ArrowClockwise} onClick={onRetry}>
            Try again
          </Button>
        )}
      </div>
      {error?.traceId && (
        <p className="label-mono mt-4">Reference {error.traceId}</p>
      )}
    </div>
  );
}

/** Inline error for a single action, where a whole-panel error would be wrong. */
export function InlineError({ error, onRetry }) {
  if (!error) return null;
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-3 rounded-[var(--radius-control)] border border-[var(--danger)]/30 bg-[var(--danger-soft)] px-3 py-2.5"
    >
      <Warning size={16} className="shrink-0 text-[var(--danger)]" aria-hidden />
      <p className="min-w-0 flex-1 text-[0.8125rem] text-[var(--danger)]">{error.message}</p>
      {onRetry && error.retryable && (
        <Button size="sm" variant="ghost" icon={ArrowClockwise} onClick={onRetry}>
          Retry
        </Button>
      )}
    </div>
  );
}
