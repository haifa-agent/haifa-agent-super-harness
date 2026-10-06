// W05 live-rendering frozen-UI Oracles.
//
// The unmodified frozen frontend runs against the real Haifa backend and a
// controlled loopback Provider. The Provider holds each Run deliberately
// mid-flight behind an explicit release gate (POST /__release) with a bounded
// timeout, so these cases assert what the page renders *while the run is still
// running* instead of only after it settles. The release is a real trigger, not
// a fixed sleep; the SSE body and rendering are real backend output.
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require("./fixtures.cjs");

const RUN_STREAM = /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/;
const RUN_STREAM_ROUTE = "**/api/langgraph/threads/*/runs/stream*";
const W05_LIVE_FIRST = "W05-LIVE02-FIRST-HALF";
const W05_LIVE_SECOND = "W05-LIVE02-SECOND-HALF";

// LIVE-04 reuses the W08 controlled Child so the frozen Ultra path declares the
// real `task` Tool. The spec shapes only the /runs/stream context; the SSE body
// and the delegation rendering are real backend output.
const CHILD_AGENT = "w08-child";
const CHILD = {
  name: CHILD_AGENT,
  display_name: "W08 Child",
  description: "Deterministic W05 live-rendering child",
  system_prompt:
    "W08-CHILD-MARKER Complete the assigned objective and return a short result.",
  model: "inherit",
  tools: [],
  skills: null,
  max_turns: 4,
  timeout_seconds: 30,
};

function providerEntries(evidenceRoot) {
  const file = fs
    .readdirSync(evidenceRoot)
    .find((name) => /^provider-.*\.stdout\.log$/.test(name));
  if (!file) throw new Error("Controlled Provider log was not found");
  return fs
    .readFileSync(path.join(evidenceRoot, file), "utf8")
    .split(/\r?\n/)
    .filter((line) => line.startsWith("{"))
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

// The Provider's own in-memory scene list. This is the release-gate authority:
// the redirected stdout log may lag behind an in-flight response, so the spec
// must never infer "armed" from the file.
async function providerScenes(context, runtime, marker) {
  const response = await context.request.get(`${runtime.provider}/__state`);
  expect(response.status(), await response.text()).toBe(200);
  const state = await response.json();
  return (state.w05LiveCalls ?? []).filter((entry) => entry.marker === marker);
}

async function waitForScene(context, runtime, marker, predicate = () => true) {
  await expect
    .poll(
      async () =>
        (await providerScenes(context, runtime, marker)).filter(predicate)
          .length,
      { timeout: 30_000 },
    )
    .toBeGreaterThan(0);
  return providerScenes(context, runtime, marker);
}

async function release(context, runtime) {
  const response = await context.request.post(`${runtime.provider}/__release`);
  expect(response.status(), `release gate returned ${response.status()}`).toBe(
    200,
  );
  return response.json();
}

async function submit(page, workspace, text) {
  const { input } = await workspace.open();
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      RUN_STREAM.test(new URL(response.url()).pathname),
    { timeout: 30_000 },
  );
  await input.fill(text);
  await page.getByRole("button", { name: "Submit" }).click();
  const response = await responsePromise;
  // Only the status is checked here: reading the body would wait for the whole SSE stream, that
  // is until the Run ends, and every "while the run is in flight" assertion would then run late.
  expect(response.status()).toBe(200);
  return response;
}

// Reuse the W08 delegated Child catalog so the frozen Ultra request declares the
// real `task` Tool; only the /runs/stream context is shaped, never the response.
async function ensureChild(context, runtime, evidence) {
  const existing = await context.request.get(`${runtime.app}/api/subagents`);
  const catalog = await existing.text();
  expect(existing.status(), catalog).toBe(200);
  const entries = JSON.parse(catalog).subagents ?? [];
  if (entries.some((entry) => entry.name === CHILD_AGENT)) return;
  const created = await context.request.post(`${runtime.app}/api/subagents`, {
    data: CHILD,
  });
  const body = await created.text();
  await evidence.json("live04-seed-child", {
    method: "POST",
    path: "/api/subagents",
    status: created.status(),
    body,
  });
  expect(created.status(), body).toBe(201);
}

async function shapeUltra(page) {
  const shaped = [];
  await page.route(RUN_STREAM_ROUTE, async (route, request) => {
    if (request.method() !== "POST") {
      await route.continue();
      return;
    }
    const body = request.postDataJSON();
    body.context = {
      ...(body.context ?? {}),
      mode: "ultra",
      subagent_enabled: true,
      is_plan_mode: true,
      thinking_enabled: false,
    };
    delete body.context.reasoning_effort;
    shaped.push(body.context);
    await route.continue({ postData: JSON.stringify(body) });
  });
  return shaped;
}

test.beforeEach(async ({ context, runtime }) => {
  await context.addCookies([
    { name: "locale", value: "en-US", url: `${runtime.app}/` },
  ]);
  const me = await context.request.get(`${runtime.app}/api/v1/auth/me`);
  expect(me.status(), await me.text()).toBe(200);
  expect((await me.json()).system_role).toBe("admin");
});

test("W05 LIVE-01 the submitted user message renders exactly one bubble while the run is in flight", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const marker = `w05-live-01-${Date.now()}`;
  await submit(page, workspace, marker);
  // The record only exists once the real Run reached its model call, so the
  // release gate is armed and the Run is provably still running.
  await waitForScene(context, runtime, marker);

  const bubbles = page.locator("#chat .is-user").filter({ hasText: marker });
  // Bounded observation of the in-flight page: record the bubble count while the
  // Run is provably blocked, and fail as soon as a second copy appears.
  const deadline = Date.now() + 12_000;
  const samples = [];
  let maxCount = 0;
  while (Date.now() < deadline) {
    const count = await bubbles.count();
    samples.push({ at: Date.now(), url: page.url(), count });
    maxCount = Math.max(maxCount, count);
    if (count > 1) break;
    await page.waitForTimeout(250);
  }
  await evidence.json("live01-samples", { marker, samples, maxCount });
  expect(
    maxCount,
    "the submitted user message must render exactly once while the run is in flight",
  ).toBe(1);
  await evidence.screenshot(page, "live01-running-single-user-bubble");

  await release(context, runtime);
  await expect(
    page.locator("#chat").getByText(`W05-LIVE01-DONE ${marker}`),
  ).toBeVisible({ timeout: 60_000 });
  await expect(bubbles).toHaveCount(1);
  await evidence.json("live01-user-bubble", {
    marker,
    scenes: await providerScenes(context, runtime, marker),
    provider: providerEntries(runtime.evidence).filter(
      (entry) => entry.type === "w05_live" && entry.marker === marker,
    ),
  });
});

test("W05 LIVE-02 streamed answer text appears before the run finishes", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const marker = `w05-live-02-${Date.now()}`;
  await submit(page, workspace, marker);
  await waitForScene(context, runtime, marker);

  // The Provider has streamed the first half and is holding the connection
  // open before the second half; the page must already show the first half.
  await expect(
    page.locator("#chat").getByText(W05_LIVE_FIRST, { exact: false }),
  ).toBeVisible({ timeout: 15_000 });
  await expect(
    page.locator("#chat").getByText(W05_LIVE_SECOND, { exact: false }),
  ).toHaveCount(0);
  await evidence.screenshot(page, "live02-running-first-half-visible");

  await release(context, runtime);
  await expect(
    page
      .locator("#chat")
      .getByText(`${W05_LIVE_SECOND} ${marker}`, { exact: false }),
  ).toBeVisible({ timeout: 60_000 });
  await evidence.json("live02-streamed-halves", {
    marker,
    scenes: await providerScenes(context, runtime, marker),
  });
});

test("W05 LIVE-03 a Tool step is visible while the run waits after its result", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const marker = `w05-live-03-${Date.now()}`;
  await submit(page, workspace, marker);
  // First model call asks for the read-only Production Tool; the follow-up call
  // carries the Tool result and is blocked, so the step must already be shown.
  const scenes = await waitForScene(
    context,
    runtime,
    marker,
    (entry) => entry.toolResults > 0,
  );
  expect(
    scenes.some((entry) => entry.offeredListUploads),
    "backend must offer list_uploaded_files",
  ).toBe(true);

  await expect(
    page.locator("#chat").getByText('Use "list_uploaded_files" tool', {
      exact: true,
    }),
  ).toBeVisible({ timeout: 15_000 });
  await evidence.screenshot(page, "live03-running-tool-step-visible");

  await release(context, runtime);
  await expect(
    page.locator("#chat").getByText(`W05-LIVE03-DONE ${marker}`),
  ).toBeVisible({ timeout: 60_000 });
  await evidence.json("live03-tool-step", { marker, scenes });
});

test("W05 LIVE-04 a delegated subtask card renders in progress while the run is in flight", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const marker = `w05-live-04-${Date.now()}`;
  const objective = `W05-LIVE04-OBJECTIVE ${marker}`;
  await ensureChild(context, runtime, evidence);
  const shaped = await shapeUltra(page);

  const response = await submit(page, workspace, marker);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  // The controlled Child blocks its own model call, so the parent Run is waiting
  // on the `task` Tool result; the card must render while it is still running.
  const scenes = await waitForScene(
    context,
    runtime,
    marker,
    (entry) => entry.child === true,
  );
  // Diagnostic ground truth for what the client has while the Child is blocked.
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error.message ?? error)));
  page.on("console", (message) => {
    if (message.type() === "error") pageErrors.push(message.text());
  });
  // Fast DOM snapshot at the block, before any request that the frontend proxy
  // may serialize behind the live stream (which would move the snapshot to the
  // settled state).
  await evidence.json("live04-block-dom", {
    groupCount: await page.locator("[data-message-group-index]").count(),
    userBubbles: await page.locator("#chat .is-user").count(),
    chatText: await page.locator("#chat").innerText().catch(() => null),
    cardCount: await page.getByTitle(objective, { exact: true }).count(),
    runningCount: await page.getByText("Running subtask", { exact: true }).count(),
    pageErrors,
  });
  try {
    await expect(page.getByTitle(objective, { exact: true })).toBeVisible({
      timeout: 15_000,
    });
    // Evidence of what the card shows while the Child is still blocked.
    await evidence.screenshot(page, "live04-card-while-child-blocked");
    await evidence.json("live04-card-while-child-blocked", {
      chatText: await page.locator("#chat").innerText().catch(() => null),
    });
    await expect(
      page.getByText("Running subtask", { exact: true }),
    ).toBeVisible({ timeout: 15_000 });
  } catch (error) {
    // Do not change the assertion; preserve the client-visible evidence instead.
    await evidence.json("live04-failure-scenes", { marker, objective, scenes });
    await evidence.json("live04-failure-dom", {
      chatText: await page.locator("#chat").innerText().catch(() => null),
      cards: await page.getByTitle(objective, { exact: true }).count(),
    });
    const location = response.headers()["content-location"];
    const threadMatch = location?.match(/\/api\/threads\/([^/]+)\/runs\//);
    if (threadMatch) {
      const state = await context.request.get(
        `${runtime.app}/api/threads/${threadMatch[1]}/state`,
      );
      await evidence.json("live04-failure-state", {
        status: state.status(),
        body: await state.text().catch(() => null),
      });
    }
    throw error;
  }
  await evidence.screenshot(page, "live04-running-subtask-card");
  await evidence.json("live04-running-scenes", { marker, objective, scenes });

  await release(context, runtime);
  await expect(
    page.locator("#chat").getByText(`W05-LIVE04-DONE ${marker}`),
  ).toBeVisible({ timeout: 60_000 });
});
