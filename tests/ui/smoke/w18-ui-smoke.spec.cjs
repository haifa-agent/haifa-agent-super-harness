const { expect, test } = require("./fixtures.cjs");
const MODEL_NAME = "w18-controlled";

test.describe("W18 frozen UI -> Haifa local smoke", () => {
  test("no-cookie auth startup renders the real frozen chat page", async ({
    context,
    page,
    runtime,
    workspace,
    runFlow,
    evidence,
  }, testInfo) => {
    const me = await context.request.get(`${runtime.app}/api/v1/auth/me`);
    expect(me.status(), await me.text()).toBe(200);
    const principal = await me.json();
    expect(principal).toMatchObject({
      id: "default",
      email: "default@test.local",
      system_role: "admin",
      needs_setup: false,
    });
    const modelsResponse = await context.request.get(
      `${runtime.app}/api/models`,
    );
    expect(modelsResponse.status(), await modelsResponse.text()).toBe(200);
    const models = await modelsResponse.json();
    expect(models.models.map((model) => model.name)).toContain(MODEL_NAME);

    await workspace.open();
    await evidence.screenshot(page, "auth-startup");
    await evidence.json("auth-startup", {
      frontend: runtime.app,
      pageUrl: page.url(),
      authRequest: `${runtime.app}/api/v1/auth/me`,
      authStatus: me.status(),
      principal,
      executableModels: models.models.map((model) => model.name),
    });
  });

  test("creates a thread and renders a real Haifa SSE answer", async ({
    context,
    page,
    runtime,
    workspace,
    runFlow,
    evidence,
  }, testInfo) => {
    const prompt = `w18-ui-success-${Date.now()}`;
    const { input } = await workspace.open();
    const streamResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(response.url()).pathname,
        ),
    );

    await input.fill(prompt);
    await input.press("Enter");
    const streamResponse = await streamResponsePromise;
    await runFlow.expectStreamSuccess(streamResponse);
    const identity = runFlow.identity(streamResponse);

    await expect(page).toHaveURL(
      new RegExp(`/workspace/chats/${identity.threadId}$`),
    );
    await expect(
      page.locator("#chat").getByText(`Controlled provider: ${prompt}`, {
        exact: true,
      }),
    ).toBeVisible();
    const run = await runFlow.poll(context.request, identity, "success", true);

    // Inspect the exact stream the frozen SDK rendered. Starting a second stream
    // without Last-Event-ID intentionally exercises replay-gap recovery once the
    // bounded bridge has evicted its first events; it is not evidence about the
    // original browser projection.
    const streamText = await streamResponse.text();
    expect(streamText).toContain("event: messages");
    expect(streamText).not.toContain("event: messages-tuple");
    expect(streamText).toContain("event: end");
    const eventIds = [...streamText.matchAll(/^id: (.+)$/gm)].map(
      (match) => match[1],
    );
    expect(eventIds.length).toBeGreaterThan(0);

    await evidence.screenshot(page, "run-sse-success");
    await evidence.json("run-sse-success", {
      prompt,
      ...identity,
      terminalStatus: run.status,
      eventIds,
      streamContainsMessageTuplePayload: true,
      streamMessageEvent: "messages",
      streamContainsEnd: true,
    });
  });

  test("stop button sends the frozen interrupt query and reaches interrupted", async ({
    context,
    page,
    runtime,
    workspace,
    runFlow,
    evidence,
  }, testInfo) => {
    const { input } = await workspace.open();
    const streamResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(response.url()).pathname,
        ),
    );
    const cancelResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/[^/]+\/cancel$/.test(
          url.pathname,
        ) &&
        url.searchParams.get("wait") === "0" &&
        url.searchParams.get("action") === "interrupt"
      );
    });

    await input.fill("__slow__");
    await input.press("Enter");
    const streamResponse = await streamResponsePromise;
    await runFlow.expectStreamSuccess(streamResponse);
    const identity = runFlow.identity(streamResponse);
    // waitForResponse resolves when headers arrive. The SDK stores the
    // Content-Location run id in sessionStorage on the next iterator step;
    // wait for that observable stop prerequisite before clicking the square.
    await expect
      .poll(() =>
        page.evaluate(
          ({ threadId }) => sessionStorage.getItem(`lg:stream:${threadId}`),
          identity,
        ),
      )
      .toBe(identity.runId);
    await page.getByRole("button", { name: "Submit" }).click();
    const cancelResponse = await cancelResponsePromise;
    expect(cancelResponse.status()).toBe(202);
    const run = await runFlow.poll(
      context.request,
      identity,
      "interrupted",
      true,
    );

    await evidence.screenshot(page, "cancel-interrupted");
    await evidence.json("cancel-interrupted", {
      ...identity,
      terminalStatus: run.status,
      cancelStatus: cancelResponse.status(),
      cancelUrl: cancelResponse.url(),
    });
  });

  test("reload restores the completed thread from the Haifa gateway", async ({
    context,
    page,
    runtime,
    workspace,
    runFlow,
    evidence,
  }, testInfo) => {
    const prompt = `w18-ui-reload-${Date.now()}`;
    const { input } = await workspace.open();
    const streamResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
          new URL(response.url()).pathname,
        ),
    );
    await input.fill(prompt);
    await input.press("Enter");
    const streamResponse = await streamResponsePromise;
    await runFlow.expectStreamSuccess(streamResponse);
    const identity = runFlow.identity(streamResponse);
    await expect(
      page.locator("#chat").getByText(`Controlled provider: ${prompt}`, {
        exact: true,
      }),
    ).toBeVisible();
    await runFlow.poll(context.request, identity, "success", true);

    const messagesPageResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        /\/api\/(?:langgraph\/)?threads\/[^/]+\/messages\/page$/.test(
          new URL(response.url()).pathname,
        ),
    );
    const historyResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/(?:langgraph\/)?threads\/[^/]+\/history$/.test(
          new URL(response.url()).pathname,
        ),
    );
    await page.reload({ waitUntil: "domcontentloaded" });
    const [messagesPageResponse, historyResponse] = await Promise.all([
      messagesPageResponsePromise,
      historyResponsePromise,
    ]);
    expect(messagesPageResponse.status()).toBe(200);
    expect(historyResponse.status()).toBe(200);
    await expect(
      page.locator("#chat").getByText(`Controlled provider: ${prompt}`, {
        exact: true,
      }),
    ).toBeVisible();
    await evidence.json("reload-success", {
      prompt,
      ...identity,
      reloadedUrl: page.url(),
      messagesPageStatus: messagesPageResponse.status(),
      messagesPageUrl: messagesPageResponse.url(),
      historyStatus: historyResponse.status(),
      historyUrl: historyResponse.url(),
    });
  });
});
