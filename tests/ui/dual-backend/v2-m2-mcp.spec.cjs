// V2-M2 MCP slice. The same frozen UI, controlled provider and real two-process
// Streamable HTTP fixture are used on both backends. No page route is fulfilled.
const fs = require("node:fs");
const net = require("node:net");
const path = require("node:path");
const { expect, test } = require(path.resolve(__dirname, "../../../frontend/node_modules/@playwright/test"));

const app = process.env.E2E_APP_URL;
const backend = process.env.W18_DIFFERENTIAL_BACKEND;
const evidenceRoot = process.env.W18_DIFFERENTIAL_EVIDENCE;
const manifestPath = process.env.W18_MCP_MANIFEST;
const configPath = process.env.W18_MCP_CONFIG;
if (!app || !["reference", "haifa"].includes(backend) || !evidenceRoot || !manifestPath || !configPath) {
  throw new Error("Explicit V2-M2 app, backend and real MCP manifest are required");
}

function baselineServers() {
  return JSON.parse(fs.readFileSync(configPath, "utf8")).mcp_servers;
}

async function fixtureState(port) {
  const response = await fetch(`http://127.0.0.1:${port}/__test/state`);
  expect(response.status).toBe(200);
  return response.json();
}

async function save(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

function providerEvents(marker) {
  const file = path.join(evidenceRoot, "provider.stdout.log");
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, "utf8").split(/\r?\n/)
    .filter((line) => line.startsWith("{"))
    .map((line) => { try { return JSON.parse(line); } catch { return null; } })
    .filter((entry) => entry &&
      (entry.marker === marker || (entry.type === "provider_request" &&
        (entry.prompt?.includes(marker) || entry.rawPrompt?.includes(marker)))));
}

function isListening(port) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: "127.0.0.1", port });
    socket.setTimeout(1000);
    socket.once("connect", () => { socket.destroy(); resolve(true); });
    socket.once("error", () => resolve(false));
    socket.once("timeout", () => { socket.destroy(); resolve(false); });
  });
}

async function browserJson(page, method, route, body) {
  return page.evaluate(async ({ method, route, body }) => {
    const response = await fetch(route, {
      method,
      headers: body ? { "content-type": "application/json" } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: response.status, body: await response.json() };
  }, { method, route, body });
}

async function openChat(page) {
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  return input;
}

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "real Run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected Content-Location ${location}`).toBeTruthy();
  return { location, threadId: match[1], runId: match[2] };
}

async function runMcp(context, page, testInfo, action, expectTool = true) {
  const marker = `w18-v2-m2-${action}-${Date.now()}`;
  const input = await openChat(page);
  const startedAt = Date.now();
  const streamPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 25_000 });
  await input.fill(marker);
  await input.press("Enter");
  const stream = await streamPromise;
  expect(stream.status(), await stream.text()).toBe(200);
  const identity = runIdentity(stream);
  // Host-declared read-only MCP Tools are auto-approved on the Haifa backend: the
  // call executes without an approval card, matching the reference backend.
  const approval = null;
  let resumed = null;

  let run = null;
  await expect.poll(async () => {
    const response = await context.request.get(`${app}/api/langgraph/threads/${identity.threadId}/runs/${identity.runId}`);
    expect(response.status(), await response.text()).toBe(200);
    run = await response.json();
    return run.status;
  }, { timeout: 30_000, intervals: [200, 500] }).toBe("success");
  const stateResponse = await context.request.get(`${app}/api/langgraph/threads/${identity.threadId}/state`);
  expect(stateResponse.status(), await stateResponse.text()).toBe(200);
  const state = await stateResponse.json();
  const threadResponse = await context.request.get(`${app}/api/langgraph/threads/${identity.threadId}`);
  expect(threadResponse.status(), await threadResponse.text()).toBe(200);
  const thread = await threadResponse.json();
  const events = providerEvents(marker);
  const call = events.find((event) => event.type === "v2_mcp" && event.action === "call");
  const result = events.find((event) => event.type === "v2_mcp" && event.action === "result");
  const streams = [await stream.text(), resumed ? await resumed.text() : ""];
  if (expectTool) {
    expect(streams.join("\n"), "a read-only MCP Tool must not pause for approval")
      .not.toContain("human_input");
  }
  const evidence = {
    backend, action, marker, ...identity, approval,
    runStatus: run.status, elapsedMs: Date.now() - startedAt,
    providerEvents: events, providerCall: call ?? null, providerResult: result ?? null,
    state, thread, streams,
    mcpManifest: JSON.parse(fs.readFileSync(manifestPath, "utf8")),
  };
  await save(testInfo, `v2-m2-${action}`, evidence);
  if (expectTool) {
    expect(call, "controlled model must request a discovered source-qualified Tool").toBeTruthy();
    expect(result, "controlled model must receive a real MCP Tool result").toBeTruthy();
    expect(result.resultSha256).toMatch(/^[0-9a-f]{64}$/);
    const results = (state.values?.messages ?? []).filter((message) =>
      message?.type === "tool" && message?.name === call.tool);
    expect(results, "the same Run must retain exactly one result for this MCP Tool").toHaveLength(1);
  } else {
    expect(call, "revoked or unavailable Tool must not be called").toBeUndefined();
    expect(result).toBeUndefined();
    expect(events.some((event) => event.type === "v2_mcp" && event.action === "unavailable")).toBe(true);
  }
  if (backend === "haifa") {
    // Server-owned restart projection is an intentional bounded difference.
    // Preserve the raw Thread above; normalize only this documented key when
    // comparing user-visible semantics with the reference backend.
    expect(thread.metadata?.haifa_mcp_plugins).toEqual({ mode: "all" });
  } else {
    expect(thread.metadata?.haifa_mcp_plugins).toBeUndefined();
  }
  return evidence;
}

test(`${backend}: V2-M2-01 two independent MCP Server configurations`, async ({ page }, testInfo) => {
  await openChat(page);
  const config = await browserJson(page, "GET", "/api/mcp/config");
  expect(config.status).toBe(200);
  const servers = config.body.mcp_servers;
  expect(Object.keys(servers).sort()).toEqual(["v2-region", "v2-reporting"]);
  expect(servers["v2-reporting"].url).toBe("http://127.0.0.1:20628/mcp");
  expect(servers["v2-region"].url).toBe("http://127.0.0.1:20629/mcp");
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  expect(manifest.mcpServers.map((server) => server.id).sort()).toEqual(["v2-region", "v2-reporting"]);
  expect(manifest.mcpServers.find((server) => server.id === "v2-reporting").tools.sort())
    .toEqual(["get_reporting_policy", "get_status"]);
  expect(manifest.mcpServers.find((server) => server.id === "v2-region").tools.sort())
    .toEqual(["get_region_label", "get_status"]);
  const third = await browserJson(page, "POST", "/api/mcp/config/servers", {
    mcp_servers: { "v2-spare": {
      type: "http", url: "http://127.0.0.1:20630/mcp", enabled: false,
      tools: { get_status: {} },
    } },
  });
  expect(third.status).toBe(200);
  expect(Object.keys(third.body.mcp_servers)).toHaveLength(3);
  const duplicate = await browserJson(page, "POST", "/api/mcp/config/servers", {
    mcp_servers: { "v2-reporting": {
      type: "http", url: "http://127.0.0.1:20630/mcp", enabled: false,
      tools: { get_status: {} },
    } },
  });
  expect(duplicate.status).toBe(409);
  const removed = await browserJson(page, "DELETE", "/api/mcp/config/servers/v2-spare");
  expect(removed.status).toBe(200);
  expect(Object.keys(removed.body.mcp_servers).sort()).toEqual(["v2-region", "v2-reporting"]);
  expect(removed.body.mcp_servers["v2-reporting"].url).toBe(servers["v2-reporting"].url);
  await save(testInfo, "v2-m2-01-config", { case: "V2-M2-01", backend, config, manifest, third, duplicate, removed });
});

test(`${backend}: V2-M2-04 reporting policy from real MCP transport`, async ({ context, page }, testInfo) => {
  const result = await runMcp(context, page, testInfo, "reporting");
  expect(result.providerCall.source).toBe("reporting");
  expect(result.providerResult.result).toContain("v2-pilot-reporting-001");
  expect(result.providerResult.result).toContain("1500");
  expect(JSON.stringify(result.state)).toContain("get_reporting_policy");
});

test(`${backend}: V2-M2-05 second Server returns a region label`, async ({ context, page }, testInfo) => {
  const result = await runMcp(context, page, testInfo, "region");
  expect(result.providerCall.source).toBe("region");
  expect(result.providerResult.result).toContain("华东");
  expect(JSON.stringify(result.state)).toContain("get_region_label");
});

test(`${backend}: V2-M2-06 duplicate get_status keeps its Server source`, async ({ context, page }, testInfo) => {
  const reporting = await runMcp(context, page, testInfo, "status-reporting");
  const region = await runMcp(context, page, testInfo, "status-region");
  expect(reporting.providerCall.tool).not.toBe(region.providerCall.tool);
  expect(reporting.providerResult.result).toContain("v2-reporting");
  expect(region.providerResult.result).toContain("v2-region");
});

test(`${backend}: V2-M2-07 disabling one Server preserves its sibling`, async ({ context, page }, testInfo) => {
  await openChat(page);
  const disabled = await browserJson(page, "PATCH", "/api/mcp/config", { server_name: "v2-region", enabled: false });
  expect(disabled.status).toBe(200);
  expect(disabled.body.mcp_servers["v2-region"].enabled).toBe(false);
  expect(disabled.body.mcp_servers["v2-reporting"].enabled).toBe(true);
  let surviving = null;
  let restored = null;
  try {
    surviving = await runMcp(context, page, testInfo, "reporting");
    expect(surviving.providerResult.result).toContain("v2-pilot-reporting-001");
  } finally {
    restored = await browserJson(page, "PATCH", "/api/mcp/config", { server_name: "v2-region", enabled: true });
    expect(restored.status).toBe(200);
  }
  await save(testInfo, "v2-m2-07-toggle", {
    case: "V2-M2-07", backend, disabled, restored,
    survivingRunId: surviving?.runId ?? null,
  });
});

test(`${backend}: V2-M2-09 revoked Server Tool is not offered to a new Run`, async ({ context, page }, testInfo) => {
  await openChat(page);
  const disabled = await browserJson(page, "PATCH", "/api/mcp/config", { server_name: "v2-region", enabled: false });
  expect(disabled.status).toBe(200);
  let revoked = null;
  try {
    revoked = await runMcp(context, page, testInfo, "region", false);
    expect(JSON.stringify(revoked.state)).toContain("NO_MCP_TOOL");
    const offered = revoked.providerEvents.filter((event) => event.type === "provider_request")
      .flatMap((event) => event.offeredTools ?? []);
    expect(offered.some((name) => name.endsWith("_get_region_label"))).toBe(false);
  } finally {
    const restored = await browserJson(page, "PATCH", "/api/mcp/config", { server_name: "v2-region", enabled: true });
    expect(restored.status).toBe(200);
  }
  await save(testInfo, "v2-m2-09-revocation", {
    case: "V2-M2-09", backend, revokedRunId: revoked?.runId ?? null,
  });
});

test(`${backend}: V2-M2-09 missing and invalid Authorization are rejected by the Server`, async ({ context, page }, testInfo) => {
  await openChat(page);
  const original = baselineServers();
  const observations = [];
  try {
    for (const variant of ["missing", "invalid"]) {
      const changed = structuredClone(original);
      if (variant === "missing") delete changed["v2-region"].headers;
      else changed["v2-region"].headers.Authorization = "Bearer w18-invalid-fixture-only";
      const before = await fixtureState(20629);
      const update = await browserJson(page, "PUT", "/api/mcp/config", { mcp_servers: changed });
      expect(update.status).toBe(200);
      const run = await runMcp(context, page, testInfo, "region", false);
      const after = await fixtureState(20629);
      expect(after.authorization_rejections, "real Server must reject an Authorization header").toBeGreaterThan(before.authorization_rejections);
      expect(JSON.stringify(run.state)).not.toContain("华东");
      observations.push({ variant, before, after, runId: run.runId,
        offeredTools: run.providerEvents.filter((event) => event.type === "provider_request")
          .flatMap((event) => event.offeredTools ?? []) });
    }
  } finally {
    const restored = await browserJson(page, "PUT", "/api/mcp/config", { mcp_servers: original });
    expect(restored.status).toBe(200);
  }
  await save(testInfo, "v2-m2-09-authorization", { case: "V2-M2-09", backend, observations,
    note: "Authorization values are fixture-only sentinels and are omitted from this attachment" });
});

test(`${backend}: V2-M2-08 one unreachable Server cannot remove sibling Tools`, async ({ context, page }, testInfo) => {
  await openChat(page);
  expect(await isListening(20630), "the negative endpoint must have no listener when this case starts").toBe(false);
  // The GET projection masks Authorization. Restore from our private fixture
  // config file so the region Server remains usable in later cases.
  const original = baselineServers();
  const changed = structuredClone(original);
  changed["v2-region"].url = "http://127.0.0.1:20630/mcp";
  let isolated = null;
  try {
    const update = await browserJson(page, "PUT", "/api/mcp/config", { mcp_servers: changed });
    expect(update.status).toBe(200);
    isolated = await runMcp(context, page, testInfo, "reporting");
    expect(isolated.providerResult.result).toContain("v2-pilot-reporting-001");
    const offered = isolated.providerEvents.filter((event) => event.type === "provider_request")
      .flatMap((event) => event.offeredTools ?? []);
    expect(offered.some((name) => name.endsWith("_get_region_label"))).toBe(false);
  } finally {
    const restored = await browserJson(page, "PUT", "/api/mcp/config", { mcp_servers: original });
    expect(restored.status).toBe(200);
  }
  await save(testInfo, "v2-m2-08-isolation", {
    case: "V2-M2-08", backend, unreachableUrl: changed["v2-region"].url,
    reportingRunId: isolated?.runId ?? null,
  });
});

test(`${backend}: V2-M2-11 unknown transport result is not replayed or reported as success`, async ({ context, page }, testInfo) => {
  const input = await openChat(page);
  const before = await fixtureState(20628);
  const arm = await fetch("http://127.0.0.1:20628/__test/arm-disconnect", { method: "POST" });
  expect(arm.status, await arm.text()).toBe(200);
  const marker = `w18-v2-m2-disconnect-${Date.now()}`;
  const streamPromise = page.waitForResponse((response) => response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 25_000 });
  await input.fill(marker);
  await input.press("Enter");
  const stream = await streamPromise;
  expect(stream.status(), await stream.text()).toBe(200);
  const identity = runIdentity(stream);
  // Read-only MCP is auto-approved, so no approval interaction is created before the
  // armed transport disconnect; the single committed call below proves no blind replay.
  const resumed = null;
  const approvalRequestId = null;
  let run = null;
  await expect.poll(async () => {
    const response = await context.request.get(`${app}/api/langgraph/threads/${identity.threadId}/runs/${identity.runId}`);
    expect(response.status(), await response.text()).toBe(200);
    run = await response.json();
    return run.status;
  }, { timeout: 30_000, intervals: [200, 500] }).toMatch(/^(success|error|failed|interrupted|cancelled)$/);
  const stateResponse = await context.request.get(`${app}/api/langgraph/threads/${identity.threadId}/state`);
  expect(stateResponse.status(), await stateResponse.text()).toBe(200);
  const state = await stateResponse.json();
  const after = await fixtureState(20628);
  const events = providerEvents(marker);
  const streams = [await stream.text(), resumed ? await resumed.text() : ""];
  expect(streams.join("\n"), "the read-only MCP disconnect case must not pause for approval")
    .not.toContain("human_input");
  await save(testInfo, "v2-m2-11-unknown-result", {
    case: "V2-M2-11", backend, marker, ...identity, approvalRequestId,
    before, after, run, state, providerEvents: events, streams,
  });
  expect(events.filter((event) => event.type === "v2_mcp" && event.action === "call")).toHaveLength(1);
  expect(after.get_status_calls - before.get_status_calls,
    "Server-side committed invocation count must stay one; no blind replay").toBe(1);
  expect(after.disconnects_after_call - before.disconnects_after_call).toBe(1);
  expect(after.disconnect_armed).toBe(false);
  expect(JSON.stringify(state)).not.toContain(`MCP_RESULT ${marker}`);
  expect(events.some((event) => event.type === "v2_mcp" && event.action === "result" &&
    event.result.includes('"state":"ready"'))).toBe(false);
});
