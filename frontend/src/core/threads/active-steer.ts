import { fetch as fetchWithAuth } from "../api/fetcher";
import { getBackendBaseURL } from "../config";

export type ActiveSteerRequest = {
  assistantId: string;
  context: Record<string, unknown>;
  messageId: string;
  text: string;
  threadId: string;
};

export type ActiveSteerResult = {
  messageId: string;
};

type SteerFetch = (
  input: RequestInfo | string,
  init?: RequestInit,
) => Promise<Response>;

/**
 * Build the deliberately small active-input payload accepted by the Haifa
 * Gateway.  In particular this is not a normal LangGraph multitask submit:
 * the explicit header is the sole opt-in to same-Run Steer.
 */
export function buildActiveSteerBody({
  assistantId,
  context,
  messageId,
  text,
}: Omit<ActiveSteerRequest, "threadId">): Record<string, unknown> {
  return {
    assistant_id: assistantId,
    input: {
      messages: [
        {
          id: messageId,
          type: "human",
          content: [{ type: "text", text }],
          additional_kwargs: {},
        },
      ],
    },
    stream_mode: ["messages-tuple", "updates", "custom"],
    context,
    on_disconnect: "continue",
  };
}

async function readSteerError(response: Response): Promise<string> {
  try {
    const data = (await response.json()) as { detail?: unknown };
    if (typeof data.detail === "string" && data.detail.trim()) {
      return data.detail;
    }
  } catch {
    // Fall through to the bounded status message below.
  }
  return response.statusText || `HTTP ${response.status}`;
}

/**
 * Submit one explicit same-Run Steer input without adopting the extra SSE
 * response as a second UI authority.  The creator/reconnect stream remains
 * authoritative; after the 200 headers confirm admission, this observer body
 * is cancelled.  The Gateway's Steer observer is non-creator, so disconnecting
 * it cannot cancel or replace the active Run.
 */
export async function postActiveSteer(
  request: ActiveSteerRequest,
  requestFetch: SteerFetch = fetchWithAuth,
): Promise<ActiveSteerResult> {
  const text = request.text.trim();
  if (!text) {
    throw new Error("Steer input must not be empty.");
  }

  const response = await requestFetch(
    `${getBackendBaseURL()}/api/threads/${encodeURIComponent(
      request.threadId,
    )}/runs/stream`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Haifa-Run-Input": "steer",
      },
      body: JSON.stringify(
        buildActiveSteerBody({
          assistantId: request.assistantId,
          context: request.context,
          messageId: request.messageId,
          text,
        }),
      ),
    },
  );

  if (!response.ok) {
    throw new Error(await readSteerError(response));
  }

  // Admission is complete once fetch resolves with 200 response headers.
  // Do not consume a duplicate event stream; the existing active stream owns
  // all message/state rendering. Failure to cancel an already accepted
  // observer must not turn the accepted input into a misleading UI failure.
  try {
    await response.body?.cancel();
  } catch {
    // The browser may already have closed a header-only/test response.
  }
  return { messageId: request.messageId };
}
