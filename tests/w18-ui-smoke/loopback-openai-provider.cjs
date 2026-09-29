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

function finishWithText(response, id, text) {
  frame(response, completionChunk(id, text, "stop"));
  frame(response, { id, model: "w18-controlled-model", choices: [], usage: { prompt_tokens: 6, completion_tokens: 4 } });
  frame(response, "[DONE]");
  response.end();
}

function frame(response, value) {
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
    sendJson(response, 200, { polishCalls, chatCalls });
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
  request.on("end", () => {
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
