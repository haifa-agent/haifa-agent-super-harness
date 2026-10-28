// Real frozen UI/BFF/Skill Runtime subset. No office identity or document result is mocked.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { test, expect } = require("./fixtures.cjs");

test("W20 Lark capability center loads the official lark-doc package through Haifa", async ({ context, page, runtime, runFlow, evidence }) => {
  test.setTimeout(120_000);
  const seedFile = process.env.W20_LARK_SEED_MANIFEST;
  expect(seedFile, "invoke the existing runner with -W20Lark and explicit prepared seed inputs").toBeTruthy();
  const seed = JSON.parse(fs.readFileSync(seedFile, "utf8"));
  expect(seed.skills).toHaveLength(27);
  expect(new Set(seed.skills).size).toBe(27);
  await context.addCookies([{ name: "locale", value: "en-US", url: runtime.app }]);
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/workspace/capabilities", { waitUntil: "domcontentloaded" });
  const statusResponse = page.waitForResponse((response) => response.request().method() === "GET" &&
    new URL(response.url()).pathname === "/api/integrations/lark/status");
  await page.getByRole("button", { name: "Configure Lark / Feishu", exact: true }).click();
  const response = await statusResponse;
  expect(response.status(), await response.text()).toBe(200);
  const status = await response.json();
  expect(status).toMatchObject({ installed: true, version: seed.version, manifest_version: seed.version,
    app_configured: false, skills_expected: 27, skills_installed: 27,
    auth: { verified: false } });
  expect(status.installed_skills.slice().sort()).toEqual(seed.skills.slice().sort());
  expect(["not_configured", "unavailable"]).toContain(status.auth.status);
  expect(status.cli.available).toEqual(typeof status.cli.path === "string" && status.cli.path.length > 0);
  expect(["none", "gateway-download", "init-container", "broker"]).toContain(status.sandbox_runtime_mode);
  expect(typeof status.sandbox_runtime_ready).toBe("boolean");
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("27/27 skills installed", { exact: true })).toBeVisible();
  await expect(dialog.getByText(`Installed: ${seed.version}`, { exact: true })).toBeVisible();
  await expect(dialog.getByText("Not connected", { exact: true }).first()).toBeVisible();
  await evidence.json("lark-status", status);
  await evidence.screenshot(page, "lark-capability-status");
  await page.keyboard.press("Escape");

  await page.goto("/workspace/capabilities?tab=skills", { waitUntil: "domcontentloaded" });
  await page.getByRole("tab", { name: "All skills", exact: true }).click();
  const toggle = page.getByRole("switch", { name: /lark-doc/ });
  await expect(toggle).toBeVisible();
  await expect(toggle).not.toBeChecked(); // Official lark-doc contains scripts and needs explicit review.
  const enabledResponse = page.waitForResponse((item) => item.request().method() === "PUT" &&
    new URL(item.url()).pathname === "/api/skills/lark-doc", { timeout: 45_000 });
  await toggle.click();
  const enabled = await enabledResponse;
  expect(enabled.status(), await enabled.text()).toBe(200);
  expect((await enabled.json()).enabled).toBe(true);
  await expect(toggle).toBeChecked();
  await evidence.screenshot(page, "lark-doc-enabled");
  const reloaded = await context.request.post(`${runtime.app}/api/skills/reload`);
  expect(reloaded.status(), await reloaded.text()).toBe(200);
  const detail = await context.request.get(`${runtime.app}/api/skills/lark-doc`);
  expect(detail.status(), await detail.text()).toBe(200);
  const metadata = await detail.json();
  expect(metadata).toMatchObject({ name: "lark-doc", source: "integration-managed", enabled: true });

  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const marker = `w20-lark-load-${Date.now()}`;
  const stream = page.waitForResponse((item) => item.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(item.url()).pathname), { timeout: 30_000 });
  await input.fill(`${marker}: load the installed lark-doc skill and read its official fetch reference only. Do not access an office account or document.`);
  await input.press("Enter");
  const runResponse = await stream;
  await runFlow.expectStreamSuccess(runResponse);
  const identity = runFlow.identity(runResponse);
  await runFlow.poll(context.request, identity, "success", true);
  await expect(page.locator("#chat").getByText(`${marker} OFFICIAL_LARK_DOC_LOADED`, { exact: true })).toBeVisible();
  const stateResponse = await context.request.get(`${runtime.app}/api/langgraph/threads/${identity.threadId}/state`);
  expect(stateResponse.status(), await stateResponse.text()).toBe(200);
  const messages = (await stateResponse.json()).values.messages.filter((message) => message.run_id === identity.runId);
  const calls = messages.flatMap((message) => message.tool_calls ?? []);
  const loadCall = calls.find((call) => call.name === "skill_load" && call.args?.skill === "lark-doc");
  const readCall = calls.find((call) => call.name === "skill_resource_read" &&
    call.args?.skill === "lark-doc" && call.args?.path === "references/lark-doc-fetch.md");
  expect(loadCall, "real SDK skill_load call").toBeTruthy();
  expect(readCall, "real SDK resource reader call").toBeTruthy();
  const loadResult = messages.find((message) => message.type === "tool" && message.tool_call_id === loadCall.id);
  const readResult = messages.find((message) => message.type === "tool" && message.tool_call_id === readCall.id);
  expect(loadResult.additional_kwargs.haifa_tool_result.activated).toBe(true);
  const content = readResult.additional_kwargs.haifa_tool_result.content;
  expect(crypto.createHash("sha256").update(content, "utf8").digest("hex")).toBe(seed.referenceSha256);
  expect(content).toBe(fs.readFileSync(path.join(seed.packageDirectory, seed.reference), "utf8"));
  expect(calls.some((call) => call.name === "execution_run")).toBe(false);
  await evidence.json("lark-skill-run", { ...identity, metadata, loadCall, readCall,
    activated: true, referenceSha256: seed.referenceSha256,
    officeOAuth: "BLOCKED_EXTERNAL", officeDocumentRun: "BLOCKED_EXTERNAL",
    boundary: "official package load/reference read only; no CLI execution or office OAuth" });
  await evidence.screenshot(page, "lark-doc-loaded");
  expect(errors).toEqual([]);
});
