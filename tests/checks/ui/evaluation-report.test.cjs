const assert = require("node:assert/strict");
const { test } = require("node:test");
const { observedValue } = require("../../ui/smoke/support/evaluation-report.cjs");

const RECORD = "W15_CONTROLLED_UI_PACKAGE_FACT=17\n";

test("observedValue accepts the bare value or the exact fact record", () => {
  for (const raw of ["17", " 17 \n", "W15_CONTROLLED_UI_PACKAGE_FACT=17", " W15_CONTROLLED_UI_PACKAGE_FACT=17\n"]) {
    assert.equal(observedValue(raw, RECORD), "17");
  }
});

test("observedValue rejects other prefixed, altered or non-string values", () => {
  for (const raw of ["18", "incorrect=17", "A=B=17", "FACT=17", "W15_CONTROLLED_UI_PACKAGE_FACT=170",
    "W15_CONTROLLED_UI_PACKAGE_FACT=1 7", "W15_CONTROLLED_UI_PACKAGE_FACT=17 extra", "", "17=x", 17, null, undefined]) {
    assert.notEqual(observedValue(raw, RECORD), "17");
  }
});

test("observedValue without a fact record only accepts the bare value", () => {
  assert.equal(observedValue("17"), "17");
  assert.notEqual(observedValue("W15_CONTROLLED_UI_PACKAGE_FACT=17"), "17");
});
