"use client";

import {
  ChevronRightIcon,
  FilePenLineIcon,
  FilePlusIcon,
  GlobeIcon,
  Loader2Icon,
  SearchIcon,
  TerminalIcon,
  WrenchIcon,
} from "lucide-react";
import { useEffect, useId, useRef } from "react";

import { Button } from "@/components/ui/button";
import { useI18n } from "@/core/i18n/hooks";
import {
  createHumanInputOptionResponse,
  type HumanInputRequest,
  type HumanInputResponse,
} from "@/core/messages/human-input";
import { cn } from "@/lib/utils";

import type { HumanInputSubmitResult } from "./human-input-card";

const toolIcons = new Map([
  ["write_file", FilePlusIcon],
  ["str_replace", FilePenLineIcon],
  ["web_search", SearchIcon],
  ["web_fetch", GlobeIcon],
  ["execution_run", TerminalIcon],
]);

export function ToolApprovalCard({
  request,
  disabled,
  pending,
  answeredResponse,
  onSubmit,
}: {
  request: HumanInputRequest;
  disabled: boolean;
  pending: boolean;
  answeredResponse: HumanInputResponse | null;
  onSubmit?: (
    response: HumanInputResponse,
  ) => HumanInputSubmitResult | Promise<HumanInputSubmitResult>;
}) {
  const { t } = useI18n();
  const labels = t.toolApproval;
  const titleId = useId();
  const scopeId = useId();
  const statusRef = useRef<HTMLSpanElement>(null);
  const submittedFromCard = useRef(false);

  useEffect(() => {
    if (answeredResponse && submittedFromCard.current) {
      submittedFromCard.current = false;
      statusRef.current?.focus();
    }
  }, [answeredResponse]);

  const approval = request.tool_approval ?? request.execution_approval;
  if (!approval) {
    return null;
  }

  const decision =
    answeredResponse?.response_kind === "option"
      ? answeredResponse.option_id
      : null;
  const status = decision === "reject"
    ? labels.rejected
    : decision === "auto_approve"
      ? labels.autoApproved
      : decision
        ? labels.approved
        : pending
          ? labels.submitting
          : labels.pending;
  const result = decision === "reject"
    ? labels.rejectedResult
    : decision === "auto_approve"
      ? labels.approvedAutomatically
      : decision === "allow_session"
        ? labels.approvedForSession
        : labels.approvedOnce;
  const toolName = request.tool_approval?.tool_name ?? "execution_run";
  const Icon = toolIcons.get(toolName) ?? WrenchIcon;
  const before = request.tool_approval?.before;
  const after = request.tool_approval?.after;
  const isNetworkRead = toolName === "web_search" || toolName === "web_fetch";
  const facts = request.tool_approval?.facts ?? [
    { label: labels.environment, value: request.execution_approval?.environment },
    { label: labels.workspace, value: request.execution_approval?.workspace },
    { label: labels.network, value: request.execution_approval?.network },
  ];
  const actions = [
    { id: "reject", label: labels.reject },
    { id: "allow_session", label: labels.session },
    { id: "approve", label: labels.once },
  ];
  const sessionOption = approval.session_eligible &&
    request.options?.some((option) =>
      option.id === "allow_session" && option.value === "allow_session",
    );

  return (
    <article
      aria-labelledby={titleId}
      className="@container w-full min-w-0 overflow-hidden rounded-2xl border border-border bg-card text-card-foreground shadow-sm"
      data-testid="tool-approval-card"
      data-tool={toolName}
    >
      <div className="space-y-4 p-4 sm:p-5">
        <header className="flex items-start gap-3">
          <span className="rounded-lg bg-emerald-50 p-2.5 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300">
            <Icon aria-hidden="true" className="size-4" />
          </span>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h2 id={titleId} className="text-base font-semibold break-words">
                {request.title || labels.fallbackTitle}
              </h2>
              <span
                ref={statusRef}
                role="status"
                tabIndex={-1}
                className={cn(
                  "inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium",
                  decision === "reject"
                    ? "bg-rose-50 text-rose-800 dark:bg-rose-950 dark:text-rose-300"
                    : decision
                      ? "bg-emerald-50 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
                      : "bg-amber-50 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
                )}
              >
                {pending && !decision ? (
                  <Loader2Icon aria-hidden="true" className="size-3 animate-spin" />
                ) : (
                  <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
                )}
                {status}
              </span>
            </div>
            {request.purpose && (
              <p className="mt-1 text-sm whitespace-pre-wrap break-words text-muted-foreground">
                <span className="mr-2 font-medium text-foreground">{labels.purpose}</span>
                {request.purpose}
              </p>
            )}
          </div>
        </header>

        <div className={cn("grid gap-3", facts.length > 0 && "@lg:grid-cols-2")}>
          {facts.length > 0 && (
            <dl className="min-w-0 divide-y divide-border">
              {facts.map(({ label, value }, index) => (
                <div key={index} className="grid grid-cols-[minmax(4rem,auto)_1fr] gap-2 py-2 text-sm">
                  <dt className="text-muted-foreground">{label}</dt>
                  <dd className="min-w-0 font-medium wrap-anywhere whitespace-pre-wrap">{value || labels.unavailable}</dd>
                </div>
              ))}
            </dl>
          )}
          <section className={cn(
            "min-w-0 rounded-lg border p-3",
            isNetworkRead
              ? "border-emerald-200 bg-emerald-50/70 text-emerald-950 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-100"
              : "border-amber-200 bg-amber-50/70 text-amber-950 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-100",
          )}>
            <h3 className="text-sm font-semibold">{request.tool_approval?.impact_title || labels.impact}</h3>
            <ul className="mt-1.5 list-disc space-y-1 pl-4 text-sm break-words">
              {(approval.impact.length ? approval.impact : [labels.unknownImpact]).map(
                (item, index) => <li key={index}>{item}</li>,
              )}
            </ul>
          </section>
        </div>

        <details className="group rounded-lg border border-border bg-muted/25">
          <summary className="flex min-h-11 cursor-pointer list-none items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium focus-visible:outline-2 focus-visible:outline-ring [&::-webkit-details-marker]:hidden">
            <ChevronRightIcon aria-hidden="true" className="size-4 shrink-0 transition-transform group-open:rotate-90" />
            <span>{request.tool_approval?.detail_title || labels.details}</span>
            {(approval.detail_label || request.content_type) && (
              <span className="ml-auto text-right text-xs font-normal text-muted-foreground">
                {approval.detail_label || request.content_type}
              </span>
            )}
          </summary>
          <div className="space-y-3 p-3 pt-1">
            {(before !== undefined || after !== undefined) && (
              <div className="grid gap-2 @lg:grid-cols-2">
                <section className="min-w-0">
                  <h3 className="mb-1.5 text-xs font-semibold">{labels.before}</h3>
                  <pre className="max-h-64 overflow-auto rounded-md border border-rose-200 bg-rose-50 p-3 text-xs wrap-anywhere whitespace-pre-wrap text-rose-950 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-100">
                    <code>{before === "" ? labels.emptyContent : before ?? labels.unavailable}</code>
                  </pre>
                </section>
                <section className="min-w-0">
                  <h3 className="mb-1.5 text-xs font-semibold">{labels.after}</h3>
                  <pre className="max-h-64 overflow-auto rounded-md border border-emerald-200 bg-emerald-50 p-3 text-xs wrap-anywhere whitespace-pre-wrap text-emerald-950 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-100">
                    <code>{after === "" ? labels.emptyContent : after ?? labels.unavailable}</code>
                  </pre>
                </section>
              </div>
            )}
            {request.content && before === undefined && after === undefined && (
              <pre className="max-h-64 overflow-auto rounded-md bg-[#20372e] p-3 text-xs wrap-anywhere whitespace-pre-wrap text-white">
                <code>{request.content}</code>
              </pre>
            )}
            <dl className="grid gap-2 text-xs @lg:grid-cols-2">
              {[
                { label: labels.tool, value: toolName },
                { label: labels.requestId, value: request.request_id },
                ...(request.technical_facts ?? []),
              ].map(({ label, value }, index) => (
                <div key={index} className="min-w-0">
                  <dt className="text-muted-foreground">{label}</dt>
                  <dd className="font-mono whitespace-pre-wrap wrap-anywhere">{value}</dd>
                </div>
              ))}
            </dl>
          </div>
        </details>
      </div>

      <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-border bg-muted/20 px-4 py-3 sm:px-5">
        {decision ? (
          <div className="space-y-0.5 text-xs">
            <p className="font-medium">{result}</p>
            {decision !== "reject" && (
              <p className="text-muted-foreground">{labels.executionResult}</p>
            )}
          </div>
        ) : (
          <>
            <p id={scopeId} className="min-w-48 flex-1 text-xs text-muted-foreground">
              {sessionOption ? labels.sessionScope : labels.individualScope}
            </p>
            <div className="flex flex-wrap gap-2">
              {actions.map(({ id, label }) => {
                const option = request.options?.find(
                  (item) => item.id === id && item.value === id,
                );
                if (!option || (id === "allow_session" && !sessionOption)) {
                  return null;
                }
                return (
                  <Button
                    key={id}
                    type="button"
                    variant={id === "approve" ? "default" : "outline"}
                    disabled={disabled || pending || !onSubmit}
                    aria-describedby={id === "allow_session" ? scopeId : undefined}
                    className={cn(
                      "min-h-10",
                      id === "approve" && "bg-emerald-800 text-white hover:bg-emerald-900",
                      id === "allow_session" && "bg-emerald-50 text-emerald-900 hover:bg-emerald-100 dark:bg-emerald-950 dark:text-emerald-200 dark:hover:bg-emerald-900",
                    )}
                    onClick={async () => {
                      submittedFromCard.current = true;
                      const submitted = await onSubmit?.(createHumanInputOptionResponse(request, option));
                      if (submitted === false) {
                        submittedFromCard.current = false;
                      }
                    }}
                  >
                    {label}
                  </Button>
                );
              })}
            </div>
          </>
        )}
      </footer>
    </article>
  );
}
