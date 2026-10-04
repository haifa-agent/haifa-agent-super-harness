const assert = require("node:assert/strict");
const crypto = require("node:crypto");

// Check the publication boundary for the actual pending call, including overwrites.
async function verifyPendingWrite({ request, app, identity, state, report }) {
  const messages = state.values.messages;
  const pending = messages.filter(message => message.run_id === identity.runId)
    .map(message => message.artifact?.human_input).filter(Boolean).at(-1);
  assert.ok(pending, "current Run must have its approval request");
  const call = messages.flatMap(message => message.tool_calls ?? [])
    .find(candidate => candidate.id === pending.tool_call_id);
  assert.ok(call, "approval must identify its actual Tool call");
  if (call.name !== "write_file" || call.args.path !== report) return { toolCallId: call.id, checked: false };
  const published = messages.filter(message => message.type === "tool" && message.name === "write_file" &&
    message.additional_kwargs?.haifa_tool_status === "completed" &&
    message.additional_kwargs?.haifa_tool_result?.path === report &&
    message.additional_kwargs?.haifa_tool_result?.artifactId).at(-1)?.additional_kwargs.haifa_tool_result;
  const response = await request.get(`${app}/api/threads/${identity.threadId}/artifacts${report}`, { timeout: 10_000 });
  if (!published) {
    assert.equal(response.status(), 404, "file must not exist before its first successful write approval");
    assert.ok(!state.values.artifacts.includes(report), "first pending write must not publish an Artifact");
    return { toolCallId: call.id, checked: true, publication: "absent" };
  }
  assert.equal(response.status(), 200, "previously published file must remain readable during overwrite approval");
  const bytes = await response.body();
  const sha256 = `sha256:${crypto.createHash("sha256").update(bytes).digest("hex")}`;
  assert.equal(sha256, published.artifactSha256, "pending overwrite must leave the last published bytes unchanged");
  assert.equal(bytes.length, published.artifactByteCount, "pending overwrite must preserve the published byte count");
  assert.ok(state.values.artifacts.includes(report), "previously published Artifact must remain listed");
  return { toolCallId: call.id, checked: true, publication: "unchanged", artifactId: published.artifactId, sha256 };
}

module.exports = { verifyPendingWrite };
