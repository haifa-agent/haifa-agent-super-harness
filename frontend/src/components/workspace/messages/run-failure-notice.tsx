import type { Message } from "@langchain/langgraph-sdk";

import { isHiddenFromUIMessage } from "@/core/messages/utils";

export function RunFailureNotice({
  messages,
  error,
  isLoading,
  label,
}: {
  messages: Message[];
  error: unknown;
  isLoading: boolean;
  label: string;
}) {
  let human: Message | undefined;
  for (let index = messages.length - 1; index >= 0; index--) {
    const message = messages[index]!;
    if (message.type === "human" && !isHiddenFromUIMessage(message)) {
      human = message;
      break;
    }
  }
  const failure: unknown = human?.additional_kwargs?.run_error;
  if (isLoading || (!error && (!failure || typeof failure !== "object")))
    return null;
  const diagnosticId =
    failure &&
    typeof failure === "object" &&
    "diagnostic_id" in failure &&
    typeof failure.diagnostic_id === "string"
      ? failure.diagnostic_id
      : undefined;
  return (
    <div
      role="alert"
      className="text-destructive w-full rounded-md border p-3 text-sm"
    >
      <p>{label}</p>
      {diagnosticId && (
        <p className="mt-1 font-mono text-xs break-all">{diagnosticId}</p>
      )}
    </div>
  );
}
