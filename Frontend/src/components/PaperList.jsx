import { useCallback, useMemo, useState } from 'react';
import { Books, MagnifyingGlass } from '@phosphor-icons/react';

import { papersApi } from '../services/api';
import { useAsync } from '../hooks/useAsync';
import { useProcessingStatus, isActiveStatus } from '../hooks/useProcessingStatus';
import { useToast } from '../context/AppProviders';
import { PaperRow } from './library';
import { Button, Input, Select } from './primitives';
import { EmptyState, ErrorState, SkeletonRows } from './states';

const STATUS_OPTIONS = [
  ['', 'Any status'],
  ['completed', 'Ready'],
  ['processing', 'Indexing'],
  ['queued', 'Queued'],
  ['failed', 'Failed'],
  ['metadata_only', 'Metadata only'],
];

const READING_OPTIONS = [
  ['', 'Any reading status'],
  ['to_read', 'To read'],
  ['reading', 'Reading'],
  ['read', 'Read'],
];

const SORT_OPTIONS = [
  ['', 'Newest first'],
  ['oldest', 'Oldest first'],
  ['title', 'Title'],
  ['year', 'Publication year'],
  ['updated', 'Recently updated'],
];

/**
 * The library list, with filters and live indexing status.
 *
 * Shared by the library screen and the project screen so the two cannot drift
 * apart in behaviour.
 */
export function PaperList({
  projectId,
  emptyState,
  selectable = false,
  selectedIds = [],
  onSelectionChange,
  refreshToken = 0,
}) {
  const toast = useToast();
  const [filters, setFilters] = useState({
    status: '',
    readingStatus: '',
    tag: '',
    q: '',
    sort: '',
  });
  const [retrying, setRetrying] = useState(null);

  const params = useMemo(
    () => ({
      projectId: projectId || undefined,
      status: filters.status || undefined,
      readingStatus: filters.readingStatus || undefined,
      tag: filters.tag || undefined,
      q: filters.q || undefined,
      sort: filters.sort || undefined,
      size: 100,
    }),
    [projectId, filters],
  );

  const papers = useAsync(
    () => papersApi.list(params),
    [params, refreshToken],
  );
  const tags = useAsync(() => papersApi.tags(), []);

  const loaded = papers.data?.items;
  const items = useMemo(() => loaded ?? [], [loaded]);

  // Only papers that are actually mid-flight are polled; a settled library
  // costs nothing to display.
  const activeIds = useMemo(
    () => items.filter((paper) => isActiveStatus(paper.processingStatus)).map((paper) => paper.id),
    [items],
  );

  const statuses = useProcessingStatus(activeIds, {
    onSettled: (id, status) => {
      const paper = items.find((item) => item.id === id);
      if (status.status === 'completed') {
        toast.success(`"${paper?.title ?? 'A paper'}" is indexed and searchable.`);
      }
      papers.reload();
    },
  });

  const retry = useCallback(
    async (paperId) => {
      setRetrying(paperId);
      try {
        await papersApi.reprocess(paperId);
        toast.info('Indexing restarted.');
        papers.reload();
      } catch (error) {
        toast.error(error.message);
      } finally {
        setRetrying(null);
      }
    },
    [papers, toast],
  );

  const setFilter = (key) => (event) =>
    setFilters((current) => ({ ...current, [key]: event.target.value }));

  const toggleSelect = (id, checked) => {
    const next = checked ? [...selectedIds, id] : selectedIds.filter((value) => value !== id);
    onSelectionChange?.(next);
  };

  return (
    <>
      <div className="flex flex-wrap items-center gap-2 border-b border-[var(--border)] px-4 py-3">
        <div className="relative min-w-[12rem] flex-1">
          <MagnifyingGlass
            size={15}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-subtle)]"
            aria-hidden
          />
          <Input
            value={filters.q}
            onChange={setFilter('q')}
            placeholder="Filter by title"
            aria-label="Filter papers by title"
            className="h-9 py-0 pl-9"
          />
        </div>

        <Select
          aria-label="Filter by indexing status"
          value={filters.status}
          onChange={setFilter('status')}
          className="h-9 w-auto py-0 text-[0.8125rem]"
        >
          {STATUS_OPTIONS.map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </Select>

        <Select
          aria-label="Filter by reading status"
          value={filters.readingStatus}
          onChange={setFilter('readingStatus')}
          className="h-9 w-auto py-0 text-[0.8125rem]"
        >
          {READING_OPTIONS.map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </Select>

        {tags.data?.length > 0 && (
          <Select
            aria-label="Filter by tag"
            value={filters.tag}
            onChange={setFilter('tag')}
            className="h-9 w-auto py-0 text-[0.8125rem]"
          >
            <option value="">Any tag</option>
            {tags.data.map((tag) => (
              <option key={tag} value={tag}>{tag}</option>
            ))}
          </Select>
        )}

        <Select
          aria-label="Sort papers"
          value={filters.sort}
          onChange={setFilter('sort')}
          className="h-9 w-auto py-0 text-[0.8125rem]"
        >
          {SORT_OPTIONS.map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </Select>
      </div>

      {papers.loading && <SkeletonRows rows={4} />}
      {papers.error && <ErrorState error={papers.error} onRetry={papers.reload} />}

      {!papers.loading && !papers.error && items.length === 0 && (
        emptyState ?? (
          <EmptyState
            icon={Books}
            title="No papers match these filters"
            description="Try clearing a filter, or upload a PDF to add one."
            action={
              <Button
                onClick={() =>
                  setFilters({ status: '', readingStatus: '', tag: '', q: '', sort: '' })
                }
              >
                Clear filters
              </Button>
            }
          />
        )
      )}

      {items.length > 0 && (
        <>
          <ul className="divide-y divide-[var(--border)]">
            {items.map((paper) => (
              <PaperRow
                key={paper.id}
                paper={paper}
                status={statuses[paper.id]}
                onRetry={retry}
                retrying={retrying === paper.id}
                selectable={selectable}
                selected={selectedIds.includes(paper.id)}
                onSelect={toggleSelect}
              />
            ))}
          </ul>
          <p className="border-t border-[var(--border)] px-4 py-2.5 text-xs text-[var(--text-subtle)]">
            {papers.data.totalItems} paper{papers.data.totalItems === 1 ? '' : 's'}
            {papers.data.totalPages > 1 && ` · showing the first ${items.length}`}
          </p>
        </>
      )}
    </>
  );
}
