// Frozen DeerFlow Skill gallery -> Haifa SDK Skill Tool -> Run Context.
// The controlled Provider calls skill_load only when the SDK offers it, then
// derives its answer from the next model request's activated instructions.
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP = process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "35618"}`;
const EVIDENCE = process.env.W18_EVIDENCE_DIR;
const SKILL = "consulting-analysis";
const HASH = "5bc99df7e5f8af5471eaa5d20b85fc46e99895cf3f2642fa9da94108d03ca13e";

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2] };
}

async function sendChat(page, marker) {
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const stream = page.waitForResponse((response) => response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
  { timeout: 30_000 });
  await input.fill(`${marker}: use the available consulting-analysis Skill and report whether its instructions reached this Run`);
  await input.press("Enter");
  const response = await stream;
  expect(response.status(), await response.text()).toBe(200);
  return runIdentity(response);
}

test("W15 frozen Skill UI installs uses and exports a complete custom package", async ({ context, page }, testInfo) => {
  page.setDefaultTimeout(20_000);
  page.setDefaultNavigationTimeout(30_000);
  const { execFileSync } = require("node:child_process");
  const fixture = path.resolve(__dirname, "../../fixtures/w15/package_archive.py");
  const archive = execFileSync("python", ["-B", fixture], { timeout: 10_000 });
  const name = "w15-package-ui";
  let primaryFailure;
  try {
    await page.goto("/workspace/capabilities?tab=skills", { waitUntil: "domcontentloaded" });
    const uploadResponse = page.waitForResponse((response) => response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/skills/install/upload", { timeout: 45_000 });
    await page.locator('input[type="file"][accept=".skill"]').setInputFiles({ name: `${name}.skill`, mimeType: "application/zip", buffer: archive });
    const uploaded = await uploadResponse;
    expect(uploaded.status(), await uploaded.text()).toBe(200);
    const toggle = page.getByRole("switch", { name: new RegExp(name) });
    await expect(toggle).toBeVisible();
    await expect(toggle).toBeChecked();
    await toggle.click();
    await expect(toggle).not.toBeChecked();
    await expect.poll(async () => (await (await context.request.get(`${APP}/api/skills/${name}`)).json()).enabled).toBe(false);
    await toggle.click();
    await expect(toggle).toBeChecked();
    await expect.poll(async () => (await (await context.request.get(`${APP}/api/skills/${name}`)).json()).enabled).toBe(true);
    await page.screenshot({ path: testInfo.outputPath("w15-package-installed.png"), fullPage: true });
    await page.getByRole("button", { name: new RegExp(`^(View details|查看详情) ${name}$`) }).click();
    await page.getByRole("dialog").getByRole("button", { name: /^(Export|导出)$/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.locator("summary").click();
    await expect(dialog.getByText("references/fact.txt", { exact: true })).toBeVisible();
    await expect(dialog.getByText("evals/evals.json", { exact: true })).toBeVisible();
    const downloadReady = page.waitForEvent("download", { timeout: 30_000 });
    await dialog.getByRole("button", { name: /Download \.skill|下载 \.skill/ }).click();
    const download = await downloadReady;
    expect(download.suggestedFilename()).toBe(`${name}.skill`);
    const saved = testInfo.outputPath("w15-export.skill");
    await download.saveAs(saved);
    const roundTrip = JSON.parse(execFileSync("python", ["-B", fixture, "--verify", saved], { timeout: 10_000, encoding: "utf8" }));
    expect(roundTrip.status).toBe("PASS");
    await saveEvidence(testInfo, "w15-package-round-trip", roundTrip);
    await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
    const marker = `w15-package-use-${Date.now()}`;
    const input = page.locator('textarea[name="message"]');
    await expect(input).toBeVisible();
    const stream = page.waitForResponse((response) => response.request().method() === "POST" && /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname), { timeout: 30_000 });
    await input.fill(`${marker}: use w15-package-ui and read its real reference fact`);
    await input.press("Enter");
    const response = await stream;
    expect(response.status(), await response.text()).toBe(200);
    const identity = runIdentity(response);
    await waitForSuccess(context.request, identity);
    await expect(page.locator("#chat").getByText(`${marker} PACKAGE_CONTEXT_AND_RESOURCE_17`, { exact: true })).toBeVisible();
    const state = await (await context.request.get(`${APP}/api/langgraph/threads/${identity.threadId}/state`)).json();
    const tools = state.values.messages.filter((message) => message.type === "tool").map((message) => message.name);
    expect(tools).toEqual(expect.arrayContaining(["skill_load", "skill_resource_read"]));
    await saveEvidence(testInfo, "w15-package-run", { ...identity, tools, provider: providerEvents().filter((event) => event.type.startsWith("w15_")) });
    await page.screenshot({ path: testInfo.outputPath("w15-package-used.png"), fullPage: true });
  } catch (failure) {
    primaryFailure = failure;
    throw failure;
  } finally {
    try {
      const deleted = await context.request.delete(`${APP}/api/skills/custom/${name}`, { timeout: 10_000 });
      expect([200, 404]).toContain(deleted.status());
    } catch (cleanupFailure) {
      await saveEvidence(testInfo, "w15-package-cleanup-failure", { message: String(cleanupFailure) });
      if (!primaryFailure) throw cleanupFailure;
    }
  }
});

async function waitForSuccess(request, { threadId, runId }) {
  let latest;
  await expect.poll(async () => {
    const response = await request.get(`${APP}/api/langgraph/threads/${threadId}/runs/${runId}`);
    expect(response.status(), await response.text()).toBe(200);
    latest = await response.json();
    return latest.status;
  }, { timeout: 45_000, intervals: [100, 250, 500] }).toBe("success");
  return latest;
}

function providerEvents() {
  expect(EVIDENCE, "runner must export W18_EVIDENCE_DIR").toBeTruthy();
  return fs.readdirSync(EVIDENCE).filter((name) => /^provider-.*\.stdout\.log$/.test(name))
    .flatMap((name) => fs.readFileSync(path.join(EVIDENCE, name), "utf8").split(/\r?\n/))
    .filter((line) => line.startsWith("{"))
    .map((line) => { try { return JSON.parse(line); } catch { return null; } })
    .filter(Boolean);
}

async function saveEvidence(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

test("W15 frozen Skill UI toggles SDK Context for subsequent Runs", async ({ context, page }, testInfo) => {
  const me = await context.request.get(`${APP}/api/v1/auth/me`);
  expect(me.status()).toBe(200);
  expect((await me.json()).system_role).toBe("admin");

  const detail = await context.request.get(`${APP}/api/skills/${SKILL}`);
  expect(detail.status(), await detail.text()).toBe(200);
  const packageInfo = await detail.json();
  expect(packageInfo).toMatchObject({
    name: SKILL, source: "bytedance/deer-flow", license: "MIT",
    enabled: true, sha256: HASH,
  });

  await page.goto("/workspace/capabilities?tab=skills", { waitUntil: "domcontentloaded" });
  const toggle = page.getByRole("switch", { name: new RegExp(SKILL) });
  await expect(toggle).toBeVisible();
  await expect(toggle).toBeChecked();
  await page.screenshot({ path: testInfo.outputPath("w15-skill-enabled.png"), fullPage: true });

  const enabledMarker = `w15-skill-enabled-${Date.now()}`;
  const enabled = await sendChat(page, enabledMarker);
  await waitForSuccess(context.request, enabled);
  await expect(page.locator("#chat").getByText(`${enabledMarker} SKILL_ACTIVE_FROM_CONTEXT`, { exact: true })).toBeVisible();
  const enabledState = await context.request.get(`${APP}/api/langgraph/threads/${enabled.threadId}/state`);
  expect(enabledState.status()).toBe(200);
  const enabledMessages = (await enabledState.json()).values.messages.filter((message) => message.run_id === enabled.runId);
  const skillCall = enabledMessages.flatMap((message) => message.tool_calls ?? [])
    .find((call) => call.name === "skill_load");
  expect(skillCall, "real SDK Skill ToolCall must exist").toBeTruthy();
  expect(skillCall.args.skill).toBe(SKILL);
  const skillResult = enabledMessages.find((message) => message.type === "tool" && message.tool_call_id === skillCall.id);
  expect(skillResult, "real SDK Skill Tool result must exist").toBeTruthy();
  expect(skillResult.additional_kwargs.haifa_tool_result.activated).toBe(true);
  const enabledEvents = providerEvents().filter((event) => event.type === "w15_skill_context" && event.marker === enabledMarker);
  expect(enabledEvents.map((event) => event.action)).toEqual(["skill_load", "answer"]);
  expect(enabledEvents.map((event) => event.skillBodyVisible)).toEqual([false, true]);
  expect(enabledEvents.every((event) => event.hasSkillTool)).toBe(true);

  await page.goto("/workspace/capabilities?tab=skills", { waitUntil: "domcontentloaded" });
  const refreshedToggle = page.getByRole("switch", { name: new RegExp(SKILL) });
  await expect(refreshedToggle).toBeChecked();
  const update = page.waitForResponse((response) => response.request().method() === "PUT" &&
    new URL(response.url()).pathname.endsWith(`/api/skills/${SKILL}`), { timeout: 15_000 });
  await refreshedToggle.click();
  const updated = await update;
  expect(updated.status(), await updated.text()).toBe(200);
  expect((await updated.json()).enabled).toBe(false);
  await expect(refreshedToggle).not.toBeChecked();
  await page.screenshot({ path: testInfo.outputPath("w15-skill-disabled.png"), fullPage: true });

  const reload = await context.request.post(`${APP}/api/skills/reload`);
  expect(reload.status(), await reload.text()).toBe(200);
  expect(await reload.json()).toMatchObject({ success: true, scope: "process" });
  const disabledMarker = `w15-skill-disabled-${Date.now()}`;
  const disabled = await sendChat(page, disabledMarker);
  await waitForSuccess(context.request, disabled);
  await expect(page.locator("#chat").getByText(`${disabledMarker} SKILL_DISABLED_NO_TOOL`, { exact: true })).toBeVisible();
  const disabledState = await context.request.get(`${APP}/api/langgraph/threads/${disabled.threadId}/state`);
  expect(disabledState.status()).toBe(200);
  const disabledMessages = (await disabledState.json()).values.messages.filter((message) => message.run_id === disabled.runId);
  expect(disabledMessages.flatMap((message) => message.tool_calls ?? []).some((call) => call.name === "skill_load")).toBe(false);
  const disabledEvents = providerEvents().filter((event) => event.type === "w15_skill_context" && event.marker === disabledMarker);
  expect(disabledEvents).toHaveLength(1);
  expect(disabledEvents[0]).toMatchObject({ action: "answer", hasSkillTool: false, skillBodyVisible: false });

  await saveEvidence(testInfo, "w15-skill-ui-run-context", {
    source: packageInfo.source, license: packageInfo.license, sha256: packageInfo.sha256,
    enabledRun: enabled, skillToolCallId: skillCall.id, enabledEvents,
    disabledRun: disabled, disabledEvents, reload: true,
  });
});
