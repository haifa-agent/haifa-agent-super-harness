// Opt-in production acceptance: real managed provider, SDK tools and frozen approval UI.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { expect, test } = require(path.resolve(__dirname, "../../../frontend/node_modules/@playwright/test"));
const APP = process.env.E2E_APP_URL;
const NAME = "w15-package-ui";
const runs = [];

async function save(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + "\n");
  await testInfo.attach(name, { path: file, contentType: "application/json" });
}

async function state(request, identity) {
  const response = await request.get(`${APP}/api/langgraph/threads/${identity.threadId}/state`, { timeout: 10_000 });
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function chat(page, context, prompt, testInfo, previousRun) {
  if (!previousRun) await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const stream = page.waitForResponse(response => response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname), { timeout: 30_000 });
  await input.fill(prompt);
  await input.press("Enter");
  const response = await stream;
  expect(response.status(), await response.text()).toBe(200);
  const location = response.headers()["content-location"];
  const match = location?.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, "production stream must identify the real Run").toBeTruthy();
  const identity = { threadId: match[1], runId: match[2], location };
  if (previousRun) {
    expect(identity.threadId).toBe(previousRun.threadId);
    expect(identity.runId).not.toBe(previousRun.runId);
  }
  runs.push(identity);
  const deadline = Date.now() + 90_000;
  let approvals = 0;
  while (Date.now() < deadline) {
    const snapshot = await context.request.get(`${APP}${location}`, { timeout: 5_000 });
    expect(snapshot.status(), await snapshot.text()).toBe(200);
    const status = (await snapshot.json()).status;
    if (status === "success") return { identity, approvals, state: await state(context.request, identity) };
    expect(["error", "timeout"], `Run ${identity.runId} failed`).not.toContain(status);
    const approve = page.getByTestId("human-input-card").getByRole("button", { name: /^(Approve|批准)$/ });
    if (await approve.count() && await approve.last().isEnabled()) {
      if (previousRun) {
        const unpublished = await context.request.get(`${APP}/api/threads/${identity.threadId}/artifacts/mnt/user-data/outputs/w15-evaluation.json`, { timeout: 10_000 });
        expect(unpublished.status(), "the evaluation file must not exist before its write approval").toBe(404);
        expect((await state(context.request, identity)).values.artifacts).not.toContain("/mnt/user-data/outputs/w15-evaluation.json");
      }
      const resumed = page.waitForResponse(value => value.request().method() === "POST" &&
        Boolean(value.request().postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs?.human_input_response), { timeout: 15_000 });
      await approve.last().click();
      const continuation = await resumed;
      expect(continuation.status(), await continuation.text()).toBe(200);
      expect(continuation.headers()["content-location"]).toBe(location);
      approvals++;
    } else {
      await page.waitForTimeout(250); // Bounded observation of this Run; no model or functional retry.
    }
  }
  await save(testInfo, "first-timeout-state", await state(context.request, identity));
  throw new Error(`Run ${identity.runId} exceeded its 90-second terminal deadline`);
}

function calls(value) { return value.values.messages.flatMap(message => message.tool_calls ?? []); }
function results(value, name) { return value.values.messages.filter(message => message.type === "tool" && message.name === name); }

test.afterEach(async ({ context }, testInfo) => {
  for (const identity of runs) {
    for (const [name, endpoint] of [["state", `/api/langgraph/threads/${identity.threadId}/state`],
      ["usage", `/api/threads/${identity.threadId}/token-usage`], ["run", identity.location]]) {
      const response = await context.request.get(`${APP}${endpoint}`, { timeout: 10_000 });
      await save(testInfo, `${identity.runId}-${name}`, { identity, status: response.status(), body: await response.text() });
    }
  }
});

test("W15 real model edits a Skill and evaluates its actual resources in a new Run", async ({ context, page }, testInfo) => {
  page.setDefaultTimeout(15_000);
  page.setDefaultNavigationTimeout(30_000);
  const started = new Date().toISOString();
  const fixture = path.resolve(__dirname, "../../fixtures/w15/package_archive.py");
  const archive = execFileSync("python", ["-B", fixture], { timeout: 10_000 });
  const uploaded = await context.request.post(`${APP}/api/skills/install/upload`, {
    multipart: { archive: { name: `${NAME}.skill`, mimeType: "application/zip", buffer: archive } }, timeout: 90_000,
  });
  expect(uploaded.status(), await uploaded.text()).toBe(200);
  const before = await (await context.request.get(`${APP}/api/skills/custom/${NAME}/export-manifest`)).json();
  const editedContent = "---\nname: w15-package-ui\ndescription: A controlled UI package for acceptance\nlicense: MIT\n---\nW15_LIVE_EDITED_PACKAGE\nRead references/fact.txt and evals/evals.json before evaluating.\n";
  const edited = await chat(page, context,
    `Use skill_manage action edit for ${NAME}, retaining its supporting files. The content parameter must be the decoded value of this JSON string, including its final newline: ${JSON.stringify(editedContent)}. Do not use other write tools. Report the actual tool result.`, testInfo);
  expect(edited.approvals).toBeGreaterThan(0);
  expect(calls(edited.state).some(call => call.name === "skill_manage" && call.args.action === "edit" && call.args.name === NAME)).toBe(true);
  expect(results(edited.state, "skill_manage").at(-1)?.additional_kwargs?.haifa_tool_result?.success).toBe(true);
  const after = await (await context.request.get(`${APP}/api/skills/custom/${NAME}/export-manifest`)).json();
  expect(after.revision).not.toBe(before.revision);
  expect((await (await context.request.get(`${APP}/api/skills/custom/${NAME}`)).json()).content).toBe(editedContent);
  const report = "/mnt/user-data/outputs/w15-evaluation.json";
  const readOnly = await chat(page, context,
    `Load ${NAME} using skill_load, read references/fact.txt using skill_resource_read, and report its actual fact. ` +
    `Do not create any output file or edit the package.`, testInfo);
  expect(readOnly.approvals).toBe(0);
  expect(results(readOnly.state, "skill_resource_read").at(-1)?.additional_kwargs?.haifa_tool_result?.content).toContain("17");
  const evaluated = await chat(page, context,
    `请使用 ${NAME}，读取 references/fact.txt 和 evals/evals.json，执行编号 1 的评测。请根据实际文件内容判断，不要猜测。` +
    `Evaluate case id 1 by comparing the actual resource fact with the expected_output in the actual eval file. ` +
    `Write ${report} using write_file with JSON fields skill_name, case_id (integer), observed (string), expected (string), passed (boolean). ` +
    `Use the actual tool results, do not invent a score. Call present_files for that JSON. ` +
    `Then call review_skill_package on skill://custom/${NAME}, profile deerflow, include_content facts-only, scope ["all"]. ` +
    `Do not execute package code. Summarize the real outcome.`, testInfo, readOnly.identity);
  expect(evaluated.identity.threadId).not.toBe(edited.identity.threadId);
  expect(evaluated.approvals).toBeGreaterThan(0);
  const evaluationState = { values: { messages: evaluated.state.values.messages.filter(message => message.run_id === evaluated.identity.runId) } };
  const sequence = calls(evaluationState).map(call => call.name);
  for (const name of ["skill_load", "skill_resource_read", "write_file", "present_files", "review_skill_package"]) expect(sequence).toContain(name);
  const reads = calls(evaluationState).filter(call => call.name === "skill_resource_read");
  expect(results(evaluationState, "skill_resource_read").every(message => message.additional_kwargs?.haifa_tool_status === "completed" &&
    typeof message.additional_kwargs?.haifa_tool_result?.content === "string"), "all reads in the next Run must succeed after its own activation").toBe(true);
  expect(results(evaluationState, "skill_resource_read").length).toBeGreaterThanOrEqual(2);
  expect(JSON.stringify(reads)).toContain("references/fact.txt");
  expect(JSON.stringify(reads)).toContain("evals/evals.json");
  const load = results(evaluated.state, "skill_load").at(-1);
  expect(load?.additional_kwargs?.haifa_tool_result?.activated).toBe(true);
  const review = results(evaluated.state, "review_skill_package").at(-1);
  expect(review?.artifact).toMatchObject({ evidence_level: "static", target_executed: false, safety_proven: false });
  const writeCalls = calls(evaluated.state).filter(call => call.name === "write_file" && call.args.path === report);
  const writeResults = results(evaluated.state, "write_file");
  const writeAttempts = writeCalls.map(call => {
    const message = writeResults.find(result => result.tool_call_id === call.id);
    return { toolCallId: call.id, contentType: typeof call.args.content,
      status: message?.additional_kwargs?.haifa_tool_status ?? "missing",
      error: message?.additional_kwargs?.haifa_tool_error ?? null,
      artifactId: message?.additional_kwargs?.haifa_tool_result?.artifactId ?? null };
  });
  const writer = writeCalls.find(call => writeResults.some(message =>
    message.tool_call_id === call.id && message.additional_kwargs?.haifa_tool_result?.artifactId));
  expect(writer).toBeTruthy();
  const artifact = writeResults.find(message => message.tool_call_id === writer.id)?.additional_kwargs?.haifa_tool_result;
  expect(artifact).toMatchObject({ path: report, artifactExecutionRef: writer.id, runId: evaluated.identity.runId });
  expect(artifact.artifactId).toBeTruthy();
  expect(evaluated.state.values.artifacts).toContain(report);
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.getByTestId("artifact-trigger").first().click();
  const downloadReady = page.waitForEvent("download", { timeout: 15_000 });
  await page.getByRole("link", { name: /^(Download|下载)$/ }).first().click();
  const download = await downloadReady;
  const bytes = fs.readFileSync(await download.path());
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  expect(`sha256:${sha256}`).toBe(artifact.artifactSha256);
  const observed = JSON.parse(bytes.toString("utf8"));
  expect(observed).toMatchObject({ skill_name: NAME, case_id: 1, observed: "17", expected: "17", passed: true });
  await save(testInfo, "w15-live-evaluation", { started, finished: new Date().toISOString(), provider: "https://api.deepseek.com",
    model: "deepseek-v4-pro", runnerRetry: false, writeAttempts, editedRun: edited.identity, readOnlyRun: readOnly.identity, evaluatedRun: evaluated.identity,
    revisions: { before: before.revision, after: after.revision }, sequence, approvals: edited.approvals + evaluated.approvals,
    artifact, sha256, actualEvaluation: observed, scriptExecution: "NOT_RUN - W14 dependency" });
  await page.screenshot({ path: testInfo.outputPath("w15-live-evaluation.png"), fullPage: true });
});

test("W15 real model evaluates Skill resources in the next conversation Run", async ({ context, page }, testInfo) => {
  page.setDefaultTimeout(15_000);
  page.setDefaultNavigationTimeout(30_000);
  const fixture = path.resolve(__dirname, "../../fixtures/w15/package_archive.py");
  const archive = execFileSync("python", ["-B", fixture], { timeout: 10_000 });
  const installed = await context.request.post(`${APP}/api/skills/install/upload`, {
    multipart: { archive: { name: `${NAME}.skill`, mimeType: "application/zip", buffer: archive } }, timeout: 90_000,
  });
  expect(installed.status(), await installed.text()).toBe(200);
  const first = await chat(page, context,
    `请加载 ${NAME}，读取它的 references/fact.txt，告诉我文件里记录的数值，并说明依据。`, testInfo);
  expect(first.approvals).toBe(0);
  expect(results(first.state, "skill_resource_read").at(-1)?.additional_kwargs?.haifa_tool_result?.content).toContain("17");
  const report = "/mnt/user-data/outputs/w15-evaluation.json";
  const evaluated = await chat(page, context,
    `请使用 ${NAME}，读取 references/fact.txt 和 evals/evals.json，执行编号 1 的评测。` +
    `将实际值、预期值和是否通过写入 ${report}，并提供下载链接。请根据实际文件内容判断，不要猜测。` +
    `JSON字段使用 skill_name、case_id（整数）、observed（字符串）、expected（字符串）、passed（布尔值）。请通过 present_files 提供文件。`,
    testInfo, first.identity);
  expect(evaluated.approvals).toBe(1);
  const own = { values: { messages: evaluated.state.values.messages.filter(message => message.run_id === evaluated.identity.runId) } };
  const resourceReads = results(own, "skill_resource_read");
  expect(resourceReads.length).toBeGreaterThanOrEqual(2);
  expect(resourceReads.every(message => message.additional_kwargs?.haifa_tool_status === "completed" &&
    typeof message.additional_kwargs?.haifa_tool_result?.content === "string"), "next request must load before every resource read").toBe(true);
  expect(calls(own).filter(call => ["skill_load", "skill_resource_read"].includes(call.name))[0].name).toBe("skill_load");
  expect(resourceReads.map(message => message.additional_kwargs.haifa_tool_result.path)).toEqual(expect.arrayContaining(["references/fact.txt", "evals/evals.json"]));
  const write = results(own, "write_file").at(-1);
  const artifact = write?.additional_kwargs?.haifa_tool_result;
  expect(artifact).toMatchObject({ path: report, runId: evaluated.identity.runId, artifactExecutionRef: write.tool_call_id });
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.getByTestId("artifact-trigger").first().click();
  const ready = page.waitForEvent("download", { timeout: 15_000 });
  await page.getByRole("link", { name: /^(Download|下载)$/ }).first().click();
  const download = await ready;
  const bytes = fs.readFileSync(await download.path());
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  expect(`sha256:${sha256}`).toBe(artifact.artifactSha256);
  const actualEvaluation = JSON.parse(bytes.toString("utf8"));
  expect(actualEvaluation).toMatchObject({ skill_name: NAME, case_id: 1, observed: "17", expected: "17", passed: true });
  await save(testInfo, "w15-next-turn-evaluation", { provider: "https://api.deepseek.com", model: "deepseek-v4-pro",
    firstRun: first.identity, evaluatedRun: evaluated.identity, approvals: evaluated.approvals, artifact, sha256,
    actualEvaluation, resourceReadFailures: 0, runnerRetry: false });
  await page.screenshot({ path: testInfo.outputPath("w15-next-turn-evaluation.png"), fullPage: true });
});
