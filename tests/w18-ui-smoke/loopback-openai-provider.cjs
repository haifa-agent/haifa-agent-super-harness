const http = require("node:http");
const fs = require("node:fs");

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
