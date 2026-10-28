const assert = require("node:assert/strict");
const { test } = require("node:test");
const { decideLark } = require("../../ui/smoke/support/lark-office.cjs");

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
