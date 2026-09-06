import { useState } from 'react';
import { MagnifyingGlass } from '@phosphor-icons/react';

import { academicApi, projectsApi, searchApi } from '../services/api';
import { useAction, useAsync } from '../hooks/useAsync';
import { useToast } from '../context/AppProviders';
import { PageHeader } from '../layouts/AppShell';
import { ProjectSelect } from '../components/library';
import { EvidenceChunk } from '../components/evidence';
import { Badge, Button, Input, Panel, PanelHeader, Select, cx } from '../components/primitives';
import { EmptyState, InlineError, SkeletonRows } from '../components/states';

const MODES = [
  ['semantic', 'Meaning'],
  ['keyword', 'Exact words'],
  ['academic', 'Academic APIs'],
];

export default function Search() {
  const toast = useToast();
  const projects = useAsync(() => projectsApi.list(), []);

  const [mode, setMode] = useState('semantic');
  const [query, setQuery] = useState('');
  const [projectId, setProjectId] = useState(null);
  const [results, setResults] = useState(null);
  const [academic, setAcademic] = useState(null);
  const [importing, setImporting] = useState(null);

  const search = useAction(async () => {
    const trimmed = query.trim();
    if (!trimmed) return;

    if (mode === 'academic') {
      setResults(null);
      setAcademic(await academicApi.search({ q: trimmed, limit: 12 }));
      return;
    }
    setAcademic(null);
    setResults(
      mode === 'semantic'
        ? await searchApi.semantic({ query: trimmed, projectId: projectId || undefined, topK: 12 })
        : await searchApi.keyword({ q: trimmed, projectId: projectId || undefined, limit: 25 }),
    );
  });

  const importPaper = async (record) => {
    if (!projectId) {
      toast.error('Choose a project to import into first.');
      return;
    }
    setImporting(record.externalId);
    try {
      await academicApi.import({
        projectId,
        externalId: record.externalId,
        title: record.title,
        authors: record.authors,
        abstractText: record.abstractText,
        year: record.year,
        venue: record.venue,
        doi: record.doi,
      });
      toast.success(`"${record.title}" imported as a metadata-only record.`);
    } catch (error) {
      toast.error(error.message);
    } finally {
      setImporting(null);
    }
  };

  return (
    <>
      <PageHeader
        title="Search"
        description="Search your own indexed papers by meaning or by exact words, or look outward to free academic APIs."
      />

      <Panel className="mb-6">
        <form
          className="flex flex-col gap-3 p-4"
          onSubmit={(event) => {
            event.preventDefault();
            search.run();
          }}
        >
          <div
            role="radiogroup"
            aria-label="Search mode"
            className="inline-flex w-fit rounded-[var(--radius-control)] border border-[var(--border-strong)] p-0.5"
          >
            {MODES.map(([value, label]) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={mode === value}
                onClick={() => setMode(value)}
                className={cx(
                  'rounded-[calc(var(--radius-control)-2px)] px-3 py-1.5 text-[0.8125rem] transition-colors duration-150',
                  mode === value
                    ? 'bg-[var(--accent)] font-medium text-[var(--accent-contrast)]'
                    : 'text-[var(--text-muted)] hover:text-[var(--text)]',
                )}
              >
                {label}
              </button>
            ))}
          </div>

          <div className="flex flex-wrap gap-2">
            <div className="relative min-w-[16rem] flex-1">
              <MagnifyingGlass
                size={15}
                className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-subtle)]"
                aria-hidden
              />
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={
                  mode === 'semantic'
                    ? 'How do they handle non-IID data?'
                    : mode === 'keyword'
                      ? 'attention mechanism'
                      : 'retrieval augmented generation'
                }
                aria-label="Search query"
                className="pl-9"
                maxLength={2000}
              />
            </div>
            {mode !== 'academic' && projects.data?.length > 0 && (
              <ProjectSelect
                projects={projects.data}
                value={projectId}
                onChange={setProjectId}
                allowAll
              />
            )}
            {mode === 'academic' && projects.data?.length > 0 && (
              <Select
                aria-label="Import into project"
                value={projectId ?? ''}
                onChange={(event) => setProjectId(event.target.value || null)}
                className="h-9 w-auto min-w-[12rem] py-0 text-[0.8125rem]"
              >
                <option value="">Import into...</option>
                {projects.data.map((project) => (
                  <option key={project.id} value={project.id}>{project.name}</option>
                ))}
              </Select>
            )}
            <Button type="submit" variant="primary" loading={search.loading} disabled={!query.trim()}>
              Search
            </Button>
          </div>

          {mode === 'semantic' && (
            <p className="text-xs text-[var(--text-subtle)]">
              Ranked by meaning using embeddings computed locally, so searching costs nothing.
            </p>
          )}
        </form>
      </Panel>

      {search.error && (
        <div className="mb-6">
          <InlineError error={search.error} onRetry={search.run} />
        </div>
      )}

      {search.loading && (
        <Panel className="overflow-hidden">
          <SkeletonRows rows={4} />
        </Panel>
      )}

      {!search.loading && results && (
        <Panel className="overflow-hidden">
          <PanelHeader
            title={`${results.hits.length} passage${results.hits.length === 1 ? '' : 's'}`}
            description={
              results.corpusSize > 0
                ? `Retrieved from ${results.corpusSize} indexed paper${results.corpusSize === 1 ? '' : 's'}.`
                : undefined
            }
            as="h2"
          />
          {results.hits.length === 0 ? (
            <EmptyState
              icon={MagnifyingGlass}
              title="Nothing matched"
              description={
                mode === 'semantic'
                  ? 'No passage in your library was close enough to this query. Try rephrasing, or add papers on the topic.'
                  : 'No passage contains those exact words. Meaning search may find it.'
              }
            />
          ) : (
            <div className="flex flex-col gap-2 p-4">
              {results.hits.map((hit) => (
                <EvidenceChunk key={hit.chunkId} hit={hit} />
              ))}
            </div>
          )}
        </Panel>
      )}

      {!search.loading && academic && (
        <Panel className="overflow-hidden">
          <PanelHeader
            title="Academic search"
            description="Metadata from a free academic API. Import a record, then attach its PDF to index the full text."
            as="h2"
          />
          {academic.degraded && (
            <p className="border-b border-[var(--border)] bg-[var(--warn-soft)] px-4 py-2.5 text-[0.8125rem] text-[var(--warn)]">
              {academic.notice}
            </p>
          )}
          {academic.results.length === 0 && !academic.degraded ? (
            <EmptyState icon={MagnifyingGlass} title="No records found" />
          ) : (
            <ul className="divide-y divide-[var(--border)]">
              {academic.results.map((record) => (
                <li key={record.externalId} className="px-4 py-3.5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      <p className="text-[0.9375rem] font-medium text-[var(--text)]">
                        {record.title}
                      </p>
                      <p className="mt-0.5 text-[0.8125rem] text-[var(--text-muted)]">
                        {[record.authors?.slice(0, 4).join(', '), record.year, record.venue]
                          .filter(Boolean)
                          .join(' · ')}
                      </p>
                      {record.abstractText && (
                        <p className="mt-1.5 line-clamp-3 text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
                          {record.abstractText}
                        </p>
                      )}
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        {typeof record.citedByCount === 'number' && (
                          <Badge tone="neutral">{record.citedByCount} citations</Badge>
                        )}
                        {record.openAccessUrl && (
                          <a
                            href={record.openAccessUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="text-xs font-medium text-[var(--accent)] hover:underline"
                          >
                            Open access PDF
                          </a>
                        )}
                      </div>
                    </div>
                    <Button
                      size="sm"
                      loading={importing === record.externalId}
                      onClick={() => importPaper(record)}
                    >
                      Import
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      )}
    </>
  );
}
