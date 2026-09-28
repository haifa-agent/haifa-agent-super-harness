import { afterEach, beforeEach, describe, expect, it, rs } from "@rstest/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { toast } from "sonner";

import { PromptInputProvider } from "@/components/ai-elements/prompt-input";
import { InputBox } from "@/components/workspace/input-box";
import { ThreadContext } from "@/components/workspace/messages/context";
import { AuthProvider } from "@/core/auth/AuthProvider";
import { DEFAULT_LOCALE } from "@/core/i18n";
import { I18nProvider } from "@/core/i18n/context";

rs.mock("next/navigation", () => ({
  useRouter: () => ({ push: rs.fn(), replace: rs.fn(), refresh: rs.fn() }),
  usePathname: () => "/workspace/chats/thread-1",
  useSearchParams: () => new URLSearchParams(),
}));

rs.mock("@/core/models/hooks", () => ({
  useModels: () => ({
    models: [],
    tokenUsageEnabled: false,
    isLoading: false,
    isFetching: false,
    error: null,
    refetch: rs.fn(),
  }),
}));

type ComposerProps = {
  canCreateRuns?: boolean;
  onSteer?: (message: { text: string; files: unknown[] }) => Promise<unknown>;
  onSubmit?: (message: { text: string; files: unknown[] }) => Promise<void>;
  status?: "ready" | "streaming";
};

function renderComposer({
  canCreateRuns,
  onSteer,
  onSubmit,
  status = "streaming",
}: ComposerProps) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const tree: ReactNode = (
    <I18nProvider initialLocale={DEFAULT_LOCALE}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider
          initialUser={{
            id: "user-1",
            email: "user@example.test",
            system_role: "user",
            needs_setup: false,
            oauth_provider: null,
          }}
        >
          <ThreadContext.Provider
            value={{ thread: { messages: [] } as never, isMock: true }}
          >
            <PromptInputProvider>
              <InputBox
                threadId="thread-1"
                status={status}
                context={{ mode: "flash" } as never}
                canCreateRuns={canCreateRuns}
                onSteer={onSteer as never}
                onSubmit={onSubmit as never}
              />
            </PromptInputProvider>
          </ThreadContext.Provider>
        </AuthProvider>
      </QueryClientProvider>
    </I18nProvider>
  );
  return render(tree);
}

function setText(container: HTMLElement, text: string) {
  const textarea = container.querySelector("textarea");
  if (!(textarea instanceof HTMLTextAreaElement)) {
    throw new Error("composer textarea not rendered");
  }
  fireEvent.change(textarea, { target: { value: text } });
  return textarea;
}

function submit(container: HTMLElement) {
  const form = container.querySelector("form");
  if (!(form instanceof HTMLFormElement)) {
    throw new Error("composer form not rendered");
  }
  fireEvent.submit(form);
}

beforeEach(() => {
  window.sessionStorage.clear();
  rs.spyOn(URL, "createObjectURL").mockReturnValue("blob:test-attachment");
  rs.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
});

afterEach(() => {
  rs.restoreAllMocks();
  cleanup();
});

describe("InputBox active Steer", () => {
  it("routes streaming plain text to Steer while ready text stays on normal submit", async () => {
    const onSteer = rs.fn(async () => undefined);
    const onSubmit = rs.fn(async () => undefined);
    const streaming = renderComposer({ onSteer, onSubmit });
    setText(streaming.container, "  revise this answer  ");
    submit(streaming.container);
    await waitFor(() => expect(onSteer).toHaveBeenCalledTimes(1));
    expect(onSteer).toHaveBeenCalledWith({
      text: "revise this answer",
      files: [],
    });
    expect(onSubmit).not.toHaveBeenCalled();
    streaming.unmount();

    const ready = renderComposer({ status: "ready", onSteer, onSubmit });
    setText(ready.container, "start a new turn");
    submit(ready.container);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSteer).toHaveBeenCalledTimes(1);
  });

  it("prevents duplicate in-flight Steer requests", async () => {
    let resolveSteer: (() => void) | undefined;
    const onSteer = rs.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveSteer = resolve;
        }),
    );
    const { container } = renderComposer({ onSteer });
    setText(container, "one instruction");

    submit(container);
    submit(container);
    await waitFor(() => expect(onSteer).toHaveBeenCalledTimes(1));
    resolveSteer?.();
    await waitFor(() =>
      expect(container.querySelector("textarea")?.value).toBe(""),
    );
  });

  it("keeps input visible and surfaces 403/409/422 or network failures", async () => {
    const errorToast = rs.spyOn(toast, "error");
    const onSteer = rs.fn(async () => {
      throw new Error("Thread already has an incompatible active input");
    });
    const { container } = renderComposer({ onSteer });
    const textarea = setText(container, "keep this instruction");

    submit(container);
    await waitFor(() => expect(onSteer).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(textarea.value).toBe("keep this instruction"));
    expect(errorToast).toHaveBeenCalledWith(
      "Thread already has an incompatible active input",
    );
  });

  it("fails closed for attachments and does not route them as normal sends", async () => {
    const errorToast = rs.spyOn(toast, "error");
    const onSteer = rs.fn(async () => undefined);
    const onSubmit = rs.fn(async () => undefined);
    const { container } = renderComposer({ onSteer, onSubmit });
    setText(container, "use this attachment");
    const fileInput = container.querySelector('input[type="file"]');
    if (!(fileInput instanceof HTMLInputElement)) {
      throw new Error("file input not rendered");
    }
    fireEvent.change(fileInput, {
      target: {
        files: [new File(["demo"], "demo.txt", { type: "text/plain" })],
      },
    });

    submit(container);
    await waitFor(() =>
      expect(errorToast).toHaveBeenCalledWith(
        expect.stringContaining("plain-text"),
      ),
    );
    expect(onSteer).not.toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("requires runs:create for the Enter Steer path", async () => {
    const infoToast = rs.spyOn(toast, "info");
    const onSteer = rs.fn(async () => undefined);
    const { container } = renderComposer({ canCreateRuns: false, onSteer });
    setText(container, "revise this answer");

    submit(container);
    await waitFor(() =>
      expect(infoToast).toHaveBeenCalledWith(
        expect.stringContaining("not permitted"),
      ),
    );
    expect(onSteer).not.toHaveBeenCalled();
  });
});
