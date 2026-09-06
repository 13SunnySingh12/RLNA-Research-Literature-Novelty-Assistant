import { AuthProvider, ThemeProvider, ToastProvider } from './AppProviders';

/**
 * Provider order matters: authentication can raise a toast while it resolves,
 * and every surface is themed, so theme is outermost.
 */
export function AppProvidersRoot({ children }) {
  return (
    <ThemeProvider>
      <ToastProvider>
        <AuthProvider>{children}</AuthProvider>
      </ToastProvider>
    </ThemeProvider>
  );
}
