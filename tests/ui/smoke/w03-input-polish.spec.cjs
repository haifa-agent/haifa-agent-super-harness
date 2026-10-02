const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

// Drives the unmodified frozen frontend against the real Haifa gateway and the
// controlled loopback provider. It never mocks the page or the protocol: the
// polish button issues the real same-origin `POST /api/input-polish`, the gateway
// performs exactly one bounded non-streaming provider call, and the rewrite/undo/
// cancel state is the frozen `input-box.tsx` implementation. No Thread, Run, Tool,
// or Artifact may be created by polishing.
const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "25618"}`;
const PROVIDER = process.env.W18_PROVIDER_URL;
const POLISH_REWRITE =
  "Polished camera brief: buy a camera; state the goal, scope, constraints, and expected output.";
const POLISH_THINK_TEXT = "Polished camera brief from think.";
const POLISH_FENCE_TEXT = "Polished camera brief from fence.";
const PRIVATE_REASONING = "PRIVATE_REASONING_DO_NOT_LEAK";
const PROVIDER_SECRET = "PROVIDER_INTERNAL_SECRET_BODY";

function isPolishRequest(request) {
  return (
    request.method() === "POST" &&
    new URL(request.url()).pathname === "/api/input-polish"
  );
}

async function providerState(request) {
  expect(PROVIDER, "W18_PROVIDER_URL must be set by the runner").toBeTruthy();
  const response = await request.get(`${PROVIDER}/__state`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function pollRun(request, threadId, runId, expectedStatus) {
  const url = `${APP}/api/langgraph/threads/${encodeURIComponent(threadId)}/runs/${encodeURIComponent(runId)}`;
  await expect
    .poll(
      async () => {
        const response = await request.get(url);
        expect(response.status(), await response.text()).toBe(200);
        return (await response.json()).status;
      },
      { timeout: 30_000, intervals: [50, 100, 250] },
    )
    .toBe(expectedStatus);
}

async function saveEvidence(testInfo, name, evidence) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, {
    path: file,
    contentType: "application/json",
  });
}

function textarea(page) {
  return page.locator('textarea[name="message"]');
}

function polishButton(page) {
  return page.locator('[data-testid="polish-input-button"]');
}

async function runPolish(page) {
  const requestPromise = page.waitForRequest(isPolishRequest);
  const responsePromise = page.waitForResponse((response) =>
    isPolishRequest(response.request()),
  );
  await polishButton(page).click();
  const request = await requestPromise;
  const response = await responsePromise;
  return { request, response };
}

test.describe("W03 frozen UI input polish", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
    await expect(textarea(page)).toBeVisible();
  });

  test("polishes the frozen draft via the same-origin gateway without side effects", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_ok__ wana get a camra for my trip";
    const before = await providerState(context.request);

    let runStreamRequests = 0;
    page.on("request", (request) => {
      if (
        request.method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(request.url()).pathname,
        )
      ) {
        runStreamRequests += 1;
      }
    });

    await textarea(page).fill(draft);
    const { request, response } = await runPolish(page);
    expect(response.status(), await response.text()).toBe(200);
    const body = await response.json();
    expect(body.rewritten_text).toBe(POLISH_REWRITE);
    expect(body.changed).toBe(true);
    expect(response.url()).toBe(`${APP}/api/input-polish`);

    // Exact frozen wire contract: only text/locale/thread_id, no product or
    // reasoning fields (the gateway rejects any unknown field with 422).
    const sent = request.postDataJSON();
    expect(sent).toEqual({
      text: draft,
      locale: "en-US",
      thread_id: expect.any(String),
    });
    expect(Object.keys(sent).sort()).toEqual(["locale", "text", "thread_id"]);

    await expect(textarea(page)).toHaveValue(POLISH_REWRITE);
    await expect(polishButton(page)).toHaveAttribute("aria-label", "Undo polish");

    await expect
      .poll(async () => (await providerState(context.request)).polishCalls.length)
      .toBe(before.polishCalls.length + 1);
    const after = await providerState(context.request);
    const call = after.polishCalls[after.polishCalls.length - 1];
    expect(call.draft).toBe(draft);
    expect(call.locale).toBe("en-US");
    expect(call.reasoningEffort).toBeNull();
    expect(call.reasoningEffortPresent).toBe(false);
    expect(call.stream).toBe(false);
    expect(call.offeredTools).toEqual([]);
    expect(after.chatCalls.length).toBe(before.chatCalls.length);

    // Polishing created no Thread/Run/Tool/Artifact facts.
    const threadId = sent.thread_id;
    const threadResponse = await context.request.get(
      `${APP}/api/langgraph/threads/${threadId}`,
    );
    expect(threadResponse.status()).toBe(404);
    const runsResponse = await context.request.get(
      `${APP}/api/langgraph/threads/${threadId}/runs`,
    );
    expect(runsResponse.status()).toBe(404);
    expect(runStreamRequests).toBe(0);

    // Explicit success screenshot while the rewritten draft is displayed and the
    // button is in its Undo state, before undo restores the original draft.
    const screenshot = testInfo.outputPath("w03-input-polish-happy.png");
    await page.screenshot({ path: screenshot, fullPage: true });
    await testInfo.attach("w03-input-polish-happy.png", {
      path: screenshot,
      contentType: "image/png",
    });

    // Undo restores the exact original draft.
    await polishButton(page).click();
    await expect(textarea(page)).toHaveValue(draft);
    await expect(polishButton(page)).toHaveAttribute("aria-label", "Polish input");

    await saveEvidence(testInfo, "w03-input-polish-happy", {
      draft,
      threadId,
      rewrite: POLISH_REWRITE,
      requestBody: sent,
      responseStatus: response.status(),
      providerCall: call,
      threadStatus: threadResponse.status(),
      runsStatus: runsResponse.status(),
      runStreamRequests,
      providerChatCalls: after.chatCalls.length - before.chatCalls.length,
    });
  });

  test("after polishing, sending submits only the current edit-box text", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_ok__ wana get a camra";
    await textarea(page).fill(draft);
    const polish = await runPolish(page);
    expect(polish.response.status(), await polish.response.text()).toBe(200);
    await expect(textarea(page)).toHaveValue(POLISH_REWRITE);

    const chatBefore = (await providerState(context.request)).chatCalls.length;
    const streamPromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(response.url()).pathname,
        ),
    );
    await textarea(page).press("Enter");
    const streamResponse = await streamPromise;
    expect(streamResponse.status(), await streamResponse.text()).toBe(200);
    const location = streamResponse.headers()["content-location"];
    const identity = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
    expect(identity, `unexpected run location: ${location}`).toBeTruthy();
    const [, threadId, runId] = identity;
    await pollRun(context.request, threadId, runId, "success");

    const sentBody = JSON.stringify(streamResponse.request().postDataJSON());
    expect(sentBody).toContain(POLISH_REWRITE);
    expect(sentBody).not.toContain(draft);

    await expect
      .poll(async () => (await providerState(context.request)).chatCalls.length)
      .toBeGreaterThan(chatBefore);
    const chatCalls = (await providerState(context.request)).chatCalls;
    const prompts = chatCalls.map((entry) => entry.prompt);
    expect(prompts).toContain(POLISH_REWRITE);
    expect(prompts.some((prompt) => prompt.includes("__polish_ok__"))).toBe(false);

    await saveEvidence(testInfo, "w03-input-polish-send", {
      draft,
      rewrite: POLISH_REWRITE,
      threadId,
      runId,
      streamRequestBody: sentBody,
      providerPrompts: prompts.slice(chatBefore),
    });
  });

  test("cancels an in-flight slow polish, ignores the late response, and ignores duplicate clicks", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_slow__ wana get a camra";
    const before = await providerState(context.request);

    await textarea(page).fill(draft);
    const requestPromise = page.waitForRequest(isPolishRequest);
    await polishButton(page).click();
    await requestPromise;

    await expect(
      page.locator('[data-testid="cancel-polish-input-button"]'),
    ).toBeVisible();
    await expect(polishButton(page)).toBeDisabled();
    await expect
      .poll(async () => (await providerState(context.request)).polishCalls.length)
      .toBe(before.polishCalls.length + 1);

    // A programmatic duplicate click must be ignored while polishing.
    await polishButton(page).dispatchEvent("click");
    await page.waitForTimeout(250);
    expect((await providerState(context.request)).polishCalls.length).toBe(
      before.polishCalls.length + 1,
    );

    await page.locator('[data-testid="cancel-polish-input-button"]').click();
    await expect(textarea(page)).toHaveValue(draft);
    await expect(polishButton(page)).toBeEnabled();

    // Wait past the 4s provider delay: the late response must not overwrite.
    await page.waitForTimeout(5000);
    await expect(textarea(page)).toHaveValue(draft);
    expect((await providerState(context.request)).polishCalls.length).toBe(
      before.polishCalls.length + 1,
    );

    await saveEvidence(testInfo, "w03-input-polish-cancel", {
      draft,
      providerCalls: 1,
      textareaAfterCancel: draft,
    });
  });

  test("leaving the page aborts an in-flight polish without polluting the next page", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_slow__ wana get a camra";
    await textarea(page).fill(draft);
    const requestPromise = page.waitForRequest(isPolishRequest);
    await polishButton(page).click();
    await requestPromise;
    await expect(
      page.locator('[data-testid="cancel-polish-input-button"]'),
    ).toBeVisible();

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(textarea(page)).toBeVisible();
    await page.waitForTimeout(5000);
    await expect(textarea(page)).not.toHaveValue(POLISH_REWRITE);
    await expect(
      page.locator('[data-testid="cancel-polish-input-button"]'),
    ).toHaveCount(0);

    await saveEvidence(testInfo, "w03-input-polish-unmount", {
      draft,
      textareaAfterReload: await textarea(page).inputValue(),
    });
  });

  test("interprets an unchanged provider answer as no rewrite", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_unchanged__ wana get a camra";
    await textarea(page).fill(draft);
    const polish = await runPolish(page);
    expect(polish.response.status(), await polish.response.text()).toBe(200);
    const body = await polish.response.json();
    expect(body.changed).toBe(false);
    await expect(
      page.locator("[data-sonner-toast]").filter({
        hasText: "This input is already clear.",
      }),
    ).toBeVisible();
    await expect(textarea(page)).toHaveValue(draft);
    await expect(polishButton(page)).toHaveAttribute("aria-label", "Polish input");

    await saveEvidence(testInfo, "w03-input-polish-unchanged", {
      draft,
      changed: body.changed,
    });
  });

  test("strips a malicious thinking block without leaking it to the UI", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_think__ wana get a camra";
    await textarea(page).fill(draft);
    const polish = await runPolish(page);
    expect(polish.response.status(), await polish.response.text()).toBe(200);
    const body = await polish.response.json();
    expect(body.rewritten_text).toBe(POLISH_THINK_TEXT);
    await expect(textarea(page)).toHaveValue(POLISH_THINK_TEXT);
    await expect(page.locator("body")).not.toContainText(PRIVATE_REASONING);

    await saveEvidence(testInfo, "w03-input-polish-think", {
      draft,
      rewritten: body.rewritten_text,
    });
  });

  test("strips a markdown code fence from the provider answer", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_fence__ wana get a camra";
    await textarea(page).fill(draft);
    const polish = await runPolish(page);
    expect(polish.response.status(), await polish.response.text()).toBe(200);
    const body = await polish.response.json();
    expect(body.rewritten_text).toBe(POLISH_FENCE_TEXT);
    await expect(textarea(page)).toHaveValue(POLISH_FENCE_TEXT);

    await saveEvidence(testInfo, "w03-input-polish-fence", {
      draft,
      rewritten: body.rewritten_text,
    });
  });

  test("fails closed on provider failure without leaking provider details", async ({
    context,
    page,
  }, testInfo) => {
    const draft = "__polish_fail__ wana get a camra";
    await textarea(page).fill(draft);
    const polish = await runPolish(page);
    expect(polish.response.status()).toBe(503);
    await expect(
      page.locator("[data-sonner-toast]").filter({
        hasText: "Failed to polish input",
      }),
    ).toBeVisible();
    await expect(textarea(page)).toHaveValue(draft);
    await expect(page.locator("body")).not.toContainText(PROVIDER_SECRET);

    await saveEvidence(testInfo, "w03-input-polish-provider-failure", {
      draft,
      responseStatus: polish.response.status(),
    });
  });

  test("fails closed on an empty provider answer", async ({ context, page }, testInfo) => {
    const draft = "__polish_empty__ wana get a camra";
    await textarea(page).fill(draft);
    const polish = await runPolish(page);
    expect(polish.response.status()).toBe(503);
    await expect(
      page.locator("[data-sonner-toast]").filter({
        hasText: "Failed to polish input",
      }),
    ).toBeVisible();
    await expect(textarea(page)).toHaveValue(draft);

    await saveEvidence(testInfo, "w03-input-polish-empty", {
      draft,
      responseStatus: polish.response.status(),
    });
  });

  test("disables polish for whitespace and fails closed above the 4000 character limit", async ({
    context,
    page,
  }, testInfo) => {
    await textarea(page).fill("   ");
    await expect(polishButton(page)).toBeDisabled();

    const overLimit = "a".repeat(4001);
    await textarea(page).fill(overLimit);
    await expect(polishButton(page)).toBeEnabled();
    const polish = await runPolish(page);
    expect(polish.response.status()).toBe(400);
    await expect(
      page.locator("[data-sonner-toast]").filter({
        hasText: "Input text exceeds 4000 characters",
      }),
    ).toBeVisible();
    await expect(textarea(page)).toHaveValue(overLimit);

    await saveEvidence(testInfo, "w03-input-polish-limits", {
      whitespaceDisabled: true,
      overLimitLength: overLimit.length,
      responseStatus: polish.response.status(),
    });
  });
});
