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

    const prompt = lastUserText(body);
    const probe = Array.isArray(body.tools) && body.tools.length > 0;
    console.log(
      JSON.stringify({
        type: "provider_request",
        path: url.pathname,
        model: body.model ?? null,
        stream: body.stream === true,
        probe,
        prompt,
        authorization: /^Bearer\s+\S+$/i.test(request.headers.authorization ?? ""),
      }),
    );

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

    const id = `w18-run-${Date.now()}`;
    if (prompt === "__slow__") {
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
      }, 25);
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
