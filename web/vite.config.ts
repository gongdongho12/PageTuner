import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      '/api': { target: process.env.PAGETUNER_WEB_API_TARGET ?? 'http://127.0.0.1:8080' },
    },
  },
  publicDir: mode === 'phone-sharing' ? false : 'public',
  build: { target: 'es2022', outDir: mode === 'phone-sharing' ? 'dist-sharing' : 'dist',
    // PDF factories fetch packaged CMaps/fonts/WASM. A LAN shell with connect-src
    // 'self' must never turn a small binary into a fetched data: URL.
    assetsInlineLimit: mode === 'phone-sharing' ? 0 : 4096,
    rollupOptions: { input: mode === 'phone-sharing' ? 'sharing.html' : { cloud: 'index.html', sharing: 'sharing.html' } } },
  test: { environment: 'node', include: ['src/**/*.test.ts', 'src/**/*.test.tsx'] },
}));
