// W08 real-backend frozen-UI delegation Oracles.
//
// The unmodified frozen frontend runs against the real Haifa backend and a
// controlled loopback Provider. The only test-side adaptation is a Playwright
// route rewrite of the /runs/stream context to the values the frozen Ultra mode
// would send (mode=ultra, subagent_enabled=true, is_plan_mode=true,
// thinking_enabled=false, no reasoning_effort); the SSE body, rendering and
// reload are all real backend output. UI-12 deliberately sends no rewrite.
//
// Covers the controlled Haifa UI-06..UI-15 contracts. Formal rounds, frozen
// reference differential and real-model L3 are separately recorded by the controller.
const fs = require("node:fs");
const path = require("node:path");
const { createHash } = require("node:crypto");

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

// A second bounded Child for UI-07 grants exactly one read-only production Tool
// (list_uploaded_files) so the frozen card renders a real assistant step and a
// real tool step.
const TOOLS_CHILD_AGENT = "w08-child-tools";
const TOOLS_CHILD = {
  ...CHILD,
  name: TOOLS_CHILD_AGENT,
  display_name: "W08 Child Tools",
  description: "Deterministic W08 delegation child with one read-only tool",
  tools: ["list_uploaded_files"],
  timeout_seconds: 120,
};

// UI-11 toggles this managed definition while its admitted Run is still running.
const UI11_CHILD_AGENT = "w08-ui11-child";
const UI11_CHILD = {
  ...CHILD,
  name: UI11_CHILD_AGENT,
  display_name: "W08 UI-11 Child",
  description: "Deterministic W08 child used to observe a runtime disable",
  timeout_seconds: 120,
};

// UI-15 grants the approval-requiring production write_file so the SDK's
// non-interactive Child policy can deny it inside a real Run.
const UI15_CHILD_AGENT = "w08-ui15-child";
const UI15_CHILD = {
  ...CHILD,
  name: UI15_CHILD_AGENT,
  display_name: "W08 UI-15 Child",
  description: "Deterministic W08 child that requests an approval-requiring write",
  tools: ["write_file"],
  timeout_seconds: 60,
};

const COMPLETED = "Subtask completed";

const IS_REFERENCE =
  (process.env.W18_DIFFERENTIAL_BACKEND ?? "haifa") === "reference";

function providerEntries(evidenceRoot) {
  const file = fs
    .readdirSync(evidenceRoot)
    .find((name) => /^provider.*\.stdout\.log$/.test(name));
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

async function ensureChild(context, runtime, evidence, child = CHILD, enabled = true) {
  const existing = await context.request.get(`${runtime.app}/api/subagents`);
  const catalog = await existing.text();
  await evidence.json("catalog-before-seed", {
    status: existing.status(),
    body: catalog,
  });
  expect(existing.status(), catalog).toBe(200);
  const entries = JSON.parse(catalog).subagents ?? [];
  const entry = entries.find((candidate) => candidate.name === child.name);
  if (entry) {
    if (entry.enabled !== enabled) {
      await setChildEnabled(context, runtime, evidence, child.name, enabled);
    }
    return;
  }
  const created = await context.request.post(`${runtime.app}/api/subagents`, {
    data: child,
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

async function setChildEnabled(context, runtime, evidence, name, enabled) {
  const response = await context.request.put(
    `${runtime.app}/api/subagents/${encodeURIComponent(name)}`,
    { data: { enabled } },
  );
  const body = await response.text();
  await evidence.json(`child-enabled-${enabled}-${name}`, {
    method: "PUT",
    path: `/api/subagents/${name}`,
    request: { enabled },
    status: response.status(),
    body,
  });
  expect(response.status(), body).toBe(200);
  const updated = JSON.parse(body);
  expect(updated.enabled).toBe(enabled);
  return updated;
}

async function releaseGate(context, runtime, evidence, name) {
  // Count unmatched waits so a settled gate from an earlier warm pass cannot
  // satisfy readiness. Registering the waiter precedes the wait log entry.
  await expect.poll(
    () => providerEntries(runtime.evidence).reduce((pending, entry) => {
      if (entry.name !== name) return pending;
      if (entry.type === "w08_gate_wait") return pending + 1;
      if (entry.type === "w08_gate") return pending - 1;
      return pending;
    }, 0),
    { timeout: 20_000, intervals: [100, 250, 500] },
  ).toBeGreaterThan(0);
  const response = await context.request.get(
    `${runtime.provider}/__release?name=${encodeURIComponent(name)}`,
  );
  const body = await response.text();
  await evidence.json(`release-${name}`, { status: response.status(), body });
  expect(response.status(), body).toBe(200);
  expect(JSON.parse(body).released).toBeGreaterThan(0);
}

async function getJson(context, runtime, pathname) {
  const response = await context.request.get(`${runtime.app}${pathname}`);
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  return JSON.parse(body);
}

async function tryJson(context, runtime, pathname) {
  const response = await context.request.get(`${runtime.app}${pathname}`);
  if (!response.ok()) return null;
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function stateMessages(state) {
  return state?.values?.messages ?? [];
}

function taskToolCalls(messages) {
  const calls = [];
  for (const message of messages) {
    for (const call of message.tool_calls ?? []) {
      if (call.name === "task") calls.push(call);
    }
  }
  return calls;
}

function taskToolResults(messages) {
  return messages.filter(
    (message) => message.type === "tool" && message.tool_call_id,
  );
}

const THREAD_STATE = (threadId) =>
  `/api/langgraph/threads/${encodeURIComponent(threadId)}/state`;
const THREAD_RUNS = (threadId) =>
  `/api/langgraph/threads/${encodeURIComponent(threadId)}/runs`;
const RUN = (threadId, runId) =>
  `/api/langgraph/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}`;

async function startRun(page, workspace, text) {
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
  expect(response.status()).toBe(200);
  return response;
}

function cardRoot(page, text) {
  return cardTitle(page, text).locator(
    "xpath=ancestor::div[contains(@class,'rounded-lg')][1]",
  );
}

const NATIVE_CHILD_STEPS = (threadId, runId, taskId) =>
  `/api/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}/events` +
  `?event_types=subagent.step&task_id=${encodeURIComponent(taskId)}&limit=500`;

// Observe the actual browser response with Chromium's read-only network stream
// tap. No new Run, response rewrite, frontend patch or fabricated frame.
async function observeCreatorSse(context, page) {
  const session = await context.newCDPSession(page);
  let stream = null;
  let failure = null;
  session.on("Network.dataReceived", (event) => {
    if (stream?.requestId === event.requestId && event.data) {
      stream.chunks.push(Buffer.from(event.data, "base64"));
    }
  });
  session.on("Network.responseReceived", async (event) => {
    if (stream || !RUN_STREAM.test(new URL(event.response.url).pathname)) return;
    stream = { requestId: event.requestId, buffered: Buffer.alloc(0), chunks: [] };
    try {
      const result = await session.send("Network.streamResourceContent", { requestId: event.requestId });
      stream.buffered = Buffer.from(result.bufferedData, "base64");
    } catch (error) { failure = String(error); }
  });
  await session.send("Network.enable");
  return {
    frames() {
      if (failure) throw new Error(failure);
      const raw = stream ? Buffer.concat([stream.buffered, ...stream.chunks]).toString("utf8") : "";
      return raw.split(/\r?\n\r?\n/).slice(0, -1).flatMap((block) => {
        const data = block.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n");
        return data ? [JSON.parse(data)] : [];
      });
    },
    close: () => session.detach(),
  };
}

async function subagentLifecycleEvents(context, runtime, identity, taskId = null) {
  let query = "event_types=subagent.start,subagent.step,subagent.end&limit=500";
  if (taskId !== null && taskId !== undefined) {
    query += `&task_id=${encodeURIComponent(taskId)}`;
  }
  const response = await context.request.get(
    `${runtime.app}/api/threads/${encodeURIComponent(identity.threadId)}/runs/${encodeURIComponent(identity.runId)}/events?${query}`,
  );
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  return JSON.parse(body);
}

function subagentEventTaskId(event) {
  return event?.metadata?.task_id ?? event?.content?.task_id ?? null;
}

async function taskCallSamples(context, runtime, identity, runJson, state = null) {
  if (!IS_REFERENCE) {
    return (runJson?.diagnostics?.tool_calls ?? []).filter(
      (call) => call.name === "task",
    );
  }
  const parentState =
    state ?? (await getJson(context, runtime, THREAD_STATE(identity.threadId)));
  const stateCalls = taskToolCalls(stateMessages(parentState));
  const argsById = new Map(stateCalls.map((call) => [call.id, call.args ?? null]));
  const events = await subagentLifecycleEvents(context, runtime, identity);
  const ids = [];
  for (const id of [
    ...stateCalls.map((call) => call.id),
    ...events.map((event) => subagentEventTaskId(event)),
  ]) {
    if (id && !ids.includes(id)) ids.push(id);
  }
  return ids.map((id) => {
    const own = events.filter((event) => subagentEventTaskId(event) === id);
    const started = own.some(
      (event) =>
        event.event_type === "subagent.start" || event.event_type === "subagent.step",
    );
    const ended = own.find((event) => event.event_type === "subagent.end");
    return {
      id,
      name: "task",
      args: argsById.get(id) ?? null,
      status: ended
        ? String(ended.content?.status ?? "").toUpperCase()
        : started
          ? "RUNNING"
          : "REQUESTED",
      started,
      result_successful: ended ? ended.content?.status === "completed" : undefined,
      requested_at: null,
      started_at: null,
    };
  });
}

function pendingInteractions(runJson) {
  return (runJson?.diagnostics?.interactions ?? []).filter(
    (interaction) => interaction.state === "PENDING",
  );
}

function runningSubtasks(scope) {
  return scope.getByText("Running subtask", { exact: true });
}

function stepOrder(cardText, markers) {
  return markers
    .map((marker) => ({ marker, index: cardText.indexOf(marker) }))
    .filter((entry) => entry.index >= 0)
    .sort((a, b) => a.index - b.index)
    .map((entry) => entry.marker);
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

async function expectNoErrorsOrDialogs(page, errors) {
  await expectNoErrorPage(page, errors);
  const errorToasts = page.locator(
    '[data-sonner-toast][data-type="error"], [data-sonner-toast][data-type="warning"], [role="alert"].toast',
  );
  await expect(errorToasts).toHaveCount(0);
  const errorDialogs = page
    .locator('[role="dialog"], [role="alertdialog"]')
    .filter({ hasText: /error|failure/i });
  await expect(errorDialogs).toHaveCount(0);
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

// Tool vs Child contract (original failing DB adjudication; no invented semantics): the generic task tool call ends FAILED,
// the Child result data.status CANCELLED projects to subagent_status cancelled (DeerFlowDelegationProjection); Child Runs stay CANCELLED/PARENT_CANCELLED.
function assertCancelledToolMessage(msg, mappedIds, label) {
  expect(msg, `${label} must be an object`).toBeTruthy();
  expect(mappedIds, `${label} tool_call_id must be in mapped IDs`).toContain(msg.tool_call_id);
  expect(typeof msg.content, `${label} content must be string`).toBe("string");
  expect(msg.content.trim().length, `${label} content must be nonempty`).toBeGreaterThan(0);
  expect(
    msg.additional_kwargs?.subagent_status,
    `${label} additional_kwargs.subagent_status must be cancelled`,
  ).toBe("cancelled");
  if (IS_REFERENCE) {
    // Approved UI10 reference SDK-diagnostics shape difference. The actual
    // mapped ToolMessage's native cancelled status is still mandatory.
    expect(msg.additional_kwargs).not.toHaveProperty("haifa_tool_status");
    expect(msg.additional_kwargs).not.toHaveProperty("haifa_tool_result");
    return;
  }
  expect(
    msg.additional_kwargs?.haifa_tool_status,
    `${label} additional_kwargs.haifa_tool_status must be failed`,
  ).toBe("failed");
  expect(
    typeof msg.additional_kwargs?.haifa_tool_result,
    `${label} additional_kwargs.haifa_tool_result must exist as an object`,
  ).toBe("object");
  expect(
    msg.additional_kwargs?.haifa_tool_result,
    `${label} additional_kwargs.haifa_tool_result must not be null`,
  ).not.toBeNull();
  expect(
    msg.additional_kwargs?.haifa_tool_result.status,
    `${label} additional_kwargs.haifa_tool_result.status must be CANCELLED`,
  ).toBe("CANCELLED");
  expect(
    msg.additional_kwargs?.haifa_tool_result.status,
    `${label} additional_kwargs.haifa_tool_result.status must be CANCELLED`,
  ).toBe("CANCELLED");
}

function extractToolMessages(messagesPageData, mappedIds) {
  const items = Array.isArray(messagesPageData?.data) ? messagesPageData.data : [];
  const selected = [];
  for (const item of items) {
    const msg = item?.content;
    if (
      msg &&
      typeof msg === "object" &&
      msg.type === "tool" &&
      mappedIds.includes(msg.tool_call_id)
    ) {
      selected.push(msg);
    }
  }
  selected.sort((a, b) =>
    String(a.tool_call_id ?? "").localeCompare(String(b.tool_call_id ?? "")),
  );
  return selected;
}

test("W08 UI-10 stopping two running children cancels the parent without residual running state", async ({
  context,
  page,
  runtime,
  workspace,
  runFlow,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence);
  const updateRes = await context.request.put(
    `${runtime.app}/api/subagents/${CHILD_AGENT}`,
    {
      data: { ...CHILD, timeout_seconds: 120 },
    },
  );
  expect(updateRes.ok(), await updateRes.text()).toBeTruthy();
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI10-PARENT";
  const alpha = "W08-UI10-OBJECTIVE-ALPHA";
  const beta = "W08-UI10-OBJECTIVE-BETA";
  const creatorSse = IS_REFERENCE ? await observeCreatorSse(context, page) : null;

  // Scope Provider gate records to this case with an initial baseline
  const providerLogBaseline = providerEntries(runtime.evidence).length;
  const ui10ProviderEntries = () =>
    providerEntries(runtime.evidence).slice(providerLogBaseline);

  const { input } = await workspace.open();
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      RUN_STREAM.test(new URL(response.url()).pathname),
    { timeout: 30_000 },
  );
  await input.fill(prompt);
  await page.getByRole("button", { name: "Submit" }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);

  const identity = runFlow.identity(response);
  const { threadId, runId } = identity;
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  // 1. Physical two Child HTTP starts proved by actual Provider waiting gate logs
  await expect
    .poll(
      () => {
        const entries = ui10ProviderEntries().filter(
          (entry) => entry.type === "w08_ui10_gate" && entry.state === "waiting",
        );
        return entries.length;
      },
      { timeout: 30_000, intervals: [100, 250, 500] },
    )
    .toBe(2);

  const waitingRecords = ui10ProviderEntries().filter(
    (entry) => entry.type === "w08_ui10_gate" && entry.state === "waiting",
  );
  expect(waitingRecords).toHaveLength(2);
  const alphaWaiting = waitingRecords.filter((r) => r.objective === alpha);
  const betaWaiting = waitingRecords.filter((r) => r.objective === beta);
  expect(alphaWaiting, "ALPHA waiting record must be exactly 1").toHaveLength(1);
  expect(betaWaiting, "BETA waiting record must be exactly 1").toHaveLength(1);
  expect(alphaWaiting[0].id, "ALPHA waiting request ID must exist").toBeTruthy();
  expect(betaWaiting[0].id, "BETA waiting request ID must exist").toBeTruthy();
  expect(
    alphaWaiting[0].id,
    "Child waiting request IDs must be distinct",
  ).not.toBe(betaWaiting[0].id);
  const alphaRequestId = alphaWaiting[0].id;
  const betaRequestId = betaWaiting[0].id;

  // 2. Fetch actual GET thread state to discover actual generated runtime Task IDs
  let stateBeforeCancel = null;
  let alphaCallId = null;
  let betaCallId = null;
  await expect
    .poll(
      async () => {
        const stateRes = await context.request.get(
          `${runtime.app}/api/langgraph/threads/${encodeURIComponent(threadId)}/state`,
        );
        if (!stateRes.ok()) return false;
        const stateData = await stateRes.json();
        const aiMessages = (stateData.values?.messages ?? []).filter(
          (msg) =>
            (msg.type === "ai" || msg.role === "assistant") &&
            Array.isArray(msg.tool_calls),
        );
        const candidates = aiMessages.filter((msg) => {
          const taskCalls = msg.tool_calls.filter((tc) => tc.name === "task");
          const alphaCalls = taskCalls.filter(
            (tc) => tc.args?.prompt === alpha || tc.args?.objective === alpha,
          );
          const betaCalls = taskCalls.filter(
            (tc) => tc.args?.prompt === beta || tc.args?.objective === beta,
          );
          return (
            alphaCalls.length === 1 &&
            betaCalls.length === 1 &&
            alphaCalls[0].id &&
            betaCalls[0].id &&
            alphaCalls[0].id !== betaCalls[0].id
          );
        });
        if (candidates.length === 1) {
          stateBeforeCancel = stateData;
          const msg = candidates[0];
          const taskCalls = msg.tool_calls.filter((tc) => tc.name === "task");
          const alphaCall = taskCalls.find(
            (tc) => tc.args?.prompt === alpha || tc.args?.objective === alpha,
          );
          const betaCall = taskCalls.find(
            (tc) => tc.args?.prompt === beta || tc.args?.objective === beta,
          );
          alphaCallId = alphaCall.id;
          betaCallId = betaCall.id;
          return true;
        }
        return false;
      },
      { timeout: 30_000, intervals: [200, 500] },
    )
    .toBe(true);

  expect(stateBeforeCancel, "raw state before cancel must be captured").toBeTruthy();
  expect(alphaCallId, "actual alpha task call ID must be nonempty").toBeTruthy();
  expect(betaCallId, "actual beta task call ID must be nonempty").toBeTruthy();
  expect(alphaCallId, "alpha and beta task call IDs must be distinct").not.toBe(betaCallId);

  const mappedTaskIds = [alphaCallId, betaCallId].sort();
  const actualTaskMapping = {
    threadId,
    runId,
    alpha: { objective: alpha, callId: alphaCallId, requestId: alphaRequestId },
    beta: { objective: beta, callId: betaCallId, requestId: betaRequestId },
    mappedTaskIds,
  };
  await evidence.json("ui10-raw-state-before-cancel", stateBeforeCancel);
  await evidence.json("ui10-actual-task-mapping", actualTaskMapping);

  // 3. Pre-Stop DOM running cards
  await expect(cardTitle(page, alpha)).toBeVisible({ timeout: 30_000 });
  await expect(cardTitle(page, beta)).toBeVisible({ timeout: 30_000 });
  await expect
    .poll(
      () => page.getByText("Running subtask", { exact: true }).count(),
      { timeout: 30_000 },
    )
    .toBe(2);
  await expect(page.getByText(COMPLETED, { exact: true })).toHaveCount(0);

  // 4. Pre-cancel diagnostics: mapped Task IDs have RUNNING status and started_at
  const runBeforeCancelRes = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}`,
  );
  expect(runBeforeCancelRes.status()).toBe(200);
  const runBeforeCancel = await runBeforeCancelRes.json();
  const diagToolCalls = runBeforeCancel.diagnostics?.tool_calls ?? [];
  const runningTaskCalls = diagToolCalls.filter(
    (tc) => tc.tool_call_id === alphaCallId || tc.tool_call_id === betaCallId,
  );
  let referenceRunningEvents = null;
  if (IS_REFERENCE) {
    expect(runBeforeCancel).not.toHaveProperty("diagnostics");
    await expect.poll(() => {
      referenceRunningEvents = creatorSse.frames().filter((frame) =>
        ["task_started", "task_running"].includes(frame.type) && mappedTaskIds.includes(frame.task_id));
      return new Set(referenceRunningEvents.map((frame) => frame.task_id)).size;
    }, { timeout: 30_000, intervals: [100, 250, 500] }).toBe(2);
  } else {
    expect(runningTaskCalls).toHaveLength(2);
    for (const tc of runningTaskCalls) {
      expect(tc.status).toBe("RUNNING");
      expect(tc.started_at).toBeTruthy();
    }
  }
  await evidence.screenshot(page, "ui10-running-cards");
  await evidence.json("ui10-running-diagnostics", {
    threadId,
    runId,
    actualTaskMapping,
    diagnostics: runBeforeCancel.diagnostics,
    referenceRunningEvents,
    provider: ui10ProviderEntries().filter(
      (entry) => entry.type === "w08_ui10_gate" || entry.type === "w08_delegation",
    ),
  });

  // 5. Stream token in sessionStorage
  await expect
    .poll(
      async () =>
        page.evaluate(
          (tid) => sessionStorage.getItem(`lg:stream:${tid}`),
          threadId,
        ),
      { timeout: 20_000 },
    )
    .toBe(runId);

  // 6. Stop via frozen UI button: exact URL pathname & searchParams wait=0/action=interrupt -> HTTP 202
  let cancelRequestEvidence = null;
  const cancelPromise = page.waitForResponse(
    (res) => {
      if (res.request().method() !== "POST") return false;
      const parsedUrl = new URL(res.url());
      const expectedPath = `/api/langgraph/threads/${threadId}/runs/${runId}/cancel`;
      if (
        parsedUrl.pathname === expectedPath &&
        parsedUrl.searchParams.get("wait") === "0" &&
        parsedUrl.searchParams.get("action") === "interrupt"
      ) {
        const rawHeaders = res.request().headers();
        const SENSITIVE_HEADERS = /^(authorization|cookie|set-cookie|proxy-authorization|x-api-key|api-key)$/i;
        cancelRequestEvidence = {
          url: res.url(),
          pathname: parsedUrl.pathname,
          searchParams: Object.fromEntries(parsedUrl.searchParams.entries()),
          method: res.request().method(),
          headers: Object.fromEntries(
            Object.entries(rawHeaders).map(([name, value]) => [
              name,
              SENSITIVE_HEADERS.test(name)
                ? `<redacted present len=${value.length}>`
                : value,
            ]),
          ),
          postData: res.request().postData(),
          status: res.status(),
        };
        return true;
      }
      return false;
    },
    { timeout: 20_000 },
  );
  await page.getByRole("button", { name: "Submit" }).click();
  const cancelResponse = await cancelPromise;
  expect(cancelResponse.status()).toBe(202);
  let cancelResponseBody;
  let cancelBodyUnavailable;
  try {
    cancelResponseBody = await cancelResponse.text();
  } catch (error) {
    if (
      !error.message.includes("Network.getResponseBody") ||
      !error.message.includes("No data found for resource with given identifier")
    ) {
      throw error;
    }
    // Chromium has no cached body for this response; the 202 status independently
    // validates the actual HTTP framing and must never become a synthetic body.
    cancelResponseBody = null;
    cancelBodyUnavailable = error.message;
  }
  cancelRequestEvidence.responseBody = cancelResponseBody;
  cancelRequestEvidence.responseBodyUnavailable = cancelBodyUnavailable ?? null;
  await evidence.json("ui10-cancel-response-evidence", cancelRequestEvidence);

  // 7. Poll interrupted status
  await runFlow.poll(context.request, identity, "interrupted", true);

  // 8. Provider closed gate evidence: exactly 2 closed, 0 timeout, correlated request IDs
  await expect
    .poll(
      () => {
        const closed = ui10ProviderEntries().filter(
          (entry) => entry.type === "w08_ui10_gate" && entry.state === "closed",
        );
        return closed.length;
      },
      { timeout: 20_000, intervals: [100, 250, 500] },
    )
    .toBe(2);

  const gateRecordsAfterCancel = ui10ProviderEntries().filter(
    (entry) => entry.type === "w08_ui10_gate",
  );
  const waitingAfterCancel = gateRecordsAfterCancel.filter((r) => r.state === "waiting");
  const closedAfterCancel = gateRecordsAfterCancel.filter((r) => r.state === "closed");
  const timeoutAfterCancel = gateRecordsAfterCancel.filter((r) => r.state === "timeout");

  expect(waitingAfterCancel, "waiting gate records must be exactly 2").toHaveLength(2);
  expect(closedAfterCancel, "closed gate records must be exactly 2").toHaveLength(2);
  expect(timeoutAfterCancel, "timeout gate records must be exactly 0").toHaveLength(0);

  const alphaClosed = closedAfterCancel.filter((r) => r.objective === alpha);
  const betaClosed = closedAfterCancel.filter((r) => r.objective === beta);
  expect(alphaClosed, "ALPHA closed record must be exactly 1").toHaveLength(1);
  expect(betaClosed, "BETA closed record must be exactly 1").toHaveLength(1);
  expect(
    alphaClosed[0].id,
    "ALPHA closed request ID must correlate with waiting request ID",
  ).toBe(alphaRequestId);
  expect(
    betaClosed[0].id,
    "BETA closed request ID must correlate with waiting request ID",
  ).toBe(betaRequestId);

  await evidence.json("ui10-provider-gate-evidence", {
    baselineCount: providerLogBaseline,
    waitingRecords,
    closedRecords: closedAfterCancel,
    allGateRecords: gateRecordsAfterCancel,
  });

  // 9. Settled run diagnostics: Parent sdk_status CANCELLED. The generic task tool call is
  // terminal FAILED while the Child stays CANCELLED — different lifecycle fields (see the
  // adjudication comment above assertCancelledToolMessage). No residual RUNNING/REQUESTED.
  const settledRunRes = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}`,
  );
  expect(settledRunRes.status()).toBe(200);
  const settledRun = await settledRunRes.json();
  if (IS_REFERENCE) {
    expect(settledRun).not.toHaveProperty("diagnostics");
    expect(settledRun.status).toBe("interrupted");
  } else {
    expect(settledRun.diagnostics?.sdk_status).toBe("CANCELLED");
    expect(settledRun.diagnostics?.stop_reason).toBe("USER_CANCELLED");
  }

  const settledDiagToolCalls = settledRun.diagnostics?.tool_calls ?? [];
  const settledMappedTaskCalls = settledDiagToolCalls.filter(
    (tc) => tc.tool_call_id === alphaCallId || tc.tool_call_id === betaCallId,
  );
  if (!IS_REFERENCE) {
    expect(
      settledMappedTaskCalls,
      "settled diagnostics.tool_calls must contain both mapped task IDs",
    ).toHaveLength(2);
    for (const tc of settledMappedTaskCalls) {
      expect(
        tc.status,
        "Task tool must be terminal FAILED with cancelled Child; no other status is allowed",
      ).toBe("FAILED");
    }
    for (const tc of settledDiagToolCalls) {
      expect(tc.status).not.toBe("RUNNING");
      expect(tc.status).not.toBe("REQUESTED");
    }
  }

  // 10. Thread runs: must be Array, actual parent run_id present, no running/pending
  const allRunsRes = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${encodeURIComponent(threadId)}/runs`,
  );
  expect(allRunsRes.status()).toBe(200);
  const allRuns = await allRunsRes.json();
  expect(Array.isArray(allRuns), "GET thread/runs response must be an array").toBe(true);
  expect(
    allRuns.some((r) => r.run_id === runId || r.id === runId),
    "same parent actual run_id must exist in runs array",
  ).toBe(true);
  for (const r of allRuns) {
    expect(r.status).not.toBe("running");
    expect(r.status).not.toBe("pending");
  }

  // 11. Post-cancel DOM: Subtask failed count 2, Running subtask count 0, no completed, no toast/dialog
  await expect(cardTitle(page, alpha)).toBeVisible({ timeout: 20_000 });
  await expect(cardTitle(page, beta)).toBeVisible({ timeout: 20_000 });
  await expect
    .poll(
      () => page.getByText("Subtask failed", { exact: true }).count(),
      { timeout: 20_000 },
    )
    .toBe(2);
  await expect(page.getByText("Running subtask", { exact: true })).toHaveCount(0);
  await expect(page.getByText(COMPLETED, { exact: true })).toHaveCount(0);
  await expectNoErrorsOrDialogs(page, errors);
  await evidence.screenshot(page, "ui10-cancelled-cards");

  // 12. Settled state ToolMessages
  const settledStateRes = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${encodeURIComponent(threadId)}/state`,
  );
  expect(settledStateRes.status()).toBe(200);
  const settledState = await settledStateRes.json();
  const stateToolMessages = (settledState.values?.messages ?? []).filter(
    (msg) =>
      msg &&
      typeof msg === "object" &&
      msg.type === "tool" &&
      mappedTaskIds.includes(msg.tool_call_id),
  );
  stateToolMessages.sort((a, b) =>
    String(a.tool_call_id ?? "").localeCompare(String(b.tool_call_id ?? "")),
  );
  expect(
    stateToolMessages,
    "settled state must contain exactly two mapped task ToolMessages",
  ).toHaveLength(2);
  for (let i = 0; i < stateToolMessages.length; i++) {
    assertCancelledToolMessage(
      stateToolMessages[i],
      mappedTaskIds,
      `stateToolMessages[${i}]`,
    );
  }

  await evidence.json("ui10-settled-raw-state", settledState);
  await evidence.json("ui10-cancelled-evidence", {
    threadId,
    runId,
    sdkStatus: settledRun.diagnostics?.sdk_status,
    nativeRun: IS_REFERENCE ? settledRun : null,
    approvedDifference: IS_REFERENCE ? "W08-UI10-REFERENCE-DIAGNOSTICS-DIFF" : null,
    settledDiagToolCalls,
    stateToolMessages,
    allRuns,
    consoleErrors: errors,
  });

  // 13. Messages page before reload
  const messagesPageBeforeRes = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${encodeURIComponent(threadId)}/messages/page?limit=100`,
  );
  expect(messagesPageBeforeRes.status()).toBe(200);
  const messagesPageBeforeData = await messagesPageBeforeRes.json();
  await evidence.json(
    "ui10-messages-page-before-reload",
    messagesPageBeforeData,
  );

  const toolMsgsBefore = extractToolMessages(
    messagesPageBeforeData,
    mappedTaskIds,
  );
  expect(
    toolMsgsBefore,
    "messages/page before reload must contain exactly two mapped task ToolMessages",
  ).toHaveLength(2);
  for (let i = 0; i < toolMsgsBefore.length; i++) {
    assertCancelledToolMessage(
      toolMsgsBefore[i],
      mappedTaskIds,
      `toolMsgsBefore[${i}]`,
    );
    expect(toolMsgsBefore[i]).toEqual(stateToolMessages[i]);
  }

  // 14. Reload and repeat all DOM, health and error checks
  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(cardTitle(page, alpha)).toBeVisible({ timeout: 60_000 });
  await expect(cardTitle(page, beta)).toBeVisible({ timeout: 60_000 });
  await expect
    .poll(
      () => page.getByText("Subtask failed", { exact: true }).count(),
      { timeout: 20_000 },
    )
    .toBe(2);
  await expect(page.getByText("Running subtask", { exact: true })).toHaveCount(0);
  await expect(page.getByText(COMPLETED, { exact: true })).toHaveCount(0);
  await expectNoErrorsOrDialogs(page, errors);
  await evidence.screenshot(page, "ui10-after-reload");

  // 15. Messages page after reload: must equal before-page messages
  const messagesPageAfterRes = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${encodeURIComponent(threadId)}/messages/page?limit=100`,
  );
  expect(messagesPageAfterRes.status()).toBe(200);
  const messagesPageAfterData = await messagesPageAfterRes.json();
  await evidence.json(
    "ui10-messages-page-after-reload",
    messagesPageAfterData,
  );

  const toolMsgsAfter = extractToolMessages(
    messagesPageAfterData,
    mappedTaskIds,
  );
  expect(
    toolMsgsAfter,
    "messages/page after reload must contain exactly two mapped task ToolMessages",
  ).toHaveLength(2);
  for (let i = 0; i < toolMsgsAfter.length; i++) {
    assertCancelledToolMessage(
      toolMsgsAfter[i],
      mappedTaskIds,
      `toolMsgsAfter[${i}]`,
    );
    expect(toolMsgsAfter[i]).toEqual(toolMsgsBefore[i]);
  }

  // 16. Preserve creator stream response evidence safely at end in try/catch
  let streamText = null;
  let streamError = null;
  try {
    streamText = await response.text();
  } catch (err) {
    streamError = String(err?.message ?? err);
  }
  await evidence.json("ui10-stream-end-evidence", {
    status: response.status(),
    headers: response.headers(),
    streamText,
    streamError,
  });
  if (creatorSse) await creatorSse.close();
});

test("W08 UI-07 a running child expands to ordered assistant and tool steps that survive completion", async ({
  context,
  page,
  runtime,
  workspace,
  runFlow,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence, TOOLS_CHILD);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI07-PARENT";
  const objective = "W08-UI07-OBJECTIVE";
  const stepText = "W08-UI07-CHILD-STEP";
  const creatorSse = IS_REFERENCE ? await observeCreatorSse(context, page) : null;
  const response = await startRun(page, workspace, prompt);
  const identity = runFlow.identity(response);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  const title = cardTitle(page, objective);
  await expect(title).toBeVisible({ timeout: 60_000 });

  // Actual persisted child step accessors over the public steps envelope only:
  // identity is task_id + content.message_index (with event seq), never an
  // invented message id.
  const stepContent = (step) => step?.content ?? null;
  const stepTaskId = (step) =>
    typeof step?.metadata === "object" && step?.metadata !== null
      ? String(step.metadata.task_id ?? stepContent(step)?.task_id ?? "")
      : String(stepContent(step)?.task_id ?? "");
  const stepIndex = (step) => stepContent(step)?.message_index ?? null;
  const stepKind = (step) => stepContent(step)?.kind ?? null;
  const stepTextValue = (step) =>
    typeof stepContent(step)?.text === "string" ? stepContent(step).text : "";
  const stepToolNames = (step) =>
    (typeof stepContent(step)?.tool_name === "string"
      ? [stepContent(step).tool_name]
      : []
    ).concat(
      (stepContent(step)?.tool_calls ?? [])
        .map((call) => call.name)
        .filter((name) => typeof name === "string"),
    );
  const stepSeq = (step) => (typeof step?.seq === "number" ? step.seq : null);
  const stepTuple = (step) => ({
    task_id: stepTaskId(step),
    message_index: stepIndex(step),
    seq: stepSeq(step),
    kind: stepKind(step),
    text: stepTextValue(step),
    tool_names: stepToolNames(step),
  });

  // Before releasing the gate: the exact creator run identity plus the real
  // task Tool call id from public Parent state, and exactly one matching call.
  const statePath = THREAD_STATE(identity.threadId);
  let state = null;
  await expect.poll(
    async () => {
      state = await tryJson(context, runtime, statePath);
      return taskToolCalls(stateMessages(state ?? { values: { messages: [] } })).length;
    },
    { timeout: 30_000, intervals: [100, 250, 500] },
  ).toBe(1);
  const calls = taskToolCalls(stateMessages(state));
  expect(calls, "the parent must have made exactly one task call").toHaveLength(1);
  const taskId = calls[0].id;
  expect(typeof taskId).toBe("string");
  expect(taskId.length).toBeGreaterThan(0);

  // The gated child's real persisted steps: assistant step before the actual
  // list_uploaded_files Tool step, unique ids/indexes and increasing seq.
  let runningSteps = null;
  await expect.poll(
    async () => {
      if (IS_REFERENCE) {
        // Approved W08-UI07-REFERENCE-PERSISTENCE-DIFF: these live frames
        // precede the frozen backend's terminal/25-event persistence flush.
        const frames = creatorSse.frames().filter((frame) => frame.type === "task_running" && frame.task_id === taskId && frame.message);
        const unique = new Map();
        for (const frame of frames) {
          const message = frame.message;
          const kind = message.type === "tool" ? "tool" : "ai";
          const content = {
            task_id: frame.task_id, message_index: frame.message_index, kind,
            text: typeof message.content === "string" ? message.content : "",
            ...(kind === "tool" ? { tool_name: message.name } : { tool_calls: message.tool_calls ?? [] }),
          };
          unique.set(frame.message_index, { metadata: { task_id: frame.task_id }, content });
        }
        runningSteps = [...unique.values()];
      } else {
        runningSteps = await tryJson(
          context,
          runtime,
          NATIVE_CHILD_STEPS(identity.threadId, identity.runId, taskId),
        );
      }
      return (runningSteps ?? []).some(
        (step) =>
          stepKind(step) === "tool" &&
          stepToolNames(step).includes("list_uploaded_files"),
      );
    },
    { timeout: 30_000, intervals: [100, 250, 500] },
  ).toBe(true);
  expect(Array.isArray(runningSteps), "child steps must be a real array").toBe(true);
  const runningIds = runningSteps.map(
    (step) => `${stepTaskId(step)}#${stepIndex(step)}`,
  );
  expect(
    runningIds.filter((id) => id.length > 1),
    "every persisted step must carry a nonempty task identity",
  ).toHaveLength(runningSteps.length);
  expect(
    runningSteps.map((step) => stepTaskId(step)),
    "the persisted steps must belong to exactly the real task id",
  ).toEqual(runningSteps.map(() => taskId));
  const runningIndexes = runningSteps.map((step) => stepIndex(step));
  runningSteps.forEach((step) => {
    expect(
      Number.isInteger(stepIndex(step)),
      `persisted message_index must be an integer for step ${stepIndex(step)}`,
    ).toBe(true);
    expect(stepIndex(step)).toBeGreaterThan(0);
    expect(stepIndex(step)).toBeLessThanOrEqual(Number.MAX_SAFE_INTEGER);
  });
  expect(
    new Set(runningIndexes).size,
    "persisted message indexes must be unique",
  ).toBe(runningSteps.length);
  for (let i = 1; i < runningIndexes.length; i++) {
    expect(
      runningIndexes[i],
      "persisted message indexes must strictly increase with step order",
    ).toBeGreaterThan(runningIndexes[i - 1]);
  }
  if (!IS_REFERENCE) {
    runningSteps.forEach((step) => {
      expect(
        Number.isInteger(stepSeq(step)) && stepSeq(step) !== null,
        `a Haifa persisted step must carry an integer event seq (step index ${stepIndex(step)})`,
      ).toBe(true);
      expect(stepSeq(step)).toBeGreaterThan(0);
      expect(stepSeq(step)).toBeLessThanOrEqual(Number.MAX_SAFE_INTEGER);
    });
  }
  const runningSeqs = runningSteps.map((step) => stepSeq(step));
  if (runningSeqs.every((seq) => seq !== null)) {
    for (let i = 1; i < runningSeqs.length; i++) {
      expect(
        runningSeqs[i],
        "persisted event seq must strictly increase with step order",
      ).toBeGreaterThan(runningSeqs[i - 1]);
    }
  }
  const runningAssistantIdx = runningSteps.findIndex(
    (step) => stepKind(step) === "ai" && stepTextValue(step).includes(stepText),
  );
  expect(
    runningAssistantIdx,
    "the gated child must expose a real persisted assistant step",
  ).toBeGreaterThanOrEqual(0);
  const runningToolIdx = runningSteps.findIndex(
    (step) =>
      stepKind(step) === "tool" &&
      stepToolNames(step).includes("list_uploaded_files"),
  );
  expect(
    runningToolIdx,
    "the persisted Tool step must come after the assistant step",
  ).toBeGreaterThan(runningAssistantIdx);

  // Expand while the Child is still gated in the Provider.
  await title.click();
  await expect(page.getByText(stepText, { exact: false })).toBeVisible({
    timeout: 30_000,
  });
  await expect(
    page.getByText("list_uploaded_files", { exact: true }),
  ).toBeVisible({ timeout: 30_000 });
  // The gate is still held, so the Child card must still be running.
  await expect(page.getByText(COMPLETED, { exact: true })).toHaveCount(0);

  // The rendered step order is the Child's real execution order.
  const runningText = await cardRoot(page, objective).innerText();
  const assistantIndex = runningText.indexOf(stepText);
  const toolIndex = runningText.indexOf("list_uploaded_files");
  expect(assistantIndex).toBeGreaterThanOrEqual(0);
  expect(toolIndex).toBeGreaterThan(assistantIndex);
  // Freeze the exact running prefix tuples before the gate release.
  const runningTuples = runningSteps.map(stepTuple);
  await evidence.screenshot(page, "ui07-running-ordered-steps");
  await evidence.json("ui07-running", {
    prompt,
    objective,
    identity,
    taskId,
    runningSteps,
    runningTuples,
    runningText,
    assistantIndex,
    toolIndex,
    consoleErrors: errors,
    approvedDifference: IS_REFERENCE ? "W08-UI07-REFERENCE-PERSISTENCE-DIFF" : null,
    actualLiveFrames: creatorSse ? creatorSse.frames() : null,
  });

  await releaseGate(context, runtime, evidence, "w08-ui07-child");
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await expect(page.getByText(`W08-CHILD-RESULT:${objective}`)).toBeVisible({
    timeout: 20_000,
  });
  // The steps are not lost once the Child completes.
  await expect(page.getByText(stepText, { exact: false })).toBeVisible();
  await expect(
    page.getByText("list_uploaded_files", { exact: true }),
  ).toBeVisible();
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });

  // Real completion facts: the frozen running prefix is unchanged (identity,
  // order and content) and a real final assistant result follows the Tool.
  const completedSteps = await getJson(
    context,
    runtime,
    NATIVE_CHILD_STEPS(identity.threadId, identity.runId, taskId),
  );
  expect(completedSteps.length).toBeGreaterThan(runningTuples.length);
  const completedTuples = completedSteps.map(stepTuple);
  for (let i = 0; i < runningTuples.length; i++) {
    expect(
      IS_REFERENCE ? { ...completedTuples[i], seq: null } : completedTuples[i],
      `persisted step ${i} must be unchanged after completion`,
    ).toEqual(runningTuples[i]);
  }
  if (IS_REFERENCE) {
    const seqs = completedSteps.map(stepSeq);
    for (let i = 0; i < seqs.length; i++) {
      expect(Number.isSafeInteger(seqs[i]) && seqs[i] > 0).toBe(true);
      if (i > 0) expect(seqs[i]).toBeGreaterThan(seqs[i - 1]);
    }
    await creatorSse.close();
  }
  const completedAssistantIdx = completedSteps.findIndex(
    (step) => stepKind(step) === "ai" && stepTextValue(step).includes(stepText),
  );
  expect(completedAssistantIdx).toBe(runningAssistantIdx);
  const completedToolIdx = completedSteps.findIndex(
    (step) =>
      stepKind(step) === "tool" &&
      stepToolNames(step).includes("list_uploaded_files"),
  );
  expect(completedToolIdx).toBe(runningToolIdx);
  const finalAssistantIdx = completedSteps.findIndex(
    (step) =>
      stepKind(step) === "ai" &&
      stepTextValue(step).includes(`W08-CHILD-RESULT:${objective}`),
  );
  expect(
    finalAssistantIdx,
    "the completed child must end with a real assistant result after the Tool",
  ).toBeGreaterThan(completedToolIdx);

  // The rendered completion order keeps the same three markers in order.
  const completedText = await page.locator("#chat").innerText();
  const resultIndex = completedText.indexOf(`W08-CHILD-RESULT:${objective}`);
  expect(completedText.indexOf(stepText)).toBeGreaterThanOrEqual(0);
  expect(completedText.indexOf("list_uploaded_files")).toBeGreaterThan(
    completedText.indexOf(stepText),
  );
  expect(resultIndex).toBeGreaterThan(
    completedText.indexOf("list_uploaded_files"),
  );
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui07-completed-steps");
  await evidence.json("ui07-completed", {
    prompt,
    objective,
    identity,
    taskId,
    completedSteps,
    completedTuples,
    finalAssistantText: stepTextValue(completedSteps[finalAssistantIdx]),
    completedText,
    provider: providerEntries(runtime.evidence).filter(
      (entry) =>
        entry.type === "w08_assistant_tool_call" ||
        (entry.type === "w08_gate" && entry.name === "w08-ui07-child"),
    ),
    consoleErrors: errors,
  });

  // Reload the same real thread: the same completed card expands to the same
  // marker order, and the child steps are exactly refetched identical.
  await page.reload({ waitUntil: "domcontentloaded" });
  const reloaded = cardTitle(page, objective);
  await expect(reloaded).toBeVisible({ timeout: 60_000 });
  await reloaded.click();
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.getByText(`W08-CHILD-RESULT:${objective}`)).toBeVisible();
  await expect(page.getByText(stepText, { exact: false })).toBeVisible();
  await expect(page.getByText("list_uploaded_files", { exact: true })).toBeVisible();
  const reloadedSteps = await getJson(
    context,
    runtime,
    NATIVE_CHILD_STEPS(identity.threadId, identity.runId, taskId),
  );
  expect(
    reloadedSteps,
    "the reloaded child steps must equal the completed steps exactly",
  ).toEqual(completedSteps);
  const reloadedText = await page.locator("#chat").innerText();
  expect(reloadedText.indexOf(stepText)).toBeGreaterThanOrEqual(0);
  expect(reloadedText.indexOf("list_uploaded_files")).toBeGreaterThan(
    reloadedText.indexOf(stepText),
  );
  expect(reloadedText.indexOf(`W08-CHILD-RESULT:${objective}`)).toBeGreaterThan(
    reloadedText.indexOf("list_uploaded_files"),
  );
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui07-reloaded-steps");
  await evidence.json("ui07-reloaded", {
    prompt,
    objective,
    identity,
    taskId,
    reloadedUrl: page.url(),
    stepsEqualAfterReload: true,
    reloadedText,
    consoleErrors: errors,
  });
});

test("W08 UI-13 a failed child card shows the public error while the parent completes", async ({
  context,
  page,
  runtime,
  workspace,
  runFlow,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI13-FAIL-PARENT";
  const objective = "W08-UI13-FAIL-OBJECTIVE";
  const streamResponse = await startRun(page, workspace, prompt);
  const identity = runFlow.identity(streamResponse);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  const title = cardTitle(page, objective);
  await expect(title).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText("Subtask failed", { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });

  // The card error must be the public error the backend exposes for the failed Child.
  const state = await getJson(
    context,
    runtime,
    THREAD_STATE(identity.threadId),
  );
  const failed = taskToolResults(stateMessages(state))[0];
  expect(failed).toBeTruthy();
  expect(failed.additional_kwargs.subagent_status).toBe("failed");
  const publicError = failed.additional_kwargs.subagent_error;
  expect(typeof publicError).toBe("string");
  expect(publicError.length).toBeGreaterThan(0);
  await title.click();
  // Frozen SubtaskCard renders its public error in this red notice. The same
  // text can also occur in a persisted step; assert the card notice itself.
  const errorNotice = page.locator("#chat div.text-red-500").filter({
    hasText: publicError,
  });
  await expect(errorNotice).toHaveCount(1);
  await expect(errorNotice).toBeVisible({
    timeout: 20_000,
  });

  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui13-failed-child");
  await evidence.json("ui13-failed", {
    prompt,
    objective,
    subagentStatus: failed.additional_kwargs.subagent_status,
    publicError,
    provider: providerEntries(runtime.evidence).filter(
      (entry) => entry.type === "w08_child_failure",
    ),
    consoleErrors: errors,
  });
});

test("W08 UI-13 a missing child is rejected before creation without a residual card", async ({
  context,
  page,
  runtime,
  workspace,
  runFlow,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI13-MISSING-PARENT";
  const objective = "W08-UI13-MISSING-OBJECTIVE";
  const streamResponse = await startRun(page, workspace, prompt);
  const identity = runFlow.identity(streamResponse);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });

  const state = await getJson(
    context,
    runtime,
    THREAD_STATE(identity.threadId),
  );
  const messages = stateMessages(state);
  const calls = taskToolCalls(messages);
  expect(
    calls,
    "the parent must make exactly one actual task call",
  ).toHaveLength(1);
  const taskId = calls[0].id;
  expect(typeof taskId, "the actual task call must carry a real id").toBe(
    "string",
  );
  expect(taskId.length).toBeGreaterThan(0);

  // Exactly one real rejection ToolMessage matching the actual task call id is
  // required public evidence and must not claim the missing Child completed.
  const toolMessages = taskToolResults(messages);
  const rejections = toolMessages.filter(
    (message) => message.tool_call_id === taskId,
  );
  expect(
    rejections,
    "exactly one rejection ToolMessage must match the actual task call id",
  ).toHaveLength(1);
  const result = rejections[0];
  const rejection = {
    tool_call_id: result.tool_call_id,
    status: result.status ?? null,
    subagent_status: result.additional_kwargs?.subagent_status ?? null,
    haifa_tool_status: result.additional_kwargs?.haifa_tool_status ?? null,
    haifa_tool_result: result.additional_kwargs?.haifa_tool_result ?? null,
    subagent_error: result.additional_kwargs?.subagent_error ?? null,
    content:
      typeof result.content === "string" ? result.content : result.content,
  };
  expect(result.additional_kwargs?.subagent_status).not.toBe("completed");
  if (!IS_REFERENCE) {
    expect(
      result.additional_kwargs?.subagent_status,
      "Haifa rejection ToolMessage must mark the missing child failed",
    ).toBe("failed");
  }
  const publicError =
    typeof result.additional_kwargs?.subagent_error === "string"
      ? result.additional_kwargs.subagent_error
      : "";
  const publicContent =
    typeof result.content === "string" ? result.content : "";
  expect(
    publicError.length > 0 || publicContent.length > 0,
    "the real public rejection error/content must confirm the missing child non-emptily",
  ).toBe(true);

  // Proof no Child was created: the missing agent has no persisted step history
  // and no public subagent lifecycle events for the exact task id.
  const childSteps = await getJson(
    context,
    runtime,
    NATIVE_CHILD_STEPS(identity.threadId, identity.runId, taskId),
  );
  expect(childSteps).toEqual([]);
  let lifecycle = [];
  let lifecycleForTask = [];
  let parentSse = null;
  if (IS_REFERENCE) {
    lifecycle = await subagentLifecycleEvents(
      context,
      runtime,
      identity,
      taskId,
    );
    lifecycleForTask = lifecycle.filter(
      (event) => subagentEventTaskId(event) === taskId,
    );
    expect(
      lifecycleForTask.filter((event) => event.event_type === "subagent.start"),
      "subagent.start must be absent for the rejected task id",
    ).toHaveLength(0);
    expect(
      lifecycleForTask.filter((event) => event.event_type === "subagent.step"),
      "subagent.step must be absent for the rejected task id",
    ).toHaveLength(0);
    expect(
      lifecycleForTask.filter((event) => event.event_type === "subagent.end"),
      "subagent.end must be absent for the rejected task id",
    ).toHaveLength(0);
  } else {
    // Positive control instead of the step-only HTTP events contract: the
    // actual creator SSE of this exact Run carries the public subtask
    // lifecycle frames, so the stream is read end-to-end and must be proven
    // complete before any lifecycle absence is meaningful. A Parent stream can
    // contain ordinary model text, so the raw body stays in this local
    // evidence artifact only.
    const parseCreatorSse = (raw) => {
      const frames = [];
      for (const block of raw.split(/\r?\n\r?\n/)) {
        let event = null;
        let frameId = null;
        const dataLines = [];
        for (const line of block.split(/\r?\n/)) {
          if (line.length === 0 || line.startsWith(":")) continue;
          if (line.startsWith("event:")) {
            if (event !== null) {
              throw new Error(`creator SSE frame repeats event: ${line}`);
            }
            event = line.slice("event:".length).trim();
          } else if (line.startsWith("id:")) {
            if (frameId !== null) {
              throw new Error(`creator SSE frame repeats id: ${line}`);
            }
            frameId = line.slice("id:".length).replace(/^ /, "");
            if (frameId.includes("\u0000")) {
              throw new Error(`creator SSE frame id contains NUL: ${line}`);
            }
          } else if (line.startsWith("data:")) {
            dataLines.push(line.slice("data:".length).replace(/^ /, ""));
          } else {
            throw new Error(
              `unexpected creator SSE line: ${JSON.stringify(line)}`,
            );
          }
        }
        if (event === null && dataLines.length === 0) continue;
        if (event === null || dataLines.length === 0) {
          throw new Error(`incomplete creator SSE frame for event ${event}`);
        }
        let data;
        try {
          data = JSON.parse(dataLines.join("\n"));
        } catch (error) {
          throw new Error(
            `unparsable creator SSE data for event ${event}: ${error.message}`,
          );
        }
        frames.push({ event, data, id: frameId });
      }
      return frames;
    };
    expect(streamResponse.status(), "the creator SSE must still be 200").toBe(
      200,
    );
    const sseRaw = await streamResponse.text();
    expect(
      sseRaw.trim().length,
      "the creator SSE body must be nonempty",
    ).toBeGreaterThan(0);
    const frames = parseCreatorSse(sseRaw);
    const metadataFrames = frames.filter((frame) => frame.event === "metadata");
    expect(
      metadataFrames,
      "the creator SSE must open with exactly one Run-bound metadata frame",
    ).toHaveLength(1);
    expect(
      metadataFrames[0].data?.run_id,
      "creator SSE metadata.run_id must be exactly this Run",
    ).toBe(identity.runId);
    expect(
      frames.some((frame) => frame.event === "end"),
      "the same-Run creator SSE must include its end frame",
    ).toBe(true);
    const customPayloads = frames
      .filter(
        (frame) =>
          frame.event === "custom" &&
          frame.data !== null &&
          typeof frame.data === "object" &&
          !Array.isArray(frame.data),
      )
      .map((frame) => frame.data);
    lifecycleForTask = customPayloads.filter(
      (payload) => payload.task_id === taskId,
    );
    for (const type of [
      "task_started",
      "task_running",
      "task_completed",
      "task_failed",
      "task_cancelled",
    ]) {
      expect(
        lifecycleForTask.filter((payload) => payload.type === type),
        `${type} must be absent for the rejected task id`,
      ).toHaveLength(0);
    }
    parentSse = {
      raw: sseRaw,
      frameEvents: frames.map((frame) => frame.event),
      frameIds: frames.map((frame) => frame.id),
      metadata: metadataFrames[0].data,
      hasEnd: true,
      customTaskIds: [
        ...new Set(customPayloads.map((payload) => payload.task_id ?? null)),
      ],
    };
  }

  // Public diagnostics: exactly one task Tool call, rejected before a Child ran.
  const runJson = await getJson(
    context,
    runtime,
    RUN(identity.threadId, identity.runId),
  );
  const admitted = await taskCallSamples(
    context,
    runtime,
    identity,
    runJson,
    state,
  );
  expect(admitted.length).toBe(1);
  const admittedId = admitted[0].tool_call_id ?? admitted[0].id ?? null;
  expect(
    admittedId,
    "public diagnostics must match the exact actual task call id",
  ).toBe(taskId);
  expect(admitted[0].result_successful).not.toBe(true);
  if (!IS_REFERENCE) {
    expect(
      runJson.status,
      "the Parent Run must have terminated successfully",
    ).toBe("success");
    expect(
      runJson.diagnostics?.sdk_status,
      "the Parent Run SDK status must be terminal COMPLETED",
    ).toBe("COMPLETED");
    expect(
      admitted[0].status,
      "the rejected task tool call must be terminal FAILED in public diagnostics",
    ).toBe("FAILED");
    expect(admitted[0].result_successful).toBe(false);
    expect(
      admitted[0].error_code,
      "Haifa diagnostics must expose the public rejection error_code for the"
        + " actual ToolMessage",
    ).toBe("TOOL_REQUEST_REJECTED");
  }

  // The frozen UI must not leave a rejected child looking running or completed.
  const cardCount = await cardTitle(page, objective).count();
  let cardState = "absent";
  if (cardCount > 0) {
    const text = await cardRoot(page, objective).innerText();
    if (/Subtask failed/.test(text)) cardState = "failed";
    else if (/Subtask completed/.test(text)) cardState = "completed";
    else if (/Running subtask/.test(text)) cardState = "running";
    else cardState = "unknown";
  }
  expect(cardState).not.toBe("completed");
  expect(cardState).not.toBe("running");
  expect(["absent", "failed"]).toContain(cardState);
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui13-missing-child");
  await evidence.json("ui13-missing", {
    prompt,
    objective,
    actualTaskId: taskId,
    taskCall: calls[0],
    cardCount,
    cardState,
    childStepEvents: childSteps,
    rejection,
    actualRejectionToolMessage: result,
    parentLifecycleEvents: lifecycle,
    lifecycleForTaskId: lifecycleForTask,
    parentSse,
    parentTerminal: {
      status: runJson.status ?? null,
      sdk_status: runJson.diagnostics?.sdk_status ?? null,
    },
    admittedTaskCalls: admitted,
    parentDiagnostics: runJson.diagnostics ?? null,
    consoleErrors: errors,
  });
});

test("W08 UI-11 disabling a managed child affects only a new run and leaves the in-flight run intact", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence, UI11_CHILD);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI11-PARENT";
  const objective = "W08-UI11-OBJECTIVE";
  await startRun(page, workspace, prompt);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  const title = cardTitle(page, objective);
  await expect(title).toBeVisible({ timeout: 60_000 });

  // The in-flight Run's model request exposed the managed child before disable.
  const beforeDisable = providerEntries(runtime.evidence).filter(
    (entry) => entry.type === "w08_delegation" && entry.prompt?.includes(prompt),
  );
  expect(beforeDisable.length).toBeGreaterThan(0);
  expect(beforeDisable.at(-1).exposedSubagents).toContain(UI11_CHILD_AGENT);

  const disabled = await setChildEnabled(
    context,
    runtime,
    evidence,
    UI11_CHILD_AGENT,
    false,
  );
  expect(disabled.enabled).toBe(false);

  // The admitted Run is not tampered with: it still completes and returns the
  // real Child result after its configuration was disabled.
  await releaseGate(context, runtime, evidence, "w08-ui11-child");
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await cardTitle(page, objective).click();
  await expect(page.getByText(`W08-CHILD-RESULT:${objective}`)).toBeVisible({
    timeout: 20_000,
  });
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui11-inflight-completed");

  // A NEW Run projects the current catalog and must no longer expose the child.
  const prompt2 = "W08-UI11R2-PARENT";
  await submit(page, workspace, prompt2);
  await expect
    .poll(
      () =>
        providerEntries(runtime.evidence).filter(
          (entry) =>
            entry.type === "w08_delegation" &&
            entry.prompt?.includes(prompt2),
        ).length,
      { timeout: 20_000 },
    )
    .toBeGreaterThan(0);
  const afterDisable = providerEntries(runtime.evidence)
    .filter(
      (entry) =>
        entry.type === "w08_delegation" && entry.prompt?.includes(prompt2),
    )
    .at(-1);
  expect(afterDisable.exposedSubagents).not.toContain(UI11_CHILD_AGENT);
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt2}`),
  ).toBeVisible({ timeout: 60_000 });
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui11-new-run");

  await evidence.json("ui11-disable-scope", {
    prompt,
    objective,
    inFlightExposed: beforeDisable.at(-1).exposedSubagents,
    newRunExposed: afterDisable.exposedSubagents,
    disabled,
    consoleErrors: errors,
  });
});

test("W08 UI-14 a long objective titles the card as a single truncated line without a synthetic description", async ({
  context,
  page,
  runtime,
  workspace,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI14-PARENT";
  await submit(page, workspace, prompt);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  // Read the real model-visible task call: it carries agent+objective only.
  await expect
    .poll(
      () =>
        providerEntries(runtime.evidence).filter(
          (entry) => entry.type === "w08_task_call",
        ).length,
      { timeout: 20_000 },
    )
    .toBeGreaterThan(0);
  const taskCall = providerEntries(runtime.evidence)
    .filter((entry) => entry.type === "w08_task_call")
    .at(-1);
  // Each backend declares its own task argument schema (reference:
  // subagent_type+prompt; Haifa SDK: agent+objective); the provider fills the
  // schema actually offered, and the card titles from the objective argument.
  const objective = IS_REFERENCE ? taskCall.args.prompt : taskCall.args.objective;
  expect(Object.keys(taskCall.args).sort()).toEqual(
    IS_REFERENCE ? ["prompt", "subagent_type"] : ["agent", "objective"],
  );
  expect(objective.length).toBeGreaterThan(200);

  const title = cardTitle(page, objective);
  await expect(title).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 60_000,
  });

  const metrics = await title.evaluate((element) => {
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return {
      title: element.getAttribute("title"),
      whiteSpace: style.whiteSpace,
      textOverflow: style.textOverflow,
      overflow: style.overflow,
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      height: rect.height,
    };
  });
  // The full real objective is the title; no synthetic SDK description replaces it.
  expect(metrics.title).toBe(objective);
  // The frozen component renders it as one truncated line.
  expect(metrics.whiteSpace).toBe("nowrap");
  expect(metrics.textOverflow).toBe("ellipsis");
  expect(metrics.overflow).toBe("hidden");
  expect(metrics.scrollWidth).toBeGreaterThan(metrics.clientWidth);
  await expectNoErrorPage(page, errors);
  await evidence.screenshot(page, "ui14-truncated-title");
  await evidence.json("ui14-long-objective", {
    prompt,
    objective,
    taskArgs: taskCall.args,
    metrics,
    consoleErrors: errors,
  });
});

test("W08 UI-15 child write follows the approved backend policy without a pending approval", async ({
  context,
  page,
  runtime,
  workspace,
  runFlow,
  evidence,
}) => {
  const errors = watchPageHealth(page);
  await ensureChild(context, runtime, evidence, UI15_CHILD);
  const shaped = await shapeUltra(page);

  const prompt = "W08-UI15-PARENT";
  const objective = "W08-UI15-OBJECTIVE";
  const response = await startRun(page, workspace, prompt);
  const identity = runFlow.identity(response);
  await expect.poll(() => shaped.length).toBeGreaterThan(0);

  const title = cardTitle(page, objective);
  await expect(title).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText(COMPLETED, { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await title.click();
  await expect(page.getByText(`W08-CHILD-RESULT:${objective}`)).toBeVisible({
    timeout: 20_000,
  });
  await expect(
    page.locator("#chat").getByText(`W08-PARENT-FINAL:${prompt}`),
  ).toBeVisible({ timeout: 60_000 });
  await expectNoErrorPage(page, errors);

  // The real SSE must never surface an approval request for the non-interactive Child.
  const sse = await response.text();
  expect(sse).not.toContain("human_input_request");
  expect(sse).not.toContain("WAITING_APPROVAL");

  // Human-approved known difference W08-UI15-REFERENCE-WRITE-DIFF applies
  // only to the reference. Haifa must still observe the actual SDK denial.
  const denials = providerEntries(runtime.evidence).filter(
    (entry) => entry.type === "w08_child_write_denied",
  );
  expect(denials.length).toBeGreaterThan(0);
  if (IS_REFERENCE) {
    expect(denials.at(-1).denied).toBe(false);
    expect(denials.at(-1).toolResult).toBe("OK");
  } else {
    expect(denials.at(-1).denied).toBe(true);
  }

  // Public child facts: the Parent task ToolMessage reports the Child completed,
  // and the Child's own persisted steps carry the exact denied write target.
  const state = await getJson(
    context,
    runtime,
    THREAD_STATE(identity.threadId),
  );
  const taskId = taskToolCalls(stateMessages(state))[0]?.id;
  expect(taskId).toBeTruthy();
  const childResult = taskToolResults(stateMessages(state))[0];
  expect(childResult?.additional_kwargs?.subagent_status).toBe("completed");
  const childSteps = await getJson(
    context,
    runtime,
    NATIVE_CHILD_STEPS(identity.threadId, identity.runId, taskId),
  );
  const writeCalls = childSteps
    .flatMap((event) => event.content?.tool_calls ?? [])
    .filter((call) => call.name === "write_file");
  expect(writeCalls.length).toBeGreaterThan(0);
  const deniedPath = writeCalls.at(-1).args?.path;
  expect(deniedPath).toBe("/mnt/user-data/outputs/w08-ui15-denied.txt");
  // The Child continued past the denial through a Tool result to its final answer.
  const stepKinds = childSteps
    .map((event) => event.content)
    .sort((a, b) => a.message_index - b.message_index)
    .map((step) => step.kind);
  expect(stepKinds).toContain("tool");
  expect(stepKinds.at(-1)).toBe("ai");

  // Public interaction fact: no pending approval exists for the Parent Run.
  const runJson = await getJson(
    context,
    runtime,
    RUN(identity.threadId, identity.runId),
  );
  if (IS_REFERENCE) {
    // The reference RunResponse exposes no interaction diagnostics; the
    // equivalent real fact is the SSE body asserted above (no
    // human_input_request / WAITING_APPROVAL) plus the child's persisted steps.
    expect(runJson).not.toHaveProperty("diagnostics");
  } else {
    expect(pendingInteractions(runJson)).toEqual([]);
  }

  // Inspect the exact real write target. Haifa keeps the original negative
  // control; reference requires the approved actual byte-for-byte write.
  const artifact = await context.request.get(
    `${runtime.app}/api/threads/${encodeURIComponent(identity.threadId)}/artifacts${deniedPath}`,
  );
  let referenceArtifact = null;
  if (IS_REFERENCE) {
    expect(artifact.status()).toBe(200);
    const bytes = await artifact.body();
    const expectedBytes = Buffer.from(writeCalls.at(-1).args.content, "utf8");
    expect(bytes).toEqual(expectedBytes);
    expect(bytes.length).toBe(67);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    expect(sha256).toBe("fb656a09e661bbc8e41c046f85a4fbfc229201c4b42f99696e21f2ce0981e924");
    referenceArtifact = { bytes: bytes.length, sha256 };
  } else {
    expect(artifact.status()).toBe(404);
  }

  await evidence.screenshot(page, "ui15-denied-child");
  await evidence.json("ui15-noninteractive-denial", {
    prompt,
    objective,
    identity,
    taskId,
    denial: denials.at(-1),
    childStatus: childResult?.additional_kwargs?.subagent_status ?? null,
    childStepKinds: stepKinds,
    deniedPath,
    pendingInteractions: pendingInteractions(runJson),
    referenceHasNoDiagnostics: IS_REFERENCE ? !("diagnostics" in runJson) : null,
    artifactStatus: artifact.status(),
    referenceArtifact,
    approvedDifference: IS_REFERENCE ? "W08-UI15-REFERENCE-WRITE-DIFF" : null,
    sseHasApproval:
      sse.includes("human_input_request") || sse.includes("WAITING_APPROVAL"),
    consoleErrors: errors,
  });
});
