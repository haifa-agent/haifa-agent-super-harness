// W19 M4: frozen /workspace/scheduled-tasks page against the Haifa backend and the real model.
//
// The spec drives the frozen UI for the create/edit/pause/resume/trigger/history/refresh chain and
// cross-checks the underlying /api/scheduled-tasks state. Scheduled Runs execute on the real
// managed model; the spec never mocks the provider and never rewrites the frozen frontend.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "26618"}`;
const PAGE_PATH = "/workspace/scheduled-tasks";

const ACTIVE = new Set(["queued", "launching", "running"]);
const TERMINAL = new Set(["success", "failed", "skipped", "interrupted"]);

const state = {};

function marker(prefix) {
  return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function saveEvidence(testInfo, name, payload) {
  const file = testInfo.outputPath(name);
  fs.writeFileSync(file, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  await testInfo.attach(name, { path: file, contentType: "application/json" });
}

async function screenshot(testInfo, page, name) {
  const file = testInfo.outputPath(name);
  await page.screenshot({ path: file, fullPage: true });
  await testInfo.attach(name, { path: file, contentType: "image/png" });
}

async function apiListTasks(request) {
  const response = await request.get(`${APP}/api/scheduled-tasks`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function apiFindTask(request, id) {
  const tasks = await apiListTasks(request);
  return tasks.find((task) => task.id === id) ?? null;
}

async function apiRuns(request, taskId) {
  const response = await request.get(
    `${APP}/api/scheduled-tasks/${encodeURIComponent(taskId)}/runs?limit=50&offset=0`,
  );
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function apiCreate(request, payload) {
  const response = await request.post(`${APP}/api/scheduled-tasks`, { data: payload });
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

// Bounded wait with an explicit deadline; no unbounded retry loop.
async function waitForRuns(request, taskId, predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  let observed = [];
  for (;;) {
    observed = await apiRuns(request, taskId);
    if (observed.some(predicate)) {
      return observed;
    }
    if (Date.now() >= deadline) {
      throw new Error(
        `timed out waiting for ${label}; observed ${observed
          .map((run) => `${run.trigger}:${run.status}`)
          .join(",") || "<none>"}`,
      );
    }
    await sleep(1_000);
  }
}

async function waitForTask(request, title, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const task = (await apiListTasks(request)).find((entry) => entry.title === title);
    if (task) {
      return task;
    }
    if (Date.now() >= deadline) {
      throw new Error(`timed out waiting for created task '${title}'`);
    }
    await sleep(500);
  }
}

async function gotoPage(page) {
  await page.goto(PAGE_PATH, { waitUntil: "domcontentloaded" });
  await expect(page.getByTestId("scheduled-task-create-form")).toBeVisible();
  await expect(page.getByTestId("scheduled-task-detail")).toBeVisible();
}

async function createIntervalTask(page, { title, prompt, amount, unit = "seconds" }) {
  await gotoPage(page);
  const form = page.getByTestId("scheduled-task-create-form");
  await form.getByRole("button", { name: "Interval", exact: true }).click();
  await form.getByPlaceholder("Task title").fill(title);
  await form.getByPlaceholder("Prompt").fill(prompt);
  const schedule = form.getByTestId("schedule-input");
  if (amount !== undefined) {
    const input = schedule.getByLabel("Every");
    await input.fill("");
    await input.fill(String(amount));
    await input.blur();
  }
  await schedule.getByTestId("schedule-interval-unit").click();
  await page.getByRole("option", { name: unit, exact: true }).click();
  await form.getByRole("button", { name: "Create", exact: true }).click();
  // The mutation is asynchronous; wait until the created row is actually rendered.
  await expect(page.getByTestId("scheduled-task-list")).toContainText(title, {
    timeout: 20_000,
  });
}

async function selectTask(page, taskId, title) {
  const item = page.getByTestId(`scheduled-task-item-${taskId}`);
  await expect(item).toBeVisible({ timeout: 20_000 });
  await item.click();
  const detail = page.getByTestId("scheduled-task-detail");
  if (title) {
    await expect(detail).toContainText(title, { timeout: 10_000 });
  }
  return item;
}

test("W19-M4-01 interval task created in the frozen UI is listed and detailed consistently", async ({
  page,
  request,
}, testInfo) => {
  const title = marker("W19-M4-01-interval");
  const prompt = "Reply with the single word SCHEDULED_M4_01_OK and nothing else. Do not use tools.";
  await createIntervalTask(page, { title, prompt });

  const created = await waitForTask(request, title);
  expect(created.schedule_type).toBe("interval");
  expect(created.schedule_spec.every_seconds).toBe(60);
  expect(created.status).toBe("enabled");
  expect(created.next_run_at).toBeTruthy();
  expect(created.context_mode).toBe("fresh_thread_per_run");

  await selectTask(page, created.id, title);
  const detail = page.getByTestId("scheduled-task-detail");
  await expect(detail).toContainText("Interval");
  // The frozen detail view does not repeat the status; the list row renders the summary
  // "Interval . Enabled" (scheduleType . status), which is the visible status surface.
  const row = page.getByTestId(`scheduled-task-item-${created.id}`);
  await expect(row).toContainText("Interval");
  await expect(row).toContainText("Enabled");

  state.task01 = created;
  await screenshot(testInfo, page, "w19-m4-01-create.png");
  await saveEvidence(testInfo, "w19-m4-01.json", { title, created });
});

test("W19-M4-02 a due interval task auto-triggers a real Run and run history shows a terminal occurrence", async ({
  page,
  request,
}, testInfo) => {
  expect(state.task01, "M4-01 must have created the interval task").toBeTruthy();
  const taskId = state.task01.id;
  const startedAt = Date.now();

  const scheduledRuns = await waitForRuns(
    request,
    taskId,
    (run) => run.trigger === "scheduled",
    180_000,
    "scheduled occurrence",
  );
  expect(scheduledRuns.filter((run) => run.trigger === "scheduled").length).toBeGreaterThanOrEqual(1);

  const terminalRuns = await waitForRuns(
    request,
    taskId,
    (run) => run.trigger === "scheduled" && TERMINAL.has(run.status),
    180_000,
    "terminal scheduled occurrence",
  );
  const terminal = terminalRuns.find(
    (run) => run.trigger === "scheduled" && TERMINAL.has(run.status),
  );
  expect(terminal.status).toBe("success");
  expect(terminal.run_id, "a successful occurrence must carry a real Run id").toBeTruthy();
  expect(terminal.thread_id, "a successful occurrence must carry a real Thread id").toBeTruthy();
  expect(terminal.finished_at).toBeTruthy();

  const task = await apiFindTask(request, taskId);
  expect(task.run_count).toBeGreaterThanOrEqual(1);
  expect(task.last_run_id, "a triggered task records its latest Run id").toBeTruthy();
  expect(
    terminalRuns.some((run) => run.run_id === task.last_run_id),
    "the recorded last Run id belongs to an admitted occurrence",
  ).toBe(true);

  await gotoPage(page);
  await selectTask(page, taskId, state.task01.title);
  const runList = page.getByTestId("scheduled-task-run-list");
  await expect(runList).toContainText("scheduled", { timeout: 20_000 });
  await expect(runList).toContainText("Success", { timeout: 20_000 });

  state.occurrence02 = terminal;
  state.waitedMs02 = Date.now() - startedAt;
  await screenshot(testInfo, page, "w19-m4-02-history.png");
  await saveEvidence(testInfo, "w19-m4-02.json", { terminal, waitedMs: state.waitedMs02 });
});

test("W19-M4-03 manual trigger works, works while paused, and repeated triggers do not stack", async ({
  page,
  request,
}, testInfo) => {
  const title = marker("W19-M4-03-manual");
  const prompt =
    "Reply with the single word SCHEDULED_M4_03_OK and nothing else. Do not use tools.";
  await createIntervalTask(page, { title, prompt, amount: 3600 });
  const task = await waitForTask(request, title);
  expect(task.schedule_spec.every_seconds).toBe(3600);

  await selectTask(page, task.id, title);
  await page.getByRole("button", { name: "Trigger now", exact: true }).click();
  await waitForRuns(
    request,
    task.id,
    (run) => run.trigger === "manual" && TERMINAL.has(run.status),
    180_000,
    "manual occurrence",
  );

  // Pause the task, then trigger again: a manual trigger is allowed while paused.
  const pause = await request.post(`${APP}/api/scheduled-tasks/${encodeURIComponent(task.id)}/pause`);
  expect(pause.status(), await pause.text()).toBe(200);
  expect((await apiFindTask(request, task.id)).status).toBe("paused");

  const triggerWhilePaused = await request.post(
    `${APP}/api/scheduled-tasks/${encodeURIComponent(task.id)}/trigger`,
  );
  expect(triggerWhilePaused.status(), await triggerWhilePaused.text()).toBe(200);
  const afterPausedTrigger = await waitForRuns(
    request,
    task.id,
    (run) => run.trigger === "manual" && TERMINAL.has(run.status),
    180_000,
    "paused manual occurrence",
  );
  expect(
    (await apiFindTask(request, task.id)).status,
    "a manual trigger must not silently resume the task",
  ).toBe("paused");

  const manual = afterPausedTrigger.filter((run) => run.trigger === "manual");
  expect(manual.length).toBeGreaterThanOrEqual(2);
  const active = afterPausedTrigger.filter((run) => ACTIVE.has(run.status));
  expect(active.length, "a task must never have two active occurrences at once").toBeLessThanOrEqual(1);

  state.task03 = task;
  await saveEvidence(testInfo, "w19-m4-03.json", {
    taskId: task.id,
    manualCount: manual.length,
    activeCount: active.length,
    statusAfterPausedTrigger: "paused",
  });
});

test("W19-M4-04 pause, resume and delete UI semantics plus the active-run 409 boundary", async ({
  page,
  request,
}, testInfo) => {
  const title = marker("W19-M4-04-lifecycle");
  const longPrompt =
    "Print every integer from 1 through 300, one per line. Write the full list with no abbreviation, commentary, code fence, or tools.";
  await createIntervalTask(page, { title, prompt: longPrompt, amount: 3600 });
  const task = await waitForTask(request, title);
  await selectTask(page, task.id, title);

  // Pause: the frozen detail swaps the action to Resume and the backend reports paused.
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await expect(page.getByRole("button", { name: "Resume", exact: true })).toBeVisible({
    timeout: 15_000,
  });
  expect((await apiFindTask(request, task.id)).status).toBe("paused");

  // Resume: back to enabled.
  await page.getByRole("button", { name: "Resume", exact: true }).click();
  await expect(page.getByRole("button", { name: "Pause", exact: true })).toBeVisible({
    timeout: 15_000,
  });
  expect((await apiFindTask(request, task.id)).status).toBe("enabled");

  // 409 boundary: start a long real Run and pause while its occurrence is still active.
  await page.getByRole("button", { name: "Trigger now", exact: true }).click();
  await waitForRuns(
    request,
    task.id,
    (run) => ACTIVE.has(run.status) || TERMINAL.has(run.status),
    60_000,
    "occurrence admitted",
  );
  let pauseConflict = 0;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const runs = await apiRuns(request, task.id);
    if (!runs.some((run) => ACTIVE.has(run.status))) {
      break;
    }
    const conflict = await request.post(
      `${APP}/api/scheduled-tasks/${encodeURIComponent(task.id)}/pause`,
    );
    if (conflict.status() === 409) {
      pauseConflict = 409;
      break;
    }
    await sleep(200);
  }
  expect(pauseConflict, "pausing an actively running task must be rejected with 409").toBe(409);
  await waitForRuns(
    request,
    task.id,
    (run) => run.trigger === "manual" && TERMINAL.has(run.status),
    180_000,
    "long manual occurrence terminal",
  );

  // Delete: the row disappears from the frozen list and the backend list.
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("button", { name: "Delete", exact: true }).last().click();
  await expect(page.getByTestId(`scheduled-task-item-${task.id}`)).toHaveCount(0, {
    timeout: 20_000,
  });
  const afterDelete = await apiListTasks(request);
  expect(afterDelete.some((entry) => entry.id === task.id)).toBe(false);

  state.deletedTask04 = task.id;
  await screenshot(testInfo, page, "w19-m4-04-lifecycle.png");
  await saveEvidence(testInfo, "w19-m4-04.json", { taskId: task.id, pauseConflict });
});

test("W19-M4-06 refresh keeps tasks, history and status consistent with no ghost or lost rows", async ({
  page,
  request,
}, testInfo) => {
  const before = await apiListTasks(request);
  const beforeTitles = before.map((task) => task.title).sort();

  await gotoPage(page);
  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.getByTestId("scheduled-task-detail")).toBeVisible();

  const after = await apiListTasks(request);
  expect(after.map((task) => task.title).sort()).toEqual(beforeTitles);
  expect(after.some((task) => task.id === state.deletedTask04)).toBe(false);

  const rows = page.locator('[data-testid^="scheduled-task-item-"]');
  await expect(rows).toHaveCount(after.length, { timeout: 20_000 });
  const rowIds = await rows.evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute("data-testid").replace("scheduled-task-item-", "")),
  );
  expect(new Set(rowIds).size).toBe(rowIds.length);
  expect([...rowIds].sort()).toEqual(after.map((task) => task.id).sort());

  if (state.task01) {
    const runs = await apiRuns(request, state.task01.id);
    await selectTask(page, state.task01.id, state.task01.title);
    await expect(page.getByTestId("scheduled-task-run-list")).toContainText("scheduled", {
      timeout: 20_000,
    });
    const count = await page.getByTestId("scheduled-task-runs").innerText();
    expect(count).toContain(String(runs.length));
  }

  await screenshot(testInfo, page, "w19-m4-06-refresh.png");
  await saveEvidence(testInfo, "w19-m4-06.json", { taskCount: after.length, rowIds });
});

test("W19-M4-08 the scheduled Run exposes a verifiable Run identity and real model output", async ({
  request,
}, testInfo) => {
  expect(state.occurrence02, "M4-02 must have produced a successful occurrence").toBeTruthy();
  const { run_id: runId, thread_id: threadId } = state.occurrence02;

  const runResponse = await request.get(
    `${APP}/api/langgraph/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}`,
  );
  expect(runResponse.status(), await runResponse.text()).toBe(200);
  const run = await runResponse.json();
  expect(run.status).toBe("success");

  const stateResponse = await request.get(
    `${APP}/api/langgraph/threads/${encodeURIComponent(threadId)}/state`,
  );
  expect(stateResponse.status(), await stateResponse.text()).toBe(200);
  const threadState = await stateResponse.json();
  const assistant = [...(threadState.values.messages ?? [])]
    .reverse()
    .find((message) => message.type === "ai");
  expect(assistant, "the scheduled Run must persist an assistant message").toBeTruthy();
  expect(String(assistant.content ?? "").trim().length).toBeGreaterThan(0);

  const outputSha256 = crypto
    .createHash("sha256")
    .update(String(assistant.content))
    .digest("hex");

  await saveEvidence(testInfo, "w19-m4-08.json", {
    runId,
    threadId,
    runStatus: run.status,
    assistantLength: String(assistant.content).length,
    outputSha256,
    provider: "https://api.deepseek.com",
    credentialSource: "os",
  });
});

test("W19-M4-05 the thread entry links to the thread-bound scheduled task view", async ({
  page,
  request,
}, testInfo) => {
  // A Thread must exist to bind a reuse_thread task. Materialize one with a real chat Run.
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const chatMarker = marker("W19-M4-05-thread");
  const streamPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      ),
    { timeout: 60_000 },
  );
  await input.fill(`${chatMarker}: reply with the single word OK and nothing else.`);
  await input.press("Enter");
  const stream = await streamPromise;
  expect(stream.status(), await stream.text()).toBe(200);
  const location = stream.headers()["content-location"];
  expect(location).toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match).toBeTruthy();
  const threadId = match[1];
  await page.goto(`/workspace/chats/${encodeURIComponent(threadId)}`, {
    waitUntil: "domcontentloaded",
  });

  const title = marker("W19-M4-05-bound");
  const created = await apiCreate(request, {
    context_mode: "reuse_thread",
    thread_id: threadId,
    assistant_id: "lead_agent",
    title,
    prompt: "Reply with the single word OK and nothing else. Do not use tools.",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 3600 },
    timezone: "UTC",
  });
  expect(created.thread_id).toBe(threadId);

  const link = page.locator('a[href*="scheduled-tasks?thread_id="]');
  await expect(link).toBeVisible({ timeout: 20_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`thread_id=${threadId}`));
  await expect(page.getByTestId("scheduled-task-list")).toContainText(title, {
    timeout: 20_000,
  });

  state.task05 = created;
  await screenshot(testInfo, page, "w19-m4-05-thread-link.png");
  await saveEvidence(testInfo, "w19-m4-05.json", { threadId, taskId: created.id, title });
});
