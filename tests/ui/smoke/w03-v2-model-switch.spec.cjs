// W03 candidate-only frozen-UI smoke. This is not the final W18 dual-backend
// differential or the V2-M6 real-model acceptance.
const fs = require("node:fs");
const path = require("node:path");
const { expect, test } = require(path.resolve(__dirname, "../../../frontend/node_modules/@playwright/test"));

const app = process.env.E2E_APP_URL;
const evidenceRoot = process.env.W18_EVIDENCE_DIR;
if (!app || !evidenceRoot) throw new Error("W03 UI app and evidence root are required");

function providerEntries() {
  const file = fs.readdirSync(evidenceRoot).find((name) => /^provider-.*\.stdout\.log$/.test(name));
  if (!file) return [];
  return fs.readFileSync(path.join(evidenceRoot, file), "utf8").split(/\r?\n/)
    .filter((line) => line.startsWith("{"))
    .map((line) => { try { return JSON.parse(line); } catch { return null; } })
    .filter(Boolean);
}

function modelRequests(marker) {
  return providerEntries().filter((entry) => entry.type === "provider_request" && entry.stream &&
    (entry.rawPrompt?.includes(marker) || entry.prompt?.includes(marker)));
}

async function save(testInfo, name, data) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(data, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

function observeApiOrigins(page) {
  const requests = [];
  const forbidden = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith("/api/")) return;
    requests.push({ method: request.method(), origin: url.origin, path: url.pathname });
    if (url.origin !== app) forbidden.push(url.origin + url.pathname);
  });
  return { requests, forbidden };
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
  const trigger = modelTrigger(page).first();
  await expect(trigger).toBeEnabled();
  await trigger.click();
  const option = page.getByRole("button", { name: new RegExp(`\\(${name}\\)$`) }).first();
  await expect(option).toBeVisible();
  await option.click();
}

async function sendRun(page, input, text) {
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
  { timeout: 30_000 });
  await input.fill(text);
  // The frozen composer exposes Submit as the explicit send action. A prior
  // slow-Run diagnostic saw Enter leave text in the composer without sending;
  // use one real button click, never a retry or synthetic API request.
  await page.getByRole("button", { name: "Submit" }).click();
  const response = await responsePromise;
  if (response.status() !== 200) {
    throw new Error(`Run stream returned ${response.status()}: ${await response.text()}`);
  }
  const location = response.headers()["content-location"];
  const match = location?.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected Run Content-Location: ${location}`).toBeTruthy();
  const body = response.request().postDataJSON();
  return { threadId: match[1], runId: match[2], modelName: body?.context?.model_name ?? null };
}

async function awaitRun(request, run, timeout = 45_000) {
  let latest;
  await expect.poll(async () => {
    const response = await request.get(`${app}/api/langgraph/threads/${run.threadId}/runs/${run.runId}`);
    expect(response.status(), await response.text()).toBe(200);
    latest = await response.json();
    return latest.status;
  }, { timeout, intervals: [100, 250, 500] }).toBe("success");
  return latest;
}

test("W03 frozen UI same Conversation A then B keeps both Runs and history after reload", async ({ context, page }, testInfo) => {
  const network = observeApiOrigins(page);
  const suffix = Date.now();
  const markerA = `w03-v2-a-${suffix}`;
  const markerB = `w03-v2-b-${suffix}`;
  const input = await openChat(page);

  const first = await sendRun(page, input, markerA);
  expect(first.modelName).toBe("w18-controlled");
  await awaitRun(context.request, first);
  await expect(page.locator("#chat").getByText(`Controlled provider: ${markerA}`, { exact: true })).toBeVisible();

  await selectModel(page, "w18-controlled-b");
  const second = await sendRun(page, input, markerB);
  expect(second.modelName).toBe("w18-controlled-b");
  expect(second.threadId).toBe(first.threadId);
  expect(second.runId).not.toBe(first.runId);
  await awaitRun(context.request, second);
  await expect(page.locator("#chat").getByText(`Controlled provider: ${markerB}`, { exact: true })).toBeVisible();

  await expect.poll(() => modelRequests(markerA).some((entry) => entry.model === "w18-controlled-model")).toBe(true);
  await expect.poll(() => modelRequests(markerB).some((entry) => entry.model === "w18-controlled-model-b")).toBe(true);
  const before = await context.request.get(`${app}/api/langgraph/threads/${first.threadId}/state`);
  expect(before.status(), await before.text()).toBe(200);
  const beforeText = JSON.stringify(await before.json());
  expect(beforeText).toContain(markerA);
  expect(beforeText).toContain(markerB);

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.locator("#chat").getByText(`Controlled provider: ${markerA}`, { exact: true })).toBeVisible();
  await expect(page.locator("#chat").getByText(`Controlled provider: ${markerB}`, { exact: true })).toBeVisible();
  const runs = await context.request.get(`${app}/api/langgraph/threads/${first.threadId}/runs`);
  expect(runs.status(), await runs.text()).toBe(200);
  expect(await runs.text()).toContain(first.runId);
  expect(await runs.text()).toContain(second.runId);
  expect(network.forbidden).toEqual([]);
  await save(testInfo, "w03-v2-same-thread-switch", {
    threadId: first.threadId, first, second,
    providerA: modelRequests(markerA).map((entry) => entry.model),
    providerB: modelRequests(markerB).map((entry) => entry.model),
    reloadRetainedBothAnswers: true, apiRequests: network.requests, forbidden: network.forbidden,
  });
});

test("W03 frozen UI active A is immutable and next Turn may select B", async ({ context, page }, testInfo) => {
  const network = observeApiOrigins(page);
  const input = await openChat(page);
  const active = await sendRun(page, input, "__slow_differential__");
  expect(active.modelName).toBe("w18-controlled");
  await expect(modelTrigger(page).first()).toBeDisabled();
  await awaitRun(context.request, active, 60_000);
  await expect.poll(() => modelRequests("__slow_differential__")
    .some((entry) => entry.model === "w18-controlled-model")).toBe(true);
  // Runtime success may precede the frozen UI consuming the final stream event.
  // Wait for its visible completed Turn before selecting the next model.
  await expect(page.getByText(/^Completed in \d+s$/)).toBeVisible({ timeout: 60_000 });

  const markerB = `w03-v2-after-active-${Date.now()}`;
  await selectModel(page, "w18-controlled-b");
  const next = await sendRun(page, input, markerB);
  expect(next.threadId).toBe(active.threadId);
  expect(next.runId).not.toBe(active.runId);
  expect(next.modelName).toBe("w18-controlled-b");
  await awaitRun(context.request, next);
  await expect.poll(() => modelRequests(markerB)
    .some((entry) => entry.model === "w18-controlled-model-b")).toBe(true);
  expect(network.forbidden).toEqual([]);
  await save(testInfo, "w03-v2-active-freeze", {
    threadId: active.threadId, active, next, modelTriggerLockedWhileActive: true,
    providerA: modelRequests("__slow_differential__").map((entry) => entry.model),
    providerB: modelRequests(markerB).map((entry) => entry.model),
    apiRequests: network.requests, forbidden: network.forbidden,
  });
});
