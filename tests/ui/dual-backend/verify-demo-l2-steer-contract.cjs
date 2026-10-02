"use strict";

const assert = require("node:assert/strict");
const { verifySteerExchange, verifySteerTerminal } = require("./demo-l2-steer-contract.cjs");

let assertions = 0;
const exchange = {
  requestUrl: "http://127.0.0.1:45618/api/threads/thread-1/runs/stream",
  requestMethod: "POST",
  requestHeaders: { "x-haifa-run-input": "steer", "content-type": "application/json" },
  requestBody: { input: { messages: [{ id: "steer-1", type: "human", content: "change direction" }] } },
  responseStatus: 200,
  responseHeaders: { "content-location": "/api/threads/thread-1/runs/run-1" },
  expectedThreadId: "thread-1",
  expectedRunId: "run-1",
  expectedInstruction: "change direction",
  existingMessageIds: ["initial-1"],
};

function pass(name, action) {
  action();
  assertions++;
}

function reject(name, mutate, pattern) {
  const candidate = structuredClone(exchange);
  mutate(candidate);
  assert.throws(() => verifySteerExchange(candidate), pattern, name);
  assertions++;
}

pass("direct endpoint", () => assert.equal(verifySteerExchange(exchange).messageId, "steer-1"));
pass("langgraph endpoint", () => assert.equal(
  verifySteerExchange({ ...exchange, requestUrl: "http://127.0.0.1:45618/api/langgraph/threads/thread-1/runs/stream" }).runId,
  "run-1",
));
reject("wrong endpoint", (value) => { value.requestUrl = "http://127.0.0.1:45618/api/other/threads/thread-1/runs/stream"; }, /Unexpected Steer endpoint/);
reject("missing header", (value) => { delete value.requestHeaders["x-haifa-run-input"]; }, /X-Haifa-Run-Input/);
reject("wrong header", (value) => { value.requestHeaders["x-haifa-run-input"] = "resume"; }, /X-Haifa-Run-Input/);
reject("wrong status", (value) => { value.responseStatus = 202; }, /must be 200/);
reject("wrong response thread", (value) => { value.responseHeaders["content-location"] = "/api/threads/thread-2/runs/run-1"; }, /targets thread/);
reject("wrong response run", (value) => { value.responseHeaders["content-location"] = "/api/threads/thread-1/runs/run-2"; }, /targets Run/);
reject("missing message id", (value) => { delete value.requestBody.input.messages[0].id; }, /non-empty id/);
reject("reused message id", (value) => { value.existingMessageIds = ["steer-1"]; }, /is not new/);
reject("multiple messages", (value) => { value.requestBody.input.messages.push({ id: "steer-2", type: "human", content: "extra" }); }, /exactly one new message/);

const terminal = {
  runStatus: "success",
  state: {
    values: {
      artifacts: ["/mnt/user-data/outputs/demo.txt"],
      messages: [
        { id: "tool-request", type: "ai", tool_calls: [{ id: "write-1", name: "write_file", args: { path: "/mnt/user-data/outputs/demo.txt" } }] },
        { id: "tool-result", type: "tool", name: "write_file", tool_call_id: "write-1", additional_kwargs: { haifa_tool_result: { artifactId: "artifact-1" } } },
        { id: "run-1-human-1", type: "human", content: "change direction" },
        { id: "ack-1", type: "ai", content: "DEMO-STEER-ACK-1" },
      ],
    },
  },
  expectedRunId: "run-1",
  expectedInstruction: "change direction",
  expectedAck: "DEMO-STEER-ACK-1",
  expectedArtifactPath: "/mnt/user-data/outputs/demo.txt",
  expectedArtifactId: "artifact-1",
  expectedToolCallId: "write-1",
};

pass("terminal success", () => verifySteerTerminal(terminal));
pass("missing terminal cannot pass", () => assert.throws(
  () => verifySteerTerminal({ ...terminal, runStatus: "running" }), /terminal success/,
));
pass("missing persisted steer cannot pass", () => assert.throws(
  () => verifySteerTerminal({ ...terminal, state: structuredClone({ values: { ...terminal.state.values, messages: terminal.state.values.messages.filter((message) => message.id !== "run-1-human-1") } }) }),
  /persisted Steer message/,
));
pass("wrong projected steer identity cannot pass", () => {
  const wrongIdentity = structuredClone(terminal);
  wrongIdentity.state.values.messages.find((message) => message.id === "run-1-human-1").id = "client-steer-1";
  assert.throws(() => verifySteerTerminal(wrongIdentity), /authoritative Run projection/);
});
pass("duplicate persisted steer content cannot pass", () => {
  const duplicate = structuredClone(terminal);
  duplicate.state.values.messages.push({ id: "run-1-human-2", type: "human", content: "change direction" });
  assert.throws(() => verifySteerTerminal(duplicate), /exactly one persisted Steer message/);
});
pass("missing acknowledgement cannot pass", () => assert.throws(
  () => verifySteerTerminal({ ...terminal, expectedAck: "DEMO-STEER-ACK-2" }), /acknowledgement/,
));
pass("duplicate write cannot pass", () => {
  const duplicate = structuredClone(terminal);
  duplicate.state.values.messages[0].tool_calls.push({ id: "write-2", name: "write_file", args: { path: terminal.expectedArtifactPath } });
  assert.throws(() => verifySteerTerminal(duplicate), /exactly once/);
});
pass("duplicate write result cannot pass", () => {
  const duplicate = structuredClone(terminal);
  duplicate.state.values.messages.push({ ...structuredClone(duplicate.state.values.messages[1]), id: "tool-result-2" });
  assert.throws(() => verifySteerTerminal(duplicate), /result must remain exactly once/);
});
pass("duplicate Artifact path cannot pass", () => {
  const duplicate = structuredClone(terminal);
  duplicate.state.values.artifacts.push(terminal.expectedArtifactPath);
  assert.throws(() => verifySteerTerminal(duplicate), /Artifact path side effect must remain exactly once/);
});

console.log(`W18_DEMO_L2_STEER_CONTRACT PASS assertions=${assertions}`);
