#!/usr/bin/env node
/**
 * Best-effort warm-up for the frozen frontend.
 *
 * Next evaluates each App Router route lazily on its first request. The
 * workspace layout is dynamic, so the first visit to every workspace route pays
 * the full server render cost. The sidebar mounts several links at once and the
 * router prefetches whatever is in the viewport, which stacks those cold
 * renders into one slow page load. Issuing the same RSC requests right after
 * the server starts moves the cost into startup instead.
 *
 * The launcher runs this in the background; it only reports timings and always
 * exits after a bounded deadline.
 */
import { setTimeout as sleep } from "node:timers/promises";

const DEFAULT_BASE_URL = "http://127.0.0.1:33018";
const DEFAULT_PATHS = [
  "/",
  "/workspace",
  "/workspace/chats",
  "/workspace/agents",
  "/workspace/scheduled-tasks",
  "/workspace/capabilities",
  "/workspace/trash",
];
const DEFAULT_READY_TIMEOUT_MS = 180_000;
const DEFAULT_REQUEST_TIMEOUT_MS = 120_000;
const DEFAULT_PASSES = 2;
const READY_POLL_INTERVAL_MS = 500;

function parseArguments(argv) {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (typeof key !== "string" || !key.startsWith("--")) {
      throw new Error(`Unexpected argument: ${String(key)}`);
    }
    if (typeof value !== "string") {
      throw new Error(`Missing value for ${key}`);
    }
    values.set(key.slice(2), value);
  }
  return values;
}

function parsePositiveInteger(raw, fallback, name) {
  if (raw === undefined) {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer; got '${raw}'`);
  }
  return value;
}

function log(event) {
  process.stdout.write(`${JSON.stringify(event)}\n`);
}

const args = parseArguments(process.argv.slice(2));
const baseUrl = (args.get("base-url") ?? DEFAULT_BASE_URL).replace(/\/+$/, "");
const paths = (args.get("paths") ?? DEFAULT_PATHS.join(","))
  .split(",")
  .map((path) => path.trim())
  .filter((path) => path.length > 0);
const readyTimeoutMs = parsePositiveInteger(
  args.get("ready-timeout-ms"),
  DEFAULT_READY_TIMEOUT_MS,
  "--ready-timeout-ms",
);
const requestTimeoutMs = parsePositiveInteger(
  args.get("request-timeout-ms"),
  DEFAULT_REQUEST_TIMEOUT_MS,
  "--request-timeout-ms",
);
const passes = parsePositiveInteger(
  args.get("passes"),
  DEFAULT_PASSES,
  "--passes",
);

async function waitForServer() {
  const deadline = Date.now() + readyTimeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseUrl}/`, {
        method: "HEAD",
        redirect: "manual",
        signal: AbortSignal.timeout(5_000),
      });
      if (response.status < 500) {
        return true;
      }
    } catch {
      // The server is not listening yet; retry until the deadline.
    }
    await sleep(READY_POLL_INTERVAL_MS);
  }
  return false;
}

async function warmPath(path) {
  const url = new URL(path, `${baseUrl}/`);
  url.searchParams.set("_rsc", "warmup");
  const startedAt = Date.now();
  try {
    const response = await fetch(url, {
      headers: {
        RSC: "1",
        "Next-Router-Prefetch": "1",
      },
      redirect: "follow",
      signal: AbortSignal.timeout(requestTimeoutMs),
    });
    // Consume the payload so the route render actually completes.
    await response.arrayBuffer();
    return {
      path,
      status: response.status,
      ok: response.ok,
      elapsedMs: Date.now() - startedAt,
    };
  } catch (error) {
    return {
      path,
      status: null,
      ok: false,
      elapsedMs: Date.now() - startedAt,
      error: error instanceof Error ? error.name : String(error),
    };
  }
}

const startedAt = Date.now();
if (!(await waitForServer())) {
  log({
    event: "frontend-warmup-unready",
    baseUrl,
    waitedMs: Date.now() - startedAt,
  });
  process.exit(1);
}

log({ event: "frontend-warmup-start", baseUrl, paths, passes });
let failures = 0;
for (let pass = 1; pass <= passes; pass += 1) {
  for (const path of paths) {
    const result = await warmPath(path);
    if (!result.ok) {
      failures += 1;
    }
    log({ event: "frontend-warmup-route", pass, ...result });
  }
}
log({
  event: "frontend-warmup-done",
  routes: paths.length,
  passes,
  failures,
  elapsedMs: Date.now() - startedAt,
});
