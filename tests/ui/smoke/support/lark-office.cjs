const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

// Pure controlled-model decisions. Only real SDK Tool replies can advance this flow.
function toolReplies(body, name) {
  const calls = new Map();
  for (const message of body.messages ?? []) {
    for (const call of message.tool_calls ?? []) calls.set(call.id, call.function?.name);
  }
  return (body.messages ?? []).filter((message) => message.role === "tool" &&
    (message.name === name || calls.get(message.tool_call_id) === name));
}

function decideLark(body, marker, offeredTools) {
  const loads = toolReplies(body, "skill_load");
  const reads = toolReplies(body, "skill_resource_read");
  if (loads.length === 0 && offeredTools.includes("skill_load")) {
    return { action: "skill_load", tool: "skill_load", args: { skill: "lark-doc" } };
  }
  if (loads.length > 0 && reads.length === 0 && offeredTools.includes("skill_resource_read")) {
    return { action: "skill_resource_read", tool: "skill_resource_read",
      args: { skill: "lark-doc", path: "references/lark-doc-fetch.md" } };
  }
  const readText = JSON.stringify(reads);
  const instructions = JSON.stringify(body.messages ?? []);
  const observed = loads.length > 0 && reads.length > 0 &&
    instructions.includes("lark-cli docs +fetch") && readText.includes("--scope keyword") &&
    readText.includes("--detail with-ids");
  return { action: "answer", observed, loads: loads.length, reads: reads.length,
    text: `${marker} ${observed ? "OFFICIAL_LARK_DOC_LOADED" : "LARK_DOC_LOAD_MISSING"}` };
}

module.exports = { decideLark, dataRootFromSeed, readResourceReceipt };

const RECEIPT_FIXTURE = path.resolve(__dirname, "../../fixtures/w20/lark_resource_receipt.py");
const REPO_ROOT = path.resolve(__dirname, "../../../..");
const MAX_RECEIPT_BYTES = 8 * 1024 * 1024;
const RECEIPT_TIMEOUT_MS = 10_000;

// The prepared seed report names <dataRoot>/integrations/lark/skills.
function dataRootFromSeed(seed) {
  const packageDirectory = seed?.packageDirectory;
  if (typeof packageDirectory !== "string" || !path.isAbsolute(packageDirectory))
    throw new Error("Seed report must name an absolute prepared package directory");
  const resolved = path.resolve(packageDirectory);
  if (path.basename(resolved) !== "skills" ||
      path.basename(path.dirname(resolved)) !== "lark" ||
      path.basename(path.dirname(path.dirname(resolved))) !== "integrations")
    throw new Error("Seed package directory is not <dataRoot>/integrations/lark/skills");
  const dataRoot = path.dirname(path.dirname(path.dirname(resolved)));
  if (!fs.existsSync(resolved) || !fs.existsSync(path.join(dataRoot, "runtime")))
    throw new Error("Seed package directory or owned Runtime database is missing");
  return dataRoot;
}

// Raw SDK tool result from the owned Runtime database, never the public projection.
function readResourceReceipt(seed, options) {
  const { runId, callId, skill, resourcePath, expectedSha256 } = options;
  for (const [name, value] of Object.entries({ runId, callId, skill, resourcePath, expectedSha256 }))
    if (typeof value !== "string" || value.length === 0)
      throw new Error(`Resource receipt requires a non-empty ${name}`);
  const dataRoot = dataRootFromSeed(seed);
  const python = process.env.W20_PYTHON || "python";
  const args = [RECEIPT_FIXTURE, "--repo-root", REPO_ROOT, "--data-root", dataRoot,
    "--run-id", runId, "--call-id", callId, "--skill", skill,
    "--path", resourcePath, "--expected-sha256", expectedSha256];
  let stdout;
  try {
    stdout = execFileSync(python, args, { cwd: REPO_ROOT, encoding: "utf8",
      timeout: RECEIPT_TIMEOUT_MS, maxBuffer: MAX_RECEIPT_BYTES, windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    const detail = String(error?.stderr ?? "").trim().slice(0, 400);
    throw new Error(`Raw resource receipt failed closed: ${detail || error?.message || "unknown"}`);
  }
  const parsed = JSON.parse(stdout);
  if (parsed?.status !== "OK" || typeof parsed.content !== "string" || !parsed.receipt)
    throw new Error("Raw resource receipt returned an unexpected shape");
  return parsed;
}
