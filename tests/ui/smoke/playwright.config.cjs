const path = require("node:path");

const { defineConfig, devices } = require(
  path.resolve(__dirname, "../../../frontend/node_modules/@playwright/test"),
);

const frontendPort = process.env.E2E_FRONTEND_PORT ?? "35618";
const evidenceRoot = process.env.W18_EVIDENCE_DIR
  ? path.resolve(process.env.W18_EVIDENCE_DIR)
  : path.resolve(
      __dirname,
      `../../../local-tmp/slot-2-w18-ui-smoke/evidence/run-manual-${Date.now()}`,
    );

// A repeated pass (run-w18-ui-smoke.ps1 -Passes n) keeps its own report and artifacts; the
// evidence root stays shared because specs read runner-written files such as the Provider log.
const outputRoot = process.env.W18_PLAYWRIGHT_OUTPUT_DIR
  ? path.resolve(process.env.W18_PLAYWRIGHT_OUTPUT_DIR)
  : evidenceRoot;

module.exports = defineConfig({
  testDir: __dirname,
  testMatch: process.env.W18_PLAYWRIGHT_SPEC ?? "w18-ui-smoke.spec.cjs",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: Number(process.env.W18_PLAYWRIGHT_TIMEOUT_MS ?? "60000"),
  expect: { timeout: 20_000 },
  outputDir: path.join(outputRoot, "artifacts"),
  reporter: [
    ["list"],
    ["json", { outputFile: path.join(outputRoot, "results.json") }],
  ],
  use: {
    baseURL: process.env.E2E_APP_URL ?? `http://127.0.0.1:${frontendPort}`,
    ...devices["Desktop Chrome"],
    screenshot: "only-on-failure",
    trace: process.env.W18_PLAYWRIGHT_TRACE ?? "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
