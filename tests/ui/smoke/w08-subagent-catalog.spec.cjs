// Independent catalog UI Oracles over the unmodified frozen frontend and real REST.
// This profile does not exercise Ultra, Child execution, or Task4 Oracle 12.
const { expect, test } = require("./fixtures.cjs");

const COLLECTION = "/api/subagents";
const MODEL_NAME = "w18-controlled";

function settings(page) {
  return page.getByRole("dialog", { name: "Settings", exact: true });
}

function editor(page, creating = true) {
  return page.getByRole("dialog", {
    name: creating ? "Add managed subagent" : "Edit managed subagent",
    exact: true,
  });
}

function row(page, entry) {
  return settings(page).locator('[data-slot="item"]').filter({
    has: page.getByText(entry.display_name ?? entry.name, { exact: true }),
  });
}

function definition(name, changes = {}) {
  return {
    name,
    display_name: `Catalog ${name}`,
    description: `Delegate bounded catalog work to ${name}`,
    system_prompt: "Complete the assigned task without asking the user questions.",
    model: "inherit",
    tools: null,
    skills: null,
    max_turns: 4,
    timeout_seconds: 30,
    ...changes,
  };
}

async function catalog(context, runtime, evidence, label) {
  const response = await context.request.get(`${runtime.app}${COLLECTION}`);
  const body = await response.text();
  await evidence.json(label, { method: "GET", path: COLLECTION, status: response.status(), body });
  expect(response.status(), body).toBe(200);
  return JSON.parse(body).subagents;
}

async function seed(context, runtime, evidence, source) {
  const response = await context.request.post(`${runtime.app}${COLLECTION}`, { data: source });
  const body = await response.text();
  await evidence.json("independent-fixture", {
    method: "POST", path: COLLECTION, request: source, status: response.status(), body,
  });
  expect(response.status(), body).toBe(201);
  return JSON.parse(body);
}

async function openCatalog(page, runtime) {
  await page.goto(`${runtime.app}/workspace/chats/new?settings=subagents`, {
    waitUntil: "domcontentloaded",
  });
  await expect(settings(page)).toBeVisible();
  await expect(settings(page).getByRole("button", { name: "Add subagent", exact: true })).toBeVisible();
}

async function reloadCatalog(page, evidence) {
  const before = page.url();
  const pending = page.waitForResponse((response) =>
    response.request().method() === "GET" && new URL(response.url()).pathname === COLLECTION,
  );
  await page.reload({ waitUntil: "domcontentloaded" });
  const after = page.url();
  // Reload resets dialog state. Reopen through the actual frozen menu, even when
  // the original deep-link query was consumed; do not navigate to a fabricated URL.
  if (new URL(before).searchParams.get("settings") === "subagents") {
    await expect(settings(page)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(settings(page)).toBeHidden();
  }
  const menu = page.getByRole("button", { name: "Settings and more", exact: true });
  await expect(menu).toHaveAttribute("aria-haspopup", "menu");
  await menu.click();
  await page.getByRole("menuitem", { name: "Settings", exact: true }).click();
  await settings(page).getByRole("button", { name: "Subagents", exact: true }).click();
  const response = await pending;
  const body = await response.text();
  expect(response.status(), body).toBe(200);
  const entries = JSON.parse(body).subagents;
  const builtin = entries.find((entry) => entry.name === "general-purpose");
  expect(builtin).toBeTruthy();
  await expect(row(page, builtin)).toBeVisible();
  await evidence.json("reload-catalog", { before, after, reopened: page.url(), status: response.status(), body });
  return entries;
}

async function fillDraft(page, source) {
  const form = editor(page);
  await expect(form).toBeVisible();
  // The new-name label also contains a hint paragraph, so its accessible name is longer than "Name".
  await form.getByRole("textbox", { name: "Name Use letters, numbers, and hyphens only.", exact: true }).fill(source.name);
  await form.getByLabel("Display name", { exact: true }).fill(source.display_name ?? "");
  await form.getByLabel("Delegation description", { exact: true }).fill(source.description);
  await form.getByLabel("System prompt", { exact: true }).fill(source.system_prompt);
  await form.getByLabel("Maximum turns", { exact: true }).fill(String(source.max_turns));
  await form.getByLabel("Timeout (seconds)", { exact: true }).fill(String(source.timeout_seconds));
}

async function mutation(page, evidence, method, pathname, action, label) {
  const pending = page.waitForResponse((response) =>
    response.request().method() === method && new URL(response.url()).pathname === pathname,
  );
  const [response] = await Promise.all([pending, action()]);
  let body;
  let bodyUnavailable;
  try {
    body = await response.text();
  } catch (error) {
    if (method !== "DELETE" || response.status() !== 204
        || !error.message.includes("Network.getResponseBody")
        || !error.message.includes("No data found for resource with given identifier")) throw error;
    // Chromium has no cached body for this 204. The deletion case independently
    // validates its actual HTTP framing; this error must never become an empty body.
    body = null;
    bodyUnavailable = error.message;
  }
  const request = response.request().postDataJSON();
  const observed = { method, path: pathname, request, status: response.status(), body, bodyUnavailable };
  await evidence.json(label, observed);
  return observed;
}

async function createInUi(page, evidence, source, selectModel) {
  await settings(page).getByRole("button", { name: "Add subagent", exact: true }).click();
  await fillDraft(page, source);
  if (selectModel) {
    // The frozen editor orders its Select controls as Model, Tools, Skills.
    await editor(page).getByRole("combobox").nth(0).click();
    await page.getByRole("option", { name: selectModel, exact: true }).click();
  }
  const result = await mutation(page, evidence, "POST", COLLECTION,
    () => editor(page).getByRole("button", { name: "Save", exact: true }).click(), "ui-create");
  expect(result.status, result.body).toBe(201);
  expect(result.request).toEqual(source);
  await expect(editor(page)).toBeHidden();
  return JSON.parse(result.body);
}

test.beforeEach(async ({ context, runtime }) => {
  await context.addCookies([{ name: "locale", value: "en-US", url: `${runtime.app}/` }]);
  const me = await context.request.get(`${runtime.app}/api/v1/auth/me`);
  expect(me.status(), await me.text()).toBe(200);
  expect((await me.json()).system_role).toBe("admin");
});

test("W08 UI-01 built-in subagent is visible and immutable", async ({ context, page, runtime, evidence }) => {
  const entries = await catalog(context, runtime, evidence, "catalog-built-in");
  const builtin = entries.find((entry) => entry.name === "general-purpose");
  expect(builtin).toMatchObject({ source: "builtin", editable: false });
  await openCatalog(page, runtime);
  const item = row(page, builtin);
  await expect(item).toHaveCount(1);
  await expect(item).toBeVisible();
  await expect(item.getByText("Built-in", { exact: true })).toBeVisible();
  await expect(item.getByRole("button", { name: "Edit", exact: true })).toHaveCount(0);
  await expect(item.getByRole("button", { name: "Delete", exact: true })).toHaveCount(0);
  await expect(item.getByRole("switch")).toHaveCount(0);
  await evidence.screenshot(page, "built-in-immutable");
});

test("W08 UI-02 creates a managed subagent and survives reload", async ({ context, page, runtime, evidence }) => {
  const source = definition("w08-ui-create");
  await openCatalog(page, runtime);
  const created = await createInUi(page, evidence, source);
  await expect(row(page, created)).toBeVisible();
  await reloadCatalog(page, evidence);
  await expect(row(page, created)).toBeVisible();
  const entries = await catalog(context, runtime, evidence, "catalog-after-reload");
  expect(entries.find((entry) => entry.name === source.name)).toMatchObject({ ...source, enabled: true, source: "managed" });
  await evidence.screenshot(page, "created-after-reload");
});

test("W08 UI-03 edits the full form without changing other fields", async ({ context, page, runtime, evidence }) => {
  const source = definition("w08-ui-edit");
  const before = await seed(context, runtime, evidence, source);
  await openCatalog(page, runtime);
  await row(page, before).getByRole("button", { name: "Edit", exact: true }).click();
  const form = editor(page, false);
  await expect(form.getByLabel("Name", { exact: true })).toBeDisabled();
  const changed = { ...source, description: "Updated delegation description through the real frozen form" };
  await form.getByRole("textbox", { name: "Delegation description", exact: true }).fill(changed.description);
  const result = await mutation(page, evidence, "PUT", `${COLLECTION}/${source.name}`,
    () => form.getByRole("button", { name: "Save", exact: true }).click(), "ui-edit-full-form");
  expect(result.status, result.body).toBe(200);
  const { name, ...expectedForm } = changed;
  expect(result.request).toEqual(expectedForm);
  const entries = await catalog(context, runtime, evidence, "catalog-after-edit");
  expect(entries.find((entry) => entry.name === name)).toEqual({ ...before, description: changed.description });
  await reloadCatalog(page, evidence);
  await expect(row(page, before).getByText(changed.description, { exact: true })).toBeVisible();
  await evidence.screenshot(page, "edited-after-reload");
});

test("W08 UI-03 toggles enabled with a partial update", async ({ context, page, runtime, evidence }) => {
  const source = definition("w08-ui-disabled");
  const before = await seed(context, runtime, evidence, source);
  await openCatalog(page, runtime);
  const toggle = row(page, before).getByRole("switch");
  await expect(toggle).toBeChecked();
  const result = await mutation(page, evidence, "PUT", `${COLLECTION}/${source.name}`,
    () => toggle.click(), "ui-disable-partial-put");
  expect(result.status, result.body).toBe(200);
  expect(result.request).toEqual({ enabled: false });
  const entries = await catalog(context, runtime, evidence, "catalog-after-disable");
  expect(entries.find((entry) => entry.name === source.name)).toEqual({ ...before, enabled: false });
  await reloadCatalog(page, evidence);
  await expect(row(page, before).getByRole("switch")).not.toBeChecked();
  await evidence.screenshot(page, "disabled-after-reload");
});

test("W08 UI-04a blocks an invalid name before submitting", async ({ context, page, runtime, evidence }) => {
  await openCatalog(page, runtime);
  const submitted = [];
  const observe = (request) => {
    if (request.method() === "POST" && new URL(request.url()).pathname === COLLECTION) submitted.push(request.url());
  };
  page.on("request", observe);
  try {
    await settings(page).getByRole("button", { name: "Add subagent", exact: true }).click();
    await fillDraft(page, definition("bad_name"));
    await expect(editor(page).getByRole("button", { name: "Save", exact: true })).toBeDisabled();
    await evidence.screenshot(page, "invalid-name-save-disabled");
    await editor(page).getByRole("button", { name: "Cancel", exact: true }).click();
    const entries = await catalog(context, runtime, evidence, "catalog-after-invalid-name");
    expect(entries.some((entry) => entry.name === "bad_name")).toBe(false);
    expect(submitted).toEqual([]);
    await evidence.json("invalid-name-no-post", { requests: submitted, name: "bad_name" });
  } finally {
    page.off("request", observe);
  }
});

test("W08 UI-04b shows the real duplicate-name conflict", async ({ context, page, runtime, evidence }) => {
  const source = definition("w08-ui-duplicate");
  const before = await seed(context, runtime, evidence, source);
  await openCatalog(page, runtime);
  await settings(page).getByRole("button", { name: "Add subagent", exact: true }).click();
  const duplicate = { ...source, description: "Must not replace the existing description" };
  await fillDraft(page, duplicate);
  const result = await mutation(page, evidence, "POST", COLLECTION,
    () => editor(page).getByRole("button", { name: "Save", exact: true }).click(), "ui-duplicate-conflict");
  expect(result.status, result.body).toBe(409);
  const detail = JSON.parse(result.body).detail;
  expect(detail).toBe("Managed subagent 'w08-ui-duplicate' already exists");
  expect(result.request).toEqual(duplicate);
  await expect(page.locator('[data-sonner-toast]').filter({ hasText: detail })).toBeVisible();
  await expect(editor(page)).toBeVisible();
  await evidence.screenshot(page, "duplicate-readable-error");
  const entries = await catalog(context, runtime, evidence, "catalog-after-conflict");
  expect(entries.filter((entry) => entry.name === source.name)).toEqual([before]);
});

test("W08 UI-05 deletes a managed subagent", async ({ context, page, runtime, evidence }) => {
  const source = definition("w08-ui-delete");
  const before = await seed(context, runtime, evidence, source);
  const deletePath = `${COLLECTION}/${source.name}`;
  const origin = new URL(runtime.app).origin;
  const network = await context.newCDPSession(page);
  const requests = new Map();
  const responseHeaderFacts = new Map();
  network.on("Network.requestWillBeSent", (event) => {
    const url = new URL(event.request.url);
    if (url.origin !== origin || !(
      (event.request.method === "GET" && url.pathname === COLLECTION)
      || (event.request.method === "DELETE" && url.pathname === deletePath))) return;
    requests.set(event.requestId, {
      requestId: event.requestId, method: event.request.method, url: event.request.url,
      status: null, responseObserved: false, entityBytes: 0, dataEvents: 0,
      finished: false, failed: null, cached: false, serviceWorker: false,
      redirected: Boolean(event.redirectResponse), transferBytes: null,
      protocol: null, observationError: null,
    });
  });
  network.on("Network.responseReceivedExtraInfo", (event) => {
    // ExtraInfo can arrive before responseReceived. Retain only framing facts,
    // never raw headers that could contain cookies or authentication values.
    const fields = Object.entries(event.headers);
    responseHeaderFacts.set(event.requestId, {
      status: event.statusCode, completeHeaders: true,
      contentLength: fields.filter(([name]) => name.toLowerCase() === "content-length").map(([, value]) => value),
      transferEncoding: fields.filter(([name]) => name.toLowerCase() === "transfer-encoding").map(([, value]) => value),
    });
  });
  network.on("Network.responseReceived", (event) => {
    const observed = requests.get(event.requestId);
    if (!observed) return;
    observed.status = event.response.status;
    observed.responseObserved = true;
    observed.cached ||= Boolean(event.response.fromDiskCache);
    observed.serviceWorker = Boolean(event.response.fromServiceWorker);
    observed.protocol = event.response.protocol;
    if (event.response.url !== observed.url) observed.observationError = "Response URL changed";
  });
  network.on("Network.requestServedFromCache", (event) => {
    const observed = requests.get(event.requestId);
    if (observed) observed.cached = true;
  });
  network.on("Network.dataReceived", (event) => {
    const observed = requests.get(event.requestId);
    if (!observed) return;
    if (!Number.isSafeInteger(event.dataLength) || event.dataLength < 0
        || !Number.isSafeInteger(observed.entityBytes + event.dataLength)) {
      observed.observationError = "Invalid native entity byte count";
      return;
    }
    observed.entityBytes += event.dataLength;
    observed.dataEvents++;
  });
  network.on("Network.loadingFinished", (event) => {
    const observed = requests.get(event.requestId);
    if (!observed) return;
    observed.finished = true;
    // This includes response headers, so it is metadata rather than entity bytes.
    observed.transferBytes = event.encodedDataLength;
  });
  network.on("Network.loadingFailed", (event) => {
    const observed = requests.get(event.requestId);
    if (!observed) return;
    const error = event.errorText || "Native request failed";
    observed.failed = error;
    if (error !== "net::ERR_ABORTED") observed.observationError = error;
  });
  try {
    await network.send("Network.enable");
    await openCatalog(page, runtime);
    // Positive control: the same observer must see real UI GET response data.
    await expect.poll(() => [...requests.values()].some((entry) => entry.method === "GET"
      && entry.responseObserved && entry.status === 200 && entry.finished
      && !entry.failed && !entry.observationError && !entry.cached && !entry.serviceWorker && !entry.redirected
      && entry.dataEvents > 0 && entry.entityBytes > 0), { timeout: 5_000 }).toBe(true);
    expect([...requests.values()].filter((entry) => entry.failed)).toEqual([]);
    let confirmation;
    page.once("dialog", async (dialog) => {
      confirmation = { type: dialog.type(), message: dialog.message() };
      await dialog.accept();
    });
    const result = await mutation(page, evidence, "DELETE", deletePath,
      () => row(page, before).getByRole("button", { name: "Delete", exact: true }).click(), "ui-delete");
    expect(result.status, result.body).toBe(204);
    expect(result.request).toBeNull();
    await expect.poll(() => [...requests.values()].filter((entry) => entry.method === "DELETE")
      .map((entry) => ({ status: entry.status, responseObserved: entry.responseObserved,
        headers: responseHeaderFacts.get(entry.requestId) })), { timeout: 5_000 }).toEqual([
      { status: 204, responseObserved: true,
        headers: { status: 204, completeHeaders: true, contentLength: [], transferEncoding: [] } },
    ]);
    const measured = [...requests.values()].find((entry) => entry.method === "DELETE");
    expect(measured.url).toBe(new URL(deletePath, runtime.app).href);
    expect(measured.protocol).toBe("http/1.1");
    expect(measured.cached || measured.serviceWorker || measured.redirected).toBe(false);
    expect(measured.observationError).toBeNull();
    // HTTP 204 ends at the complete headers and cannot carry an entity:
    // https://www.rfc-editor.org/rfc/rfc9110.html#name-204-no-content
    // https://www.rfc-editor.org/rfc/rfc9112.html#section-6.3
    // This is protocol framing evidence, not raw packet proof that a sender
    // emitted no illegal trailing octets. No missing body is converted to "".
    expect(measured.entityBytes).toBe(0);
    expect([null, "net::ERR_ABORTED"]).toContain(measured.failed);
    if (result.body !== null) expect(result.body).toBe("");
    expect(confirmation?.type).toBe("confirm");
    await evidence.json("delete-confirmation", confirmation);
    await expect(row(page, before)).toHaveCount(0);
    const entries = await catalog(context, runtime, evidence, "catalog-after-delete");
    expect(entries.some((entry) => entry.name === source.name)).toBe(false);
    const refreshed = await reloadCatalog(page, evidence);
    expect(refreshed.some((entry) => entry.name === source.name)).toBe(false);
    await expect(row(page, before)).toHaveCount(0);
    // Recheck after the real follow-up requests so late native events cannot
    // overwrite an earlier successful observation or hide received entity data.
    expect(measured.entityBytes).toBe(0);
    expect(measured.observationError).toBeNull();
    expect([null, "net::ERR_ABORTED"]).toContain(measured.failed);
    await evidence.json("delete-empty-entity", {
      requestId: measured.requestId, status: measured.status,
      headers: responseHeaderFacts.get(measured.requestId),
      emptyEntityBasis: "HTTP204 framing", observedEntityBytes: measured.entityBytes,
      dataEvents: measured.dataEvents, loadingFinished: measured.finished,
      loadingFailureDiagnostic: measured.failed,
      body: result.body, bodyUnavailable: result.bodyUnavailable,
    });
    await evidence.screenshot(page, "deleted-after-reload");
  } finally {
    try {
      await evidence.json("delete-native-network", { requests: [...requests.values()].map((entry) => ({
        ...entry, headers: responseHeaderFacts.get(entry.requestId),
      })) });
    } finally {
      network.removeAllListeners();
      await network.detach();
    }
  }
});

test("W08 catalog preserves an explicit model and a long legal name", async ({ context, page, runtime, evidence }) => {
  const response = await context.request.get(`${runtime.app}/api/models`);
  const body = await response.text();
  await evidence.json("executable-model-catalog", { status: response.status(), body });
  expect(response.status(), body).toBe(200);
  const selected = JSON.parse(body).models.find((model) => model.name === MODEL_NAME);
  expect(selected, "The runner must provide the actual configured model").toBeTruthy();
  const source = definition(`w08-ui-model-${"a".repeat(64)}`, { display_name: null, model: selected.name });
  await openCatalog(page, runtime);
  const created = await createInUi(page, evidence, source, selected.display_name || selected.name);
  await expect(row(page, created)).toBeVisible();
  await reloadCatalog(page, evidence);
  await expect(row(page, created).getByText(source.name, { exact: true })).toBeVisible();
  const entries = await catalog(context, runtime, evidence, "catalog-explicit-model-after-reload");
  expect(entries.find((entry) => entry.name === source.name)).toMatchObject(source);
  await row(page, created).getByRole("button", { name: "Edit", exact: true }).click();
  const form = editor(page, false);
  await expect(form.getByLabel("Name", { exact: true })).toHaveValue(source.name);
  await expect(form.getByLabel("Name", { exact: true })).toBeDisabled();
  await expect(form.getByRole("combobox").nth(0)).toContainText(selected.display_name || selected.name);
  await evidence.screenshot(page, "long-name-explicit-model-preserved");
});
