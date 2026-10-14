// W04 baoyu-image-gen trusted Skill script profile: the frozen UI plus the isolated execution
// seed with the declared bun runtime.
//
// Assertion table (038-32 contract 5.1, adapted to this lane's declared facts): A0 proves profile
// admission without a model call; A1-A8 drive real Runs through the frozen UI with the live DeepSeek
// managed model. The runner records whether the declared bun runtime exists (w04-profile.json
// `runtime: declared|undeclared`):
//   - declared round: A1-A8 all assert real behaviour (A8 exercises the model boundary: the runtime
//     is not a model choice, so a schema-level injection is rejected before any process).
//   - undeclared round: the runner's AllowedNotRun list (w04-imagegen-deferred.json) covers A1-A7;
//     A8 alone asserts the fail-closed surface (the Tool is not registered, nothing starts).
// No placeholder env gate remains: the deferral is value-driven from the same manifest both sides
// read, so a skipped case can never widen into a PASS.
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
const SKILL_TOOL_NAME = "baoyu_image_gen";
const MODEL_NAME = process.env.W18_MODEL_NAME ?? "w18-deepseek-live";
const DEFERRED = JSON.parse(
  fs.readFileSync(path.join(__dirname, "w04-imagegen-deferred.json"), "utf8"),
);
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

// Value-driven NOT_RUN gate: in an undeclared-runtime round A1-A7 stay NOT_RUN through the runner's
// AllowedNotRun list; the deferral records the reason per case instead of silently passing.
async function requireExecution(testInfo, assertion) {
  const profile = readProfile();
  if (profile.runtime === "declared") return;
  const file = testInfo.outputPath(`${assertion}-deferral.json`);
  fs.writeFileSync(
    file,
    JSON.stringify(
      {
        assertion,
        contract: ASSERTION_TABLE[assertion],
        status: "NOT_RUN",
        blockedBy: DEFERRED.blockedBy,
        reason: DEFERRED.reason,
        runtime: profile.runtime,
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
  test.skip(true, `${assertion} is NOT_RUN: ${DEFERRED.blockedBy}`);
}

function readProfile() {
  expect(EVIDENCE, "runner must export W18_EVIDENCE_DIR").toBeTruthy();
  return JSON.parse(fs.readFileSync(path.resolve(EVIDENCE, "w04-profile.json"), "utf8"));
}

// Review + enable flow: the executable seed enters the executable Skill catalog only after the
// product's deterministic screen evidence is recorded, exactly like the frozen UI's enable toggle.
async function enableSkill(context) {
  const response = await context.request.put(`${APP}/api/skills/${SKILL}`, {
    data: { enabled: true },
    timeout: 60_000,
  });
  expect(response.status(), await response.text()).toBe(200);
  const row = (await response.json());
  expect(row.enabled, "the execution lane needs the admitted seed enabled").toBe(true);
}

function runIdentity(response) {
  const location = response.headers()["content-location"];
  expect(location, "run stream must expose Content-Location").toBeTruthy();
  const match = location.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `unexpected run location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2], location };
}

async function runChat(page, context, prompt, deadlineMs = 150_000, cardAction = "approve") {
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
  const deadline = Date.now() + deadlineMs;
  let answered = null;
  while (Date.now() < deadline) {
    const snapshot = await context.request.get(`${APP}${identity.location}`, { timeout: 5_000 });
    expect(snapshot.status(), await snapshot.text()).toBe(200);
    const status = (await snapshot.json()).status;
    if (status === "success" || status === "error" || status === "timeout") {
      return { identity, terminal: status, ...answered };
    }
    const card = page.getByTestId("human-input-card");
    if (card.count && (await card.count())) {
      for (const action of cardAction === "deny" ? ["deny", "approve"] : ["approve", "deny"]) {
        const button = card.getByRole("button", {
          name: action === "approve" ? /^(Approve|批准)$/ : /^(Reject|拒绝)$/,
        });
        if ((await button.count()) && (await button.last().isEnabled())) {
          const answeredResponse = page.waitForResponse(
            (value) =>
              value.request().method() === "POST" &&
              Boolean(
                value.request().postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs
                  ?.human_input_response,
              ),
            { timeout: 15_000 },
          );
          await button.last().click();
          if (!answered) answered = {};
          const continuation = await answeredResponse;
          expect(continuation.status(), await continuation.text()).toBe(200);
          answered = { [action === "approve" ? "approved" : "denied"]: true };
          break;
        }
      }
    }
    await page.waitForTimeout(250); // Bounded observation of this Run; no retry, no budget change.
  }
  throw new Error(`Run ${identity.runId} exceeded its ${Math.round(deadlineMs / 1000)}-second terminal deadline`);
}

async function threadState(context, identity) {
  const response = await context.request.get(
    `${APP}/api/langgraph/threads/${identity.threadId}/state`,
    { timeout: 10_000 },
  );
  expect(response.status(), await response.text()).toBe(200);
  return (await response.json()).values;
}

// The frozen artifact route serves `artifacts/mnt/user-data/<relative>`; a virtual path carries a
// leading slash that the route joins (the encoded URL keeps the same segment sequence).
function routeArtifact(artifactPath) {
  return String(artifactPath).replace(/^\//, "");
}

function ownCalls(state, runId) {
  return state.messages
    .filter((message) => message.run_id === runId)
    .flatMap((message) => message.tool_calls ?? [])
    .filter((call) => call.name === SKILL_TOOL_NAME);
}

function toolResult(state, callId) {
  return state.messages.find(
    (message) => message.type === "tool" && message.tool_call_id === callId,
  );
}

const GENERATE_PROMPT =
  `Load ${SKILL} using skill_load, follow its loaded instructions, then answer with a short summary ` +
  `of the tool result text.`;

test("A0 isolated baoyu-image-gen execution seed with the host shell profile", async ({ context, page }, testInfo) => {
  page.setDefaultTimeout(15_000);
  page.setDefaultNavigationTimeout(30_000);
  expect(EVIDENCE, "runner must export W18_EVIDENCE_DIR").toBeTruthy();
  const profile = readProfile();
  expect(profile.hostShell, "the W04 imagegen profile must turn the host shell switch on").toBe("on");
  expect(profile.toolName).toBe(SKILL_TOOL_NAME);
  expect(["declared", "undeclared"]).toContain(profile.runtime);
  if (profile.runtime === "declared") {
    expect(profile.bunPath, "the declared bun executable must be an absolute file reference").toBeTruthy();
    expect(path.isAbsolute(profile.bunPath)).toBe(true);
  } else {
    expect(profile.bunPath ?? null, "an undeclared round must carry no bun path").toBeNull();
  }
  if (profile.bunHome) {
    // The controller-supplied bun directory is passed through verbatim; the spec never opens it
    // (it is external to this worktree), it only records that a resolved directory was carried.
    expect(
      path.isAbsolute(profile.bunHome),
      `the recorded bun home ${profile.bunHome} must be an absolute directory reference`,
    ).toBe(true);
  }

  // Isolation: every seeded path must stay inside this worktree and outside any user home.
  const repoRoot = path.resolve(__dirname, "..", "..", "..");
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
  expect(manifest.entry, "the seed entry matches the product declaration").toBe("scripts/main.ts");
  expect(manifest.runtime).toBe("bun");
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
  // the scripts flag intact, and stay disabled until the review/enable flow runs.
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
    seedFiles: Object.fromEntries(files),
    packageSha256: manifest.packageSha256,
    extendProvider: EXTEND_PROVIDER,
    skillRow: row,
    deferred: { blockedBy: DEFERRED.blockedBy, cases: DEFERRED.cases },
  });
});

test("A1 approval denial starts no skill script process", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A1");
  await enableSkill(context);
  const attempt = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} Start by generating an image with prompt "approval probe" and image_name "a1-denial.png". ` +
      `When the approval card appears somebody will answer it; wait for the result and then report it.`,
    150_000,
    "deny",
  );
  expect(attempt.denied, "the ALWAYS-approval card must be answered with the denial").toBe(true);
  const state = await threadState(context, attempt.identity);
  const calls = ownCalls(state, attempt.identity.runId);
  expect(calls, "the model must request the skill script tool").toHaveLength(1);
  const result = toolResult(state, calls[0].id);
  expect(result, "the denied call must carry a tool message").toBeTruthy();
  expect(
    result.additional_kwargs?.haifa_tool_status === "completed",
    "a denied call must never complete",
  ).toBe(false);
  expect((state.artifacts ?? []).length, "a denied execution must not publish anything").toBe(0);
  await saveEvidence(testInfo, "w04-a1-denial", {
    case: "A1", contract: ASSERTION_TABLE.A1, identity: attempt.identity,
    toolCall: calls[0], status: result.additional_kwargs?.haifa_tool_status ?? null,
  });
  await page.screenshot({ path: testInfo.outputPath("w04-a1-denial.png"), fullPage: true });
});

test("A2 verified package identity prepares the frozen execution directory once", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A2");
  await enableSkill(context);
  const profile = readProfile();
  const manifest = JSON.parse(fs.readFileSync(path.resolve(profile.manifest), "utf8"));
  const attempt = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} Run the script entry in help mode (help: true) first, then report its usage output verbatim.`,
  );
  expect(attempt.terminal, `the identity run must complete, got ${attempt.terminal}`).toBe("success");
  expect(attempt.approved, "the execution approval must be answered").toBe(true);
  const state = await threadState(context, attempt.identity);
  const calls = ownCalls(state, attempt.identity.runId).filter((call) =>
    JSON.stringify(call.args ?? {}).includes('"help":true') || JSON.stringify(call.args ?? {}).includes('"help": true'));
  expect(calls.length, "the run must execute the declared help probe").toBeGreaterThanOrEqual(1);
  const results = calls.map((call) => toolResult(state, call.id)).filter(Boolean);
  expect(
    results.filter((message) => message.additional_kwargs?.haifa_tool_status === "completed").length,
    "the approved help probe must complete",
  ).toBeGreaterThanOrEqual(1);
  const usage = results.map((message) => message.content ?? []).join("\n");
  expect(usage, "the executed bytes must come from the declared package identity").toContain("W04_SEED_MAIN_OK");

  // Materialization audit inside this worktree's data root: this Run's own workspace carries the
  // prepared version directory with the declared entry bytes; no temporary directory remains.
  const storageRoot = path.resolve(profile.workspaceStorageRoot, "workspaces");
  const threadWorkspaces = fs.existsSync(storageRoot)
    ? fs.readdirSync(storageRoot, { withFileTypes: true })
        .filter((entry) => entry.isDirectory() && /^ws-[0-9a-f]{32}$/.test(entry.name))
        .filter((entry) => {
          const binding = path.join(storageRoot, entry.name, "binding.properties");
          if (!fs.existsSync(binding)) return false;
          const content = fs.readFileSync(binding, "utf8");
          return content.split(/\r?\n/).some((line) => line.trim() === `threadId=${attempt.identity.threadId}`);
        })
        .map((entry) => path.join(storageRoot, entry.name))
    : [];
  expect(threadWorkspaces.length, "the audited Run owns exactly one workspace directory").toBe(1);
  const versionDir = path.join(threadWorkspaces[0], "user-data", ".haifa-trusted-skills");
  expect(fs.existsSync(versionDir), "the declared package must materialize in the Run workspace").toBe(true);
  const versionRoots = fs.readdirSync(versionDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => ({ version: entry.name, root: path.join(versionDir, entry.name) }));
  expect(versionRoots.length, "the frozen package materializes exactly once in this workspace").toBe(1);
  expect(/^[0-9a-f]{64}$/.test(versionRoots[0].version), "version key must be a 64-hex identity").toBe(true);
  const entryBytes = fs.readFileSync(path.join(versionRoots[0].root, "scripts/main.ts"));
  expect(sha256(entryBytes), "the prepared entry must be byte-identical to the seed").toBe(manifest.files["scripts/main.ts"]);
  expect(fs.readdirSync(versionRoots[0].root).some((name) => name.startsWith(".haifa-prepared")),
    "the SDK prepared marker must be present").toBe(true);
  expect(
    fs.readdirSync(versionDir, { withFileTypes: true }).some((entry) => entry.name.startsWith(".pending-")),
    "no temporary materialization directories may remain",
  ).toBe(false);
  await saveEvidence(testInfo, "w04-a2-materialization", {
    case: "A2", contract: ASSERTION_TABLE.A2, identity: attempt.identity,
    packageSha256: manifest.packageSha256, versionDirectory: versionRoots[0],
    entrySha256: manifest.files["scripts/main.ts"],
  });
});

test("A3 multi-file package resolves relative imports and entry-file identity", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A3");
  await enableSkill(context);
  const attempt = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} Call the tool with { "prompt": "entry identity probe", "image_name": "a3-entry.png" } so the entry resolves its providers, then report the printed lines.`,
  );
  expect(attempt.terminal).toBe("success");
  expect(attempt.approved, "the execution approval must be answered").toBe(true);
  const state = await threadState(context, attempt.identity);
  const calls = ownCalls(state, attempt.identity.runId)
    .filter((call) => JSON.stringify(call.args ?? {}).includes("entry identity probe"));
  expect(calls, "the generation call must reference the bounded business arguments").toHaveLength(1);
  const result = toolResult(state, calls[0].id);
  expect(result?.additional_kwargs?.haifa_tool_status, "relative imports must execute successfully").toBe("completed");
  const text = `${result?.content ?? ""}\n${JSON.stringify(result?.additional_kwargs?.haifa_tool_result ?? {})}`;
  // The relative-import evidence comes from the entry's own printed facts: the banner constant and
  // the EXTEND provider read through ../lib/extend.ts prove multi-file cross-directory execution.
  expect(text).toContain("W04_SEED_PROVIDER_OK=codex-cli");
  expect(text).toContain("W04_SEED_ARTIFACT=outputs/a3-entry.png");
  await saveEvidence(testInfo, "w04-a3-entry", {
    case: "A3", contract: ASSERTION_TABLE.A3, identity: attempt.identity,
    toolCall: calls[0], text: text.slice(0, 4000),
  });
});

test("A4 timeout and cancellation terminate the whole process tree", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A4");
  await enableSkill(context);
  const attempt = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} Call the tool with { "prompt": "timeout probe", "image_name": "a4-timeout.png", "sleep_millis": 600000 } to make the declared timeout terminate the process, then report the tool result.`,
    280_000,
  );
  expect(attempt.terminal, "the timed-out Run must still reach an interpretable terminal status").toBe("success");
  expect(attempt.approved, "the timeout probe approval must be answered").toBe(true);
  const state = await threadState(context, attempt.identity);
  const calls = ownCalls(state, attempt.identity.runId);
  const results = calls.map((call) => toolResult(state, call.id)).filter(Boolean);
  const terminated = results.filter((message) => {
    const envelope = message.additional_kwargs?.haifa_tool_result ?? {};
    return envelope.timedOut === true || envelope.cancelled === true;
  });
  expect(terminated.length, "the declared timeout must surface as a terminated tool result").toBeGreaterThanOrEqual(1);
  expect(
    (state.artifacts ?? []).some((entry) => String(entry).includes("a4-timeout.png")),
    "a terminated process must not publish the probe artifact",
  ).toBe(false);
  await saveEvidence(testInfo, "w04-a4-process-tree", {
    case: "A4", contract: ASSERTION_TABLE.A4, identity: attempt.identity,
    timedOutResults: terminated.map((message) => message.additional_kwargs.haifa_tool_result),
  });
  await page.screenshot({ path: testInfo.outputPath("w04-a4-process-tree.png"), fullPage: true });
});

test("A5 output outside outputs is rejected and publishes no artifact", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A5");
  await enableSkill(context);
  const attempt = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} First call the tool with { "prompt": "boundary probe", "image_name": "sub/way.png" } (a deliberate path escape), then retry once with { "prompt": "boundary probe", "image_name": "a5-bounds.png" }, and report both tool results verbatim.`,
  );
  expect(["success", "error"]).toContain(attempt.terminal); // bounded rejection stays interpretable
  const state = await threadState(context, attempt.identity);
  const calls = ownCalls(state, attempt.identity.runId);
  expect(calls.length, "the model must reach the tool with the escape probe").toBeGreaterThanOrEqual(1);
  const failed = calls
    .map((call) => toolResult(state, call.id))
    .filter((message) => message && (
      message.additional_kwargs?.haifa_tool_status === "failed" ||
      typeof message.additional_kwargs?.haifa_tool_error === "string"));
  expect(failed.length, "the out-of-bounds write must be rejected with a tool failure").toBeGreaterThanOrEqual(1);
  expect(
    (state.artifacts ?? []).every((artifact) => String(artifact).startsWith("/mnt/user-data/outputs/")),
    "no artifact outside outputs/ may be published",
  ).toBe(true);
  // The escape path must never exist anywhere on disk under the lane workspaces.
  const storageRoot = path.resolve(readProfile().workspaceStorageRoot, "workspaces");
  const escapeOnDisk = (function walkEscape(root) {
    if (!fs.existsSync(root)) return null;
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
      const workspace = path.join(root, entry.name);
      if (!entry.isDirectory()) continue;
      const userData = path.join(workspace, "user-data");
      if (!fs.existsSync(path.join(userData, "sub"))) continue;
      return path.join(userData, "sub");
    }
    return null;
  })(storageRoot);
  expect(escapeOnDisk, "the escape path must never be written on disk").toBeNull();
  await saveEvidence(testInfo, "w04-a5-out-of-bounds", {
    case: "A5", contract: ASSERTION_TABLE.A5, identity: attempt.identity,
    rejected: failed.map((message) => ({
      status: message.additional_kwargs?.haifa_tool_status,
      error: message.additional_kwargs?.haifa_tool_error ?? null,
      content: String(message.content ?? "").slice(0, 1000),
    })),
  });
});

test("A6 run artifact lands in outputs with verifiable download SHA-256", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A6");
  await enableSkill(context);
  const attempt = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} Call the tool with { "prompt": "artifact chain probe", "image_name": "w04-artifact.png" }, wait for the tool result, then present outputs/w04-artifact.png with present_files so the user can download it, and report the SHA-256 line.`,
  );
  expect(attempt.terminal).toBe("success");
  expect(attempt.approved, "the execution approval must be answered").toBe(true);
  const state = await threadState(context, attempt.identity);
  const artifactPath = (state.artifacts ?? []).find((entry) =>
    String(entry).startsWith("/mnt/user-data/outputs/") && String(entry).includes("w04-artifact.png"));
  expect(artifactPath, "the generated image must enter the artifact list under outputs/").toBeTruthy();
  const producerCalls = ownCalls(state, attempt.identity.runId);
  const producerResult = producerCalls
    .map((call) => toolResult(state, call.id))
    .find((message) => message && String(message.content ?? "").includes("W04_SEED_IMAGE_SHA256="));
  expect(producerResult, "the producer tool result must announce the artifact digest").toBeTruthy();
  const claimed = producerResult.content.match(/W04_SEED_IMAGE_SHA256=([0-9a-f]{64})/);
  expect(claimed, "the tool result must carry the printed SHA-256 line").toBeTruthy();
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.getByTestId("artifact-trigger").first().click();
  const downloadReady = page.waitForEvent("download", { timeout: 20_000 });
  await page.getByRole("link", { name: /^(Download|下载)$/ }).first().click();
  const download = await downloadReady;
  const bytes = fs.readFileSync(await download.path());
  const downloadedSha = sha256(bytes);
  expect(downloadedSha, "the artifact download bytes must match the executed artifact").toBe(claimed[1]);
  const again = await context.request.get(
    `${APP}/api/threads/${attempt.identity.threadId}/artifacts/${routeArtifact(artifactPath)}`,
    { timeout: 15_000 });
  expect(again.status(), await again.text()).toBe(200);
  expect(sha256(await again.body()), "a second download must be byte-stable and routable").toBe(downloadedSha);
  expect(producerResult.run_id, "the producer provenance stays on this Run").toBe(attempt.identity.runId);
  await saveEvidence(testInfo, "w04-a6-artifact", {
    case: "A6", contract: ASSERTION_TABLE.A6, identity: attempt.identity,
    artifactPath, downloadedSha, byteSize: bytes.length,
  });
});

test("A7 cross-Run isolation keeps artifacts thread-scoped", async ({ context, page }, testInfo) => {
  await requireExecution(testInfo, "A7");
  await enableSkill(context);
  const first = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} Call the tool with { "prompt": "isolation probe", "image_name": "w04-isolation.png" }, then present outputs/w04-isolation.png with present_files.`,
  );
  expect(first.terminal).toBe("success");
  expect(first.approved, "the isolation producer approval must be answered").toBe(true);
  const firstState = await threadState(context, first.identity);
  const firstPath = (firstState.artifacts ?? []).find((entry) =>
    String(entry).includes("w04-isolation.png"));
  expect(firstPath, "Run A must own its artifact").toBeTruthy();
  const second = await runChat(
    page,
    context,
    `List the files currently present in this conversation's workspace without executing any Skill code or tool beyond a listing.`,
  );
  expect(second.identity.threadId, "the second Run must live on a different thread").not.toBe(first.identity.threadId);
  expect(second.denied ?? second.approved ?? null, "the isolation Run must not touch the skill script Tool").toBeNull();
  const leak = await context.request.get(
    `${APP}/api/threads/${second.identity.threadId}/artifacts/${routeArtifact(firstPath)}`,
    { timeout: 15_000 });
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
  const profile = readProfile();
  expect(EVIDENCE, "runner must export W18_EVIDENCE_DIR").toBeTruthy();
  await enableSkill(context);
  if (profile.runtime === "declared") {
    // Declared round: the runtime is a product declaration (bun, profile-recorded) and the Tool
    // schema never exposes runtime/script/working-directory selection. The in-Run stable rejection
    // is the same fail-closed boundary, exercised at a model-controlled point: the output escape.
    const attempt = await runChat(
      page,
      context,
      `${GENERATE_PROMPT} Run one probing call exactly once with { "prompt": "runtime boundary probe", "image_name": "sub/way.png" } as written, without adjusting the arguments, then quote the tool's error text so we can document the fixed output boundary.`,
    );
    expect(["success", "error"]).toContain(attempt.terminal); // bounded rejection stays interpretable
    const state = await threadState(context, attempt.identity);
    const calls = ownCalls(state, attempt.identity.runId);
    expect(calls.length, "the model must reach the Tool with the boundary probe").toBeGreaterThanOrEqual(1);
    // No tool call may carry a key that would widen the execution surface (runtime, script,
    // interpreter, working directory, path or args); the declaration closes them all.
    const forbidden = ["runtime", "language", "interpreter", "script", "workdir", "cwd", "workingDirectory", "workspaceId", "args"];
    for (const call of calls) {
      for (const key of forbidden) {
        expect(Object.keys(call.args ?? {}), `${key} is not model-reachable`).not.toContain(key);
      }
    }
    const rejected = calls
      .map((call) => toolResult(state, call.id))
      .filter((message) => message && (
        message.additional_kwargs?.haifa_tool_status === "failed" ||
        typeof message.additional_kwargs?.haifa_tool_error === "string"));
    expect(rejected.length, "the boundary must fail closed with a stable tool error").toBeGreaterThanOrEqual(1);
    expect(
      calls.some((call) => toolResult(state, call.id)?.additional_kwargs?.haifa_tool_status === "completed"),
      "an escape attempt must never start the process",
    ).toBe(false);
    expect((state.artifacts ?? []).some((entry) => String(entry).includes("sub/")),
      "an escape attempt must not publish anything").toBe(false);
    await saveEvidence(testInfo, "w04-a8-schema-boundary", {
      case: "A8", contract: ASSERTION_TABLE.A8, runtime: profile.runtime, bunPath: profile.bunPath,
      identity: attempt.identity,
      rejected: rejected.map((message) => ({
        status: message.additional_kwargs?.haifa_tool_status ?? null,
        error: message.additional_kwargs?.haifa_tool_error ?? null,
      })),
    });
    return;
  }
  // Undeclared round (dedicated profile round without the bun declaration): the Tool is not
  // registered at all; any attempt fails closed and nothing is approved, executed or published.
  const attempt = await runChat(
    page,
    context,
    `${GENERATE_PROMPT} Call the tool named ${SKILL_TOOL_NAME} with { "prompt": "undeclared runtime probe", "image_name": "a8-undeclared.png" } exactly once, then report the tool result verbatim.`,
  );
  expect(attempt.terminal, `the fail-closed Run must complete, got ${attempt.terminal}`).toBe("success");
  expect(attempt.approved ?? false, "an undeclared runtime must never reach an approval").toBe(false);
  expect(attempt.denied ?? false, "an undeclared runtime must never reach a denial either").toBe(false);
  const state = await threadState(context, attempt.identity);
  const attempted = ownCalls(state, attempt.identity.runId);
  const results = attempted.map((call) => toolResult(state, call.id)).filter(Boolean);
  expect(
    results.filter((message) => message.additional_kwargs?.haifa_tool_status === "completed").length,
    "the undeclared runtime must never start a process",
  ).toBe(0);
  expect((state.artifacts ?? []).length, "an undeclared runtime must not publish anything").toBe(0);
  await saveEvidence(testInfo, "w04-a8-fail-closed", {
    case: "A8", contract: ASSERTION_TABLE.A8, runtime: profile.runtime, identity: attempt.identity,
    attemptedCalls: attempted.length,
    failedCalls: results.map((message) => ({
      status: message.additional_kwargs?.haifa_tool_status ?? null,
      error: message.additional_kwargs?.haifa_tool_error ?? null,
    })),
  });
});
