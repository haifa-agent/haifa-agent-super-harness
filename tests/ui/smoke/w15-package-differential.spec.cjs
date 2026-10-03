// Identical frozen UI and REST assertions against each real backend; no Run/SDK equivalence claim.
const path = require("node:path");
const fs = require("node:fs");
const { execFileSync } = require("node:child_process");
const { expect, test } = require(path.resolve(__dirname, "../../../frontend/node_modules/@playwright/test"));
const APP = process.env.E2E_APP_URL;

test("W15 frozen package management contract matches both backends", async ({ context, page }, testInfo) => {
  page.setDefaultTimeout(20_000);
  page.setDefaultNavigationTimeout(30_000);
  const fixture = path.resolve(__dirname, "../../fixtures/w15/package_archive.py");
  const name = "w15-package-ui";
  const prefix = `${APP}/api/skills/custom/${name}`;
  const archive = execFileSync("python", ["-B", fixture], { timeout: 10_000 });
  await page.goto("/workspace/capabilities?tab=skills", { waitUntil: "domcontentloaded" });
  const ready = page.waitForResponse(response => response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/skills/install/upload", { timeout: 45_000 });
  await page.locator('input[type="file"][accept=".skill"]').setInputFiles({ name: `${name}.skill`, mimeType: "application/zip", buffer: archive });
  const upload = await ready;
  expect(upload.status(), await upload.text()).toBe(200);
  const toggle = page.getByRole("switch", { name: new RegExp(name) });
  await expect(toggle).toBeChecked();
  await toggle.click();
  await expect(toggle).not.toBeChecked();
  await expect.poll(async () => (await (await context.request.get(`${APP}/api/skills/${name}`)).json()).enabled).toBe(false);
  await toggle.click();
  await expect(toggle).toBeChecked();
  await expect.poll(async () => (await (await context.request.get(`${APP}/api/skills/${name}`)).json()).enabled).toBe(true);
  await page.getByRole("button", { name: new RegExp(`^(View details|查看详情) ${name}$`) }).click();
  await page.getByRole("dialog").getByRole("button", { name: /^(Export|导出)$/ }).click();
  const dialog = page.getByRole("dialog");
  const manifestResponse = await context.request.get(`${prefix}/export-manifest`);
  let bytes;
  let manifest;
  if (process.env.W15_DIFFERENTIAL_BACKEND === "reference" && process.platform === "win32") {
    // The frozen backend explicitly requires POSIX O_NOFOLLOW/dir-fd support for export.
    expect(manifestResponse.status(), await manifestResponse.text()).toBe(422);
    const failure = await manifestResponse.json();
    expect(failure.detail.code).toBe("skill_export_unsupported");
    expect(failure.detail.message).toBe("This platform cannot safely capture skill files.");
    await expect(dialog.getByRole("alert")).toContainText(/cannot be exported|无法导出/i);
    bytes = { status: "BLOCKED_PLATFORM", code: failure.detail.code };
  } else {
    expect(manifestResponse.status(), await manifestResponse.text()).toBe(200);
    manifest = await manifestResponse.json();
    await dialog.locator("summary").click();
    await expect(dialog.getByText("references/fact.txt", { exact: true })).toBeVisible();
    const downloading = page.waitForEvent("download", { timeout: 15_000 });
    await dialog.getByRole("button", { name: /Download \.skill|下载 \.skill/ }).click();
    const download = await downloading;
    const exported = testInfo.outputPath("complete-export.skill");
    await download.saveAs(exported);
    bytes = JSON.parse(execFileSync("python", ["-B", fixture, "--verify", exported], { timeout: 10_000, encoding: "utf8" }));
    expect(bytes.status).toBe("PASS");
  }
  const original = await (await context.request.get(prefix)).json();
  const edited = await context.request.put(prefix, { data: { content: original.content.replace("Read references/fact.txt", "First read references/fact.txt") } });
  expect(edited.status(), await edited.text()).toBe(200);
  if (manifest) {
    const stale = await context.request.get(`${prefix}/export?expected_revision=${manifest.revision}`);
    expect(stale.status(), await stale.text()).toBe(409);
  }
  const rollback = await context.request.post(`${prefix}/rollback`, { data: { history_index: -1 } });
  expect(rollback.status(), await rollback.text()).toBe(200);
  expect((await rollback.json()).content).toBe(original.content);
  const history = await (await context.request.get(`${prefix}/history`)).json();
  expect(history.history.some(row => row.action === "rollback")).toBe(true);
  const deleted = await context.request.delete(prefix);
  expect(deleted.status(), await deleted.text()).toBe(200);
  expect((await context.request.get(prefix)).status()).toBe(404);
  const evidence = { backend: process.env.W15_DIFFERENTIAL_BACKEND, upload: 200, toggle: [false, true],
    completeRoundTrip: bytes, edit: 200, staleRevision: manifest ? 409 : "BLOCKED_PLATFORM", rollback: 200, delete: 200, missing: 404 };
  const output = testInfo.outputPath("package-differential.json");
  fs.writeFileSync(output, JSON.stringify(evidence, null, 2));
  await testInfo.attach("package-differential", { path: output, contentType: "application/json" });
  await page.screenshot({ path: testInfo.outputPath("package-differential.png"), fullPage: true });
});
