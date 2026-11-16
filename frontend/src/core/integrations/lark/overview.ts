import type { ChannelProvider } from "@/core/channels/types";

import type { LarkIntegrationStatus } from "./types";

export const LARK_OFFICE_URL =
  "/workspace/capabilities?tab=plugins&plugin=lark";
export const LARK_SKILLS_URL = "/workspace/capabilities?tab=skills&source=lark";

export const calendarOperations = {
  read: ["calendar:calendar.event:read"],
  freeBusy: ["calendar:calendar.free_busy:read"],
  create: ["calendar:calendar.event:create", "calendar:calendar.event:update"],
  update: ["calendar:calendar.event:update"],
  reply: ["calendar:calendar.event:reply"],
} as const;

export function calendarGrant(
  status: LarkIntegrationStatus,
  required: readonly string[],
): "granted" | "missing" | "unknown" {
  if (status.auth.status === "error" || status.auth.status === "unavailable") {
    return "unknown";
  }
  const scopes = status.auth.granted_scopes;
  if (scopes == null) {
    return status.auth.user_authorized === false ? "missing" : "unknown";
  }
  return required.every((scope) => scopes.includes(scope))
    ? "granted"
    : "missing";
}

export function larkSkillCounts(status: LarkIntegrationStatus) {
  const installed = new Set(status.installed_skills);
  return {
    installed: status.skills_installed,
    expected: status.skills_expected,
    enabled: status.enabled_skills.filter((skill) => installed.has(skill))
      .length,
  };
}

export function feishuChatState(
  provider: ChannelProvider | undefined,
  failed = false,
): "online" | "offline" | "unconfigured" | "disabled" | "unknown" {
  if (failed || !provider) return "unknown";
  if (!provider.enabled) return "disabled";
  if (!provider.configured) return "unconfigured";
  if (provider.runtime_online === true) return "online";
  if (provider.runtime_online === false) return "offline";
  return "unknown";
}
