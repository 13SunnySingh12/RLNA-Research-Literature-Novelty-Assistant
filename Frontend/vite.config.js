import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ mode }) => {
  // Vite only exposes VITE_* to the client. The repository-root .env is read
  // here so local development has one place to configure the whole stack.
  const env = loadEnv(mode, '..', 'VITE_');

  return {
    plugins: [react(), tailwindcss()],
    envDir: '..',
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        // Same-origin in development, so the browser never has to deal with
        // preflight on every API call.
        '/api': {
          target: env.VITE_API_BASE_URL || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      rollupOptions: {
        output: {
          // Recharts is only needed on the dashboard; splitting it keeps the
          // first paint on every other route small. Declared as a function
          // because the rolldown bundler behind Vite 8 does not accept the
          // object form.
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined;
            if (id.includes('recharts') || id.includes('d3-')) return 'charts';
            if (/[\\/]node_modules[\\/](react|react-dom|react-router)/.test(id)) return 'vendor';
            return undefined;
          },
        },
      },
    },
  };
});
