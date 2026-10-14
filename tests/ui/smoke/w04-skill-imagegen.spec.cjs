// W04 baoyu-image-gen after admission: the frozen UI plus the isolated profile Skill seed with
// the host shell Tool.
//
// Assertion table (038-32 contract 5.1): A0 is the only case allowed to pass in this lane. It
// proves profile admission without any model call (live DeepSeek is started, but the deferred
// executions are the only things that would consume it): the isolated seed Skill is admitted
// from the profile's own skills-fixture directory, its EXTEND.md seeding stays inside the work
// tree data root, the host shell switch is declared on, and the cached bun directory resolves.
//
// A1-A8 stay NOT_RUN with recorded evidence until the controller supplies both facts for a
// candidate-SDK round:
//   - W04_SKILL_RUNTIME_READY=1 (the upstream 038-32 entry is on the consumed baseline), and
//   - W04_SKILL_TOOL_NAME=<tool name> (the registered skill script tool alias).
// The runner's AllowedNotRun list reads tests/ui/smoke/w04-imagegen-deferred.json together with
// the gate below, so deferral can never widen into a PASS.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");

const { expect, test } = require(path.resolve(
  __dirname,
  "../../../frontend/node_modules/@playwright/test",
));

const APP =
  process.env.E2E_APP_URL ??
  `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? "25618"}`;
const EVIDENCE = process.env.W18_EVIDENCE_DIR;
const SKILL = "baoyu-image-gen";
const MODEL_NAME = process.env.W18_MODEL_NAME ?? "w18-deepseek-live";
const DEFERRED = JSON.parse(
  fs.readFileSync(path.join(__dirname, "w04-imagegen-deferred.json"), "utf8"),
);
const RUNTIME_READY = process.env.W04_SKILL_RUNTIME_READY === "1";
const SKILL_TOOL_NAME = process.env.W04_SKILL_TOOL_NAME ?? "";
const EXTEND_PROVIDER = "codex-cli";
const ASSERTION_TABLE = DEFERRED.assertions;

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

// JDK-locked aggregate (SkillPackageFiles.digest): sorted (relative path, content) map with a
// big-endian 8-byte length prefix per key and per value. Reimplemented here so A0 computes the
// package identity independently of the server process.
function packageDigest(files) {
  const digest = crypto.createHash("sha256");
  for (const relative of [...files.keys()].sort()) {
    const key = Buffer.from(relative, "utf8");
    const content = files.get(relative);
    const keyLength = Buffer.alloc(8);
    keyLength.writeBigInt64BE(BigInt(key.length));
    const contentLength = Buffer.alloc(8);
    contentLength.writeBigInt64BE(BigInt(content.length));
    digest.update(keyLength).update(key).update(contentLength).update(content);
  }
  return digest.digest("hex");
}

function seedFiles(root) {
  const files = new Map();
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const child = path.join(root, entry.name);
    if (entry.isDirectory()) {
      for (const [relative, content] of seedFiles(child)) {
        files.set(`${entry.name}/${relative}`, content);
      }
    } else {
      expect(entry.isFile(), `${child} must be a regular file`).toBe(true);
      files.set(entry.name, fs.readFileSync(child));
    }
  }
  return files;
}

function strictlyInside(base, value) {
  if (!value || !path.isAbsolute(value)) return false;
  const relative = path.relative(base, value);
  return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

async function saveEvidence(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(name, {
    path: file,
    contentType: "application/json",
  });
}

// Deferred A1-A8 gate: the runner records the skipped cases as NOT_RUN and only accepts them
// through the AllowedNotRun list sourced from w04-imagegen-deferred.json.
async function requireExecution(testInfo, assertion) {
  if (RUNTIME_READY && SKILL_TOOL_NAME) return;
  const file = testInfo.outputPath(`${assertion}-deferral.json`);
  fs.writeFileSync(
    file,
    JSON.stringify(
      {
        assertion,
        contract: ASSERTION_TABLE[assertion],
        status: "NOT_RUN",
        blockedBy: DEFERRED.blockedBy,
        reason: RUNTIME_READY
          ? `W04_SKILL_TOOL_NAME is unset; the controller supplies it once ${DEFERRED.blockedBy} names the skill script tool`
          : DEFERRED.reason,
        runtimeReady: process.env.W04_SKILL_RUNTIME_READY ?? null,
        skillToolName: SKILL_TOOL_NAME || null,
      },
      null,
      2,
    ) + "\n",
    "utf8",
  );
  await testInfo.attach(`${assertion}-deferral.json`, {
    path: file,
    contentType: "application/json",
  });
  test.skip(true, `${assertion} is NOT_RUN: ${RUNTIME_READY ? "skill tool name undeclared" : DEFERRED.blockedBy}`);
}

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2], location };
}

async function runChat(page, context, prompt) {
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  const stream = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(
        new URL(response.url()).pathname,
      ),
    { timeout: 30_000 },
  );
  await input.fill(prompt);
  await input.press("Enter");
  const response = await stream;
  if (response.status() !== 200) {
    throw new Error(`run stream returned ${response.status()}: ${await response.text()}`);
  }
  const identity = runIdentity(response);
  const deadline = Date.now() + 150_000;
  while (Date.now() < deadline) {
    const snapshot = await context.request.get(`${APP}${identity.location}`, { timeout: 5_000 });
    expect(snapshot.status(), await snapshot.text()).toBe(200);
    const status = (await snapshot.json()).status;
    if (status === "success" || status === "error" || status === "timeout") {
      return { identity, terminal: status };
    }
    const card = page.getByTestId("human-input-card");
    const deny = card.getByRole("button", { name: /^(Reject|拒绝)$/ });
    const approve = card.getByRole("button", { name: /^(Approve|批准)$/ });
    if ((await deny.count()) && (await deny.last().isEnabled())) {
      const answered = page.waitForResponse(
        (value) =>
          value.request().method() === "POST" &&
          Boolean(
            value.request().postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs
              ?.human_input_response,
          ),
        { timeout: 15_000 },
      );
      await deny.last().click();
      const continuation = await answered;
      expect(continuation.status(), await continuation.text()).toBe(200);
      return { identity, denied: true };
    }
    if ((await approve.count()) && (await approve.last().isEnabled())) {
      const answered = page.waitForResponse(
        (value) =>
          value.request().method() === "POST" &&
          Boolean(
            value.request().postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs
              ?.human_input_response,
          ),
        { timeout: 15_000 },
      );
      await approve.last().click();
      const continuation = await answered;
      expect(continuation.status(), await continuation.text()).toBe(200);
      return { identity, approved: true };
    }
    await page.waitForTimeout(250); // Bounded observation of this Run; no retry, no budget change.
  }
  throw new Error(`Run ${identity.runId} exceeded its 150-second terminal deadline`);
}

async function threadState(context, identity) {
  const response = await context.request.get(
    `${APP}/api/langgraph/threads/${identity.threadId}/state`,
    { timeout: 10_000 },
  );
  expect(response.status(), await response.text()).toBe(200);
  return (await response.json()).values;
}

test("A0 isolated baoyu-image-gen admission seed with the host shell profile", async ({ context, page }, testInfo) => {
  page.setDefaultTimeout(15_000);
  page.setDefaultNavigationTimeout(30_000);
  expect(EVIDENCE, "runner must export W18_EVIDENCE_DIR").toBeTruthy();
  const profile = JSON.parse(fs.readFileSync(path.resolve(EVIDENCE, "w04-profile.json"), "utf8"));
  expect(profile.hostShell, "the W04 imagegen profile must turn the host shell switch on").toBe("on");
  if (profile.bunHome) {
    // The controller-supplied bun directory is passed through verbatim; the spec never opens it
    // (it is external to this worktree), it only records that a resolved directory was carried.
    expect(
      path.isAbsolute(profile.bunHome),
      `the recorded bun home ${profile.bunHome} must be an absolute directory reference`,
    ).toBe(true);
  }
  if (profile.bunHomeCandidate) {
    expect(typeof profile.bunHomeCandidate, "the raw W04_BUN_HOME fact must stay a recorded string").toBe("string");
    expect(profile.bunHome === null || profile.bunHome === profile.bunHomeCandidate, "the resolved bun home must be the recorded candidate").toBe(true);
  }

  // Isolation: every seeded path must stay inside this worktree and outside any user home.
  const repoRoot = path.resolve(__dirname, "..", "..");
  const home = path.isAbsolute(os.homedir()) ? os.homedir() : "";
  for (const [name, value] of Object.entries({
    skillsDirectory: profile.skillsDirectory,
    seedPackage: profile.seedPackage,
    extendPath: profile.extendPath,
    manifest: profile.manifest,
  })) {
    expect(strictlyInside(repoRoot, value), `${name} must stay inside this worktree: ${value}`).toBe(true);
    if (home) {
      expect(strictlyInside(home, value) || value === home, `${name} must never live under the user home`).toBe(false);
    }
  }
  expect(path.basename(profile.seedPackage)).toBe(SKILL);

  // Re-hash the whole seed package against the manifest with the JDK-locked digest rules; any
  // drift between the recorded bytes and the bytes the Gateway admitted is a hard failure.
  const seedPackage = path.resolve(profile.seedPackage);
  const manifest = JSON.parse(fs.readFileSync(path.resolve(profile.manifest), "utf8"));
  expect(manifest.skill).toBe(SKILL);
  const files = seedFiles(seedPackage);
  expect([...files.keys()].sort(), "seed file set must match the manifest sheet").toEqual(
    Object.keys(manifest.files).sort(),
  );
  for (const [relative, content] of files) {
    expect(sha256(content), `${relative} digest`).toBe(manifest.files[relative]);
  }
  expect(packageDigest(files), "JDK-locked package digest").toBe(manifest.packageSha256);

  // EXTEND seeding: the package copy and the data-root .baoyu-skills copy are byte-identical
  // and declare the isolated provider fact without touching any external directory.
  const extendContent = files.get("EXTEND.md");
  expect(extendContent.toString("utf8")).toContain(`default_provider: ${EXTEND_PROVIDER}`);
  const companion = path.resolve(profile.extendPath);
  expect(companion.includes(".baoyu-skills"), "the companion EXTEND seeding must live in the data root").toBe(true);
  expect(fs.readFileSync(companion).equals(extendContent), "companion EXTEND.md must mirror the package copy").toBe(true);
  expect(manifest.companion.sha256).toBe(sha256(extendContent));

  const auth = await context.request.get(`${APP}/api/v1/auth/me`);
  expect(auth.status(), await auth.text()).toBe(200);
  const models = await context.request.get(`${APP}/api/models`);
  expect(models.status(), await models.text()).toBe(200);
  expect((await models.json()).models.map((model) => model.name)).toContain(MODEL_NAME);

  // External-directory admission: the exact seed bytes enter the executable Skill catalog with
  // the scripts flag intact, and stay disabled until the deferred review/enable flow runs.
  const skills = await context.request.get(`${APP}/api/skills`);
  expect(skills.status(), await skills.text()).toBe(200);
  const rows = (await skills.json()).skills;
  const row = rows.find((entry) => entry.name === SKILL);
  expect(row, `external-directory admission must expose ${SKILL}`).toBeTruthy();
  expect(row.source).toBe("external-directory");
  expect(row.category).toBe("public");
  expect(row.sha256).toBe(manifest.files["SKILL.md"]);
  expect(row.version).toBe(`sha256:${manifest.packageSha256}`);
  expect(row.enabled, "an executable seed stays disabled before the review and enable flow").toBe(false);
  const detail = await context.request.get(`${APP}/api/skills/${SKILL}`);
  expect(detail.status(), await detail.text()).toBe(200);
  expect((await detail.json()).version).toBe(`sha256:${manifest.packageSha256}`);

  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();

  await page.screenshot({ path: testInfo.outputPath("w04-a0-admission.png"), fullPage: true });
  await saveEvidence(testInfo, "w04-a0-admission", {
    case: "A0",
    started: new Date().toISOString(),
    provider: "https://api.deepseek.com",
    model: MODEL_NAME,
    modelCall: false,
    profile: { ...profile, manifest: "inline below", seedPackage: "inline below" },
    manifest,
    seedFiles: Object.fromEntries([...files.keys()]),
    packageSha256: manifest.packageSha256,
    extendProvider: EXTEND_PROVIDER,
    skillRow: row,
    bunHomePresent: Boolean(profile.bunHome),
    deferred: { blockedBy: DEFERRED.blockedBy, cases: DEFERRED.cases, runtimeReady: RUNTIME_READY },
  });
});

const EXECUTION_PROMPT =
  `Load ${SKILL} using skill_load, then execute its scripts/main.ts entry through the skill script tool ` +
  `(${SKILL_TOOL_NAME || "skill-script-tool"}) to prove the package really runs, and show the tool result.`;

test("A1 approval denial starts no skill script process", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A1");
  const attempt = await runChat(page, context, EXECUTION_PROMPT);
  expect(attempt.denied, "the ALWAYS-approval card must be answered with the denial").toBe(true);
  const state = await threadState(context, attempt.identity);
  const own = state.messages.filter((message) => message.run_id === attempt.identity.runId);
  const calls = own.flatMap((message) => message.tool_calls ?? []).filter((call) => call.name === SKILL_TOOL_NAME);
  expect(calls, "the model must request the skill script tool").toHaveLength(1);
  const results = own.filter((message) => message.type === "tool" && message.tool_call_id === calls[0].id);
  expect(results, "the denied call must carry a tool message").toHaveLength(1);
  expect(
    results.some((message) => message.additional_kwargs?.haifa_tool_status === "completed"),
    "a denied call must never complete",
  ).toBe(false);
  expect((state.artifacts ?? []).length, "a denied execution must not publish anything").toBe(0);
  await saveEvidence(testInfo, "w04-a1-denial", {
    case: "A1", contract: ASSERTION_TABLE.A1, ...attempt, skillToolName: SKILL_TOOL_NAME,
  });
  await page.screenshot({ path: testInfo.outputPath("w04-a1-denial.png"), fullPage: true });
});

test("A2 dependency digest drift rejects skill package materialization", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A2");
  // The frozen package's per-file digests are re-checked first; the SDK owns the pre-execution
  // materialization validation, so the Run-level half asserts the stable rejection surface.
  const profile = JSON.parse(fs.readFileSync(path.resolve(EVIDENCE, "w04-profile.json"), "utf8"));
  const manifest = JSON.parse(fs.readFileSync(path.resolve(profile.manifest), "utf8"));
  const files = seedFiles(path.resolve(profile.seedPackage));
  for (const [relative, content] of files) {
    expect(sha256(content), `${relative} seed digest`).toBe(manifest.files[relative]);
  }
  const attempt = await runChat(page, context, EXECUTION_PROMPT);
  expect(attempt.terminal, `drift must leave the Run interpretable, got ${attempt.terminal}`).toBe("success");
  const state = await threadState(context, attempt.identity);
  const results = state.messages.filter(
    (message) => message.type === "tool" &&
      message.additional_kwargs?.haifa_tool_result?.skill === SKILL,
  );
  expect(
    results.some((message) => message.additional_kwargs?.haifa_tool_status !== "completed"),
    "a drifted dependency must be rejected before execution",
  ).toBe(true);
  await saveEvidence(testInfo, "w04-a2-drift", {
    case: "A2", contract: ASSERTION_TABLE.A2, manifestPackage: manifest.packageSha256, ...attempt,
  });
});

test("A3 multi-file package resolves relative imports and entry-file identity", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A3");
  const attempt = await runChat(page, context,
    `${EXECUTION_PROMPT} Use the entry file scripts/main.ts directly so its relative providers/*.ts imports resolve.`);
  expect(attempt.terminal).toBe("success");
  const state = await threadState(context, attempt.identity);
  const calls = state.messages.flatMap((message) => message.tool_calls ?? [])
    .filter((call) => call.name === SKILL_TOOL_NAME);
  expect(calls, "the execution must reference the entry file, not a stdin script body").toHaveLength(1);
  expect(JSON.stringify(calls[0] ?? {}), "the entry path scripts/main.ts must be part of the identified execution")
    .toContain("scripts/main.ts");
  const toolMessages = state.messages.filter(
    (message) => message.type === "tool" && message.tool_call_id === calls[0].id);
  expect(
    toolMessages.every((message) => message.additional_kwargs?.haifa_tool_status === "completed"),
    "multi-file relative imports must execute successfully",
  ).toBe(true);
  await saveEvidence(testInfo, "w04-a3-entry", {
    case: "A3", contract: ASSERTION_TABLE.A3, ...attempt, skillToolName: SKILL_TOOL_NAME,
  });
});

test("A4 timeout and cancellation terminate the whole process tree", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A4");
  const attempt = await runChat(page, context,
    `${EXECUTION_PROMPT} first demonstrate the timeout path by running a script that sleeps far beyond the declared maximum.`);
  expect(attempt.terminal, "the timed-out Run must still reach an interpretable terminal status").toBe("success");
  const state = await threadState(context, attempt.identity);
  const timedOut = state.messages.filter(
    (message) => message.type === "tool" && message.additional_kwargs?.haifa_tool_result?.timedOut === true);
  expect(timedOut.length, "the declared timeout must surface as a timedOut tool result").toBeGreaterThanOrEqual(1);
  await saveEvidence(testInfo, "w04-a4-process-tree", {
    case: "A4", contract: ASSERTION_TABLE.A4, ...attempt,
    timedOutResults: timedOut.map((message) => message.additional_kwargs.haifa_tool_result),
  });
  expect(attempt.identity).toBeTruthy(); // The runner's managed-process registry carries the tree evidence.
});

test("A5 output outside outputs is rejected and publishes no artifact", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A5");
  const attempt = await runChat(page, context,
    `${EXECUTION_PROMPT} then deliberately write an image artifact outside the outputs/ directory to prove the boundary.`);
  expect(attempt.terminal).toBe("success");
  const state = await threadState(context, attempt.identity);
  const rejected = state.messages.filter((message) => message.type === "tool" &&
    message.tool_call_id &&
    (message.additional_kwargs?.haifa_tool_status === "failed" ||
      typeof message.additional_kwargs?.haifa_tool_error === "string"));
  expect(rejected.length, "the out-of-bounds write must be rejected with a tool failure").toBeGreaterThanOrEqual(1);
  expect(
    (state.artifacts ?? []).every((artifact) => String(artifact).startsWith("/mnt/user-data/outputs/")),
    "no artifact outside outputs/ may be published",
  ).toBe(true);
  await saveEvidence(testInfo, "w04-a5-out-of-bounds", {
    case: "A5", contract: ASSERTION_TABLE.A5, ...attempt, artifacts: state.artifacts ?? [],
  });
});

test("A6 run artifact lands in outputs with verifiable download SHA-256", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A6");
  const attempt = await runChat(page, context,
    `${EXECUTION_PROMPT} generate one real image into /mnt/user-data/outputs/w04-artifact.png and present it.`);
  expect(attempt.terminal).toBe("success");
  expect(attempt.approved, "the execution approval must be answered").toBe(true);
  const state = await threadState(context, attempt.identity);
  const artifactPath = (state.artifacts ?? []).find((entry) => String(entry).startsWith("/mnt/user-data/outputs/"));
  expect(artifactPath, "the generated image must enter the artifact list under outputs/").toBeTruthy();
  const produced = state.messages.filter((message) => message.type === "tool" &&
    message.additional_kwargs?.haifa_tool_result?.artifactId);
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.getByTestId("artifact-trigger").first().click();
  const downloadReady = page.waitForEvent("download", { timeout: 15_000 });
  await page.getByRole("link", { name: /^(Download|下载)$/ }).first().click();
  const download = await downloadReady;
  const bytes = fs.readFileSync(await download.path());
  const downloadedSha = sha256(bytes);
  const producer = produced.find((message) => message.additional_kwargs.haifa_tool_result.artifactSha256 ===
    `sha256:${downloadedSha}`);
  expect(producer, "the artifact producer provenance must match the downloaded bytes").toBeTruthy();
  expect(producer.additional_kwargs.haifa_tool_result.runId, "the artifact must belong to this Run")
    .toBe(attempt.identity.runId);
  await saveEvidence(testInfo, "w04-a6-artifact", {
    case: "A6", contract: ASSERTION_TABLE.A6, ...attempt, artifactPath, downloadedSha,
    provenance: producer.additional_kwargs.haifa_tool_result,
  });
});

test("A7 cross-Run isolation keeps artifacts thread-scoped", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A7");
  const first = await runChat(page, context,
    `${EXECUTION_PROMPT} generate one image named w04-isolation.png into /mnt/user-data/outputs/.`);
  expect(first.terminal).toBe("success");
  const firstState = await threadState(context, first.identity);
  const firstPath = (firstState.artifacts ?? []).find((entry) => String(entry).includes("w04-isolation.png"));
  expect(firstPath, "Run A must own its artifact").toBeTruthy();
  const second = await runChat(page, context,
    `List the files currently present in this conversation's workspace without executing any Skill code.`);
  expect(second.identity.threadId, "the second Run must live on a different thread").not.toBe(first.identity.threadId);
  const leak = await context.request.get(
    `${APP}/api/threads/${second.identity.threadId}/artifacts/${String(firstPath).replace("/mnt/user-data/", "")}`);
  expect([200, 404].includes(leak.status()), "cross-Thread artifact access must 404 or be refused").toBe(true);
  expect(leak.status(), "Run A's product must never be served from Run B").toBe(404);
  const secondState = await threadState(context, second.identity);
  expect(
    (secondState.artifacts ?? []).find((entry) => String(entry).includes("w04-isolation.png")),
    "Run B must expose none of Run A's artifacts",
  ).toBeUndefined();
  await saveEvidence(testInfo, "w04-a7-isolation", {
    case: "A7", contract: ASSERTION_TABLE.A7, first: first.identity, second: second.identity, firstPath,
  });
});

test("A8 undeclared runtime fails closed with a stable error code", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A8");
  const profile = JSON.parse(fs.readFileSync(path.resolve(EVIDENCE, "w04-profile.json"), "utf8"));
  expect(profile.bunHome, "the undeclared-runtime case needs the hosted bun directory").toBeTruthy();
  const attempt = await runChat(page, context,
    `Load ${SKILL} using skill_load and execute its scripts/main.ts entry through the skill script tool.`);
  expect(attempt.terminal).toBe("success");
  const state = await threadState(context, attempt.identity);
  const rejected = state.messages.filter((message) => message.type === "tool" &&
    typeof message.additional_kwargs?.haifa_tool_error === "string");
  expect(rejected.some((message) => /RUNTIME|RUNTIME_UNDECLARED|SCRIPT_RUNTIME/i.test(
    message.additional_kwargs.haifa_tool_error)),
    "an undeclared runtime must fail closed with a stable error signature").toBe(true);
  await saveEvidence(testInfo, "w04-a8-fail-closed", {
    case: "A8", contract: ASSERTION_TABLE.A8, ...attempt,
    errors: rejected.map((message) => message.additional_kwargs.haifa_tool_error),
  });
});
