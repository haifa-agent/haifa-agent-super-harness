// W19 scheduled-tasks differential: the same frozen /workspace/scheduled-tasks page
// and the same controlled loopback Provider against both backends (reference = frozen
// DeerFlow e545c28, haifa = Super Harness). Each case drives the frozen UI and
// cross-checks the same-origin /api/scheduled-tasks projection.
//
// Authority for every expectation is the frozen frontend contract
// (frontend/src/core/scheduled-tasks/types.ts, api.ts, page.tsx) and the frozen
// DeerFlow router semantics. The task/run contract field sets below are the frozen
// frontend's declared fields; both backends may carry additional internal fields
// (for example the reference's user_id/lease_owner) that the frozen UI never reads,
// so the comparison is on the contract projection, not on byte-identical objects.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP = process.env.E2E_APP_URL;
const backend = process.env.W18_DIFFERENTIAL_BACKEND;
const evidenceRoot = process.env.W18_DIFFERENTIAL_EVIDENCE;
const round = Number.parseInt(process.env.W18_DIFFERENTIAL_ROUND ?? "", 10);
if (!APP || !["reference", "haifa"].includes(backend) || !evidenceRoot || ![1, 2].includes(round)) {
  throw new Error("Explicit W19 differential app/backend/evidence/round is required");
}

// The full trigger can hold a real ~20s Run open; the frozen UI also has to
// compile its route on first navigation in a fresh production server.
test.describe.configure({ timeout: 150_000 });

const TASK_FIELDS = [
  "id", "thread_id", "context_mode", "assistant_id", "title", "prompt", "schedule_type",
  "schedule_spec", "timezone", "status", "next_run_at", "last_run_at", "last_run_id",
  "last_thread_id", "last_error", "run_count", "created_at", "updated_at",
];
const RUN_FIELDS = [
  "id", "task_id", "thread_id", "run_id", "scheduled_for", "trigger", "status", "error",
  "attempt_count", "started_at", "finished_at", "created_at",
];
const ACTIVE = new Set(["queued", "launching", "running"]);
const TERMINAL = new Set(["success", "failed", "skipped", "interrupted"]);

function marker(prefix) {
  return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function save(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

async function screenshot(testInfo, page, name) {
  const file = testInfo.outputPath(name);
  await page.screenshot({ path: file, fullPage: true });
  await testInfo.attach(name, { path: file, contentType: "image/png" });
}

// Frozen frontend contract projection: every declared field is present with the
// declared JSON type. Extra backend-internal fields are ignored (the frozen UI
// ignores them too), so this proves the visible protocol shape without asserting
// an implementation's private columns.
function assertTaskContract(task, label) {
  for (const field of TASK_FIELDS) {
    expect(Object.prototype.hasOwnProperty.call(task, field), `${label}: missing task field ${field}`).toBe(true);
  }
  expect(typeof task.id, `${label}: id`).toBe("string");
  expect(typeof task.title, `${label}: title`).toBe("string");
  expect(typeof task.prompt, `${label}: prompt`).toBe("string");
  expect(["once", "cron", "interval"], `${label}: schedule_type`).toContain(task.schedule_type);
  expect(task.schedule_spec && typeof task.schedule_spec === "object", `${label}: schedule_spec`).toBe(true);
  expect(typeof task.timezone, `${label}: timezone`).toBe("string");
  expect(["enabled", "paused", "running", "completed", "failed", "cancelled"], `${label}: status`).toContain(task.status);
  expect(typeof task.run_count, `${label}: run_count`).toBe("number");
  expect(typeof task.created_at, `${label}: created_at`).toBe("string");
  expect(typeof task.updated_at, `${label}: updated_at`).toBe("string");
  for (const nullable of ["thread_id", "assistant_id", "next_run_at", "last_run_at", "last_run_id", "last_thread_id", "last_error"]) {
    const value = task[nullable];
    expect(value === null || typeof value === "string", `${label}: ${nullable}`).toBe(true);
  }
}

function assertRunContract(run, label) {
  for (const field of RUN_FIELDS) {
    expect(Object.prototype.hasOwnProperty.call(run, field), `${label}: missing run field ${field}`).toBe(true);
  }
  expect(typeof run.id, `${label}: id`).toBe("string");
  expect(typeof run.task_id, `${label}: task_id`).toBe("string");
  expect(typeof run.thread_id, `${label}: thread_id`).toBe("string");
  expect(["scheduled", "manual"], `${label}: trigger`).toContain(run.trigger);
  expect(["queued", "launching", "running", "success", "failed", "skipped", "interrupted"], `${label}: status`).toContain(run.status);
  expect(typeof run.attempt_count, `${label}: attempt_count`).toBe("number");
  expect(run.run_id === null || typeof run.run_id === "string", `${label}: run_id`).toBe(true);
}

async function apiJson(request, method, url, data) {
  const response = data === undefined
    ? await request[method](url)
    : await request[method](url, { data });
  return { response, status: response.status(), body: await response.text() };
}

async function listTasks(request) {
  const response = await request.get(`${APP}/api/scheduled-tasks`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function findTask(request, id) {
  return (await listTasks(request)).find((task) => task.id === id) ?? null;
}

async function taskRuns(request, id, query = "?limit=50&offset=0") {
  const response = await request.get(`${APP}/api/scheduled-tasks/${encodeURIComponent(id)}/runs${query}`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function createTask(request, payload) {
  const response = await request.post(`${APP}/api/scheduled-tasks`, { data: payload });
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function waitForTask(request, title, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const task = (await listTasks(request)).find((entry) => entry.title === title);
    if (task) return task;
    if (Date.now() >= deadline) throw new Error(`timed out waiting for created task '${title}'`);
    await sleep(400);
  }
}

// Bounded wait for an occurrence matching the predicate; never an unbounded retry.
async function waitForRun(request, id, predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  let observed = [];
  for (;;) {
    observed = await taskRuns(request, id);
    const found = observed.find(predicate);
    if (found) return { found, observed };
    if (Date.now() >= deadline) {
      throw new Error(
        `timed out waiting for ${label}; observed ${observed.map((r) => `${r.trigger}:${r.status}`).join(",") || "<none>"}`,
      );
    }
    await sleep(500);
  }
}

async function gotoPage(page) {
  await page.goto("/workspace/scheduled-tasks", { waitUntil: "domcontentloaded" });
  await expect(page.getByTestId("scheduled-task-create-form")).toBeVisible();
  await expect(page.getByTestId("scheduled-task-detail")).toBeVisible();
}

async function selectTask(page, id) {
  const item = page.getByTestId(`scheduled-task-item-${id}`);
  await expect(item).toBeVisible({ timeout: 20_000 });
  await item.click();
  return item;
}

async function createUiInterval(page, { title, prompt, seconds = 3600 }) {
  await gotoPage(page);
  const form = page.getByTestId("scheduled-task-create-form");
  await form.getByRole("button", { name: "Interval", exact: true }).click();
  const schedule = form.getByTestId("schedule-input");
  await schedule.getByTestId("schedule-interval-unit").click();
  await page.getByRole("option", { name: "seconds", exact: true }).click();
  const amount = schedule.getByLabel("Every");
  await amount.fill("");
  await amount.fill(String(seconds));
  await amount.blur();
  await form.getByPlaceholder("Task title").fill(title);
  await form.getByPlaceholder("Prompt").fill(prompt);
  await form.getByRole("button", { name: "Create", exact: true }).click();
  await expect(page.getByTestId("scheduled-task-list")).toContainText(title, { timeout: 20_000 });
}

async function createUiCron(page, { title, prompt }) {
  await gotoPage(page);
  const form = page.getByTestId("scheduled-task-create-form");
  await form.getByRole("button", { name: "Recurring", exact: true }).click();
  await form.getByPlaceholder("Task title").fill(title);
  await form.getByPlaceholder("Prompt").fill(prompt);
  await form.getByRole("button", { name: "Create", exact: true }).click();
  await expect(page.getByTestId("scheduled-task-list")).toContainText(title, { timeout: 20_000 });
}

// SCH-01: creating interval and cron tasks in the frozen UI yields the same listed
// contract on both backends (schedule_type, status, next_run_at, timezone).
test(`${backend}: SCH-01 create interval and cron in the frozen UI is listed consistently`, async ({ page, request }, testInfo) => {
  const intervalTitle = marker("SCH-01-interval");
  const cronTitle = marker("SCH-01-cron");
  await createUiInterval(page, {
    title: intervalTitle,
    prompt: "Reply with the single word SCHEDULED_SCH01_OK and nothing else. Do not use tools.",
  });
  const interval = await waitForTask(request, intervalTitle);
  assertTaskContract(interval, "interval");
  expect(interval.schedule_type).toBe("interval");
  expect(interval.schedule_spec.every_seconds).toBe(3600);
  expect(interval.status).toBe("enabled");
  expect(interval.context_mode).toBe("fresh_thread_per_run");
  expect(interval.next_run_at).toBeTruthy();
  expect(interval.timezone.length).toBeGreaterThan(0);

  await createUiCron(page, {
    title: cronTitle,
    prompt: "Reply with the single word SCHEDULED_SCH01_OK and nothing else. Do not use tools.",
  });
  const cron = await waitForTask(request, cronTitle);
  assertTaskContract(cron, "cron");
  expect(cron.schedule_type).toBe("cron");
  expect(typeof cron.schedule_spec.cron).toBe("string");
  expect(String(cron.schedule_spec.cron).split(/\s+/).length).toBe(5);
  expect(cron.next_run_at).toBeTruthy();
  expect(cron.timezone.length).toBeGreaterThan(0);

  // The frozen list row renders "{typeLabel} · {statusLabel}"; both backends must
  // project the same visible summary for the same stored task.
  const intervalRow = page.getByTestId(`scheduled-task-item-${interval.id}`);
  await expect(intervalRow).toContainText("Interval");
  await expect(intervalRow).toContainText("Enabled");
  const cronRow = page.getByTestId(`scheduled-task-item-${cron.id}`);
  await expect(cronRow).toContainText("Recurring");
  await expect(cronRow).toContainText("Enabled");

  await screenshot(testInfo, page, "w19-sch01-create.png");
  await save(testInfo, "w19-sch01", { interval, cron });
});

// SCH-02: PATCH edit and pause/resume keep the frozen UI and the API in agreement.
test(`${backend}: SCH-02 edit, pause and resume agree between the frozen UI and the API`, async ({ page, request }, testInfo) => {
  const title = marker("SCH-02-lifecycle");
  const created = await createTask(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title,
    prompt: "Reply with the single word SCHEDULED_SCH02_OK and nothing else. Do not use tools.",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 3600 },
    timezone: "UTC",
  });
  assertTaskContract(created, "created");

  await gotoPage(page);
  await selectTask(page, created.id);

  const editedTitle = `${title}-edited`;
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await page.getByPlaceholder("Edit title").fill(editedTitle);
  // Change the cadence (2 hours) so the PATCH carries a genuinely new schedule.
  // The frozen reference's PATCH writes the unchanged-interval branch's next_run_at
  // as a string into its SQLite DateTime column and errors; changing the interval
  // keeps SCH-02 about the frozen edit contract instead of that reference defect
  // (recorded separately in the round report).
  const editAmount = page.getByTestId("scheduled-task-detail").getByLabel("Every");
  await editAmount.fill("");
  await editAmount.fill("2");
  await editAmount.blur();
  await page.getByRole("button", { name: "Save edit", exact: true }).click();
  await expect(page.getByTestId("scheduled-task-detail")).toContainText(editedTitle, { timeout: 20_000 });
  const afterEdit = await findTask(request, created.id);
  expect(afterEdit.title).toBe(editedTitle);
  expect(afterEdit.schedule_type).toBe("interval");
  expect(afterEdit.schedule_spec.every_seconds).toBe(7200);

  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await expect(page.getByRole("button", { name: "Resume", exact: true })).toBeVisible({ timeout: 15_000 });
  expect((await findTask(request, created.id)).status).toBe("paused");
  const pausedRow = page.getByTestId(`scheduled-task-item-${created.id}`);
  await expect(pausedRow).toContainText("Paused");

  await page.getByRole("button", { name: "Resume", exact: true }).click();
  await expect(page.getByRole("button", { name: "Pause", exact: true })).toBeVisible({ timeout: 15_000 });
  expect((await findTask(request, created.id)).status).toBe("enabled");
  await expect(pausedRow).toContainText("Enabled");

  await screenshot(testInfo, page, "w19-sch02-lifecycle.png");
  await save(testInfo, "w19-sch02", { created, afterEdit, final: await findTask(request, created.id) });
});

// SCH-03: a manual trigger admits an occurrence whose frozen run-history shape and
// terminal success are identical on both backends.
test(`${backend}: SCH-03 manual trigger admits a run-history occurrence`, async ({ page, request }, testInfo) => {
  const title = marker("SCH-03-manual");
  const created = await createTask(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title,
    prompt: "Reply with the single word SCHEDULED_SCH03_OK and nothing else. Do not use tools.",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 3600 },
    timezone: "UTC",
  });

  await gotoPage(page);
  await selectTask(page, created.id);
  await page.getByRole("button", { name: "Trigger now", exact: true }).click();

  const { found: manual } = await waitForRun(
    request, created.id,
    (run) => run.trigger === "manual" && TERMINAL.has(run.status),
    120_000, "manual occurrence terminal",
  );
  assertRunContract(manual, "manual");
  expect(manual.status).toBe("success");
  expect(manual.run_id, "a successful occurrence carries a real Run id").toBeTruthy();
  expect(manual.thread_id).toBeTruthy();

  // The frozen detail history renders "manual · Success" for that occurrence.
  await gotoPage(page);
  await selectTask(page, created.id);
  const runList = page.getByTestId("scheduled-task-run-list");
  await expect(runList).toContainText("manual", { timeout: 20_000 });
  await expect(runList).toContainText("Success", { timeout: 20_000 });

  // The frozen frontend's own paged read (page 0) still returns the same contract.
  const page0 = await taskRuns(request, created.id, "?limit=50&offset=0");
  expect(Array.isArray(page0)).toBe(true);
  expect(page0.length).toBeGreaterThanOrEqual(1);
  for (const run of page0) assertRunContract(run, "page0");

  await screenshot(testInfo, page, "w19-sch03-history.png");
  await save(testInfo, "w19-sch03", { taskId: created.id, manual });
});

// SCH-04: while an occurrence is actively running, pausing and deleting are the
// frozen 409 conflict (never a silent mutation).
test(`${backend}: SCH-04 active occurrence rejects pause and delete with 409`, async ({ page, request }, testInfo) => {
  const title = marker("SCH-04-conflict");
  const created = await createTask(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title,
    // The controlled Provider holds this exact prompt open (~10s) so the active
    // occurrence window is deterministic on both backends; a scheduled Run issues a
    // non-streaming model call, which the generic streaming slow markers cannot hold.
    prompt: "__w19_scheduled_slow__",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 3600 },
    timezone: "UTC",
  });

  await gotoPage(page);
  await selectTask(page, created.id);
  await page.getByRole("button", { name: "Trigger now", exact: true }).click();

  await waitForRun(request, created.id, (run) => run.status === "running", 60_000, "running occurrence");

  let pauseStatus = 0;
  const pausePauseDeadline = Date.now() + 30_000;
  while (Date.now() < pausePauseDeadline) {
    const runs = await taskRuns(request, created.id);
    if (!runs.some((run) => run.status === "running")) break;
    const pause = await request.post(`${APP}/api/scheduled-tasks/${encodeURIComponent(created.id)}/pause`);
    pauseStatus = pause.status();
    if (pauseStatus === 409) break;
    await sleep(200);
  }
  expect(pauseStatus, "pausing an actively running task must be rejected with 409").toBe(409);

  let deleteStatus = 0;
  const runsNow = await taskRuns(request, created.id);
  if (runsNow.some((run) => run.status === "running")) {
    const del = await request.delete(`${APP}/api/scheduled-tasks/${encodeURIComponent(created.id)}`);
    deleteStatus = del.status();
  }
  expect(deleteStatus, "deleting an actively running task must be rejected with 409").toBe(409);

  // Let the held occurrence finish so the round leaves no active state behind.
  const { found: terminal } = await waitForRun(
    request, created.id,
    (run) => run.trigger === "manual" && TERMINAL.has(run.status),
    120_000, "held manual occurrence terminal",
  );
  expect(terminal.status).toBe("success");
  const peakActive = (await taskRuns(request, created.id)).filter((run) => ACTIVE.has(run.status)).length;
  expect(peakActive, "no occurrence may remain active after the held Run finished").toBe(0);

  await save(testInfo, "w19-sch04", { taskId: created.id, pauseStatus, deleteStatus, terminal: terminal.status });
});

// SCH-05: delete removes the task from the frozen list and the API.
test(`${backend}: SCH-05 delete removes the task from the frozen UI and the API`, async ({ page, request }, testInfo) => {
  const title = marker("SCH-05-delete");
  const created = await createTask(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title,
    prompt: "Reply with the single word SCHEDULED_SCH05_OK and nothing else. Do not use tools.",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 3600 },
    timezone: "UTC",
  });

  await gotoPage(page);
  await selectTask(page, created.id);
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("button", { name: "Delete", exact: true }).last().click();
  await expect(page.getByTestId(`scheduled-task-item-${created.id}`)).toHaveCount(0, { timeout: 20_000 });
  expect((await listTasks(request)).some((task) => task.id === created.id)).toBe(false);

  await save(testInfo, "w19-sch05", { taskId: created.id });
});

// SCH-06: invalid schedules return the frozen 422 error contract on both backends.
// The interval floor is deployment-configured (frozen default 60s), so its message
// is asserted by shape while the fixed messages are asserted verbatim.
test(`${backend}: SCH-06 invalid schedules return the frozen 422 detail`, async ({ request }, testInfo) => {
  const cases = [
    {
      name: "bad-cron",
      body: { schedule_type: "cron", schedule_spec: { cron: "not a cron" }, timezone: "UTC" },
      detail: "Cron expression must contain exactly 5 fields",
    },
    {
      name: "bad-timezone",
      body: { schedule_type: "cron", schedule_spec: { cron: "0 9 * * *" }, timezone: "Mars/Olympus" },
      detail: "Unknown timezone: Mars/Olympus",
    },
    {
      name: "past-once",
      body: { schedule_type: "once", schedule_spec: { run_at: "2020-01-01T00:00:00+00:00" }, timezone: "UTC" },
      detail: "once schedule must be in the future",
    },
    {
      name: "interval-below-floor",
      body: { schedule_type: "interval", schedule_spec: { every_seconds: 10 }, timezone: "UTC" },
      detailPattern: /interval schedule must be at least \d+ seconds/,
    },
  ];
  const observed = {};
  for (const testCase of cases) {
    const response = await request.post(`${APP}/api/scheduled-tasks`, {
      data: {
        context_mode: "fresh_thread_per_run",
        title: marker(`SCH-06-${testCase.name}`),
        prompt: "invalid schedule negative",
        ...testCase.body,
      },
    });
    expect(response.status(), `${testCase.name}: ${await response.text()}`).toBe(422);
    const payload = await response.json();
    expect(typeof payload.detail, `${testCase.name}: detail must be a string`).toBe("string");
    if (testCase.detail) {
      expect(payload.detail, `${testCase.name}: frozen detail text`).toBe(testCase.detail);
    } else {
      expect(payload.detail, `${testCase.name}: frozen detail shape`).toMatch(testCase.detailPattern);
    }
    observed[testCase.name] = payload.detail;
  }

  // Positive control: an in-range schedule is accepted, proving the 422s above are
  // rejections and not a blanket failure.
  const ok = await createTask(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title: marker("SCH-06-valid"),
    prompt: "Reply with the single word SCHEDULED_SCH06_OK and nothing else. Do not use tools.",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 600 },
    timezone: "UTC",
  });
  assertTaskContract(ok, "valid");
  expect(ok.status).toBe("enabled");

  await save(testInfo, "w19-sch06", { observed, validTaskId: ok.id });
});

// SCH-07: the thread page exposes the scheduled-tasks entry carrying ?thread_id=.
test(`${backend}: SCH-07 thread page links to the thread-scoped scheduled-tasks view`, async ({ page }, testInfo) => {
  const chatMarker = marker("SCH-07-thread");
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const streamPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 60_000 },
  );
  await input.fill(`${chatMarker}: reply with the single word OK and nothing else.`);
  await input.press("Enter");
  const stream = await streamPromise;
  expect(stream.status(), await stream.text()).toBe(200);
  const location = stream.headers()["content-location"];
  expect(location, "the run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/[^/]+$/);
  expect(match, `unexpected Run Content-Location: ${location}`).toBeTruthy();
  const threadId = match[1];

  await page.goto(`/workspace/chats/${encodeURIComponent(threadId)}`, { waitUntil: "domcontentloaded" });
  const link = page.locator('a[href*="scheduled-tasks?thread_id="]');
  await expect(link).toBeVisible({ timeout: 20_000 });
  const href = await link.getAttribute("href");
  expect(href).toContain(`thread_id=${threadId}`);
  await link.click();
  await expect(page).toHaveURL(new RegExp(`thread_id=${threadId}`));
  await expect(page.getByTestId("scheduled-task-list")).toBeVisible({ timeout: 20_000 });

  await screenshot(testInfo, page, "w19-sch07-thread-link.png");
  await save(testInfo, "w19-sch07", { threadId, href });
});
