import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Target } from '@phosphor-icons/react';

import { analysisApi, projectsApi } from '../services/api';
import { useAction, useAsync } from '../hooks/useAsync';
import { PageHeader } from '../layouts/AppShell';
import { ProjectSelect } from '../components/library';
import { AnalysisResult } from '../components/AnalysisResult';
import { Button, Panel } from '../components/primitives';
import { EmptyState, InlineError, SkeletonText } from '../components/states';
import { InsufficientEvidence } from '../components/evidence';

export default function ResearchGaps() {
  const [params] = useSearchParams();
  const projects = useAsync(() => projectsApi.list(), []);
  const [projectId, setProjectId] = useState(params.get('projectId'));
  const [gaps, setGaps] = useState(null);

  const find = useAction(async (refresh = false) => {
    setGaps(await analysisApi.researchGap({ projectId, refresh }));
  });

  const insufficient = find.error?.code === 'INSUFFICIENT_EVIDENCE';

  return (
    <>
      <PageHeader
        title="Research gaps"
        description="Gaps are derived from what authors themselves wrote in their limitations, discussion and future-work sections, not invented by a model."
        actions={
          projects.data?.length > 0 && (
            <>
              <ProjectSelect
                projects={projects.data}
                value={projectId}
                onChange={(value) => {
                  setProjectId(value);
                  setGaps(null);
                  find.reset();
                }}
              />
              <Button
                variant="primary"
                icon={Target}
                loading={find.loading}
                disabled={!projectId}
                onClick={() => find.run(Boolean(gaps))}
              >
                {gaps ? 'Run again' : 'Find gaps'}
              </Button>
            </>
          )
        }
      />

      <Panel className="mb-6">
        <div className="p-4">
          <h2 className="text-[0.875rem] font-semibold text-[var(--text)]">How this works</h2>
          <p className="mt-1.5 max-w-3xl text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
            Retrieval is weighted toward limitations, discussion and future work, so the passages
            that reach the model are the ones where authors said what their work could not do. Any
            gap that cannot cite a real passage is discarded rather than shown, which means an empty
            result is a real answer about your corpus.
          </p>
        </div>
      </Panel>

      {!projectId && (
        <Panel>
          <EmptyState
            icon={Target}
            title="Choose a project"
            description="Gap analysis reads one project at a time, and reports how many papers it drew from."
          />
        </Panel>
      )}

      {find.loading && (
        <div className="panel p-4">
          <SkeletonText lines={6} />
          <p className="mt-3 text-xs text-[var(--text-subtle)]">
            Retrieving limitations and future-work passages across the project.
          </p>
        </div>
      )}

      {insufficient && <InsufficientEvidence message={find.error.message} />}
      {find.error && !insufficient && <InlineError error={find.error} onRetry={() => find.run()} />}

      {gaps && !find.loading && <AnalysisResult analysis={gaps} />}
    </>
  );
}
