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
  expect(runningTaskCalls).toHaveLength(2);
  for (const tc of runningTaskCalls) {
    expect(tc.status).toBe("RUNNING");
    expect(tc.started_at).toBeTruthy();
  }
  await evidence.screenshot(page, "ui10-running-cards");
  await evidence.json("ui10-running-diagnostics", {
    threadId,
    runId,
    actualTaskMapping,
    diagnostics: runBeforeCancel.diagnostics,
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
  expect(settledRun.diagnostics?.sdk_status).toBe("CANCELLED");
  expect(settledRun.diagnostics?.stop_reason).toBe("USER_CANCELLED");

  const settledDiagToolCalls = settledRun.diagnostics?.tool_calls ?? [];
  const settledMappedTaskCalls = settledDiagToolCalls.filter(
    (tc) => tc.tool_call_id === alphaCallId || tc.tool_call_id === betaCallId,
  );
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
});
