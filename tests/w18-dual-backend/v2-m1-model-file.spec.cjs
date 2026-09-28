// W18 V2-M1 differential cases (model switch A->B and file context) on the one
// frozen DeerFlow UI / dual-backend runner. Every case id is the customer-demo-v2
// Oracle case id so the runner can report reference and Haifa side by side.
//
// Rules honoured here:
//  * the frozen UI and the same controlled loopback Provider are used, never a
//    mock page or page.route().fulfill();
//  * the Provider derives every CSV value from the real read_file Tool result
//    (see loopback-openai-provider.cjs `v2_file_context`), never from a copy of
//    the fixture;
//  * the independent CSV oracle below recomputes the expected answer from the
//    fixture bytes, so a wrong row offset or a total drift cannot pass.
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

const FIXTURE = path.resolve(__dirname, "../../contracts/customer-demo-v2/fixtures/customers.csv");
const FIXTURE_SHA256 = "ec326b7d0cf68f660ed769f8eecf9b71b43137af4e9aa83de75aa75bc5302f00";
const UPLOAD_PATH = "/mnt/user-data/uploads/customers.csv";
const CSV_HEADER = "customer_id,region,amount_cny";
const CSV_ANSWER = "row17=C017 east=10000 west=11000 total=21000 high=6 rows=20";

// The pinned Oracle hash is the committed LF blob. Windows checkouts with
// core.autocrlf=true materialize the same blob as CRLF, so normalize here
// (never edit the fixture) and assert the pinned hash before uploading.
function fixtureBytes() {
  const raw = fs.readFileSync(FIXTURE);
  const lf = Buffer.from(raw.toString("utf8").replace(/\r\n/g, "\n"), "utf8");
  expect(sha256(lf), "working-tree fixture drifted from the committed LF blob").toBe(FIXTURE_SHA256);
  return lf;
}

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

// Independent oracle recomputed from the LF fixture bytes, never from a Run.
function csvDataRows(bytes) {
  const lines = bytes.toString("utf8").split("\n").filter((line) => line !== "");
  expect(lines[0]).toBe(CSV_HEADER);
  return lines.slice(1).map((line) => line.split(","));
}

function csvOracle(bytes) {
  const rows = csvDataRows(bytes);
  const sum = (region) => rows.filter((row) => row[1] === region).reduce((acc, row) => acc + Number(row[2]), 0);
  const east = sum("East");
  const west = sum("West");
  const high = rows.filter((row) => Number(row[2]) >= 1500).length;
  const answer = `row17=${rows[16][0]} east=${east} west=${west} total=${east + west} high=${high} rows=${rows.length}`;
  expect(answer).toBe(CSV_ANSWER);
  return answer;
}

function providerLog() {
  const log = path.join(evidenceRoot, "provider.stdout.log");
  if (!fs.existsSync(log)) return [];
  return fs.readFileSync(log, "utf8").split(/\r?\n/)
    .filter((line) => line.startsWith("{"))
    .map((line) => { try { return JSON.parse(line); } catch { return null; } })
    .filter(Boolean);
}

function providerRequests(marker) {
  return providerLog().filter((entry) => entry?.type === "provider_request" && entry.stream &&
    (entry.rawPrompt?.includes(marker) || entry.prompt?.includes(marker)));
}

function v2FileActions(marker) {
  return providerLog().filter((entry) => entry?.type === "v2_file_context" && entry.marker === marker);
}

async function save(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

function observeSameOrigin(page) {
  const requests = [];
  const forbidden = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith("/api/")) return;
    requests.push({ method: request.method(), origin: url.origin, path: url.pathname });
    if (url.origin !== app) forbidden.push(url.origin + url.pathname);
  });
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

function runRequestBodyModel(response) {
  try {
    const body = response.request().postDataJSON();
    return body?.context?.model_name ?? body?.config?.configurable?.model_name ?? null;
  } catch {
    return null;
  }
}

async function sendRun(page, input, text, timeout = 30_000) {
  const streamPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout });
  await input.fill(text);
  await input.press("Enter");
  const response = await streamPromise;
  if (response.status() !== 200) {
    throw new Error(`Run stream returned ${response.status()}: ${await response.text()}`);
  }
  const location = response.headers()["content-location"];
  const match = location?.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  if (!match) throw new Error(`unexpected Run Content-Location: ${location}`);
  return { response, threadId: match[1], runId: match[2], modelName: runRequestBodyModel(response) };
}

async function pollRun(request, threadId, runId, expected = "success", timeout = 40_000) {
  let latest;
  await expect.poll(async () => {
    const response = await request.get(`${app}/api/langgraph/threads/${threadId}/runs/${runId}`);
    expect(response.status(), await response.text()).toBe(200);
    latest = await response.json();
    return latest.status;
  }, { timeout, intervals: [100, 250, 500] }).toBe(expected);
  return latest;
}

async function openChat(page) {
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  // Wait until the model catalog resolved so the composer carries an explicit
  // context.model_name instead of racing the default model for the first Turn.
  await expect(modelTrigger(page).first()).toBeEnabled();
  return input;
}

function modelTrigger(page) {
  return page.locator('button[aria-haspopup="dialog"]').filter({ hasText: /W18 Controlled/ });
}

async function selectModel(page, name) {
  const trigger = modelTrigger(page);
  await expect(trigger.first()).toBeEnabled();
  await trigger.first().click();
  const option = page.getByRole("button", { name: new RegExp(`\\(${name}\\)$`) });
  await expect(option.first()).toBeVisible();
  await option.first().click();
}

// Once the real Run reaches the Provider, a missing production Tool is a
// functional failure for that backend, not an unrun case.
function assertReadFileOffered(requests) {
  expect(requests.length, "the real Run must reach the Provider").toBeGreaterThan(0);
  expect(
    requests.some((entry) => (entry.offeredTools ?? []).includes("read_file")),
    `${backend} must offer the production read_file Tool to this Run`,
  ).toBe(true);
}

async function createProject(request, name) {
  const response = await request.post(`${app}/api/projects`, { data: { name } });
  expect(response.status(), await response.text()).toBe(201);
  return await response.json();
}

async function uploadProjectDocument(request, projectId, { name, buffer, mimeType, timeout, origin }) {
  const response = await request.post(`${origin || app}/api/projects/${projectId}/documents`, {
    multipart: { file: { name, mimeType, buffer } },
    ...(timeout ? { timeout } : {}),
  });
  const text = await response.text();
  let body = null;
  try { body = JSON.parse(text); } catch { /* non-JSON error body stays as text */ }
  return { status: response.status(), body, text };
}

async function createThread(request, threadId, projectId) {
  const response = await request.post(`${app}/api/langgraph/threads`, {
    data: { thread_id: threadId, ...(projectId ? { project_id: projectId } : {}), metadata: { title: `V2M1 ${threadId}` } },
  });
  expect(response.status(), await response.text()).toBe(200);
}

async function attachDocument(request, projectId, documentId, threadId) {
  const response = await request.post(
    `${app}/api/projects/${projectId}/documents/${documentId}/attach-to-thread/${threadId}`,
  );
  return { status: response.status(), text: await response.text() };
}

async function runOnThread(page, threadId, marker) {
  await page.goto(`/workspace/chats/${threadId}`, { waitUntil: "domcontentloaded" });
  return await sendOnCurrentThread(page, `${marker}: report data row 17's customer_id and the East, West and overall amount totals`);
}

// Sends a Run from the composer currently on screen (used right after the
// frozen UI attached a Project document and navigated to the Thread, so the
// staged attachment is still part of the composer).
async function sendOnCurrentThread(page, text) {
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const run = await sendRun(page, input, text);
  await pollRun(page.context().request, run.threadId, run.runId, "success");
  return run;
}

// HTTP-direct run admission for the unavailable-model negatives: the frozen
// model picker only lists usable models, so an unknown/disabled model cannot be
// selected there. The request still targets the exact same frozen gateway
// protocol the UI uses; it is not a mock or a page.route() fulfill.
async function postRunStream(request, threadId, modelName, text, timeout = 30_000) {
  const response = await request.post(
    `${app}/api/langgraph/threads/${threadId}/runs/stream`,
    {
      data: {
        assistant_id: "lead_agent",
        input: { messages: [{ id: `${text}-human`, type: "human", content: text }] },
        context: { model_name: modelName },
        on_disconnect: "continue",
      },
      timeout,
    },
  );
  const body = await response.text();
  const location = response.headers()["content-location"];
  const match = location?.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  return { status: response.status(), body, runId: match?.[2] ?? null };
}

// The no-silent-fallback judgement for the two V2-M1-01 negatives: turn A must
// be a real Run that binds the Thread to A; a same-Thread request for the
// unavailable model must be refused explicitly and must never be served by A.
// The raw request body, response status/body and provider slice are recorded.
async function assertUnavailableModelRefused(context, page, testInfo, { caseId, artifactName, unavailableModel }) {
  const suffix = Date.now();
  const markerA = `v2-m1-avail-a-${suffix}`;
  const markerB = `v2-m1-unavail-b-${suffix}`;
  const input = await openChat(page);
  const first = await sendRun(page, input, markerA);
  await pollRun(context.request, first.threadId, first.runId, "success");
  const aRequests = providerRequests(markerA);

  const requestBody = {
    assistant_id: "lead_agent",
    input: { messages: [{ id: `${markerB}-human`, type: "human", content: markerB }] },
    context: { model_name: unavailableModel },
    on_disconnect: "continue",
  };
  const second = await postRunStream(context.request, first.threadId, unavailableModel, markerB);
  const bProviderRequests = providerLog().filter((entry) =>
    entry?.type === "provider_request" &&
    (entry.rawPrompt?.includes(markerB) || entry.prompt?.includes(markerB)));
  const servedByA = bProviderRequests.filter((entry) => entry.model === "w18-controlled-model");
  const visibleModels = (await (await context.request.get(`${app}/api/models`)).json())
    .models.map((model) => model.name);

  // Raw request body, status, response body and provider slice are written to
  // this round's external evidence BEFORE any assertion, so a failing negative
  // still leaves its original request/response on disk.
  await save(testInfo, artifactName, {
    case: caseId,
    backend,
    unavailableModel,
    disabledModelConfigured: process.env.W18_DISABLED_MODEL_CONFIGURED === "1",
    threadId: first.threadId,
    turnA: { runId: first.runId, requestedModel: first.modelName, providerModels: aRequests.map((entry) => entry.model) },
    turnB: {
      requestBody,
      status: second.status,
      responseBody: second.body.slice(0, 2000),
      providerRequestCount: bProviderRequests.length,
      servedByA: servedByA.map((entry) => entry.model),
    },
    visibleModels,
    noSilentFallbackToA: servedByA.length === 0,
  });

  // The provider slice is the authoritative evidence that turn A was served by
  // A; the request field is recorded but not the sole criterion.
  expect(
    aRequests.some((entry) => entry.model === "w18-controlled-model"),
    `turn A must reach the provider as model A: ${JSON.stringify(aRequests.map((entry) => entry.model))}`,
  ).toBe(true);
  expect(visibleModels, `${unavailableModel} must not be a selectable model`).not.toContain(unavailableModel);
  expect(servedByA, "an unavailable model must never silently fall back to A").toEqual([]);
  expect(
    second.status,
    `an unavailable model must be refused explicitly, got ${second.status}: ${second.body}`,
  ).toBeGreaterThanOrEqual(400);
}

async function readUploadLimits(request, threadId) {
  const response = await request.get(`${app}/api/threads/${threadId}/uploads/limits`);
  expect(response.status(), await response.text()).toBe(200);
  return await response.json();
}

// Shared capture of the V2-M1-05 attached-file Run. The positive case and each
// V2-M1-05 negative assert a distinct property of the same captured flow, so
// every negative gets an independent result row without re-running the UI.
async function captureOracleRun(page, context, prefix) {
  const bytes = fixtureBytes();
  const expected = csvOracle(bytes);
  const attached = await attachViaUi(page, context, prefix);
  const marker = `v2-m1-file-${Date.now()}`;
  const run = await sendOnCurrentThread(page, `${marker}: report data row 17's customer_id and the East, West and overall amount totals`);
  const requests = providerRequests(marker);
  const actions = v2FileActions(marker);
  const state = await (await context.request.get(`${app}/api/langgraph/threads/${attached.threadId}/state`)).json();
  const runMessages = (state.values?.messages ?? []).filter((message) => !message.run_id || message.run_id === run.runId);
  const call = runMessages.flatMap((message) => message.tool_calls ?? []).find((entry) => entry.name === "read_file");
  const contentResponse = await context.request.get(
    `${app}/api/projects/${attached.projectId}/documents/${attached.documentId}/content?download=true`);
  const downloaded = await contentResponse.body();
  return { bytes, expected, attached, marker, run, requests, actions, state, call, contentStatus: contentResponse.status(), downloaded };
}

test(`${backend}: V2-M1-01 same conversation Turn A then Turn B keeps Thread and run model`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const suffix = Date.now();
  const markerA = `v2-m1-a-${suffix}`;
  const markerB = `v2-m1-b-${suffix}`;
  const input = await openChat(page);

  const first = await sendRun(page, input, markerA);
  await pollRun(context.request, first.threadId, first.runId, "success");
  await expect(page.locator("#chat").getByText(`Controlled provider: ${markerA}`, { exact: true })).toBeVisible();

  await selectModel(page, "w18-controlled-b");
  let second;
  try {
    second = await sendRun(page, input, markerB);
  } catch (error) {
    await save(testInfo, "v2-m1-01-model-switch-failure", {
      case: "V2-M1-01",
      backend,
      threadId: first.threadId,
      turnA: { runId: first.runId, requestedModel: first.modelName },
      turnB: { requestedModel: "w18-controlled-b" },
      error: String(error.message),
      note: "same-Thread model switch failed; owner W03 (per-Run model freeze) is not merged at this base SHA",
    });
    throw error;
  }
  expect(second.threadId, "turn 2 must stay on the same Thread").toBe(first.threadId);
  await pollRun(context.request, second.threadId, second.runId, "success");
  await expect(page.locator("#chat").getByText(`Controlled provider: ${markerB}`, { exact: true })).toBeVisible();

  const requestsA = providerRequests(markerA);
  const requestsB = providerRequests(markerB);
  expect(requestsA.some((entry) => entry.model === "w18-controlled-model"),
    `turn A must reach the provider as model A: ${JSON.stringify(requestsA.map((e) => e.model))}`).toBe(true);
  expect(requestsB.some((entry) => entry.model === "w18-controlled-model-b"),
    `turn B must reach the provider as model B: ${JSON.stringify(requestsB.map((e) => e.model))}`).toBe(true);
  expect(first.modelName).toBe("w18-controlled");
  expect(second.modelName).toBe("w18-controlled-b");

  const stateResponse = await context.request.get(`${app}/api/langgraph/threads/${first.threadId}/state`);
  expect(stateResponse.status(), await stateResponse.text()).toBe(200);
  const state = await stateResponse.json();
  const humans = (state.values?.messages ?? []).filter((message) => message.type === "human")
    .map((message) => (typeof message.content === "string" ? message.content : JSON.stringify(message.content)));
  expect(humans.join("\n")).toContain(markerA);
  expect(humans.join("\n")).toContain(markerB);
  const answers = (state.values?.messages ?? []).filter((message) => message.type === "ai")
    .flatMap((message) => (typeof message.content === "string" ? [message.content] : []));
  expect(answers.join("\n")).toContain(`Controlled provider: ${markerA}`);
  expect(answers.join("\n")).toContain(`Controlled provider: ${markerB}`);

  expect(targets.forbidden).toEqual([]);
  await save(testInfo, "v2-m1-01-model-switch", {
    case: "V2-M1-01",
    backend,
    threadId: first.threadId,
    turnA: { runId: first.runId, requestedModel: first.modelName, providerModels: requestsA.map((e) => e.model) },
    turnB: { runId: second.runId, requestedModel: second.modelName, providerModels: requestsB.map((e) => e.model) },
    threadUnchanged: second.threadId === first.threadId,
    historyHasBothTurnsAndAnswers: true,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: V2-M1-01-N2 unknown B is refused and never silently served by A`, async ({ context, page }, testInfo) => {
  await assertUnavailableModelRefused(context, page, testInfo, {
    caseId: "V2-M1-01-N2",
    artifactName: "v2-m1-01-n2-unknown-model",
    unavailableModel: `w18-unknown-model-${Date.now()}`,
  });
});

test(`${backend}: V2-M1-01-N3 disabled B is refused and never silently served by A`, async ({ context, page }, testInfo) => {
  const disabledModel = process.env.W18_DISABLED_MODEL || "w18-controlled-b-disabled";
  test.skip(
    backend === "reference" && process.env.W18_DISABLED_MODEL_CONFIGURED !== "1",
    "this reference test profile could not configure a disabled model; the disabled precondition was not established",
  );
  await assertUnavailableModelRefused(context, page, testInfo, {
    caseId: "V2-M1-01-N3",
    artifactName: "v2-m1-01-n3-disabled-model",
    unavailableModel: disabledModel,
  });
});

test(`${backend}: V2-M1-02 active Run keeps its frozen model and refuses a mid-flight switch`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const suffix = Date.now();
  const markerB = `v2-m1-frozen-b-${suffix}`;
  const input = await openChat(page);

  const active = await sendRun(page, input, "__slow_differential__");
  expect(active.modelName).toBe("w18-controlled");

  // The frozen composer must lock the model trigger while a Run is active; no
  // second Run may be created and no B request may appear mid-flight.
  await expect(modelTrigger(page).first()).toBeDisabled();
  const bRequestsDuringActive = providerLog().filter((entry) =>
    entry.type === "provider_request" && entry.model === "w18-controlled-model-b" &&
    entry.rawPrompt?.includes("__slow_differential__"));
  expect(bRequestsDuringActive).toEqual([]);

  const activeRun = await pollRun(context.request, active.threadId, active.runId, "success", 60_000);
  const aRequests = providerLog().filter((entry) =>
    entry.type === "provider_request" && entry.stream && entry.model === "w18-controlled-model" &&
    entry.prompt === "__slow_differential__");
  expect(aRequests.length, "the active Run must be served by model A").toBeGreaterThan(0);

  await selectModel(page, "w18-controlled-b");
  let next;
  try {
    next = await sendRun(page, input, markerB);
  } catch (error) {
    await save(testInfo, "v2-m1-02-next-run-failure", {
      case: "V2-M1-02",
      backend,
      threadId: active.threadId,
      activeRunId: active.runId,
      activeRunModel: active.modelName,
      nextRequestedModel: "w18-controlled-b",
      error: String(error.message),
      note: "the follow-up Run after the active one failed; owner W03 (per-Run model freeze) is not merged at this base SHA",
    });
    throw error;
  }
  expect(next.threadId).toBe(active.threadId);
  expect(next.runId).not.toBe(active.runId);
  expect(next.modelName).toBe("w18-controlled-b");
  await pollRun(context.request, next.threadId, next.runId, "success");
  const bRequests = providerRequests(markerB);
  expect(bRequests.some((entry) => entry.model === "w18-controlled-model-b")).toBe(true);

  expect(targets.forbidden).toEqual([]);
  await save(testInfo, "v2-m1-02-active-freeze", {
    case: "V2-M1-02",
    backend,
    threadId: active.threadId,
    activeRunId: active.runId,
    activeRunModel: active.modelName,
    activeRunTerminalStatus: activeRun.status,
    triggerDisabledWhileActive: true,
    bRequestsDuringActive,
    nextRunId: next.runId,
    nextRunModel: next.modelName,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: V2-M1-03 Project upload of customers.csv stores the pinned bytes`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const bytes = fixtureBytes();
  const suffix = Date.now();
  await openChat(page);

  await page.getByTestId("projects-new-project-button").click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("input").first().fill(`V2M1 project ${suffix}`);
  const projectPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && new URL(response.url()).pathname === "/api/projects",
    { timeout: 20_000 });
  await dialog.getByRole("button", { name: "Create", exact: true }).click();
  const projectResponse = await projectPromise;
  expect(projectResponse.status(), await projectResponse.text()).toBe(201);
  const project = await projectResponse.json();

  await page.goto(`/workspace/projects/${project.id}`, { waitUntil: "domcontentloaded" });
  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-shelf")).toBeVisible();
  const uploadPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === `/api/projects/${project.id}/documents`,
    { timeout: 20_000 });
  await page.getByTestId("project-documents-upload-input").setInputFiles({
    name: "customers.csv", mimeType: "text/csv", buffer: bytes,
  });
  const uploadResponse = await uploadPromise;
  expect(uploadResponse.status(), await uploadResponse.text()).toBe(201);
  const uploaded = await uploadResponse.json();
  const document = uploaded.document;

  const contentResponse = await context.request.get(
    `${app}/api/projects/${project.id}/documents/${document.id}/content?download=true`);
  expect(contentResponse.status(), await contentResponse.text()).toBe(200);
  const downloaded = await contentResponse.body();
  expect(sha256(downloaded), "stored original bytes must equal the pinned fixture").toBe(FIXTURE_SHA256);

  const listResponse = await context.request.get(`${app}/api/projects/${project.id}/documents`);
  expect(listResponse.status(), await listResponse.text()).toBe(200);
  const listing = await listResponse.json();
  expect(listing.documents.map((entry) => entry.name)).toContain("customers.csv");

  expect(targets.forbidden).toEqual([]);
  await save(testInfo, "v2-m1-03-project-upload", {
    case: "V2-M1-03",
    backend,
    projectId: project.id,
    documentId: document.id,
    uploadStatus: uploadResponse.status(),
    fixtureSha256: FIXTURE_SHA256,
    downloadedSha256: sha256(downloaded),
    byteLength: downloaded.length,
    listedNames: listing.documents.map((entry) => entry.name),
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: V2-M1-03-N1 unsafe shelf filename is rejected`, async ({ context }, testInfo) => {
  const bytes = fixtureBytes();
  const project = await createProject(context.request, `V2M1 unsafe ${Date.now()}`);
  // Multipart parsers normalize separator-bearing file.filename to its basename
  // before this endpoint validates it. A >255-byte bare name reaches the same
  // frozen shelf validator without being silently turned into a safe basename.
  const unsafeName = `${"x".repeat(256)}.csv`;
  const upload = await uploadProjectDocument(context.request, project.id, {
    name: unsafeName, mimeType: "text/csv", buffer: bytes,
  });
  await save(testInfo, "v2-m1-03-n1-unsafe-filename", {
    case: "V2-M1-03-N1", backend, projectId: project.id, attemptedNameBytes: Buffer.byteLength(unsafeName),
    status: upload.status, referenceExpectedStatus: 400, body: upload.body ?? upload.text,
  });
  expect(upload.status, `unsafe filename must match the frozen 400 contract: ${upload.text}`).toBe(400);
});

test(`${backend}: V2-M1-03-N2 zero-byte shelf file is rejected with 400`, async ({ context }, testInfo) => {
  const project = await createProject(context.request, `V2M1 empty ${Date.now()}`);
  const upload = await uploadProjectDocument(context.request, project.id, {
    name: "empty.csv", mimeType: "text/csv", buffer: Buffer.alloc(0),
  });
  await save(testInfo, "v2-m1-03-n2-empty-file", {
    case: "V2-M1-03-N2", backend, projectId: project.id, status: upload.status,
    frozenEmptyStatus: 400, body: upload.body ?? upload.text,
  });
  // Frozen project_documents.py rejects an empty staged file with 400 ("Empty
  // file"); it is not the oversized 413 path, so accept only 400.
  expect(upload.status, `zero-byte file must match the frozen 400 contract: ${upload.text}`).toBe(400);
});

test(`${backend}: V2-M1-03-N3 oversized shelf file is rejected with 413`, async ({ context }, testInfo) => {
  const threadId = `v2m1-limits-${Date.now()}`;
  await createThread(context.request, threadId, null);
  const limits = await readUploadLimits(context.request, threadId);
  const maxFileSize = Number(limits.max_file_size);
  expect(maxFileSize, `backend must advertise a positive per-file upload limit: ${JSON.stringify(limits)}`).toBeGreaterThan(0);
  // Bounded, controlled oversize derived from the backend's own advertised
  // limit (never a fixed >50 MiB blob). Exactly one byte over keeps the body
  // inside the backend's multipart envelope so the server reads it and answers
  // with a normal 413 instead of racing a declared-length early close.
  const oversizeBytes = maxFileSize + 1;
  const gateway = process.env.W18_DIFFERENTIAL_GATEWAY || app;
  const project = await createProject(context.request, `V2M1 oversized ${Date.now()}`);
  const upload = await uploadProjectDocument(context.request, project.id, {
    name: "oversized.csv", mimeType: "text/csv", buffer: Buffer.alloc(oversizeBytes, 0x78),
    // Post straight to the gateway (the backend multipart endpoint the frozen
    // UI proxy forwards to) with an explicit bounded client timeout.
    origin: gateway,
    timeout: 120_000,
  });
  await save(testInfo, "v2-m1-03-n3-oversized-file", {
    case: "V2-M1-03-N3", backend, projectId: project.id, maxFileSize, oversizeBytes,
    uploadOrigin: gateway, status: upload.status, body: upload.body ?? upload.text,
  });
  expect(upload.status, `oversized file must match the frozen 413 contract: ${upload.text}`).toBe(413);
});

async function createThreadWithTitle(request, threadId, projectId, title) {
  const response = await request.post(`${app}/api/langgraph/threads`, {
    data: { thread_id: threadId, project_id: projectId },
  });
  expect(response.status(), await response.text()).toBe(200);
  // The frozen attach dialog renders values.title from the thread search
  // projection, not a caller-supplied metadata.title. Name the thread through
  // the real state endpoint so the UI can select it unambiguously.
  const renamed = await request.post(`${app}/api/langgraph/threads/${threadId}/state`, {
    data: { values: { title } },
  });
  expect(renamed.status(), await renamed.text()).toBe(200);
}

// The project-shelf attach is a browser-side handoff (core/projects/
// composer-attach.ts): the frozen project page stages the server-confirmed
// attachment for the target thread and navigates there, where the composer
// shows the chip and includes its virtual path in the next Run. It therefore
// has to be driven through the frozen UI, not a bare HTTP attach.
async function attachViaUi(page, context, prefix) {
  const suffix = Date.now();
  const projectName = `V2M1 attach ${prefix} ${suffix}`;
  const threadId = `v2m1-${prefix}-${suffix}`;
  const threadTitle = `V2M1 chat ${prefix} ${suffix}`;
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  await expect(page.locator('textarea[name="message"]')).toBeVisible();

  await page.getByTestId("projects-new-project-button").click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("input").first().fill(projectName);
  const projectPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && new URL(response.url()).pathname === "/api/projects",
    { timeout: 20_000 });
  await dialog.getByRole("button", { name: "Create", exact: true }).click();
  const projectResponse = await projectPromise;
  expect(projectResponse.status(), await projectResponse.text()).toBe(201);
  const project = await projectResponse.json();
  await createThreadWithTitle(context.request, threadId, project.id, threadTitle);

  await page.goto(`/workspace/projects/${project.id}`, { waitUntil: "domcontentloaded" });
  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-shelf")).toBeVisible();
  const uploadPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === `/api/projects/${project.id}/documents`,
    { timeout: 20_000 });
  await page.getByTestId("project-documents-upload-input").setInputFiles({
    name: "customers.csv", mimeType: "text/csv", buffer: fixtureBytes(),
  });
  const uploadResponse = await uploadPromise;
  expect(uploadResponse.status(), await uploadResponse.text()).toBe(201);
  const documentId = (await uploadResponse.json()).document.id;

  const attachPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname ===
      `/api/projects/${project.id}/documents/${documentId}/attach-to-thread/${threadId}`,
    { timeout: 20_000 });
  await page.getByRole("button", { name: "Attach to chat", exact: true }).click();
  const attachDialog = page.getByRole("dialog");
  // The frozen dialog uses a paged, global recent-Thread query. A newly
  // created empty Thread need not be on its first page after other cases run.
  let selected = false;
  for (let pageNumber = 0; pageNumber < 30; pageNumber++) {
    const choice = attachDialog.getByRole("button", { name: threadTitle, exact: true });
    if (await choice.count()) {
      await choice.click();
      selected = true;
      break;
    }
    const loadMore = attachDialog.getByTestId("attach-thread-load-more");
    if (!(await loadMore.count())) break;
    const buttonCount = await attachDialog.getByRole("button").count();
    await loadMore.click();
    await expect.poll(async () => await attachDialog.getByRole("button").count(),
      { timeout: 10_000, intervals: [100, 250, 500] }).not.toBe(buttonCount);
  }
  expect(selected, `Thread ${threadTitle} was not found in 30 frozen UI pages`).toBe(true);
  const attachResponse = await attachPromise;
  expect(attachResponse.status(), await attachResponse.text()).toBe(200);
  await expect(page).toHaveURL(new RegExp(`/workspace/chats/${threadId}$`));
  await expect(page.getByTestId("project-attachment-chip")).toContainText("customers.csv");
  return { threadId, projectId: project.id, documentId };
}

test(`${backend}: V2-M1-04 attached Project document enters the Run context`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const attached = await attachViaUi(page, context, "attached");
  const marker = `v2-m1-file-${Date.now()}`;

  const listed = await context.request.get(`${app}/api/threads/${attached.threadId}/uploads/list`);
  const listedStatus = listed.status();
  let listedBody = null;
  try { listedBody = await listed.json(); } catch { listedBody = null; }
  const listedNames = (listedBody?.files ?? []).map((entry) => entry.filename);

  const run = await sendOnCurrentThread(page, `${marker}: report data row 17's customer_id and the East, West and overall amount totals`);
  const requests = providerRequests(marker);
  const actions = v2FileActions(marker);
  const first = requests.find((entry) => entry.toolResults === 0);

  const state = await (await context.request.get(`${app}/api/langgraph/threads/${attached.threadId}/state`)).json();
  const humans = (state.values?.messages ?? []).filter((message) => message.type === "human")
    .map((message) => (typeof message.content === "string" ? message.content : JSON.stringify(message.content))).join("\n");

  // Raw provider slice, upload listing and Thread path reference are written
  // before assertions so a first failure still leaves this case's evidence.
  await save(testInfo, "v2-m1-04-attach-context", {
    case: "V2-M1-04",
    backend,
    threadId: attached.threadId,
    runId: run.runId,
    listedStatus,
    listedNames,
    providerActions: actions,
    providerRequestCount: requests.length,
    firstRequestUploadPaths: first?.uploadPaths ?? null,
    firstRequestOfferedTools: first?.offeredTools ?? null,
    firstRequestRawPromptHasValue: first ? Boolean(first.rawPrompt?.includes("C017")) : null,
    humanMessages: humans,
    humanReferencesPathNotValue: humans.includes(`Path: ${UPLOAD_PATH}`) && !humans.includes("C017"),
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });

  expect(listedStatus, "the uploads list must be readable").toBe(200);
  assertReadFileOffered(requests);
  expect(actions.map((entry) => entry.action), JSON.stringify(actions)).toEqual(["read_file", "answer"]);
  expect(actions[0].path).toBe(UPLOAD_PATH);
  expect(first, "the first provider request must precede the read_file Tool result").toBeTruthy();
  expect(first.uploadPaths, "the attached file must be an input reference, not inlined").toContain(UPLOAD_PATH);
  expect(first.rawPrompt).not.toContain("C017");
  expect(first.offeredTools).toContain("read_file");
  expect(humans).toContain(`Path: ${UPLOAD_PATH}`);
  expect(humans).not.toContain("C017");
  expect(targets.forbidden).toEqual([]);
});

test(`${backend}: V2-M1-04-N1 un-attached Project document does not enter the Run context`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const orphanProject = await createProject(context.request, `v2m1 orphan ${Date.now()}`);
  const orphanUpload = await uploadProjectDocument(context.request, orphanProject.id, {
    name: "customers.csv", mimeType: "text/csv", buffer: fixtureBytes(),
  });
  const orphanThread = `v2m1-orphan-${Date.now()}`;
  await createThread(context.request, orphanThread, orphanProject.id);
  const orphanMarker = `v2-m1-file-${Date.now()}`;
  await runOnThread(page, orphanThread, orphanMarker);
  const orphanRequests = providerRequests(orphanMarker);
  const orphanPaths = [...new Set(orphanRequests.flatMap((entry) => entry.uploadPaths ?? []))];
  const orphanActions = v2FileActions(orphanMarker);
  const orphanState = await (await context.request.get(`${app}/api/langgraph/threads/${orphanThread}/state`)).json();
  const orphanText = (orphanState.values?.messages ?? []).filter((message) => message.type === "ai")
    .map((message) => (typeof message.content === "string" ? message.content : "")).join("\n");

  await save(testInfo, "v2-m1-04-n1-unattached", {
    case: "V2-M1-04-N1",
    backend,
    threadId: orphanThread,
    projectId: orphanProject.id,
    uploadStatus: orphanUpload.status,
    runAnswer: orphanText,
    providerRequestCount: orphanRequests.length,
    providerUploadPaths: orphanPaths,
    providerActions: orphanActions,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });

  expect(orphanUpload.status, orphanUpload.text).toBe(201);
  expect(orphanPaths, "an un-attached document must not enter the Run context").toEqual([]);
  expect(orphanActions.map((entry) => entry.action), JSON.stringify(orphanActions)).toEqual(["no_file_context"]);
  expect(orphanText).toContain(`${orphanMarker} NO_FILE_CONTEXT`);
  expect(targets.forbidden).toEqual([]);
});

test(`${backend}: V2-M1-04-N2 attaching to an unknown Thread fails closed`, async ({ context }, testInfo) => {
  const suffix = Date.now();
  const project = await createProject(context.request, `V2M1 foreign ${suffix}`);
  const upload = await uploadProjectDocument(context.request, project.id, {
    name: "customers.csv", mimeType: "text/csv", buffer: fixtureBytes(),
  });
  expect(upload.status, await upload.text).toBe(201);
  const attach = await attachDocument(context.request, project.id, upload.body.document.id, `v2m1-missing-${suffix}`);
  await save(testInfo, "v2-m1-04-n2-foreign-thread", {
    case: "V2-M1-04-N2", backend, projectId: project.id, documentId: upload.body.document.id,
    targetThreadId: `v2m1-missing-${suffix}`, status: attach.status, body: attach.text,
  });
  expect(attach.status, `attach to an unknown Thread must be 404, got ${attach.status}: ${attach.text}`).toBe(404);
});

test(`${backend}: V2-M1-05 model reads the saved original and answers the CSV oracle`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const captured = await captureOracleRun(page, context, "oracle");
  const { expected, attached, marker, run, actions, call } = captured;

  await save(testInfo, "v2-m1-05-csv-oracle", {
    case: "V2-M1-05",
    backend,
    threadId: attached.threadId,
    runId: run.runId,
    answer: actions[1]?.answer ?? null,
    expectedOracle: expected,
    providerActions: actions,
    providerRequestCount: captured.requests.length,
    readFile: call ? { toolCallId: call.id, path: call.args?.path ?? null, resultSha256: actions[1]?.toolResultSha256 ?? null } : null,
    contentStatus: captured.contentStatus,
    downloadedSha256: sha256(captured.downloaded),
    downloadedByteLength: captured.downloaded.length,
    fixtureSha256: FIXTURE_SHA256,
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });

  assertReadFileOffered(captured.requests);
  expect(actions.map((entry) => entry.action), JSON.stringify(actions)).toEqual(["read_file", "answer"]);
  expect(actions[0].path).toBe(UPLOAD_PATH);
  expect(actions[1].answer).toBe(`${marker} ${expected}`);
  expect(actions[1].toolResultSha256).toBe(FIXTURE_SHA256);
  await expect(page.locator("#chat").getByText(`${marker} ${expected}`, { exact: true })).toBeVisible();
  expect(call, "the Run must call read_file").toBeTruthy();
  expect(call.args.path).toBe(UPLOAD_PATH);
  expect(captured.contentStatus, "the saved original must download").toBe(200);
  expect(sha256(captured.downloaded)).toBe(FIXTURE_SHA256);
  expect(targets.forbidden).toEqual([]);
});

test(`${backend}: V2-M1-05-N1 row offset answers the 1-based row 17, not an off-by-one row`, async ({ context, page }, testInfo) => {
  const captured = await captureOracleRun(page, context, "oracle-n1");
  const rows = csvDataRows(captured.bytes);
  const correctRowId = rows[16][0];
  const nextRowId = rows[17]?.[0];
  const previousRowId = rows[15]?.[0];
  const answer = captured.actions[1]?.answer ?? null;
  await save(testInfo, "v2-m1-05-n1-row-offset", {
    case: "V2-M1-05-N1", backend, threadId: captured.attached.threadId, runId: captured.run.runId,
    correctRowId, nextRowId, previousRowId, answer,
    providerActions: captured.actions, providerRequestCount: captured.requests.length,
  });
  assertReadFileOffered(captured.requests);
  expect(correctRowId, "the independent 1-based row 17 is C017").toBe("C017");
  expect(answer, "the answer must resolve row 17 with 1-based indexing").toContain(`row17=${correctRowId}`);
  expect(answer, "a 0-based/off-by-one row offset must not pass").not.toContain(`row17=${nextRowId}`);
  expect(answer, "a shifted-up row offset must not pass").not.toContain(`row17=${previousRowId}`);
});

test(`${backend}: V2-M1-05-N2 a BOM or mojibake Tool result would break the pinned bytes`, async ({ context, page }, testInfo) => {
  const captured = await captureOracleRun(page, context, "oracle-n2");
  await save(testInfo, "v2-m1-05-n2-encoding", {
    case: "V2-M1-05-N2", backend, threadId: captured.attached.threadId, runId: captured.run.runId,
    toolResultSha256: captured.actions[1]?.toolResultSha256 ?? null, fixtureSha256: FIXTURE_SHA256,
    downloadedSha256: sha256(captured.downloaded),
    firstFixtureCharCode: captured.bytes.toString("utf8").charCodeAt(0),
    answer: captured.actions[1]?.answer ?? null,
    providerActions: captured.actions, providerRequestCount: captured.requests.length,
  });
  assertReadFileOffered(captured.requests);
  // The provider hashes the CSV lines it actually saw through the production
  // read_file Tool; a BOM or mojibake would break header detection or the digest.
  expect(captured.actions[1].toolResultSha256, "the read_file Tool result must still hash to the pinned LF fixture").toBe(FIXTURE_SHA256);
  expect(sha256(captured.downloaded)).toBe(FIXTURE_SHA256);
  expect(captured.bytes.toString("utf8").charCodeAt(0), "the committed fixture is UTF-8 without BOM").not.toBe(0xfeff);
  expect(captured.actions[1].answer).toContain("rows=20");
});

test(`${backend}: V2-M1-05-N3 recomputed region and grand totals match the independent oracle`, async ({ context, page }, testInfo) => {
  const captured = await captureOracleRun(page, context, "oracle-n3");
  const rows = csvDataRows(captured.bytes);
  const sum = (region) => rows.filter((row) => row[1] === region).reduce((acc, row) => acc + Number(row[2]), 0);
  const east = sum("East");
  const west = sum("West");
  const total = east + west;
  const high = rows.filter((row) => Number(row[2]) >= 1500).length;
  const answer = captured.actions[1]?.answer ?? null;
  await save(testInfo, "v2-m1-05-n3-totals", {
    case: "V2-M1-05-N3", backend, threadId: captured.attached.threadId, runId: captured.run.runId,
    recomputed: { east, west, total, high, rows: rows.length }, answer,
    providerActions: captured.actions, providerRequestCount: captured.requests.length,
  });
  assertReadFileOffered(captured.requests);
  expect({ east, west, total, high, rows: rows.length }).toEqual({ east: 10000, west: 11000, total: 21000, high: 6, rows: 20 });
  expect(answer).toContain(`east=${east}`);
  expect(answer).toContain(`west=${west}`);
  expect(answer).toContain(`total=${total}`);
  expect(answer).toContain(`high=${high}`);
  expect(answer, "a drifted grand total must not pass").not.toContain(`total=${total + 1}`);
  expect(answer, "a wrong high-amount threshold must not pass").not.toContain(`high=${high + 1}`);
});
