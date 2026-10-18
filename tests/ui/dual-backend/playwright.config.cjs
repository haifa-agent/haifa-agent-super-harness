const path = require("node:path");

const { defineConfig, devices } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const evidenceRoot = process.env.W18_DIFFERENTIAL_EVIDENCE;
if (!evidenceRoot) throw new Error("W18_DIFFERENTIAL_EVIDENCE is required");

// The V2 differential round runs its two committed specs. A non-V2 differential
// profile (for example the W19 scheduled-tasks profile) selects exactly one spec
// through W18_DUAL_PLAYWRIGHT_SPEC, so the V2 default is never widened.
const specOverride = process.env.W18_DUAL_PLAYWRIGHT_SPEC;
const testMatch = specOverride
  ? [specOverride]
  : ["differential.spec.cjs", "v2-m1-model-file.spec.cjs"];

module.exports = defineConfig({
  testDir: __dirname,
  testMatch,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 25_000 },
  outputDir: path.join(evidenceRoot, "artifacts"),
  reporter: [
    ["list"],
    ["json", { outputFile: path.join(evidenceRoot, "results.json") }],
  ],
  use: {
    baseURL: process.env.E2E_APP_URL,
    ...devices["Desktop Chrome"],
    screenshot: "only-on-failure",
    trace: "on",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
