import { redirect } from "next/navigation";

import { getServerSideUser } from "@/core/auth/server";
import { buildLoginUrl } from "@/core/auth/types";
import { parseLarkRecoveryScope } from "@/core/integrations/lark/recovery";

// Feishu may open a browser without a Super Harness login cookie.
export default async function FeishuAuthorizationLink({
  searchParams,
}: {
  searchParams: Promise<{ lark_scope?: string | string[] }>;
}) {
  const { lark_scope: rawScope } = await searchParams;
  const scope = parseLarkRecoveryScope(
    typeof rawScope === "string" ? rawScope : null,
  );
  const params = new URLSearchParams({ tab: "plugins", plugin: "lark" });
  if (scope) params.set("lark_scope", scope);
  const target = `/workspace/capabilities?${params}`;
  const user = await getServerSideUser();
  redirect(user.tag === "unauthenticated" ? buildLoginUrl(target) : target);
}
