import { useCallback, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { FilePdf, UploadSimple, WarningCircle } from '@phosphor-icons/react';

import { papersApi } from '../services/api';
import { useToast } from '../context/AppProviders';
import { Badge, Button, Select, StatusBadge, cx } from './primitives';
import { ProcessingProgress } from './ProcessingProgress';

const MAX_MB = 25;

export function ProjectSelect({ projects, value, onChange, allowAll = false, label = 'Project' }) {
  return (
    <Select
      aria-label={label}
      value={value ?? ''}
      onChange={(event) => onChange(event.target.value || null)}
      className="h-9 w-auto min-w-[12rem] py-0 text-[0.8125rem]"
    >
      {allowAll && <option value="">All projects</option>}
      {!allowAll && <option value="">Select a project</option>}
      {projects.map((project) => (
        <option key={project.id} value={project.id}>
          {project.name}
        </option>
      ))}
    </Select>
  );
}

/**
 * Upload control.
 *
 * Files are checked in the browser for the obvious cases so a user is not made
 * to wait for a round trip to learn a file is the wrong type. The server checks
 * again, by content rather than by name, because this check is a convenience
 * and not a security boundary.
 */
export function UploadDropzone({ projectId, onUploaded, disabled }) {
  const toast = useToast();
  const inputRef = useRef(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState(0);

  const send = useCallback(
    async (fileList) => {
      const files = Array.from(fileList ?? []);
      if (!files.length) return;

      const tooLarge = files.filter((file) => file.size > MAX_MB * 1024 * 1024);
      const usable = files.filter((file) => file.size <= MAX_MB * 1024 * 1024);
      tooLarge.forEach((file) =>
        toast.error(`${file.name} is larger than ${MAX_MB} MB and was not uploaded.`),
      );
      if (!usable.length) return;

      setBusy(true);
      setProgress(0);
      try {
        const outcomes = await papersApi.upload(usable, projectId, setProgress);
        const accepted = outcomes.filter((outcome) => outcome.accepted);
        outcomes
          .filter((outcome) => !outcome.accepted)
          .forEach((outcome) => toast.error(`${outcome.filename}: ${outcome.errorMessage}`));

        accepted
          .filter((outcome) => outcome.duplicateOf)
          .forEach((outcome) =>
            toast.push(
              `${outcome.filename} looks similar to "${outcome.duplicateOf.existingTitle}" already in your library.`,
              'info',
              8000,
            ),
          );

        if (accepted.length) {
          toast.success(
            `${accepted.length} paper${accepted.length === 1 ? '' : 's'} uploaded. Indexing continues in the background.`,
          );
          onUploaded?.(accepted.map((outcome) => outcome.paper));
        }
      } catch (error) {
        toast.error(error.message);
      } finally {
        setBusy(false);
        setProgress(0);
        if (inputRef.current) inputRef.current.value = '';
      }
    },
    [projectId, onUploaded, toast],
  );

  return (
    <div
      onDragOver={(event) => {
        event.preventDefault();
        if (!disabled) setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        event.preventDefault();
        setDragging(false);
        if (!disabled && !busy) send(event.dataTransfer.files);
      }}
      className={cx(
        'rounded-[var(--radius-panel)] border border-dashed px-5 py-8 text-center transition-colors duration-150',
        dragging
          ? 'border-[var(--accent)] bg-[var(--accent-soft)]'
          : 'border-[var(--border-strong)] bg-[var(--surface-sunken)]',
        disabled && 'opacity-60',
      )}
    >
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf"
        multiple
        // The input is visually hidden behind the drop zone, so it carries its
        // own name: the surrounding prose is not associated with it, and a
        // screen reader would otherwise announce an unlabelled file control.
        aria-label="Choose PDF files to upload"
        className="sr-only"
        disabled={disabled || busy}
        onChange={(event) => send(event.target.files)}
      />

      {busy ? (
        <div className="mx-auto max-w-sm">
          <p className="text-[0.875rem] font-medium text-[var(--text)]">
            Uploading {progress}%
          </p>
          <div className="mt-2 h-1 overflow-hidden rounded-full bg-[var(--surface-inset)]">
            <div
              className="h-full rounded-full bg-[var(--accent)] transition-[width] duration-300"
              style={{ width: `${Math.max(progress, 3)}%` }}
            />
          </div>
          <p className="mt-2 text-xs text-[var(--text-subtle)]">
            Indexing runs on the server, so you can leave this page once the upload finishes.
          </p>
        </div>
      ) : (
        <>
          <FilePdf size={22} className="mx-auto text-[var(--text-subtle)]" aria-hidden />
          <p className="mt-3 text-[0.875rem] font-medium text-[var(--text)]">
            Drop PDFs here, or choose files
          </p>
          <p className="mt-1 text-[0.8125rem] text-[var(--text-muted)]">
            Text-based PDFs up to {MAX_MB} MB. Scanned pages are not supported yet.
          </p>
          <div className="mt-4">
            <Button
              icon={UploadSimple}
              disabled={disabled}
              onClick={() => inputRef.current?.click()}
            >
              Choose PDFs
            </Button>
          </div>
        </>
      )}
    </div>
  );
}

/** One row in the library. */
export function PaperRow({ paper, status, onRetry, retrying, selectable, selected, onSelect }) {
  const active = status && ['queued', 'processing', 'running'].includes(status.status);
  const failed = (status?.status ?? paper.processingStatus) === 'failed';

  return (
    <li className="flex items-start gap-3 px-4 py-3.5">
      {selectable && (
        <input
          type="checkbox"
          className="mt-1 h-4 w-4 shrink-0 rounded-sm accent-[var(--accent)]"
          checked={selected}
          onChange={(event) => onSelect?.(paper.id, event.target.checked)}
          aria-label={`Select ${paper.title || 'paper'}`}
        />
      )}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <Link
            to={`/papers/${paper.id}`}
            className="truncate text-[0.9375rem] font-medium text-[var(--text)] hover:text-[var(--accent)] hover:underline"
          >
            {paper.title || 'Untitled paper'}
          </Link>
          <StatusBadge status={status?.status ?? paper.processingStatus} />
          {paper.readingStatus && paper.readingStatus !== 'to_read' && (
            <Badge tone="neutral">{paper.readingStatus === 'read' ? 'Read' : 'Reading'}</Badge>
          )}
        </div>

        <p className="mt-0.5 truncate text-[0.8125rem] text-[var(--text-muted)]">
          {[paper.authors?.slice(0, 3).join(', '), paper.publicationYear, paper.venue]
            .filter(Boolean)
            .join(' · ') || 'No metadata recovered yet'}
        </p>

        {paper.tags?.length > 0 && (
          <ul className="mt-1.5 flex flex-wrap gap-1">
            {paper.tags.map((tag) => (
              <li key={tag}>
                <Badge tone="neutral">{tag}</Badge>
              </li>
            ))}
          </ul>
        )}

        {(active || failed) && (
          <div className="mt-2.5 max-w-md">
            <ProcessingProgress
              status={status}
              onRetry={failed ? () => onRetry?.(paper.id) : undefined}
              retrying={retrying}
              compact
            />
          </div>
        )}

        {paper.processingStatus === 'metadata_only' && (
          <p className="mt-2 flex items-center gap-1.5 text-[0.8125rem] text-[var(--warn)]">
            <WarningCircle size={13} aria-hidden />
            Metadata only. Attach the PDF to index its full text.
          </p>
        )}
      </div>
    </li>
  );
}
