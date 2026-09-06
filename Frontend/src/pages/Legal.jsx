import { AuthCard, AuthLink } from '../components/auth';

/**
 * Plain-language descriptions of how the service actually behaves.
 *
 * Deliberately not boilerplate copied from a template: every statement here is
 * checkable against the implementation, which is more useful than clauses
 * nobody reads. It is not a lawyer-drafted agreement, and says so.
 */

function Section({ heading, children }) {
  return (
    <section className="border-t border-[var(--border)] pt-5">
      <h2 className="text-[0.9375rem] font-semibold text-[var(--text)]">{heading}</h2>
      <div className="mt-2 flex flex-col gap-2 text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
        {children}
      </div>
    </section>
  );
}

function Disclaimer() {
  return (
    <p className="rounded-[var(--radius-panel)] border border-[var(--border)] bg-[var(--surface-sunken)] p-4 text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
      This is a plain-language summary written to be accurate about what the software does. It is
      not a lawyer-drafted agreement, and it should be replaced with a reviewed one before the
      service is offered commercially.
    </p>
  );
}

export function Terms() {
  return (
    <AuthCard
      title="Terms"
      description="What this service does, and what it does not promise."
      footer={
        <>
          See also the <AuthLink to="/privacy">Privacy Policy</AuthLink>. ·{' '}
          <AuthLink to="/signup">Back to sign up</AuthLink>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        <Disclaimer />

        <Section heading="What the service is">
          <p>
            RLNA indexes research papers you upload and answers questions about them using only the
            passages it retrieved from your own library. It is a research aid, not a source of
            authoritative fact.
          </p>
        </Section>

        <Section heading="What it does not guarantee">
          <p>
            Generated summaries, comparisons, research gaps and novelty assessments can be wrong or
            incomplete. A novelty assessment is never proof that an idea is new: it reflects only
            the papers in your library and whatever an academic search returned at that moment.
          </p>
          <p>
            Verify anything that matters against the cited passage before relying on it. Every
            claim shown to you carries the chunk it came from precisely so you can check it.
          </p>
        </Section>

        <Section heading="Your responsibilities">
          <p>
            Upload only documents you are permitted to store and process. Keep your account
            credentials to yourself. Do not use the service to attempt to access another
            account&rsquo;s data.
          </p>
        </Section>

        <Section heading="Availability">
          <p>
            The service depends on external providers for language models, storage and academic
            search. Any of them can be unavailable, and the service degrades or refuses rather than
            inventing an answer when that happens.
          </p>
        </Section>
      </div>
    </AuthCard>
  );
}

export function Privacy() {
  return (
    <AuthCard
      title="Privacy"
      description="What is stored, where it goes, and who can reach it."
      footer={
        <>
          See also the <AuthLink to="/terms">Terms</AuthLink>. ·{' '}
          <AuthLink to="/signup">Back to sign up</AuthLink>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        <Disclaimer />

        <Section heading="Your account">
          <p>
            Signing in with Google or GitHub shares your name, email address and avatar with the
            service. Signing up with an email address stores your name and email, and a hash of
            your password — the password itself is never stored or visible to the operator.
          </p>
        </Section>

        <Section heading="Your papers">
          <p>
            Uploaded PDFs are stored in private object storage. They are never publicly readable;
            viewing one issues a link that expires after a few minutes and only after your
            ownership has been checked.
          </p>
          <p>
            Extracted text, detected sections, chunks, embeddings, tags and notes are stored in a
            PostgreSQL database, scoped to your account. Nothing is shared between accounts.
          </p>
        </Section>

        <Section heading="What is sent to third parties">
          <p>
            Generating a summary, answer, comparison, gap analysis or novelty assessment sends the
            retrieved excerpts of your papers — not the whole document — to a language-model
            provider so it can produce the result. Academic search sends your search terms to a
            public scholarly database.
          </p>
          <p>
            If that is not acceptable for a particular document, do not upload it.
          </p>
        </Section>

        <Section heading="Deletion">
          <p>
            Deleting a paper removes its database records and its stored file. Deleting a project
            keeps its papers and returns them to your library rather than destroying them.
          </p>
        </Section>
      </div>
    </AuthCard>
  );
}
