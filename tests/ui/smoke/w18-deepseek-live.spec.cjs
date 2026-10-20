const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "35618"}`;
const MODEL_NAME = process.env.W18_MODEL_NAME ?? "w18-deepseek-live";

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2], location };
}

async function saveEvidence(testInfo, evidence) {
  const file = testInfo.outputPath("deepseek-web-l3.json");
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  await testInfo.attach("deepseek-web-l3.json", {
    path: file,
    contentType: "application/json",
  });
}

test("real DeepSeek answer is rendered, terminal, streamed, and reloadable", async ({
  context,
  page,
}, testInfo) => {
  const apiOrigins = new Set();
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith("/api/")) apiOrigins.add(url.origin);
  });

  const auth = await context.request.get(`${APP}/api/v1/auth/me`);
  expect(auth.status(), await auth.text()).toBe(200);
  const modelsResponse = await context.request.get(`${APP}/api/models`);
  expect(modelsResponse.status(), await modelsResponse.text()).toBe(200);
  const models = await modelsResponse.json();
  expect(models.models.map((model) => model.name)).toContain(MODEL_NAME);

  const startedAt = Date.now();
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  // The persisted UI locale may be English or Chinese. The frozen composer
  // keeps this stable field name across both translations.
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const prompt = "Reply briefly with a harmless greeting and the marker DEEPSEEK_WEB_L3_OK. Do not use tools.";
  const streamPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      ),
    { timeout: 30_000 },
  );
  await input.fill(prompt);
  await input.press("Enter");
  const stream = await streamPromise;
  if (stream.status() !== 200) {
    throw new Error(`run stream returned ${stream.status()}: ${await stream.text()}`);
  }
  const identity = runIdentity(stream);

  let run;
  await expect
    .poll(
      async () => {
        const response = await context.request.get(
          `${APP}/api/langgraph/threads/${identity.threadId}/runs/${identity.runId}`,
        );
        expect(response.status(), await response.text()).toBe(200);
        run = await response.json();
        return run.status;
      },
      { timeout: 90_000, intervals: [250, 500, 1_000] },
    )
    .toBe("success");

  const stateResponse = await context.request.get(
    `${APP}/api/langgraph/threads/${identity.threadId}/state`,
  );
  expect(stateResponse.status(), await stateResponse.text()).toBe(200);
  const state = await stateResponse.json();
  const assistant = [...state.values.messages]
    .reverse()
    .find((message) => message.type === "ai");
  expect(assistant?.content?.trim().length).toBeGreaterThan(0);
  await expect(
    page.locator("#chat").getByText(assistant.content, { exact: true }),
  ).toBeVisible({ timeout: 30_000 });

  const streamText = await stream.text();
  expect(streamText).toContain("event: messages");
  expect(streamText).toContain("event: end");
  const eventIds = [...streamText.matchAll(/^id: (.+)$/gm)].map(
    (match) => match[1],
  );
  expect(eventIds.length).toBeGreaterThan(0);

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(
    page.locator("#chat").getByText(assistant.content, { exact: true }),
  ).toBeVisible({ timeout: 30_000 });
  expect([...apiOrigins]).toEqual([new URL(APP).origin]);

  const screenshot = testInfo.outputPath("deepseek-web-l3.png");
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("deepseek-web-l3.png", {
    path: screenshot,
    contentType: "image/png",
  });
  await saveEvidence(testInfo, {
    provider: "https://api.deepseek.com",
    dialect: "deepseek-openai-chat",
    modelName: MODEL_NAME,
    credentialSource: "os",
    authStatus: auth.status(),
    ...identity,
    terminalStatus: run.status,
    assistantNonEmpty: true,
    assistantLength: assistant.content.length,
    assistantSha256: crypto
      .createHash("sha256")
      .update(assistant.content)
      .digest("hex"),
    streamMessageEvent: "messages",
    streamContainsEnd: true,
    eventIds,
    reloaded: true,
    apiOrigins: [...apiOrigins],
    deerFlowFallbackObserved: false,
    elapsedMilliseconds: Date.now() - startedAt,
  });
});
