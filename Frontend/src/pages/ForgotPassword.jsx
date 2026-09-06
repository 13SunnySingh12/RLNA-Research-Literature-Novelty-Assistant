import { useState } from 'react';
import { EnvelopeSimple } from '@phosphor-icons/react';

import { AuthCard, AuthLink } from '../components/auth';
import { Button, Field, Input } from '../components/primitives';
import { InlineError } from '../components/states';
import { requestPasswordReset } from '../services/authClient';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [error, setError] = useState(null);
  const [failure, setFailure] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    setFailure(null);

    if (!email.trim()) return setError('Enter your email address.');
    if (!EMAIL_PATTERN.test(email.trim())) return setError('Enter a valid email address.');
    setError(null);

    setSubmitting(true);
    try {
      await requestPasswordReset(email.trim());
    } catch (err) {
      // Only a transport or server failure surfaces. An unknown address is not
      // an error the user should see: saying "no such account" would let anyone
      // test which emails are registered here.
      if (err.code === 'NETWORK') {
        setFailure({ message: err.message, retryable: true });
        setSubmitting(false);
        return;
      }
    }
    setSent(true);
    setSubmitting(false);
  };

  if (sent) {
    return (
      <AuthCard
        title="Check your email"
        description={`If ${email.trim()} has an account, a reset link is on its way.`}
        footer={<AuthLink to="/login">Back to sign in</AuthLink>}
      >
        <div className="flex items-start gap-3 rounded-[var(--radius-panel)] border border-[var(--border)] bg-[var(--surface-sunken)] p-4">
          <EnvelopeSimple size={18} className="mt-0.5 shrink-0 text-[var(--accent)]" aria-hidden />
          <p className="text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
            The link expires after a short while, so use it soon. If nothing arrives, check spam
            and confirm you typed the address you signed up with.
          </p>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard
      title="Reset your password"
      description="Enter your email address and we will send you a link to set a new one."
      footer={<AuthLink to="/login">Back to sign in</AuthLink>}
    >
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        <Field label="Email address" htmlFor="email" required error={error}>
          <Input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(event) => {
              setEmail(event.target.value);
              if (error) setError(null);
            }}
            invalid={Boolean(error)}
          />
        </Field>

        {/* No retry affordance: the submit button below is the retry. */}
        {failure && <InlineError error={failure} />}

        <Button type="submit" variant="primary" size="lg" loading={submitting}>
          Send reset link
        </Button>
      </form>
    </AuthCard>
  );
}
