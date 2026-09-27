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
const DATA_ROOT = process.env.W04_DATA_ROOT;

if (!DATA_ROOT || !path.isAbsolute(DATA_ROOT)) {
  throw new Error("W04_DATA_ROOT must be an absolute path");
}

async function saveEvidence(testInfo, value) {
  const file = testInfo.outputPath("w04-archive-restore-delete-evidence.json");
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach("w04-archive-restore-delete-evidence.json", {
    path: file,
    contentType: "application/json",
  });
}

test("frozen UI project archive, restore, document trash, restore, purge, and cascade delete", async ({
  context,
  page,
}, testInfo) => {
  const suffix = `${Date.now()}-${testInfo.repeatEachIndex}`;
  const projectName = `W04 ARD ${suffix}`;
  const threadId = `w04-ard-th-${suffix}`;
  const docPurgeName = `doc-purge-${suffix}.txt`;
  const docPurgeBytes = `content to purge ${suffix}\n`;
  const docRestoreName = `doc-restore-${suffix}.txt`;
  const docRestoreBytes = `content to restore ${suffix}\n`;
  const docCascadeName = `doc-cascade-${suffix}.txt`;
  const docCascadeBytes = `content to cascade on project delete ${suffix}\n`;

  // 1. Create project through frozen UI
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  await expect(page.locator('textarea[name="message"]')).toBeVisible();
  await page.getByTestId("projects-new-project-button").click();
  const createDialog = page.getByRole("dialog");
  await createDialog.locator("input").fill(projectName);

  const projectResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/projects",
  );
  await createDialog.getByRole("button", { name: "Create", exact: true }).click();
  const projectResponse = await projectResponsePromise;
  expect(projectResponse.status(), await projectResponse.text()).toBe(201);
  const project = await projectResponse.json();
  expect(project.name).toBe(projectName);

  // 2. Associate a thread with the project to verify unbind on deletion
  const threadResponse = await context.request.post(
    `${APP}/api/langgraph/threads`,
    {
      data: {
        thread_id: threadId,
        project_id: project.id,
        metadata: { title: `Thread for ${projectName}` },
      },
    },
  );
  expect(threadResponse.status(), await threadResponse.text()).toBe(200);

  // 3. Open project page and switch to Documents tab
  await page.goto(`/workspace/projects/${project.id}`, {
    waitUntil: "domcontentloaded",
  });
  await expect(page.getByRole("heading", { name: projectName })).toBeVisible();

  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-shelf")).toBeVisible();

  // Upload doc 1 (will be trashed then permanently purged)
  const upload1Promise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents`,
  );
  await page.getByTestId("project-documents-upload-input").setInputFiles({
    name: docPurgeName,
    mimeType: "text/plain",
    buffer: Buffer.from(docPurgeBytes, "utf8"),
  });
  const upload1Resp = await upload1Promise;
  expect(upload1Resp.status(), await upload1Resp.text()).toBe(201);
  const docPurge = (await upload1Resp.json()).document;
  await expect(page.getByText(docPurgeName, { exact: true })).toBeVisible();

  // Upload doc 2 (will be trashed then restored back to shelf)
  const upload2Promise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents`,
  );
  await page.getByTestId("project-documents-upload-input").setInputFiles({
    name: docRestoreName,
    mimeType: "text/plain",
    buffer: Buffer.from(docRestoreBytes, "utf8"),
  });
  const upload2Resp = await upload2Promise;
  expect(upload2Resp.status(), await upload2Resp.text()).toBe(201);
  const docRestore = (await upload2Resp.json()).document;
  await expect(page.getByText(docRestoreName, { exact: true })).toBeVisible();

  // 4. Move doc 1 to trash via UI confirmation
  const rowPurge = page.locator("li").filter({ hasText: docPurgeName });
  await rowPurge.getByRole("button", { name: "Move to trash" }).click();
  const trashModal1 = page.getByRole("dialog");
  await expect(trashModal1.getByRole("button", { name: "Move to trash" })).toBeVisible();

  const deleteDoc1Promise = page.waitForResponse(
    (response) =>
      response.request().method() === "DELETE" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents/${docPurge.id}`,
  );
  await trashModal1.getByRole("button", { name: "Move to trash" }).click();
  const deleteDoc1Resp = await deleteDoc1Promise;
  expect(deleteDoc1Resp.status()).toBe(204);
  await expect(page.getByText(docPurgeName)).toHaveCount(0);

  // 5. Navigate to /workspace/trash, assert metadata, and permanently purge doc 1
  await page.goto("/workspace/trash", { waitUntil: "domcontentloaded" });
  const trashRowPurge = page.locator("li").filter({ hasText: docPurgeName });
  await expect(trashRowPurge).toBeVisible();
  await expect(trashRowPurge.getByText(new RegExp(`from ${projectName}`))).toBeVisible();

  await trashRowPurge.getByRole("button", { name: "Delete permanently" }).click();
  const purgeModal = page.getByRole("dialog");
  await expect(purgeModal.getByRole("button", { name: "Delete permanently" })).toBeVisible();

  const purgePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === `/api/trash/documents/${docPurge.id}/purge`,
  );
  await purgeModal.getByRole("button", { name: "Delete permanently" }).click();
  const purgeResp = await purgePromise;
  expect(purgeResp.status()).toBe(204);
  await expect(page.getByText(docPurgeName)).toHaveCount(0);

  // 6. Return to project, move doc 2 to trash, then restore it from trash
  await page.goto(`/workspace/projects/${project.id}`, {
    waitUntil: "domcontentloaded",
  });
  await expect(page.getByRole("heading", { name: projectName })).toBeVisible();
  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-shelf")).toBeVisible();

  const rowRestore = page.locator("li").filter({ hasText: docRestoreName });
  await rowRestore.getByRole("button", { name: "Move to trash" }).click();
  const trashModal2 = page.getByRole("dialog");
  await expect(trashModal2.getByRole("button", { name: "Move to trash" })).toBeVisible();

  const deleteDoc2Promise = page.waitForResponse(
    (response) =>
      response.request().method() === "DELETE" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents/${docRestore.id}`,
  );
  await trashModal2.getByRole("button", { name: "Move to trash" }).click();
  const deleteDoc2Resp = await deleteDoc2Promise;
  expect(deleteDoc2Resp.status()).toBe(204);
  await expect(page.getByText(docRestoreName)).toHaveCount(0);

  // Restore doc 2 from trash view
  await page.goto("/workspace/trash", { waitUntil: "domcontentloaded" });
  const trashRowRestore = page.locator("li").filter({ hasText: docRestoreName });
  await expect(trashRowRestore).toBeVisible();

  const restorePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname ===
        `/api/trash/documents/${docRestore.id}/restore`,
  );
  await trashRowRestore.getByRole("button", { name: "Restore" }).click();
  const restoreResp = await restorePromise;
  expect(restoreResp.status()).toBe(200);
  await expect(page.getByText(docRestoreName)).toHaveCount(0);

  // Verify doc 2 is back on the project shelf
  await page.goto(`/workspace/projects/${project.id}`, {
    waitUntil: "domcontentloaded",
  });
  await expect(page.getByRole("heading", { name: projectName })).toBeVisible();
  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-shelf")).toBeVisible();
  await expect(page.getByText(docRestoreName, { exact: true })).toBeVisible();

  // 7. Test Project Archive and Restore in Settings tab
  await page.getByRole("tab", { name: "Settings", exact: true }).click();

  const archivePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === `/api/projects/${project.id}/archive`,
  );
  await page.getByRole("button", { name: "Archive" }).click();
  const archiveResp = await archivePromise;
  expect(archiveResp.status()).toBe(200);
  await expect(page.getByRole("button", { name: "Restore" })).toBeVisible();

  // Verify archived banner in Documents tab
  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-archived-banner")).toBeVisible();

  // Reactivate project via Restore button in Settings tab
  await page.getByRole("tab", { name: "Settings", exact: true }).click();
  const restoreProjPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === `/api/projects/${project.id}/restore`,
  );
  await page.getByRole("button", { name: "Restore" }).click();
  const restoreProjResp = await restoreProjPromise;
  expect(restoreProjResp.status()).toBe(200);
  await expect(page.getByRole("button", { name: "Archive" })).toBeVisible();

  // Verify archived banner is gone
  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-archived-banner")).toHaveCount(0);

  // 8. Upload doc 3, then delete project (cascade documents to trash & unbind threads)
  const upload3Promise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents`,
  );
  await page.getByTestId("project-documents-upload-input").setInputFiles({
    name: docCascadeName,
    mimeType: "text/plain",
    buffer: Buffer.from(docCascadeBytes, "utf8"),
  });
  const upload3Resp = await upload3Promise;
  expect(upload3Resp.status()).toBe(201);
  const docCascade = (await upload3Resp.json()).document;
  await expect(page.getByText(docCascadeName, { exact: true })).toBeVisible();

  // Delete project via Settings tab
  await page.getByRole("tab", { name: "Settings", exact: true }).click();
  await page.getByRole("button", { name: "Delete project" }).click();
  const deleteProjModal = page.getByRole("dialog");
  await expect(deleteProjModal.getByRole("button", { name: "Delete" })).toBeVisible();

  const deleteProjPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "DELETE" &&
      new URL(response.url()).pathname === `/api/projects/${project.id}`,
  );
  await deleteProjModal.getByRole("button", { name: "Delete" }).click();
  const deleteProjResp = await deleteProjPromise;
  expect(deleteProjResp.status()).toBe(204);

  await page.waitForURL("**/workspace/chats**");
  const checkProj = await context.request.get(`${APP}/api/projects/${project.id}`);
  expect(checkProj.status()).toBe(404);

  // Verify thread metadata was cleanly unbound
  const checkThread = await context.request.get(`${APP}/api/langgraph/threads/${threadId}`);
  expect(checkThread.status()).toBe(200);
  const threadData = await checkThread.json();
  expect(threadData.metadata?.deerflow_project_id ?? null).toBeNull();

  // 9. Verify cascaded documents in trash and permanently empty trash
  await page.goto("/workspace/trash", { waitUntil: "domcontentloaded" });
  await expect(page.getByText(docCascadeName, { exact: true })).toBeVisible();
  await expect(page.getByText(docRestoreName, { exact: true })).toBeVisible();

  await page.getByTestId("trash-empty-button").click();
  const emptyModal = page.getByRole("dialog");
  await expect(emptyModal.getByRole("button", { name: "Empty trash" })).toBeVisible();

  const emptyPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/trash/purge",
  );
  await emptyModal.getByRole("button", { name: "Empty trash" }).click();
  const emptyResp = await emptyPromise;
  expect(emptyResp.status()).toBe(200);

  await expect(page.getByText(docCascadeName)).toHaveCount(0);
  await expect(page.getByText(docRestoreName)).toHaveCount(0);

  const finalTrash = await context.request.get(`${APP}/api/trash/documents`);
  expect((await finalTrash.json()).total).toBe(0);

  await saveEvidence(testInfo, {
    projectId: project.id,
    projectName,
    threadId,
    purgedDocument: { id: docPurge.id, name: docPurgeName },
    restoredDocument: { id: docRestore.id, name: docRestoreName },
    cascadedDocument: { id: docCascade.id, name: docCascadeName },
    finalTrashTotal: 0,
    timestamp: new Date().toISOString(),
  });
});
