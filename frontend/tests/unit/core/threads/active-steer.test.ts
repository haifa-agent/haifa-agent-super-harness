import { describe, expect, it, rs } from "@rstest/core";

import {
  buildActiveSteerBody,
  postActiveSteer,
} from "@/core/threads/active-steer";

const request = {
  assistantId: "lead_agent",
  context: { mode: "flash", thread_id: "thread/with space" },
  messageId: "local-steer-1",
  text: "  revise the answer  ",
  threadId: "thread/with space",
};

describe("active Steer request", () => {
  it("builds one explicit human message without a multitask strategy", () => {
    const body = buildActiveSteerBody({
      assistantId: request.assistantId,
      context: request.context,
      messageId: request.messageId,
      text: "revise the answer",
    });

    expect(body).toEqual({
      assistant_id: "lead_agent",
      input: {
        messages: [
          {
            id: "local-steer-1",
            type: "human",
            content: [{ type: "text", text: "revise the answer" }],
            additional_kwargs: {},
          },
        ],
      },
      stream_mode: ["messages-tuple", "updates", "custom"],
      context: request.context,
      on_disconnect: "continue",
    });
    expect(body).not.toHaveProperty("multitask_strategy");
  });

  it("marks the request explicitly, reuses auth fetch semantics, and releases the observer stream", async () => {
    const cancel = rs.fn(async () => undefined);
    const requestFetch = rs.fn(
      async (_input: RequestInfo | string, _init?: RequestInit) => {
        return {
          ok: true,
          status: 200,
          statusText: "OK",
          body: { cancel },
        } as unknown as Response;
      },
    );

    await expect(postActiveSteer(request, requestFetch)).resolves.toEqual({
      messageId: "local-steer-1",
    });
    expect(requestFetch).toHaveBeenCalledTimes(1);
    const [url, init] = requestFetch.mock.calls[0] ?? [];
    expect(url).toContain("/api/threads/thread%2Fwith%20space/runs/stream");
    expect(init?.method).toBe("POST");
    expect(new Headers(init?.headers).get("X-Haifa-Run-Input")).toBe("steer");
    const serializedBody = typeof init?.body === "string" ? init.body : "";
    expect(serializedBody).not.toBe("");
    expect(JSON.parse(serializedBody)).not.toHaveProperty("multitask_strategy");
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it.each([403, 409, 422])(
    "surfaces HTTP %s detail without retrying",
    async (status) => {
      const requestFetch = rs.fn(
        async () =>
          new Response(JSON.stringify({ detail: `rejected-${status}` }), {
            status,
            headers: { "Content-Type": "application/json" },
          }),
      );

      await expect(postActiveSteer(request, requestFetch)).rejects.toThrow(
        `rejected-${status}`,
      );
      expect(requestFetch).toHaveBeenCalledTimes(1);
    },
  );

  it("surfaces a network failure without retrying", async () => {
    const requestFetch = rs.fn(async () => {
      throw new Error("network unavailable");
    });

    await expect(postActiveSteer(request, requestFetch)).rejects.toThrow(
      "network unavailable",
    );
    expect(requestFetch).toHaveBeenCalledTimes(1);
  });
});
