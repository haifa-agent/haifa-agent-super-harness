import { afterEach, beforeEach, describe, expect, it, rs } from "@rstest/core";
import type { NextRequest } from "next/server";

describe("memory BFF gateway target", () => {
  let savedGatewayUrl: string | undefined;

  beforeEach(() => {
    savedGatewayUrl = process.env.DEER_FLOW_INTERNAL_GATEWAY_BASE_URL;
    process.env.DEER_FLOW_INTERNAL_GATEWAY_BASE_URL =
      "http://127.0.0.1:18018";
    rs.resetModules();
  });

  afterEach(() => {
    if (savedGatewayUrl === undefined) {
      delete process.env.DEER_FLOW_INTERNAL_GATEWAY_BASE_URL;
    } else {
      process.env.DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = savedGatewayUrl;
    }
    rs.restoreAllMocks();
  });

  it("uses the server-only Haifa gateway for root and nested memory routes", async () => {
    const fetch = rs
      .spyOn(globalThis, "fetch")
      .mockImplementation(async () => Response.json({ ok: true }));
    const rootRoute = await import("@/app/api/memory/route");
    const nestedRoute = await import("@/app/api/memory/[...path]/route");

    await rootRoute.GET(
      new Request("http://127.0.0.1:33018/api/memory") as NextRequest,
    );
    await nestedRoute.POST(
      new Request("http://127.0.0.1:33018/api/memory/facts", {
        method: "POST",
        body: "{}",
      }) as NextRequest,
      { params: Promise.resolve({ path: ["facts"] }) },
    );

    expect(fetch).toHaveBeenNthCalledWith(
      1,
      new URL("http://127.0.0.1:18018/api/memory"),
      expect.objectContaining({ method: "GET" }),
    );
    expect(fetch).toHaveBeenNthCalledWith(
      2,
      new URL("http://127.0.0.1:18018/api/memory/facts"),
      expect.objectContaining({ method: "POST" }),
    );
  });
});
