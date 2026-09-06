import { useState } from 'react';
import { Link } from 'react-router-dom';
import { FolderPlus, Plus, Sparkle } from '@phosphor-icons/react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { analyticsApi, projectsApi } from '../services/api';
import { useAsync, useAction } from '../hooks/useAsync';
import { useToast } from '../context/AppProviders';
import { PageHeader } from '../layouts/AppShell';
import { Button, Field, Input, Panel, PanelHeader, Textarea } from '../components/primitives';
import { EmptyState, ErrorState, InlineError, Skeleton, SkeletonRows } from '../components/states';

const CHART_COLORS = ['var(--accent)'];

function ChartFrame({ title, children, empty }) {
  return (
    <Panel>
      <PanelHeader title={title} as="h3" />
      <div className="p-4">
        {empty ? (
          <p className="py-8 text-center text-[0.8125rem] text-[var(--text-muted)]">
            Nothing to chart yet. Index a few papers and this fills in.
          </p>
        ) : (
          children
        )}
      </div>
    </Panel>
  );
}

function Stat({ label, value, hint }) {
  return (
    <div className="px-4 py-3.5">
      <p className="label-mono">{label}</p>
      <p className="mt-1 font-mono text-2xl tabular-nums text-[var(--text)]">{value}</p>
      {hint && <p className="mt-0.5 text-xs text-[var(--text-subtle)]">{hint}</p>}
    </div>
  );
}

function NewProjectForm({ onCreated, onCancel }) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [topic, setTopic] = useState('');
  const [description, setDescription] = useState('');

  const create = useAction(async () => {
    const project = await projectsApi.create({
      name: name.trim(),
      researchTopic: topic.trim() || null,
      description: description.trim() || null,
    });
    toast.success(`Created "${project.name}".`);
    onCreated(project);
  });

  return (
    <form
      className="flex flex-col gap-4 p-4"
      onSubmit={(event) => {
        event.preventDefault();
        create.run();
      }}
    >
      <Field
        label="Project name"
        htmlFor="project-name"
        required
        error={create.error?.fields?.name}
      >
        <Input
          id="project-name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Federated learning for healthcare"
          maxLength={255}
          autoFocus
          invalid={Boolean(create.error?.fields?.name)}
        />
      </Field>

      <Field
        label="Research topic"
        htmlFor="project-topic"
        helper="Used to steer gap analysis and the literature review draft."
      >
        <Input
          id="project-topic"
          value={topic}
          onChange={(event) => setTopic(event.target.value)}
          placeholder="Privacy-preserving training on distributed clinical data"
          maxLength={500}
        />
      </Field>

      <Field label="Description" htmlFor="project-description">
        <Textarea
          id="project-description"
          rows={2}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
      </Field>

      {create.error && !create.error.fields && <InlineError error={create.error} />}

      <div className="flex items-center gap-2">
        <Button type="submit" variant="primary" loading={create.loading} disabled={!name.trim()}>
          Create project
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

export default function Dashboard() {
  const [creating, setCreating] = useState(false);
  const projects = useAsync(() => projectsApi.list(), []);
  const analytics = useAsync(() => analyticsApi.get(undefined), []);

  const hasProjects = (projects.data?.length ?? 0) > 0;

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="Your research projects and what the library currently contains."
        actions={
          hasProjects && (
            <Button variant="primary" icon={Plus} onClick={() => setCreating(true)}>
              New project
            </Button>
          )
        }
      />

      {creating && (
        <Panel className="mb-6">
          <PanelHeader title="New research project" as="h2" />
          <NewProjectForm
            onCancel={() => setCreating(false)}
            onCreated={(project) => {
              setCreating(false);
              projects.setData((current) => [project, ...(current ?? [])]);
            }}
          />
        </Panel>
      )}

      <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <Panel>
          <PanelHeader
            title="Projects"
            description="Papers are organised under projects. Analysis runs against one project at a time."
            as="h2"
          />
          {projects.loading && <SkeletonRows rows={3} />}
          {projects.error && <ErrorState error={projects.error} onRetry={projects.reload} />}
          {!projects.loading && !projects.error && !hasProjects && !creating && (
            <EmptyState
              icon={FolderPlus}
              title="Create your first project"
              description="A project holds a set of papers on one topic. Upload PDFs into it, then search, compare, and check novelty against them."
              action={
                <Button variant="primary" icon={Plus} onClick={() => setCreating(true)}>
                  New project
                </Button>
              }
            />
          )}
          {hasProjects && (
            <ul className="divide-y divide-[var(--border)]">
              {projects.data.map((project) => {
                const counts = project.paperStatusCounts ?? {};
                const indexing = (counts.queued ?? 0) + (counts.processing ?? 0);
                return (
                  <li key={project.id}>
                    <Link
                      to={`/projects/${project.id}`}
                      className="block px-4 py-3.5 transition-colors duration-150 hover:bg-[var(--surface-sunken)]"
                    >
                      <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <span className="text-[0.9375rem] font-medium text-[var(--text)]">
                          {project.name}
                        </span>
                        <span className="label-mono tabular-nums">
                          {project.paperCount} paper{project.paperCount === 1 ? '' : 's'}
                        </span>
                      </div>
                      {project.researchTopic && (
                        <p className="mt-0.5 line-clamp-1 text-[0.8125rem] text-[var(--text-muted)]">
                          {project.researchTopic}
                        </p>
                      )}
                      {indexing > 0 && (
                        <p className="mt-1 text-xs text-[var(--accent)]">
                          {indexing} paper{indexing === 1 ? '' : 's'} still indexing
                        </p>
                      )}
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </Panel>

        <Panel>
          <PanelHeader title="Library" as="h2" />
          {analytics.loading ? (
            <div className="grid grid-cols-2 gap-px bg-[var(--border)]">
              {Array.from({ length: 4 }).map((_, index) => (
                <div key={index} className="bg-[var(--surface-raised)] px-4 py-3.5">
                  <Skeleton className="h-3 w-16" />
                  <Skeleton className="mt-2 h-7 w-12" />
                </div>
              ))}
            </div>
          ) : analytics.error ? (
            <ErrorState error={analytics.error} onRetry={analytics.reload} />
          ) : (
            <div className="grid grid-cols-2 gap-px bg-[var(--border)]">
              <Stat label="Papers" value={analytics.data.totalPapers} />
              <Stat label="Indexed chunks" value={analytics.data.totalChunks} />
              <Stat
                label="Ready"
                value={analytics.data.papersByStatus?.completed ?? 0}
                hint="Searchable now"
              />
              <Stat
                label="Index success"
                value={`${Math.round((analytics.data.processingSuccessRate ?? 1) * 100)}%`}
                hint="Of finished attempts"
              />
            </div>
          )}
        </Panel>
      </div>

      {analytics.data && analytics.data.totalPapers > 0 && (
        <div className="mt-6 grid gap-6 lg:grid-cols-2">
          <ChartFrame
            title="Papers by publication year"
            empty={!analytics.data.papersByYear?.length}
          >
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={analytics.data.papersByYear} margin={{ top: 4, right: 4, bottom: 0, left: -20 }}>
                <CartesianGrid stroke="var(--border)" vertical={false} />
                <XAxis
                  dataKey="year"
                  tick={{ fontSize: 11, fill: 'var(--text-subtle)' }}
                  tickLine={false}
                  axisLine={{ stroke: 'var(--border)' }}
                />
                <YAxis
                  allowDecimals={false}
                  tick={{ fontSize: 11, fill: 'var(--text-subtle)' }}
                  tickLine={false}
                  axisLine={false}
                />
                <Tooltip
                  cursor={{ fill: 'var(--surface-sunken)' }}
                  contentStyle={{
                    background: 'var(--surface-raised)',
                    border: '1px solid var(--border)',
                    borderRadius: 8,
                    fontSize: 12,
                    color: 'var(--text)',
                  }}
                />
                <Bar dataKey="count" radius={[3, 3, 0, 0]}>
                  {analytics.data.papersByYear.map((entry) => (
                    <Cell key={entry.year} fill={CHART_COLORS[0]} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </ChartFrame>

          <ChartFrame title="Most common concepts" empty={!analytics.data.topConcepts?.length}>
            <ul className="flex flex-col gap-2">
              {analytics.data.topConcepts?.slice(0, 8).map((concept) => {
                const max = analytics.data.topConcepts[0].paperCount || 1;
                return (
                  <li key={`${concept.concept}-${concept.conceptType}`} className="flex items-center gap-3">
                    <span className="w-40 shrink-0 truncate text-[0.8125rem] text-[var(--text)]">
                      {concept.concept}
                    </span>
                    <span
                      className="h-1.5 rounded-full bg-[var(--accent)]"
                      style={{ width: `${Math.max(6, (concept.paperCount / max) * 100)}%` }}
                      aria-hidden
                    />
                    <span className="label-mono tabular-nums">{concept.paperCount}</span>
                  </li>
                );
              })}
            </ul>
          </ChartFrame>
        </div>
      )}

      {hasProjects && (
        <Panel className="mt-6">
          <PanelHeader title="Quick actions" as="h2" />
          <div className="grid gap-px bg-[var(--border)] sm:grid-cols-3">
            {[
              ['/ask', 'Ask a question', 'Grounded in your own papers, with the passages shown.'],
              ['/gaps', 'Find research gaps', 'Derived from limitations the authors reported.'],
              ['/novelty', 'Check an idea', 'Compared against your library and academic search.'],
            ].map(([to, title, body]) => (
              <Link
                key={to}
                to={to}
                className="bg-[var(--surface-raised)] p-4 transition-colors duration-150 hover:bg-[var(--surface-sunken)]"
              >
                <Sparkle size={16} className="text-[var(--accent)]" aria-hidden />
                <p className="mt-2.5 text-[0.875rem] font-medium text-[var(--text)]">{title}</p>
                <p className="mt-1 text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
                  {body}
                </p>
              </Link>
            ))}
          </div>
        </Panel>
      )}
    </>
  );
}
