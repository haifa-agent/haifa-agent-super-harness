const assert = require("node:assert/strict");
const { test } = require("node:test");
const { observedValue } = require("../../ui/smoke/support/evaluation-report.cjs");

test("observedValue returns the fact value after the last equals sign", () => {
  for (const raw of ["17", "W15_CONTROLLED_UI_PACKAGE_FACT=17", " 17 \n", "A=B=17"]) {
    assert.equal(observedValue(raw), "17");
  }
});

test("observedValue rejects inputs that are not the exact fact value", () => {
  for (const raw of ["18", "FACT=170", "FACT=1 7", "", "17=x", 17, null, undefined]) {
    assert.notEqual(observedValue(raw), "17");
  }
});
