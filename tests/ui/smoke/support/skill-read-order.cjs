// Validate one Run's own Skill message order under the D7 relaxation: a resource
// read rejected before activation (SKILL_NOT_ACTIVATED) is tolerated, but the
// Run must activate the expected Skill and then successfully read every required
// path from that same Skill. Results for other Skills never satisfy a requirement.
function verifySkillReads(messages, requiredPaths, skillName) {
  const results = (messages ?? []).filter(message => message?.type === "tool");
  const toolResult = message => message.additional_kwargs?.haifa_tool_result;
  const loadIndex = results.findIndex(message => message.name === "skill_load" &&
    toolResult(message)?.activated === true && toolResult(message)?.skill === skillName);
  if (loadIndex < 0) {
    return { ok: false, reason: `no skill_load tool result reports activated=true for ${skillName}`,
      preActivationRejectedReads: 0, completedPaths: [] };
  }
  let preActivationRejectedReads = 0;
  for (let index = 0; index < loadIndex; index++) {
    const message = results[index];
    if (message.name !== "skill_resource_read") continue;
    const kwargs = message.additional_kwargs ?? {};
    if (kwargs.haifa_tool_status === "failed" && kwargs.haifa_tool_result?.failureCode === "SKILL_NOT_ACTIVATED") {
      preActivationRejectedReads++;
      continue;
    }
    return { ok: false, reason: `pre-activation resource read at index ${index} was not a SKILL_NOT_ACTIVATED rejection`,
      preActivationRejectedReads, completedPaths: [] };
  }
  const completedPaths = [];
  for (let index = loadIndex + 1; index < results.length; index++) {
    const message = results[index];
    if (message.name !== "skill_resource_read") continue;
    const kwargs = message.additional_kwargs ?? {};
    if (kwargs.haifa_tool_status !== "completed" || typeof kwargs.haifa_tool_result?.content !== "string") {
      return { ok: false, reason: `resource read at index ${index} did not complete after activation`,
        preActivationRejectedReads, completedPaths };
    }
    if (kwargs.haifa_tool_result.skill === skillName) completedPaths.push(kwargs.haifa_tool_result.path);
  }
  for (const required of requiredPaths ?? []) {
    if (!completedPaths.includes(required)) {
      return { ok: false, reason: `required path ${required} was not read from ${skillName} after activation`,
        preActivationRejectedReads, completedPaths };
    }
  }
  return { ok: true, reason: "every resource read followed activation", preActivationRejectedReads, completedPaths };
}

module.exports = { verifySkillReads };
