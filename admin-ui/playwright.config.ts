import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests: the built admin UI against a real backend and PostgreSQL, with a
 * deterministic stub standing in for the OpenAI API (e2e/support/stub-llm.mjs), so no
 * provider is called and no API key is needed.
 *
 * Playwright starts three servers (reusing any already running, except in CI):
 *   1. the stub LLM/embedding API             http://127.0.0.1:${E2E_STUB_PORT:-18081}
 *   2. the backend (`sbt run` in open mode)    http://localhost:${E2E_BACKEND_PORT:-18080}
 *   3. the UI (`vite build` + `vite preview`, proxying /api to the backend)
 *                                              http://localhost:${E2E_UI_PORT:-14173}/admin/
 * PostgreSQL with pgvector must already be running on E2E_PG_PORT (default 15433):
 * `npm run test:e2e:db` starts a throwaway container. See CONTRIBUTING.md.
 *
 * E2E_BACKEND_CMD replaces `sbt -batch run` (run from the repository root), e.g. with
 * `java -jar target/scala-3.7.1/ragbox-assembly.jar`.
 */
const stubPort = Number(process.env.E2E_STUB_PORT ?? 18081)
const backendPort = Number(process.env.E2E_BACKEND_PORT ?? 18080)
const uiPort = Number(process.env.E2E_UI_PORT ?? 14173)
const pgPort = process.env.E2E_PG_PORT ?? '15433'

const stubUrl = `http://127.0.0.1:${stubPort}`
const backendUrl = `http://localhost:${backendPort}`
const uiUrl = `http://localhost:${uiPort}/admin/`

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'on-failure' }]],
  use: {
    baseURL: uiUrl,
    // The PWA service worker would cache the app shell across tests
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ],
  webServer: [
    {
      command: 'node e2e/support/stub-llm.mjs',
      url: `${stubUrl}/health`,
      env: { STUB_LLM_PORT: String(stubPort) },
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      timeout: 10_000
    },
    {
      command: process.env.E2E_BACKEND_CMD ?? 'sbt -batch run',
      cwd: '..',
      url: `${backendUrl}/health`,
      env: {
        SERVER_PORT: String(backendPort),
        PG_HOST: process.env.E2E_PG_HOST ?? 'localhost',
        PG_PORT: pgPort,
        PG_DATABASE: 'ragdb',
        PG_USER: 'rag',
        PG_PASSWORD: 'rag',
        AUTH_MODE: 'open',
        // Both the answer LLM and the embeddings go to the stub
        OPENAI_API_KEY: 'sk-e2e-stub-not-a-real-key',
        OPEN_AI_KEY: 'sk-e2e-stub-not-a-real-key',
        LLM_MODEL: 'openai/gpt-4o',
        LLM_BASE_URL: `${stubUrl}/v1`,
        EMBEDDING_PROVIDER: 'openai',
        EMBEDDING_MODEL: 'text-embedding-3-small',
        EMBEDDING_BASE_URL: `${stubUrl}/v1`,
        EMBEDDING_DIMENSIONS: '',
        RAG_RERANKING_STRATEGY: 'none',
        RAG_QUERY_TRANSFORM: 'none',
        // Blank out a developer's own keys and settings (empty values count as unset)
        ANTHROPIC_API_KEY: '',
        VOYAGE_API_KEY: '',
        COHERE_API_KEY: '',
        API_KEY: '',
        DATABASE_URL: ''
      },
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      // First run compiles the backend
      timeout: 600_000
    },
    {
      command: `npx vite build --logLevel warn && npx vite preview --port ${uiPort} --strictPort`,
      url: uiUrl,
      env: { RAGBOX_API_URL: backendUrl },
      reuseExistingServer: !process.env.CI,
      // vite's proxy logs an EPIPE stack for every live-update websocket a closing page drops
      stderr: 'ignore',
      timeout: 180_000
    }
  ]
})
