// Normalize the model-reported observed value to the bare fact value produced by the package.
function observedValue(raw) {
  if (typeof raw !== "string") return null;
  const equals = raw.lastIndexOf("=");
  const value = equals >= 0 ? raw.slice(equals + 1) : raw;
  return value.trim();
}

module.exports = { observedValue };
