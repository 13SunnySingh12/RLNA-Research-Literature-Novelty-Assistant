import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { AuthCard, AuthLink, PasswordInput, PasswordStrength } from '../components/auth';
import { Button, Field } from '../components/primitives';
import { InlineError } from '../components/states';
import { PASSWORD_MIN_LENGTH, completePasswordReset } from '../services/authClient';

export default function ResetPassword() {
  const [params] = useSearchParams();
  // Better Auth appends the token to the redirect it was given. An `error`
  // parameter means it rejected the token before we ever got here.
  const token = params.get('token');
  const rejected = params.get('error');

  const [form, setForm] = useState({ password: '', confirm: '' });
  const [errors, setErrors] = useState({});
  const [failure, setFailure] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const set = (key) => (event) => {
    setForm((current) => ({ ...current, [key]: event.target.value }));
    if (errors[key]) setErrors((current) => ({ ...current, [key]: undefined }));
  };

  const submit = async (event) => {
    event.preventDefault();
    setFailure(null);

    const found = {};
    if (!form.password) found.password = 'Choose a new password.';
    else if (form.password.length < PASSWORD_MIN_LENGTH)
      found.password = `Use at least ${PASSWORD_MIN_LENGTH} characters.`;
    if (form.confirm !== form.password) found.confirm = 'The two passwords do not match.';
    setErrors(found);
    if (Object.keys(found).length) return;

    setSubmitting(true);
    try {
      await completePasswordReset({ token, newPassword: form.password });
      setDone(true);
    } catch (error) {
      setFailure({ message: error.message, retryable: false });
    } finally {
      setSubmitting(false);
    }
  };

  if (!token || rejected) {
    return (
      <AuthCard
        title="This link is no longer valid"
        description="Reset links can only be used once, and they expire. Request a new one and it will work."
        footer={<AuthLink to="/login">Back to sign in</AuthLink>}
      >
        <Link to="/forgot-password">
          <Button variant="primary" size="lg" className="w-full">
            Request a new link
          </Button>
        </Link>
      </AuthCard>
    );
  }

  if (done) {
    return (
      <AuthCard
        title="Password updated"
        description="Your new password is active. Sign in with it to continue."
      >
        <Link to="/login">
          <Button variant="primary" size="lg" className="w-full">
            Go to sign in
          </Button>
        </Link>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Choose a new password" description="Pick something you have not used here before.">
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        <Field label="New password" htmlFor="password" required error={errors.password}>
          <PasswordInput
            id="password"
            name="password"
            autoComplete="new-password"
            value={form.password}
            onChange={set('password')}
            invalid={Boolean(errors.password)}
          />
          <PasswordStrength password={form.password} />
        </Field>

        <Field label="Confirm new password" htmlFor="confirm" required error={errors.confirm}>
          <PasswordInput
            id="confirm"
            name="confirm"
            autoComplete="new-password"
            value={form.confirm}
            onChange={set('confirm')}
            invalid={Boolean(errors.confirm)}
          />
        </Field>

        {failure && <InlineError error={failure} />}

        <Button type="submit" variant="primary" size="lg" loading={submitting}>
          Update password
        </Button>
      </form>
    </AuthCard>
  );
}
