import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5179,
    strictPort: true,
    proxy: {
      '/data-service-admin': {
        target: 'http://localhost:8087',
        changeOrigin: true
      },
      '/openapi': {
        target: 'http://localhost:8087',
        changeOrigin: true
      }
    }
  }
});
