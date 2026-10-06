// W08 real-backend frozen-UI delegation Oracles.
//
// The unmodified frozen frontend runs against the real Haifa backend and a
// controlled loopback Provider. The only test-side adaptation is a Playwright
// route rewrite of the /runs/stream context to the values the frozen Ultra mode
// would send (mode=ultra, subagent_enabled=true, is_plan_mode=true,
// thinking_enabled=false, no reasoning_effort); the SSE body, rendering and
// reload are all real backend output. UI-12 deliberately sends no rewrite.
//
// First slice only: UI-06, UI-08, UI-09, UI-12. UI-07/10/11/13..16, the dual
// backends and L3 stay NOT_RUN in this task.
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require("./fixtures.cjs");

const RUN_STREAM = /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/;
// Route matching runs against the full URL, so tolerate the SDK query string.
const RUN_STREAM_ROUTE = "**/api/langgraph/threads/*/runs/stream*";
const CHILD_AGENT = "w08-child";
const CHILD = {
  name: CHILD_AGENT,
  display_name: "W08 Child",
  description: "Deterministic W08 delegation child",
  system_prompt:
    "W08-CHILD-MARKER Complete the assigned objective and return a short result.",
  model: "inherit",
  tools: [],
  skills: null,
  max_turns: 4,
  timeout_seconds: 30,
};

const COMPLETED = "Subtask completed";

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

async function ensureChild(context, runtime, evidence) {
  const existing = await context.request.get(`${runtime.app}/api/subagents`);
  const catalog = await existing.text();
  await evidence.json("catalog-before-seed", {
    status: existing.status(),
    body: catalog,
  });
  expect(existing.status(), catalog).toBe(200);
  const entries = JSON.parse(catalog).subagents ?? [];
  if (entries.some((entry) => entry.name === CHILD_AGENT)) return;
  const created = await context.request.post(`${runtime.app}/api/subagents`, {
    data: CHILD,
  });
  const body = await created.text();
  await evidence.json("seed-child", {
    method: "POST",
    path: "/api/subagents",
    status: created.status(),
    body,
  });
  expect(created.status(), body).toBe(201);
}

function watchPageHealth(page) {
  const errors = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  page.on("pageerror", (error) => errors.push(String(error.message ?? error)));
  return errors;
}

async function expectNoErrorPage(page, errors) {
  await expect(page.locator("#__next_error__")).toHaveCount(0);
  expect(
    errors.filter((text) => /#185|Maximum update depth exceeded/.test(text)),
    "React #185 / infinite update loop must not appear",
  ).toEqual([]);
}

async function shapeUltra(page) {
  const shaped = [];
  await page.route(RUN_STREAM_ROUTE, async (route, request) => {
    if (request.method() !== "POST") {
      await route.continue();
      return;
    }
    const body = request.postDataJSON();
    const context = {
      ...(body.context ?? {}),
      mode: "ultra",
      subagent_enabled: true,
      is_plan_mode: true,
      thinking_enabled: false,
    };
    delete context.reasoning_effort;
    body.context = context;
    shaped.push(context);
    await route.continue({ postData: JSON.stringify(body) });
  });
  return shaped;
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
  expect(response.status(), await response.text()).toBe(200);
  return response;
}

function cardTitle(page, text) {
  return page.getByTitle(text, { exact: true });
}

test.beforeEach(async ({ context, runtime }) => {
  await context.addCookies([
    { name: "locale", value: "en-US", url: `${runtime.app}/` },
  ]);
  const me = await context.request.get(`${runtime.app}/api/v1/auth/me`);
  expect(me.status(), await me.text()).toBe(200);
  expect((await me.json()).system_role).toBe("admin");
});

test("W08 UI-06 one delegated task card runs to completion with a visible result", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI06-PARENT";
  const objective = "W08-UI06-OBJECTIVE";
  const response = await submit(page, workspace, prompt);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  const title = cardTitle(page, objective);
  await expect(title).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });
  await evidence.screenshot(page, "ui06-completed-collapsed");

  await title.click();
  await expect(
    page.getByText(`W08-CHILD-RESULT:${objective}`),
  ).toBeVisible({ timeout: 20_000 });
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui06-expanded-result");
  await evidence.json("ui06-result", {
    prompt,
    objective,
    shapedContext: shaped.at(-1),
    status: (await response.headerValue("content-location")) ?? null,
    provider: providerEntries(runtime.evidence)
      .filter((entry) => entry.type === "w08_delegation")
      .map((entry) => ({
        child: entry.child,
        prompt: entry.prompt,
        offeredTask: entry.offeredTools.includes("task"),
      })),
    consoleErrors: errors,
  });
});

test("W08 UI-08 a completed delegated card stays completed after reload", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI08-PARENT";
  const objective = "W08-UI08-OBJECTIVE";
  await submit(page, workspace, prompt);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);
  const title = cardTitle(page, objective);
  await expect(title).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });
  // The single-task result assertion of UI-06 before reload.
  await title.click();
  await expect(page.getByText(`W08-CHILD-RESULT:${objective}`)).toBeVisible({
    timeout: 20_000,
  });
  const threadUrl = page.url();

  await page.reload({ waitUntil: "domcontentloaded" });
  const reloaded = cardTitle(page, objective);
  await expect(reloaded).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  // The completed result is restored from persisted ToolMessage metadata.
  await reloaded.click();
  await expect(page.getByText(`W08-CHILD-RESULT:${objective}`)).toBeVisible({
    timeout: 20_000,
  });
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui08-completed-after-reload");
  await evidence.json("ui08-reload", {
    prompt,
    objective,
    threadUrl,
    reloadedUrl: page.url(),
    consoleErrors: errors,
  });
});

test("W08 UI-09 one round with two parallel tasks renders one group and two completed cards", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI09-PARENT";
  const alpha = "W08-UI09-OBJECTIVE-ALPHA";
  const beta = "W08-UI09-OBJECTIVE-BETA";
  await submit(page, workspace, prompt);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  await expect(
    page.getByText("Executing 2 subtasks in parallel", { exact: true }),
  ).toBeVisible({ timeout: 60_000 });
  await expect(cardTitle(page, alpha)).toBeVisible({ timeout: 60_000 });
  await expect(cardTitle(page, beta)).toBeVisible({ timeout: 60_000 });
  await expect
    .poll(() => page.getByText(COMPLETED, { exact: true }).count(), {
      timeout: 60_000,
    })
    .toBe(2);
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui09-two-completed-cards");

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(cardTitle(page, alpha)).toBeVisible({ timeout: 60_000 });
  await expect(cardTitle(page, beta)).toBeVisible({ timeout: 60_000 });
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui09-after-reload");
  await evidence.json("ui09-parallel", {
    prompt,
    alpha,
    beta,
    consoleErrors: errors,
    provider: providerEntries(runtime.evidence)
      .filter((entry) => entry.type === "w08_delegation" && entry.child)
      .map((entry) => entry.objective),
  });
});

test("W08 UI-12 non-delegation mode sends no task tool and renders no card", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  // No Ultra shaping here: the frozen UI's real flash request is observed.
  const prompt = "W08-UI12-PARENT";
  await submit(page, workspace, prompt);
  await expect(
    page.locator("#chat").getByText(`Controlled provider: ${prompt}`),
  ).toBeVisible({ timeout: 60_000 });

  await expect
    .poll(
      () =>
        providerEntries(runtime.evidence).filter(
          (entry) =>
            entry.type === "provider_request" &&
            entry.stream === true &&
            (entry.prompt?.includes(prompt) || entry.rawPrompt?.includes(prompt)),
        ).length,
      { timeout: 20_000 },
    )
    .toBeGreaterThan(0);
  const requests = providerEntries(runtime.evidence).filter(
    (entry) =>
      entry.type === "provider_request" &&
      entry.stream === true &&
      (entry.prompt?.includes(prompt) || entry.rawPrompt?.includes(prompt)),
  );
  for (const request of requests) {
    expect(request.offeredTools).not.toContain("task");
  }
  await expect(page.getByTitle("W08-UI12-OBJECTIVE")).toHaveCount(0);
  await expect(page.getByText(/Executing .*subtask/)).toHaveCount(0);
  await expect(page.getByText(COMPLETED, { exact: true })).toHaveCount(0);
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui12-no-card");
  await evidence.json("ui12-no-delegation", {
    prompt,
    offeredTools: requests.map((request) => request.offeredTools),
    consoleErrors: errors,
  });
});
