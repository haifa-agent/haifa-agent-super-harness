const path = require("node:path");
const { defineConfig, devices } = require(path.resolve(__dirname, "../../frontend/node_modules/@playwright/test"));

const evidence = process.env.W18_DIFFERENTIAL_EVIDENCE;
if (!evidence || process.env.W18_MCP_SLICE !== "1") {
  throw new Error("W16 requires a real MCP fixture and explicit external evidence directory");
}

module.exports = defineConfig({
  testDir: __dirname,
  testMatch: ["skill-artifact.spec.cjs"],
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 30_000 },
  outputDir: path.join(evidence, "artifacts"),
  reporter: [["list"], ["json", { outputFile: path.join(evidence, "results.json") }]],
  use: {
    baseURL: process.env.E2E_APP_URL,
    ...devices["Desktop Chrome"],
    screenshot: "only-on-failure",
    trace: "on",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
