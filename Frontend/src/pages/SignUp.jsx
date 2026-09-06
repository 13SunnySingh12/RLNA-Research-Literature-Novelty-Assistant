import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { EnvelopeSimple } from '@phosphor-icons/react';

import {
  AuthCard,
  AuthLink,
  OrDivider,
  PasswordInput,
  PasswordStrength,
  SocialButtons,
} from '../components/auth';
import { Button, Checkbox, Field, Input } from '../components/primitives';
import { InlineError } from '../components/states';
import {
  PASSWORD_MIN_LENGTH,
  resendVerificationEmail,
  signInWithEmail,
  signUpWithEmail,
  startSocialSignIn,
} from '../services/authClient';
import { useAuth } from '../context/AppProviders';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function validate({ name, email, password, confirm, accepted }) {
  const errors = {};
  if (!name.trim()) errors.name = 'Enter your name.';
  if (!email.trim()) errors.email = 'Enter your email address.';
  else if (!EMAIL_PATTERN.test(email.trim())) errors.email = 'Enter a valid email address.';
  if (!password) errors.password = 'Choose a password.';
  else if (password.length < PASSWORD_MIN_LENGTH)
    errors.password = `Use at least ${PASSWORD_MIN_LENGTH} characters.`;
  if (!confirm) errors.confirm = 'Re-enter your password.';
  else if (confirm !== password) errors.confirm = 'The two passwords do not match.';
  if (!accepted) errors.accepted = 'Please accept the terms to continue.';
  return errors;
}

export default function SignUp() {
  const navigate = useNavigate();
  const { refresh } = useAuth();

  const [form, setForm] = useState({
    name: '',
    email: '',
    password: '',
    confirm: '',
    accepted: false,
  });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [social, setSocial] = useState(null);
  const [failure, setFailure] = useState(null);
  const [created, setCreated] = useState(false);
  const [resent, setResent] = useState(false);

  const set = (key) => (event) => {
    const value = event.target.type === 'checkbox' ? event.target.checked : event.target.value;
    setForm((current) => ({ ...current, [key]: value }));
    // Clearing on edit rather than re-validating on every keystroke: telling
    // someone their password is too short while they are still typing it is
    // noise, not help.
    if (errors[key]) setErrors((current) => ({ ...current, [key]: undefined }));
  };

  const submit = async (event) => {
    event.preventDefault();
    setFailure(null);

    const found = validate(form);
    setErrors(found);
    if (Object.keys(found).some((key) => found[key])) return;

    setSubmitting(true);
    try {
      const { signedIn } = await signUpWithEmail({
        name: form.name.trim(),
        email: form.email.trim(),
        password: form.password,
      });

      if (signedIn) {
        await refresh();
        navigate('/dashboard', { replace: true });
        return;
      }

      // Verification is required on this deployment, so sign-up hands back no
      // session. Try once anyway: if verification is ever switched off, this
      // takes the user straight in instead of stranding them on a dead end.
      try {
        await signInWithEmail({ email: form.email.trim(), password: form.password });
        await refresh();
        navigate('/dashboard', { replace: true });
      } catch {
        setCreated(true);
      }
    } catch (error) {
      setFailure({ message: error.message, retryable: false });
    } finally {
      setSubmitting(false);
    }
  };

  const social_ = async (provider) => {
    setFailure(null);
    setSocial(provider);
    try {
      await startSocialSignIn(provider);
    } catch {
      setFailure({ message: 'Sign-in could not start. Please try again.', retryable: false });
      setSocial(null);
    }
  };

  if (created) {
    return (
      <AuthCard
        title="Confirm your email"
        description={`We sent a link to ${form.email.trim()}. Open it to activate your account, then sign in.`}
        footer={<AuthLink to="/login">Back to sign in</AuthLink>}
      >
        <div className="flex flex-col gap-4">
          <div className="flex items-start gap-3 rounded-[var(--radius-panel)] border border-[var(--border)] bg-[var(--surface-sunken)] p-4">
            <EnvelopeSimple size={18} className="mt-0.5 shrink-0 text-[var(--accent)]" aria-hidden />
            <p className="text-[0.8125rem] leading-relaxed text-[var(--text-muted)]">
              The link can take a minute to arrive, and it sometimes lands in spam. Your account
              exists already — you only need to confirm the address.
            </p>
          </div>
          <Button
            type="button"
            variant="secondary"
            size="lg"
            disabled={resent}
            onClick={async () => {
              await resendVerificationEmail(form.email.trim()).catch(() => {});
              setResent(true);
            }}
          >
            {resent ? 'Verification email sent' : 'Send it again'}
          </Button>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard
      title="Create your account"
      description="Index your own papers and ask questions that cite the passage behind the answer."
      footer={
        <>
          Already have an account? <AuthLink to="/login">Sign in</AuthLink>
        </>
      }
    >
      <SocialButtons pending={social} onSelect={social_} />
      <OrDivider label="or sign up with email" />

      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        <Field label="Full name" htmlFor="name" required error={errors.name}>
          <Input
            id="name"
            name="name"
            autoComplete="name"
            value={form.name}
            onChange={set('name')}
            invalid={Boolean(errors.name)}
          />
        </Field>

        <Field label="Email address" htmlFor="email" required error={errors.email}>
          <Input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            value={form.email}
            onChange={set('email')}
            invalid={Boolean(errors.email)}
          />
        </Field>

        <Field label="Password" htmlFor="password" required error={errors.password}>
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

        <Field label="Confirm password" htmlFor="confirm" required error={errors.confirm}>
          <PasswordInput
            id="confirm"
            name="confirm"
            autoComplete="new-password"
            value={form.confirm}
            onChange={set('confirm')}
            invalid={Boolean(errors.confirm)}
          />
        </Field>

        <div className="flex flex-col gap-2">
          {/* The links sit inside the label deliberately. Per the HTML spec a
              label's activation behaviour is skipped when the click lands on
              interactive content inside it, so "Terms" navigates without also
              toggling the box.

              The explicit aria-label is what a screen reader announces: name
              computation drops the text of the nested links, leaving the label
              alone reading as "I agree to the and". */}
          <Checkbox
            id="accepted"
            checked={form.accepted}
            onChange={set('accepted')}
            aria-label="I agree to the Terms and Privacy Policy"
            aria-invalid={errors.accepted ? true : undefined}
            label={
              <>
                I agree to the <AuthLink to="/terms">Terms</AuthLink> and{' '}
                <AuthLink to="/privacy">Privacy Policy</AuthLink>
              </>
            }
          />
          {errors.accepted && (
            <p role="alert" className="text-xs font-medium text-[var(--danger)]">
              {errors.accepted}
            </p>
          )}
        </div>

        {failure && <InlineError error={failure} />}

        <Button type="submit" variant="primary" size="lg" loading={submitting} className="mt-1">
          Create account
        </Button>
      </form>
    </AuthCard>
  );
}
