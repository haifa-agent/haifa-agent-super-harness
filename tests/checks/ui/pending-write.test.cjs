const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const http = require("node:http");
const { test } = require("node:test");
const { verifyPendingWrite } = require("../../ui/smoke/support/pending-write.cjs");

const report = "/mnt/user-data/outputs/evaluation.json";
const identity = { threadId: "same-thread", runId: "second-run" };
const original = Buffer.from('{"observed":"17"}\n');
const request = { get: async (url, options) => {
  const response = await fetch(url, { signal: AbortSignal.timeout(options.timeout) });
  return { status: () => response.status, body: async () => Buffer.from(await response.arrayBuffer()) };
} };

function pendingState(published) {
  const messages = [
    { run_id: identity.runId, tool_calls: [{ id: "pending-write", name: "write_file", args: { path: report } }] },
    { run_id: identity.runId, artifact: { human_input: { tool_call_id: "pending-write" } } },
  ];
  if (published) messages.unshift({ type: "tool", name: "write_file", additional_kwargs: {
    haifa_tool_status: "completed", haifa_tool_result: { path: report, artifactId: "original-artifact",
      artifactSha256: `sha256:${crypto.createHash("sha256").update(original).digest("hex")}`,
      artifactByteCount: original.length },
  } });
  return { values: { messages, artifacts: published ? [report] : [] } };
}

async function observe(status, bytes, published, assertion) {
  const server = http.createServer((req, res) => {
    assert.equal(req.url, `/api/threads/${identity.threadId}/artifacts${report}`);
    res.writeHead(status);
    res.end(bytes);
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
    await assertion(verifyPendingWrite({ request, app: `http://127.0.0.1:${server.address().port}`,
      identity, state: pendingState(published), report }));
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
}

test("first pending write requires an unpublished file", async () => {
  await observe(404, "", false, async result => assert.equal((await result).publication, "absent"));
});
test("first pending write rejects premature publication", async () => {
  await observe(200, original, false, result => assert.rejects(result, /must not exist/));
});
test("second approval accepts the unchanged previously published file", async () => {
  await observe(200, original, true, async result => assert.equal((await result).publication, "unchanged"));
});
test("second approval rejects an unapproved overwrite", async () => {
  await observe(200, '{"observed":"99"}\n', true, result => assert.rejects(result, /bytes unchanged/));
});
