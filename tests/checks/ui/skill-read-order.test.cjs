const assert = require("node:assert/strict");
const { test } = require("node:test");
const { verifySkillReads } = require("../../ui/smoke/support/skill-read-order.cjs");

const REQUIRED = ["references/fact.txt", "evals/evals.json"];

function load(activated = true) {
  return { type: "tool", name: "skill_load", additional_kwargs: {
    haifa_tool_status: "completed", haifa_tool_result: { activated } } };
}
function read(path, content = "FACT=17") {
  return { type: "tool", name: "skill_resource_read", additional_kwargs: {
    haifa_tool_status: "completed", haifa_tool_result: { path, content } } };
}
function rejectedRead(path = "references/fact.txt") {
  return { type: "tool", name: "skill_resource_read", additional_kwargs: {
    haifa_tool_status: "failed", haifa_tool_result: { path, failureCode: "SKILL_NOT_ACTIVATED" } } };
}
function failedRead(path, failureCode) {
  return { type: "tool", name: "skill_resource_read", additional_kwargs: {
    haifa_tool_status: "failed", haifa_tool_result: { path, failureCode } } };
}

test("accepts load followed by every required read", () => {
  const result = verifySkillReads([load(), read("references/fact.txt"), read("evals/evals.json")], REQUIRED);
  assert.equal(result.ok, true, result.reason);
  assert.equal(result.preActivationRejectedReads, 0);
  assert.deepEqual(result.completedPaths, REQUIRED);
});

test("accepts a pre-activation rejection that is then repaired", () => {
  const result = verifySkillReads([rejectedRead(), load(), read("references/fact.txt"), read("evals/evals.json")], REQUIRED);
  assert.equal(result.ok, true, result.reason);
  assert.equal(result.preActivationRejectedReads, 1);
});

test("rejects a run with no activated load", () => {
  const result = verifySkillReads([read("references/fact.txt"), read("evals/evals.json")], REQUIRED);
  assert.equal(result.ok, false);
});

test("rejects a successful read before activation", () => {
  const result = verifySkillReads([read("references/fact.txt"), load(), read("evals/evals.json")], REQUIRED);
  assert.equal(result.ok, false);
});

test("rejects a pre-activation failure with another code", () => {
  const result = verifySkillReads([failedRead("references/fact.txt", "OTHER"), load(),
    read("references/fact.txt"), read("evals/evals.json")], REQUIRED);
  assert.equal(result.ok, false);
});

test("rejects a failed read after activation", () => {
  const result = verifySkillReads([load(), failedRead("references/fact.txt", "SKILL_NOT_ACTIVATED"),
    read("evals/evals.json")], REQUIRED);
  assert.equal(result.ok, false);
});

test("rejects a run that misses a required path after activation", () => {
  const result = verifySkillReads([load(), read("references/fact.txt")], REQUIRED);
  assert.equal(result.ok, false);
});
