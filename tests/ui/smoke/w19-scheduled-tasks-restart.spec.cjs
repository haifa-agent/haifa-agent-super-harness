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

  // The warm round persisted an explicit stale `launching` claim before its process stopped; that is
  // the pre-shutdown active occurrence this round must converge (not a vacuous empty set).
  const seedFile = path.join(DATA_ROOT, "w19-m4-restart-seed.json");
  expect(fs.existsSync(seedFile), `the warm round must persist the stale-claim seed at ${seedFile}`).toBe(
    true,
  );
  const seed = JSON.parse(fs.readFileSync(seedFile, "utf8"));
  expect(seed.status, "the seed describes a pre-shutdown active occurrence").toBe("launching");

  const store = JSON.parse(fs.readFileSync(storeFile, "utf8"));
  const storeTaskIds = (store.tasks ?? []).map((task) => task.id);

  const tasks = await apiListTasks(request);
  const byPrefix = (prefix) => tasks.filter((task) => task.title.startsWith(prefix));
  expect(byPrefix("W19-M4-01-interval").length).toBeGreaterThanOrEqual(1);
  expect(byPrefix("W19-M4-05-bound").length).toBeGreaterThanOrEqual(1);
  expect(storeTaskIds, "the reopened data root must own the seeded holder task").toContain(seed.taskId);
  for (const task of tasks) {
    expect(storeTaskIds, "the reopened data root must own every listed task").toContain(task.id);
  }

  // Convergence of the explicitly seeded stale claim: startup recovery must move it out of the
  // dangling `launching` state (with no committed Run) and re-dispatch it onto a real Run, so it
  // terminates from a committed Run rather than staying a dangling claim. With recovery disabled the
  // claim would remain `launching` here and this wait would fail.
  const seededSettled = await waitForRuns(
    request,
    seed.taskId,
    (run) => run.id === seed.occurrenceId && run.status !== "launching",
    60_000,
    "stale launch converged out of launching",
  );
  const seededRun = seededSettled.find((run) => run.id === seed.occurrenceId);
  expect(seededRun.status).not.toBe("launching");

  const seededTerminal = await waitForRuns(
    request,
    seed.taskId,
    (run) => run.id === seed.occurrenceId && TERMINAL.has(run.status),
    200_000,
    "stale launch terminal after re-dispatch",
  );
  const converged = seededTerminal.find((run) => run.id === seed.occurrenceId);
  expect(converged.status).toBe("success");
  expect(
    converged.run_id,
    "the recovered claim must carry the committed Run it was re-dispatched to",
  ).toBeTruthy();

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

  // Rescheduling: the interval task from the warm round (paused there only to freeze the store while
  // the claim was injected) is re-armed, and must fire again on the reopened instance, proving the
  // worker restarted and the plan was rescheduled from persisted state.
  const interval = byPrefix("W19-M4-01-interval")[0];
  if (interval.status === "paused") {
    const resumed = await request.post(
      `${APP}/api/scheduled-tasks/${encodeURIComponent(interval.id)}/resume`,
    );
    expect(resumed.status(), await resumed.text()).toBe(200);
  }
  const before = await apiRuns(request, interval.id);
  const beforeIds = new Set(before.map((run) => run.id));
  expect(
    before.some((run) => run.trigger === "scheduled" && TERMINAL.has(run.status)),
    "a pre-restart terminal occurrence must still be readable",
  ).toBe(true);

  const after = await waitForRuns(
    request,
    interval.id,
    (run) => !beforeIds.has(run.id) && run.trigger === "scheduled",
    200_000,
    "new scheduled occurrence after restart",
  );
  const newRun = after.find((run) => !beforeIds.has(run.id) && run.trigger === "scheduled");
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
    seed: {
      occurrenceId: seed.occurrenceId,
      taskId: seed.taskId,
      threadId: seed.threadId,
      preShutdownStatus: seed.status,
      seededAt: seed.seededAt,
    },
    seededConvergence: {
      status: converged.status,
      runId: converged.run_id,
      startedAt: converged.started_at,
      finishedAt: converged.finished_at,
    },
    storeTaskIds,
    taskIds: tasks.map((task) => task.id),
    danglingLaunches,
    activeWithoutRun,
    history,
    newRunId: newRun.id,
  });
});
