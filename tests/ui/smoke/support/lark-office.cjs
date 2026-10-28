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

module.exports = { decideLark };
