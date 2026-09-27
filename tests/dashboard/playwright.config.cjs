const path = require("node:path");
const { defineConfig, devices } = require(path.resolve(__dirname, "../../frontend/node_modules/@playwright/test"));

module.exports = defineConfig({
  testDir: __dirname,
  testMatch: "demo-dashboard.spec.cjs",
  workers: 1,
  retries: 0,
  timeout: 30_000,
  outputDir: path.resolve(__dirname, "../../target/dashboard-evidence/artifacts"),
  reporter: [["list"], ["json", { outputFile: path.resolve(__dirname, "../../target/dashboard-evidence/results.json") }]],
  use: {
    ...devices["Desktop Chrome"],
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
