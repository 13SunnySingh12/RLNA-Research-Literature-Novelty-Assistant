import { useState } from 'react';
import { CaretDown, Quotes, Sparkle } from '@phosphor-icons/react';

import { Badge, cx } from './primitives';

const CONFIDENCE_TONE = { high: 'ok', medium: 'warn', low: 'danger' };

function sectionLabel(section) {
  return section ? section.replace(/_/g, ' ') : 'unlabelled';
}

/**
 * Wrapper marking content as model-generated.
 *
 * AI output and text quoted from a paper use deliberately different surfaces,
 * because a reader has to be able to tell at a glance what a paper said from
 * what a model said about it (Section 23.2).
 */
export function AiContent({ title, children, actions, className }) {
  return (
    <section
      className={cx(
        'rounded-[var(--radius-panel)] border border-[var(--ai-border)] bg-[var(--ai-surface)]',
        className,
      )}
    >
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-[var(--ai-border)] px-4 py-2.5">
        <span className="flex items-center gap-1.5 text-[0.75rem] font-medium text-[var(--ai-text)]">
          <Sparkle size={13} weight="fill" aria-hidden />
          {title ?? 'AI generated'}
        </span>
        {actions}
      </header>
      <div className="px-4 py-4">{children}</div>
    </section>
  );
}

/**
 * Confidence, evidence base and limitations, shown as part of the result.
 *
 * Never behind a tooltip and never collapsed: an assessment read without its
 * limitations is an assessment misread (Sections 14.5 and 15.4).
 */
export function ConfidenceNotice({ confidence, corpusSize, limitations, kind }) {
  const tone = CONFIDENCE_TONE[confidence] ?? 'neutral';

  return (
    <aside className="rounded-[var(--radius-control)] border border-[var(--border)] bg-[var(--surface-sunken)] px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        {confidence && (
          <Badge tone={tone}>Confidence: {confidence}</Badge>
        )}
        {typeof corpusSize === 'number' && corpusSize > 0 && (
          <Badge tone="neutral">
            Based on {corpusSize} paper{corpusSize === 1 ? '' : 's'}
          </Badge>
        )}
      </div>
      {limitations && (
        <p className="mt-2.5 text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
          {limitations}
        </p>
      )}
      {kind === 'novelty' && (
        <p className="mt-2.5 border-t border-[var(--border)] pt-2.5 text-[0.8125rem] font-medium leading-relaxed text-[var(--text)]">
          This is not a proof of novelty. The absence of similar work in this corpus does not mean
          none exists.
        </p>
      )}
    </aside>
  );
}

/** One retrieved chunk, quoted verbatim, with where it came from. */
export function EvidenceChunk({ hit, compact = false }) {
  const [expanded, setExpanded] = useState(false);
  const long = hit.content && hit.content.length > 320;
  const shown = expanded || !long ? hit.content : `${hit.content.slice(0, 320).trimEnd()}...`;

  return (
    <article className="rounded-[var(--radius-control)] border border-[var(--evidence-border)] bg-[var(--evidence-surface)] px-3.5 py-3">
      <header className="mb-2 flex flex-wrap items-center gap-x-2 gap-y-1">
        <Quotes size={13} weight="fill" className="text-[var(--text-subtle)]" aria-hidden />
        <span className="truncate text-[0.8125rem] font-medium text-[var(--text)]">
          {hit.paperTitle || 'Untitled paper'}
        </span>
        <span className="label-mono">{sectionLabel(hit.sectionType)}</span>
        {!compact && typeof hit.score === 'number' && (
          <span className="label-mono ml-auto">match {hit.score.toFixed(2)}</span>
        )}
      </header>
      <p className="whitespace-pre-wrap text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
        {shown}
      </p>
      {long && (
        <button
          type="button"
          onClick={() => setExpanded((value) => !value)}
          className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-[var(--accent)] hover:underline"
        >
          {expanded ? 'Show less' : 'Show full passage'}
          <CaretDown
            size={11}
            weight="bold"
            className={cx('transition-transform duration-150', expanded && 'rotate-180')}
            aria-hidden
          />
        </button>
      )}
    </article>
  );
}

/**
 * Collapsible evidence for a result. Collapsed by default so an answer reads
 * cleanly, but always exactly one click from the passages behind it.
 */
export function EvidenceDisclosure({ hits, label = 'Evidence', defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen);
  if (!hits?.length) return null;

  return (
    <div className="mt-3">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="inline-flex items-center gap-1.5 text-[0.8125rem] font-medium text-[var(--accent)] hover:underline"
      >
        <CaretDown
          size={12}
          weight="bold"
          className={cx('transition-transform duration-150', open && 'rotate-180')}
          aria-hidden
        />
        {label} ({hits.length})
      </button>
      {open && (
        <div className="mt-2.5 flex flex-col gap-2">
          {hits.map((hit, index) => (
            <EvidenceChunk key={hit.chunkId ?? index} hit={hit} compact />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Shown instead of an answer when retrieval found too little to work with.
 * Refusing is a correct outcome, so it is presented as one rather than as a
 * failure (Section 13.3 rule 7).
 */
export function InsufficientEvidence({ message, action }) {
  return (
    <div className="rounded-[var(--radius-control)] border border-[var(--warn)]/30 bg-[var(--warn-soft)] px-4 py-3.5">
      <h4 className="text-[0.875rem] font-semibold text-[var(--warn)]">Not enough evidence</h4>
      <p className="mt-1 text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
        {message ??
          'There is not enough in your library to answer this. Try adding related papers.'}
      </p>
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}
