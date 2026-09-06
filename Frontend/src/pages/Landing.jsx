import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  ArrowRight,
  Files,
  GithubLogo,
  GoogleLogo,
  EnvelopeSimple,
  Quotes,
  Scales,
  Target,
} from '@phosphor-icons/react';

import { startSocialSignIn } from '../services/authClient';
import { Button, cx } from '../components/primitives';
import { InlineError } from '../components/states';

const PROBLEMS = [
  {
    icon: Files,
    title: 'Forty papers, three weeks',
    body: 'Reading a corpus properly takes longer than the project allows, and skimming loses the detail that matters.',
  },
  {
    icon: Quotes,
    title: 'Answers you cannot check',
    body: 'General chat tools answer from memory, invent citations, and cannot see the PDF actually sitting on your disk.',
  },
  {
    icon: Target,
    title: 'No cheap way to check novelty',
    body: 'Finding out whether an idea already exists usually happens months in, after the work is done.',
  },
];

const STEPS = [
  {
    title: 'Upload your papers',
    body: 'Text is extracted, sections are detected, and every chunk is embedded and stored with the section it came from.',
  },
  {
    title: 'Ask in plain English',
    body: 'Retrieval runs over your own corpus. Answers cite the passage behind every claim, and say so when the evidence is thin.',
  },
  {
    title: 'Compare, find gaps, check novelty',
    body: 'Gap analysis reads the limitations and future-work sections authors wrote themselves, rather than inventing gaps.',
  },
];

const EXCLUSIONS = [
  ['A numeric novelty score', 'A percentage would imply precision the evidence cannot support.'],
  ['Answers without sources', 'A claim whose citation is not in the retrieved context is dropped before you see it.'],
  ['Guesses when evidence is thin', 'Below a relevance threshold the system refuses instead of generating.'],
];

export default function Landing() {
  const [params] = useSearchParams();
  const [pending, setPending] = useState(null);
  const [error, setError] = useState(
    params.get('error') === 'signin'
      ? { message: 'Sign-in did not complete. Please try again.', retryable: false }
      : null,
  );

  const signIn = async (provider) => {
    setError(null);
    setPending(provider);
    try {
      await startSocialSignIn(provider);
    } catch {
      setError({
        message: `${provider === 'github' ? 'GitHub' : 'Google'} sign-in could not start. Please try again, or use email instead.`,
        retryable: false,
      });
      setPending(null);
    }
  };

  return (
    <div className="min-h-[100dvh] bg-[var(--surface)]">
      <header className="mx-auto flex h-16 w-full max-w-[1200px] items-center gap-3 px-5">
        <img src="/logo.svg" alt="" className="h-7 w-auto" />
        <span className="text-[0.9375rem] font-semibold tracking-tight text-[var(--text)]">RLNA</span>
        <span className="ml-2 hidden text-[0.8125rem] text-[var(--text-subtle)] sm:inline">
          Research Literature &amp; Novelty Assistant
        </span>
        <Link
          to="/login"
          className="ml-auto rounded-[var(--radius-control)] px-3 py-1.5 text-[0.8125rem] font-medium text-[var(--text-muted)] transition-colors hover:bg-[var(--surface-sunken)] hover:text-[var(--text)]"
        >
          Sign in
        </Link>
      </header>

      {/* Hero: split rather than centered, four text elements at most. */}
      <section className="mx-auto grid w-full max-w-[1200px] items-center gap-12 px-5 pb-16 pt-10 lg:grid-cols-[1.05fr_1fr] lg:gap-16 lg:pt-20">
        <div>
          <h1 className="text-[2.25rem] font-semibold leading-[1.12] tracking-tight text-[var(--text)] sm:text-[2.75rem] lg:text-[3.25rem]">
            Answers from your papers,
            <br />
            not from memory.
          </h1>
          <p className="mt-5 max-w-[46ch] text-[1.0625rem] leading-relaxed text-[var(--text-muted)]">
            Upload a corpus, search it in plain English, and get answers that cite the passage they
            came from.
          </p>

          <div className="mt-8 flex flex-col gap-3 sm:flex-row">
            <Button
              size="lg"
              variant="primary"
              icon={GoogleLogo}
              loading={pending === 'google'}
              onClick={() => signIn('google')}
            >
              Continue with Google
            </Button>
            <Button
              size="lg"
              variant="secondary"
              icon={GithubLogo}
              loading={pending === 'github'}
              onClick={() => signIn('github')}
            >
              Continue with GitHub
            </Button>
          </div>

          <div className="mt-3 flex flex-col items-start gap-2 sm:flex-row sm:items-center sm:gap-4">
            <Link to="/signup">
              <Button size="lg" variant="ghost" icon={EnvelopeSimple}>
                Sign up with email
              </Button>
            </Link>
            <span className="text-[0.8125rem] text-[var(--text-muted)]">
              Already have an account?{' '}
              <Link
                to="/login"
                className="rounded-sm font-medium text-[var(--accent)] underline-offset-2 hover:underline"
              >
                Sign in
              </Link>
            </span>
          </div>

          {error && (
            <div className="mt-4 max-w-md">
              <InlineError error={error} />
            </div>
          )}
        </div>

        <figure className="relative">
          <div className="overflow-hidden rounded-[var(--radius-panel)] border border-[var(--border)] bg-[var(--surface-raised)] shadow-sm">
            <img
              src="/product-library.png"
              alt="The RLNA library, showing indexed papers with their processing status and detected sections."
              width={1280}
              height={860}
              loading="eager"
              className="block w-full"
            />
          </div>
        </figure>
      </section>

      {/* Problem: asymmetric, not three equal cards. */}
      <section className="border-t border-[var(--border)] bg-[var(--surface-sunken)]">
        <div className="mx-auto w-full max-w-[1200px] px-5 py-16 lg:py-20">
          <h2 className="max-w-[20ch] text-[1.75rem] font-semibold leading-tight tracking-tight text-[var(--text)]">
            Starting a literature review is mostly logistics.
          </h2>
          <div className="mt-10 grid gap-px overflow-hidden rounded-[var(--radius-panel)] border border-[var(--border)] bg-[var(--border)] md:grid-cols-3">
            {PROBLEMS.map(({ icon: Icon, title, body }) => (
              <div key={title} className="bg-[var(--surface-raised)] p-6">
                <Icon size={20} className="text-[var(--accent)]" aria-hidden />
                <h3 className="mt-4 text-[0.9375rem] font-semibold text-[var(--text)]">{title}</h3>
                <p className="mt-2 text-[0.875rem] leading-relaxed text-[var(--text-muted)]">{body}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* How it works: numbered rows, a different layout family from above. */}
      <section className="mx-auto w-full max-w-[1200px] px-5 py-16 lg:py-20">
        <h2 className="max-w-[24ch] text-[1.75rem] font-semibold leading-tight tracking-tight text-[var(--text)]">
          Retrieve first, then reason only over what was retrieved.
        </h2>
        <ol className="mt-10 grid gap-10 lg:grid-cols-3">
          {STEPS.map((step, index) => (
            <li key={step.title} className="border-t-2 border-[var(--accent)] pt-5">
              <span className="font-mono text-[0.6875rem] text-[var(--text-subtle)]">
                {String(index + 1).padStart(2, '0')}
              </span>
              <h3 className="mt-2 text-[1.0625rem] font-semibold text-[var(--text)]">{step.title}</h3>
              <p className="mt-2 text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
                {step.body}
              </p>
            </li>
          ))}
        </ol>
      </section>

      {/* What it refuses to do: a full-width band, a third layout family. */}
      <section className="border-y border-[var(--border)] bg-[var(--surface-sunken)]">
        <div className="mx-auto grid w-full max-w-[1200px] gap-10 px-5 py-16 lg:grid-cols-[0.8fr_1.2fr] lg:py-20">
          <div>
            <Scales size={22} className="text-[var(--accent)]" aria-hidden />
            <h2 className="mt-4 text-[1.75rem] font-semibold leading-tight tracking-tight text-[var(--text)]">
              What it will not do
            </h2>
            <p className="mt-3 max-w-[38ch] text-[0.9375rem] leading-relaxed text-[var(--text-muted)]">
              The constraints are the feature. A tool that overstates what it knows is worse than no
              tool.
            </p>
          </div>
          <dl className="grid gap-6 sm:grid-cols-2 lg:grid-cols-1 lg:gap-5">
            {EXCLUSIONS.map(([term, reason]) => (
              <div key={term} className="border-l-2 border-[var(--border-strong)] pl-4">
                <dt className="text-[0.9375rem] font-semibold text-[var(--text)]">{term}</dt>
                <dd className="mt-1 text-[0.875rem] leading-relaxed text-[var(--text-muted)]">
                  {reason}
                </dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      <footer className="mx-auto w-full max-w-[1200px] px-5 py-14">
        <div className="flex flex-col items-start gap-6 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-[1.25rem] font-semibold tracking-tight text-[var(--text)]">
              Index your first paper in about a minute.
            </h2>
            <p className="mt-1 text-[0.875rem] text-[var(--text-muted)]">
              Your library stays yours. Nothing is shared between accounts.
            </p>
          </div>
          <Button
            size="lg"
            variant="primary"
            icon={ArrowRight}
            loading={pending === 'google'}
            onClick={() => signIn('google')}
            className={cx('shrink-0')}
          >
            Continue with Google
          </Button>
        </div>
      </footer>
    </div>
  );
}
