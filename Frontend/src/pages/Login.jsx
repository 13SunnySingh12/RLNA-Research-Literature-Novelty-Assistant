import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { AuthCard, AuthLink, OrDivider, PasswordInput, SocialButtons } from '../components/auth';
import { Button, Checkbox, Field, Input } from '../components/primitives';
import { InlineError } from '../components/states';
import {
  resendVerificationEmail,
  signInWithEmail,
  startSocialSignIn,
} from '../services/authClient';
import { useAuth } from '../context/AppProviders';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function Login() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { refresh } = useAuth();

  const [form, setForm] = useState({ email: '', password: '', remember: true });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [social, setSocial] = useState(null);
  const [failure, setFailure] = useState(
    params.get('error') === 'signin'
      ? { message: 'Sign-in did not complete. Please try again.', retryable: false }
      : null,
  );
  // Tracked separately from the generic failure: it is the one error with a
  // specific action attached rather than "try again".
  const [unverified, setUnverified] = useState(false);
  const [resent, setResent] = useState(false);

  const set = (key) => (event) => {
    const value = event.target.type === 'checkbox' ? event.target.checked : event.target.value;
    setForm((current) => ({ ...current, [key]: value }));
    if (errors[key]) setErrors((current) => ({ ...current, [key]: undefined }));
  };

  const submit = async (event) => {
    event.preventDefault();
    setFailure(null);
    setUnverified(false);

    const found = {};
    if (!form.email.trim()) found.email = 'Enter your email address.';
    else if (!EMAIL_PATTERN.test(form.email.trim())) found.email = 'Enter a valid email address.';
    if (!form.password) found.password = 'Enter your password.';
    setErrors(found);
    if (Object.keys(found).length) return;

    setSubmitting(true);
    try {
      await signInWithEmail({
        email: form.email.trim(),
        password: form.password,
        rememberMe: form.remember,
      });
      await refresh();
      navigate('/dashboard', { replace: true });
    } catch (error) {
      if (error.code === 'EMAIL_NOT_VERIFIED') setUnverified(true);
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

  return (
    <AuthCard
      title="Sign in"
      description="Pick up where you left off in your library."
      footer={
        <>
          New here? <AuthLink to="/signup">Create an account</AuthLink>
        </>
      }
    >
      <SocialButtons pending={social} onSelect={social_} />
      <OrDivider label="or sign in with email" />

      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
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
            autoComplete="current-password"
            value={form.password}
            onChange={set('password')}
            invalid={Boolean(errors.password)}
          />
        </Field>

        <div className="flex items-center justify-between gap-4">
          {/* Remember me maps to Better Auth's rememberMe, which decides whether
              the session cookie survives closing the browser. It is a real
              setting here, not decoration. */}
          <Checkbox
            id="remember"
            checked={form.remember}
            onChange={set('remember')}
            label="Keep me signed in"
          />
          <AuthLink to="/forgot-password">Forgot password?</AuthLink>
        </div>

        {failure && <InlineError error={failure} />}

        {unverified && (
          <Button
            type="button"
            variant="secondary"
            disabled={resent}
            onClick={async () => {
              await resendVerificationEmail(form.email.trim()).catch(() => {});
              setResent(true);
            }}
          >
            {resent ? 'Verification email sent' : 'Resend the confirmation email'}
          </Button>
        )}

        <Button type="submit" variant="primary" size="lg" loading={submitting} className="mt-1">
          Sign in
        </Button>
      </form>
    </AuthCard>
  );
}
