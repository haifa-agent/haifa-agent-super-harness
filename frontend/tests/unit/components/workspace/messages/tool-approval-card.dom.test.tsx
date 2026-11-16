import { afterEach, expect, it, rs } from "@rstest/core";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { ToolApprovalCard } from "@/components/workspace/messages/tool-approval-card";
import { I18nProvider } from "@/core/i18n/context";
import {
  createHumanInputOptionResponse,
  type HumanInputRequest,
  type HumanInputResponse,
} from "@/core/messages/human-input";

const approve = { id: "approve", label: "批准", value: "approve" };
const reject = { id: "reject", label: "拒绝", value: "reject" };
const session = {
  id: "allow_session",
  label: "本会话内允许",
  value: "allow_session",
};
const path = "/mnt/user-data/workspace/build_deck.py";
const request: HumanInputRequest = {
  version: 1,
  kind: "human_input_request",
  source: "haifa_interaction",
  request_id: "approval-1",
  title: "修改文件",
  question: "是否允许修改文件？",
  purpose: "替换匹配的文本。",
  input_mode: "single_choice",
  options: [reject, session, approve],
  tool_approval: {
    tool_name: "str_replace",
    facts: [
      { label: "目标文件", value: path },
      { label: "工作区", value: "当前任务工作区" },
    ],
    impact: ["会写入目标文件"],
    before: "old",
    after: "new",
    session_eligible: true,
  },
};

function card({
  response = null,
  pending = false,
  onSubmit,
  approvalRequest = request,
}: {
  response?: HumanInputResponse | null;
  pending?: boolean;
  onSubmit?: (response: HumanInputResponse) => boolean;
  approvalRequest?: HumanInputRequest;
} = {}) {
  return (
    <I18nProvider initialLocale="en-US">
      <ToolApprovalCard
        request={approvalRequest}
        answeredResponse={response}
        disabled={false}
        pending={pending}
        onSubmit={onSubmit}
      />
    </I18nProvider>
  );
}

afterEach(cleanup);

it("collapses and focuses the summary after a confirmed response", () => {
  const onSubmit = rs.fn(() => true);
  const { rerender } = render(card({ onSubmit }));
  expect(screen.getByText(path)).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Allow once" }));
  const response = createHumanInputOptionResponse(request, approve);
  expect(onSubmit).toHaveBeenCalledWith(response);

  rerender(card({ pending: true, onSubmit }));
  expect(screen.getByRole("status").textContent).toBe("Submitting decision");
  expect(screen.getByText(path)).toBeTruthy();

  rerender(card({ response, onSubmit }));
  const summary = screen.getByText("build_deck.py").closest("summary")!;
  expect(summary.parentElement?.hasAttribute("open")).toBe(false);
  expect(summary.textContent).toContain("Approved");
  expect(summary.textContent).not.toContain(path);
  expect(document.activeElement).toBe(summary);
  expect(screen.queryByRole("button", { name: "Allow once" })).toBeNull();
});

it.each([
  [approve, "Approved"],
  [reject, "Rejected"],
  [session, "Allow for this session"],
  [
    { id: "auto_approve", label: "自动批准", value: "auto_approve" },
    "Automatically approved",
  ],
])("starts closed for a historical response (%s)", (option, label) => {
  const { container } = render(
    card({ response: createHumanInputOptionResponse(request, option) }),
  );
  const details = container.querySelector("details")!;
  expect(details.open).toBe(false);
  expect(details.querySelector("summary")?.textContent).toContain(label);
  expect(details.querySelector("summary")?.textContent).not.toContain(
    "会写入目标文件",
  );
  expect(details.textContent).toContain(path);
  expect(details.textContent).toContain("Before");
  expect(details.textContent).toContain("After");
});

it("summarizes a legacy command by its purpose", () => {
  const approvalRequest: HumanInputRequest = {
    ...request,
    title: "运行本机命令",
    purpose: "重新构建 HTML 幻灯片",
    content: "python build_deck.py",
    tool_approval: undefined,
    execution_approval: {
      environment: "宿主机",
      impact: [],
      session_eligible: false,
    },
  };
  const response = createHumanInputOptionResponse(approvalRequest, approve);
  const { container } = render(card({ response, approvalRequest }));
  const summary = container.querySelector("summary")!;
  expect(summary.textContent).toContain("重新构建 HTML 幻灯片");
  expect(summary.textContent).not.toContain("python build_deck.py");
  expect(container.querySelector("details")?.textContent).toContain(
    "python build_deck.py",
  );
});
