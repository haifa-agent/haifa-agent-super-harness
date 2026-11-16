export function parseLarkRecoveryScope(raw: string | null): string {
  if (!raw || raw.length > 1024) return "";
  const scopes = raw.trim().split(/\s+/);
  if (
    scopes.length > 24 ||
    scopes.some(
      (scope) =>
        scope.length > 100 ||
        !/^[a-z][a-z0-9_.-]*(?::[a-z0-9_.-]+){1,4}$/.test(scope),
    )
  ) {
    return "";
  }
  return [...new Set(scopes)].join(" ");
}
