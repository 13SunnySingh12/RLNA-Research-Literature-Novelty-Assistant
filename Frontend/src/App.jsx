import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';

import { AppProvidersRoot } from './context/root';
import { useAuth } from './context/AppProviders';
import { AppShell } from './layouts/AppShell';
import { SkeletonRows } from './components/states';
import Landing from './pages/Landing';

// Auth pages load eagerly alongside the landing page: they are the rest of the
// signed-out experience, and a spinner between "Sign in" and the form is a
// worse trade than a few kilobytes.
import Login from './pages/Login';
import SignUp from './pages/SignUp';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import { Privacy, Terms } from './pages/Legal';

// The landing page loads eagerly because it is the first paint for a signed-out
// visitor. Everything behind the sign-in wall is split out of that bundle.
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Library = lazy(() => import('./pages/Library'));
const ProjectView = lazy(() => import('./pages/ProjectView'));
const PaperDetail = lazy(() => import('./pages/PaperDetail'));
const Search = lazy(() => import('./pages/Search'));
const Ask = lazy(() => import('./pages/Ask'));
const Compare = lazy(() => import('./pages/Compare'));
const ResearchGaps = lazy(() => import('./pages/ResearchGaps'));
const NoveltyCheck = lazy(() => import('./pages/NoveltyCheck'));
const History = lazy(() => import('./pages/History'));

function RouteFallback() {
  return (
    <div className="panel overflow-hidden">
      <SkeletonRows rows={5} />
    </div>
  );
}

function Protected({ children }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <div className="flex min-h-[100dvh] items-center justify-center px-6">
        <p className="text-sm text-[var(--text-muted)]">Checking your session...</p>
      </div>
    );
  }
  if (status !== 'authenticated') {
    // Route protection here is a convenience, not the security boundary. Every
    // API call is authorized on the server regardless (Section 22.2 rule 5).
    return <Navigate to="/" replace state={{ from: location.pathname }} />;
  }
  return (
    <AppShell>
      <Suspense fallback={<RouteFallback />}>{children}</Suspense>
    </AppShell>
  );
}

function PublicOnly({ children }) {
  const { status } = useAuth();
  if (status === 'authenticated') return <Navigate to="/dashboard" replace />;
  return children;
}

export default function App() {
  return (
    <BrowserRouter>
      <AppProvidersRoot>
        <Routes>
          <Route
            path="/"
            element={
              <PublicOnly>
                <Landing />
              </PublicOnly>
            }
          />
          <Route path="/login" element={<PublicOnly><Login /></PublicOnly>} />
          <Route path="/signup" element={<PublicOnly><SignUp /></PublicOnly>} />
          <Route path="/forgot-password" element={<PublicOnly><ForgotPassword /></PublicOnly>} />
          {/* Reachable while signed out by definition — the link arrives by email. */}
          <Route path="/reset-password" element={<ResetPassword />} />
          <Route path="/terms" element={<Terms />} />
          <Route path="/privacy" element={<Privacy />} />
          <Route path="/dashboard" element={<Protected><Dashboard /></Protected>} />
          <Route path="/library" element={<Protected><Library /></Protected>} />
          <Route path="/projects/:projectId" element={<Protected><ProjectView /></Protected>} />
          <Route path="/papers/:paperId" element={<Protected><PaperDetail /></Protected>} />
          <Route path="/search" element={<Protected><Search /></Protected>} />
          <Route path="/ask" element={<Protected><Ask /></Protected>} />
          <Route path="/compare" element={<Protected><Compare /></Protected>} />
          <Route path="/gaps" element={<Protected><ResearchGaps /></Protected>} />
          <Route path="/novelty" element={<Protected><NoveltyCheck /></Protected>} />
          <Route path="/history" element={<Protected><History /></Protected>} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AppProvidersRoot>
    </BrowserRouter>
  );
}
