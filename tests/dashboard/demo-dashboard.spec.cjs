const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { test, expect } = require(path.resolve(__dirname, "../../frontend/node_modules/@playwright/test"));

const dashboardUrl = pathToFileURL(path.resolve(__dirname, "../../dashboard.html")).href;

test("本地 Dashboard 以客户演示为首页，三页签与工作包跳转可往返", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(dashboardUrl);

  await expect(page.getByRole("tab", { name: "客户演示交付" })).toHaveAttribute("aria-selected", "true");
  await expect(page.locator("#demoDecision")).toContainText("演示决策：GO");
  await expect(page.locator(".demo-card")).toHaveCount(5);
  await expect(page.locator(".demo-gate.good")).toHaveCount(5);
  await expect(page.locator("#demoCounts")).toContainText("5/5 PASS");
  await expect(page.locator("#demoStages")).toContainText("M0");
  await expect(page.locator("#demoStages")).toContainText("PASS");
  await expect(page.locator("#demoStages")).toContainText("M3");
  await expect(page.locator("#demoStages")).toContainText("M4");

  await page.getByRole("tab", { name: "最近工作安排" }).click();
  await expect(page.locator("#planView")).toBeVisible();
  await expect(page.locator("#planItems")).toContainText("暂无最近工作安排");
  await expect(page.locator("#planItems")).not.toContainText("主线 M3 双后端集成复验");
  await expect(page.locator("#planItems")).not.toContainText("W05 真实 Artifact 与冻结 UI 卡片");
  await expect(page.locator("#planItems")).not.toContainText("M0 冻结客户演示脚本与 doctor");

  await page.getByRole("tab", { name: "工作包总览" }).click();
  await expect(page.locator("#packageView")).toBeVisible();
  await expect(page.locator("#packages .package")).toHaveCount(22);

  await page.getByRole("tab", { name: "客户演示交付" }).click();
  await page.locator('[data-work-package="W05"]').first().click();
  await expect(page.getByRole("tab", { name: "工作包总览" })).toHaveAttribute("aria-selected", "true");
  await expect(page.locator("#search")).toHaveValue("W05");
  await expect(page.locator('#packages .package[data-id="W05"]')).toBeVisible();

  await page.getByRole("tab", { name: "客户演示交付" }).click();
  await expect(page.locator("#demoView")).toBeVisible();
  expect(errors).toEqual([]);
});

test("窄屏仍可从工作包总览返回客户演示", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 780 });
  await page.goto(dashboardUrl);
  await page.getByRole("tab", { name: "工作包总览" }).click();
  await expect(page.locator("#packageView")).toBeVisible();
  await page.getByRole("tab", { name: "客户演示交付" }).click();
  await expect(page.locator("#demoView")).toBeVisible();
  await expect(page.locator(".demo-card")).toHaveCount(5);
});
