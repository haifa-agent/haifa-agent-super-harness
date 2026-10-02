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

function eventIds(transcript) {
  return [...transcript.matchAll(/^id: (\d+)-0$/gm)].map((match) =>
    Number.parseInt(match[1], 10),
  );
}

function identifiedEvents(transcript) {
  return transcript
    .split("\n\n")
    .map((frame) => ({
      event: /^event: (.+)$/m.exec(frame)?.[1] ?? null,
      id: /^id: (\d+)-0$/m.exec(frame)?.[1] ?? null,
    }))
    .filter((frame) => frame.id !== null);
}

async function saveEvidence(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, {
    path: file,
    contentType: "application/json",
  });
}

test("active creator disconnect continues and same-tab reload rejoins", async ({
  context,
  page,
}, testInfo) => {
  const joins = [];
  page.on("response", async (response) => {
    const request = response.request();
    const url = new URL(request.url());
    if (
      request.method() === "GET" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/[^/]+\/stream$/.test(
        url.pathname,
      )
    ) {
      const join = {
        url: request.url(),
        status: response.status(),
        lastEventId: request.headers()["last-event-id"] ?? null,
      };
      joins.push(join);
      response
        .text()
        .then((body) => {
          join.body = body;
        })
        .catch((error) => {
          join.bodyError = String(error);
        });
    }
  });

  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const streamPromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      ),
  );
  await input.fill("__slow__");
  await input.press("Enter");
  const stream = await streamPromise;
  expect(stream.status()).toBe(200);
  const identity = runIdentity(stream);
  await expect
    .poll(() =>
      page.evaluate(
        ({ threadId }) => sessionStorage.getItem(`lg:stream:${threadId}`),
        identity,
      ),
    )
    .toBe(identity.runId);

  const runBeforeReload = await context.request.get(
    `${APP}/api/langgraph/threads/${identity.threadId}/runs/${identity.runId}`,
  );
  expect(runBeforeReload.status(), await runBeforeReload.text()).toBe(200);
  expect((await runBeforeReload.json()).status).toBe("running");

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect
    .poll(() => joins.length, { timeout: 30_000, intervals: [100, 250, 500] })
    .toBeGreaterThan(0);
  expect(joins[0]).toMatchObject({ status: 200, lastEventId: "-1" });

  let terminal;
  await expect
    .poll(
      async () => {
        const response = await context.request.get(
          `${APP}/api/langgraph/threads/${identity.threadId}/runs/${identity.runId}`,
        );
        expect(response.status(), await response.text()).toBe(200);
        terminal = await response.json();
        return terminal.status;
      },
      { timeout: 30_000, intervals: [250, 500] },
    )
    .toBe("success");

  await expect
    .poll(() => joins[0].body ?? joins[0].bodyError, {
      timeout: 30_000,
      intervals: [100, 250, 500],
    })
    .toBeTruthy();
  expect(joins[0].bodyError).toBeUndefined();
  const ids = eventIds(joins[0].body);
  expect(ids.length).toBeGreaterThan(0);
  expect(ids).toEqual([...ids].sort((left, right) => left - right));
  const frames = identifiedEvents(joins[0].body);
  expect(new Set(frames.map(({ event, id }) => `${id}:${event}`)).size).toBe(
    frames.length,
  );
  expect(joins[0].body).toContain("event: end");

  const expectedAssistant = ".".repeat(200);
  await expect(
    page.locator("#chat").getByText(expectedAssistant, {
      exact: true,
    }),
  ).toBeVisible({ timeout: 30_000 });
  const occurrences = await page
    .locator("#chat")
    .getByText(expectedAssistant, { exact: true })
    .count();
  expect(occurrences).toBe(1);

  const screenshot = testInfo.outputPath("active-reconnect.png");
  await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach("active-reconnect.png", {
    path: screenshot,
    contentType: "image/png",
  });
  await saveEvidence(testInfo, "active-reconnect", {
    ...identity,
    creatorDisconnectedByReload: true,
    runStatusBeforeReload: "running",
    terminalStatus: terminal.status,
    joins: joins.map(({ body, ...join }) => ({
      ...join,
      eventIds: eventIds(body),
      identifiedEvents: identifiedEvents(body),
      events: [...body.matchAll(/^event: (.+)$/gm)].map((match) => match[1]),
    })),
    renderedOccurrences: occurrences,
  });
});
