const assert = require("node:assert/strict");
const { test } = require("node:test");
const { verifySkillReads } = require("../../ui/smoke/support/skill-read-order.cjs");

const REQUIRED = ["references/fact.txt", "evals/evals.json"];
const SKILL = "w15-package-ui";

function load(activated = true, skill = SKILL) {
  return { type: "tool", name: "skill_load", additional_kwargs: {
    haifa_tool_status: "completed", haifa_tool_result: { activated, skill } } };
}
function read(path, skill = SKILL, content = "FACT=17") {
  return { type: "tool", name: "skill_resource_read", additional_kwargs: {
    haifa_tool_status: "completed", haifa_tool_result: { path, content, skill } } };
}
function rejectedRead(path = "references/fact.txt") {
  return { type: "tool", name: "skill_resource_read", additional_kwargs: {
    haifa_tool_status: "failed", haifa_tool_result: { skill: SKILL, failureCode: "SKILL_NOT_ACTIVATED" } } };
}
function failedRead(path, failureCode) {
  return { type: "tool", name: "skill_resource_read", additional_kwargs: {
    haifa_tool_status: "failed", haifa_tool_result: { path, failureCode, skill: SKILL } } };
}

test("accepts load followed by every required read", () => {
  const result = verifySkillReads([load(), read("references/fact.txt"), read("evals/evals.json")], REQUIRED, SKILL);
  assert.equal(result.ok, true, result.reason);
  assert.equal(result.preActivationRejectedReads, 0);
  assert.deepEqual(result.completedPaths, REQUIRED);
});

test("accepts a pre-activation rejection that is then repaired", () => {
  const result = verifySkillReads([rejectedRead(), load(), read("references/fact.txt"), read("evals/evals.json")], REQUIRED, SKILL);
  assert.equal(result.ok, true, result.reason);
  assert.equal(result.preActivationRejectedReads, 1);
});

test("rejects a run with no activated load", () => {
  const result = verifySkillReads([read("references/fact.txt"), read("evals/evals.json")], REQUIRED, SKILL);
  assert.equal(result.ok, false);
});

test("rejects a successful read before activation", () => {
  const result = verifySkillReads([read("references/fact.txt"), load(), read("evals/evals.json")], REQUIRED, SKILL);
  assert.equal(result.ok, false);
});

test("rejects a pre-activation failure with another code", () => {
  const result = verifySkillReads([failedRead("references/fact.txt", "OTHER"), load(),
    read("references/fact.txt"), read("evals/evals.json")], REQUIRED, SKILL);
  assert.equal(result.ok, false);
});

test("rejects a failed read after activation", () => {
  const result = verifySkillReads([load(), failedRead("references/fact.txt", "SKILL_NOT_ACTIVATED"),
    read("evals/evals.json")], REQUIRED, SKILL);
  assert.equal(result.ok, false);
});

test("rejects a run that misses a required path after activation", () => {
  const result = verifySkillReads([load(), read("references/fact.txt")], REQUIRED, SKILL);
  assert.equal(result.ok, false);
});

test("rejects required paths that were read from another Skill", () => {
  const result = verifySkillReads([load(), read("references/fact.txt", "other-skill"), read("evals/evals.json", "other-skill")],
    REQUIRED, SKILL);
  assert.equal(result.ok, false);
  assert.match(result.reason, /was not read from w15-package-ui/);
  assert.deepEqual(result.completedPaths, []);
});

test("rejects activation of another Skill only", () => {
  const result = verifySkillReads([load(true, "other-skill"), read("references/fact.txt"), read("evals/evals.json")],
    REQUIRED, SKILL);
  assert.equal(result.ok, false);
});

test("ignores another Skill's reads when the expected Skill supplied every path", () => {
  const result = verifySkillReads([load(true, "other-skill"), load(), read("references/fact.txt", "other-skill"),
    read("references/fact.txt"), read("evals/evals.json")], REQUIRED, SKILL);
  assert.equal(result.ok, true, result.reason);
  assert.deepEqual(result.completedPaths, REQUIRED);
});
