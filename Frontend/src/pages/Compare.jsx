import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { GitDiff } from '@phosphor-icons/react';

import { analysisApi, projectsApi } from '../services/api';
import { useAction, useAsync } from '../hooks/useAsync';
import { PageHeader } from '../layouts/AppShell';
import { PaperList } from '../components/PaperList';
import { ProjectSelect } from '../components/library';
import { AnalysisResult } from '../components/AnalysisResult';
import { Button, Panel, PanelHeader } from '../components/primitives';
import { EmptyState, InlineError, SkeletonText } from '../components/states';
import { InsufficientEvidence } from '../components/evidence';

export default function Compare() {
  const [params] = useSearchParams();
  const projects = useAsync(() => projectsApi.list(), []);
  const [projectId, setProjectId] = useState(params.get('projectId'));
  const [selected, setSelected] = useState([]);
  const [comparison, setComparison] = useState(null);

  const compare = useAction(async () => {
    setComparison(await analysisApi.compare({ paperIds: selected }));
  });

  const enough = selected.length >= 2 && selected.length <= 3;
  const insufficient = compare.error?.code === 'INSUFFICIENT_EVIDENCE';

  return (
    <>
      <PageHeader
        title="Compare papers"
        description="Two or three papers, aligned across fixed dimensions. A dimension a paper does not cover is marked Not reported, never inferred."
        actions={
          projects.data?.length > 0 && (
            <ProjectSelect
              projects={projects.data}
              value={projectId}
              onChange={(value) => {
                setProjectId(value);
                setSelected([]);
              }}
              allowAll
            />
          )
        }
      />

      <Panel className="mb-6 overflow-hidden">
        <PanelHeader
          title="Choose papers"
          description="Select two or three indexed papers."
          as="h2"
          actions={
            <div className="flex items-center gap-3">
              <span className="label-mono tabular-nums">{selected.length} selected</span>
              <Button
                variant="primary"
                icon={GitDiff}
                loading={compare.loading}
                disabled={!enough}
                onClick={compare.run}
              >
                Compare
              </Button>
            </div>
          }
        />
        <PaperList
          projectId={projectId}
          selectable
          selectedIds={selected}
          onSelectionChange={(next) => setSelected(next.slice(0, 3))}
          emptyState={
            <EmptyState
              icon={GitDiff}
              title="No papers to compare yet"
              description="Upload and index at least two papers first."
            />
          }
        />
        {selected.length > 3 && (
          <p className="border-t border-[var(--border)] px-4 py-2.5 text-[0.8125rem] text-[var(--warn)]">
            At most three papers can be compared at once.
          </p>
        )}
      </Panel>

      {compare.loading && (
        <div className="panel p-4">
          <SkeletonText lines={5} />
        </div>
      )}

      {insufficient && <InsufficientEvidence message={compare.error.message} />}
      {compare.error && !insufficient && <InlineError error={compare.error} onRetry={compare.run} />}

      {comparison && !compare.loading && <AnalysisResult analysis={comparison} />}
    </>
  );
}
