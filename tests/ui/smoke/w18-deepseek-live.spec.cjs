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

// W05's original controlled LIVE02 remains unchanged. This additional case
// observes a real long response without a Provider gate or response rewriting.
test("W05 real long answer grows twice while running with one user bubble and survives reload", async ({
  context,
  page,
}, testInfo) => {
  const startedAt = Date.now();
  const marker = `W05-REAL-LONG-${crypto.randomUUID()}`;
  const prompt = `${marker}: Print every integer from 1 through 300, one per line. `
    + "Write the full list with no abbreviation, commentary, code fence, or tools.";
  const samples = [];
  let identity;
  let stream;
  try {
    await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
    const input = page.locator('textarea[name="message"]');
    await expect(input).toBeVisible();
    const response = page.waitForResponse((r) =>
      r.request().method() === "POST"
      && /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(r.url()).pathname),
    { timeout: 30_000 });
    await input.fill(prompt);
    await input.press("Enter");
    stream = await response;
    expect(stream.status()).toBe(200);
    identity = runIdentity(stream);
    const user = page.locator("#chat .is-user").filter({ hasText: marker });
    // Frozen ordinary answers render inside MessageGroup's MarkdownContent,
    // including the in-flight processing panel, rather than .is-assistant.
    const assistant = page.locator("#chat [data-message-group-index]")
      .filter({ hasNot: page.locator(".is-user") });
    const answer = assistant.locator("p").filter({ hasText: /^1\s+2\s+3(?:\s|$)/ });
    await expect(user).toHaveCount(1, { timeout: 15_000 });

    async function observe() {
      const runResponse = await context.request.get(
        `${APP}/api/langgraph/threads/${identity.threadId}/runs/${identity.runId}`,
      );
      expect(runResponse.status()).toBe(200);
      const run = await runResponse.json();
      const stateResponse = await context.request.get(`${APP}/api/langgraph/threads/${identity.threadId}/state`);
      expect(stateResponse.status()).toBe(200);
      const state = await stateResponse.json();
      const rows = await answer.allInnerTexts();
      const text = rows.join("\n").replace(/\s+/g, " ").trim();
      const sample = {
        at: Date.now(), status: run.status, userCount: await user.count(),
        acceptedAssistantCount: state.values.messages.filter((m) => m.type === "ai").length,
        publicHumanIds: state.values.messages.filter((m) => m.type === "human").map((m) => m.id),
        assistantCount: rows.length, assistantGroupCount: await assistant.count(), visibleLength: text.length,
        visibleSha256: crypto.createHash("sha256").update(text).digest("hex"),
        prefix: text.slice(0, 80),
      };
      samples.push(sample);
      expect(sample.userCount, "every real in-flight sample has exactly one user bubble").toBe(1);
      return { sample, text };
    }

    let first;
    await expect.poll(async () => {
      const seen = await observe();
      if (seen.sample.status === "running" && seen.sample.acceptedAssistantCount === 0
          && /^1\s+2\s+3(?:\s|$)/.test(seen.text)) {
        first = seen;
        return true;
      }
      return false;
    }, { timeout: 15_000, intervals: [100, 150, 250] }).toBe(true);
    await expect.poll(async () => {
      const seen = await observe();
      return seen.sample.status === "running"
        && seen.sample.acceptedAssistantCount === 0
        && seen.text.startsWith(first.text)
        && seen.text.length > first.text.length;
    }, { timeout: 60_000, intervals: [100, 150, 250] }).toBe(true);
    await page.screenshot({ path: testInfo.outputPath("real-long-growing.png"), fullPage: true });

    await expect.poll(async () => {
      const response = await context.request.get(
        `${APP}/api/langgraph/threads/${identity.threadId}/runs/${identity.runId}`,
      );
      expect(response.status()).toBe(200);
      return (await response.json()).status;
    }, { timeout: 90_000, intervals: [250, 500, 1_000] }).toBe("success");
    const stateResponse = await context.request.get(`${APP}/api/langgraph/threads/${identity.threadId}/state`);
    expect(stateResponse.status()).toBe(200);
    const state = await stateResponse.json();
    const accepted = state.values.messages.filter((m) => m.type === "ai");
    expect(accepted).toHaveLength(1);
    expect(accepted[0].content.length, "actual accepted answer is long, not just a greeting").toBeGreaterThan(800);
    expect(accepted[0].content.replace(/\s+/g, " ")).toContain(first.text.replace(/\s+/g, " "));
    await expect(user).toHaveCount(1);
    await expect(assistant).toHaveCount(1);
    await expect(answer).toHaveCount(1);
    await expect(assistant.getByText(accepted[0].content, { exact: true })).toBeVisible();
    const raw = await stream.text();
    expect(raw).toContain("event: values");
    expect(raw).toContain("event: end");
    fs.writeFileSync(testInfo.outputPath("real-long-creator.sse"), raw, "utf8");
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.locator("#chat .is-user").filter({ hasText: marker })).toHaveCount(1);
    await expect(assistant).toHaveCount(1);
    await expect(answer).toHaveCount(1);
    await expect(assistant.getByText(accepted[0].content, { exact: true })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath("real-long-reloaded.png"), fullPage: true });
  } finally {
    const file = testInfo.outputPath("w05-real-long-observations.json");
    fs.writeFileSync(file, JSON.stringify({
      provider: "https://api.deepseek.com", credentialSource: "os", modelName: MODEL_NAME,
      marker, identity, samples, elapsedMilliseconds: Date.now() - startedAt,
      scope: "Actual UI and public running status; no Provider gate, response mock, or budget increase",
    }, null, 2), "utf8");
    await testInfo.attach("w05-real-long-observations.json", { path: file, contentType: "application/json" });
  }
});
