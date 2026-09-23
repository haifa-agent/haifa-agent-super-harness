const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "53018"}`;
const MODEL_NAME = "w18-controlled";

async function expectStreamSuccess(response) {
  if (response.status() !== 200) {
    throw new Error(
      `run stream returned ${response.status()}: ${await response.text()}`,
    );
  }
}

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2], location };
}

async function pollRun(request, threadId, runId, expectedStatus) {
  const url = `${APP}/api/langgraph/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}`;
  let latest;
  await expect
    .poll(
      async () => {
        const response = await request.get(url);
        expect(response.status(), await response.text()).toBe(200);
        latest = await response.json();
        return latest.status;
      },
      { timeout: 20_000, intervals: [50, 100, 250] },
    )
    .toBe(expectedStatus);
  return latest;
}

async function saveEvidence(testInfo, name, evidence) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, {
    path: file,
    contentType: "application/json",
  });
}

async function openNewChat(page) {
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  return { input };
}

test.describe("W18 frozen UI -> Haifa local smoke", () => {
  test("no-cookie auth startup renders the real frozen chat page", async ({
    context,
    page,
  }, testInfo) => {
    const me = await context.request.get(`${APP}/api/v1/auth/me`);
    expect(me.status(), await me.text()).toBe(200);
    const principal = await me.json();
    expect(principal).toMatchObject({
      id: "default",
      email: "default@test.local",
      system_role: "admin",
      needs_setup: false,
    });
    const modelsResponse = await context.request.get(`${APP}/api/models`);
    expect(modelsResponse.status(), await modelsResponse.text()).toBe(200);
    const models = await modelsResponse.json();
    expect(models.models.map((model) => model.name)).toContain(MODEL_NAME);

    await openNewChat(page);
    const screenshot = testInfo.outputPath("auth-startup.png");
    await page.screenshot({ path: screenshot, fullPage: true });
    await testInfo.attach("auth-startup.png", {
      path: screenshot,
      contentType: "image/png",
    });
    await saveEvidence(testInfo, "auth-startup", {
      frontend: APP,
      pageUrl: page.url(),
      authRequest: `${APP}/api/v1/auth/me`,
      authStatus: me.status(),
      principal,
      executableModels: models.models.map((model) => model.name),
    });
  });

  test("creates a thread and renders a real Haifa SSE answer", async ({
    context,
    page,
  }, testInfo) => {
    const prompt = `w18-ui-success-${Date.now()}`;
    const { input } = await openNewChat(page);
    const streamResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(response.url()).pathname,
        ),
    );

    await input.fill(prompt);
    await input.press("Enter");
    const streamResponse = await streamResponsePromise;
    await expectStreamSuccess(streamResponse);
    const identity = runIdentity(streamResponse);

    await expect(page).toHaveURL(
      new RegExp(`/workspace/chats/${identity.threadId}$`),
    );
    await expect(
      page.locator("#chat").getByText(`Controlled provider: ${prompt}`, {
        exact: true,
      }),
    ).toBeVisible();
    const run = await pollRun(
      context.request,
      identity.threadId,
      identity.runId,
      "success",
    );

    // Inspect the exact stream the frozen SDK rendered. Starting a second stream
    // without Last-Event-ID intentionally exercises replay-gap recovery once the
    // bounded bridge has evicted its first events; it is not evidence about the
    // original browser projection.
    const streamText = await streamResponse.text();
    expect(streamText).toContain("event: messages");
    expect(streamText).not.toContain("event: messages-tuple");
    expect(streamText).toContain("event: end");
    const eventIds = [...streamText.matchAll(/^id: (.+)$/gm)].map(
      (match) => match[1],
    );
    expect(eventIds.length).toBeGreaterThan(0);

    const screenshot = testInfo.outputPath("run-sse-success.png");
    await page.screenshot({ path: screenshot, fullPage: true });
    await testInfo.attach("run-sse-success.png", {
      path: screenshot,
      contentType: "image/png",
    });
    await saveEvidence(testInfo, "run-sse-success", {
      prompt,
      ...identity,
      terminalStatus: run.status,
      eventIds,
      streamContainsMessageTuplePayload: true,
      streamMessageEvent: "messages",
      streamContainsEnd: true,
    });
  });

  test("stop button sends the frozen interrupt query and reaches interrupted", async ({
    context,
    page,
  }, testInfo) => {
    const { input } = await openNewChat(page);
    const streamResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(response.url()).pathname,
        ),
    );
    const cancelResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/[^/]+\/cancel$/.test(
          url.pathname,
        ) &&
        url.searchParams.get("wait") === "0" &&
        url.searchParams.get("action") === "interrupt"
      );
    });

    await input.fill("__slow__");
    await input.press("Enter");
    const streamResponse = await streamResponsePromise;
    await expectStreamSuccess(streamResponse);
    const identity = runIdentity(streamResponse);
    // waitForResponse resolves when headers arrive. The SDK stores the
    // Content-Location run id in sessionStorage on the next iterator step;
    // wait for that observable stop prerequisite before clicking the square.
    await expect
      .poll(() =>
        page.evaluate(
          ({ threadId }) => sessionStorage.getItem(`lg:stream:${threadId}`),
          identity,
        ),
      )
      .toBe(identity.runId);
    await page.getByRole("button", { name: "Submit" }).click();
    const cancelResponse = await cancelResponsePromise;
    expect(cancelResponse.status()).toBe(202);
    const run = await pollRun(
      context.request,
      identity.threadId,
      identity.runId,
      "interrupted",
    );

    const screenshot = testInfo.outputPath("cancel-interrupted.png");
    await page.screenshot({ path: screenshot, fullPage: true });
    await testInfo.attach("cancel-interrupted.png", {
      path: screenshot,
      contentType: "image/png",
    });
    await saveEvidence(testInfo, "cancel-interrupted", {
      ...identity,
      terminalStatus: run.status,
      cancelStatus: cancelResponse.status(),
      cancelUrl: cancelResponse.url(),
    });
  });

  test("reload restores the completed thread from the Haifa gateway", async ({
    context,
    page,
  }, testInfo) => {
    const prompt = `w18-ui-reload-${Date.now()}`;
    const { input } = await openNewChat(page);
    const streamResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(response.url()).pathname,
        ),
    );
    await input.fill(prompt);
    await input.press("Enter");
    const streamResponse = await streamResponsePromise;
    await expectStreamSuccess(streamResponse);
    const identity = runIdentity(streamResponse);
    await expect(
      page.locator("#chat").getByText(`Controlled provider: ${prompt}`, {
        exact: true,
      }),
    ).toBeVisible();
    await pollRun(
      context.request,
      identity.threadId,
      identity.runId,
      "success",
    );

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(
      page.locator("#chat").getByText(`Controlled provider: ${prompt}`, {
        exact: true,
      }),
    ).toBeVisible();
    await saveEvidence(testInfo, "reload-success", {
      prompt,
      ...identity,
      reloadedUrl: page.url(),
    });
  });
});
