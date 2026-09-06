import { forwardRef, useId } from 'react';
import { CircleNotch } from '@phosphor-icons/react';

const cx = (...parts) => parts.filter(Boolean).join(' ');

/* -------------------------------------------------------------------------- */
/* Button                                                                     */
/* -------------------------------------------------------------------------- */

const VARIANTS = {
  primary:
    'bg-[var(--accent)] text-[var(--accent-contrast)] hover:bg-[var(--accent-hover)] border-transparent',
  secondary:
    'bg-[var(--surface-raised)] text-[var(--text)] hover:bg-[var(--surface-sunken)] border-[var(--border-strong)]',
  ghost:
    'bg-transparent text-[var(--text-muted)] hover:text-[var(--text)] hover:bg-[var(--surface-sunken)] border-transparent',
  danger:
    'bg-[var(--danger-soft)] text-[var(--danger)] hover:bg-[var(--danger)] hover:text-white border-[var(--danger)]/30',
};

const SIZES = {
  sm: 'h-8 px-3 text-[0.8125rem] gap-1.5',
  md: 'h-9 px-4 text-sm gap-2',
  lg: 'h-11 px-5 text-[0.9375rem] gap-2',
};

export const Button = forwardRef(function Button(
  { variant = 'secondary', size = 'md', loading = false, icon: Icon, children, className, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      // A loading button stays disabled so a slow analysis cannot be fired twice.
      disabled={props.disabled || loading}
      className={cx(
        'inline-flex items-center justify-center whitespace-nowrap rounded-[var(--radius-control)]',
        'border font-medium transition-colors duration-150',
        'active:translate-y-px disabled:opacity-55 disabled:pointer-events-none',
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
      {...props}
    >
      {loading ? (
        <CircleNotch size={16} weight="bold" className="animate-spin" aria-hidden />
      ) : (
        Icon && <Icon size={16} weight="bold" aria-hidden />
      )}
      {children}
    </button>
  );
});

export function IconButton({ label, icon: Icon, size = 18, className, ...props }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      className={cx(
        'inline-flex h-8 w-8 items-center justify-center rounded-[var(--radius-control)]',
        'text-[var(--text-subtle)] transition-colors duration-150',
        'hover:bg-[var(--surface-sunken)] hover:text-[var(--text)]',
        'disabled:opacity-50 disabled:pointer-events-none',
        className,
      )}
      {...props}
    >
      <Icon size={size} aria-hidden />
    </button>
  );
}

/* -------------------------------------------------------------------------- */
/* Surfaces                                                                   */
/* -------------------------------------------------------------------------- */

export function Panel({ children, className, ...props }) {
  return (
    <section className={cx('panel', className)} {...props}>
      {children}
    </section>
  );
}

export function PanelHeader({ title, description, actions, as: Heading = 'h2' }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[var(--border)] px-4 py-3">
      <div className="min-w-0">
        <Heading className="text-[0.9375rem] font-semibold text-[var(--text)]">{title}</Heading>
        {description && (
          <p className="mt-0.5 text-[0.8125rem] text-[var(--text-muted)]">{description}</p>
        )}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Form controls: label above, helper and error below (Section 4.6)           */
/* -------------------------------------------------------------------------- */

export function Field({ label, helper, error, required, children, htmlFor }) {
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={htmlFor} className="text-[0.8125rem] font-medium text-[var(--text)]">
        {label}
        {required && <span className="ml-1 text-[var(--danger)]">*</span>}
      </label>
      {children}
      {helper && !error && <p className="text-xs text-[var(--text-muted)]">{helper}</p>}
      {error && (
        <p role="alert" className="text-xs font-medium text-[var(--danger)]">
          {error}
        </p>
      )}
    </div>
  );
}

// Width is deliberately absent from the base: `w-auto` in a caller's className
// cannot reliably beat a `w-full` here, because Tailwind orders utilities by its
// own rules rather than by their position in the class string.
const controlBase =
  'rounded-[var(--radius-control)] border border-[var(--border-strong)] ' +
  'bg-[var(--surface-raised)] px-3 py-2 text-sm text-[var(--text)] ' +
  'placeholder:text-[var(--text-subtle)] transition-colors duration-150 ' +
  'hover:border-[var(--text-subtle)] disabled:opacity-60';

const controlStyles = `w-full ${controlBase}`;

export const Input = forwardRef(function Input({ className, invalid, ...props }, ref) {
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cx(controlStyles, invalid && 'border-[var(--danger)]', className)}
      {...props}
    />
  );
});

export const Textarea = forwardRef(function Textarea({ className, invalid, ...props }, ref) {
  return (
    <textarea
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cx(controlStyles, 'resize-y leading-relaxed', invalid && 'border-[var(--danger)]', className)}
      {...props}
    />
  );
});

export function Select({ className, fullWidth = false, children, ...props }) {
  return (
    <select
      className={cx(controlBase, 'cursor-pointer pr-8', fullWidth && 'w-full', className)}
      {...props}
    >
      {children}
    </select>
  );
}

export function Checkbox({ label, id, ...props }) {
  const generated = useId();
  const inputId = id || generated;
  return (
    <div className="flex items-center gap-2">
      <input
        id={inputId}
        type="checkbox"
        className="h-4 w-4 rounded-sm accent-[var(--accent)]"
        {...props}
      />
      <label htmlFor={inputId} className="text-[0.8125rem] text-[var(--text-muted)]">
        {label}
      </label>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Badges                                                                     */
/* -------------------------------------------------------------------------- */

const TONES = {
  neutral: 'bg-[var(--surface-sunken)] text-[var(--text-muted)] border-[var(--border)]',
  accent: 'bg-[var(--accent-soft)] text-[var(--accent)] border-[var(--accent)]/25',
  ok: 'bg-[var(--ok-soft)] text-[var(--ok)] border-[var(--ok)]/25',
  warn: 'bg-[var(--warn-soft)] text-[var(--warn)] border-[var(--warn)]/25',
  danger: 'bg-[var(--danger-soft)] text-[var(--danger)] border-[var(--danger)]/25',
  ai: 'bg-[var(--ai-surface)] text-[var(--ai-text)] border-[var(--ai-border)]',
};

export function Badge({ tone = 'neutral', icon: Icon, children, className }) {
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1 rounded-full border px-2 py-0.5',
        'text-[0.6875rem] font-medium',
        TONES[tone],
        className,
      )}
    >
      {Icon && <Icon size={11} weight="bold" aria-hidden />}
      {children}
    </span>
  );
}

const PROCESSING_TONE = {
  completed: { tone: 'ok', label: 'Ready' },
  processing: { tone: 'accent', label: 'Indexing' },
  queued: { tone: 'neutral', label: 'Queued' },
  running: { tone: 'accent', label: 'Indexing' },
  failed: { tone: 'danger', label: 'Failed' },
  metadata_only: { tone: 'warn', label: 'Metadata only' },
};

export function StatusBadge({ status }) {
  const entry = PROCESSING_TONE[status] ?? { tone: 'neutral', label: status };
  return <Badge tone={entry.tone}>{entry.label}</Badge>;
}

export function Spinner({ size = 16, label = 'Loading' }) {
  return (
    <CircleNotch
      size={size}
      weight="bold"
      className="animate-spin text-[var(--text-subtle)]"
      role="status"
      aria-label={label}
    />
  );
}

export { cx };
