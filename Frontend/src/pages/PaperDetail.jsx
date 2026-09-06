import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  ArrowSquareOut,
  ChatCircleText,
  FilePdf,
  Sparkle,
  Tag,
  Trash,
  WarningCircle,
} from '@phosphor-icons/react';

import { analysisApi, papersApi } from '../services/api';
import { useAction, useAsync } from '../hooks/useAsync';
import { useProcessingStatus, isActiveStatus } from '../hooks/useProcessingStatus';
import { useToast } from '../context/AppProviders';
import { PageHeader } from '../layouts/AppShell';
import { AnalysisResult } from '../components/AnalysisResult';
import { ProcessingProgress } from '../components/ProcessingProgress';
import {
  Badge,
  Button,
  Field,
  Input,
  Panel,
  PanelHeader,
  Select,
  StatusBadge,
  Textarea,
} from '../components/primitives';
import { ErrorState, InlineError, SkeletonText } from '../components/states';
import { InsufficientEvidence } from '../components/evidence';

const READING_STATUSES = [
  ['to_read', 'To read'],
  ['reading', 'Reading'],
  ['read', 'Read'],
];

function Meta({ label, children }) {
  if (!children) return null;
  return (
    <div className="py-2.5">
      <dt className="label-mono">{label}</dt>
      <dd className="mt-1 text-[0.875rem] leading-relaxed text-[var(--text)]">{children}</dd>
    </div>
  );
}

export default function PaperDetail() {
  const { paperId } = useParams();
  const navigate = useNavigate();
  const toast = useToast();

  const detail = useAsync(() => papersApi.get(paperId), [paperId]);
  const duplicate = useAsync(() => papersApi.duplicate(paperId), [paperId]);

  const [summary, setSummary] = useState(null);
  const [concepts, setConcepts] = useState(null);
  const [tagInput, setTagInput] = useState('');
  const [notesDraft, setNotesDraft] = useState(null);

  const paper = detail.data?.paper;
  const active = paper && isActiveStatus(paper.processingStatus);
  const statuses = useProcessingStatus(active ? [paperId] : [], {
    onSettled: () => detail.reload(),
  });

  const generateSummary = useAction(async (refresh = false) => {
    const result = await analysisApi.summary(paperId, refresh);
    setSummary(result);
  });

  const generateConcepts = useAction(async (refresh = false) => {
    const result = await analysisApi.concepts(paperId, refresh);
    setConcepts(result);
  });

  const patch = useAction(async (body) => {
    const updated = await papersApi.update(paperId, body);
    detail.setData((current) => ({ ...current, paper: updated }));
    return updated;
  });

  const openPdf = useAction(async () => {
    const { url } = await papersApi.fileUrl(paperId);
    window.open(url, '_blank', 'noopener,noreferrer');
  });

  const remove = useAction(async () => {
    await papersApi.remove(paperId);
    toast.success('Paper deleted, along with its index and stored file.');
    navigate('/library');
  });

  const retry = useAction(async () => {
    await papersApi.reprocess(paperId);
    toast.info('Indexing restarted.');
    detail.reload();
  });

  if (detail.loading) {
    return (
      <div className="panel p-6">
        <SkeletonText lines={4} />
      </div>
    );
  }
  if (detail.error) {
    return <ErrorState error={detail.error} onRetry={detail.reload} />;
  }

  const { sections, concepts: storedConcepts, chunkCount } = detail.data;
  const indexed = paper.processingStatus === 'completed';
  const notes = notesDraft ?? paper.notes ?? '';

  return (
    <>
      <PageHeader
        breadcrumb={
          <Link
            to="/library"
            className="mb-3 inline-flex items-center gap-1.5 text-[0.8125rem] text-[var(--text-muted)] hover:text-[var(--accent)]"
          >
            <ArrowLeft size={13} aria-hidden />
            Library
          </Link>
        }
        title={paper.title || 'Untitled paper'}
        description={paper.authors?.join(', ')}
        actions={
          <>
            {paper.hasFile && (
              <Button icon={FilePdf} loading={openPdf.loading} onClick={openPdf.run}>
                Open PDF
              </Button>
            )}
            {indexed && (
              <Button
                variant="primary"
                icon={ChatCircleText}
                onClick={() => navigate(`/ask?paperId=${paperId}`)}
              >
                Ask about this paper
              </Button>
            )}
          </>
        }
      />

      {openPdf.error && (
        <div className="mb-4">
          <InlineError error={openPdf.error} onRetry={openPdf.run} />
        </div>
      )}

      {duplicate.data && (
        <div className="mb-6 flex flex-wrap items-center gap-3 rounded-[var(--radius-control)] border border-[var(--warn)]/30 bg-[var(--warn-soft)] px-4 py-3">
          <WarningCircle size={17} className="shrink-0 text-[var(--warn)]" aria-hidden />
          <p className="min-w-0 flex-1 text-[0.875rem] text-[var(--text)]">
            {duplicate.data.reason}{' '}
            <Link
              to={`/papers/${duplicate.data.existingPaperId}`}
              className="font-medium text-[var(--accent)] underline"
            >
              {duplicate.data.existingTitle}
            </Link>
            {typeof duplicate.data.similarity === 'number' &&
              ` (similarity ${duplicate.data.similarity.toFixed(2)})`}
          </p>
        </div>
      )}

      {(active || paper.processingStatus === 'failed') && (
        <div className="mb-6 max-w-lg">
          <ProcessingProgress
            status={statuses[paperId] ?? { status: paper.processingStatus, errorMessage: paper.processingError, progress: 0 }}
            onRetry={paper.processingStatus === 'failed' ? retry.run : undefined}
            retrying={retry.loading}
          />
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[1.5fr_1fr]">
        <div className="flex flex-col gap-6">
          {indexed && (
            <Panel>
              <PanelHeader
                title="AI summary"
                description="Generated once and reused. Regenerating is explicit."
                as="h2"
                actions={
                  <Button
                    size="sm"
                    variant={summary ? 'ghost' : 'primary'}
                    icon={Sparkle}
                    loading={generateSummary.loading}
                    onClick={() => generateSummary.run(Boolean(summary))}
                  >
                    {summary ? 'Regenerate' : 'Generate summary'}
                  </Button>
                }
              />
              <div className="p-4">
                {generateSummary.error?.code === 'INSUFFICIENT_EVIDENCE' ? (
                  <InsufficientEvidence message={generateSummary.error.message} />
                ) : generateSummary.error ? (
                  <InlineError error={generateSummary.error} onRetry={() => generateSummary.run()} />
                ) : summary ? (
                  <AnalysisResult analysis={summary} />
                ) : (
                  <p className="text-[0.875rem] text-[var(--text-muted)]">
                    A structured summary covering the research problem, approach, findings and the
                    limitations the authors reported.
                  </p>
                )}
              </div>
            </Panel>
          )}

          <Panel>
            <PanelHeader
              title="Abstract"
              as="h2"
            />
            <div className="p-4">
              {paper.abstractText ? (
                <p className="text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
                  {paper.abstractText}
                </p>
              ) : (
                <p className="text-[0.875rem] text-[var(--text-subtle)]">
                  No abstract was recovered from this PDF.
                </p>
              )}
            </div>
          </Panel>

          <Panel>
            <PanelHeader
              title="Detected sections"
              description={`${chunkCount} indexed chunk${chunkCount === 1 ? '' : 's'}`}
              as="h2"
            />
            {sections.length ? (
              <ul className="divide-y divide-[var(--border)]">
                {sections.map((sectionItem) => (
                  <li
                    key={sectionItem.id}
                    className="flex items-center justify-between gap-3 px-4 py-2.5"
                  >
                    <div className="min-w-0">
                      <p className="truncate text-[0.875rem] text-[var(--text)]">
                        {sectionItem.heading || sectionItem.sectionType}
                      </p>
                      <p className="label-mono mt-0.5">
                        {sectionItem.sectionType.replace(/_/g, ' ')}
                      </p>
                    </div>
                    <span className="label-mono shrink-0 tabular-nums">
                      {sectionItem.characterCount.toLocaleString()} chars
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="px-4 py-6 text-center text-[0.8125rem] text-[var(--text-muted)]">
                No sections detected yet.
              </p>
            )}
          </Panel>
        </div>

        <div className="flex flex-col gap-6">
          <Panel>
            <PanelHeader title="Details" as="h2" />
            <dl className="divide-y divide-[var(--border)] px-4">
              <Meta label="Status">
                <StatusBadge status={paper.processingStatus} />
              </Meta>
              <Meta label="Year">{paper.publicationYear}</Meta>
              <Meta label="Venue">{paper.venue}</Meta>
              <Meta label="DOI">
                {paper.doi && (
                  <a
                    href={`https://doi.org/${paper.doi}`}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1 text-[var(--accent)] hover:underline"
                  >
                    {paper.doi}
                    <ArrowSquareOut size={12} aria-hidden />
                  </a>
                )}
              </Meta>
              <Meta label="File size">
                {paper.fileSize ? `${(paper.fileSize / 1024 / 1024).toFixed(1)} MB` : null}
              </Meta>
            </dl>
          </Panel>

          <Panel>
            <PanelHeader title="Reading" as="h2" />
            <div className="flex flex-col gap-4 p-4">
              <Field label="Reading status" htmlFor="reading-status">
                <Select
                  id="reading-status"
                  fullWidth
                  value={paper.readingStatus}
                  onChange={(event) => patch.run({ readingStatus: event.target.value })}
                >
                  {READING_STATUSES.map(([value, label]) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </Select>
              </Field>

              <Field label="Tags" htmlFor="tag-input" helper="Press Enter to add.">
                <div className="flex flex-wrap gap-1.5">
                  {paper.tags?.map((tag) => (
                    <button
                      key={tag}
                      type="button"
                      onClick={() =>
                        patch.run({ tags: paper.tags.filter((value) => value !== tag) })
                      }
                      className="inline-flex items-center gap-1 rounded-full border border-[var(--border)] bg-[var(--surface-sunken)] px-2 py-0.5 text-[0.6875rem] text-[var(--text-muted)] hover:border-[var(--danger)] hover:text-[var(--danger)]"
                      aria-label={`Remove tag ${tag}`}
                    >
                      <Tag size={10} aria-hidden />
                      {tag}
                    </button>
                  ))}
                </div>
                <Input
                  id="tag-input"
                  value={tagInput}
                  onChange={(event) => setTagInput(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && tagInput.trim()) {
                      event.preventDefault();
                      const next = [...new Set([...(paper.tags ?? []), tagInput.trim()])];
                      patch.run({ tags: next });
                      setTagInput('');
                    }
                  }}
                  placeholder="Add a tag"
                  maxLength={60}
                />
              </Field>

              <Field label="Notes" htmlFor="notes">
                <Textarea
                  id="notes"
                  rows={5}
                  value={notes}
                  onChange={(event) => setNotesDraft(event.target.value)}
                  placeholder="Your own notes on this paper."
                />
                {notesDraft !== null && notesDraft !== (paper.notes ?? '') && (
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="primary"
                      loading={patch.loading}
                      onClick={async () => {
                        await patch.run({ notes: notesDraft });
                        setNotesDraft(null);
                      }}
                    >
                      Save notes
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => setNotesDraft(null)}>
                      Discard
                    </Button>
                  </div>
                )}
              </Field>

              {patch.error && <InlineError error={patch.error} />}
            </div>
          </Panel>

          {indexed && (
            <Panel>
              <PanelHeader
                title="Concepts"
                as="h2"
                actions={
                  <Button
                    size="sm"
                    variant="ghost"
                    icon={Sparkle}
                    loading={generateConcepts.loading}
                    onClick={() => generateConcepts.run(Boolean(concepts))}
                  >
                    {storedConcepts.length || concepts ? 'Refresh' : 'Extract'}
                  </Button>
                }
              />
              <div className="p-4">
                {generateConcepts.error && (
                  <InlineError error={generateConcepts.error} onRetry={() => generateConcepts.run()} />
                )}
                {storedConcepts.length > 0 ? (
                  <ul className="flex flex-wrap gap-1.5">
                    {storedConcepts.map((concept) => (
                      <li key={`${concept.concept}-${concept.conceptType}`}>
                        <Badge tone={concept.conceptType === 'method' ? 'accent' : 'neutral'}>
                          {concept.concept}
                        </Badge>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-[0.8125rem] text-[var(--text-muted)]">
                    Keywords, methods, datasets and metrics named in the paper.
                  </p>
                )}
              </div>
            </Panel>
          )}

          <Panel>
            <PanelHeader title="Danger zone" as="h2" />
            <div className="p-4">
              <Button
                variant="danger"
                icon={Trash}
                loading={remove.loading}
                onClick={() => {
                  if (
                    window.confirm(
                      'Delete this paper? Its index, notes and stored PDF are removed permanently.',
                    )
                  ) {
                    remove.run();
                  }
                }}
              >
                Delete paper
              </Button>
              {remove.error && (
                <div className="mt-3">
                  <InlineError error={remove.error} />
                </div>
              )}
            </div>
          </Panel>
        </div>
      </div>
    </>
  );
}
