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

function hasToolResult(body) {
  return (
    Array.isArray(body.messages) &&
    body.messages.some((message) => message?.role === "tool")
  );
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
