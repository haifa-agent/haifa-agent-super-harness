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
function csvOracle(bytes) {
  const lines = bytes.toString("utf8").split("\n").filter((line) => line !== "");
  expect(lines[0]).toBe(CSV_HEADER);
  const rows = lines.slice(1).map((line) => line.split(","));
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

async function uploadProjectDocument(request, projectId, { name, buffer, mimeType }) {
  const response = await request.post(`${app}/api/projects/${projectId}/documents`, {
    multipart: { file: { name, mimeType, buffer } },
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
  expect(upload.status, `unsafe filename must match the frozen 400 contract: ${upload.text}`).toBe(400);
  await save(testInfo, "v2-m1-03-n1-unsafe-filename", {
    case: "V2-M1-03-N1", backend, projectId: project.id, attemptedNameBytes: Buffer.byteLength(unsafeName),
    status: upload.status, referenceExpectedStatus: 400, body: upload.body ?? upload.text,
  });
});

test(`${backend}: V2-M1-03-N2 empty shelf file is rejected`, async ({ context }, testInfo) => {
  const project = await createProject(context.request, `V2M1 empty ${Date.now()}`);
  const upload = await uploadProjectDocument(context.request, project.id, {
    name: "empty.csv", mimeType: "text/csv", buffer: Buffer.alloc(0),
  });
  expect([400, 413], `empty file must be rejected with 400/413: ${upload.text}`).toContain(upload.status);
  await save(testInfo, "v2-m1-03-n2-empty-file", {
    case: "V2-M1-03-N2", backend, projectId: project.id, status: upload.status, body: upload.body ?? upload.text,
  });
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

test(`${backend}: V2-M1-04 attached Project document enters the Run context, un-attached does not`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const attached = await attachViaUi(page, context, "attached");
  const marker = `v2-m1-file-${Date.now()}`;

  const listed = await context.request.get(`${app}/api/threads/${attached.threadId}/uploads/list`);
  expect(listed.status(), await listed.text()).toBe(200);
  const listedNames = (await listed.json()).files.map((entry) => entry.filename);

  const run = await sendOnCurrentThread(page, `${marker}: report data row 17's customer_id and the East, West and overall amount totals`);
  const requests = providerRequests(marker);
  assertReadFileOffered(requests);

  const actions = v2FileActions(marker);
  expect(actions.map((entry) => entry.action), JSON.stringify(actions)).toEqual(["read_file", "answer"]);
  expect(actions[0].path).toBe(UPLOAD_PATH);
  const first = requests.find((entry) => entry.toolResults === 0);
  expect(first.uploadPaths, "the attached file must be an input reference, not inlined").toContain(UPLOAD_PATH);
  expect(first.rawPrompt).not.toContain("C017");
  expect(first.offeredTools).toContain("read_file");

  const state = await (await context.request.get(`${app}/api/langgraph/threads/${attached.threadId}/state`)).json();
  const humans = (state.values?.messages ?? []).filter((message) => message.type === "human")
    .map((message) => (typeof message.content === "string" ? message.content : JSON.stringify(message.content))).join("\n");
  expect(humans).toContain(`Path: ${UPLOAD_PATH}`);
  expect(humans).not.toContain("C017");

  // Negative V2-M1-04-N1: a Project document that was never attached must not
  // reach the Run context (no prompt stuffing).
  const orphanProject = await createProject(context.request, `v2m1 orphan ${Date.now()}`);
  const orphanUpload = await uploadProjectDocument(context.request, orphanProject.id, {
    name: "customers.csv", mimeType: "text/csv", buffer: fixtureBytes(),
  });
  expect(orphanUpload.status, await orphanUpload.text).toBe(201);
  const orphanThread = `v2m1-orphan-${Date.now()}`;
  await createThread(context.request, orphanThread, orphanProject.id);
  const orphanMarker = `v2-m1-file-${Date.now()}`;
  await runOnThread(page, orphanThread, orphanMarker);
  const orphanRequests = providerRequests(orphanMarker);
  const orphanPaths = [...new Set(orphanRequests.flatMap((entry) => entry.uploadPaths ?? []))];
  expect(orphanPaths, "an un-attached document must not enter the Run context").toEqual([]);
  const orphanActions = v2FileActions(orphanMarker);
  expect(orphanActions.map((entry) => entry.action), JSON.stringify(orphanActions)).toEqual(["no_file_context"]);
  const orphanState = await (await context.request.get(`${app}/api/langgraph/threads/${orphanThread}/state`)).json();
  const orphanText = (orphanState.values?.messages ?? []).filter((message) => message.type === "ai")
    .map((message) => (typeof message.content === "string" ? message.content : "")).join("\n");
  expect(orphanText).toContain(`${orphanMarker} NO_FILE_CONTEXT`);

  expect(targets.forbidden).toEqual([]);
  await save(testInfo, "v2-m1-04-attach-context", {
    case: "V2-M1-04",
    backend,
    threadId: attached.threadId,
    runId: run.runId,
    listedNames,
    providerActions: actions,
    firstRequestUploadPaths: first.uploadPaths,
    humanReferencesPathNotValue: true,
    unattached: {
      case: "V2-M1-04-N1",
      threadId: orphanThread,
      providerUploadPaths: orphanPaths,
      providerActions: orphanActions,
      runAnswer: orphanText,
    },
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});

test(`${backend}: V2-M1-04-N2 attaching to an unknown Thread fails closed`, async ({ context }, testInfo) => {
  const suffix = Date.now();
  const project = await createProject(context.request, `V2M1 foreign ${suffix}`);
  const upload = await uploadProjectDocument(context.request, project.id, {
    name: "customers.csv", mimeType: "text/csv", buffer: fixtureBytes(),
  });
  expect(upload.status, await upload.text).toBe(201);
  const attach = await attachDocument(context.request, project.id, upload.body.document.id, `v2m1-missing-${suffix}`);
  expect(attach.status, `attach to an unknown Thread must be 404, got ${attach.status}: ${attach.text}`).toBe(404);
  await save(testInfo, "v2-m1-04-n2-foreign-thread", {
    case: "V2-M1-04-N2", backend, projectId: project.id, status: attach.status, body: attach.text,
  });
});

test(`${backend}: V2-M1-05 model reads the saved original and answers the CSV oracle`, async ({ context, page }, testInfo) => {
  const targets = observeSameOrigin(page);
  const bytes = fixtureBytes();
  const expected = csvOracle(bytes);
  const attached = await attachViaUi(page, context, "oracle");
  const marker = `v2-m1-file-${Date.now()}`;

  const run = await sendOnCurrentThread(page, `${marker}: report data row 17's customer_id and the East, West and overall amount totals`);
  const requests = providerRequests(marker);
  assertReadFileOffered(requests);

  const actions = v2FileActions(marker);
  expect(actions.map((entry) => entry.action), JSON.stringify(actions)).toEqual(["read_file", "answer"]);
  expect(actions[0].path).toBe(UPLOAD_PATH);
  expect(actions[1].answer).toBe(`${marker} ${expected}`);
  expect(actions[1].toolResultSha256).toBe(FIXTURE_SHA256);

  await expect(page.locator("#chat").getByText(`${marker} ${expected}`, { exact: true })).toBeVisible();

  const state = await (await context.request.get(`${app}/api/langgraph/threads/${attached.threadId}/state`)).json();
  const runMessages = (state.values?.messages ?? []).filter((message) => !message.run_id || message.run_id === run.runId);
  const call = runMessages.flatMap((message) => message.tool_calls ?? []).find((entry) => entry.name === "read_file");
  expect(call, "the Run must call read_file").toBeTruthy();
  expect(call.args.path).toBe(UPLOAD_PATH);

  const contentResponse = await context.request.get(
    `${app}/api/projects/${attached.projectId}/documents/${attached.documentId}/content?download=true`);
  expect(contentResponse.status(), await contentResponse.text()).toBe(200);
  const downloaded = await contentResponse.body();
  expect(sha256(downloaded)).toBe(FIXTURE_SHA256);

  expect(targets.forbidden).toEqual([]);
  await save(testInfo, "v2-m1-05-csv-oracle", {
    case: "V2-M1-05",
    backend,
    threadId: attached.threadId,
    runId: run.runId,
    answer: actions[1].answer,
    expectedOracle: expected,
    readFile: { toolCallId: call.id, path: call.args.path, resultSha256: actions[1].toolResultSha256 },
    downloadedSha256: sha256(downloaded),
    fixtureSha256: FIXTURE_SHA256,
    negatives: {
      "V2-M1-05-N1": "row offset is asserted by the exact C017 answer",
      "V2-M1-05-N2": "byte/share mismatch would break the pinned read_file/download hashes",
      "V2-M1-05-N3": "East/West/total are recomputed independently by this spec",
    },
    browserApiTargets: targets.requests,
    forbiddenBrowserTargets: targets.forbidden,
  });
});
