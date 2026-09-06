import { createAuthClient } from 'better-auth/react';

const baseURL = import.meta.env.VITE_NEON_AUTH_BASE_URL;

if (!baseURL) {
  // Failing loudly beats a sign-in button that silently does nothing.
  console.error(
    'VITE_NEON_AUTH_BASE_URL is not set. Sign-in will not work until it is configured.',
  );
}

export const authClient = createAuthClient({
  baseURL,
  // The session lives in a cross-site cookie issued by Neon Auth, so every
  // request to it has to carry credentials explicitly.
  fetchOptions: { credentials: 'include' },
});

export const { useSession, signIn, signOut } = authClient;

/** Server-enforced minimum. Shown to the user before they hit it. */
export const PASSWORD_MIN_LENGTH = 8;

let cached = null;

function expiryOf(jwt) {
  try {
    const payload = JSON.parse(atob(jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return typeof payload.exp === 'number' ? payload.exp * 1000 : 0;
  } catch {
    return 0;
  }
}

/**
 * Returns a short-lived access token for the API.
 *
 * Neon Auth holds the session in an http-only cookie; the API accepts a signed
 * token. This exchanges one for the other and caches the result until shortly
 * before it expires, so a page of API calls costs one token request rather than
 * one per call.
 */
export async function getAccessToken({ force = false } = {}) {
  const now = Date.now();
  // Refreshed a minute early so a request in flight cannot expire mid-journey.
  if (!force && cached && cached.expiresAt - 60_000 > now) {
    return cached.token;
  }
  if (!force && cached?.pending) {
    return cached.pending;
  }

  const pending = (async () => {
    const response = await fetch(`${baseURL}/token`, { credentials: 'include' });
    if (!response.ok) {
      cached = null;
      return null;
    }
    const body = await response.json();
    if (!body?.token) {
      cached = null;
      return null;
    }
    cached = { token: body.token, expiresAt: expiryOf(body.token) || now + 5 * 60_000 };
    return body.token;
  })();

  cached = { ...(cached ?? {}), pending };
  try {
    return await pending;
  } finally {
    if (cached) delete cached.pending;
  }
}

export function clearAccessToken() {
  cached = null;
}

/* -------------------------------------------------------------------------- */
/* Credential auth                                                            */
/* -------------------------------------------------------------------------- */

/**
 * The credential endpoints are called directly rather than through the client's
 * generated methods.
 *
 * Better Auth builds those methods from a proxy, so their names track the
 * server's route names and have moved between minor versions — password reset
 * is `/request-password-reset` here, while `/forget-password` (the older name)
 * returns 404. Calling the routes we actually verified against this deployment
 * keeps a library upgrade from silently breaking sign-in.
 */
async function authFetch(path, body) {
  let response;
  try {
    response = await fetch(`${baseURL}${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    throw new AuthError('NETWORK', 'Could not reach the sign-in service. Check your connection.');
  }

  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new AuthError(payload?.code || 'UNKNOWN', messageFor(payload?.code, payload?.message));
  }
  return payload;
}

export class AuthError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'AuthError';
    this.code = code;
  }
}

// Provider codes are translated once, here, so no page has to know them and the
// user never sees a raw enum.
const MESSAGES = {
  INVALID_EMAIL_OR_PASSWORD: 'That email and password do not match an account.',
  USER_ALREADY_EXISTS: 'An account with this email already exists. Try signing in instead.',
  EMAIL_NOT_VERIFIED: 'Confirm your email address before signing in. Check your inbox for the link.',
  PASSWORD_TOO_SHORT: `Use at least ${PASSWORD_MIN_LENGTH} characters.`,
  PASSWORD_TOO_LONG: 'That password is too long.',
  INVALID_TOKEN: 'This reset link has expired or has already been used. Request a new one.',
  VALIDATION_ERROR: 'Check the details you entered and try again.',
  TOO_MANY_REQUESTS: 'Too many attempts. Wait a minute and try again.',
};

function messageFor(code, fallback) {
  if (MESSAGES[code]) return MESSAGES[code];
  // A provider message is safe to show only when we have nothing better; it is
  // still a message written for an end user, not a stack trace.
  return fallback || 'Something went wrong. Please try again.';
}

/**
 * Creates an account. Returns whether a session was established.
 *
 * This deployment requires email confirmation, so the normal outcome is an
 * account with no session and a verification email in flight. The caller must
 * not assume it can navigate to the app.
 */
export async function signUpWithEmail({ name, email, password }) {
  const result = await authFetch('/sign-up/email', {
    name,
    email,
    password,
    callbackURL: `${window.location.origin}/dashboard`,
  });
  return { signedIn: Boolean(result?.token) };
}

export async function signInWithEmail({ email, password, rememberMe = true }) {
  await authFetch('/sign-in/email', { email, password, rememberMe });
}

export async function resendVerificationEmail(email) {
  await authFetch('/send-verification-email', {
    email,
    callbackURL: `${window.location.origin}/dashboard`,
  });
}

export async function requestPasswordReset(email) {
  await authFetch('/request-password-reset', {
    email,
    redirectTo: `${window.location.origin}/reset-password`,
  });
}

export async function completePasswordReset({ token, newPassword }) {
  await authFetch('/reset-password', { token, newPassword });
}

/* -------------------------------------------------------------------------- */
/* Social auth                                                                */
/* -------------------------------------------------------------------------- */

export async function startSocialSignIn(provider) {
  return authClient.signIn.social({
    provider,
    callbackURL: `${window.location.origin}/dashboard`,
    errorCallbackURL: `${window.location.origin}/?error=signin`,
  });
}

export async function endSession() {
  clearAccessToken();
  // Neon Auth owns the session, so this is the call that actually ends it.
  await authClient.signOut().catch(() => {});
}
