const http = require("node:http");
const https = require("node:https");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const RESPONSE_CAPTURE_LIMIT = 4 * 1024 * 1024;
const REQUEST_METADATA_LIMIT = 1024 * 1024;

function endToEndHeaders(headers) {
  const excluded = new Set(["host", "connection", "keep-alive", "proxy-authenticate",
    "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"]);
  for (const token of String(headers.connection ?? "").split(",")) excluded.add(token.trim().toLowerCase());
  return Object.fromEntries(Object.entries(headers).filter(([key]) => !excluded.has(key.toLowerCase())));
}

// Only the test caller supplies upstreamBase. The incoming request cannot
// select a host. Production use of this test helper pins api.deepseek.com.
function forwardWithCapture(request, response, { upstreamBase, captureDirectory,
  prefix = "/w16-live", captureLimit = RESPONSE_CAPTURE_LIMIT }) {
  const id = crypto.randomUUID();
  const bytesPath = path.join(captureDirectory, `${id}.response.bin`);
  const metadataPath = path.join(captureDirectory, `${id}.metadata.json`);
  const metadata = { id, startedAt: new Date().toISOString(), captureLimit,
    responseBytesPath: bytesPath, request: {}, response: { bytes: 0, capturedBytes: 0 } };
  const requestHash = crypto.createHash("sha256");
  const responseHash = crypto.createHash("sha256");
  const capturedHash = crypto.createHash("sha256");
  let requestBytes = 0;
  const requestPieces = [];
  let finalized = false;
  let requestFinished = false;
  let requestHashFinished = false;
  let responseStarted = false;
  let ended = false;
  let fd;
  try {
    fs.mkdirSync(captureDirectory, { recursive: true });
    fd = fs.openSync(bytesPath, "wx");
  } catch (error) {
    metadata.captureErrorCode = error.code ?? "UNKNOWN";
    console.error(JSON.stringify({ type: "w16-capture-initialization-failed", id,
      errorCode: metadata.captureErrorCode, captureIncomplete: true }));
  }
  const requestSummary = () => {
    if (requestHashFinished) return;
    requestHashFinished = true;
    metadata.request.bodySha256 = requestHash.digest("hex");
    metadata.request.bytes = requestBytes;
    metadata.request.complete = requestFinished;
    if (requestBytes > REQUEST_METADATA_LIMIT) {
      metadata.request.budgetMetadataUnavailable = "request exceeds metadata parsing limit";
      return;
    }
    try {
      const body = JSON.parse(Buffer.concat(requestPieces).toString("utf8"));
      for (const key of ["model", "stream", "max_tokens", "max_completion_tokens"])
        if (["string", "boolean", "number"].includes(typeof body[key])) metadata.request[key] = body[key];
    } catch { metadata.request.budgetMetadataUnavailable = "request is not complete JSON"; }
  };
  const finish = (state, error) => {
    if (finalized) return;
    finalized = true;
    requestSummary();
    if (fd !== undefined) { try { fs.closeSync(fd); } catch { } }
    metadata.endedAt = new Date().toISOString();
    metadata.response.endState = state;
    metadata.response.transportComplete = ended;
    metadata.response.captureComplete = ended && !metadata.captureErrorCode &&
      metadata.response.capturedBytes === metadata.response.bytes;
    metadata.response.captureLimitExceeded = metadata.response.bytes > captureLimit;
    metadata.response.sha256 = responseHash.digest("hex");
    metadata.response.capturedSha256 = capturedHash.digest("hex");
    if (error) metadata.response.errorCode = error.code ?? "UNKNOWN";
    // Headers and exception messages are deliberately absent: Authorization is
    // forwarded only in memory, and request content is represented by its hash.
    try { fs.writeFileSync(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`, { flag: "wx" }); }
    catch { console.error(JSON.stringify({ type: "w16-capture-write-failed", id, captureIncomplete: true })); }
  };
  const target = new URL(upstreamBase);
  const suffix = request.url.slice(prefix.length);
  const upstreamPath = `${target.pathname.replace(/\/$/, "")}${suffix}`;
  metadata.request.path = upstreamPath;
  const upstream = (target.protocol === "https:" ? https : http).request({
    protocol: target.protocol, hostname: target.hostname, port: target.port || undefined,
    path: upstreamPath, method: request.method, headers: endToEndHeaders(request.headers),
  }, (incoming) => {
    responseStarted = true;
    metadata.response.httpStatus = incoming.statusCode;
    response.writeHead(incoming.statusCode, endToEndHeaders(incoming.headers));
    incoming.on("data", (chunk) => {
      if (finalized) return; // Observation only; pipe still owns forwarding.
      metadata.response.bytes += chunk.length;
      responseHash.update(chunk);
      const remaining = captureLimit - metadata.response.capturedBytes;
      if (fd !== undefined && remaining > 0) {
        const captured = chunk.subarray(0, remaining);
        try {
          let offset = 0;
          while (offset < captured.length) {
            const written = fs.writeSync(fd, captured, offset, captured.length - offset);
            if (written <= 0) throw Object.assign(new Error(), { code: "CAPTURE_WRITE_INCOMPLETE" });
            capturedHash.update(captured.subarray(offset, offset + written));
            metadata.response.capturedBytes += written;
            offset += written;
          }
        } catch (error) { metadata.captureErrorCode = error.code ?? "UNKNOWN"; }
      }
    });
    incoming.on("end", () => { ended = true; finish("end"); });
    incoming.on("aborted", () => { finish("aborted"); response.destroy(); });
    incoming.on("error", (error) => { finish("error", error); response.destroy(error); });
    incoming.on("close", () => { if (!ended) finish("close-before-end"); });
    incoming.pipe(response);
  });
  upstream.on("error", (error) => {
    finish(responseStarted ? "upstream-error" : "upstream-connect-error", error);
    if (response.headersSent) response.destroy(error);
    else { response.writeHead(502); response.end(); }
  });
  request.on("data", (chunk) => {
    if (!requestHashFinished) requestHash.update(chunk);
    requestBytes += chunk.length;
    if (requestBytes <= REQUEST_METADATA_LIMIT) requestPieces.push(chunk);
  });
  request.on("end", () => { requestFinished = true; requestSummary(); });
  request.on("aborted", () => { finish("request-aborted"); upstream.destroy(); });
  response.on("close", () => { if (!response.writableFinished && !finalized) { finish("downstream-aborted"); upstream.destroy(); } });
  request.pipe(upstream);
}

module.exports = { forwardWithCapture, endToEndHeaders, RESPONSE_CAPTURE_LIMIT };
