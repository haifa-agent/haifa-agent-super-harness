// W04 V2 file context on the frozen DeerFlow UI (Haifa backend, controlled L2 provider).
//
// Real browser upload -> saved original -> Thread reference -> Run reads the saved
// bytes with read_file -> model answer. Nothing is mocked or fulfilled; the
// provider derives every value from the read_file result, and this spec checks
// the answer against an independent recomputation of the fixed V2 fixture.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "35618"}`;
const EVIDENCE = process.env.W18_EVIDENCE_DIR;
const FIXTURE = path.resolve(__dirname, "../../../contracts/customer-demo-v2/fixtures/customers.csv");
const FIXTURE_SHA256 = "ec326b7d0cf68f660ed769f8eecf9b71b43137af4e9aa83de75aa75bc5302f00";
const UPLOAD_PATH = "/mnt/user-data/uploads/customers.csv";

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

// Independent oracle: recomputed here from the fixture bytes, never from the Run.
function csvOracle(bytes) {
  const lines = bytes.toString("utf8").split("\n").filter((line) => line !== "");
  expect(lines[0]).toBe("customer_id,region,amount_cny");
  const rows = lines.slice(1).map((line) => line.split(","));
  const sum = (region) => rows.filter((row) => row[1] === region).reduce((acc, row) => acc + Number(row[2]), 0);
  const east = sum("East");
  const west = sum("West");
  const high = rows.filter((row) => Number(row[2]) >= 1500).length;
  const answer = `row17=${rows[16][0]} east=${east} west=${west} total=${east + west} high=${high} rows=${rows.length}`;
  expect(answer).toBe("row17=C017 east=10000 west=11000 total=21000 high=6 rows=20");
  return answer;
}

function providerLog() {
  expect(EVIDENCE, "runner must export W18_EVIDENCE_DIR").toBeTruthy();
  const files = fs.readdirSync(EVIDENCE).filter((name) => /^provider-.*\.stdout\.log$/.test(name));
  return files.flatMap((name) => fs.readFileSync(path.join(EVIDENCE, name), "utf8").split(/\r?\n/))
    .filter((line) => line.startsWith("{"))
    .map((line) => { try { return JSON.parse(line); } catch { return null; } })
    .filter(Boolean);
}

async function saveEvidence(testInfo, name, evidence) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2] };
}

async function pollRun(request, threadId, runId, expected) {
  let latest;
  await expect.poll(async () => {
    const response = await request.get(`${APP}/api/langgraph/threads/${threadId}/runs/${runId}`);
    expect(response.status(), await response.text()).toBe(200);
    latest = await response.json();
    return latest.status;
  }, { timeout: 30_000, intervals: [100, 250, 500] }).toBe(expected);
  return latest;
}

function streamResponse(page) {
  return page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
    { timeout: 30_000 });
}

async function sendText(page, input, text) {
  const stream = streamResponse(page);
  await input.fill(text);
  await input.press("Enter");
  const response = await stream;
  if (response.status() !== 200) throw new Error(`Run stream returned ${response.status()}: ${await response.text()}`);
  return runIdentity(response);
}

async function uploadAndAsk(page, input, bytes, text) {
  const uploadPromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && /\/api\/threads\/[^/]+\/uploads$/.test(new URL(response.url()).pathname),
    { timeout: 30_000 });
  await page.getByLabel("Upload files").setInputFiles({ name: "customers.csv", mimeType: "text/csv", buffer: bytes });
  const stream = streamResponse(page);
  stream.catch(() => {}); // a failed upload must remain the first error
  await input.fill(text);
  await input.press("Enter");
  const upload = await uploadPromise;
  const uploadBody = await upload.text();
  expect(upload.status(), uploadBody).toBe(200);
  const response = await stream;
  if (response.status() !== 200) throw new Error(`Run stream returned ${response.status()}: ${await response.text()}`);
  return { upload: JSON.parse(uploadBody), uploadUrl: upload.url(), ...runIdentity(response) };
}

async function verifyRun(context, page, testInfo, name, { threadId, runId, upload, uploadUrl }, marker, bytes) {
  const expected = `${marker} ${csvOracle(bytes)}`;
  const run = await pollRun(context.request, threadId, runId, "success");
  const chat = page.locator("#chat");
  await expect(chat.getByText(expected, { exact: true })).toBeVisible();
  // The frozen UI strips the server-side upload block from the human bubble.
  await expect(chat.getByText("<current_uploads>")).toHaveCount(0);

  const file = upload.files.find((entry) => entry.filename === "customers.csv");
  expect(file, JSON.stringify(upload)).toBeTruthy();
  expect(file.virtual_path).toBe(UPLOAD_PATH);
  expect(new URL(uploadUrl).pathname).toBe(`/api/threads/${threadId}/uploads`);
  const download = await context.request.get(`${APP}${file.artifact_url}`);
  expect(download.status()).toBe(200);
  const downloadedSha = sha256(await download.body());
  expect(downloadedSha).toBe(FIXTURE_SHA256);

  const stateResponse = await context.request.get(`${APP}/api/langgraph/threads/${threadId}/state`);
  expect(stateResponse.status()).toBe(200);
  const messages = (await stateResponse.json()).values.messages.filter((message) => message.run_id === runId);
  const call = messages.flatMap((message) => message.tool_calls ?? []).find((entry) => entry.name === "read_file");
  expect(call, "the Run must call read_file").toBeTruthy();
  expect(call.args.path).toBe(UPLOAD_PATH);
  const result = messages.find((message) => message.type === "tool" && message.tool_call_id === call.id);
  expect(result, "read_file must have a result").toBeTruthy();
  const structured = result.additional_kwargs.haifa_tool_result;
  expect(structured.sha256).toBe(FIXTURE_SHA256);
  expect(structured.path).toBe(UPLOAD_PATH);
  const human = messages.find((message) => message.type === "human");
  expect(human.content).toContain(`Path: ${UPLOAD_PATH}`);
  expect(human.content).toContain(FIXTURE_SHA256);
  expect(human.content).not.toContain("C017");

  const log = providerLog();
  const actions = log.filter((entry) => entry.type === "v2_file_context" && entry.marker === marker);
  expect(actions.map((entry) => entry.action)).toEqual(["read_file", "answer"]);
  expect(actions[0].path).toBe(UPLOAD_PATH);
  expect(actions[1].answer).toBe(expected);
  expect(actions[1].toolResultSha256).toBe(FIXTURE_SHA256);
  const firstModelRequest = log.find((entry) => entry.type === "provider_request" && entry.toolResults === 0 &&
    (entry.rawPrompt ?? "").includes(marker));
  expect(firstModelRequest.uploadPaths).toContain(UPLOAD_PATH);
  expect(firstModelRequest.offeredTools).toContain("read_file");
  expect(firstModelRequest.rawPrompt).not.toContain("C017");

  await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: true });
  await saveEvidence(testInfo, name, {
    case: name,
    threadId,
    runId,
    terminalStatus: run.status,
    upload: { url: uploadUrl, virtualPath: file.virtual_path, size: file.size, artifactUrl: file.artifact_url },
    downloadedSha256: downloadedSha,
    fixtureSha256: FIXTURE_SHA256,
    readFile: { toolCallId: call.id, path: call.args.path, resultSha256: structured.sha256, lines: `${structured.startLine}-${structured.endLine}/${structured.totalLines}` },
    runContextListsUpload: true,
    providerActions: actions,
    answer: expected,
  });
}

test.describe("W04 V2 frozen UI upload -> Run context -> model answer", () => {
  test("first upload in a new conversation (previous 404 Thread not found)", async ({ context, page }, testInfo) => {
    const bytes = fs.readFileSync(FIXTURE);
    expect(sha256(bytes)).toBe(FIXTURE_SHA256);
    const marker = `v2-file-context-new-${Date.now()}`;
    await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
    const input = page.locator('textarea[name="message"]');
    await expect(input).toBeVisible();
    const run = await uploadAndAsk(page, input, bytes,
      `${marker}: report data row 17's customer_id and the East, West and overall amount totals`);
    await verifyRun(context, page, testInfo, "v2-new-conversation-upload", run, marker, bytes);
  });

  test("upload on an existing Thread reaches the model context", async ({ context, page }, testInfo) => {
    const bytes = fs.readFileSync(FIXTURE);
    const suffix = Date.now();
    await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
    const input = page.locator('textarea[name="message"]');
    await expect(input).toBeVisible();
    const openerMarker = `v2-file-context-open-${suffix}`;
    const opener = await sendText(page, input, openerMarker);
    await pollRun(context.request, opener.threadId, opener.runId, "success");
    await expect(page.locator("#chat").getByText(`${openerMarker} NO_FILE_CONTEXT`, { exact: true })).toBeVisible();

    const marker = `v2-file-context-existing-${suffix}`;
    const run = await uploadAndAsk(page, input, bytes,
      `${marker}: report data row 17's customer_id and the East, West and overall amount totals`);
    expect(run.threadId).toBe(opener.threadId);
    await verifyRun(context, page, testInfo, "v2-existing-thread-upload", run, marker, bytes);
  });
});
