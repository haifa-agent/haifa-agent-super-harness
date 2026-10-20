const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { forwardWithCapture } = require("./passive-provider-forward.cjs");

const output = process.argv[2];
if (!output) throw new Error("external mock evidence directory is required");
fs.mkdirSync(output, { recursive: true });
const authorization = "Bearer test";
const requestBytes = Buffer.from(JSON.stringify({ model: "deepseek-v4-flash", stream: true,
  max_tokens: 4096, messages: [{ role: "user", content: "mock request" }] }));
const sha = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const listen = (server) => new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const close = (server) => new Promise((resolve) => server.close(resolve));
const fixtures = {
  normal: { status: 200, bytes: Buffer.from('data: {"choices":[{"delta":{"content":"正常响应"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n') },
  incomplete: { status: 200, bytes: Buffer.from('data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"/out\\\",\\\"content\\\":\\\"unfinished"}}]},"finish_reason":"length"}]}\n\ndata: [DONE]\n\n') },
  overflow: { status: 422, bytes: Buffer.from("x".repeat(256)), limit: 64 },
  aborted: { status: 200, bytes: Buffer.from('data: {"unfinished":true}\n\n'), abort: true },
  captureWriteFailure: { status: 200, bytes: Buffer.from("data: [DONE]\n\n"), captureWriteFailure: true },
  directoryInitializationFailure: { status: 200, bytes: Buffer.from("data: [DONE]\n\n"), directoryInitializationFailure: true },
};

async function main() {
  const results = [];
  for (const [name, fixture] of Object.entries(fixtures)) {
    const directory = path.join(output, name);
    if (fixture.directoryInitializationFailure) fs.writeFileSync(directory, "mock path is a file");
    const diagnostics = [];
    const originalConsoleError = console.error;
    if (fixture.directoryInitializationFailure) console.error = (line) => diagnostics.push(JSON.parse(line));
    let upstreamRequest;
    const upstream = http.createServer((request, response) => {
      const chunks = [];
      request.on("data", (chunk) => chunks.push(chunk));
      request.on("end", () => {
        upstreamRequest = { bytes: Buffer.concat(chunks), path: request.url, headers: request.headers };
        response.writeHead(fixture.status, { "content-type": "text/event-stream; charset=utf-8",
          connection: "x-private-hop", "x-private-hop": "do-not-forward" });
        response.write(fixture.bytes.subarray(0, 7));
        response.write(fixture.bytes.subarray(7));
        if (fixture.abort) setTimeout(() => response.destroy(), 30);
        else response.end();
      });
    });
    await listen(upstream);
    const proxy = http.createServer((request, response) => forwardWithCapture(request, response, {
      upstreamBase: `http://127.0.0.1:${upstream.address().port}`, captureDirectory: directory,
      captureLimit: fixture.limit ?? 4194304,
    }));
    await listen(proxy);
    const originalOpen = fs.openSync;
    if (fixture.captureWriteFailure) fs.openSync = (file, ...args) => {
      if (String(file).endsWith(".response.bin")) throw Object.assign(new Error(), { code: "ENOSPC" });
      return originalOpen(file, ...args);
    };
    try {
      const downstream = await new Promise((resolve, reject) => {
        const request = http.request({ hostname: "127.0.0.1", port: proxy.address().port,
          method: "POST", path: "/w16-live/chat/completions", headers: {
            authorization, "content-type": "application/json", "content-length": requestBytes.length,
            connection: "x-private-hop", "x-private-hop": "do-not-forward",
          } }, (response) => {
          const chunks = [];
          let done = false;
          const finish = (state) => { if (!done) { done = true; resolve({ status: response.statusCode,
            headers: response.headers, bytes: Buffer.concat(chunks), endState: state }); } };
          response.on("data", (chunk) => chunks.push(chunk));
          response.on("end", () => finish("end"));
          response.on("aborted", () => finish("aborted"));
          response.on("error", () => finish("error"));
        });
        request.on("error", reject);
        request.end(requestBytes);
      });
      if (fixture.directoryInitializationFailure) {
        assert.deepEqual(upstreamRequest.bytes, requestBytes);
        assert.deepEqual(downstream.bytes, fixture.bytes);
        assert.equal(downstream.status, fixture.status);
        assert.equal(downstream.endState, "end");
        assert(diagnostics.some((item) => item.type === "w16-capture-initialization-failed" && item.captureIncomplete));
        assert(diagnostics.some((item) => item.type === "w16-capture-write-failed" && item.captureIncomplete));
        assert.equal(JSON.stringify(diagnostics).includes(authorization), false);
        fs.writeFileSync(path.join(output, `${name}.diagnostics.json`), JSON.stringify(diagnostics, null, 2));
        results.push({ name, status: "PASS", httpStatus: downstream.status, responseSha256: sha(downstream.bytes),
          downstreamEndState: "end", captureComplete: false, capturedByteCount: 0, diagnostics });
        continue;
      }
      const metadata = JSON.parse(fs.readFileSync(path.join(directory,
        fs.readdirSync(directory).find((file) => file.endsWith(".metadata.json"))), "utf8"));
      const captured = fixture.captureWriteFailure ? Buffer.alloc(0) : fs.readFileSync(metadata.responseBytesPath);
      assert.deepEqual(upstreamRequest.bytes, requestBytes);
      assert.equal(upstreamRequest.path, "/chat/completions");
      assert.equal(upstreamRequest.headers.authorization, authorization);
      assert.equal(upstreamRequest.headers["x-private-hop"], undefined);
      assert.equal(downstream.headers["x-private-hop"], undefined);
      assert.equal(downstream.status, fixture.status);
      assert.deepEqual(downstream.bytes, fixture.bytes);
      assert.deepEqual(captured, fixture.captureWriteFailure ? Buffer.alloc(0) : fixture.bytes.subarray(0, fixture.limit ?? fixture.bytes.length));
      assert.equal(metadata.response.sha256, sha(fixture.bytes));
      assert.equal(metadata.request.bodySha256, sha(requestBytes));
      assert.equal(metadata.request.max_tokens, 4096);
      assert.equal(metadata.response.captureLimitExceeded, Boolean(fixture.limit));
      assert.equal(metadata.response.captureComplete, !fixture.abort && !fixture.limit && !fixture.captureWriteFailure);
      if (fixture.captureWriteFailure) assert.equal(metadata.captureErrorCode, "ENOSPC");
      assert.equal(metadata.response.transportComplete, !fixture.abort);
      assert.equal(downstream.endState, fixture.abort ? "aborted" : "end");
      for (const file of fs.readdirSync(directory))
        assert.equal(fs.readFileSync(path.join(directory, file)).includes(Buffer.from(authorization)), false);
      fs.writeFileSync(path.join(directory, "source-response.bin"), fixture.bytes);
      fs.writeFileSync(path.join(directory, "downstream-response.bin"), downstream.bytes);
      results.push({ name, status: "PASS", httpStatus: downstream.status,
        responseSha256: sha(fixture.bytes), byteCount: fixture.bytes.length,
        downstreamEndState: downstream.endState, captureEndState: metadata.response.endState,
        captureComplete: metadata.response.captureComplete, transportComplete: metadata.response.transportComplete,
        capturedByteCount: captured.length, upstreamRequestSha256: sha(upstreamRequest.bytes),
        credentialForwardedInMemory: true, credentialStored: false, metadata });
    } finally { fs.openSync = originalOpen; console.error = originalConsoleError; await close(proxy); await close(upstream); }
  }
  fs.writeFileSync(path.join(output, "mock-capture-results.json"), `${JSON.stringify(results, null, 2)}\n`);
  console.log(JSON.stringify(results.map(({ metadata, ...result }) => result), null, 2));
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
