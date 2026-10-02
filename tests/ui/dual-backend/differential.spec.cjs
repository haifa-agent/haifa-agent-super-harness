const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));
const { verifySteerExchange, verifySteerTerminal } = require("./demo-l2-steer-contract.cjs");

const app = process.env.E2E_APP_URL;
const backend = process.env.W18_DIFFERENTIAL_BACKEND;
const evidenceRoot = process.env.W18_DIFFERENTIAL_EVIDENCE;
const demoRound = Number.parseInt(process.env.W18_DIFFERENTIAL_ROUND ?? "", 10);
if (!app || !["reference", "haifa"].includes(backend) || !evidenceRoot || ![1, 2].includes(demoRound)) {
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

// Frozen composer availability signal: `chat-page.tsx` derives the composer
// `status` from `thread.isLoading`, and `input-box.tsx::handleSubmit` routes
// Enter to a same-Run active Steer while that status is still "streaming"
// (and rejects a file-bearing submit with `steerPlainTextOnly` before any
// request). `PromptInputSubmit` renders SquareIcon (class `lucide-square`)
// while streaming and ArrowUpIcon once ready, so the stop icon is the frozen
// UI's own "not yet submittable" marker. Loader2Icon means submitted and
// XIcon means error, so only ArrowUpIcon is a positive ready signal.
function composerStopIcon(page) {
  return page.locator('button[type="submit"] svg.lucide-square');
}

function composerReadyIcon(page) {
  return page.locator('button[type="submit"] svg.lucide-arrow-up');
}

async function waitForComposerReady(page, { timeout = 20_000 } = {}) {
  const submit = page.locator('button[type="submit"]').first();
  const stop = composerStopIcon(page);
  const ready = composerReadyIcon(page);
  const started = Date.now();
  let observedStreaming = false;
  for (;;) {
    const streaming = (await stop.count()) > 0;
    if ((await ready.count()) > 0 && !(await submit.isDisabled())) {
      return { ready: true, observedStreaming, waitedMs: Date.now() - started };
    }
    observedStreaming = observedStreaming || streaming;
    if (Date.now() - started >= timeout) {
      return { ready: false, observedStreaming, waitedMs: Date.now() - started, timedOut: true };
    }
    await page.waitForTimeout(250);
  }
}

// Composer DOM projection for failure evidence: proves whether a staged
// attachment/text was actually sent (real upload/run POST) or left behind.
async function composerSnapshot(page) {
  const submit = page.locator('button[type="submit"]').first();
  const textarea = page.locator('textarea[name="message"]').first();
  return {
    composerStreaming: (await composerStopIcon(page).count()) > 0,
    submitDisabled: (await submit.count()) ? await submit.isDisabled().catch(() => null) : null,
    textareaValue: (await textarea.count()) ? await textarea.inputValue().catch(() => null) : null,
    stagedAttachmentCount: await page.locator('[aria-label="Remove attachment"]').count().catch(() => 0),
  };
}

function requestPostData(request) {
  try { return request.postData() ?? null; } catch { return null; }
}

// Submit one Turn from the frozen composer while observing both the new-Run
// path (LangGraph SDK `/api/langgraph/threads/{id}/runs/stream`) and the
// mid-flight Steer path (Haifa gateway `/api/threads/{id}/runs/stream`). The
// composer is bounded-waited until its own Run finished so Enter starts a new
// Run; if it is still mis-routed to Steer the raw request/response ride the
// thrown error.
async function sendAndCaptureRun(page, input, text, options = {}) {
  const { timeout = 20_000, readinessTimeout = 20_000 } = options;
  const readiness = await waitForComposerReady(page, { timeout: readinessTimeout });
  if (!readiness.ready) {
    const error = new Error(`frozen composer did not become ready within ${readinessTimeout}ms`);
    error.readiness = readiness;
    throw error;
  }
  const runStream = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout },
  );
  const steerStream = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout },
  );
  runStream.catch(() => {});
  steerStream.catch(() => {});
  await input.fill(text);
  await input.press("Enter");
  const winner = await Promise.race([
    runStream.then((response) => ({ kind: "run", response })),
    steerStream.then((response) => ({ kind: "steer", response })),
  ]);
  if (winner.kind === "steer") {
    const responseBody = await winner.response.text().catch(() => "");
    const error = new Error(
      `the Turn was routed to the active-Steer path ${new URL(winner.response.url()).pathname} ` +
      `and returned ${winner.response.status()}: ${responseBody}`);
    error.steer = {
      url: winner.response.url(),
      status: winner.response.status(),
      requestBody: requestPostData(winner.response.request()),
      responseBody,
    };
    error.readiness = readiness;
    throw error;
  }
  const stream = winner.response;
  if (stream.status() !== 200) {
    throw new Error(`Run stream returned ${stream.status()}: ${await stream.text()}`);
  }
  const location = stream.headers()["content-location"];
  expect(location).toMatch(/\/api\/threads\/[^/]+\/runs\/[^/]+$/);
  const [, threadId, runId] = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  return { stream, location, threadId, runId, readiness };
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

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected Run Content-Location: ${location}`).toBeTruthy();
  return { location, threadId: match[1], runId: match[2] };
}

function humanInputProjection(state) {
  const messages = state?.values?.messages ?? [];
  const card = messages.find((message) => message?.artifact?.human_input);
  expect(card, "authoritative state must contain the pending human-input card").toBeTruthy();
  const request = card.artifact.human_input;
  return {
    messageId: card.id ?? null,
    toolCallId: request.tool_call_id ?? card.tool_call_id ?? null,
    requestId: request.request_id ?? null,
  };
}

function authoritativeWriteProjection(state, expectedPath) {
  const messages = state?.values?.messages ?? [];
  const request = messages.find((message) =>
    message?.tool_calls?.some((call) => call.name === "write_file"),
  );
  expect(request, "state must contain the authoritative write_file request").toBeTruthy();
  const call = request.tool_calls.find((candidate) => candidate.name === "write_file");
  expect(call.args.path).toBe(expectedPath);
  const results = messages.filter(
    (message) =>
      message?.type === "tool" &&
      message?.name === "write_file" &&
      message?.tool_call_id === call.id,
  );
  expect(results, "write_file must have exactly one matching result").toHaveLength(1);
  return {
    call,
    result: results[0],
    structured: results[0].additional_kwargs?.haifa_tool_result,
  };
}

async function classifyAndSkip(testInfo, type, reason, evidence) {
  expect(["NOT_RUN", "BLOCKED_EXTERNAL", "UPSTREAM_UNSUPPORTED"]).toContain(type);
  await save(testInfo, "demo-l2-classification", { backend, round: demoRound, type, reason, ...evidence });
  testInfo.annotations.push({ type, description: reason });
  test.skip(true, reason);
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
  for (const port of [8001, 2026, 3000, 18018, 33018, 20618, 25618, 30618, 35618, 40618, 46618, 28018, 38018, 43018, 53018, 48018, 63018]) {
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
  // The next Turn must be typed only after the frozen composer finished the
  // opener Run (status back to ready); otherwise Enter is routed to the active
  // Steer path and a file-bearing submit is rejected before any upload POST.
  const preUploadReadiness = await waitForComposerReady(page);
  if (!preUploadReadiness.ready) {
    await save(testInfo, "upload-model-context-failure", {
      backend,
      case: "upload-model-context",
      filename,
      marker,
      preUploadReadiness,
      composer: await composerSnapshot(page),
      uploadPostObserved: false,
      note: "the composer never became ready after the opener Run; upload was not attempted",
    });
    throw new Error("frozen composer did not become ready before upload staging");
  }
  const uploadPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && /\/api\/threads\/[^/]+\/uploads$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 },
  );
  uploadPromise.catch(() => {});
  await page.getByLabel("Upload files").setInputFiles({
    name: filename, mimeType: "text/plain", buffer: Buffer.from(`context ${suffix}\n`, "utf8"),
  });
  let send;
  try {
    send = await sendAndCaptureRun(page, input, marker, { readinessTimeout: 5_000 });
  } catch (error) {
    // The upload POST only fires from a real normal submit; capture the
    // untouched staged file/text and the absent POST so a mis-routed or
    // rejected submit is a precise FAIL, not a bare timeout.
    await save(testInfo, "upload-model-context-failure", {
      backend,
      case: "upload-model-context",
      filename,
      marker,
      error: String(error.message),
      preUploadReadiness,
      composerReadiness: error.readiness ?? null,
      steerCapture: error.steer ?? null,
      composer: await composerSnapshot(page),
      uploadPostObserved: false,
      note: "no upload POST and no new Run stream were observed; the staged file/text are captured from the composer",
      browserApiTargets: targets.requests,
      forbiddenBrowserTargets: targets.forbidden,
    });
    await uploadPromise.catch(() => null);
    throw error;
  }
  const { threadId, runId } = send;
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
    preUploadReadiness,
    runReadiness: send.readiness,
    uploadPostObserved: true,
    uploadPostUrl: uploadResponse.url(),
    uploadPostStatus: uploadResponse.status(),
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
  let resumedStream = null;
  await expect.poll(() => providerRequests(prompt).length, { timeout: 20_000 }).toBeGreaterThan(0);
  const requests = providerRequests(prompt);
  if (await notRunUnlessOffered(testInfo, requests, "write_file", "W05 production Tool", {
    case: "production-file-tool", threadId, runId,
  })) {
    expect(targets.forbidden).toEqual([]);
    test.skip(true, "write_file production Tool absent (W05)");
  }
  if (backend === "haifa") {
    const approval = page.getByTestId("human-input-card");
    await expect(approval).toHaveCount(1);
    const resumedPromise = page.waitForResponse((response) => {
      const request = response.request();
      if (request.method() !== "POST" ||
          !/\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname)) return false;
      try {
        return Boolean(request.postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs?.human_input_response);
      } catch { return false; }
    }, { timeout: 20_000 });
    await approval.getByRole("button", { name: "Approve", exact: true }).click();
    const resumed = await resumedPromise;
    if (resumed.status() !== 200) throw new Error(`Approval resume returned ${resumed.status()}: ${await resumed.text()}`);
    expect(resumed.status()).toBe(200);
    expect(runIdentity(resumed)).toEqual({ location: stream.headers()["content-location"], threadId, runId });
    resumedStream = resumed;
  }
  const run = await pollRunStatus(context, threadId, runId, "success");
  const streamText = `${await stream.text()}\n${resumedStream ? await resumedStream.text() : ""}`;
  const toolCallIds = [...new Set(streamText.match(/w18-(?:write|present)-call/g) ?? [])];
  if (backend === "reference") {
    expect(toolCallIds).toContain("w18-write-call");
    expect(toolCallIds).toContain("w18-present-call");
  }
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

test(`${backend}: customer demo L2 approval, Artifact, reconnect, and active Steer`, async ({ context, page }, testInfo) => {
  const targets = observeBrowserTargets(page);
  const marker = `w18-demo-l2-r${demoRound}-${Date.now()}`;
  const artifactPath = `/mnt/user-data/outputs/${marker}.txt`;
  const expectedBytes = Buffer.from(`haifa-customer-demo-round-${demoRound}`, "utf8");
  const expectedSha256 = sha256(expectedBytes);
  const steerAck = `DEMO-STEER-ACK-${demoRound}`;
  const steerInstruction = `Please acknowledge DEMO-STEER-ACK-${demoRound} in this same Run without calling write_file again.`;

  const input = await openChat(page);
  const started = await sendAndCaptureRun(page, input, marker);
  const initialIdentity = runIdentity(started.stream);
  expect(initialIdentity.threadId).toBe(started.threadId);
  expect(initialIdentity.runId).toBe(started.runId);

  const card = page.getByTestId("human-input-card");
  await expect(card).toHaveCount(1);
  await expect(card).toBeVisible();
  await expect(page.getByTestId("artifact-trigger")).toHaveCount(0);

  const preStateResponse = await context.request.get(`${app}/api/langgraph/threads/${started.threadId}/state`);
  expect(preStateResponse.status(), await preStateResponse.text()).toBe(200);
  const preState = await preStateResponse.json();
  const preInteraction = humanInputProjection(preState);
  expect(preState.values?.artifacts ?? []).not.toContain(artifactPath);
  const absent = await context.request.get(`${app}/api/threads/${started.threadId}/artifacts${artifactPath}`);
  expect(absent.status()).toBe(404);

  await page.reload({ waitUntil: "domcontentloaded" });
  const restoredCard = page.getByTestId("human-input-card");
  await expect(restoredCard).toHaveCount(1);
  await expect(restoredCard).toBeVisible();
  const restoredStateResponse = await context.request.get(`${app}/api/langgraph/threads/${started.threadId}/state`);
  expect(restoredStateResponse.status(), await restoredStateResponse.text()).toBe(200);
  const restoredInteraction = humanInputProjection(await restoredStateResponse.json());
  expect(restoredInteraction).toEqual(preInteraction);
  await expect(page.getByTestId("artifact-trigger")).toHaveCount(0);

  let hiddenResponse;
  const resumedPromise = page.waitForResponse((response) => {
    const request = response.request();
    if (request.method() !== "POST" ||
        !/\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname)) {
      return false;
    }
    try {
      const message = request.postDataJSON()?.input?.messages?.at(-1);
      if (message?.additional_kwargs?.human_input_response) {
        hiddenResponse = message.additional_kwargs.human_input_response;
        return true;
      }
    } catch { return false; }
    return false;
  }, { timeout: 20_000 });
  const approve = restoredCard.getByRole("button", { name: "Approve", exact: true });
  await expect(approve).toBeEnabled();
  await approve.click();
  const resumed = await resumedPromise;
  if (resumed.status() !== 200) throw new Error(`Approval resume returned ${resumed.status()}: ${await resumed.text()}`);
  expect(resumed.status()).toBe(200);
  const resumedIdentity = runIdentity(resumed);
  expect(resumedIdentity.threadId).toBe(started.threadId);

  if (resumedIdentity.runId !== started.runId) {
    await classifyAndSkip(
      testInfo,
      "UPSTREAM_UNSUPPORTED",
      "frozen reference Interaction approval resumes in a distinct Run; the M3 same-Run chain requires main-controller/human scope arbitration",
      {
        marker,
        artifactPath,
        initialRunId: started.runId,
        resumedRunId: resumedIdentity.runId,
        interaction: preInteraction,
        hiddenResponse,
        fileAbsentBeforeApproval: true,
        artifactAbsentBeforeApproval: true,
        browserApiTargets: targets.requests,
        forbiddenBrowserTargets: targets.forbidden,
      },
    );
  }

  expect(resumedIdentity.runId).toBe(started.runId);
  let postState;
  await expect.poll(async () => {
    const response = await context.request.get(`${app}/api/langgraph/threads/${started.threadId}/state`);
    expect(response.status(), await response.text()).toBe(200);
    postState = await response.json();
    return postState.values?.artifacts ?? [];
  }, { timeout: 10_000, intervals: [100, 250, 500] }).toEqual([artifactPath]);
  const projection = authoritativeWriteProjection(postState, artifactPath);
  expect(projection.structured).toMatchObject({
    path: artifactPath,
    sha256: expectedSha256,
    artifactSha256: `sha256:${expectedSha256}`,
    artifactByteCount: expectedBytes.length,
    runId: started.runId,
    threadId: started.threadId,
  });
  expect(projection.structured.artifactId).toBeTruthy();
  expect(projection.structured.artifactExecutionRef).toBe(projection.call.id);
  const existingMessageIdsBeforeSteer = (postState.values?.messages ?? [])
    .map((message) => message?.id)
    .filter((id) => typeof id === "string" && id.length > 0);

  const artifactUrl = `${app}/api/threads/${started.threadId}/artifacts${artifactPath}`;
  const full = await context.request.get(artifactUrl);
  expect(full.status(), await full.text()).toBe(200);
  const range = await context.request.get(artifactUrl, { headers: { Range: "bytes=0-7" } });
  expect(range.status()).toBe(206);
  expect(range.headers()["content-range"]).toBe(`bytes 0-7/${expectedBytes.length}`);
  const downloaded = await context.request.get(`${artifactUrl}?download=true`);
  expect(downloaded.status()).toBe(200);
  const downloadedBytes = await downloaded.body();
  expect(sha256(downloadedBytes)).toBe(expectedSha256);

  const liveArtifactVisible = await page.getByTestId("artifact-trigger").isVisible().catch(() => false);
  const reconnect = await context.newPage();
  await reconnect.goto(page.url(), { waitUntil: "domcontentloaded" });
  await expect(reconnect.getByTestId("artifact-trigger")).toBeVisible({ timeout: 10_000 });
  await expect(reconnect.locator("#chat").getByText(artifactPath, { exact: true })).toBeVisible();
  await expect(reconnect.getByTestId("human-input-card")).toHaveCount(1);
  await expect(
    reconnect.getByTestId("human-input-card").getByRole("button", { name: "Approve", exact: true }),
  ).toBeDisabled();
  await reconnect.getByTestId("artifact-trigger").click();
  await expect(reconnect.getByText(`${marker}.txt`, { exact: true })).toBeVisible();
  const uiDownloadPromise = reconnect.waitForEvent("download");
  await reconnect.getByRole("link", { name: "Download", exact: true }).click();
  const uiDownload = await uiDownloadPromise;
  const uiDownloadPath = await uiDownload.path();
  expect(uiDownloadPath).toBeTruthy();
  const uiDownloadBytes = fs.readFileSync(uiDownloadPath);
  expect(sha256(uiDownloadBytes)).toBe(expectedSha256);

  // Negative protocol probes execute in the frozen browser origin and do not
  // mutate the active Run: missing mode and invalid cursor must fail closed.
  const negative = await reconnect.evaluate(async ({ threadId, marker }) => {
    const url = `/api/threads/${threadId}/runs/stream`;
    const body = JSON.stringify({
      input: { messages: [{ id: `${marker}-negative`, type: "human", content: "must-not-apply" }] },
      on_disconnect: "continue",
    });
    const unmarked = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body });
    const invalidCursor = await fetch(url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-haifa-run-input": "steer",
        "last-event-id": "not-a-valid-cursor",
      },
      body,
    });
    return {
      unmarked: { status: unmarked.status, body: await unmarked.text() },
      invalidCursor: { status: invalidCursor.status, body: await invalidCursor.text() },
    };
  }, { threadId: started.threadId, marker });
  expect(negative.unmarked.status).toBe(409);
  expect(negative.invalidCursor.status).toBe(409);

  const activeBeforeSteer = await context.request.get(`${app}${started.location}`);
  expect(activeBeforeSteer.status(), await activeBeforeSteer.text()).toBe(200);
  const activeSnapshot = await activeBeforeSteer.json();
  expect(activeSnapshot.status).toBe("running");

  // Exercise the actual frozen composer. Both same-origin paths are visible
  // to the frozen UI, but the captured exchange must prove explicit Steer
  // semantics; merely observing a POST is never sufficient for PASS.
  const steerResponsePromise = reconnect.waitForResponse((response) => {
    const request = response.request();
    const pathname = new URL(response.url()).pathname;
    return request.method() === "POST" &&
      pathname.endsWith(`/threads/${started.threadId}/runs/stream`);
  }, { timeout: 10_000 }).catch(() => null);
  const steerInput = reconnect.locator('textarea[name="message"]');
  await expect(steerInput).toBeVisible();
  await steerInput.fill(steerInstruction);
  await steerInput.press("Enter");
  const steerResponse = await steerResponsePromise;
  const toastTexts = await reconnect.locator("[data-sonner-toast]").allTextContents();
  const screenshot = testInfo.outputPath("demo-l2-steer-window.png");
  await reconnect.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("demo-l2-steer-window.png", { path: screenshot, contentType: "image/png" });

  if (steerResponse === null) {
    await save(testInfo, "demo-l2-chain", {
      backend,
      round: demoRound,
      marker,
      artifactPath,
      threadId: started.threadId,
      runId: started.runId,
      toolCallId: projection.call.id,
      artifactId: projection.structured.artifactId,
      activeStatusBeforeSteer: activeSnapshot.status,
      steerInstruction,
      steerRequestObserved: false,
      toastTexts,
      negative,
      browserApiTargets: targets.requests,
      forbiddenBrowserTargets: targets.forbidden,
    });
    expect(
      steerResponse,
      "the frozen UI must emit an explicit same-Run Steer request while the Run is active",
    ).not.toBeNull();
  }

  const steerRequest = steerResponse.request();
  const steerExchange = verifySteerExchange({
    requestUrl: steerResponse.url(),
    requestMethod: steerRequest.method(),
    requestHeaders: steerRequest.headers(),
    requestBody: steerRequest.postData(),
    responseStatus: steerResponse.status(),
    responseHeaders: steerResponse.headers(),
    expectedThreadId: started.threadId,
    expectedRunId: started.runId,
    expectedInstruction: steerInstruction,
    existingMessageIds: existingMessageIdsBeforeSteer,
  });

  const terminalRun = await pollRunStatus(context, started.threadId, started.runId, "success", 40_000);
  const terminalStateResponse = await context.request.get(`${app}/api/langgraph/threads/${started.threadId}/state`);
  expect(terminalStateResponse.status(), await terminalStateResponse.text()).toBe(200);
  const terminalState = await terminalStateResponse.json();
  const terminalContract = verifySteerTerminal({
    runStatus: terminalRun.status,
    state: terminalState,
    expectedRunId: started.runId,
    expectedInstruction: steerInstruction,
    expectedAck: steerAck,
    expectedArtifactPath: artifactPath,
    expectedArtifactId: projection.structured.artifactId,
    expectedToolCallId: projection.call.id,
  });
  const terminalProjection = authoritativeWriteProjection(terminalState, artifactPath);
  expect(terminalProjection.call.id).toBe(projection.call.id);
  expect(terminalProjection.result.id).toBe(projection.result.id);
  expect(terminalProjection.structured.artifactId).toBe(projection.structured.artifactId);
  const terminalArtifact = await context.request.get(artifactUrl);
  expect(terminalArtifact.status(), await terminalArtifact.text()).toBe(200);
  const terminalArtifactBytes = await terminalArtifact.body();
  expect(sha256(terminalArtifactBytes)).toBe(expectedSha256);

  await save(testInfo, "demo-l2-chain", {
    backend,
    round: demoRound,
    marker,
    artifactPath,
    threadId: started.threadId,
    runId: started.runId,
    runLocation: started.location,
    interaction: preInteraction,
    resumedRunId: resumedIdentity.runId,
    sameRunApprovalResume: resumedIdentity.runId === started.runId,
    hiddenResponse,
    fileAbsentBeforeApproval: true,
    artifactAbsentBeforeApproval: true,
    toolCallId: projection.call.id,
    toolResultMessageId: projection.result.id,
    artifactId: projection.structured.artifactId,
    artifactExecutionRef: projection.structured.artifactExecutionRef,
    liveArtifactVisibleBeforeReconnect: liveArtifactVisible,
    artifactVisibleAfterReconnect: true,
    expectedSha256,
    downloadSha256: sha256(downloadedBytes),
    uiDownloadSha256: sha256(uiDownloadBytes),
    range: { status: range.status(), contentRange: range.headers()["content-range"] },
    activeStatusBeforeSteer: activeSnapshot.status,
    steerInstruction,
    steerRequestObserved: true,
    steerEndpoint: steerExchange.endpointPath,
    steerMessageId: steerExchange.messageId,
    steerStatus: steerResponse.status(),
    steerContentLocation: steerExchange.location,
    terminalStatus: terminalRun.status,
    terminalSteerMessageId: terminalContract.steerMessage.id,
    terminalAckMessageId: terminalContract.acknowledgement.id,
    terminalArtifactSha256: sha256(terminalArtifactBytes),
    toastTexts,
    negative,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
  expect(targets.forbidden).toEqual([]);
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
