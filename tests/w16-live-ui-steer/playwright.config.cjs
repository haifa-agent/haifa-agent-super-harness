const { defineConfig, devices } = require('../../frontend/node_modules/@playwright/test');
const path = require('node:path');
const evidence = process.env.W18_DIFFERENTIAL_EVIDENCE;
if (!evidence) throw new Error('External evidence root required');
module.exports = defineConfig({ testDir: __dirname, testMatch: ['acceptance.spec.cjs'], workers: 1, fullyParallel: false,
  retries: 0, timeout: 420000, expect: { timeout: 20000 }, outputDir: path.join(evidence, 'artifacts'),
  reporter: [['list'], ['json', { outputFile: path.join(evidence, 'results.json') }]],
  use: { baseURL: process.env.E2E_APP_URL, ...devices['Desktop Chrome'], screenshot: 'only-on-failure', trace: 'on' },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }] });
