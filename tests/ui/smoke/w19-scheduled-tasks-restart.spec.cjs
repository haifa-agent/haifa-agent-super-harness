// W19 M4 restart round: the runner reopens the exact data root of the warm round, so this spec
// proves startup recovery, history readability and rescheduling on one instance and one data root.
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "26618"}`;
const DATA_ROOT = process.env.SUPER_HARNESS_DATA_ROOT;

const ACTIVE = new Set(["queued", "launching", "running"]);
const TERMINAL = new Set(["success", "failed", "skipped", "interrupted"]);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function saveEvidence(testInfo, name, payload) {
  const file = testInfo.outputPath(name);
  fs.writeFileSync(file, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  await testInfo.attach(name, { path: file, contentType: "application/json" });
}

async function apiListTasks(request) {
  const response = await request.get(`${APP}/api/scheduled-tasks`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function apiRuns(request, taskId) {
  const response = await request.get(
    `${APP}/api/scheduled-tasks/${encodeURIComponent(taskId)}/runs?limit=50&offset=0`,
  );
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

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
    await sleep(2_000);
  }
}

test("W19-M4-07 same-data-root restart keeps tasks and history, converges stale occurrences, and reschedules", async ({
  request,
}, testInfo) => {
  expect(DATA_ROOT, "the restart round must run on an explicit data root").toBeTruthy();
  const storeFile = path.join(DATA_ROOT, "scheduler", "state.json");
  expect(fs.existsSync(storeFile), `scheduler store must survive at ${storeFile}`).toBe(true);
  const store = JSON.parse(fs.readFileSync(storeFile, "utf8"));
  const storeTaskIds = (store.tasks ?? []).map((task) => task.id);

  const tasks = await apiListTasks(request);
  const byPrefix = (prefix) => tasks.filter((task) => task.title.startsWith(prefix));
  expect(byPrefix("W19-M4-01-interval").length).toBeGreaterThanOrEqual(1);
  expect(byPrefix("W19-M4-05-bound").length).toBeGreaterThanOrEqual(1);
  for (const task of tasks) {
    expect(storeTaskIds, "the reopened data root must own every listed task").toContain(task.id);
  }

  // Convergence: no occurrence may remain a dangling `launching` claim after restart, and any
  // still-active occurrence must already carry a committed Run id.
  let danglingLaunches = 0;
  let activeWithoutRun = 0;
  const history = {};
  for (const task of tasks) {
    const runs = await apiRuns(request, task.id);
    history[task.id] = runs.map((run) => ({
      trigger: run.trigger,
      status: run.status,
      runId: run.run_id,
    }));
    for (const run of runs) {
      if (run.status === "launching") danglingLaunches += 1;
      if (ACTIVE.has(run.status) && !run.run_id) activeWithoutRun += 1;
    }
    if (task.status === "enabled" && task.next_run_at) {
      expect(new Date(task.next_run_at).getTime()).toBeGreaterThan(Date.now() - 5_000);
    }
  }
  expect(danglingLaunches, "recovery must not leave a dangling launching occurrence").toBe(0);
  expect(activeWithoutRun, "an active occurrence must reference a committed Run").toBe(0);

  // Rescheduling: the interval task created before the restart must fire again on the reopened
  // instance, proving the worker restarted and the plan was rescheduled from persisted state.
  const interval = byPrefix("W19-M4-01-interval")[0];
  const before = await apiRuns(request, interval.id);
  const beforeCount = before.length;
  expect(
    before.some((run) => run.trigger === "scheduled" && TERMINAL.has(run.status)),
    "the pre-restart terminal occurrence must still be readable",
  ).toBe(true);

  const after = await waitForRuns(
    request,
    interval.id,
    (run) => !before.some((previous) => previous.id === run.id),
    200_000,
    "new occurrence after restart",
  );
  const newRun = after.find((run) => !before.some((previous) => previous.id === run.id));
  expect(newRun, "a new occurrence must be admitted after restart").toBeTruthy();

  const settled = await waitForRuns(
    request,
    interval.id,
    (run) => run.id === newRun.id && TERMINAL.has(run.status),
    200_000,
    "post-restart occurrence terminal",
  );
  expect(settled.find((run) => run.id === newRun.id).status).toBe("success");

  await saveEvidence(testInfo, "w19-m4-07.json", {
    dataRoot: DATA_ROOT,
    storeTaskIds,
    taskIds: tasks.map((task) => task.id),
    danglingLaunches,
    activeWithoutRun,
    history,
    newRunId: newRun.id,
  });
});
