// Oracle 13: exercise the frozen new-agent page with the actual managed model.
// All SOUL content is synthetic. Raw Playwright traces retain real browser traffic;
// derived JSON evidence records hashes and identities instead of duplicating SOUL.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { expect, test } = require(path.resolve(__dirname, "../../frontend/node_modules/@playwright/test"));
const APP = process.env.E2E_APP_URL ?? `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "35618"}`;
const MODEL = process.env.W18_MODEL_NAME ?? "w18-deepseek-live";
const PROVIDER_MODEL = "deepseek-v4-pro";
const STREAM = /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/;
const hash = (value) => crypto.createHash("sha256").update(String(value)).digest("hex");

async function evidence(info, name, value) {
  const file = info.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await info.attach(name, { path: file, contentType: "application/json" });
}

function nextStream(page) {
  return page.waitForResponse((response) => response.request().method() === "POST" && STREAM.test(new URL(response.url()).pathname), { timeout: 30_000 });
}

async function readState(context, threadId) {
  const response = await context.request.get(`${APP}/api/langgraph/threads/${threadId}/state`, { timeout: 10_000 });
  expect(response.status(), "authoritative thread state must be readable").toBe(200);
  return response.json();
}

async function complete(page, context, pending, info, label, identities) {
  const response = await pending;
  expect(response.status(), `${label}: stream admission`).toBe(200);
  const location = response.headers()["content-location"];
  const match = location?.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `${label}: actual Run identity`).toBeTruthy();
  const identity = { threadId: match[1], runId: match[2], location };
  identities.push(identity);
  const input = response.request().postDataJSON();
  expect(input.context.is_bootstrap, `${label}: frozen UI bootstrap context`).toBe(true);
  if (input.context.model_name != null) expect(input.context.model_name).toBe(MODEL);
  const started = Date.now();
  let terminal;
  await expect.poll(async () => {
    expect(await page.getByTestId("human-input-card").getByRole("button", { name: /^(Approve|批准)$/ }).count(), "setup_agent must finish without an approval card").toBe(0);
    const snapshot = await context.request.get(`${APP}${location}`, { timeout: 10_000 });
    expect(snapshot.status()).toBe(200);
    terminal = await snapshot.json();
    const status = terminal.status;
    expect(["error", "interrupted", "timeout"], `${label}: failed or suspended Run`).not.toContain(status);
    return status;
  }, { timeout: 90_000, intervals: [250, 500, 1000], message: `${label}: bounded terminal Run` }).toBe("success");
  expect(terminal.run_id).toBe(identity.runId);
  const modelCalls = terminal.diagnostics.model_calls;
  expect(modelCalls.length, `${label}: actual managed model calls`).toBeGreaterThan(0);
  for (const call of modelCalls) {
    expect(call.run_id).toBe(identity.runId);
    expect(call.provider_id).toBe("super-harness-managed-openai-compatible");
    expect(call.model_id).toBe(PROVIDER_MODEL);
    expect(call.status).toBe("SUCCEEDED");
  }
  const raw = await response.text();
  const state = await readState(context, identity.threadId);
  await evidence(info, label, {
    derived: true, identity, status: "success", durationMs: Date.now() - started,
    actualModelCalls: modelCalls.map(({ model_call_id, run_id, provider_id, model_id, status, attempt }) =>
      ({ model_call_id, run_id, provider_id, model_id, status, attempt })),
    streamSha256: hash(raw), streamBytes: Buffer.byteLength(raw),
    messages: state.values.messages.map((message) => ({
      id: message.id, type: message.type, name: message.name,
      contentSha256: hash(typeof message.content === "string" ? message.content : JSON.stringify(message.content)),
      toolCallId: message.tool_call_id,
      success: message.additional_kwargs?.haifa_tool_result?.success,
      toolCalls: (message.tool_calls ?? []).map((call) => ({ id: call.id, name: call.name })),
    })),
  });
  return { identity, state };
}

test("Agent SOUL: conversation -> save -> setup_agent -> created -> gallery refresh", async ({ page, context }, info) => {
  const name = `uxv1-soul-${Date.now()}`;
  const marker = `SYNTHETIC_SOUL_${Date.now()}`;
  const identities = [];
  try {
    const models = await (await context.request.get(`${APP}/api/models`)).json();
    expect(models.models.map((model) => model.name)).toContain(MODEL);
    const managed = await (await context.request.get(`${APP}/api/managed-models`)).json();
    const enabled = managed.models.filter((model) => model.enabled);
    expect(enabled.map((model) => model.name), "fresh acceptance data has one enabled managed model").toEqual([MODEL]);
    expect(enabled[0].model).toBe(PROVIDER_MODEL);
    await page.goto("/workspace/agents/new", { waitUntil: "domcontentloaded" });
    await page.getByPlaceholder(/e\.g\. code-reviewer|例如 code-reviewer/).fill(name);
    const initial = nextStream(page);
    await page.getByRole("button", { name: /^(Continue|继续)$/ }).click();
    const first = await complete(page, context, initial, info, "01-bootstrap", identities);
    const input = page.locator('textarea[name="message"]');
    await expect(input).toBeEnabled();
    const design = nextStream(page);
    await input.fill(`请将这个agent设计为英文测试助手，description为Synthetic acceptance assistant。初始SOUL.md必须包含独立一行 ${marker}。请先确认设计，暂不保存；我会用页面的保存菜单正式确认。不要调用任何Web、文件、MCP工具，也不要再询问额外问题。`);
    await input.press("Enter");
    const designed = await complete(page, context, design, info, "02-design", identities);
    expect(designed.identity.threadId).toBe(first.identity.threadId);
    const priorMessages = new Set(designed.state.values.messages.map((message) => message.id));
    await page.getByRole("button", { name: /^(More actions|更多操作)$/ }).click();
    const save = nextStream(page);
    await page.getByRole("menuitem", { name: /^(Save agent|保存智能体)$/ }).click();
    const saved = await complete(page, context, save, info, "03-save", identities);
    expect(saved.identity.threadId).toBe(first.identity.threadId);
    const newMessages = saved.state.values.messages.filter((message) => !priorMessages.has(message.id));
    const setupCalls = newMessages.flatMap((message) => message.tool_calls ?? []).filter((call) => call.name === "setup_agent");
    expect(setupCalls.length, "the actual Save turn must invoke setup_agent").toBeGreaterThan(0);
    const setupResults = newMessages.filter((message) => message.type === "tool" && message.name === "setup_agent");
    expect(setupResults.length).toBeGreaterThan(0);
    for (const result of setupResults) {
      expect(setupCalls.some((call) => call.id === result.tool_call_id)).toBe(true);
      expect(result.additional_kwargs?.haifa_tool_result?.success).toBe(true);
      expect(String(result.content)).toContain(`Agent '${name}' created successfully!`);
    }
    await expect(page.getByText(/^(Agent created!|智能体已创建！)$/)).toBeVisible({ timeout: 15_000 });
    const record = await context.request.get(`${APP}/api/agents/${name}`, { timeout: 10_000 });
    expect(record.status(), "created UI must correspond to an actual stored agent").toBe(200);
    const agent = await record.json();
    expect(agent.name).toBe(name);
    expect(agent.soul).toContain(marker);
    expect(agent.soul.trim().length).toBeGreaterThan(0);
    const thread = await context.request.get(`${APP}/api/threads/${first.identity.threadId}`);
    expect(thread.status()).toBe(200);
    const metadata = (await thread.json()).metadata;
    expect(metadata.haifa_agent_name).toBe(name);
    expect(metadata.haifa_agent_bootstrap).toBe(true);
    expect(metadata.haifa_lead_prompt_signature).toMatch(/^[0-9a-f]{64}$/);
    await evidence(info, "04-created-record", {
      derived: true, name, soulSha256: hash(agent.soul), soulChars: agent.soul.length,
      markerPresent: true, setupCallIds: setupCalls.map((call) => call.id), metadata,
      approvals: 0, identities,
    });
    const created = info.outputPath("agent-created.png");
    await page.screenshot({ path: created, fullPage: true });
    await info.attach("agent-created", { path: created, contentType: "image/png" });
    await page.getByRole("button", { name: /^(Back to Gallery|返回 Gallery)$/ }).click();
    await expect(page).toHaveURL(/\/workspace\/agents$/);
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.getByText(name, { exact: true })).toBeVisible();
    const refreshed = await context.request.get(`${APP}/api/agents/${name}`);
    expect(refreshed.status()).toBe(200);
    expect(hash((await refreshed.json()).soul)).toBe(hash(agent.soul));
    const gallery = info.outputPath("agent-gallery-refreshed.png");
    await page.screenshot({ path: gallery, fullPage: true });
    await info.attach("agent-gallery-refreshed", { path: gallery, contentType: "image/png" });
  } finally {
    await evidence(info, "observed-runs", { derived: true, name, identities, syntheticInputsOnly: true, retries: 0 });
  }
});
