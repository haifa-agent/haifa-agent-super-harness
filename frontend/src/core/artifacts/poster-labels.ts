import type { Message } from "@langchain/langgraph-sdk";

import { getFileName } from "../utils/files";

const DRAFT = /^(.+)-(A稿|B稿|A-B对比)-v([1-9][0-9]*)\.png$/u;

export function currentPosterPath(messages: readonly Message[] = []) {
  for (let index = messages.length - 1; index >= 0; index--) {
    const message = messages[index];
    if (
      message?.type !== "tool" ||
      (message.name !== "poster_edit" && message.name !== "poster_render")
    ) {
      continue;
    }
    const result = message.additional_kwargs?.haifa_tool_result;
    if (!result || typeof result !== "object" || !("currentPoster" in result)) {
      continue;
    }
    const current = result.currentPoster;
    if (
      typeof current === "string" &&
      current.startsWith("/mnt/user-data/outputs/") &&
      DRAFT.test(getFileName(current))
    ) {
      return current;
    }
  }
}

export function posterArtifactLabel(
  filepath: string,
  current: string | undefined,
  locale: string,
) {
  const filename = getFileName(filepath);
  const match = DRAFT.exec(filename);
  if (!match) return filename;
  const [, stem, draft, version] = match;
  if (!stem || !draft || !version) return filename;
  const label =
    locale === "zh-CN"
      ? draft === "A-B对比"
        ? "A/B 对比"
        : draft.replace("稿", " 稿")
      : draft === "A-B对比"
        ? "A/B comparison"
        : `Draft ${draft[0]}`;
  const suffix =
    filepath === current
      ? locale === "zh-CN"
        ? " · 当前版本"
        : " · Current"
      : "";
  return `${stem.replaceAll("-", " ")} · ${label} · v${version}${suffix}`;
}
