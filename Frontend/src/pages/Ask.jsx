import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChatCircleText, PaperPlaneTilt } from '@phosphor-icons/react';

import { analysisApi, papersApi, projectsApi } from '../services/api';
import { useAction, useAsync } from '../hooks/useAsync';
import { PageHeader } from '../layouts/AppShell';
import { ProjectSelect } from '../components/library';
import { AnalysisResult } from '../components/AnalysisResult';
import { Button, Panel, Select, Textarea } from '../components/primitives';
import { EmptyState, InlineError, SkeletonText } from '../components/states';
import { InsufficientEvidence } from '../components/evidence';

export default function Ask() {
  const [params, setParams] = useSearchParams();
  const projects = useAsync(() => projectsApi.list(), []);

  const [projectId, setProjectId] = useState(params.get('projectId'));
  const [paperId, setPaperId] = useState(params.get('paperId'));
  const [question, setQuestion] = useState('');
  const [turns, setTurns] = useState([]);
  const endRef = useRef(null);

  // Only completed papers can be asked about, so the picker offers only those.
  const papers = useAsync(
    () => papersApi.list({ projectId: projectId || undefined, status: 'completed', size: 100 }),
    [projectId],
  );

  useEffect(() => {
    const next = new URLSearchParams();
    if (projectId) next.set('projectId', projectId);
    if (paperId) next.set('paperId', paperId);
    setParams(next, { replace: true });
  }, [projectId, paperId, setParams]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [turns.length]);

  const ask = useAction(async () => {
    const text = question.trim();
    if (!text) return;

    const answer = paperId
      ? await analysisApi.askPaper(paperId, { question: text })
      : await analysisApi.askProject(projectId, { question: text });

    setTurns((current) => [...current, { question: text, answer }]);
    setQuestion('');
  });

  const scoped = Boolean(paperId || projectId);
  const insufficient = ask.error?.code === 'INSUFFICIENT_EVIDENCE';

  return (
    <>
      <PageHeader
        title="Ask"
        description="Answers are built from passages retrieved out of your own papers, and every claim shows the passage behind it."
      />

      <Panel className="mb-6">
        <div className="flex flex-wrap items-center gap-2 p-4">
          {projects.data?.length > 0 && (
            <ProjectSelect
              projects={projects.data}
              value={projectId}
              onChange={(value) => {
                setProjectId(value);
                setPaperId(null);
              }}
            />
          )}
          <Select
            aria-label="Limit to one paper"
            value={paperId ?? ''}
            onChange={(event) => setPaperId(event.target.value || null)}
            disabled={!projectId && !paperId}
            className="h-9 w-auto min-w-[14rem] py-0 text-[0.8125rem]"
          >
            <option value="">Whole project</option>
            {papers.data?.items?.map((paper) => (
              <option key={paper.id} value={paper.id}>
                {paper.title || 'Untitled paper'}
              </option>
            ))}
          </Select>
        </div>
      </Panel>

      {turns.length === 0 && !ask.loading && (
        <Panel className="mb-6">
          <EmptyState
            icon={ChatCircleText}
            title={scoped ? 'Ask your first question' : 'Choose what to ask about'}
            description={
              scoped
                ? 'Ask in plain English. If your library does not contain enough to answer, the system says so instead of guessing.'
                : 'Pick a project, and optionally a single paper, to scope the retrieval.'
            }
          />
        </Panel>
      )}

      <div className="flex flex-col gap-6">
        {turns.map((turn, index) => (
          <section key={index} className="flex flex-col gap-3">
            <p className="rounded-[var(--radius-panel)] border border-[var(--border)] bg-[var(--surface-raised)] px-4 py-3 text-[0.9375rem] font-medium text-[var(--text)]">
              {turn.question}
            </p>
            <AnalysisResult analysis={turn.answer} paperTitles={turn.answer.paperTitles} />
          </section>
        ))}

        {ask.loading && (
          <div className="panel p-4">
            <SkeletonText lines={4} />
            <p className="mt-3 text-xs text-[var(--text-subtle)]">
              Retrieving passages, then reasoning only over what was retrieved.
            </p>
          </div>
        )}

        {insufficient && (
          <InsufficientEvidence
            message={ask.error.message}
            action={
              <Button size="sm" onClick={ask.reset}>
                Ask something else
              </Button>
            }
          />
        )}
        {ask.error && !insufficient && <InlineError error={ask.error} onRetry={ask.run} />}
        <div ref={endRef} />
      </div>

      <form
        className="sticky bottom-4 mt-6"
        onSubmit={(event) => {
          event.preventDefault();
          ask.run();
        }}
      >
        <div className="panel flex items-end gap-2 p-2 shadow-sm">
          <Textarea
            rows={2}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={(event) => {
              // Enter sends, Shift+Enter breaks the line.
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                ask.run();
              }
            }}
            placeholder={
              scoped ? 'What do these papers say about...' : 'Choose a project above first'
            }
            aria-label="Your question"
            disabled={!scoped}
            maxLength={2000}
            className="border-0 bg-transparent focus:outline-none"
          />
          <Button
            type="submit"
            variant="primary"
            icon={PaperPlaneTilt}
            loading={ask.loading}
            disabled={!scoped || !question.trim()}
          >
            Ask
          </Button>
        </div>
      </form>
    </>
  );
}
