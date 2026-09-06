import { useState } from 'react';
import { ClockCounterClockwise, Trash } from '@phosphor-icons/react';

import { analysisApi } from '../services/api';
import { useAsync } from '../hooks/useAsync';
import { useToast } from '../context/AppProviders';
import { PageHeader } from '../layouts/AppShell';
import { AnalysisResult } from '../components/AnalysisResult';
import { Badge, IconButton, Panel, PanelHeader, Select } from '../components/primitives';
import { EmptyState, ErrorState, SkeletonRows } from '../components/states';

const TYPES = [
  ['', 'All types'],
  ['qa', 'Questions'],
  ['summary', 'Summaries'],
  ['comparison', 'Comparisons'],
  ['research_gap', 'Research gaps'],
  ['novelty', 'Novelty checks'],
  ['literature_review', 'Literature reviews'],
  ['concepts', 'Concepts'],
];

const LABELS = Object.fromEntries(TYPES.slice(1));

export default function History() {
  const toast = useToast();
  const [type, setType] = useState('');
  const [openId, setOpenId] = useState(null);

  const history = useAsync(
    () => analysisApi.history({ type: type || undefined, size: 50 }),
    [type],
  );
  const detail = useAsync(
    () => (openId ? analysisApi.get(openId) : Promise.resolve(null)),
    [openId],
    { immediate: Boolean(openId) },
  );

  const remove = async (id, event) => {
    event.stopPropagation();
    if (!window.confirm('Delete this saved analysis?')) return;
    try {
      await analysisApi.remove(id);
      toast.success('Analysis deleted.');
      if (openId === id) setOpenId(null);
      history.reload();
    } catch (error) {
      toast.error(error.message);
    }
  };

  const items = history.data?.items ?? [];

  return (
    <>
      <PageHeader
        title="History"
        description="Every analysis is stored with the evidence it used, so re-opening one costs nothing."
        actions={
          <Select
            aria-label="Filter by analysis type"
            value={type}
            onChange={(event) => {
              setType(event.target.value);
              setOpenId(null);
            }}
            className="h-9 w-auto py-0 text-[0.8125rem]"
          >
            {TYPES.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </Select>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[1fr_1.4fr]">
        <Panel className="overflow-hidden">
          <PanelHeader title="Saved analyses" as="h2" />
          {history.loading && <SkeletonRows rows={5} />}
          {history.error && <ErrorState error={history.error} onRetry={history.reload} />}
          {!history.loading && !history.error && items.length === 0 && (
            <EmptyState
              icon={ClockCounterClockwise}
              title="Nothing saved yet"
              description="Ask a question, compare papers, or run a novelty check. Results are stored here automatically."
            />
          )}
          {items.length > 0 && (
            <ul className="divide-y divide-[var(--border)]">
              {items.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => setOpenId(item.id)}
                    aria-current={openId === item.id}
                    className={`flex w-full items-start gap-3 px-4 py-3.5 text-left transition-colors duration-150 hover:bg-[var(--surface-sunken)] ${
                      openId === item.id ? 'bg-[var(--accent-soft)]' : ''
                    }`}
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="neutral">{LABELS[item.analysisType] ?? item.analysisType}</Badge>
                        {item.confidence && (
                          <span className="label-mono">confidence {item.confidence}</span>
                        )}
                      </div>
                      <p className="mt-1.5 line-clamp-2 text-[0.875rem] text-[var(--text)]">
                        {item.inputQuery || 'No question recorded'}
                      </p>
                      <p className="mt-1 text-xs text-[var(--text-subtle)]">
                        {new Date(item.createdAt).toLocaleString()}
                      </p>
                    </div>
                    <IconButton
                      label="Delete analysis"
                      icon={Trash}
                      size={15}
                      onClick={(event) => remove(item.id, event)}
                    />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <div>
          {!openId && (
            <Panel>
              <EmptyState
                icon={ClockCounterClockwise}
                title="Select an analysis"
                description="Its full result, evidence and limitations open here, exactly as first generated."
              />
            </Panel>
          )}
          {openId && detail.loading && (
            <Panel className="overflow-hidden">
              <SkeletonRows rows={4} />
            </Panel>
          )}
          {openId && detail.error && (
            <ErrorState error={detail.error} onRetry={detail.reload} />
          )}
          {openId && detail.data && !detail.loading && (
            <AnalysisResult analysis={detail.data} />
          )}
        </div>
      </div>

      {items.length > 0 && (
        <p className="mt-4 text-xs text-[var(--text-subtle)]">
          An identical request over the same evidence reuses the stored result instead of calling a
          model again.
        </p>
      )}
    </>
  );
}
