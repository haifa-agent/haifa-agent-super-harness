import { readFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "@rstest/core";

const FRONTEND_ROOT = path.resolve(import.meta.dirname, "../../../..");

function source(relativePath: string) {
  return readFileSync(path.join(FRONTEND_ROOT, relativePath), "utf8");
}

describe("active Steer composer call sites", () => {
  it.each([
    "src/components/workspace/chats/chat-page.tsx",
    "src/app/workspace/agents/[agent_name]/chats/[thread_id]/page.tsx",
  ])("wires %s to the useThreadStream Steer path", (relativePath) => {
    const page = source(relativePath);

    expect(page).toMatch(/\bsendSteer,\s*\n/);
    expect(page).toContain(
      "onSteer={(message) => sendSteer(threadId, message)}",
    );
  });

  it("keeps custom-agent identity on the same stream hook", () => {
    const page = source(
      "src/app/workspace/agents/[agent_name]/chats/[thread_id]/page.tsx",
    );

    expect(page).toContain("assistantId: agent_name");
  });
});
