// UI golden sample: production write_file, approval, Artifact bytes and reload/reconnect.
const crypto = require("node:crypto");
const fs = require("node:fs");
const { expect, test } = require("./fixtures.cjs");

function authoritativeToolProjection(state, expectedPath) {
  const messages = state?.values?.messages ?? [];
  const request = messages.find((message) =>
    message?.tool_calls?.some((call) => call.name === "write_file"),
  );
  expect(
    request,
    "state must contain the authoritative write_file request",
  ).toBeTruthy();
  const call = request.tool_calls.find(
    (candidate) => candidate.name === "write_file",
  );
  expect(call.args.path).toBe(expectedPath);
  const result = messages.find(
    (message) =>
      message?.type === "tool" &&
      message?.name === "write_file" &&
      message?.tool_call_id === call.id,
  );
  expect(
    result,
    "state must contain the matching write_file result",
  ).toBeTruthy();
  return {
    call,
    result,
    structured: result.additional_kwargs?.haifa_tool_result,
  };
}

test("production write_file approval publishes one real Artifact and survives reload/reconnect", async ({
  context,
  page,
  runtime,
  workspace,
  runFlow,
  approval,
  evidence,
}, testInfo) => {
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

  const { input } = await workspace.open();
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
  const identity = runFlow.identity(firstStream);

  const { approve } = await approval.current();
  await expect(page.getByTestId("artifact-trigger")).toHaveCount(0);

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
  await approve();
  const resumedStream = await resumedStreamPromise;
  expect(resumedStream.status(), await resumedStream.text()).toBe(200);
  expect(runFlow.identity(resumedStream)).toEqual(identity);
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
  const terminal = await runFlow.poll(context.request, identity, "success");
  await expect(
    page.locator("#chat").getByText(artifactPath, { exact: true }),
  ).toBeVisible();
  await expect(page.getByTestId("artifact-trigger")).toBeVisible();

  const stateResponse = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${identity.threadId}/state`,
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
  await expect(
    page.locator("#chat").getByText(artifactPath, { exact: true }),
  ).toBeVisible();
  const restoredCard = page.getByTestId("human-input-card");
  await expect(restoredCard).toHaveCount(1);
  await expect(restoredCard).toBeVisible();
  await expect(
    restoredCard.getByRole("button", { name: "Approve", exact: true }),
  ).toBeDisabled();
  const reloadedStateResponse = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${identity.threadId}/state`,
  );
  expect(
    reloadedStateResponse.status(),
    await reloadedStateResponse.text(),
  ).toBe(200);
  const reloadedState = await reloadedStateResponse.json();
  const reloadedCards = reloadedState.values.messages.filter(
    (message) => message?.name === "ask_clarification",
  );
  const reloadedResults = reloadedState.values.messages.filter(
    (message) => message?.type === "tool" && message?.name === "write_file",
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
  await expect(
    reconnected.getByText(`${prompt}.md`, { exact: true }),
  ).toBeVisible();
  const downloadPromise = reconnected.waitForEvent("download");
  await reconnected
    .getByRole("link", { name: "Download", exact: true })
    .click();
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
  await evidence.bytes("w05-demo-artifact.download.md", downloadedBytes);

  const replayed = await context.request.get(
    `${runtime.app}/api/langgraph/threads/${identity.threadId}/state`,
  );
  const replayedState = await replayed.json();
  expect(replayedState.values.artifacts).toEqual([artifactPath]);
  const replayedProjection = authoritativeToolProjection(
    replayedState,
    artifactPath,
  );
  expect(replayedProjection.call.id).toBe(call.id);
  expect(replayedProjection.structured.artifactId).toBe(structured.artifactId);

  await evidence.screenshot(reconnected, "w05-demo-artifact");
  await evidence.json("w05-demo-artifact", {
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
