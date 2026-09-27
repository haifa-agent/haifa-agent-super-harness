const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../frontend/node_modules/@playwright/test",
));

const app = process.env.E2E_APP_URL;
const backend = process.env.W18_DIFFERENTIAL_BACKEND;
const evidenceRoot = process.env.W18_DIFFERENTIAL_EVIDENCE;
if (!app || !["reference", "haifa"].includes(backend) || !evidenceRoot) {
  throw new Error("Explicit W18 differential app/backend/evidence is required");
}

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

// Requests the controlled provider actually received; the runner writes its
// stdout into the same evidence directory.
function providerRequests(marker) {
  const log = path.join(evidenceRoot, "provider.stdout.log");
  if (!fs.existsSync(log)) return [];
  return fs.readFileSync(log, "utf8").split(/\r?\n/)
    .filter((line) => line.startsWith("{"))
    .map((line) => { try { return JSON.parse(line); } catch { return null; } })
    .filter((entry) => entry?.type === "provider_request" && entry.stream &&
      (entry.rawPrompt.includes(marker) || entry.prompt.includes(marker)));
}

// Production-capability gate: only a Haifa run whose real provider request
// shows the Tool was never offered is recorded as NOT_RUN for its owner.
async function notRunUnlessOffered(testInfo, requests, tool, owner, evidence) {
  const offered = requests.some((entry) => (entry.offeredTools ?? []).includes(tool));
  if (offered) return false;
  expect(requests.length, "the real Run must reach the provider before claiming NOT_RUN").toBeGreaterThan(0);
  expect(backend, `reference backend must offer production Tool ${tool}`).toBe("haifa");
  const reason = `${tool} is not a registered production Tool on this backend (owner ${owner})`;
  await save(testInfo, `${evidence.case}-not-run`, {
    ...evidence,
    backend,
    status: "NOT_RUN",
    owner,
    reason,
    offeredTools: [...new Set(requests.flatMap((entry) => entry.offeredTools ?? []))],
  });
  testInfo.annotations.push({ type: "NOT_RUN", description: reason });
  return true;
}

async function sendAndCaptureRun(page, input, text) {
  const streamPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 },
  );
  await input.fill(text);
  await input.press("Enter");
  const stream = await streamPromise;
  if (stream.status() !== 200) {
    throw new Error(`Run stream returned ${stream.status()}: ${await stream.text()}`);
  }
  const location = stream.headers()["content-location"];
  expect(location).toMatch(/\/api\/threads\/[^/]+\/runs\/[^/]+$/);
  const [, threadId, runId] = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  return { stream, location, threadId, runId };
}

async function pollRunStatus(context, threadId, runId, expected, timeout = 20_000) {
  let run;
  await expect.poll(async () => {
    const response = await context.request.get(`${app}/api/langgraph/threads/${threadId}/runs/${runId}`);
    expect(response.status(), await response.text()).toBe(200);
    run = await response.json();
    return run.status;
  }, { timeout, intervals: [100, 250, 500] }).toBe(expected);
  return run;
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

test(`${backend}: upload bytes round-trip through artifact download`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const suffix = `${Date.now()}`;
  const filename = `w18-upload-${suffix}.txt`;
  const bytes = Buffer.from(`W18 upload ${suffix}\nunicode: 数据 ✓\n${"0123456789abcdef".repeat(256)}\n`, "utf8");
  const input = await openChat(page);
  const uploadPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && /\/api\/threads\/[^/]+\/uploads$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 },
  );
  await page.getByLabel("Upload files").setInputFiles({ name: filename, mimeType: "text/plain", buffer: bytes });
  const runPromise = sendAndCaptureRun(page, input, `w18-upload-message-${suffix}`);
  runPromise.catch(() => {}); // awaited below; a failed upload must stay the first error
  const uploadResponse = await uploadPromise;
  if (uploadResponse.status() !== 200) {
    await save(testInfo, "upload-download-failure", {
      backend,
      case: "upload-download-hash",
      filename,
      uploadUrl: uploadResponse.url(),
      uploadStatus: uploadResponse.status(),
      uploadBody: await uploadResponse.text(),
      pageUrl: page.url(),
      forbiddenBrowserTargets: targets.forbidden,
    });
  }
  expect(uploadResponse.status(), await uploadResponse.text()).toBe(200);
  const upload = await uploadResponse.json();
  const uploaded = upload.files.find((file) => file.filename === filename);
  expect(uploaded, JSON.stringify(upload)).toBeTruthy();
  expect(uploaded.virtual_path).toBe(`/mnt/user-data/uploads/${filename}`);
  const { threadId, runId } = await runPromise;
  const run = await pollRunStatus(context, threadId, runId, "success");
  expect(uploaded.artifact_url).toBe(`/api/threads/${threadId}/artifacts/mnt/user-data/uploads/${filename}`);

  const listed = await context.request.get(`${app}/api/threads/${threadId}/uploads/list`);
  expect(listed.status(), await listed.text()).toBe(200);
  const listing = await listed.json();
  expect(listing.files.map((file) => file.filename)).toContain(filename);

  const artifactUrl = `${app}${uploaded.artifact_url}`;
  const full = await context.request.get(artifactUrl);
  expect(full.status(), await full.text()).toBe(200);
  const fullBody = await full.body();
  const range = await context.request.get(artifactUrl, { headers: { Range: "bytes=0-15" } });
  expect(range.status()).toBe(206);
  expect(range.headers()["content-range"]).toBe(`bytes 0-15/${bytes.length}`);
  expect(Buffer.compare(await range.body(), bytes.subarray(0, 16))).toBe(0);
  const download = await context.request.get(`${artifactUrl}?download=true`);
  expect(download.status()).toBe(200);
  expect(download.headers()["content-disposition"]).toContain("attachment");
  const downloadBody = await download.body();
  expect(sha256(fullBody)).toBe(sha256(bytes));
  expect(sha256(downloadBody)).toBe(sha256(bytes));
  expect(targets.forbidden).toEqual([]);
  await save(testInfo, "upload-download", {
    backend,
    case: "upload-download-hash",
    threadId,
    runId,
    terminalStatus: run.status,
    filename,
    uploadUrl: uploadResponse.url(),
    uploadResponse: upload,
    listedFilenames: listing.files.map((file) => file.filename),
    artifactUrl: uploaded.artifact_url,
    sourceSha256: sha256(bytes),
    fullSha256: sha256(fullBody),
    downloadSha256: sha256(downloadBody),
    byteLength: bytes.length,
    range: { status: range.status(), contentRange: range.headers()["content-range"] },
    etag: full.headers().etag ?? null,
    downloadDisposition: download.headers()["content-disposition"],
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: uploaded file reaches the Run model context`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const suffix = `${Date.now()}`;
  const filename = `w18-context-${suffix}.txt`;
  const marker = `w18-context-message-${suffix}`;
  const input = await openChat(page);
  // Create the Thread first so this case isolates model-context consumption;
  // first-message upload into a not-yet-created Thread is the previous case.
  const opener = await sendAndCaptureRun(page, input, `w18-context-open-${suffix}`);
  await pollRunStatus(context, opener.threadId, opener.runId, "success");
  await expect(page.locator("#chat").getByText(`Controlled provider: w18-context-open-${suffix}`, { exact: true })).toBeVisible();
  const uploadPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && /\/api\/threads\/[^/]+\/uploads$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 },
  );
  await page.getByLabel("Upload files").setInputFiles({
    name: filename, mimeType: "text/plain", buffer: Buffer.from(`context ${suffix}\n`, "utf8"),
  });
  const { threadId, runId } = await sendAndCaptureRun(page, input, marker);
  const uploadResponse = await uploadPromise;
  expect(uploadResponse.status(), await uploadResponse.text()).toBe(200);
  expect(threadId).toBe(opener.threadId);
  const run = await pollRunStatus(context, threadId, runId, "success");
  const requests = providerRequests(marker);
  const seenPaths = [...new Set(requests.flatMap((entry) => entry.uploadPaths ?? []))];
  await save(testInfo, "upload-model-context", {
    backend,
    case: "upload-model-context",
    threadId,
    runId,
    terminalStatus: run.status,
    filename,
    providerRequests: requests.length,
    uploadPathsSeenByModel: seenPaths,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
  expect(requests.length).toBeGreaterThan(0);
  expect(targets.forbidden).toEqual([]);
  expect(seenPaths, "the model context must reference the uploaded virtual path").toContain(`/mnt/user-data/uploads/${filename}`);
});

test(`${backend}: production file Tool output downloads with matching hash`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const prompt = `w18-file-tool-${Date.now()}`;
  const outputPath = `/mnt/user-data/outputs/${prompt}.md`;
  // Must match the provider's single-line write_file content byte for byte.
  const expected = Buffer.from(`W18 ${prompt} produced by write_file 数据 ✓`, "utf8");
  const input = await openChat(page);
  const { stream, threadId, runId } = await sendAndCaptureRun(page, input, prompt);
  const run = await pollRunStatus(context, threadId, runId, "success");
  const requests = providerRequests(prompt);
  if (await notRunUnlessOffered(testInfo, requests, "write_file", "W05 production Tool", {
    case: "production-file-tool", threadId, runId, terminalStatus: run.status,
  })) {
    expect(targets.forbidden).toEqual([]);
    test.skip(true, "write_file production Tool absent (W05)");
  }
  const streamText = await stream.text();
  const toolCallIds = [...new Set(streamText.match(/w18-(?:write|present)-call/g) ?? [])];
  expect(toolCallIds).toEqual(expect.arrayContaining(["w18-write-call", "w18-present-call"]));
  await expect(page.locator("#chat").getByText(`File tool done: ${prompt}`, { exact: true })).toBeVisible();
  const state = await context.request.get(`${app}/api/langgraph/threads/${threadId}/state`);
  expect(state.status(), await state.text()).toBe(200);
  const artifacts = (await state.json()).values?.artifacts ?? [];
  expect(artifacts).toContain(outputPath);
  const artifactUrl = `${app}/api/threads/${threadId}/artifacts${outputPath}`;
  const full = await context.request.get(artifactUrl);
  expect(full.status(), await full.text()).toBe(200);
  const download = await context.request.get(`${artifactUrl}?download=true`);
  expect(download.status()).toBe(200);
  expect(download.headers()["content-disposition"]).toContain("attachment");
  const fullBody = await full.body();
  const downloadBody = await download.body();
  expect(sha256(fullBody)).toBe(sha256(expected));
  expect(sha256(downloadBody)).toBe(sha256(expected));
  expect(targets.forbidden).toEqual([]);
  const screenshot = testInfo.outputPath("file-tool.png");
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("file-tool.png", { path: screenshot, contentType: "image/png" });
  await save(testInfo, "production-file-tool", {
    backend,
    case: "production-file-tool",
    status: "EXECUTED",
    threadId,
    runId,
    terminalStatus: run.status,
    toolCallIds,
    providerToolResults: requests.map((entry) => entry.toolResults),
    artifacts,
    artifactUrl: `/api/threads/${threadId}/artifacts${outputPath}`,
    expectedSha256: sha256(expected),
    fullSha256: sha256(fullBody),
    downloadSha256: sha256(downloadBody),
    streamEvents: [...new Set([...streamText.matchAll(/^event: (.+)$/gm)].map((match) => match[1]))],
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: production Interaction request and hidden response`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const prompt = `w18-interaction-${Date.now()}`;
  const input = await openChat(page);
  const first = await sendAndCaptureRun(page, input, prompt);
  // Gate on the provider request, not on a terminal status: DeerFlow ends the
  // asking Run, while a same-Run Interaction backend keeps it open.
  await expect.poll(() => providerRequests(prompt).length, { timeout: 20_000 }).toBeGreaterThan(0);
  const requests = providerRequests(prompt);
  if (await notRunUnlessOffered(testInfo, requests, "ask_clarification", "W05 production Interaction Tool", {
    case: "production-interaction", threadId: first.threadId, runId: first.runId,
    terminalStatus: (await pollRunStatus(context, first.threadId, first.runId, "success")).status,
  })) {
    expect(targets.forbidden).toEqual([]);
    test.skip(true, "ask_clarification production Tool absent (W05)");
  }
  const card = page.getByTestId("human-input-card");
  await expect(card).toBeVisible();
  const firstRunResponse = await context.request.get(`${app}/api/langgraph/threads/${first.threadId}/runs/${first.runId}`);
  expect(firstRunResponse.status()).toBe(200);
  const firstRun = await firstRunResponse.json();
  const choice = card.getByRole("button", { name: "Alpha", exact: true });
  await expect(choice).toBeEnabled();
  let hidden;
  const resumedPromise = page.waitForResponse((response) => {
    const request = response.request();
    if (request.method() !== "POST" ||
        !/\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname)) return false;
    try {
      const message = request.postDataJSON()?.input?.messages?.at(-1);
      if (message?.additional_kwargs?.human_input_response) { hidden = message; return true; }
    } catch { return false; }
    return false;
  }, { timeout: 20_000 });
  await choice.click();
  const resumed = await resumedPromise;
  expect(resumed.status(), await resumed.text()).toBe(200);
  const resumedLocation = resumed.headers()["content-location"];
  const [, resumedThreadId, resumedRunId] = resumedLocation.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(resumedThreadId).toBe(first.threadId);
  const response = hidden.additional_kwargs.human_input_response;
  expect(hidden.additional_kwargs.hide_from_ui).toBe(true);
  expect(response).toMatchObject({
    version: 1, kind: "human_input_response", response_kind: "option", value: "Alpha",
  });
  const resumedRun = await pollRunStatus(context, first.threadId, resumedRunId, "success");
  await expect(page.locator("#chat").getByText(`Interaction resolved: ${prompt}`, { exact: true })).toBeVisible();
  await page.reload({ waitUntil: "domcontentloaded" });
  const restored = page.getByTestId("human-input-card");
  await expect(restored).toBeVisible();
  await expect(restored.getByRole("button", { name: "Alpha", exact: true })).toBeDisabled();
  expect(targets.forbidden).toEqual([]);
  const screenshot = testInfo.outputPath("interaction.png");
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("interaction.png", { path: screenshot, contentType: "image/png" });
  await save(testInfo, "production-interaction", {
    backend,
    case: "production-interaction",
    status: "EXECUTED",
    threadId: first.threadId,
    requestRunId: first.runId,
    requestRunStatus: firstRun.status,
    responseRunId: resumedRunId,
    responseRunStatus: resumedRun.status,
    sameRunResumed: resumedRunId === first.runId,
    requestId: response.request_id,
    hiddenResponse: response,
    hiddenFromUi: hidden.additional_kwargs.hide_from_ui,
    cardAnsweredAfterReload: true,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: browser WebSocket with browser automation disabled`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const input = await openChat(page);
  const { threadId, runId } = await sendAndCaptureRun(page, input, `w18-ws-${Date.now()}`);
  await pollRunStatus(context, threadId, runId, "success");
  const features = await (await context.request.get(`${app}/api/features`)).json();
  // Same-origin WS exactly as browserStreamURL builds it; Next proxies upgrades.
  const outcome = await page.evaluate(async ({ threadId }) => {
    const url = `${window.location.origin.replace(/^http/i, "ws")}/api/threads/${threadId}/browser/stream?frame_format=binary`;
    return await new Promise((resolve) => {
      const events = [];
      const socket = new WebSocket(url);
      const timer = setTimeout(() => { socket.close(); resolve({ url, events, timedOut: true }); }, 10_000);
      socket.onopen = () => events.push({ type: "open" });
      socket.onmessage = (event) => events.push({ type: "message", binary: typeof event.data !== "string" });
      socket.onerror = () => events.push({ type: "error" });
      socket.onclose = (event) => {
        clearTimeout(timer);
        events.push({ type: "close", code: event.code, reason: event.reason, wasClean: event.wasClean });
        resolve({ url, events, timedOut: false });
      };
    });
  }, { threadId });
  await save(testInfo, "browser-ws-disabled", {
    backend,
    case: "browser-ws-disabled",
    threadId,
    features,
    outcome,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
  expect(targets.forbidden).toEqual([]);
  expect(outcome.timedOut, "a disabled browser stream must terminate, not hang").toBe(false);
  expect(outcome.events.some((event) => event.type === "message"), "no screencast frames while disabled").toBe(false);
  // Frozen reference oracle (observed): the gateway refuses the upgrade while
  // browser_control is disabled, so the browser never opens and reports 1006.
  expect(outcome.events.some((event) => event.type === "open"), "disabled stream must refuse the handshake").toBe(false);
  expect(outcome.events.find((event) => event.type === "close")?.code).toBe(1006);
});
