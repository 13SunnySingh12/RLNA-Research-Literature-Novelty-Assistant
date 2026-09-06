import { useState } from 'react';
import { Books } from '@phosphor-icons/react';

import { projectsApi } from '../services/api';
import { useAsync } from '../hooks/useAsync';
import { PageHeader } from '../layouts/AppShell';
import { PaperList } from '../components/PaperList';
import { ProjectSelect, UploadDropzone } from '../components/library';
import { Panel, PanelHeader } from '../components/primitives';
import { EmptyState } from '../components/states';

export default function Library() {
  const projects = useAsync(() => projectsApi.list(), []);
  const [projectId, setProjectId] = useState(null);
  const [refreshToken, setRefreshToken] = useState(0);

  return (
    <>
      <PageHeader
        title="Library"
        description="Every paper you have uploaded, across all projects."
        actions={
          projects.data?.length > 0 && (
            <ProjectSelect
              projects={projects.data}
              value={projectId}
              onChange={setProjectId}
              allowAll
              label="Filter by project"
            />
          )
        }
      />

      <div className="mb-6">
        <UploadDropzone
          projectId={projectId}
          disabled={projects.loading}
          onUploaded={() => setRefreshToken((token) => token + 1)}
        />
        {!projectId && (
          <p className="mt-2 text-xs text-[var(--text-subtle)]">
            Uploading without a project selected leaves the papers unassigned. You can move them
            into a project later.
          </p>
        )}
      </div>

      <Panel className="overflow-hidden">
        <PanelHeader title="Papers" as="h2" />
        <PaperList
          projectId={projectId}
          refreshToken={refreshToken}
          emptyState={
            <EmptyState
              icon={Books}
              title="Your library is empty"
              description="Upload a few PDFs above. Text extraction, section detection and indexing all run on the server, so you can carry on working while they finish."
            />
          }
        />
      </Panel>
    </>
  );
}
