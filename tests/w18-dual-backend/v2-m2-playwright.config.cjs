const path = require("node:path");
const { defineConfig, devices } = require(path.resolve(__dirname, "../../frontend/node_modules/@playwright/test"));

const evidenceRoot = process.env.W18_DIFFERENTIAL_EVIDENCE;
if (!evidenceRoot || process.env.W18_MCP_SLICE !== "1") {
  throw new Error("Explicit V2-M2 MCP evidence directory and slice are required");
}

module.exports = defineConfig({
  testDir: __dirname,
  testMatch: ["v2-m2-mcp.spec.cjs"],
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 25_000 },
  outputDir: path.join(evidenceRoot, "artifacts"),
  reporter: [["list"], ["json", { outputFile: path.join(evidenceRoot, "results.json") }]],
  use: {
    baseURL: process.env.E2E_APP_URL,
    ...devices["Desktop Chrome"],
    screenshot: "only-on-failure",
    trace: "on",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
