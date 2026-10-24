import type { Message } from "@langchain/langgraph-sdk";
import { afterEach, expect, test } from "@rstest/core";
import { cleanup, render, screen } from "@testing-library/react";

import { RunFailureNotice } from "@/components/workspace/messages/run-failure-notice";

afterEach(cleanup);
const human = (failure = false): Message => ({
  id: "human",
  type: "human",
  content: "today?",
  additional_kwargs: failure
    ? { run_error: { diagnostic_id: "diagnostic-123" } }
    : {},
});

test("persisted current Run failure survives a reload without a stream error", () => {
  render(
    <RunFailureNotice
      messages={[human(true)]}
      error={null}
      isLoading={false}
      label="Run failed"
    />,
  );
  expect(screen.getByRole("alert").textContent).toContain("Run failed");
  expect(screen.getByRole("alert").textContent).toContain("diagnostic-123");
});

test("a stream error is visible even without a new assistant message", () => {
  render(
    <RunFailureNotice
      messages={[human()]}
      error={new Error("failed")}
      isLoading={false}
      label="Run failed"
    />,
  );
  expect(screen.getByRole("alert").textContent).toBe("Run failed");
});

test("new submission and streaming do not retain the earlier failure notice", () => {
  const { rerender } = render(
    <RunFailureNotice
      messages={[human(true), human()]}
      error={null}
      isLoading={false}
      label="Run failed"
    />,
  );
  expect(screen.queryByRole("alert")).toBeNull();
  rerender(
    <RunFailureNotice
      messages={[human(true)]}
      error={null}
      isLoading={true}
      label="Run failed"
    />,
  );
  expect(screen.queryByRole("alert")).toBeNull();
});

test("hidden control replies do not hide the latest visible human failure", () => {
  const hidden = { ...human(), additional_kwargs: { hide_from_ui: true } };
  render(
    <RunFailureNotice
      messages={[human(true), hidden]}
      error={null}
      isLoading={false}
      label="Run failed"
    />,
  );
  expect(screen.getByRole("alert").textContent).toContain("diagnostic-123");
});
