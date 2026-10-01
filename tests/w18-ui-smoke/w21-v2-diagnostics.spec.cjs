const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../frontend/node_modules/@playwright/test",
));

const APP = process.env.E2E_APP_URL;

function identity(response) {
  const location = response.headers()["content-location"];
  expect(location, "Run stream Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `Run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2], location };
}

function streamResponse(response) {
  return response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname);
}

async function terminalRun(request, run, timeout = 25_000) {
  let value;
  await expect.poll(async () => {
    const response = await request.get(`${APP}${run.location}`);
    expect(response.status(), await response.text()).toBe(200);
    value = await response.json();
    return ["success", "error", "interrupted"].includes(value.status) ? value.status : null;
  }, { timeout, intervals: [100, 250, 500] }).not.toBeNull();
  return value;
}

async function submit(page, prompt) {
  const stream = page.waitForResponse(streamResponse);
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  await input.fill(prompt);
  await input.press("Enter");
  const response = await stream;
  expect(response.status(), await response.text()).toBe(200);
  return identity(response);
}

test("W21 frozen UI projects one approved production Artifact and true Usage", async (
  { context, page }, testInfo,
) => {
  const marker = `w18-file-tool-${Date.now()}`;
  const artifactPath = `/mnt/user-data/outputs/${marker}.md`;
  const expected = Buffer.from(`W18 ${marker} produced by write_file 数据 ✓`, "utf8");
  const expectedHash = crypto.createHash("sha256").update(expected).digest("hex");
  const browserUsage = [];
  page.on("response", async (response) => {
    if (/\/api\/threads\/[^/]+\/token-usage$/.test(new URL(response.url()).pathname)) {
      browserUsage.push({ url: response.url(), status: response.status() });
    }
  });

  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const run = await submit(page, marker);
  const approval = page.getByTestId("human-input-card");
  await expect(approval).toBeVisible();
  const waitingResponse = await context.request.get(`${APP}${run.location}`);
  expect(waitingResponse.status()).toBe(200);
  const waiting = await waitingResponse.json();
  expect(waiting.diagnostics).toMatchObject({
    thread_run_id: run.runId,
    sdk_status: "WAITING_APPROVAL",
  });
  expect(waiting.diagnostics.interactions).toEqual(expect.arrayContaining([
    expect.objectContaining({ run_id: run.runId, state: expect.any(String) }),
  ]));
  const pendingRequestId = waiting.diagnostics.interactions.at(-1).request_id;
  expect(pendingRequestId).toBeTruthy();

  let approvalReply;
  const resumedStream = page.waitForResponse((response) => {
    if (!streamResponse(response)) return false;
    try {
      const reply = response.request().postDataJSON()?.input?.messages?.at(-1)
        ?.additional_kwargs?.human_input_response;
      if (!reply) return false;
      approvalReply = reply;
      return true;
    } catch { return false; }
  });
  await approval.getByRole("button", { name: "Approve", exact: true }).click();
  const resumed = await resumedStream;
  expect(resumed.status(), await resumed.text()).toBe(200);
  expect(identity(resumed)).toEqual(run);
  expect(approvalReply).toMatchObject({
    request_id: pendingRequestId,
    option_id: "approve",
    value: "approve",
  });

  const finished = await terminalRun(context.request, run);
  expect(finished.status).toBe("success");
  const facts = finished.diagnostics;
  expect(facts).toMatchObject({
    thread_run_id: run.runId,
    projection_complete: true,
    sdk_status: "COMPLETED",
    usage: { availability: "known" },
  });
  expect(facts.run_attempt).toBeNull();
  expect(facts.model_calls.length).toBeGreaterThan(0);
  for (const call of facts.model_calls) {
    expect(call).toMatchObject({
      run_id: run.runId,
      model_call_id: expect.any(String),
      provider_id: "super-harness-managed-openai-compatible",
      model_id: "w18-controlled-model",
      status: "SUCCEEDED",
    });
    expect(call.configuration_digest).toBeNull();
  }
  expect(facts.interactions).toEqual(expect.arrayContaining([
    expect.objectContaining({
      request_id: pendingRequestId,
      run_id: run.runId,
      action: "approve",
    }),
  ]));
  const write = facts.tool_calls.find((call) => call.name === "write_file");
  expect(write).toMatchObject({
    run_id: run.runId,
    tool_call_id: expect.any(String),
    status: "COMPLETED",
    actual_source: null,
  });
  const artifact = facts.artifacts.find((item) => item.path === artifactPath);
  expect(artifact).toMatchObject({
    artifact_id: expect.any(String),
    run_id: run.runId,
    tool_call_id: write.tool_call_id,
    path: artifactPath,
    sha256: `sha256:${expectedHash}`,
  });
  expect(facts.usage.input_tokens).toBeGreaterThan(0);
  expect(facts.usage.output_tokens).toBeGreaterThan(0);
  expect(finished.total_tokens).toBe(facts.usage.total_tokens);

  await expect(page.getByTestId("artifact-trigger")).toBeVisible();
  await page.getByTestId("artifact-trigger").click();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download", exact: true }).click();
  const download = await downloadPromise;
  const bytes = fs.readFileSync(await download.path());
  expect(bytes).toEqual(expected);
  expect(crypto.createHash("sha256").update(bytes).digest("hex")).toBe(expectedHash);

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.getByTestId("artifact-trigger")).toBeVisible();
  const usageResponse = await context.request.get(`${APP}/api/threads/${run.threadId}/token-usage`);
  expect(usageResponse.status(), await usageResponse.text()).toBe(200);
  const usage = await usageResponse.json();
  expect(usage).toMatchObject({
    thread_id: run.threadId,
    total_input_tokens: facts.usage.input_tokens,
    total_output_tokens: facts.usage.output_tokens,
    total_tokens: facts.usage.total_tokens,
    total_runs: 1,
  });
  await expect.poll(() => browserUsage.some((entry) => entry.url.includes(run.threadId)), {
    timeout: 10_000,
  }).toBe(true);

  const evidence = {
    ...run, approvalRequestId: pendingRequestId, modelCalls: facts.model_calls,
    toolCallId: write.tool_call_id, artifactId: artifact.artifact_id,
    artifactPath, artifactSha256: artifact.sha256, downloadedSha256: expectedHash,
    usage: facts.usage, browserUsage, httpUsage: usage,
  };
  const file = testInfo.outputPath("w21-positive.json");
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`);
  await testInfo.attach("w21-positive.json", { path: file, contentType: "application/json" });
});

test("W21 Provider omission keeps Usage unknown instead of fabricated zero", async (
  { context, page }, testInfo,
) => {
  const marker = `w21-omit-usage-${Date.now()}`;
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const run = await submit(page, marker);
  const finished = await terminalRun(context.request, run);
  expect(finished.status).toBe("error");
  expect(finished.diagnostics.sdk_status).toBe("FAILED");
  expect(finished.diagnostics.error_code).toBe("MODEL_RESPONSE_INVALID");
  expect(finished.diagnostics.model_calls).toEqual(expect.arrayContaining([
    expect.objectContaining({ status: "FAILED", reason_code: "PARTIAL_RESPONSE" }),
  ]));
  expect(finished.diagnostics.thread_run_id).toBe(run.runId);
  expect(finished.diagnostics.usage).toMatchObject({
    availability: "unknown", input_tokens: null, output_tokens: null,
    total_tokens: null, llm_call_count: null,
  });
  expect(finished.total_input_tokens).toBeNull();
  expect(finished.total_output_tokens).toBeNull();
  expect(finished.total_tokens).toBeNull();
  expect(finished.llm_call_count).toBeNull();
  const usageResponse = await context.request.get(`${APP}/api/threads/${run.threadId}/token-usage`);
  expect(usageResponse.status(), await usageResponse.text()).toBe(200);
  expect(await usageResponse.json()).toBeNull();
  const evidence = { ...run, sdkStatus: finished.diagnostics.sdk_status,
    status: finished.status, usage: finished.diagnostics.usage, threadUsage: null };
  const file = testInfo.outputPath("w21-unknown-usage.json");
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`);
  await testInfo.attach("w21-unknown-usage.json", { path: file, contentType: "application/json" });
});
