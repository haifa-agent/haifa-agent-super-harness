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
async function driveToTerminal(page, context, identity, { timeoutMs = 300_000 } = {}) {
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
    const identity = runIdentity(stream);

    const { status, approvals } = await driveToTerminal(page, context, identity);
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
    await saveEvidence(testInfo, "ux-v1-A-files", evidence);
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
    const first = runIdentity(stream);
    const firstRun = await driveToTerminal(page, context, first, { timeoutMs: 180_000 });
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
    const second = runIdentity(answerStream);
    expect(second.threadId).toBe(first.threadId);
    expect(second.runId, "the answer starts a new Turn").not.toBe(first.runId);
    const secondRun = await driveToTerminal(page, context, second, { timeoutMs: 240_000 });
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
    await expect(page.getByTestId("human-input-card").first()).toBeVisible({ timeout: 30_000 });
    await screenshot(page, testInfo, "ux-v1-B-after-reload");
  });

  test("C web: web_search -> web_fetch without approval", async ({ context, page }, testInfo) => {
    const startedAt = Date.now();
    const input = await openNewChat(page, context);
    const firstStream = streamWait(page);
    await input.fill(
      "请先调用 web_search 搜索 \"bytedance deer-flow GitHub\"，然后对搜索结果中的第一个 URL 调用 web_fetch，最后用一句话总结并附上该链接。必须调用这两个工具。",
    );
    await input.press("Enter");
    const stream = await firstStream;
    expect(stream.status(), await stream.text()).toBe(200);
    const identity = runIdentity(stream);
    const { status, approvals } = await driveToTerminal(page, context, identity, { timeoutMs: 240_000 });
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
    // "No results" is BLOCKED_EXTERNAL only when an independent browser-like request from this
    // machine is refused too; if that probe sees results, SH itself failed (product FAIL).
    if (searchBlocked) {
      const probe = await fetch("https://html.duckduckgo.com/html/", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        },
        body: new URLSearchParams({ q: "bytedance deer-flow GitHub", b: "", l: "wt-wt" }),
      }).catch((error) => ({ status: -1, text: async () => String(error) }));
      const probeBody = await probe.text();
      evidence.externalProbe = { status: probe.status, resultBodies: (probeBody.match(/result__body/g) ?? []).length };
      await saveEvidence(testInfo, "ux-v1-C-web", evidence);
      expect(evidence.externalProbe.resultBodies, "DuckDuckGo answers this machine; SH search returned nothing").toBe(0);
    }
    test.skip(searchBlocked, "BLOCKED_EXTERNAL: DuckDuckGo refuses this machine too");
    expect(search.join("\n")).toContain("\"url\"");
    expect(sequence).toContain("web_fetch");
    test.skip(fetchBlocked, "BLOCKED_EXTERNAL: Jina Reader returned an error");
    expect(fetched.join("\n").length).toBeGreaterThan(50);
  });
});
