/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/monaco-editor') || id.includes('node_modules/@monaco-editor')) return 'monaco';
          if (id.includes('node_modules/@ant-design/plots') || id.includes('node_modules/@antv')) return 'charts';
          if (id.includes('node_modules/antd') || id.includes('node_modules/@ant-design/icons')) return 'antd';
          if (id.includes('node_modules/react') || id.includes('node_modules/react-dom') || id.includes('node_modules/react-router')) return 'react';
        }
      }
    }
  },
  server: {
    port: 5178,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8085',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    css: false
  }
});
