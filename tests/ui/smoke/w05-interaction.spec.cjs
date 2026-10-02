const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "35618"}`;

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

async function saveEvidence(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, {
    path: file,
    contentType: "application/json",
  });
}

async function exerciseInteraction({ action, context, page }, testInfo) {
  const prompt = `w05-interaction-${action}-${Date.now()}`;
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
  const requestArtifact = await page.evaluate(() => {
    const entries = performance.getEntriesByType("resource");
    return entries.length;
  });
  const selectedButton = card.getByRole("button", {
    name: action === "approve" ? "Approve" : "Reject",
    exact: true,
  });
  await expect(selectedButton).toBeEnabled();

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
  await selectedButton.click();
  const resumedStream = await resumedStreamPromise;
  expect(resumedStream.status(), await resumedStream.text()).toBe(200);
  const resumedIdentity = runIdentity(resumedStream);
  expect(resumedIdentity).toEqual(identity);

  const hiddenMessage = hiddenRequest.input.messages.at(-1);
  const responseMetadata = hiddenMessage.additional_kwargs.human_input_response;
  expect(hiddenMessage.additional_kwargs.hide_from_ui).toBe(true);
  expect(responseMetadata).toMatchObject({
    version: 1,
    kind: "human_input_response",
    source: "haifa_interaction",
    response_kind: "option",
    option_id: action,
    value: action,
  });
  expect(typeof responseMetadata.request_id).toBe("string");
  expect(responseMetadata.request_id.length).toBeGreaterThan(0);
  expect(await resumedStream.text()).toContain("event: end");
  const terminal = await pollRun(context.request, identity, "success");

  await expect(selectedButton).toBeDisabled();
  if (action === "approve") {
    await expect(
      page.locator("#chat").getByText(`Interaction resolved: ${prompt}`, {
        exact: true,
      }),
    ).toBeVisible();
  }

  await page.reload({ waitUntil: "domcontentloaded" });
  const restoredCard = page.getByTestId("human-input-card");
  await expect(restoredCard).toBeVisible();
  await expect(
    restoredCard.getByRole("button", {
      name: action === "approve" ? "Approve" : "Reject",
      exact: true,
    }),
  ).toBeDisabled();

  const screenshot = testInfo.outputPath(`interaction-${action}.png`);
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach(`interaction-${action}.png`, {
    path: screenshot,
    contentType: "image/png",
  });
  await saveEvidence(testInfo, `interaction-${action}`, {
    prompt,
    action,
    ...identity,
    requestId: responseMetadata.request_id,
    firstStreamStatus: firstStream.status(),
    resumedStreamStatus: resumedStream.status(),
    hiddenResponseShape: responseMetadata,
    hiddenFromUi: hiddenMessage.additional_kwargs.hide_from_ui,
    terminalStatus: terminal.status,
    cardRestoredAnsweredAfterReload: true,
    resourceEntryCountAtCard: requestArtifact,
  });
}

test.describe("W05 frozen Interaction card acceptance profile", () => {
  test("approval resumes the same SDK Run and restores the answered card", async (
    { context, page },
    testInfo,
  ) => {
    await exerciseInteraction({ action: "approve", context, page }, testInfo);
  });

  test("rejection resumes the same SDK Run and restores the answered card", async (
    { context, page },
    testInfo,
  ) => {
    await exerciseInteraction({ action: "reject", context, page }, testInfo);
  });
});
