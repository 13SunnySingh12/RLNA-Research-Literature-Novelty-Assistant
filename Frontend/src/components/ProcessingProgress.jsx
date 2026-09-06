import { ArrowClockwise, CheckCircle, WarningCircle } from '@phosphor-icons/react';

import { Button, cx } from './primitives';

/**
 * Live indexing progress for one paper.
 *
 * Shows the step the server is actually on, not a generic spinner: "Generating
 * embeddings" tells a user that a slow upload is progressing, where a spinner
 * only tells them to keep waiting (Section 23.2).
 */
export function ProcessingProgress({ status, onRetry, retrying = false, compact = false }) {
  if (!status) return null;

  if (status.status === 'failed') {
    return (
      <div
        className={cx(
          'rounded-[var(--radius-control)] border border-[var(--danger)]/30 bg-[var(--danger-soft)] px-3.5 py-3',
          compact && 'px-3 py-2.5',
        )}
      >
        <div className="flex items-start gap-2.5">
          <WarningCircle size={16} className="mt-0.5 shrink-0 text-[var(--danger)]" aria-hidden />
          <div className="min-w-0 flex-1">
            <p className="text-[0.8125rem] font-medium text-[var(--danger)]">Indexing failed</p>
            <p className="mt-0.5 text-[0.8125rem] leading-snug text-[var(--text-muted)]">
              {status.errorMessage ?? 'You can retry it.'}
            </p>
            {/* The upload itself is never lost when indexing fails. */}
            <p className="mt-1 text-xs text-[var(--text-subtle)]">
              Your PDF is still stored and can be downloaded.
            </p>
          </div>
        </div>
        {onRetry && (
          <div className="mt-2.5 pl-[26px]">
            <Button size="sm" variant="secondary" icon={ArrowClockwise} loading={retrying} onClick={onRetry}>
              Retry indexing
            </Button>
          </div>
        )}
      </div>
    );
  }

  if (status.status === 'completed') {
    return compact ? null : (
      <p className="flex items-center gap-1.5 text-[0.8125rem] text-[var(--ok)]">
        <CheckCircle size={14} weight="fill" aria-hidden />
        Indexed and searchable
      </p>
    );
  }

  const progress = Math.max(4, Math.min(status.progress ?? 0, 100));

  return (
    <div className={cx('rounded-[var(--radius-control)] bg-[var(--surface-sunken)] px-3.5 py-3', compact && 'px-3 py-2.5')}>
      <div className="flex items-baseline justify-between gap-3">
        <p className="text-[0.8125rem] font-medium text-[var(--text)]">
          {status.currentStep || 'Queued'}
        </p>
        <span className="label-mono tabular-nums">{progress}%</span>
      </div>
      <div
        className="mt-2 h-1 overflow-hidden rounded-full bg-[var(--surface-inset)]"
        role="progressbar"
        aria-valuenow={progress}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Indexing progress"
      >
        <div
          className="h-full rounded-full bg-[var(--accent)] transition-[width] duration-500 ease-out"
          style={{ width: `${progress}%` }}
        />
      </div>
      {status.attempts > 1 && (
        <p className="mt-1.5 text-xs text-[var(--text-subtle)]">Attempt {status.attempts}</p>
      )}
    </div>
  );
}
