const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { decideLark, dataRootFromSeed, readResourceReceipt } =
  require("../../ui/smoke/support/lark-office.cjs");

test("Lark driver cannot report success from a user prompt or absent tools", () => {
  const body = { messages: [{ role: "user", content: "lark-cli docs +fetch --scope keyword --detail with-ids" }] };
  assert.equal(decideLark(body, "probe", []).observed, false);
  assert.equal(decideLark(body, "probe", ["skill_load"]).action, "skill_load");
});
test("Lark driver reads only after an actual load tool reply", () => {
  const messages = [{ role: "assistant", tool_calls: [{ id: "load", function: { name: "skill_load" } }] },
    { role: "tool", tool_call_id: "load", content: "activated lark-cli docs +fetch" }];
  assert.equal(decideLark({ messages }, "probe", ["skill_resource_read"]).action, "skill_resource_read");
  assert.equal(decideLark({ messages }, "probe", []).observed, false);
  messages.push({ role: "assistant", tool_calls: [{ id: "read", function: { name: "skill_resource_read" } }] },
    { role: "tool", tool_call_id: "read", content: "--scope keyword --detail with-ids" });
  assert.equal(decideLark({ messages }, "probe", []).text, "probe OFFICIAL_LARK_DOC_LOADED");
});

test("Raw resource receipt binds only the prepared seed's owned data root", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "w20-lark-receipt-"));
  try {
    const skills = path.join(root, "integrations", "lark", "skills");
    fs.mkdirSync(skills, { recursive: true });
    fs.mkdirSync(path.join(root, "runtime"));
    assert.equal(dataRootFromSeed({ packageDirectory: skills }), root);
    assert.throws(() => dataRootFromSeed({ packageDirectory: "integrations/lark/skills" }),
      /absolute/);
    assert.throws(() => dataRootFromSeed({ packageDirectory: path.join(root, "skills") }),
      /integrations/);
    fs.rmSync(path.join(root, "runtime"), { recursive: true });
    assert.throws(() => dataRootFromSeed({ packageDirectory: skills }), /missing/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("Raw resource receipt fails closed before spawning on an absent seed", () => {
  const options = { runId: "r", callId: "c", skill: "lark-doc",
    resourcePath: "references/x.md", expectedSha256: "0".repeat(64) };
  assert.throws(() => readResourceReceipt({ packageDirectory: "relative" }, options), /absolute/);
  assert.throws(() => readResourceReceipt({}, options), /absolute/);
});
