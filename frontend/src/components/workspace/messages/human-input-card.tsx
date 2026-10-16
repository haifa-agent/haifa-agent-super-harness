"use client";

import {
  AlertTriangleIcon,
  CheckCircle2Icon,
  CheckIcon,
  ChevronRightIcon,
  CopyIcon,
  Loader2Icon,
  MessageCircleQuestionMarkIcon,
  ShieldAlertIcon,
} from "lucide-react";
import { useId, useState, type KeyboardEvent } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useI18n } from "@/core/i18n/hooks";
import {
  buildHumanInputFormSubmissionValue,
  buildHumanInputFormSummary,
  buildInitialHumanInputFormValues,
  createHumanInputOptionResponse,
  createHumanInputTextResponse,
  readHumanInputFormValue,
  type HumanInputField,
  type HumanInputFormValue,
  type HumanInputOption,
  type HumanInputRequest,
  type HumanInputResponse,
} from "@/core/messages/human-input";
import { isIMEComposing } from "@/lib/ime";
import { cn } from "@/lib/utils";

import { MarkdownContent } from "./markdown-content";

export type HumanInputSubmitResult = boolean | void;

export function shouldSubmitHumanInputTextOnKeyDown(
  event: KeyboardEvent<HTMLTextAreaElement>,
  isComposing = false,
) {
  return (
    event.key === "Enter" &&
    !event.shiftKey &&
    !isIMEComposing(event, isComposing)
  );
}

function isEmptyFieldValue(value: HumanInputFormValue | undefined) {
  if (value === undefined) {
    return true;
  }
  if (typeof value === "string") {
    return value.trim().length === 0;
  }
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  return value === false;
}

export function findMissingRequiredFields(
  fields: HumanInputField[],
  values: Record<string, HumanInputFormValue>,
) {
  // Own-property reads only: field names like "toString" must never resolve
  // to inherited Object.prototype members and satisfy required validation.
  return fields.filter(
    (field) =>
      field.required &&
      isEmptyFieldValue(readHumanInputFormValue(values, field.name)),
  );
}

function FormFieldInput({
  field,
  value,
  disabled,
  selectPlaceholder,
  controlId,
  labelId,
  invalid,
  errorId,
  onChange,
}: {
  field: HumanInputField;
  value: HumanInputFormValue | undefined;
  disabled: boolean;
  selectPlaceholder: string;
  controlId: string;
  labelId: string;
  invalid: boolean;
  errorId?: string;
  onChange: (value: HumanInputFormValue) => void;
}) {
  const stringValue = typeof value === "string" ? value : "";
  const ariaProps = {
    "aria-required": field.required || undefined,
    "aria-invalid": invalid || undefined,
    "aria-describedby": invalid ? errorId : undefined,
  };

  if (field.type === "textarea") {
    return (
      <Textarea
        id={controlId}
        className="min-h-20 resize-y text-sm"
        disabled={disabled}
        placeholder={field.placeholder}
        value={stringValue}
        onChange={(event) => onChange(event.target.value)}
        {...ariaProps}
      />
    );
  }

  if (field.type === "select") {
    return (
      <Select
        disabled={disabled}
        value={stringValue}
        onValueChange={(next) => onChange(next)}
      >
        <SelectTrigger
          id={controlId}
          className="w-full"
          aria-labelledby={labelId}
          {...ariaProps}
        >
          <SelectValue placeholder={field.placeholder ?? selectPlaceholder} />
        </SelectTrigger>
        <SelectContent>
          {(field.options ?? []).map((option) => (
            <SelectItem key={option.id} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    );
  }

  if (field.type === "multi_select") {
    const selectedValues = Array.isArray(value) ? value : [];
    return (
      <div
        id={controlId}
        className="flex flex-wrap gap-2"
        role="group"
        aria-labelledby={labelId}
        aria-describedby={invalid ? errorId : undefined}
      >
        {(field.options ?? []).map((option) => {
          const selected = selectedValues.includes(option.value);
          return (
            <Button
              key={option.id}
              className={cn(
                "h-8 w-fit rounded-md px-2.5 text-left leading-5 whitespace-normal",
                selected && "border-primary/40 bg-primary/10",
              )}
              aria-pressed={selected}
              disabled={disabled}
              type="button"
              variant="outline"
              onClick={() => {
                onChange(
                  selected
                    ? selectedValues.filter((entry) => entry !== option.value)
                    : [...selectedValues, option.value],
                );
              }}
            >
              <span
                className={cn(
                  "border-border flex size-3.5 shrink-0 items-center justify-center rounded-sm border",
                  selected &&
                    "border-primary bg-primary text-primary-foreground",
                )}
                aria-hidden
              >
                {selected ? <CheckIcon className="size-2.5" /> : null}
              </span>
              {option.label}
            </Button>
          );
        })}
      </div>
    );
  }

  return (
    <Input
      id={controlId}
      className="text-sm"
      disabled={disabled}
      placeholder={field.placeholder}
      type={
        field.type === "number"
          ? "number"
          : field.type === "date"
            ? "date"
            : "text"
      }
      value={stringValue}
      onChange={(event) => onChange(event.target.value)}
      {...ariaProps}
    />
  );
}

export function HumanInputCard({
  request,
  disabled = false,
  pending = false,
  answeredResponse = null,
  onSubmit,
}: {
  request: HumanInputRequest;
  disabled?: boolean;
  pending?: boolean;
  answeredResponse?: HumanInputResponse | null;
  onSubmit?: (
    response: HumanInputResponse,
  ) => HumanInputSubmitResult | Promise<HumanInputSubmitResult>;
}) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const [error, setError] = useState("");
  const [isComposing, setIsComposing] = useState(false);
  const [formValues, setFormValues] = useState<
    Record<string, HumanInputFormValue>
  >(() => buildInitialHumanInputFormValues(request.fields ?? []));
  const [invalidFieldNames, setInvalidFieldNames] = useState<Set<string>>(
    () => new Set(),
  );
  const titleId = useId();
  const textInputId = useId();
  const formFieldIdBase = useId();
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const isRiskConfirmation =
    request.clarification_type === "risk_confirmation" ||
    Boolean(request.risk_level) ||
    Boolean(request.content);

  const isHighRisk =
    request.risk_level === "HIGH" || request.risk_level === "CRITICAL";
  const riskLabel = isHighRisk
    ? "高危操作"
    : request.risk_level === "MEDIUM"
      ? "中等风险"
      : request.risk_level
        ? `${request.risk_level} 风险`
        : isRiskConfirmation
          ? "特权审批"
          : null;

  const handleCopyContent = async () => {
    if (!request.content) return;
    try {
      await navigator.clipboard.writeText(request.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // ignore clipboard error in restricted headless environments
    }
  };

  const formErrorId = `${formFieldIdBase}-error`;
  const isForm = request.input_mode === "form";
  const allowText =
    request.input_mode === "free_text" ||
    request.input_mode === "choice_with_other";
  const options = request.options ?? [];
  const fields = request.fields ?? [];
  const readOnly = !onSubmit;
  const isDisabled =
    disabled || pending || Boolean(answeredResponse) || readOnly;
  const statusLabel = answeredResponse
    ? t.humanInput.answered
    : pending
      ? t.humanInput.pending
      : readOnly
        ? t.humanInput.readOnly
        : null;

  const submitResponse = async (response: HumanInputResponse) => {
    if (isDisabled || !onSubmit) {
      return;
    }
    setError("");
    const result = await onSubmit(response);
    if (result !== false && response.response_kind === "text") {
      setText("");
    }
  };

  const handleOptionClick = (option: HumanInputOption) => {
    void submitResponse(createHumanInputOptionResponse(request, option));
  };

  const handleFormValueChange = (name: string, value: HumanInputFormValue) => {
    const remaining = new Set(invalidFieldNames);
    remaining.delete(name);
    setInvalidFieldNames(remaining);
    // Keep the error node mounted while other fields are still invalid —
    // their aria-describedby must keep pointing at an existing element.
    if (remaining.size === 0) {
      setError("");
    }
    setFormValues((previous) => ({ ...previous, [name]: value }));
  };

  const handleFormSubmit = (event: { preventDefault(): void }) => {
    event.preventDefault();
    const missing = findMissingRequiredFields(fields, formValues);
    if (missing.length > 0) {
      setInvalidFieldNames(new Set(missing.map((field) => field.name)));
      setError(t.humanInput.requiredError);
      return;
    }
    // Per the request-side-only protocol scope, form answers are submitted as
    // a readable v1 text response — no structured response kind is introduced.
    // The submitted value carries a JSON block keyed by stable field names so
    // the model can reconstruct the mapping unambiguously.
    if (!buildHumanInputFormSummary(request, formValues).trim()) {
      setError(t.humanInput.emptyError);
      return;
    }
    void submitResponse(
      createHumanInputTextResponse(
        request,
        buildHumanInputFormSubmissionValue(request, formValues),
      ),
    );
  };

  const handleTextSubmit = (event: { preventDefault(): void }) => {
    event.preventDefault();
    const value = text.trim();
    if (!value) {
      setError(t.humanInput.emptyError);
      return;
    }
    void submitResponse(createHumanInputTextResponse(request, value));
  };

  const handleTextKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (shouldSubmitHumanInputTextOnKeyDown(event, isComposing)) {
      event.preventDefault();
      const value = text.trim();
      if (!value) {
        setError(t.humanInput.emptyError);
        return;
      }
      void submitResponse(createHumanInputTextResponse(request, value));
    }
  };

  const submitFooter = (
    <div className="flex min-h-9 flex-wrap items-center justify-between gap-2">
      {error ? (
        <p className="text-destructive text-sm" id={formErrorId} role="alert">
          {error}
        </p>
      ) : answeredResponse ? (
        <p className="text-muted-foreground text-sm" aria-live="polite">
          {t.humanInput.answeredValue(answeredResponse.value)}
        </p>
      ) : (
        <span />
      )}
      <Button
        className="min-w-24"
        disabled={isDisabled}
        type="submit"
        variant="secondary"
      >
        {pending ? <Loader2Icon className="size-4 animate-spin" /> : null}
        {t.humanInput.submit}
      </Button>
    </div>
  );

  return (
    <section
      aria-labelledby={titleId}
      className="border-border bg-card/70 text-card-foreground rounded-lg border p-4 shadow-xs"
      data-testid="human-input-card"
    >
      <div className="flex items-start gap-3">
        <div
          className={cn(
            "mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md border",
            isRiskConfirmation
              ? isHighRisk
                ? "border-rose-500/20 bg-rose-500/10 text-rose-600 dark:text-rose-400"
                : "border-amber-500/20 bg-amber-500/10 text-amber-600 dark:text-amber-400"
              : "bg-primary/10 text-primary border-transparent",
          )}
        >
          {isRiskConfirmation ? (
            isHighRisk ? (
              <ShieldAlertIcon className="size-4" />
            ) : (
              <AlertTriangleIcon className="size-4" />
            )
          ) : (
            <MessageCircleQuestionMarkIcon className="size-4" />
          )}
        </div>
        <div className="min-w-0 flex-1 space-y-3">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div className="min-w-0 space-y-1">
              <div className="flex items-center gap-2 flex-wrap">
                <h2 id={titleId} className="text-sm leading-5 font-semibold">
                  {request.title ?? t.toolCalls.needYourHelp}
                </h2>
                {riskLabel ? (
                  <span
                    className={cn(
                      "text-xs px-2 py-0.5 rounded-full border font-medium flex items-center gap-1",
                      isHighRisk
                        ? "border-rose-500/20 bg-rose-500/10 text-rose-600 dark:text-rose-400"
                        : "border-amber-500/20 bg-amber-500/10 text-amber-600 dark:text-amber-400",
                    )}
                  >
                    <span
                      className={cn(
                        "size-1.5 rounded-full",
                        isHighRisk ? "bg-rose-500 animate-pulse" : "bg-amber-500",
                      )}
                    />
                    {riskLabel}
                  </span>
                ) : null}
              </div>
              {request.context ? (
                <div className="text-muted-foreground text-sm leading-6">
                  <MarkdownContent
                    content={request.context}
                    isLoading={false}
                  />
                </div>
              ) : null}
            </div>
            {statusLabel ? (
              <Badge
                className={cn(
                  "h-6 rounded-md px-2",
                  pending && "gap-1.5",
                  answeredResponse &&
                    "border-primary/20 bg-primary/10 text-primary",
                )}
                variant={answeredResponse ? "outline" : "secondary"}
              >
                {pending ? (
                  <Loader2Icon className="size-3 animate-spin" />
                ) : null}
                {answeredResponse ? (
                  <CheckCircle2Icon className="size-3" />
                ) : null}
                {statusLabel}
              </Badge>
            ) : null}
          </div>

          {request.purpose ? (
            <div className="bg-secondary/40 border border-border/60 rounded-md p-3 text-xs leading-relaxed space-y-1">
              <span className="font-semibold text-foreground block">
                💡 执行目的：
              </span>
              <p className="text-foreground">{request.purpose}</p>
            </div>
          ) : (
            <div className="text-foreground text-sm leading-6">
              <MarkdownContent content={request.question} isLoading={false} />
            </div>
          )}

          {request.content ? (
            <div className="space-y-1.5">
              <div className="flex items-center justify-between text-xs text-muted-foreground px-0.5">
                <span className="font-medium flex items-center gap-1.5">
                  即将执行的内容：
                </span>
                <button
                  type="button"
                  onClick={handleCopyContent}
                  className="hover:text-foreground flex items-center gap-1 transition-colors px-1.5 py-0.5 rounded hover:bg-secondary text-xs"
                >
                  {copied ? (
                    <>
                      <CheckIcon className="size-3 text-emerald-500" />
                      <span>已复制</span>
                    </>
                  ) : (
                    <>
                      <CopyIcon className="size-3" />
                      <span>复制</span>
                    </>
                  )}
                </button>
              </div>
              <div className="relative rounded-lg overflow-hidden border border-slate-700/60 bg-slate-900 text-slate-100 font-mono text-xs shadow-inner">
                <div className="bg-slate-800/80 px-3 py-1.5 border-b border-slate-700/50 flex items-center justify-between text-[11px] text-slate-400">
                  <div className="flex items-center gap-1.5">
                    <span className="size-2 rounded-full bg-rose-500/80 inline-block" />
                    <span className="size-2 rounded-full bg-amber-500/80 inline-block" />
                    <span className="size-2 rounded-full bg-emerald-500/80 inline-block" />
                    <span className="ml-2 font-medium text-slate-300">
                      {request.content_type || "command"}
                    </span>
                  </div>
                </div>
                <div className="p-3 overflow-x-auto max-h-48">
                  <pre className="text-slate-200 leading-5 whitespace-pre-wrap break-all font-mono">
                    {request.content}
                  </pre>
                </div>
              </div>
            </div>
          ) : null}

          {request.environment_facts && request.environment_facts.length > 0 ? (
            <div className="flex flex-wrap gap-2 pt-0.5">
              {request.environment_facts.map((fact) => (
                <span
                  key={fact.label}
                  className="inline-flex items-center gap-1 text-[11px] bg-secondary/60 px-2 py-0.5 rounded text-muted-foreground border border-border/40"
                >
                  <span>{fact.label}:</span>
                  <span className="text-foreground font-mono">{fact.value}</span>
                </span>
              ))}
            </div>
          ) : null}

          {request.technical_facts && request.technical_facts.length > 0 ? (
            <div className="pt-1">
              <button
                type="button"
                onClick={() => setDetailsOpen(!detailsOpen)}
                className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1 font-medium transition-colors"
              >
                <ChevronRightIcon
                  className={cn(
                    "size-3.5 transition-transform",
                    detailsOpen && "rotate-90",
                  )}
                />
                <span>
                  {detailsOpen
                    ? "收起技术与审计详情"
                    : "展开查看技术与审计详情 (底层工具、版本及签名摘要)"}
                </span>
              </button>
              {detailsOpen ? (
                <div className="mt-2 p-3 rounded-lg bg-background/80 border border-border text-xs space-y-1.5 font-mono">
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px]">
                    {request.technical_facts.map((fact) => (
                      <div key={fact.label} className="break-all">
                        <span className="text-muted-foreground">{fact.label}: </span>
                        <span className="text-foreground">{fact.value}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}

          {isForm ? (
            <form className="space-y-4" onSubmit={handleFormSubmit}>
              {fields.map((field, index) => {
                const controlId = `${formFieldIdBase}-${index}`;
                const labelId = `${controlId}-label`;
                const fieldValue = readHumanInputFormValue(
                  formValues,
                  field.name,
                );
                const invalid = invalidFieldNames.has(field.name);

                if (field.type === "checkbox") {
                  const checked = fieldValue === true;
                  // Native checkbox: real role/checked semantics for assistive
                  // technology instead of an aria-pressed button. No HTML
                  // `required` attribute — native constraint validation would
                  // intercept the form's custom submit handling.
                  return (
                    <label
                      key={field.name}
                      className="flex w-fit cursor-pointer items-center gap-2 text-sm leading-5"
                      htmlFor={controlId}
                    >
                      <input
                        id={controlId}
                        checked={checked}
                        className="accent-primary size-4"
                        disabled={isDisabled}
                        type="checkbox"
                        aria-required={field.required || undefined}
                        aria-invalid={invalid || undefined}
                        aria-describedby={invalid ? formErrorId : undefined}
                        onChange={(event) =>
                          handleFormValueChange(
                            field.name,
                            event.target.checked,
                          )
                        }
                      />
                      {field.label}
                      {field.required ? (
                        <>
                          <span className="text-destructive" aria-hidden>
                            *
                          </span>
                          <span className="sr-only">
                            {t.humanInput.requiredA11yLabel}
                          </span>
                        </>
                      ) : null}
                    </label>
                  );
                }

                return (
                  <div key={field.name} className="space-y-1.5">
                    <label
                      className="text-sm leading-5 font-medium"
                      htmlFor={controlId}
                      id={labelId}
                    >
                      {field.label}
                      {field.required ? (
                        <>
                          <span className="text-destructive ml-0.5" aria-hidden>
                            *
                          </span>
                          <span className="sr-only">
                            {t.humanInput.requiredA11yLabel}
                          </span>
                        </>
                      ) : null}
                    </label>
                    <FormFieldInput
                      controlId={controlId}
                      disabled={isDisabled}
                      errorId={formErrorId}
                      field={field}
                      invalid={invalid}
                      labelId={labelId}
                      selectPlaceholder={t.humanInput.selectPlaceholder}
                      value={fieldValue}
                      onChange={(value) =>
                        handleFormValueChange(field.name, value)
                      }
                    />
                  </div>
                );
              })}
              {submitFooter}
            </form>
          ) : null}

          {!isForm && options.length > 0 ? (
            isRiskConfirmation ? (
              <div className="pt-3 border-t border-border flex flex-wrap items-center justify-between gap-3">
                {answeredResponse ? (
                  <div className="flex items-center gap-2 text-xs">
                    <span
                      className={cn(
                        "size-2 rounded-full",
                        answeredResponse.value.toLowerCase().includes("approve")
                          ? "bg-emerald-500"
                          : "bg-rose-500",
                      )}
                    />
                    <span className="font-medium text-foreground">
                      {answeredResponse.value.toLowerCase().includes("approve")
                        ? "已批准执行 (Approved)"
                        : "已拒绝执行 (Rejected)"}
                    </span>
                    <span className="text-muted-foreground ml-1" aria-live="polite">
                      {t.humanInput.answeredValue(answeredResponse.value)}
                    </span>
                  </div>
                ) : (
                  <span className="text-xs text-muted-foreground">
                    审批确认后任务将继续执行
                  </span>
                )}
                <div className="flex items-center gap-2">
                  {options.map((option) => {
                    const isApprove = option.id.toLowerCase().includes("approve");
                    const isChosen = answeredResponse?.value === option.value;
                    return (
                      <Button
                        key={option.id}
                        disabled={isDisabled}
                        type="button"
                        variant={isApprove ? "default" : "outline"}
                        className={cn(
                          "text-xs font-medium h-8 px-3.5",
                          isApprove
                            ? "bg-emerald-600 hover:bg-emerald-700 text-white shadow-xs"
                            : "hover:bg-destructive/10 hover:border-destructive/30 hover:text-destructive",
                          answeredResponse && !isChosen && "opacity-50",
                        )}
                        onClick={() => handleOptionClick(option)}
                      >
                        {pending ? (
                          <Loader2Icon className="size-3.5 animate-spin mr-1" />
                        ) : isApprove ? (
                          <CheckIcon className="size-3.5 mr-1" />
                        ) : null}
                        {option.label}
                      </Button>
                    );
                  })}
                </div>
              </div>
            ) : (
              <div className="grid gap-2">
                {options.map((option) => (
                  <Button
                    key={option.id}
                    className="min-h-11 w-full justify-start rounded-md px-3 py-2 text-left leading-5 whitespace-normal"
                    disabled={isDisabled}
                    type="button"
                    variant="outline"
                    onClick={() => handleOptionClick(option)}
                  >
                    <span className="min-w-0 wrap-break-word whitespace-pre-wrap">
                      {option.label}
                    </span>
                  </Button>
                ))}
              </div>
            )
          ) : null}

          {allowText ? (
            <form className="space-y-2" onSubmit={handleTextSubmit}>
              <label className="sr-only" htmlFor={textInputId}>
                {t.humanInput.otherLabel}
              </label>
              <Textarea
                id={textInputId}
                aria-invalid={Boolean(error)}
                aria-describedby={error ? `${textInputId}-error` : undefined}
                className="min-h-20 resize-y text-sm"
                disabled={isDisabled}
                placeholder={t.humanInput.otherPlaceholder}
                value={text}
                onChange={(event) => {
                  setText(event.target.value);
                  if (error) {
                    setError("");
                  }
                }}
                onCompositionEnd={() => setIsComposing(false)}
                onCompositionStart={() => setIsComposing(true)}
                onKeyDown={handleTextKeyDown}
              />
              <div className="flex min-h-9 flex-wrap items-center justify-between gap-2">
                {error ? (
                  <p
                    className="text-destructive text-sm"
                    id={`${textInputId}-error`}
                  >
                    {error}
                  </p>
                ) : answeredResponse ? (
                  <p
                    className="text-muted-foreground text-sm"
                    aria-live="polite"
                  >
                    {t.humanInput.answeredValue(answeredResponse.value)}
                  </p>
                ) : (
                  <span />
                )}
                <Button
                  className="min-w-24"
                  disabled={isDisabled}
                  type="submit"
                  variant="secondary"
                >
                  {pending ? (
                    <Loader2Icon className="size-4 animate-spin" />
                  ) : null}
                  {t.humanInput.submit}
                </Button>
              </div>
            </form>
          ) : null}

          {!allowText && !isForm && !isRiskConfirmation && answeredResponse ? (
            <p className="text-muted-foreground text-sm" aria-live="polite">
              {t.humanInput.answeredValue(answeredResponse.value)}
            </p>
          ) : null}
        </div>
      </div>
    </section>
  );
}
