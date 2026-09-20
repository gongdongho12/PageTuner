import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/live', workers: 1, timeout: 90_000,
  outputDir: '../.local/browser-results', reporter: [['list'], ['json', { outputFile: '../.local/live-results.json' }]],
  use: { baseURL: 'http://127.0.0.1:3000', trace: 'off', screenshot: 'only-on-failure',
    viewport: { width: 1280, height: 900 },
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
      (process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : undefined) } },
});
