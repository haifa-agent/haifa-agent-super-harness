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

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2], location };
}

async function pollRun(request, identity, expectedStatus) {
  let snapshot;
  await expect
    .poll(
      async () => {
        const response = await request.get(`${APP}${identity.location}`);
        expect(response.status(), await response.text()).toBe(200);
        snapshot = await response.json();
        return snapshot.status;
      },
      { timeout: 20_000, intervals: [50, 100, 250] },
    )
    .toBe(expectedStatus);
  return snapshot;
}

async function saveEvidence(testInfo, evidence) {
  const file = testInfo.outputPath("w05-demo-artifact.json");
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  await testInfo.attach("w05-demo-artifact.json", {
    path: file,
    contentType: "application/json",
  });
}

function authoritativeToolProjection(state, expectedPath) {
  const messages = state?.values?.messages ?? [];
  const request = messages.find((message) =>
    message?.tool_calls?.some((call) => call.name === "write_file"),
  );
  expect(request, "state must contain the authoritative write_file request").toBeTruthy();
  const call = request.tool_calls.find((candidate) => candidate.name === "write_file");
  expect(call.args.path).toBe(expectedPath);
  const result = messages.find(
    (message) =>
      message?.type === "tool" &&
      message?.name === "write_file" &&
      message?.tool_call_id === call.id,
  );
  expect(result, "state must contain the matching write_file result").toBeTruthy();
  return {
    call,
    result,
    structured: result.additional_kwargs?.haifa_tool_result,
  };
}

test("production write_file approval publishes one real Artifact and survives reload/reconnect", async (
  { context, page },
  testInfo,
) => {
  const prompt = `w18-file-tool-${Date.now()}`;
  const artifactPath = `/mnt/user-data/outputs/${prompt}.md`;
  const expectedBytes = Buffer.from(
    `W18 ${prompt} produced by write_file 数据 ✓`,
    "utf8",
  );
  const expectedSha256 = crypto
    .createHash("sha256")
    .update(expectedBytes)
    .digest("hex");

  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const firstStreamPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      ),
  );
  await input.fill(prompt);
  await input.press("Enter");
  const firstStream = await firstStreamPromise;
  expect(firstStream.status(), await firstStream.text()).toBe(200);
  const identity = runIdentity(firstStream);

  const card = page.getByTestId("human-input-card");
  await expect(card).toBeVisible();
  await expect(page.getByTestId("artifact-trigger")).toHaveCount(0);
  const approve = card.getByRole("button", { name: "Approve", exact: true });
  await expect(approve).toBeEnabled();

  let hiddenRequest;
  const resumedStreamPromise = page.waitForResponse((response) => {
    const request = response.request();
    if (
      request.method() !== "POST" ||
      !/\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      )
    ) {
      return false;
    }
    try {
      const candidate = request.postDataJSON();
      const message = candidate?.input?.messages?.at(-1);
      if (message?.additional_kwargs?.human_input_response) {
        hiddenRequest = candidate;
        return true;
      }
    } catch {
      return false;
    }
    return false;
  });
  await approve.click();
  const resumedStream = await resumedStreamPromise;
  expect(resumedStream.status(), await resumedStream.text()).toBe(200);
  expect(runIdentity(resumedStream)).toEqual(identity);
  const responseMetadata =
    hiddenRequest.input.messages.at(-1).additional_kwargs.human_input_response;
  expect(responseMetadata).toMatchObject({
    version: 1,
    kind: "human_input_response",
    source: "haifa_interaction",
    response_kind: "option",
    option_id: "approve",
    value: "approve",
  });
  const terminal = await pollRun(context.request, identity, "success");
  await expect(page.locator("#chat").getByText(artifactPath, { exact: true })).toBeVisible();
  await expect(page.getByTestId("artifact-trigger")).toBeVisible();

  const stateResponse = await context.request.get(
    `${APP}/api/langgraph/threads/${identity.threadId}/state`,
  );
  expect(stateResponse.status(), await stateResponse.text()).toBe(200);
  const state = await stateResponse.json();
  expect(state.values.artifacts).toEqual([artifactPath]);
  const { call, result, structured } = authoritativeToolProjection(
    state,
    artifactPath,
  );
  expect(structured).toMatchObject({
    path: artifactPath,
    sha256: expectedSha256,
    artifactSha256: `sha256:${expectedSha256}`,
    artifactByteCount: expectedBytes.length,
    artifactExecutionRef: call.id,
    runId: identity.runId,
    threadId: identity.threadId,
  });
  expect(structured.artifactId).toBeTruthy();
  expect(structured.artifactWorkspaceRef).toBeTruthy();
  expect(structured.artifactSessionId).toBeTruthy();

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.getByTestId("artifact-trigger")).toBeVisible();
  await expect(page.locator("#chat").getByText(artifactPath, { exact: true })).toBeVisible();
  const restoredCard = page.getByTestId("human-input-card");
  await expect(restoredCard).toHaveCount(1);
  await expect(restoredCard).toBeVisible();
  await expect(
    restoredCard.getByRole("button", { name: "Approve", exact: true }),
  ).toBeDisabled();
  const reloadedStateResponse = await context.request.get(
    `${APP}/api/langgraph/threads/${identity.threadId}/state`,
  );
  expect(reloadedStateResponse.status(), await reloadedStateResponse.text()).toBe(
    200,
  );
  const reloadedState = await reloadedStateResponse.json();
  const reloadedCards = reloadedState.values.messages.filter(
    (message) => message?.name === "ask_clarification",
  );
  const reloadedResults = reloadedState.values.messages.filter(
    (message) =>
      message?.type === "tool" && message?.name === "write_file",
  );
  expect(reloadedCards).toHaveLength(1);
  expect(reloadedResults).toHaveLength(1);
  expect(reloadedCards[0].tool_call_id).toBe(reloadedCards[0].id);
  expect(reloadedCards[0].tool_call_id).not.toBe(call.id);
  expect(reloadedCards[0].artifact.human_input.tool_call_id).toBe(call.id);
  expect(reloadedResults[0].tool_call_id).toBe(call.id);

  const reconnected = await context.newPage();
  await reconnected.goto(page.url(), { waitUntil: "domcontentloaded" });
  await expect(reconnected.getByTestId("artifact-trigger")).toBeVisible();
  await expect(
    reconnected.locator("#chat").getByText(artifactPath, { exact: true }),
  ).toBeVisible();

  await reconnected.getByTestId("artifact-trigger").click();
  await expect(reconnected.getByText(`${prompt}.md`, { exact: true })).toBeVisible();
  const downloadPromise = reconnected.waitForEvent("download");
  await reconnected.getByRole("link", { name: "Download", exact: true }).click();
  const download = await downloadPromise;
  const downloadPath = await download.path();
  expect(downloadPath).toBeTruthy();
  const downloadedBytes = fs.readFileSync(downloadPath);
  expect(downloadedBytes).toEqual(expectedBytes);
  const downloadSha256 = crypto
    .createHash("sha256")
    .update(downloadedBytes)
    .digest("hex");
  expect(downloadSha256).toBe(expectedSha256);

  const replayed = await context.request.get(
    `${APP}/api/langgraph/threads/${identity.threadId}/state`,
  );
  const replayedState = await replayed.json();
  expect(replayedState.values.artifacts).toEqual([artifactPath]);
  const replayedProjection = authoritativeToolProjection(
    replayedState,
    artifactPath,
  );
  expect(replayedProjection.call.id).toBe(call.id);
  expect(replayedProjection.structured.artifactId).toBe(structured.artifactId);

  const screenshot = testInfo.outputPath("w05-demo-artifact.png");
  await reconnected.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("w05-demo-artifact.png", {
    path: screenshot,
    contentType: "image/png",
  });
  await saveEvidence(testInfo, {
    prompt,
    artifactPath,
    expectedSize: expectedBytes.length,
    expectedSha256,
    downloadSize: downloadedBytes.length,
    downloadSha256,
    ...identity,
    interactionRequestId: responseMetadata.request_id,
    toolCallId: call.id,
    toolResultMessageId: result.id,
    artifactId: structured.artifactId,
    artifactVersion: structured.artifactVersion,
    artifactProjectRef: structured.artifactProjectRef,
    artifactWorkspaceRef: structured.artifactWorkspaceRef,
    artifactSessionId: structured.artifactSessionId,
    artifactExecutionRef: structured.artifactExecutionRef,
    terminalStatus: terminal.status,
    approvalRestoredDisabledAfterReload: true,
    artifactRestoredAfterReconnect: true,
    duplicateArtifactCount: replayedState.values.artifacts.length,
  });
});
