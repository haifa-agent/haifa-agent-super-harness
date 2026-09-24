const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "43018"}`;
const DATA_ROOT = process.env.W04_DATA_ROOT;
const MODEL_NAME = "w04-controlled";

if (!DATA_ROOT || !path.isAbsolute(DATA_ROOT)) {
  throw new Error("W04_DATA_ROOT must be an absolute path");
}

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2] };
}

async function pollRun(request, threadId, runId) {
  let latest;
  await expect
    .poll(
      async () => {
        const response = await request.get(
          `${APP}/api/langgraph/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}`,
        );
        expect(response.status(), await response.text()).toBe(200);
        latest = await response.json();
        return latest.status;
      },
      { timeout: 30_000, intervals: [50, 100, 250, 500] },
    )
    .toBe("success");
  return latest;
}

async function workspaceChanges(request, threadId, runId) {
  let latest;
  await expect
    .poll(
      async () => {
        const response = await request.get(
          `${APP}/api/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}/workspace-changes?include_files=true&include_diff=true`,
        );
        expect(response.status(), await response.text()).toBe(200);
        latest = await response.json();
        return latest.available === true ? latest.summary?.created : -1;
      },
      { timeout: 30_000, intervals: [50, 100, 250, 500] },
    )
    .toBe(1);
  return latest;
}

async function userDataRoot(threadId) {
  const workspaces = path.join(DATA_ROOT, "workspace", "workspaces");
  let resolved;
  await expect
    .poll(
      () => {
        if (!fs.existsSync(workspaces)) return null;
        for (const entry of fs.readdirSync(workspaces, { withFileTypes: true })) {
          if (!entry.isDirectory()) continue;
          const workspace = path.join(workspaces, entry.name);
          const binding = path.join(workspace, "binding.properties");
          if (!fs.existsSync(binding)) continue;
          const metadata = fs.readFileSync(binding, "utf8");
          if (metadata.split(/\r?\n/).includes(`threadId=${threadId}`)) {
            resolved = path.join(workspace, "user-data");
            return resolved;
          }
        }
        return null;
      },
      { timeout: 10_000, intervals: [25, 50, 100] },
    )
    .not.toBeNull();
  return resolved;
}

async function saveEvidence(testInfo, value) {
  const file = testInfo.outputPath("w04-real-ui.json");
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach("w04-real-ui.json", {
    path: file,
    contentType: "application/json",
  });
}

test("frozen UI closes files, Project documents, and workspace changes against Haifa", async ({
  context,
  page,
}, testInfo) => {
  const suffix = `${Date.now()}-${testInfo.repeatEachIndex}`;
  const projectName = `W04 UI ${suffix}`;
  const threadId = `w04-ui-${suffix}`;
  const threadTitle = `W04 project chat ${suffix}`;
  const projectFilename = `project-${suffix}.txt`;
  const projectBytes = `project document ${suffix}\n`;
  const uploadFilename = `upload-${suffix}`;
  const uploadBytes = `extensionless upload ${suffix}\n`;

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

  const threadResponse = await context.request.post(
    `${APP}/api/langgraph/threads`,
    {
      data: {
        thread_id: threadId,
        project_id: project.id,
        metadata: { title: threadTitle },
      },
    },
  );
  expect(threadResponse.status(), await threadResponse.text()).toBe(200);

  await page.goto(`/workspace/projects/${project.id}`, {
    waitUntil: "domcontentloaded",
  });
  await page.getByRole("tab", { name: "Documents", exact: true }).click();
  await expect(page.getByTestId("project-documents-shelf")).toBeVisible();
  const projectUploadPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents`,
  );
  await page.getByTestId("project-documents-upload-input").setInputFiles({
    name: projectFilename,
    mimeType: "text/plain",
    buffer: Buffer.from(projectBytes),
  });
  const projectUpload = await projectUploadPromise;
  expect(projectUpload.status(), await projectUpload.text()).toBe(201);
  const uploadedDocument = await projectUpload.json();
  await expect(page.getByText(projectFilename, { exact: true })).toBeVisible();

  const previewResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "GET" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents/${uploadedDocument.document.id}/content`,
  );
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  const previewResponse = await previewResponsePromise;
  expect(previewResponse.status()).toBe(206);
  expect(previewResponse.request().headers().range).toBe("bytes=0-1048575");
  await expect(page.getByRole("dialog").getByText(projectBytes.trim())).toBeVisible();
  await page.keyboard.press("Escape");

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download", exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe(projectFilename);

  await page.getByRole("button", { name: "Attach to chat", exact: true }).click();
  const attachResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname ===
        `/api/projects/${project.id}/documents/${uploadedDocument.document.id}/attach-to-thread/${threadId}`,
    { timeout: 20_000 },
  );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: threadTitle, exact: true })
    .click({ timeout: 20_000 });
  const attachResponse = await attachResponsePromise;
  expect(attachResponse.status(), await attachResponse.text()).toBe(200);
  await expect(page).toHaveURL(new RegExp(`/workspace/chats/${threadId}$`));
  await expect(page.getByTestId("project-attachment-chip")).toContainText(
    projectFilename,
  );

  const uploadResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === `/api/threads/${threadId}/uploads`,
  );
  const runResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      ),
  );
  await page.getByLabel("Upload files").setInputFiles({
    name: uploadFilename,
    mimeType: "text/plain",
    buffer: Buffer.from(uploadBytes),
  });
  const input = page.locator('textarea[name="message"]');
  await input.fill(`use both files ${suffix}`);
  await input.press("Enter");
  const uploadResponse = await uploadResponsePromise;
  expect(uploadResponse.status(), await uploadResponse.text()).toBe(200);
  const upload = await uploadResponse.json();
  expect(upload.files[0].filename).toBe(uploadFilename);
  const uploadRunResponse = await runResponsePromise;
  expect(uploadRunResponse.status(), await uploadRunResponse.text()).toBe(200);
  const uploadRun = runIdentity(uploadRunResponse);
  await pollRun(context.request, uploadRun.threadId, uploadRun.runId);
  await expect(
    page.getByText(`Controlled provider: use both files ${suffix}`, {
      exact: true,
    }),
  ).toBeVisible();

  const artifactUrl = `${APP}${upload.files[0].artifact_url}`;
  const range = await context.request.get(artifactUrl, {
    headers: { Range: "bytes=0-8" },
  });
  expect(range.status(), await range.text()).toBe(206);
  expect(range.headers()["content-range"]).toBe(
    `bytes 0-8/${Buffer.byteLength(uploadBytes)}`,
  );
  const uploadSha = crypto
    .createHash("sha256")
    .update(Buffer.from(uploadBytes))
    .digest("hex");
  expect(range.headers().etag).toBe(`"${uploadSha}"`);
  const downloaded = await context.request.get(`${artifactUrl}?download=true`);
  expect(downloaded.status(), await downloaded.text()).toBe(200);
  expect(downloaded.headers()["content-disposition"]).toContain("attachment");

  const changesStreamPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      ),
  );
  await input.fill("__slow__");
  await input.press("Enter");
  const changesStream = await changesStreamPromise;
  if (changesStream.status() !== 200) {
    throw new Error(
      `workspace-change run stream returned ${changesStream.status()}: ${await changesStream.text()}`,
    );
  }
  const changeRun = runIdentity(changesStream);
  const userData = await userDataRoot(threadId);
  const outputFile = path.join(userData, "outputs", `ui-report-${suffix}.md`);
  fs.writeFileSync(outputFile, `# W04 ${suffix}\n\nReady\n`, "utf8");
  await changesStream.finished();
  await pollRun(context.request, changeRun.threadId, changeRun.runId);
  const changes = await workspaceChanges(
    context.request,
    changeRun.threadId,
    changeRun.runId,
  );
  expect(changes.files).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        path: `/mnt/user-data/outputs/ui-report-${suffix}.md`,
        status: "created",
        sensitive: false,
      }),
    ]),
  );

  await expect(page.getByText("Edited 1 file")).toBeVisible();
  await page.getByRole("button", { name: "View changes" }).click();
  const changesDialog = page.getByRole("dialog", { name: /workspace changes/i });
  await expect(changesDialog).toBeVisible();
  await expect(
    changesDialog.getByText(`/mnt/user-data/outputs/ui-report-${suffix}.md`, {
      exact: true,
    }),
  ).toBeVisible();
  await expect(changesDialog.getByText("+Ready", { exact: true })).toBeVisible();

  const screenshot = testInfo.outputPath("w04-real-ui.png");
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("w04-real-ui.png", {
    path: screenshot,
    contentType: "image/png",
  });
  await saveEvidence(testInfo, {
    repeatEachIndex: testInfo.repeatEachIndex,
    projectId: project.id,
    threadId,
    projectDocumentId: uploadedDocument.document.id,
    projectPreviewStatus: previewResponse.status(),
    projectDownloadName: download.suggestedFilename(),
    uploadRun,
    artifactRange: range.headers()["content-range"],
    changeRun,
    workspaceSummary: changes.summary,
    workspacePath: changes.files[0]?.path,
    model: MODEL_NAME,
  });
});
