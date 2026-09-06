import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Lightbulb } from '@phosphor-icons/react';

import { analysisApi, projectsApi } from '../services/api';
import { useAction, useAsync } from '../hooks/useAsync';
import { PageHeader } from '../layouts/AppShell';
import { ProjectSelect } from '../components/library';
import { AnalysisResult } from '../components/AnalysisResult';
import { Button, Checkbox, Field, Panel, Textarea } from '../components/primitives';
import { EmptyState, InlineError, SkeletonText } from '../components/states';
import { InsufficientEvidence } from '../components/evidence';

const MIN_LENGTH = 20;

export default function NoveltyCheck() {
  const [params] = useSearchParams();
  const projects = useAsync(() => projectsApi.list(), []);

  const [projectId, setProjectId] = useState(params.get('projectId'));
  const [idea, setIdea] = useState('');
  const [includeAcademic, setIncludeAcademic] = useState(true);
  const [assessment, setAssessment] = useState(null);

  const check = useAction(async () => {
    setAssessment(
      await analysisApi.novelty({
        projectId,
        idea: idea.trim(),
        includeAcademicSearch: includeAcademic,
      }),
    );
  });

  const tooShort = idea.trim().length > 0 && idea.trim().length < MIN_LENGTH;
  const ready = projectId && idea.trim().length >= MIN_LENGTH;
  const insufficient = check.error?.code === 'INSUFFICIENT_EVIDENCE';

  return (
    <>
      <PageHeader
        title="Novelty check"
        description="Describe an idea and see what the retrieved literature already covers, where it overlaps, and what looks different."
      />

      <Panel className="mb-6">
        <form
          className="flex flex-col gap-4 p-4"
          onSubmit={(event) => {
            event.preventDefault();
            check.run();
          }}
        >
          {projects.data?.length > 0 && (
            <Field label="Compare against" htmlFor="novelty-project" required>
              <ProjectSelect
                projects={projects.data}
                value={projectId}
                onChange={setProjectId}
              />
            </Field>
          )}

          <Field
            label="Your research idea"
            htmlFor="idea"
            required
            helper={`Describe it in at least ${MIN_LENGTH} characters. More specific descriptions retrieve better.`}
            error={
              tooShort
                ? `Add a little more detail: ${MIN_LENGTH - idea.trim().length} more characters.`
                : check.error?.fields?.idea
            }
          >
            <Textarea
              id="idea"
              rows={4}
              value={idea}
              onChange={(event) => setIdea(event.target.value)}
              invalid={tooShort}
              maxLength={4000}
              placeholder="Using federated learning with differential privacy for ECG arrhythmia detection on edge devices"
            />
          </Field>

          <Checkbox
            label="Also search free academic APIs, not just my library"
            checked={includeAcademic}
            onChange={(event) => setIncludeAcademic(event.target.checked)}
          />

          <div>
            <Button
              type="submit"
              variant="primary"
              icon={Lightbulb}
              loading={check.loading}
              disabled={!ready}
            >
              Check this idea
            </Button>
          </div>
        </form>
      </Panel>

      {!assessment && !check.loading && !check.error && (
        <Panel>
          <EmptyState
            icon={Lightbulb}
            title="What this can and cannot tell you"
            description="It reports what the retrieved corpus contains, where your idea overlaps with it, and which aspects appear different. It does not produce a novelty score, and it cannot prove that nothing similar exists."
          />
        </Panel>
      )}

      {check.loading && (
        <div className="panel p-4">
          <SkeletonText lines={6} />
          <p className="mt-3 text-xs text-[var(--text-subtle)]">
            Embedding the idea, retrieving the closest work, then comparing against it.
          </p>
        </div>
      )}

      {insufficient && <InsufficientEvidence message={check.error.message} />}
      {check.error && !insufficient && !check.error.fields && (
        <InlineError error={check.error} onRetry={check.run} />
      )}

      {assessment && !check.loading && <AnalysisResult analysis={assessment} />}
    </>
  );
}
