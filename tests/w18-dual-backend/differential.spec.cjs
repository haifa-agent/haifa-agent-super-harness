const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../frontend/node_modules/@playwright/test",
));

const app = process.env.E2E_APP_URL;
const backend = process.env.W18_DIFFERENTIAL_BACKEND;
if (!app || !["reference", "haifa"].includes(backend)) {
  throw new Error("Explicit W18 differential app/backend is required");
}

async function save(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, {
    path: file,
    contentType: "application/json",
  });
}

function observeBrowserTargets(page) {
  const requests = [];
  const forbidden = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith("/api/")) return;
    requests.push({ method: request.method(), origin: url.origin, path: url.pathname });
    if (url.origin !== app) forbidden.push(url.origin + url.pathname);
  });
  // This is a negative guard only. No response is mocked or fulfilled.
  for (const port of [8001, 2026, 3000, 18018, 28018, 38018, 43018, 53018]) {
    for (const host of ["127.0.0.1", "localhost"]) {
      page.route(`http://${host}:${port}/**`, (route) => {
        forbidden.push(route.request().url());
        return route.abort("blockedbyclient");
      });
    }
  }
  return { requests, forbidden };
}

async function openChat(page) {
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  return input;
}

test(`${backend}: no-cookie startup and same-origin API`, async ({ context, page }, testInfo) => {
  expect(await context.cookies()).toEqual([]);
  const targets = observeBrowserTargets(page);
  const setup = await context.request.get(`${app}/api/v1/auth/setup-status`);
  const me = await context.request.get(`${app}/api/v1/auth/me`);
  const models = await context.request.get(`${app}/api/models`);
  const features = await context.request.get(`${app}/api/features`);
  for (const response of [setup, me, models, features]) {
    expect(response.status(), `${response.url()}: ${await response.text()}`).toBe(200);
  }
  const principal = await me.json();
  expect(principal).toMatchObject({
    id: "default",
    email: "default@test.local",
    system_role: "admin",
    needs_setup: false,
  });
  const modelCatalog = await models.json();
  expect(modelCatalog.models.map((model) => model.name)).toContain("w18-controlled");
  await openChat(page);
  expect(targets.forbidden).toEqual([]);
  const screenshot = testInfo.outputPath("startup.png");
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("startup.png", { path: screenshot, contentType: "image/png" });
  await save(testInfo, "startup", {
    backend,
    app,
    setup: await setup.json(),
    principal,
    modelNames: modelCatalog.models.map((model) => model.name),
    featureKeys: Object.keys(await features.json()),
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: real Thread, SSE answer, and reload`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const prompt = `w18-differential-${Date.now()}`;
  const input = await openChat(page);
  const streamPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 },
  );
  await input.fill(prompt);
  await input.press("Enter");
  const stream = await streamPromise;
  if (stream.status() !== 200) {
    throw new Error(`Run stream returned ${stream.status()}: ${await stream.text()}`);
  }
  const location = stream.headers()["content-location"];
  expect(location).toMatch(/\/api\/threads\/[^/]+\/runs\/[^/]+$/);
  const [, threadId, runId] = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  await expect(page).toHaveURL(new RegExp(`/workspace/chats/${threadId}$`));
  await expect(page.locator("#chat").getByText(`Controlled provider: ${prompt}`, { exact: true })).toBeVisible();
  let run;
  await expect.poll(async () => {
    const response = await context.request.get(`${app}/api/langgraph/threads/${threadId}/runs/${runId}`);
    expect(response.status(), await response.text()).toBe(200);
    run = await response.json();
    return run.status;
  }, { timeout: 20_000, intervals: [100, 250, 500] }).toBe("success");
  const streamText = await stream.text();
  expect(streamText).toContain("event: messages");
  expect(streamText).toContain("event: end");
  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.locator("#chat").getByText(`Controlled provider: ${prompt}`, { exact: true })).toBeVisible();
  expect(targets.forbidden).toEqual([]);
  const screenshot = testInfo.outputPath("stream-reload.png");
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("stream-reload.png", { path: screenshot, contentType: "image/png" });
  await save(testInfo, "stream-reload", {
    backend,
    prompt,
    threadId,
    runId,
    terminalStatus: run.status,
    contentLocation: location,
    streamEvents: [...streamText.matchAll(/^event: (.+)$/gm)].map((match) => match[1]),
    streamEventIds: [...streamText.matchAll(/^id: (.+)$/gm)].map((match) => match[1]),
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: frozen stop control cancels a live Run`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const input = await openChat(page);
  const streamPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 },
  );
  const cancelPromise = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/[^/]+\/cancel$/.test(url.pathname) &&
      url.searchParams.get("wait") === "0" &&
      url.searchParams.get("action") === "interrupt";
  }, { timeout: 15_000 });
  await input.fill("__slow_differential__");
  await input.press("Enter");
  const stream = await streamPromise;
  if (stream.status() !== 200) {
    throw new Error(`Run stream returned ${stream.status()}: ${await stream.text()}`);
  }
  const location = stream.headers()["content-location"];
  expect(location).toMatch(/\/api\/threads\/[^/]+\/runs\/[^/]+$/);
  const [, threadId, runId] = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  // The reference UI clears its in-flight session marker immediately after a
  // short controlled run; using that private cache as a cross-backend gate
  // races the real stop button. Click the visible UI control while live.
  const stopButton = page.getByRole("button", { name: "Submit" });
  await expect(stopButton).toBeVisible();
  await stopButton.click();
  const cancelled = await cancelPromise;
  // Cancelling the active stream can discard Chromium's response body for the
  // cancel request; its observed status and persisted Run are the wire oracle.
  expect(cancelled.status()).toBe(202);
  let run;
  await expect.poll(async () => {
    const response = await context.request.get(`${app}/api/langgraph/threads/${threadId}/runs/${runId}`);
    expect(response.status(), await response.text()).toBe(200);
    run = await response.json();
    return run.status;
  }, { timeout: 20_000, intervals: [100, 250, 500] }).toBe("interrupted");
  expect(targets.forbidden).toEqual([]);
  await save(testInfo, "cancel", {
    backend,
    threadId,
    runId,
    cancelStatus: cancelled.status(),
    cancelUrl: cancelled.url(),
    terminalStatus: run.status,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: active reload rejoins the same Run`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const joins = [];
  page.on("response", (response) => {
    const request = response.request();
    if (request.method() !== "GET" ||
        !/\/api\/langgraph\/threads\/[^/]+\/runs\/[^/]+\/stream$/.test(new URL(request.url()).pathname)) {
      return;
    }
    const join = {
      url: request.url(),
      status: response.status(),
      lastEventId: request.headers()["last-event-id"] ?? null,
    };
    joins.push(join);
    response.text().then((body) => { join.body = body; }).catch((error) => { join.bodyError = String(error); });
  });
  const input = await openChat(page);
  const streamPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 },
  );
  await input.fill("__slow_differential__");
  await input.press("Enter");
  const stream = await streamPromise;
  if (stream.status() !== 200) {
    throw new Error(`Run stream returned ${stream.status()}: ${await stream.text()}`);
  }
  const location = stream.headers()["content-location"];
  expect(location).toMatch(/\/api\/threads\/[^/]+\/runs\/[^/]+$/);
  const [, threadId, runId] = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  await expect(page).toHaveURL(new RegExp(`/workspace/chats/${threadId}$`));
  const beforeResponse = await context.request.get(`${app}/api/langgraph/threads/${threadId}/runs/${runId}`);
  expect(beforeResponse.status()).toBe(200);
  const before = await beforeResponse.json();
  expect(before.status).toBe("running");
  await page.reload({ waitUntil: "domcontentloaded" });
  await expect.poll(() => joins.length, { timeout: 25_000, intervals: [100, 250, 500] }).toBeGreaterThan(0);
  expect(joins[0].status).toBe(200);
  let run;
  await expect.poll(async () => {
    const response = await context.request.get(`${app}/api/langgraph/threads/${threadId}/runs/${runId}`);
    expect(response.status()).toBe(200);
    run = await response.json();
    return run.status;
  }, { timeout: 35_000, intervals: [250, 500] }).toBe("success");
  const answer = page.locator("#chat").getByText(".".repeat(200), { exact: true });
  await expect.poll(() => answer.count(), { timeout: 30_000 }).toBeGreaterThan(0);
  const renderedOccurrences = await answer.count();
  await save(testInfo, "active-reconnect", {
    backend,
    threadId,
    runId,
    statusBeforeReload: before.status,
    terminalStatus: run.status,
    renderedOccurrences,
    joins: joins.map((join) => ({
      url: join.url,
      status: join.status,
      lastEventId: join.lastEventId,
      bodyError: join.bodyError ?? null,
      events: join.body ? [...join.body.matchAll(/^event: (.+)$/gm)].map((match) => match[1]) : null,
    })),
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
  expect(targets.forbidden).toEqual([]);
  expect(renderedOccurrences, "active replay must not duplicate the assistant answer").toBe(1);
});
