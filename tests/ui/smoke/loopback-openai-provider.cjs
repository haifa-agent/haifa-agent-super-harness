const http = require("node:http");
const fs = require("node:fs");
const crypto = require("node:crypto");

const port = Number.parseInt(process.argv[2] ?? "0", 10);
const readyFile = process.argv[3];
if (!Number.isInteger(port) || port < 0 || port > 65535) {
  throw new Error("provider port must be between 0 and 65535");
}
if (!readyFile) {
  throw new Error("provider ready metadata path is required");
}

function sendJson(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(body),
  });
  response.end(body);
}

function textOfContent(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .map((part) =>
      part && typeof part === "object" && typeof part.text === "string"
        ? part.text
        : "",
    )
    .join("");
}

function lastUserText(body) {
  if (!Array.isArray(body.messages)) return "";
  return body.messages
    .filter((message) => message?.role === "user")
    .map((message) => textOfContent(message.content))
    .at(-1) ?? "";
}

function syntheticUserText(raw) {
  // The frozen DeerFlow graph frames the user message before invoking the
  // model. Preserve that raw input in the provider log while returning the
  // same deterministic fixture answer for both backend combinations.
  const framed = /^--- BEGIN USER INPUT ---\r?\n([\s\S]*?)\r?\n--- END USER INPUT ---$/.exec(raw.trim());
  return framed ? framed[1] : raw;
}

function toolNames(body) {
  if (!Array.isArray(body.tools)) return [];
  return body.tools
    .map((tool) => tool?.function?.name)
    .filter((name) => typeof name === "string");
}

// The frozen DeerFlow follow-up route sends a one-shot non-streaming request whose
// system instruction is boilerplate about "follow-up questions"; answer it with a
// deterministic JSON array so the frozen UI can render the suggestions verbatim.
function isSuggestionsRequest(body) {
  if (!Array.isArray(body.messages)) return false;
  return body.messages.some(
    (message) =>
      message?.role === "system" &&
      typeof textOfContent(message.content) === "string" &&
      textOfContent(message.content).includes("follow-up questions"),
  );
}

const SUGGESTIONS = ["What changed?", "Why now?", "What next?"];

// ---- W03 Input Polish controlled fixture -------------------------------------
// The frozen UI sends {text, locale?, thread_id?} to the same-origin gateway,
// which performs one bounded non-streaming OpenAI-compatible call whose system
// instruction is the "pre-send prompt optimizer". Answer deterministically based
// on a marker embedded in the draft so the spec can drive both the happy path and
// the error boundaries without ever mocking the page or the protocol.
const POLISH_REWRITE =
  "Polished camera brief: buy a camera; state the goal, scope, constraints, and expected output.";
const POLISH_THINK_TEXT = "Polished camera brief from think.";
const POLISH_FENCE_TEXT = "Polished camera brief from fence.";

const polishCalls = [];
const chatCalls = [];

// ---- W08 Child delegation controlled fixture ---------------------------------
// The unmodified frozen frontend is driven against the real Haifa backend in Ultra
// mode (the spec shapes only the /runs/stream context, never the response stream).
// Parent and Child requests are distinguished by stable scenario inputs: the Child
// run carries this managed system-prompt marker; the Parent carries the `task` Tool
// actually declared by the backend. One ordered list of Tool calls is emitted per
// model round, never one message per call.
const W08_CHILD_MARK = "W08-CHILD-MARKER";
const W08_CHILD_AGENT = "w08-child";
const w08Calls = [];

function blockW08Ui10Gate(response, objective, id) {
  console.log(
    JSON.stringify({
      type: "w08_ui10_gate",
      id,
      objective,
      state: "waiting",
    }),
  );
  return new Promise((resolve) => {
    let settled = false;
    let timer = null;
    const finish = (state) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if (response) response.removeListener("close", onClose);
      console.log(
        JSON.stringify({
          type: "w08_ui10_gate",
          id,
          objective,
          state,
        }),
      );
      resolve(state);
    };
    const onClose = () => finish("closed");
    timer = setTimeout(() => finish("timeout"), 90_000);
    if (response) {
      response.once("close", onClose);
    }
  });
}

const W08_TOOLS_CHILD_AGENT = "w08-child-tools";
const W08_MISSING_CHILD_AGENT = "w08-missing-child";
const W08_UI11_CHILD_AGENT = "w08-ui11-child";
const W08_UI15_CHILD_AGENT = "w08-ui15-child";
const W08_UI14_LONG_OBJECTIVE = `W08-UI14-OBJECTIVE-${"LONG".repeat(60)}`;

const w08Gates = new Map();

function waitForGate(name, response, timeoutMs = 90_000) {
  return new Promise((resolve) => {
    let settled = false;
    const settle = (reason) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const waiters = w08Gates.get(name);
      if (waiters) {
        waiters.delete(settle);
        if (waiters.size === 0) w08Gates.delete(name);
      }
      console.log(JSON.stringify({ type: "w08_gate", name, reason }));
      resolve(reason);
    };
    const timer = setTimeout(() => settle("timeout"), timeoutMs);
    if (!w08Gates.has(name)) w08Gates.set(name, new Set());
    w08Gates.get(name).add(settle);
    console.log(JSON.stringify({ type: "w08_gate_wait", name }));
    if (response) response.on("close", () => settle("closed"));
  });
}

function releaseW08Gate(name) {
  const waiters = w08Gates.get(name);
  if (!waiters || waiters.size === 0) return 0;
  const released = waiters.size;
  for (const settle of [...waiters]) settle("released");
  return released;
}

function finishWithAssistantToolCall(response, id, text, callId, name, args) {
  frame(response, {
    id,
    model: "w18-controlled-model",
    choices: [{ index: 0, delta: { content: text } }],
  });
  frame(response, {
    id,
    model: "w18-controlled-model",
    choices: [
      {
        index: 0,
        delta: {
          tool_calls: [
            {
              index: 0,
              id: callId,
              type: "function",
              function: { name, arguments: JSON.stringify(args) },
            },
          ],
        },
        finish_reason: "tool_calls",
      },
    ],
  });
  frame(response, { id, model: "w18-controlled-model", choices: [], usage: { prompt_tokens: 5, completion_tokens: 3 } });
  frame(response, "[DONE]");
  response.end();
}

function w08Objective(body) {
  return /W08-UI\d+[A-Z0-9-]*OBJECTIVE[A-Z0-9-]*/.exec(messagesText(body))?.[0] ?? null;
}

function exposedSubagents(body) {
  // Parse real prompt text, not JSON-escaped newlines in serialized messages.
  const text = (body.messages ?? []).map((message) => textOfContent(message.content)).join("\n");
  const block = /<available_subagents>([\s\S]*?)<\/available_subagents>/.exec(text)?.[1];
  if (block !== undefined) {
    return [...block.matchAll(/<name>([^<]*)<\/name>/g)].map((match) => match[1]);
  }
  const referenceBlock = /<subagent_system>([\s\S]*?)<\/subagent_system>/.exec(text)?.[1] ?? "";
  const available = /\*\*Available Subagents:\*\*\s*([\s\S]*?)\*\*Delegation workflow:\*\*/.exec(referenceBlock)?.[1] ?? "";
  return [...available.matchAll(/^- \*\*([^*\r\n]+)\*\*:/gm)].map((match) => match[1]);
}

function taskArguments(body, agent, objective) {
  if (!Array.isArray(body.tools)) return { agent, objective };
  const task = body.tools.find((tool) => tool?.function?.name === "task");
  const properties = task?.function?.parameters?.properties ?? null;
  if (properties && "subagent_type" in properties && "prompt" in properties) {
    return { subagent_type: agent, prompt: objective };
  }
  return { agent, objective };
}

function lastToolText(body) {
  if (!Array.isArray(body.messages)) return "";
  const tool = [...body.messages].reverse().find((message) => message?.role === "tool");
  return tool ? v2ToolText(tool.content) : "";
}


// ---- W05 live-rendering controlled fixture -----------------------------------
// Three bounded scenes keep a Run deliberately in flight so the spec can assert
// what the frozen UI renders *during* the run. Each scene blocks the model call
// on an explicit release gate (POST /__release) with a hard timeout, never a
// fixed sleep; the gate is released by the spec or when the client disconnects.
const W05_LIVE_FIRST = "W05-LIVE02-FIRST-HALF";
const W05_LIVE_SECOND = "W05-LIVE02-SECOND-HALF";
const W05_LIVE_BLOCK_TIMEOUT_MS = 30_000;
const w05LiveCalls = [];
const w05LiveReleases = [];

function releaseW05Live() {
  let released = 0;
  while (w05LiveReleases.length > 0) {
    w05LiveReleases.shift()();
    released += 1;
  }
  return released;
}

// Resolves when the spec calls /__release, when the bounded timeout expires, or
// when the client disconnects. The timer is always cleared so a release cannot
// leak a pending handle into a later scene.
function blockW05Live(response, record) {
  record.blocked = true;
  return new Promise((resolve) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      record.blocked = false;
      resolve();
    };
    const timer = setTimeout(finish, W05_LIVE_BLOCK_TIMEOUT_MS);
    w05LiveReleases.push(() => {
      clearTimeout(timer);
      finish();
    });
    if (response) {
      response.on("close", () => {
        clearTimeout(timer);
        finish();
      });
    }
  });
}

function w05LiveMarker(body) {
  return /w05-live-(01|02|03|04)-[A-Za-z0-9-]+/.exec(messagesText(body));
}

// Returns true when the request belongs to this fixture and was fully handled.
async function handleW05LiveRendering(response, body) {
  const match = w05LiveMarker(body);
  if (!match) return false;
  const scenario = match[1];
  const marker = match[0];
  const id = `w05-live-${scenario}-${Date.now()}`;
  const record = {
    type: "w05_live",
    scenario,
    marker,
    at: Date.now(),
    stream: body.stream === true,
    toolResults: toolResultCount(body),
    offeredListUploads: toolNames(body).includes("list_uploaded_files"),
    child: isW08Child(body),
    objective: /W05-LIVE04-OBJECTIVE[^\s"\\]*/.exec(messagesText(body))?.[0] ?? null,
  };
  w05LiveCalls.push(record);
  console.log(JSON.stringify(record));
  if (scenario === "04") {
    // Parent asks the delegated Child to run; the Child's own model call blocks so
    // the subtask card must already be visible in progress during the hold.
    if (record.child) {
      await blockW05Live(response, record);
      completeW08Text(response, body, id, `W08-CHILD-RESULT:${record.objective ?? marker}`);
      return true;
    }
    if (record.toolResults === 0) {
      const taskCallId = `w05-live04-${Date.now()}`;
      record.taskCallId = taskCallId;
      finishWithTaskCalls(response, id, [
        {
          callId: taskCallId,
          name: "task",
          args: { agent: W08_CHILD_AGENT, objective: `W05-LIVE04-OBJECTIVE ${marker}` },
        },
      ]);
      return true;
    }
    completeW08Text(response, body, id, `W05-LIVE04-DONE ${marker}`);
    return true;
  }
  if (scenario === "02" && body.stream === true) {
    // Stream a recognisable first half, then hold the open SSE response before
    // the second half so the page snapshot can be inspected mid-run.
    sseHeaders(response);
    frame(response, completionChunk(id, `${W05_LIVE_FIRST} `, null));
    await blockW05Live(response, record);
    record.blocked = false;
    frame(response, completionChunk(id, `${W05_LIVE_SECOND} ${marker}`, "stop"));
    frame(response, { id, model: "w18-controlled-model", choices: [], usage: { prompt_tokens: 6, completion_tokens: 8 } });
    frame(response, "[DONE]");
    response.end();
    return true;
  }
  if (scenario === "03") {
    if (record.toolResults === 0 && record.offeredListUploads) {
      finishWithToolCall(response, id, `w05-live03-${Date.now()}`, "list_uploaded_files", {});
      return true;
    }
    // Hold the post-Tool model call so the Tool step must already be visible.
    await blockW05Live(response, record);
    completeW08Text(response, body, id, `W05-LIVE03-DONE ${marker}`);
    return true;
  }
  // LIVE-01: hold the model call so the Run stays in flight while the page is
  // asserted; the answer is irrelevant to the assertion.
  await blockW05Live(response, record);
  completeW08Text(response, body, id, `W05-LIVE01-DONE ${marker}`);
  return true;
}

function messagesText(body) {
  return JSON.stringify(body.messages ?? []);
}

function isW08Child(body) {
  return messagesText(body).includes(W08_CHILD_MARK);
}

function isInputPolishRequest(body) {
  if (!Array.isArray(body.messages)) return false;
  return body.messages.some(
    (message) =>
      message?.role === "system" &&
      textOfContent(message.content).includes("pre-send prompt optimizer"),
  );
}

function polishContentFor(draft) {
  if (draft.includes("__polish_unchanged__")) return draft;
  if (draft.includes("__polish_empty__")) return "";
  if (draft.includes("__polish_think__")) {
    return `<think>PRIVATE_REASONING_DO_NOT_LEAK</think> ${POLISH_THINK_TEXT}`;
  }
  if (draft.includes("__polish_fence__")) {
    return `\`\`\`\n${POLISH_FENCE_TEXT}\n\`\`\``;
  }
  return POLISH_REWRITE;
}

function handleInputPolish(request, response, body, prompt) {
  const draftMatch = /<draft>\r?\n([\s\S]*?)\r?\n<\/draft>/.exec(prompt);
  const localeMatch = /^Locale hint: (.*)$/m.exec(prompt);
  const draft = draftMatch ? draftMatch[1] : "";
  // Distinguish "field absent" from "field present but null": the flash/non-thinking
  // path must not send reasoning_effort at all, so the spec asserts presence === false.
  const reasoningEffortPresent = Object.prototype.hasOwnProperty.call(
    body,
    "reasoning_effort",
  );
  const record = {
    draft,
    locale: localeMatch ? localeMatch[1].trim() : null,
    model: body.model ?? null,
    stream: body.stream === true,
    reasoningEffort: reasoningEffortPresent ? body.reasoning_effort : null,
    reasoningEffortPresent,
    offeredTools: toolNames(body),
  };
  polishCalls.push(record);
  console.log(JSON.stringify({ type: "provider_input_polish", ...record }));

  if (draft.includes("__polish_fail__")) {
    // Non-2xx with a body the gateway must never surface to the browser.
    sendJson(response, 500, { error: { message: "PROVIDER_INTERNAL_SECRET_BODY" } });
    return;
  }

  const content = polishContentFor(draft);
  const deliver = () => {
    if (response.writableEnded || response.destroyed) return;
    if (body.stream === true) {
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache",
        connection: "keep-alive",
      });
      frame(response, completionChunk("w03-polish", content, "stop"));
      frame(response, {
        id: "w03-polish",
        model: "w18-controlled-model",
        choices: [],
        usage: { prompt_tokens: 4, completion_tokens: 3 },
      });
      frame(response, "[DONE]");
      response.end();
      return;
    }
    sendJson(response, 200, {
      id: `w03-polish-${Date.now()}`,
      object: "chat.completion",
      created: Math.floor(Date.now() / 1000),
      model: "w18-controlled-model",
      choices: [
        {
          index: 0,
          message: { role: "assistant", content },
          finish_reason: "stop",
        },
      ],
      usage: { prompt_tokens: 4, completion_tokens: 3, total_tokens: 7 },
    });
  };

  if (draft.includes("__polish_slow__")) {
    const timer = setTimeout(deliver, 4000);
    response.on("close", () => clearTimeout(timer));
    return;
  }
  deliver();
}

function hasToolResult(body) {
  return (
    Array.isArray(body.messages) &&
    body.messages.some((message) => message?.role === "tool")
  );
}

function toolResultCount(body) {
  if (!Array.isArray(body.messages)) return 0;
  return body.messages.filter((message) => message?.role === "tool").length;
}

// W18 differential: which upload paths reached the model context, independent
// of how each backend frames them.
function uploadPaths(body) {
  const text = JSON.stringify(body.messages ?? []);
  return [...new Set(text.match(/\/mnt\/user-data\/uploads\/[A-Za-z0-9._-]+/g) ?? [])];
}

// Haifa hands the model a JSON Tool envelope whose summary is the Tool text;
// the frozen DeerFlow Tool returns the text directly.
function v2ToolText(content) {
  const text = textOfContent(content);
  try {
    const envelope = JSON.parse(text);
    if (envelope && typeof envelope.summary === "string") return envelope.summary;
  } catch {
    // plain-text Tool result
  }
  return text;
}

function v2CsvAnswer(toolText) {
  if (/^Error:/.test(toolText)) return `FILE_UNREADABLE ${toolText.split(/\r?\n/)[0]}`;
  const lines = toolText.split(/\r?\n/);
  const header = lines.findIndex((line) => line.trim() === "customer_id,region,amount_cny");
  if (header < 0) return "FILE_UNREADABLE no CSV header";
  const rows = lines.slice(header + 1).filter((line) => line.trim() !== "").map((line) => line.trim().split(","));
  const totals = {};
  let total = 0;
  let high = 0;
  for (const [, region, raw] of rows) {
    const amount = Number.parseInt(raw, 10);
    totals[region] = (totals[region] ?? 0) + amount;
    total += amount;
    if (amount >= 1500) high += 1;
  }
  const row17 = rows.length >= 17 ? rows[16][0] : "missing";
  return `row17=${row17} east=${totals.East ?? 0} west=${totals.West ?? 0} total=${total} high=${high} rows=${rows.length}`;
}

// Both backends return the CSV bytes through the production read_file Tool,
// but only Haifa's Tool envelope advertises a sha256= field. Derive the same
// digest from the CSV lines actually seen by the Provider on either backend;
// never open the fixture or an upload path in this test Provider.
function v2CsvToolResultSha256(toolText) {
  const lines = toolText.split(/\r?\n/);
  const header = lines.findIndex((line) => line.trim() === "customer_id,region,amount_cny");
  if (header < 0) return null;
  const csvLines = lines.slice(header).filter((line) => line.trim() !== "").map((line) => line.trim());
  return crypto.createHash("sha256").update(`${csvLines.join("\n")}\n`, "utf8").digest("hex");
}

function firstUserMatch(body, pattern) {
  if (!Array.isArray(body.messages)) return null;
  for (const message of body.messages) {
    if (message?.role !== "user") continue;
    const match = pattern.exec(textOfContent(message.content));
    if (match) return match[0];
  }
  return null;
}

function toolCallFrame(id, callId, name, args) {
  return {
    id,
    model: "w18-controlled-model",
    choices: [
      {
        index: 0,
        delta: {
          tool_calls: [
            {
              index: 0,
              id: callId,
              type: "function",
              function: { name, arguments: JSON.stringify(args) },
            },
          ],
        },
        finish_reason: "tool_calls",
      },
    ],
  };
}

function finishWithToolCall(response, id, callId, name, args) {
  frame(response, toolCallFrame(id, callId, name, args));
  frame(response, { id, model: "w18-controlled-model", choices: [], usage: { prompt_tokens: 4, completion_tokens: 2 } });
  frame(response, "[DONE]");
  response.end();
}

function taskCallsFrame(id, calls) {
  return {
    id,
    model: "w18-controlled-model",
    choices: [
      {
        index: 0,
        delta: {
          tool_calls: calls.map((call, index) => ({
            index,
            id: call.callId,
            type: "function",
            function: { name: call.name, arguments: JSON.stringify(call.args) },
          })),
        },
        finish_reason: "tool_calls",
      },
    ],
  };
}

// One assistant message per model round even for parallel work: both `task` calls
// share a single delta so the projection opens one frozen `assistant:subagent` group.
function finishWithTaskCalls(response, id, calls) {
  frame(response, taskCallsFrame(id, calls));
  frame(response, { id, model: "w18-controlled-model", choices: [], usage: { prompt_tokens: 4, completion_tokens: 2 } });
  frame(response, "[DONE]");
  response.end();
}

function completeW08Text(response, body, id, text) {
  if (body.stream === true) {
    finishWithText(response, id, text);
    return;
  }
  // A Child model call may be a one-shot non-streaming request; answer in the
  // OpenAI completion shape rather than emitting an SSE body to a JSON reader.
  sendJson(response, 200, {
    id,
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: "w18-controlled-model",
    choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }],
    usage: { prompt_tokens: 6, completion_tokens: 4, total_tokens: 10 },
  });
}

async function handleW08Delegation(response, body, prompt, offeredTools) {
  // Scenario markers live anywhere in the transcript. The child's first model
  // call can carry an injected RUNTIME_CONTROL_UPDATE as the last message, so
  // gating on the last user text alone would misclassify it.
  const text = messagesText(body);
  const child = text.includes(W08_CHILD_MARK);
  const childObjective = w08Objective(body);
  const record = { child, prompt, offeredTools, objective: childObjective, exposedSubagents: exposedSubagents(body) };
  w08Calls.push(record);
  if (!child && offeredTools.includes("task") && !hasToolResult(body) && text.includes("W08-UI10-PARENT")) {
    record.calls = [
      { callId: "w08-task-10-alpha", name: "task", objective: "W08-UI10-OBJECTIVE-ALPHA" },
      { callId: "w08-task-10-beta", name: "task", objective: "W08-UI10-OBJECTIVE-BETA" },
    ];
  }
  console.log(JSON.stringify({ type: "w08_delegation", ...record }));
  const id = `w08-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  if (child && childObjective && /W08-UI10-OBJECTIVE-(?:ALPHA|BETA)/.test(childObjective)) {
    const state = await blockW08Ui10Gate(response, childObjective, id);
    if (state !== "closed") {
      completeW08Text(response, body, id, `W08-CHILD-RESULT:${childObjective}`);
    }
    return;
  }
  if (child) {
    await handleW08Child(response, body, id, offeredTools, childObjective);
    return;
  }
  handleW08Parent(response, body, prompt, offeredTools, id);
}

async function handleW08Child(response, body, id, offeredTools, objective) {
  const text = messagesText(body);
  const toolResults = toolResultCount(body);
  // UI-07: one read-only production Tool, then a gate before the final answer so
  // the assistant and tool steps are asserted while the Child is still running,
  // and are still present once it completes.
  if (text.includes("W08-UI07-OBJECTIVE")) {
    if (toolResults === 0) {
      console.log(JSON.stringify({ type: "w08_assistant_tool_call", objective, tool: "list_uploaded_files" }));
      finishWithAssistantToolCall(
        response,
        id,
        "W08-UI07-CHILD-STEP: reading the available uploads before answering.",
        `w08-ui07-list-${Date.now()}`,
        "list_uploaded_files",
        {},
      );
      return;
    }
    const reason = await waitForGate("w08-ui07-child", response, 90_000);
    if (reason !== "closed") {
      completeW08Text(response, body, id, `W08-CHILD-RESULT:${objective}`);
    }
    return;
  }
  // UI-11: the Child is held open while the browser disables this managed
  // definition. It must still complete, because its Run was admitted before the
  // configuration change.
  if (text.includes("W08-UI11-OBJECTIVE")) {
    const reason = await waitForGate("w08-ui11-child", response, 90_000);
    if (reason !== "closed") {
      completeW08Text(response, body, id, `W08-CHILD-RESULT:${objective}`);
    }
    return;
  }
  // UI-13 variant A: fail the Child model call so the Parent receives a real
  // failed task result and still finishes.
  if (text.includes("W08-UI13-FAIL-OBJECTIVE")) {
    console.log(JSON.stringify({ type: "w08_child_failure", objective, status: 500 }));
    sendJson(response, 500, { error: { message: "W08_UI13_CONTROLLED_CHILD_FAILURE" } });
    return;
  }
  // UI-15: the Child's first model call requests the approval-requiring
  // production write_file; the SDK's non-interactive Child policy must deny it.
  // The second call observes the real denial Tool result and continues.
  if (text.includes("W08-UI15-OBJECTIVE")) {
    if (toolResults === 0 && offeredTools.includes("write_file")) {
      console.log(JSON.stringify({ type: "w08_child_write_call", objective, tool: "write_file" }));
      finishWithToolCall(response, id, `w08-ui15-write-${Date.now()}`, "write_file", {
        path: "/mnt/user-data/outputs/w08-ui15-denied.txt",
        content: "W08 UI-15 non-interactive child write that must be denied by policy",
      });
      return;
    }
    const toolResult = lastToolText(body);
    console.log(JSON.stringify({
      type: "w08_child_write_denied",
      objective,
      denied: /denied by policy/i.test(toolResult),
      toolResult: toolResult.slice(0, 400),
    }));
    completeW08Text(response, body, id, `W08-CHILD-RESULT:${objective}`);
    return;
  }
  completeW08Text(response, body, id, objective ? `W08-CHILD-RESULT:${objective}` : "W08-CHILD-RESULT:UNKNOWN");
}

function handleW08Parent(response, body, prompt, offeredTools, id) {
  const text = messagesText(body);
  if (!offeredTools.includes("task")) {
    completeW08Text(response, body, id, `Controlled provider: ${prompt}`);
    return;
  }
  if (hasToolResult(body)) {
    completeW08Text(response, body, id, `W08-PARENT-FINAL:${prompt}`);
    return;
  }
  if (text.includes("W08-UI10-PARENT")) {
    finishWithTaskCalls(response, id, [
      { callId: "w08-task-10-alpha", name: "task", args: taskArguments(body, W08_CHILD_AGENT, "W08-UI10-OBJECTIVE-ALPHA") },
      { callId: "w08-task-10-beta", name: "task", args: taskArguments(body, W08_CHILD_AGENT, "W08-UI10-OBJECTIVE-BETA") },
    ]);
    return;
  }
  if (text.includes("W08-UI09-PARENT")) {
    finishWithTaskCalls(response, id, [
      { callId: "w08-task-alpha", name: "task", args: taskArguments(body, W08_CHILD_AGENT, "W08-UI09-OBJECTIVE-ALPHA") },
      { callId: "w08-task-beta", name: "task", args: taskArguments(body, W08_CHILD_AGENT, "W08-UI09-OBJECTIVE-BETA") },
    ]);
    return;
  }
  if (text.includes("W08-UI07-PARENT")) {
    finishWithTaskCalls(response, id, [
      { callId: `w08-task-${Date.now()}`, name: "task", args: taskArguments(body, W08_TOOLS_CHILD_AGENT, "W08-UI07-OBJECTIVE") },
    ]);
    return;
  }
  if (text.includes("W08-UI11-PARENT")) {
    finishWithTaskCalls(response, id, [
      { callId: `w08-task-${Date.now()}`, name: "task", args: taskArguments(body, W08_UI11_CHILD_AGENT, "W08-UI11-OBJECTIVE") },
    ]);
    return;
  }
  if (text.includes("W08-UI11R2-PARENT")) {
    completeW08Text(response, body, id, `W08-PARENT-FINAL:${prompt}`);
    return;
  }
  if (text.includes("W08-UI13-FAIL-PARENT")) {
    finishWithTaskCalls(response, id, [
      { callId: `w08-task-${Date.now()}`, name: "task", args: taskArguments(body, W08_CHILD_AGENT, "W08-UI13-FAIL-OBJECTIVE") },
    ]);
    return;
  }
  if (text.includes("W08-UI13-MISSING-PARENT")) {
    if (hasToolResult(body) || text.includes(`"${W08_MISSING_CHILD_AGENT}"`)) {
      completeW08Text(response, body, id, `W08-PARENT-FINAL:${prompt}`);
      return;
    }
    finishWithTaskCalls(response, id, [
      { callId: `w08-task-${Date.now()}`, name: "task", args: taskArguments(body, W08_MISSING_CHILD_AGENT, "W08-UI13-MISSING-OBJECTIVE") },
    ]);
    return;
  }
  if (text.includes("W08-UI14-PARENT")) {
    const args = taskArguments(body, W08_CHILD_AGENT, W08_UI14_LONG_OBJECTIVE);
    console.log(JSON.stringify({ type: "w08_task_call", objective: W08_UI14_LONG_OBJECTIVE, args }));
    finishWithTaskCalls(response, id, [
      { callId: `w08-task-${Date.now()}`, name: "task", args },
    ]);
    return;
  }
  if (text.includes("W08-UI15-PARENT")) {
    finishWithTaskCalls(response, id, [
      { callId: `w08-task-${Date.now()}`, name: "task", args: taskArguments(body, W08_UI15_CHILD_AGENT, "W08-UI15-OBJECTIVE") },
    ]);
    return;
  }
  const objective = text.includes("W08-UI08-PARENT") ? "W08-UI08-OBJECTIVE" : "W08-UI06-OBJECTIVE";
  finishWithTaskCalls(response, id, [
    { callId: `w08-task-${Date.now()}`, name: "task", args: taskArguments(body, W08_CHILD_AGENT, objective) },
  ]);
}

function finishWithText(response, id, text) {
  frame(response, completionChunk(id, text, "stop"));
  frame(response, { id, model: "w18-controlled-model", choices: [], usage: { prompt_tokens: 6, completion_tokens: 4 } });
  frame(response, "[DONE]");
  response.end();
}

// The W08 delegation branch is reached before the generic SSE writeHead, so every
// streaming finish must set the event-stream headers itself. Guarded, so the
// existing branches that already wrote them are unchanged.
function sseHeaders(response) {
  if (!response.headersSent) {
    response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
  }
}

function frame(response, value) {
  sseHeaders(response);
  response.write(`data: ${typeof value === "string" ? value : JSON.stringify(value)}\n\n`);
}

function completionChunk(id, content, finishReason = null) {
  return {
    id,
    model: "w18-controlled-model",
    choices: [{ index: 0, delta: { content }, finish_reason: finishReason }],
  };
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, `http://127.0.0.1:${port}`);
  if (request.method === "GET" && url.pathname === "/health") {
    sendJson(response, 200, { status: "ok" });
    return;
  }
  if (request.method === "GET" && url.pathname === "/__state") {
    sendJson(response, 200, { polishCalls, chatCalls, w08Calls, w05LiveCalls });
    return;
  }
  if (request.method === "POST" && url.pathname === "/__release") {
    // Explicit gate for the W05 live-rendering scenes; returns how many blocked
    // model calls this release actually unblocked.
    sendJson(response, 200, { released: releaseW05Live() });
    return;
  }
  if (url.pathname === "/__release") {
    // Browser-controlled gate release for the blocked W08 Child model calls.
    const name = url.searchParams.get("name") || "";
    const released = releaseW08Gate(name);
    sendJson(response, released ? 200 : 404, { type: "w08_gate_release", released, name });
    return;
  }
  if (request.method !== "POST" || url.pathname !== "/v1/chat/completions") {
    sendJson(response, 404, { error: "not_found" });
    return;
  }

  let raw = "";
  request.setEncoding("utf8");
  request.on("data", (chunk) => {
    raw += chunk;
    if (raw.length > 1024 * 1024) request.destroy();
  });
  request.on("end", async () => {
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      sendJson(response, 400, { error: "invalid_json" });
      return;
    }

    const rawPrompt = lastUserText(body);
    const prompt = syntheticUserText(rawPrompt);
    const offeredTools = toolNames(body);
    const probe = offeredTools.includes("connection_check");
    const interaction = offeredTools.includes("mutating_probe");

    // Only synthetic W15 acceptance bytes are allowed by this controlled screen.
    // Real-model security acceptance uses the managed live Provider separately.
    const skillScreen = body.stream !== true && offeredTools.length === 0 &&
      (body.messages ?? []).some((message) => message.role === "system" &&
        message.content?.startsWith("You are a security reviewer for AI agent skills."));
    if (skillScreen) {
      const fixtureFile = /^Location: (?:w15-package-ui\/)?(SKILL\.md|references\/fact\.txt|evals\/evals\.json)$/m.test(rawPrompt);
      const boundedFixture = fixtureFile && (rawPrompt.includes("W15_CONTROLLED_UI_PACKAGE") ||
        rawPrompt.includes('"skill_name":"w15-package-ui"'));
      const decision = boundedFixture ? "allow" : "block";
      console.log(JSON.stringify({ type: "w15_security_screen", decision, fixtureFile, tools: offeredTools }));
      sendJson(response, 200, { id: `w15-screen-${Date.now()}`, object: "chat.completion",
        model: "w18-controlled-model", choices: [{ index: 0, message: { role: "assistant", content: JSON.stringify({ decision }) }, finish_reason: "stop" }],
        usage: { prompt_tokens: 4, completion_tokens: 3, total_tokens: 7 } });
      return;
    }

    if (isInputPolishRequest(body)) {
      handleInputPolish(request, response, body, prompt);
      return;
    }

    chatCalls.push({ prompt, stream: body.stream === true, offeredTools });
    console.log(
      JSON.stringify({
        type: "provider_request",
        path: url.pathname,
        model: body.model ?? null,
        stream: body.stream === true,
        probe,
        interaction,
        prompt,
        rawPrompt,
        offeredTools,
        toolResults: toolResultCount(body),
        uploadPaths: uploadPaths(body),
        authorization: /^Bearer\s+\S+$/i.test(request.headers.authorization ?? ""),
      }),
    );

    // W08 Child-delegation scenes: only this bounded marker family. Parent vs Child
    // is decided by the Child system-prompt marker and the declared `task` Tool, not
    // by arrival order; the request stream is otherwise untouched.
    // W05 LIVE-04 reuses the controlled W08 Child, so its Child request also carries
    // W08-CHILD-MARKER; route the whole scene to the live-rendering handler instead.
    const w05Live04 = messagesText(body).includes("w05-live-04");
    if (!w05Live04 && (isW08Child(body) || messagesText(body).includes("W08-UI"))) {
      await handleW08Delegation(response, body, prompt, offeredTools);
      return;
    }

    // W05 live-rendering scenes: hold a Run mid-flight behind an explicit
    // release gate so the spec can assert the frozen UI while it is running.
    // The post-run follow-up-suggestions request echoes the transcript, so it
    // must never be mistaken for a scene.
    if (w05LiveMarker(body) && !isSuggestionsRequest(body)) {
      await handleW05LiveRendering(response, body);
      return;
    }

    if (body.stream !== true && !probe) {
      const suggestion = isSuggestionsRequest(body);
      console.log(
        JSON.stringify({
          type: "provider_nonstream",
          suggestion,
          stream: false,
        }),
      );
      sendJson(response, 200, {
        id: `w18-nonstream-${Date.now()}`,
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model: "w18-controlled-model",
        choices: [{
          index: 0,
          message: {
            role: "assistant",
            content: suggestion
              ? JSON.stringify(SUGGESTIONS)
              : `Controlled provider: ${prompt}`,
          },
          finish_reason: "stop",
        }],
        usage: { prompt_tokens: 4, completion_tokens: 3, total_tokens: 7 },
      });
      return;
    }

    response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
    if (probe) {
      frame(response, {
        id: "w18-probe",
        model: "w18-controlled-model",
        choices: [
          {
            index: 0,
            delta: {
              tool_calls: [
                {
                  index: 0,
                  id: "w18-call-1",
                  type: "function",
                  function: { name: "connection_check", arguments: "{}" },
                },
              ],
            },
            finish_reason: "tool_calls",
          },
        ],
      });
      frame(response, {
        id: "w18-probe",
        model: "w18-controlled-model",
        choices: [],
        usage: { prompt_tokens: 2, completion_tokens: 1 },
      });
      frame(response, "[DONE]");
      response.end();
      return;
    }

    if (interaction && prompt.startsWith("w05-interaction-") && !hasToolResult(body)) {
      const id = `w05-interaction-${Date.now()}`;
      frame(response, {
        id,
        model: "w18-controlled-model",
        choices: [
          {
            index: 0,
            delta: {
              tool_calls: [
                {
                  index: 0,
                  id: "w05-mutating-call",
                  type: "function",
                  function: {
                    name: "mutating_probe",
                    arguments: '{"value":"browser-approved"}',
                  },
                },
              ],
            },
            finish_reason: "tool_calls",
          },
        ],
      });
      frame(response, {
        id,
        model: "w18-controlled-model",
        choices: [],
        usage: { prompt_tokens: 4, completion_tokens: 2 },
      });
      frame(response, "[DONE]");
      response.end();
      return;
    }

    if (interaction && prompt.startsWith("w05-interaction-") && hasToolResult(body)) {
      const id = `w05-interaction-final-${Date.now()}`;
      frame(response, completionChunk(id, `Interaction resolved: ${prompt}`, "stop"));
      frame(response, {
        id,
        model: "w18-controlled-model",
        choices: [],
        usage: { prompt_tokens: 6, completion_tokens: 4 },
      });
      frame(response, "[DONE]");
      response.end();
      return;
    }

    // W04 V2 file context: read the saved original the Run context references
    // with read_file and derive every value from the Tool result. The provider
    // never holds the CSV values; a missing reference or unreadable file yields
    // an explicit NO_FILE_CONTEXT / FILE_UNREADABLE answer instead.
    // W04's `v2-file-context-<word>-<n>` and V2-M1's `v2-m1-file-<n>` share one
    // bounded read_file -> answer path; every value still comes from the Tool
    // result, never from a provider-side copy of the fixture.
    const v2FilePrompt = /(?:v2-file-context-[a-z]+|v2-m1-file)-\d+/.exec(rawPrompt)?.[0];
    if (v2FilePrompt && offeredTools.includes("read_file")) {
      const id = `v2-file-context-${Date.now()}`;
      const last = Array.isArray(body.messages) ? body.messages.at(-1) : null;
      if (last?.role === "tool") {
        const toolText = v2ToolText(last.content);
        const answer = `${v2FilePrompt} ${v2CsvAnswer(toolText)}`;
        console.log(JSON.stringify({
          type: "v2_file_context", marker: v2FilePrompt, action: "answer", answer,
          toolResultSha256: v2CsvToolResultSha256(toolText),
        }));
        finishWithText(response, id, answer);
      } else {
        const target = /\/mnt\/user-data\/uploads\/[^\s)`'"<]+/.exec(rawPrompt)?.[0];
        if (target) {
          console.log(JSON.stringify({ type: "v2_file_context", marker: v2FilePrompt, action: "read_file", path: target }));
          finishWithToolCall(response, id, `v2-read-${Date.now()}`, "read_file", {
            description: "Read the uploaded CSV",
            path: target,
          });
        } else {
          console.log(JSON.stringify({ type: "v2_file_context", marker: v2FilePrompt, action: "no_file_context" }));
          finishWithText(response, id, `${v2FilePrompt} NO_FILE_CONTEXT`);
        }
      }
      return;
    }

    // W18 V2-M2: request a Tool only when the real backend offered its
    // source-qualified name. The answer is derived solely from the Tool reply
    // returned through the real MCP transport, never from the policy fixture.
    const mcpPrompt = firstUserMatch(body, /w18-v2-m2-(?:disconnect|status-reporting|status-region|reporting|region)-\d+/);
    if (mcpPrompt) {
      const action = /^w18-v2-m2-(disconnect|status-reporting|status-region|reporting|region)-/.exec(mcpPrompt)?.[1];
      const source = action === "disconnect" || action?.includes("reporting") ? "reporting" : "region";
      const suffix = action === "disconnect" || action?.startsWith("status-") ? "get_status" :
        source === "reporting" ? "get_reporting_policy" : "get_region_label";
      const qualified = offeredTools.find((name) =>
        name.endsWith(`_${suffix}`) && name.replace(/_/g, "-").includes(source));
      const lastTool = [...(body.messages ?? [])].reverse().find((message) => message?.role === "tool");
      const id = `w18-v2-m2-${Date.now()}`;
      if (lastTool) {
        const result = v2ToolText(lastTool.content);
        const resultSha256 = crypto.createHash("sha256").update(result, "utf8").digest("hex");
        console.log(JSON.stringify({
          type: "v2_mcp", marker: mcpPrompt, action: "result", source,
          tool: qualified ?? null, result, resultSha256,
        }));
        // A transport error may arrive as a Tool message. Never turn it into
        // a claimed successful observation for the ambiguous-result case.
        const prefix = action === "disconnect" && !result.includes('"state":"ready"')
          ? "MCP_UNKNOWN" : "MCP_RESULT";
        finishWithText(response, id, `${prefix} ${mcpPrompt} ${result}`);
      } else if (qualified) {
        const args = suffix === "get_region_label" ? { region: "East" } : {};
        console.log(JSON.stringify({ type: "v2_mcp", marker: mcpPrompt, action: "call", source, tool: qualified, args }));
        finishWithToolCall(response, id, `w18-v2-m2-${source}-${Date.now()}`, qualified, args);
      } else {
        console.log(JSON.stringify({ type: "v2_mcp", marker: mcpPrompt, action: "unavailable", source, offeredTools }));
        finishWithText(response, id, `NO_MCP_TOOL ${mcpPrompt}`);
      }
      return;
    }

    // W15 Skill proof: the answer is derived from the SDK's offered Tool and
    // the instructions visible in the resumed model request, not from a local
    // copy of SKILL.md or a hard-coded successful response.
    const packageMarker = firstUserMatch(body, /w15-package-use-\d+/);
    if (packageMarker) {
      const id = `w15-package-${Date.now()}`;
      const results = toolResultCount(body);
      const text = JSON.stringify(body.messages ?? []);
      if (results === 0 && offeredTools.includes("skill_load")) {
        finishWithToolCall(response, id, `w15-package-load-${Date.now()}`, "skill_load", { skill: "w15-package-ui" });
      } else if (results === 1 && offeredTools.includes("skill_resource_read")) {
        finishWithToolCall(response, id, `w15-package-read-${Date.now()}`, "skill_resource_read", { skill: "w15-package-ui", path: "references/fact.txt" });
      } else {
        const observed = text.includes("W15_CONTROLLED_UI_PACKAGE_INSTRUCTIONS") && text.includes("W15_CONTROLLED_UI_PACKAGE_FACT=17");
        finishWithText(response, id, `${packageMarker} ${observed ? "PACKAGE_CONTEXT_AND_RESOURCE_17" : "PACKAGE_CONTEXT_MISSING"}`);
        console.log(JSON.stringify({ type: "w15_package_context", marker: packageMarker, observed, results }));
      }
      return;
    }
    const skillMarker = firstUserMatch(body, /w15-skill-(?:enabled|disabled)-\d+/);
    if (skillMarker) {
      const id = `w15-skill-${Date.now()}`;
      const hasSkillTool = offeredTools.includes("skill_load");
      const skillBodyVisible = JSON.stringify(body.messages ?? []).includes("Data Authenticity Protocol");
      const toolResults = toolResultCount(body);
      let action;
      if (skillMarker.includes("-enabled-") && toolResults === 0 && hasSkillTool) {
        action = "skill_load";
        finishWithToolCall(response, id, `w15-skill-load-${Date.now()}`, "skill_load", {
          skill: "consulting-analysis",
        });
      } else {
        action = "answer";
        const answer = skillMarker.includes("-disabled-")
          ? (!hasSkillTool && !skillBodyVisible ? "SKILL_DISABLED_NO_TOOL" : "SKILL_DISABLED_LEAK")
          : (toolResults > 0 && skillBodyVisible ? "SKILL_ACTIVE_FROM_CONTEXT" : "SKILL_BODY_MISSING");
        finishWithText(response, id, `${skillMarker} ${answer}`);
      }
      console.log(JSON.stringify({
        type: "w15_skill_context", marker: skillMarker, action,
        hasSkillTool, skillBodyVisible, toolResults,
      }));
      return;
    }

    // W18 differential scripts only drive tools the backend actually offers; a
    // backend without that production Tool falls through to the plain answer.
    const fileToolPrompt = firstUserMatch(body, /w18-file-tool-\d+/);
    if (fileToolPrompt && offeredTools.includes("write_file")) {
      const id = `w18-file-tool-${Date.now()}`;
      const target = `/mnt/user-data/outputs/${fileToolPrompt}.md`;
      const results = toolResultCount(body);
      if (results === 0) {
        finishWithToolCall(response, id, "w18-write-call", "write_file", {
          description: "W18 differential output",
          path: target,
          // Single line: the frozen LocalSandbox writes in text mode, so on
          // Windows any "\n" becomes "\r\n" and the hash oracle would test the
          // host newline convention instead of the Tool→Artifact path.
          content: `W18 ${fileToolPrompt} produced by write_file 数据 ✓`,
        });
      } else if (results === 1 && offeredTools.includes("present_files")) {
        finishWithToolCall(response, id, "w18-present-call", "present_files", { filepaths: [target] });
      } else {
        finishWithText(response, id, `File tool done: ${fileToolPrompt}`);
      }
      return;
    }
    const interactionPrompt = firstUserMatch(body, /w18-interaction-\d+/);
    if (interactionPrompt && offeredTools.includes("ask_clarification")) {
      const id = `w18-interaction-${Date.now()}`;
      if (toolResultCount(body) === 0) {
        finishWithToolCall(response, id, "w18-clarify-call", "ask_clarification", {
          question: `Choose a path for ${interactionPrompt}`,
          clarification_type: "approach_choice",
          options: ["Alpha", "Beta"],
        });
      } else {
        finishWithText(response, id, `Interaction resolved: ${interactionPrompt}`);
      }
      return;
    }

    // W18 customer-demo L2 chain. The same controlled Provider and browser
    // spec drive both backends. The reference backend exposes present_files
    // and uses its built-in ask_clarification interruption before write_file;
    // Haifa exposes the policy-guarded production write_file directly. This
    // branch only chooses among Tools actually offered by the backend and
    // never manufactures Tool/Interaction/Artifact responses.
    const demoPrompt = firstUserMatch(body, /w18-demo-l2-r\d+-\d+/);
    if (demoPrompt && offeredTools.includes("write_file")) {
      const round = /w18-demo-l2-r(\d+)-/.exec(demoPrompt)?.[1] ?? "0";
      const id = `w18-demo-l2-${Date.now()}`;
      const target = `/mnt/user-data/outputs/${demoPrompt}.txt`;
      const results = toolResultCount(body);
      const reference = offeredTools.includes("present_files");
      const steerAck = firstUserMatch(body, /DEMO-STEER-ACK-\d+/);
      if (steerAck) {
        finishWithText(response, id, steerAck);
      } else if (reference && results === 0 && offeredTools.includes("ask_clarification")) {
        finishWithToolCall(response, id, `w18-demo-approval-${round}`, "ask_clarification", {
          question: `Approve production write_file for ${demoPrompt}?`,
          clarification_type: "confirmation",
          options: ["Approve", "Reject"],
        });
      } else if ((!reference && results === 0) || (reference && results === 1)) {
        finishWithToolCall(response, id, `w18-demo-write-${round}`, "write_file", {
          description: "W18 customer demo L2 output",
          path: target,
          content: `haifa-customer-demo-round-${round}`,
        });
      } else if (reference && results === 2) {
        finishWithToolCall(response, id, `w18-demo-present-${round}`, "present_files", {
          filepaths: [target],
        });
      } else {
        // A bounded streamed answer leaves a deterministic L2 active-input
        // window after the real Tool result. It is not a PASS oracle by
        // itself: the browser case must prove that the frozen UI emitted the
        // Steer request and that the same Run consumed it.
        let emitted = 0;
        const timer = setInterval(() => {
          if (response.destroyed || response.writableEnded) {
            clearInterval(timer);
            return;
          }
          emitted += 1;
          frame(response, completionChunk(
            id,
            emitted === 1 ? `Artifact ready for ${demoPrompt}. ` : ".",
            emitted === 300 ? "stop" : null,
          ));
          if (emitted === 300) {
            clearInterval(timer);
            frame(response, {
              id,
              model: "w18-controlled-model",
              choices: [],
              usage: { prompt_tokens: 12, completion_tokens: 24 },
            });
            frame(response, "[DONE]");
            response.end();
          }
        }, 100);
        response.on("close", () => clearInterval(timer));
      }
      return;
    }

    const id = `w18-run-${Date.now()}`;
    if (prompt === "__slow__" || prompt === "__slow_differential__") {
      let emitted = 0;
      const timer = setInterval(() => {
        if (response.destroyed || response.writableEnded) {
          clearInterval(timer);
          return;
        }
        emitted += 1;
        frame(response, completionChunk(id, ".", emitted === 200 ? "stop" : null));
        if (emitted === 200) {
          clearInterval(timer);
          frame(response, {
            id,
            model: "w18-controlled-model",
            choices: [],
            usage: { prompt_tokens: 4, completion_tokens: 50 },
          });
          frame(response, "[DONE]");
          response.end();
        }
      }, prompt === "__slow_differential__" ? 100 : 25);
      response.on("close", () => clearInterval(timer));
      return;
    }

    // W21 negative control: a real SSE completion without Provider Usage.
    // Keep the raw frame in the Provider log for later source verification.
    if (firstUserMatch(body, /w21-omit-usage-\d+/)) {
      frame(response, completionChunk(id, `W21 usage omitted: ${prompt}`, "stop"));
      frame(response, "[DONE]");
      console.log(JSON.stringify({ type: "w21_usage_omitted", id, prompt, usage: null }));
      response.end();
      return;
    }

    frame(response, completionChunk(id, "Controlled provider: "));
    frame(response, completionChunk(id, prompt, "stop"));
    frame(response, {
      id,
      model: "w18-controlled-model",
      choices: [],
      usage: { prompt_tokens: 4, completion_tokens: 3 },
    });
    frame(response, "[DONE]");
    response.end();
  });
});

server.listen(port, "127.0.0.1", () => {
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("provider did not expose a TCP address");
  }
  const ready = {
    type: "ready",
    address: `http://127.0.0.1:${address.port}`,
    port: address.port,
    pid: process.pid,
  };
  fs.writeFileSync(readyFile, `${JSON.stringify(ready)}\n`, {
    encoding: "utf8",
    flag: "wx",
  });
  console.log(JSON.stringify(ready));
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
