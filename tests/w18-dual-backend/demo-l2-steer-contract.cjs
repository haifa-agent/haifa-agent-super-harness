"use strict";

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function header(headers, name) {
  const wanted = name.toLowerCase();
  const entry = Object.entries(headers ?? {}).find(([key]) => key.toLowerCase() === wanted);
  return entry?.[1] ?? null;
}

function contentText(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content.map((part) => typeof part === "string" ? part : (part?.text ?? "")).join("");
  }
  return content == null ? "" : JSON.stringify(content);
}

function verifySteerExchange({
  requestUrl,
  requestMethod,
  requestHeaders,
  requestBody,
  responseStatus,
  responseHeaders,
  expectedThreadId,
  expectedRunId,
  expectedInstruction,
  existingMessageIds = [],
}) {
  requireCondition(requestMethod === "POST", `Steer request must be POST, got ${requestMethod}`);
  const pathname = new URL(requestUrl, "http://w18.invalid").pathname;
  const endpoint = pathname.match(/^\/api\/(?:langgraph\/)?threads\/([^/]+)\/runs\/stream$/);
  requireCondition(endpoint, `Unexpected Steer endpoint: ${pathname}`);
  requireCondition(endpoint[1] === expectedThreadId, `Steer endpoint targets thread ${endpoint[1]}, expected ${expectedThreadId}`);
  requireCondition(
    header(requestHeaders, "x-haifa-run-input") === "steer",
    "Steer request must carry X-Haifa-Run-Input: steer",
  );
  requireCondition(responseStatus === 200, `Steer response must be 200, got ${responseStatus}`);

  const location = header(responseHeaders, "content-location");
  const identity = location?.match(/^\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  requireCondition(identity, `Invalid Steer Content-Location: ${location}`);
  requireCondition(identity[1] === expectedThreadId, `Steer response targets thread ${identity[1]}, expected ${expectedThreadId}`);
  requireCondition(identity[2] === expectedRunId, `Steer response targets Run ${identity[2]}, expected ${expectedRunId}`);

  let body;
  try {
    body = typeof requestBody === "string" ? JSON.parse(requestBody) : requestBody;
  } catch (error) {
    throw new Error(`Steer request body is not JSON: ${error.message}`);
  }
  const messages = body?.input?.messages;
  requireCondition(Array.isArray(messages) && messages.length === 1, "Steer body must contain exactly one new message");
  const message = messages[0];
  requireCondition(message?.type === "human", `Steer message must be human, got ${message?.type}`);
  requireCondition(typeof message.id === "string" && message.id.trim() !== "", "Steer human message must have a non-empty id");
  requireCondition(!existingMessageIds.includes(message.id), `Steer human message id is not new: ${message.id}`);
  requireCondition(contentText(message.content) === expectedInstruction, "Steer human message content drifted");

  return { endpointPath: pathname, location, threadId: identity[1], runId: identity[2], messageId: message.id };
}

function verifySteerTerminal({
  runStatus,
  state,
  expectedRunId,
  expectedInstruction,
  expectedAck,
  expectedArtifactPath,
  expectedArtifactId,
  expectedToolCallId,
}) {
  requireCondition(runStatus === "success", `Steered Run must reach terminal success, got ${runStatus}`);
  const messages = state?.values?.messages;
  requireCondition(Array.isArray(messages), "Authoritative state messages are unavailable");

  const steerMessages = messages.filter(
    (message) => message?.type === "human" && contentText(message.content) === expectedInstruction,
  );
  requireCondition(steerMessages.length === 1, `Expected exactly one persisted Steer message, got ${steerMessages.length}`);
  requireCondition(steerMessages[0].type === "human", "Persisted Steer message is not human");
  requireCondition(
    steerMessages[0].id === `${expectedRunId}-human-1`,
    `Persisted Steer identity must be the authoritative Run projection, got ${steerMessages[0].id}`,
  );
  const acknowledgements = messages.filter(
    (message) => message?.type === "ai" && contentText(message.content).includes(expectedAck),
  );
  requireCondition(acknowledgements.length === 1, `Expected exactly one persisted Steer acknowledgement, got ${acknowledgements.length}`);

  const writeCalls = messages.flatMap((message) => message?.tool_calls ?? [])
    .filter((call) => call?.name === "write_file" && call?.args?.path === expectedArtifactPath);
  requireCondition(writeCalls.length === 1, `write_file side effect must remain exactly once, got ${writeCalls.length}`);
  requireCondition(writeCalls[0].id === expectedToolCallId, "write_file Tool call identity changed after Steer");
  const writeResults = messages.filter(
    (message) => message?.type === "tool" && message?.name === "write_file" && message?.tool_call_id === expectedToolCallId,
  );
  requireCondition(writeResults.length === 1, `write_file result must remain exactly once, got ${writeResults.length}`);
  requireCondition(
    writeResults[0].additional_kwargs?.haifa_tool_result?.artifactId === expectedArtifactId,
    "Artifact provenance changed after Steer",
  );
  const artifactPaths = state?.values?.artifacts ?? [];
  requireCondition(
    artifactPaths.filter((candidate) => candidate === expectedArtifactPath).length === 1,
    "Artifact path side effect must remain exactly once",
  );

  return { steerMessage: steerMessages[0], acknowledgement: acknowledgements[0], writeResult: writeResults[0] };
}

module.exports = { verifySteerExchange, verifySteerTerminal };
