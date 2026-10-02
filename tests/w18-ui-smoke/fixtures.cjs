const fs = require("node:fs");
const path = require("node:path");
const { expect, test: base } = require(
  path.resolve(__dirname, "../../frontend/node_modules/@playwright/test"),
);

const test = base.extend({
  runtime: async ({}, use) => {
    const names = [
      "W18_EVIDENCE_DIR",
      "E2E_GATEWAY_PORT",
      "E2E_FRONTEND_PORT",
      "E2E_APP_URL",
      "W18_PROVIDER_URL",
    ];
    for (const name of names) {
      if (!process.env[name])
        throw new Error(
          `Runner must export ${name}; invoke scripts/run-w18-ui-smoke.ps1.`,
        );
    }
    const evidence = path.resolve(process.env.W18_EVIDENCE_DIR);
    if (
      evidence.split(path.sep).some((part) => part.toLowerCase() === "target")
    )
      throw new Error("UI evidence must be outside target.");
    const provider = new URL(process.env.W18_PROVIDER_URL);
    if (
      provider.protocol !== "http:" ||
      provider.hostname !== "127.0.0.1" ||
      !provider.port
    )
      throw new Error("Controlled Provider must use loopback HTTP.");
    await use({
      evidence,
      app: process.env.E2E_APP_URL,
      gateway: `http://127.0.0.1:${process.env.E2E_GATEWAY_PORT}`,
      provider: provider.origin,
    });
  },
  workspace: async ({ page, runtime }, use) => {
    await use({
      open: async () => {
        await page.goto(`${runtime.app}/workspace/chats/new`, {
          waitUntil: "domcontentloaded",
        });
        const input = page.locator('textarea[name="message"]');
        await expect(input).toBeVisible();
        return { input };
      },
    });
  },
  runFlow: async ({ runtime }, use) => {
    await use({
      identity(response) {
        const location = response.headers()["content-location"];
        expect(
          location,
          "run stream must expose Content-Location",
        ).toBeTruthy();
        const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
        expect(match, `unexpected run location: ${location}`).toBeTruthy();
        return { threadId: match[1], runId: match[2], location };
      },
      async expectStreamSuccess(response) {
        if (response.status() !== 200)
          throw new Error(
            `run stream returned ${response.status()}: ${await response.text()}`,
          );
      },
      async poll(request, identity, expectedStatus, langgraph = false) {
        const location = langgraph
          ? `/api/langgraph/threads/${encodeURIComponent(identity.threadId)}/runs/${encodeURIComponent(identity.runId)}`
          : identity.location;
        let latest;
        try {
          await expect
            .poll(
              async () => {
                const response = await request.get(`${runtime.app}${location}`);
                expect(response.status(), await response.text()).toBe(200);
                latest = await response.json();
                return latest.status;
              },
              { timeout: 20_000, intervals: [50, 100, 250] },
            )
            .toBe(expectedStatus);
        } catch (error) {
          // Preserve the original assertion, while adding the last real observation.
          error.message += `\nRun ${identity.runId}: last observed status=${latest?.status ?? "unobserved"}`;
          throw error;
        }
        return latest;
      },
    });
  },
  approval: async ({ page }, use) => {
    await use({
      current: async () => {
        const card = page.getByTestId("human-input-card");
        await expect(card).toBeVisible();
        const approve = card.getByRole("button", {
          name: "Approve",
          exact: true,
        });
        await expect(approve).toBeEnabled();
        return { card, approve: () => approve.click() };
      },
    });
  },
  evidence: async ({ runtime }, use, testInfo) => {
    const attach = async (name, bytes, contentType) => {
      const file = testInfo.outputPath(name);
      if (!path.resolve(file).startsWith(runtime.evidence + path.sep))
        throw new Error("Attachment escaped this run's evidence directory.");
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, bytes);
      await testInfo.attach(name, { path: file, contentType });
    };
    await use({
      json: (name, value) =>
        attach(
          `${name}.json`,
          `${JSON.stringify(value, null, 2)}\n`,
          "application/json",
        ),
      bytes: (name, value) => attach(name, value, "application/octet-stream"),
      screenshot: async (page, name) => {
        const file = testInfo.outputPath(`${name}.png`);
        await page.screenshot({ path: file, fullPage: true });
        await testInfo.attach(`${name}.png`, {
          path: file,
          contentType: "image/png",
        });
      },
    });
  },
});

module.exports = { test, expect };
