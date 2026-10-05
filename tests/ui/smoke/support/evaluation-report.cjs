// The report's observed field must be the bare fact value. The only tolerated variation is the model
// copying the exact fact record it read (`<key>=<value>`); any other prefixed or altered text is returned
// unchanged so the caller's comparison with the expected value fails.
function observedValue(raw, factRecord) {
  if (typeof raw !== "string") return null;
  const text = raw.trim();
  if (typeof factRecord === "string" && factRecord.includes("=") && text === factRecord.trim()) {
    return text.slice(text.indexOf("=") + 1).trim();
  }
  return text;
}

module.exports = { observedValue };
