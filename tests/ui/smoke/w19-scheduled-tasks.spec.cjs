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
const DATA_ROOT = process.env.SUPER_HARNESS_DATA_ROOT;

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

// Cases must be runnable on their own (`-CaseTitle`). Every prerequisite is created or discovered
// from the persisted state instead of relying on an earlier case's in-memory `state` slot.
async function ensureEnabledIntervalTask(request, titlePrefix, prompt) {
  const existing = (await apiListTasks(request)).find((task) => task.title.startsWith(titlePrefix));
  if (existing) {
    if (existing.status === "paused") {
      // A previous round may have paused the task while freezing the store; re-arm it so its
      // interval fires again. A concurrent occurrence keeps the task busy, so retry bounded.
      const deadline = Date.now() + 30_000;
      for (;;) {
        const resumed = await request.post(
          `${APP}/api/scheduled-tasks/${encodeURIComponent(existing.id)}/resume`,
        );
        if (resumed.status() === 200) {
          break;
        }
        if (Date.now() >= deadline) {
          throw new Error(
            `timed out resuming interval task '${existing.title}': ${await resumed.text()}`,
          );
        }
        await sleep(1_000);
      }
    }
    return apiFindTask(request, existing.id);
  }
  const created = await apiCreate(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title: marker(titlePrefix),
    prompt,
    schedule_type: "interval",
    schedule_spec: { every_seconds: 60 },
    timezone: "UTC",
  });
  return waitForTask(request, created.title);
}

// Discover an already-successful occurrence with a committed Run/Thread so M4-08 is independent of
// M4-02's module state; if none exists yet, admit one through the real trigger route and wait.
async function ensureSuccessfulOccurrence(request) {
  for (const task of await apiListTasks(request)) {
    const runs = await apiRuns(request, task.id);
    const found = runs.find((run) => run.status === "success" && run.run_id && run.thread_id);
    if (found) {
      return found;
    }
  }
  const created = await apiCreate(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title: marker("W19-M4-08-standalone"),
    prompt: "Reply with the single word SCHEDULED_M4_08_OK and nothing else. Do not use tools.",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 3600 },
    timezone: "UTC",
  });
  const triggered = await request.post(
    `${APP}/api/scheduled-tasks/${encodeURIComponent(created.id)}/trigger`,
  );
  expect(triggered.status(), await triggered.text()).toBe(200);
  const runs = await waitForRuns(
    request,
    created.id,
    (run) => run.status === "success" && run.run_id && run.thread_id,
    180_000,
    "standalone successful occurrence",
  );
  return runs.find((run) => run.status === "success" && run.run_id && run.thread_id);
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
  // Self-contained: use M4-01's task when it ran, otherwise discover (or create) an enabled
  // interval task; never depend on the earlier case's module state.
  const intervalTask =
    (state.task01 && state.task01.status === "enabled" ? state.task01 : null) ??
    (await ensureEnabledIntervalTask(
      request,
      "W19-M4-01-interval",
      "Reply with the single word SCHEDULED_M4_01_OK and nothing else. Do not use tools.",
    ));
  expect(intervalTask, "an enabled interval task must be available for M4-02").toBeTruthy();
  const taskId = intervalTask.id;
  state.task01 = intervalTask;
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
    "Print every integer from 1 through 300, one per line. Write the full list with no abbreviation, commentary, code fence, or tools.";
  await createIntervalTask(page, { title, prompt, amount: 3600 });
  const task = await waitForTask(request, title);
  expect(task.schedule_spec.every_seconds).toBe(3600);

  await selectTask(page, task.id, title);
  await page.getByRole("button", { name: "Trigger now", exact: true }).click();

  // Overlap window: while the first manual occurrence is still active, burst concurrent triggers.
  // The single-active-occurrence rule must reject every competing trigger and the peak active count
  // observed must never exceed one; waiting for the first to finish would make the guard vacuous.
  await waitForRuns(
    request,
    task.id,
    (run) => ACTIVE.has(run.status),
    60_000,
    "active manual occurrence",
  );
  const burst = await Promise.all(
    Array.from({ length: 5 }, () =>
      request.post(`${APP}/api/scheduled-tasks/${encodeURIComponent(task.id)}/trigger`),
    ),
  );
  const burstStatuses = burst.map((response) => response.status());
  expect(
    burstStatuses.every((status) => status === 409),
    `concurrent triggers during an active occurrence must all be rejected, saw ${burstStatuses.join(",")}`,
  ).toBe(true);

  // Observe the peak active count across the whole overlap window (before and after the burst).
  let peakActive = 0;
  const overlapDeadline = Date.now() + 30_000;
  for (;;) {
    const runs = await apiRuns(request, task.id);
    const activeNow = runs.filter((run) => ACTIVE.has(run.status)).length;
    peakActive = Math.max(peakActive, activeNow);
    if (activeNow === 0 || Date.now() >= overlapDeadline) {
      break;
    }
    await sleep(250);
  }
  expect(peakActive, "a task must never have two active occurrences at once").toBeLessThanOrEqual(1);

  const firstManual = await waitForRuns(
    request,
    task.id,
    (run) => run.trigger === "manual" && TERMINAL.has(run.status),
    180_000,
    "manual occurrence",
  );
  expect(firstManual.some((run) => run.trigger === "manual" && TERMINAL.has(run.status))).toBe(true);

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
    peakActive,
    burstStatuses,
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

  const historyTask =
    state.task01 ??
    (await apiListTasks(request)).find((task) => task.title.startsWith("W19-M4-01-interval")) ??
    null;
  if (historyTask) {
    const runs = await apiRuns(request, historyTask.id);
    await selectTask(page, historyTask.id, historyTask.title);
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
  // Self-contained: prefer M4-02's occurrence, otherwise discover an already-successful occurrence
  // from the persisted state (or admit one) so this case does not depend on case order.
  const occurrence = state.occurrence02 ?? (await ensureSuccessfulOccurrence(request));
  expect(occurrence, "a successful scheduled occurrence must be available").toBeTruthy();
  state.occurrence02 = occurrence;
  const { run_id: runId, thread_id: threadId } = occurrence;

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

// M4-07 setup, run last in the warm round. M4-07 previously reopened a data root whose occurrences
// had all reached a terminal state before shutdown, so its "no dangling launching" convergence
// assertion was vacuous (it would pass even with recovery fully disabled). Persist an explicit stale
// `launching` claim (no committed Run) into the scheduler store before the round's process stops, so
// the restart round must actively converge it: the claim is re-queued and re-dispatched onto a real
// Run. The claim mirrors the crash window between the atomic claim and the association write.
test("W19-M4-07a the warm round persists a stale launching occurrence before the process stops", async ({
  request,
}, testInfo) => {
  expect(DATA_ROOT, "the warm round must run on an explicit data root").toBeTruthy();
  const storeFile = path.join(DATA_ROOT, "scheduler", "state.json");
  expect(fs.existsSync(storeFile), `scheduler store must exist at ${storeFile}`).toBe(true);

  // A host Thread that already exists, so restart `recoverLaunch` can read the Thread's committed
  // Runs instead of fail-closing on an unknown Thread.
  const interval = (await apiListTasks(request)).find((task) =>
    task.title.startsWith("W19-M4-01-interval"),
  );
  expect(interval, "the warm interval task must exist before seeding").toBeTruthy();
  const hostRun = (await apiRuns(request, interval.id)).find(
    (run) => TERMINAL.has(run.status) && run.thread_id,
  );
  expect(hostRun, "a terminal occurrence with a real Thread is required as the stale host").toBeTruthy();

  // Dedicated holder task so seeding does not perturb the interval task M4-07 later resumes for its
  // rescheduling proof. Not due for an hour, so it cannot mutate the store during the freeze.
  const holderTitle = marker("W19-M4-07a-stale-holder");
  const holder = await apiCreate(request, {
    context_mode: "fresh_thread_per_run",
    assistant_id: "lead_agent",
    title: holderTitle,
    prompt: "Reply with the single word SCHEDULED_M4_07A_OK and nothing else. Do not use tools.",
    schedule_type: "interval",
    schedule_spec: { every_seconds: 3600 },
    timezone: "UTC",
  });

  // Freeze the scheduler so no further store mutation can overwrite the injected claim: pause the
  // only due task (the interval task) and wait for every active occurrence to settle.
  const pauseDeadline = Date.now() + 60_000;
  for (;;) {
    const paused = await request.post(
      `${APP}/api/scheduled-tasks/${encodeURIComponent(interval.id)}/pause`,
    );
    if (paused.status() === 200) {
      break;
    }
    if (Date.now() >= pauseDeadline) {
      throw new Error(`could not pause the interval task before seeding: ${await paused.text()}`);
    }
    await sleep(1_000);
  }
  expect((await apiFindTask(request, interval.id)).status).toBe("paused");
  const settleDeadline = Date.now() + 60_000;
  for (;;) {
    let activeNow = 0;
    for (const task of await apiListTasks(request)) {
      activeNow += (await apiRuns(request, task.id)).filter((run) => ACTIVE.has(run.status)).length;
    }
    if (activeNow === 0) {
      break;
    }
    if (Date.now() >= settleDeadline) {
      throw new Error(`occurrences did not settle before seeding (active=${activeNow})`);
    }
    await sleep(1_000);
  }

  const store = JSON.parse(fs.readFileSync(storeFile, "utf8"));
  const holderNode = (store.tasks ?? []).find((task) => task.id === holder.id);
  expect(holderNode, "the holder task must be persisted in the store").toBeTruthy();
  const occurrenceId = `occ-stale-${crypto.randomUUID().replace(/-/g, "").slice(0, 24)}`;
  const seededAt = new Date().toISOString();
  holderNode.last_occurrence_seq = Number(holderNode.last_occurrence_seq ?? 0) + 1;
  store.runs.push({
    id: occurrenceId,
    task_id: holder.id,
    occurrence_seq: holderNode.last_occurrence_seq,
    thread_id: hostRun.thread_id,
    run_id: null,
    scheduled_for: seededAt,
    trigger: "scheduled",
    status: "launching",
    error: null,
    started_at: null,
    finished_at: null,
    attempt_count: 1,
    launch_accounted: false,
    created_at: seededAt,
  });
  const temporary = `${storeFile}.w19-seed.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(store, null, 2)}\n`, "utf8");
  fs.renameSync(temporary, storeFile);

  const readBack = JSON.parse(fs.readFileSync(storeFile, "utf8"));
  const seeded = (readBack.runs ?? []).find((run) => run.id === occurrenceId);
  expect(seeded, "the stale launching claim must be persisted before the process stops").toBeTruthy();
  expect(seeded.status).toBe("launching");
  expect(seeded.run_id).toBe(null);

  const seedFile = path.join(DATA_ROOT, "w19-m4-restart-seed.json");
  fs.writeFileSync(
    seedFile,
    `${JSON.stringify(
      {
        schema: "w19-m4-restart-seed/1",
        seededAt,
        occurrenceId,
        taskId: holder.id,
        holderTitle,
        threadId: hostRun.thread_id,
        status: "launching",
      },
      null,
      2,
    )}\n`,
    "utf8",
  );

  state.seed07 = { occurrenceId, taskId: holder.id, threadId: hostRun.thread_id };
  await saveEvidence(testInfo, "w19-m4-07a-seed.json", {
    occurrenceId,
    taskId: holder.id,
    holderTitle,
    threadId: hostRun.thread_id,
    status: "launching",
    storeFile,
    seedFile,
  });
});
