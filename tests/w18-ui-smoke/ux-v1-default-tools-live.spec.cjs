// UX-V1 joint acceptance: the real DeepSeek model drives the DeerFlow default tools through the
// unmodified frozen UI. Three independent chains (files, clarification, web) so one failure does
// not hide the others. Assertions read the authoritative thread state, never model prose.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "25618"}`;
const MODEL_NAME = process.env.W18_MODEL_NAME ?? "w18-deepseek-live";
const STREAM_PATH = /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/;
const TERMINAL = new Set(["success", "error", "interrupted", "timeout"]);
const observedRuns = new WeakMap();

async function captureStream(page, response, testInfo) {
  const identity = runIdentity(response);
  const runs = observedRuns.get(page) ?? [];
  runs.push(identity);
  observedRuns.set(page, runs);
  const file = testInfo.outputPath(`run-${identity.runId}-stream-${runs.length}.sse`);
  fs.writeFileSync(file, await response.text(), "utf8");
  await testInfo.attach(path.basename(file), { path: file, contentType: "text/event-stream" });
  return identity;
}

test.afterEach(async ({ context, page }, testInfo) => {
  for (const identity of observedRuns.get(page) ?? []) {
    for (const [name, endpoint] of [
      ["snapshot", identity.location],
      ["state", `/api/langgraph/threads/${identity.threadId}/state`],
      ["usage", `/api/threads/${identity.threadId}/token-usage`],
    ]) {
      const response = await context.request.get(`${APP}${endpoint}`, { timeout: 10_000 });
      await saveEvidence(testInfo, `run-${identity.runId}-${name}`, {
        identity, status: response.status(), body: await response.text(),
      });
    }
  }
});

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2], location };
}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

async function saveEvidence(testInfo, name, evidence) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

async function screenshot(page, testInfo, name) {
  const file = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  await testInfo.attach(`${name}.png`, { path: file, contentType: "image/png" });
}

function streamWait(page, predicate = () => true) {
  return page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      STREAM_PATH.test(new URL(response.url()).pathname) &&
      predicate(response.request()),
    { timeout: 60_000 },
  );
}

async function runStatus(request, identity) {
  const response = await request.get(`${APP}${identity.location}`);
  expect(response.status(), await response.text()).toBe(200);
  return (await response.json()).status;
}

async function threadState(request, threadId) {
  const response = await request.get(`${APP}/api/langgraph/threads/${threadId}/state`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

function toolCallNames(state) {
  return state.values.messages.flatMap((message) =>
    (message?.tool_calls ?? []).map((call) => call.name),
  );
}

function toolResults(state, name) {
  return state.values.messages.filter(
    (message) => message?.type === "tool" && message?.name === name,
  );
}

async function openNewChat(page, context) {
  const models = await (await context.request.get(`${APP}/api/models`)).json();
  expect(models.models.map((model) => model.name)).toContain(MODEL_NAME);
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  return input;
}

/**
 * Drives one Run to a terminal state. Production write tools stop on the frozen approval card; the
 * acceptance approves them (the V0-accepted known difference). Returns how many approvals were given.
 */
async function driveToTerminal(page, context, identity, { timeoutMs = 300_000, testInfo } = {}) {
  const deadline = Date.now() + timeoutMs;
  let approvals = 0;
  let interruptedSince = null;
  for (;;) {
    const status = await runStatus(context.request, identity);
    if (Date.now() > deadline) throw new Error(`Run ${identity.runId} not terminal: ${status}`);
    // A Run waiting on a frozen approval card reports "interrupted"; it is terminal only when no
    // approvable card appears for that Run.
    const approve = page
      .getByTestId("human-input-card")
      .getByRole("button", { name: "Approve", exact: true });
    const approvable = (await approve.count()) > 0 && (await approve.last().isEnabled());
    if (!approvable && TERMINAL.has(status)) {
      if (status !== "interrupted") return { status, approvals };
      interruptedSince ??= Date.now();
      if (Date.now() - interruptedSince > 15_000) return { status, approvals };
      await page.waitForTimeout(1_000);
      continue;
    }
    interruptedSince = null;
    if (approvable) {
      const resumed = streamWait(
        page,
        (request) => !!request.postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs?.human_input_response,
      );
      await approve.last().click();
      const response = await resumed;
      expect(response.status(), await response.text()).toBe(200);
      expect(runIdentity(response).runId, "approval must resume the same Run").toBe(identity.runId);
      if (testInfo) await captureStream(page, response, testInfo);
      approvals += 1;
      continue;
    }
    await page.waitForTimeout(1_000);
  }
}

test.describe("UX-V1 DeerFlow default tools with live DeepSeek", () => {
  test("A files: grep -> write_file -> read_file -> str_replace -> present_files -> download", async (
    { context, page },
    testInfo,
  ) => {
    const marker = `UXV1_GREP_MARKER_${Date.now()}`;
    const csv = Buffer.from(`id,name,note\n1,alpha,plain row\n2,beta,${marker}\n3,gamma,plain row\n`, "utf8");
    const report = "/mnt/user-data/outputs/ux-v1-report.md";
    const startedAt = Date.now();
    const input = await openNewChat(page, context);

    const uploadPromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/threads\/[^/]+\/uploads$/.test(new URL(response.url()).pathname),
    );
    await page.getByLabel("Upload files").setInputFiles({ name: "ux-v1-data.csv", mimeType: "text/csv", buffer: csv });
    const firstStream = streamWait(page);
    await input.fill(
      [
        "请严格按顺序调用工具完成以下步骤，不要跳过任何一步：",
        "1. 调用 grep，pattern 为 UXV1_GREP_MARKER，path 为 /mnt/user-data/uploads。",
        `2. 调用 write_file 新建 ${report}，内容为两行：第一行 "# UX-V1 报告"，第二行为 grep 找到的那一整行原文。`,
        `3. 调用 read_file 读取 ${report}。`,
        `4. 调用 str_replace 把 ${report} 中的 "# UX-V1 报告" 替换为 "# UX-V1 最终报告"。`,
        `5. 调用 present_files 呈现 ${report}。`,
        "完成后用一句话告诉我结果。",
      ].join("\n"),
    );
    await input.press("Enter");
    const upload = await uploadPromise;
    expect(upload.status(), await upload.text()).toBe(200);
    const stream = await firstStream;
    expect(stream.status(), await stream.text()).toBe(200);
    const identity = await captureStream(page, stream, testInfo);

    const { status, approvals } = await driveToTerminal(page, context, identity, { testInfo });
    const state = await threadState(context.request, identity.threadId);
    const sequence = toolCallNames(state);
    const evidence = { case: "A-files", identity, status, approvals, sequence, marker, durationMs: Date.now() - startedAt };
    await saveEvidence(testInfo, "ux-v1-A-files", evidence);
    await screenshot(page, testInfo, "ux-v1-A-files");

    expect(status).toBe("success");
    for (const tool of ["grep", "write_file", "read_file", "str_replace", "present_files"]) {
      expect(sequence, `model must call ${tool}; actual ${sequence.join(",")}`).toContain(tool);
    }
    expect(toolResults(state, "grep").map((m) => String(m.content)).join("\n")).toContain(marker);
    expect(String(toolResults(state, "present_files").at(-1)?.content)).toContain("Successfully presented files");
    expect(state.values.artifacts).toContain(report);
    const editCall = state.values.messages.flatMap((message) => message.tool_calls ?? [])
      .findLast((call) => call.name === "str_replace" && call.args.path === report);
    expect(editCall, "latest Artifact must originate from the actual str_replace call").toBeTruthy();
    const edit = toolResults(state, "str_replace").find((message) => message.tool_call_id === editCall.id);
    const artifact = edit?.additional_kwargs?.haifa_tool_result;
    expect(artifact).toMatchObject({ path: report, artifactExecutionRef: editCall.id, error: "" });
    expect(artifact.artifactId).toBeTruthy();
    evidence.artifact = artifact;
    evidence.artifactToolCallId = editCall.id;
    await saveEvidence(testInfo, "ux-v1-A-files", evidence);

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.getByTestId("artifact-trigger")).toBeVisible({ timeout: 30_000 });
    await page.getByTestId("artifact-trigger").click();
    await expect(page.getByText("ux-v1-report.md", { exact: true }).first()).toBeVisible();
    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("link", { name: "Download", exact: true }).first().click();
    const download = await downloadPromise;
    const bytes = fs.readFileSync(await download.path());
    const text = bytes.toString("utf8");
    evidence.downloadSha256 = sha256(bytes);
    evidence.downloadText = text;
    evidence.downloadSize = bytes.length;
    await saveEvidence(testInfo, "ux-v1-A-files", evidence);
    expect(`sha256:${evidence.downloadSha256}`).toBe(artifact.artifactSha256);
    expect(bytes.length).toBe(artifact.size);
    expect(text).toContain("# UX-V1 最终报告");
    expect(text).not.toContain("# UX-V1 报告\n");
    expect(text).toContain(marker);
  });

  test("B clarification: card -> option answer -> new Turn continues", async ({ context, page }, testInfo) => {
    const startedAt = Date.now();
    const input = await openNewChat(page, context);
    const firstStream = streamWait(page);
    await input.fill(
      "我需要一份周报，但还没决定格式。请务必先调用 ask_clarification 工具问我格式，clarification_type 用 approach_choice，options 设为 [\"Markdown\", \"纯文本\"]。在我回答之前不要调用任何其它工具，也不要写任何文件。",
    );
    await input.press("Enter");
    const stream = await firstStream;
    expect(stream.status(), await stream.text()).toBe(200);
    const first = await captureStream(page, stream, testInfo);
    const firstRun = await driveToTerminal(page, context, first, { timeoutMs: 180_000, testInfo });
    const firstState = await threadState(context.request, first.threadId);
    const cardMessages = toolResults(firstState, "ask_clarification").filter(
      (message) => message?.artifact?.human_input?.kind === "human_input_request",
    );
    const evidence = {
      case: "B-clarification",
      first,
      firstStatus: firstRun.status,
      firstApprovals: firstRun.approvals,
      firstSequence: toolCallNames(firstState),
      cardPayload: cardMessages.at(-1)?.artifact?.human_input ?? null,
    };
    await saveEvidence(testInfo, "ux-v1-B-clarification", evidence);
    expect(firstRun.status, "a clarification Run must not surface as cancelled or failed").toBe("success");
    expect(cardMessages.length, `ask_clarification card required; tools ${evidence.firstSequence}`).toBeGreaterThan(0);
    expect(evidence.firstSequence).not.toContain("write_file");
    expect(firstRun.approvals).toBe(0);

    const card = page.getByTestId("human-input-card").last();
    await expect(card).toBeVisible({ timeout: 30_000 });
    await screenshot(page, testInfo, "ux-v1-B-card");
    const option = card.getByRole("button", { name: "Markdown", exact: true });
    await expect(option).toBeEnabled();
    const answered = streamWait(
      page,
      (request) => !!request.postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs?.human_input_response,
    );
    await option.click();
    const answerStream = await answered;
    expect(answerStream.status(), await answerStream.text()).toBe(200);
    const second = await captureStream(page, answerStream, testInfo);
    expect(second.threadId).toBe(first.threadId);
    expect(second.runId, "the answer starts a new Turn").not.toBe(first.runId);
    const secondRun = await driveToTerminal(page, context, second, { timeoutMs: 240_000, testInfo });
    const finalState = await threadState(context.request, first.threadId);
    const hidden = finalState.values.messages.filter(
      (message) => message?.type === "human" && message?.additional_kwargs?.human_input_response,
    );
    evidence.second = second;
    evidence.secondStatus = secondRun.status;
    evidence.secondApprovals = secondRun.approvals;
    evidence.hiddenAnswer = hidden.at(-1)?.additional_kwargs?.human_input_response ?? null;
    evidence.durationMs = Date.now() - startedAt;
    await saveEvidence(testInfo, "ux-v1-B-clarification", evidence);
    expect(secondRun.status).toBe("success");
    expect(hidden.length).toBeGreaterThan(0);
    expect(evidence.hiddenAnswer).toMatchObject({ kind: "human_input_response", response_kind: "option", value: "Markdown" });

    await page.reload({ waitUntil: "domcontentloaded" });
    const restored = page.getByTestId("human-input-card").first();
    await expect(restored).toBeVisible({ timeout: 30_000 });
    await expect(restored.getByRole("button", { name: "Markdown", exact: true })).toBeDisabled();
    await expect(restored.getByRole("button", { name: "纯文本", exact: true })).toBeDisabled();
    await expect(restored).toContainText("Markdown");
    for (const reply of hidden) {
      if (String(reply.content).trim()) {
        await expect(page.locator("#chat").getByText(String(reply.content), { exact: true })).toHaveCount(0);
      }
    }
    await screenshot(page, testInfo, "ux-v1-B-after-reload");
  });

  test("C web: web_search -> web_fetch without approval", async ({ context, page }, testInfo) => {
    test.skip(process.env.UXV1_DEFER_WEB === "1", "NOT_RUN: human deferred the DDG-dependent Web chain; independent A/B continue");
    const startedAt = Date.now();
    const input = await openNewChat(page, context);
    const firstStream = streamWait(page);
    await input.fill(
      "请先调用 web_search 搜索 \"bytedance deer-flow GitHub\"，然后对搜索结果中的第一个 URL 调用 web_fetch，最后用一句话总结并附上该链接。必须调用这两个工具。",
    );
    await input.press("Enter");
    const stream = await firstStream;
    expect(stream.status(), await stream.text()).toBe(200);
    const identity = await captureStream(page, stream, testInfo);
    const { status, approvals } = await driveToTerminal(page, context, identity, { timeoutMs: 240_000, testInfo });
    const state = await threadState(context.request, identity.threadId);
    const sequence = toolCallNames(state);
    const search = toolResults(state, "web_search").map((m) => String(m.content));
    const fetched = toolResults(state, "web_fetch").map((m) => String(m.content));
    const searchBlocked = search.length > 0 && search.every((text) => text.includes("\"error\""));
    const fetchBlocked = fetched.length > 0 && fetched.every((text) => text.startsWith("Error:"));
    const evidence = {
      case: "C-web",
      identity,
      status,
      approvals,
      sequence,
      searchHead: search.map((text) => text.slice(0, 600)),
      fetchHead: fetched.map((text) => text.slice(0, 300)),
      searchBlocked,
      fetchBlocked,
      durationMs: Date.now() - startedAt,
    };
    await saveEvidence(testInfo, "ux-v1-C-web", evidence);
    await screenshot(page, testInfo, "ux-v1-C-web");

    expect(status).toBe("success");
    expect(approvals, "web tools must not raise approval cards (B1)").toBe(0);
    expect(sequence).toContain("web_search");
    // A Tool error remains a failing assertion. External attribution requires separate controller
    // evidence with the production GET/proxy semantics; it must never become an automatic skip.
    expect(searchBlocked, "web_search must return actual results").toBe(false);
    expect(search.join("\n")).toContain("\"url\"");
    expect(sequence).toContain("web_fetch");
    expect(fetchBlocked, "web_fetch must return actual content").toBe(false);
    expect(fetched.join("\n").length).toBeGreaterThan(50);
    const results = JSON.parse(search.at(-1)).results;
    expect(Array.isArray(results)).toBe(true);
    expect(results.length).toBeGreaterThan(0);
    const fetchCall = state.values.messages.flatMap((message) => message.tool_calls ?? [])
      .findLast((call) => call.name === "web_fetch");
    const canonicalUrl = (value) => {
      const url = new URL(value);
      return url.hostname === "duckduckgo.com" && url.pathname === "/l/" && url.searchParams.has("uddg")
        ? new URL(url.searchParams.get("uddg")).href : url.href;
    };
    expect(results.map((item) => canonicalUrl(item.url)), "fetch must consume an actual search result URL")
      .toContain(canonicalUrl(fetchCall.args.url));
    const moreSteps = page.getByRole("button", { name: /^\d+ more steps?$/ });
    for (const button of await moreSteps.all()) await button.click();
    const searchCall = state.values.messages.flatMap((message) => message.tool_calls ?? [])
      .findLast((call) => call.name === "web_search");
    await expect(page.getByText(`Search on the web for "${searchCall.args.query}"`, { exact: true })).toBeVisible();
    const fetchLink = page.locator(`#chat a[href=${JSON.stringify(fetchCall.args.url)}][target="_blank"]`).first();
    await expect(fetchLink, "frozen web-fetch card exposes the actual safe URL").toBeVisible();
    await expect(fetchLink).toHaveAttribute("rel", /noopener/);
    expect(await page.locator("#chat a").evaluateAll((links) => links.every((link) =>
      !/^\s*(javascript|data|vbscript):/i.test(link.getAttribute("href") ?? ""))))
      .toBe(true);
    await screenshot(page, testInfo, "ux-v1-C-web-cards");
  });
});
