import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  Books,
  ChatCircleText,
  GitDiff,
  Lightbulb,
  NotePencil,
  Target,
  TextAlignLeft,
  Trash,
} from '@phosphor-icons/react';

import { analysisApi, projectsApi } from '../services/api';
import { useAction, useAsync } from '../hooks/useAsync';
import { useToast } from '../context/AppProviders';
import { PageHeader } from '../layouts/AppShell';
import { PaperList } from '../components/PaperList';
import { UploadDropzone } from '../components/library';
import { AnalysisResult } from '../components/AnalysisResult';
import { Button, Field, Input, Panel, PanelHeader, Textarea } from '../components/primitives';
import { EmptyState, ErrorState, InlineError, SkeletonText } from '../components/states';
import { InsufficientEvidence } from '../components/evidence';

function EditProject({ project, onSaved, onCancel }) {
  const toast = useToast();
  const [name, setName] = useState(project.name);
  const [topic, setTopic] = useState(project.researchTopic ?? '');
  const [description, setDescription] = useState(project.description ?? '');

  const save = useAction(async () => {
    const updated = await projectsApi.update(project.id, {
      name: name.trim(),
      researchTopic: topic.trim(),
      description: description.trim(),
    });
    toast.success('Project updated.');
    onSaved(updated);
  });

  return (
    <form
      className="flex flex-col gap-4 p-4"
      onSubmit={(event) => {
        event.preventDefault();
        save.run();
      }}
    >
      <Field label="Project name" htmlFor="edit-name" required error={save.error?.fields?.name}>
        <Input id="edit-name" value={name} onChange={(event) => setName(event.target.value)} />
      </Field>
      <Field label="Research topic" htmlFor="edit-topic">
        <Input id="edit-topic" value={topic} onChange={(event) => setTopic(event.target.value)} />
      </Field>
      <Field label="Description" htmlFor="edit-description">
        <Textarea
          id="edit-description"
          rows={2}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
      </Field>
      {save.error && !save.error.fields && <InlineError error={save.error} />}
      <div className="flex gap-2">
        <Button type="submit" variant="primary" loading={save.loading}>
          Save changes
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

export default function ProjectView() {
  const { projectId } = useParams();
  const navigate = useNavigate();
  const toast = useToast();

  const project = useAsync(() => projectsApi.get(projectId), [projectId]);
  const [editing, setEditing] = useState(false);
  const [refreshToken, setRefreshToken] = useState(0);
  const [review, setReview] = useState(null);

  const generateReview = useAction(async () => {
    const result = await analysisApi.literatureReview({ projectId });
    setReview(result);
  });

  const remove = useAction(async () => {
    const result = await projectsApi.remove(projectId);
    toast.success(
      result.papersUnassigned > 0
        ? `Project deleted. ${result.papersUnassigned} paper(s) stayed in your library, now unassigned.`
        : 'Project deleted.',
    );
    navigate('/dashboard');
  });

  if (project.loading) {
    return (
      <div className="panel p-6">
        <SkeletonText lines={3} />
      </div>
    );
  }
  if (project.error) {
    return <ErrorState error={project.error} onRetry={project.reload} />;
  }

  const data = project.data;
  const ready = data.paperStatusCounts?.completed ?? 0;

  return (
    <>
      <PageHeader
        breadcrumb={
          <Link
            to="/dashboard"
            className="mb-3 inline-flex items-center gap-1.5 text-[0.8125rem] text-[var(--text-muted)] hover:text-[var(--accent)]"
          >
            <ArrowLeft size={13} aria-hidden />
            Dashboard
          </Link>
        }
        title={data.name}
        description={data.researchTopic || data.description}
        actions={
          <>
            <Button icon={NotePencil} onClick={() => setEditing((value) => !value)}>
              Edit
            </Button>
            <Button
              variant="danger"
              icon={Trash}
              loading={remove.loading}
              onClick={() => {
                if (
                  window.confirm(
                    'Delete this project? Its papers stay in your library and become unassigned.',
                  )
                ) {
                  remove.run();
                }
              }}
            >
              Delete
            </Button>
          </>
        }
      />

      {editing && (
        <Panel className="mb-6">
          <PanelHeader title="Edit project" as="h2" />
          <EditProject
            project={data}
            onCancel={() => setEditing(false)}
            onSaved={(updated) => {
              setEditing(false);
              project.setData(updated);
            }}
          />
        </Panel>
      )}

      <div className="mb-6 grid gap-px overflow-hidden rounded-[var(--radius-panel)] border border-[var(--border)] bg-[var(--border)] sm:grid-cols-4">
        {[
          ['Papers', data.paperCount],
          ['Ready', ready],
          ['Indexing', (data.paperStatusCounts?.queued ?? 0) + (data.paperStatusCounts?.processing ?? 0)],
          ['Failed', data.paperStatusCounts?.failed ?? 0],
        ].map(([label, value]) => (
          <div key={label} className="bg-[var(--surface-raised)] px-4 py-3">
            <p className="label-mono">{label}</p>
            <p className="mt-1 font-mono text-xl tabular-nums text-[var(--text)]">{value}</p>
          </div>
        ))}
      </div>

      <Panel className="mb-6">
        <PanelHeader
          title="Project analysis"
          description="Each of these retrieves from this project only, and shows the evidence it used."
          as="h2"
        />
        <div className="grid gap-px bg-[var(--border)] sm:grid-cols-2 lg:grid-cols-4">
          {[
            [`/ask?projectId=${projectId}`, ChatCircleText, 'Ask a question'],
            [`/gaps?projectId=${projectId}`, Target, 'Find research gaps'],
            [`/novelty?projectId=${projectId}`, Lightbulb, 'Check an idea'],
            [`/compare?projectId=${projectId}`, GitDiff, 'Compare papers'],
          ].map(([to, Icon, label]) => (
            <Link
              key={to}
              to={to}
              className="flex items-center gap-2.5 bg-[var(--surface-raised)] px-4 py-3.5 text-[0.875rem] font-medium text-[var(--text)] transition-colors duration-150 hover:bg-[var(--surface-sunken)]"
            >
              <Icon size={16} className="text-[var(--accent)]" aria-hidden />
              {label}
            </Link>
          ))}
        </div>
        <div className="border-t border-[var(--border)] p-4">
          <Button
            icon={TextAlignLeft}
            loading={generateReview.loading}
            disabled={ready === 0}
            onClick={generateReview.run}
          >
            Generate literature review draft
          </Button>
          {ready === 0 && (
            <p className="mt-2 text-xs text-[var(--text-subtle)]">
              Available once at least one paper has finished indexing.
            </p>
          )}
          {generateReview.error?.code === 'INSUFFICIENT_EVIDENCE' ? (
            <div className="mt-3">
              <InsufficientEvidence message={generateReview.error.message} />
            </div>
          ) : (
            generateReview.error && (
              <div className="mt-3">
                <InlineError error={generateReview.error} onRetry={generateReview.run} />
              </div>
            )
          )}
        </div>
      </Panel>

      {review && (
        <div className="mb-6">
          <AnalysisResult analysis={review} />
        </div>
      )}

      <div className="mb-6">
        <UploadDropzone
          projectId={projectId}
          onUploaded={() => {
            setRefreshToken((token) => token + 1);
            project.reload();
          }}
        />
      </div>

      <Panel className="overflow-hidden">
        <PanelHeader title="Papers in this project" as="h2" />
        <PaperList
          projectId={projectId}
          refreshToken={refreshToken}
          emptyState={
            <EmptyState
              icon={Books}
              title="No papers here yet"
              description="Upload PDFs above, or import metadata from academic search and attach the file later."
            />
          }
        />
      </Panel>
    </>
  );
}
