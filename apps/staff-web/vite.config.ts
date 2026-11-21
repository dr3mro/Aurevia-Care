/// <reference types="vitest" />
import type { IncomingMessage, ServerResponse } from 'node:http';
import { readFileSync } from 'node:fs';
import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import {
  assignChunk,
  immutableCacheHeader,
  injectFontStylesheet,
} from './scripts/web-platform-config';

// The display name is a build-time input from the repository identity file,
// never a hard-coded string in application source.
const project = JSON.parse(
  readFileSync(new URL('../../project.json', import.meta.url), 'utf8'),
) as { displayName: string };

/**
 * Add the self-hosted Arabic-coverage font stylesheet to the emitted document.
 * The faces themselves live under `public/fonts`, so no CDN is contacted.
 */
function selfHostedFonts(): Plugin {
  return {
    name: 'self-hosted-fonts',
    transformIndexHtml(html: string): string {
      return injectFontStylesheet(html);
    },
  };
}

/**
 * Serve content-hashed assets as immutable while leaving the document and
 * other URLs revalidating (WEB-08). Applies to both Vite servers.
 */
function immutableHashedAssets(): Plugin {
  const setCacheHeader = (
    request: IncomingMessage,
    response: ServerResponse,
    next: () => void,
  ): void => {
    const value = immutableCacheHeader(request.url);
    if (value !== undefined) {
      response.setHeader('Cache-Control', value);
    }
    next();
  };
  return {
    name: 'immutable-hashed-assets',
    configureServer(server) {
      server.middlewares.use(setCacheHeader);
    },
    configurePreviewServer(server) {
      server.middlewares.use(setCacheHeader);
    },
  };
}

export default defineConfig({
  plugins: [react(), selfHostedFonts(), immutableHashedAssets()],
  define: {
    __APP_DISPLAY_NAME__: JSON.stringify(project.displayName),
  },
  build: {
    rollupOptions: {
      output: {
        // Shared React packages stay in one small chunk; application modules
        // return undefined so every feature route keeps its lazy-import chunk.
        manualChunks: assignChunk,
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
    clearMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      // Generated contract types and declaration files contain no runnable
      // statements; they are produced by scripts/gen-api-types.sh and kept
      // out of the hand-written coverage scope by this documented rule.
      exclude: [
        'src/api/generated/**',
        '**/*.d.ts',
        '**/*.test.{ts,tsx}',
        'dist/**',
        'coverage/**',
      ],
      reporter: ['text', 'json-summary'],
      thresholds: {
        perFile: true,
        lines: 100,
        branches: 100,
        functions: 100,
        statements: 100,
      },
    },
  },
});
