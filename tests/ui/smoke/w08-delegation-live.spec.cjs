// Real frozen Ultra and provider. No route shaping, Provider gates or response mocks.
const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require(path.resolve(__dirname, '../../../frontend/node_modules/@playwright/test'));
const APP = process.env.E2E_APP_URL ?? `http://127.0.0.1:${process.env.E2E_FRONTEND_PORT ?? '25618'}`;
const GATEWAY = `http://127.0.0.1:${process.env.E2E_GATEWAY_PORT ?? '20618'}`;
const MODEL = process.env.W18_MODEL_NAME ?? 'w18-deepseek-live';
const CHILD = 'w08-live-child';
const STREAM = /\/api\/langgraph\/threads\/([^/]+)\/runs\/stream$/;
const RUN = id => `/api/langgraph/threads/${id.threadId}/runs/${id.runId}`;
const STATE = id => `/api/langgraph/threads/${id.threadId}/state`;
const messages = state => state?.values?.messages ?? [];
const tasks = run => (run?.diagnostics?.tool_calls ?? []).filter(call => call.name === 'task');
const results = state => messages(state).filter(message => message.type === 'tool' && message.tool_call_id);
const running = page => page.getByText('Running subtask', { exact: true });
const excerpt = text => String(text).split(/\r?\n/).map(line => line.trim().replace(/^#+\s*/, '').replace(/[*`]/g, '')).find(Boolean)?.slice(0, 100) ?? '';
test.setTimeout(Number(process.env.W18_PLAYWRIGHT_TIMEOUT_MS ?? 120000));

async function json(context, pathname) {
  const response = await context.request.get(`${APP}${pathname}`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

// An independent real join records physical Child starts while the browser renders.
// task_started comes from public ChildRunView.startedAt, not Tool-call admission.
function observe(id, remaining) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), remaining);
  const observation = { raw: '', frames: [], error: null, status: null };
  const done = (async () => {
    const response = await fetch(`${GATEWAY}/api/threads/${id.threadId}/runs/${id.runId}/stream`, { signal: controller.signal });
    observation.status = response.status;
    if (response.status !== 200) throw new Error(`Actual SSE join status ${response.status}`);
    const decoder = new TextDecoder();
    let pending = '';
    for await (const chunk of response.body) {
      const text = decoder.decode(chunk, { stream: true });
      observation.raw += text;
      pending += text.replace(/\r\n/g, '\n');
      let boundary;
      while ((boundary = pending.indexOf('\n\n')) >= 0) {
        const frame = pending.slice(0, boundary); pending = pending.slice(boundary + 2);
        const event = frame.match(/^event: (.+)$/m)?.[1];
        const data = [...frame.matchAll(/^data: ?(.*)$/gm)].map(match => match[1]).join('\n');
        let payload = null;
        if (data) { try { payload = JSON.parse(data); } catch { /* raw frame remains evidence */ } }
        observation.frames.push({ event, payload, receivedAt: Date.now() });
      }
    }
  })().catch(error => { observation.error = String(error); }).finally(() => clearTimeout(timer));
  return { observation, done, abort: () => controller.abort() };
}

async function screenshot(page, info, name) {
  const file = info.outputPath(`${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  await info.attach(name, { path: file, contentType: 'image/png' });
}

async function setup(context, page) {
  await context.addCookies([{ name: 'locale', value: 'en-US', url: `${APP}/` }]);
  expect((await json(context, '/api/v1/auth/me')).system_role).toBe('admin');
  const model = (await json(context, '/api/models')).models.find(item => item.name === MODEL);
  expect(model, 'Real managed model must expose the verified thinking capability').toMatchObject({ supports_thinking: true });
  const catalog = await json(context, '/api/subagents');
  const existing = catalog.subagents.find(item => item.name === CHILD);
  if (!existing) {
  const response = await context.request.post(`${APP}/api/subagents`, { data: {
    name: CHILD, display_name: 'W08 Live Child', description: 'Harmless real-model delegation acceptance',
    system_prompt: 'Complete only the assigned harmless text objective. Use no tools. Answer directly with useful detail.',
    model: 'inherit', tools: [], skills: null, max_turns: 4, timeout_seconds: 90,
  } });
    expect(response.status(), await response.text()).toBe(201);
  } else {
    // Each runner has a fresh data-root; cases in that invocation reuse its exact definition.
    expect(existing).toMatchObject({ enabled: true, model: 'inherit', tools: [] });
  }
  await page.goto('/workspace/chats/new', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('textarea[name="message"]')).toBeVisible();
  await expect(page.getByRole('button', { name: model.display_name, exact: true })).toBeVisible();
  // Click the unmodified frozen mode selector and its actual Ultra handler.
  const modeButton = page.getByRole('button', { name: /^(Flash|Reasoning|Pro|Ultra)$/ });
  await expect(modeButton).toHaveCount(1);
  await modeButton.click({ timeout: 20000 });
  await page.getByRole('menuitem').filter({ hasText: /^Ultra/ }).click();
  // The frozen action item intentionally leaves its menu open. Dismiss the real
  // menu before inspecting the composer, whose roles are inert behind the modal.
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Ultra', exact: true })).toBeVisible();
}

async function runCase(context, page, info, count, cancel) {
  const started = Date.now();
  const budget = Number(process.env.W18_PLAYWRIGHT_TIMEOUT_MS ?? 120000);
  const remaining = () => Math.max(1, budget - (Date.now() - started));
  const evidence = { count, cancel, model: MODEL, provider: 'https://api.deepseek.com', errors: [] };
  let id, observer;
  page.on('pageerror', error => evidence.errors.push(String(error)));
  const cancelResponses = [];
  page.on('response', response => {
    if (response.request().method() === 'POST' && /\/runs\/[^/]+\/cancel$/.test(new URL(response.url()).pathname)) cancelResponses.push(response);
  });
  try {
    await setup(context, page);
    const objectives = count === 1
      ? ['Write a detailed harmless explanation of why the first twenty prime numbers are prime, with trial divisions.']
      : ['Write a detailed explanation of the first fifty prime numbers with trial division checks.', 'Write a detailed explanation of the first fifty Fibonacci numbers and their recurrence.'];
    const prompt = `Use the task tool to delegate exactly ${count} objective${count === 1 ? '' : 's'} to ${CHILD}. ${count === 2 ? 'Issue both task calls together in ONE tool-call round so both children execute concurrently.' : ''} Do not solve them yourself. Objectives: ${objectives.map((value, index) => `${index + 1}. ${value}`).join(' ')} Wait for all Child results before your final short summary.`;
    const responsePromise = page.waitForResponse(response => response.request().method() === 'POST' && STREAM.test(new URL(response.url()).pathname), { timeout: Math.min(30000, remaining()) });
    await page.locator('textarea[name="message"]').fill(prompt);
    await page.getByRole('button', { name: 'Submit' }).click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);
    const location = response.headers()['content-location'];
    const match = location?.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
    expect(match, 'Actual canonical Parent identity').toBeTruthy();
    id = { threadId: match[1], runId: match[2] };
    evidence.identity = id;
    evidence.requestContext = response.request().postDataJSON().context;
    expect(evidence.requestContext).toMatchObject({ mode: 'ultra', subagent_enabled: true, is_plan_mode: true });
    expect(evidence.requestContext.model_name).toBe(MODEL);
    observer = observe(id, remaining());
    let sample;
    await expect.poll(async () => {
      const run = await json(context, RUN(id));
      const calls = tasks(run);
      const physicalStarts = observer.observation.frames.filter(frame => frame.event === 'custom' && frame.payload?.type === 'task_started').map(frame => frame.payload.task_id);
      const childEnds = observer.observation.frames.filter(frame => frame.event === 'custom' && /^task_(completed|failed|cancelled|timed_out)$/.test(frame.payload?.type ?? ''));
      const state = await json(context, STATE(id));
      sample = { sampledAt: Date.now(), run, state, physicalStarts, childEnds };
      return calls.length === count && calls.every(call => call.status === 'RUNNING' && call.started_at)
        && calls.every(call => physicalStarts.includes(call.tool_call_id)) && childEnds.length === 0
        && await running(page).count() === count;
    }, { timeout: Math.min(60000, remaining()), intervals: [100, 250, 500] }).toBe(true);
    evidence.runningSample = sample;
    const ids = tasks(sample.run).map(call => call.tool_call_id);
    evidence.taskIds = ids;
    const round = messages(sample.state).filter(message => message.type === 'ai' && (message.tool_calls ?? []).filter(call => call.name === 'task').length === count);
    expect(round, 'Exactly one genuine native task-call round').toHaveLength(1);
    expect(round[0].tool_calls.filter(call => call.name === 'task').map(call => call.id).sort()).toEqual([...ids].sort());
    for (const call of round[0].tool_calls.filter(call => call.name === 'task')) expect(call.args.subagent_type).toBe(CHILD);
    const taskRoundIndex = messages(sample.state).indexOf(round[0]);
    expect(messages(sample.state).slice(taskRoundIndex + 1).filter(message => message.type === 'ai' && !(message.tool_calls?.length) && message.content?.trim()), 'No Parent final answer after delegation while Child results are outstanding').toHaveLength(0);
    if (count === 2) await expect(page.getByText('Executing 2 subtasks in parallel', { exact: true })).toBeVisible();
    await screenshot(page, info, 'real-children-running');
    if (cancel) {
      const stopped = page.waitForResponse(response => response.request().method() === 'POST' && /\/runs\/[^/]+\/cancel$/.test(new URL(response.url()).pathname), { timeout: remaining() });
      await page.getByRole('button', { name: 'Submit' }).click();
      expect((await stopped).status()).toBe(202);
    }
    let terminal;
    await expect.poll(async () => {
      terminal = await json(context, RUN(id)); return terminal.status;
    }, { timeout: remaining(), intervals: [250, 500, 1000] }).toBe(cancel ? 'interrupted' : 'success');
    evidence.terminal = terminal;
    if (cancel) expect(terminal.diagnostics.sdk_status).toBe('CANCELLED');
    const state = await json(context, STATE(id)); evidence.terminalState = state;
    const childResults = results(state).filter(message => ids.includes(message.tool_call_id));
    expect(childResults).toHaveLength(count);
    expect(childResults.map(message => message.additional_kwargs?.subagent_status)).toEqual(Array(count).fill(cancel ? 'cancelled' : 'completed'));
    expect(tasks(terminal).every(call => !['REQUESTED', 'RUNNING'].includes(call.status))).toBe(true);
    if (cancel) {
      const runs = await json(context, `/api/langgraph/threads/${id.threadId}/runs`);
      evidence.runsAfterCancel = runs;
      expect(runs.filter(run => ['pending', 'running'].includes(run.status))).toHaveLength(0);
      await expect(page.getByText('Subtask failed', { exact: true })).toHaveCount(count, { timeout: remaining() });
    } else {
      expect(childResults.every(message => String(message.content ?? '').trim().length > 0)).toBe(true);
      const lastResult = Math.max(...messages(state).map((message, index) => ids.includes(message.tool_call_id) ? index : -1));
      const finalIndex = messages(state).findLastIndex(message => message.type === 'ai' && !message.tool_calls?.length && String(message.content ?? '').trim());
      expect(finalIndex, 'Actual Parent final answer follows both durable Child results').toBeGreaterThan(lastResult);
      evidence.finalAnswer = messages(state)[finalIndex].content;
      await expect(page.getByText('Subtask completed', { exact: true })).toHaveCount(count, { timeout: remaining() });
      expect(excerpt(evidence.finalAnswer)).not.toBe('');
      await expect(page.locator('#chat').getByText(excerpt(evidence.finalAnswer), { exact: false }).last()).toBeVisible({ timeout: remaining() });
      for (const call of round[0].tool_calls.filter(call => ids.includes(call.id))) {
        const title = call.args.description ?? call.args.prompt;
        expect(title).toBeTruthy();
        const result = childResults.find(message => message.tool_call_id === call.id);
        const brief = result.additional_kwargs.subagent_result_brief;
        expect(typeof brief).toBe('string');
        expect(excerpt(brief)).not.toBe('');
        await page.getByTitle(title, { exact: true }).click();
        const card = page.getByTitle(title, { exact: true }).locator("xpath=ancestor::div[contains(@class,'rounded-lg')][1]");
        await expect(card).toContainText(excerpt(brief), { timeout: remaining() });
      }
      if (count === 2) {
        const calls = tasks(terminal);
        expect(Math.max(...calls.map(call => Date.parse(call.started_at)))).toBeLessThan(Math.min(...calls.map(call => Date.parse(call.completed_at))));
      }
    }
    await observer.done;
    expect(observer.observation.error).toBeNull();
    expect(observer.observation.status).toBe(200);
    expect(observer.observation.frames.some(frame => frame.event === 'end')).toBe(true);
    expect(observer.observation.frames.some(frame => frame.event === 'error')).toBe(false);
    const physicalStarts = observer.observation.frames.filter(frame => frame.event === 'custom' && frame.payload?.type === 'task_started');
    const childEnds = observer.observation.frames.filter(frame => frame.event === 'custom' && frame.payload?.type === (cancel ? 'task_cancelled' : 'task_completed'));
    expect(physicalStarts.map(frame => frame.payload.task_id).sort()).toEqual([...ids].sort());
    expect(childEnds.map(frame => frame.payload.task_id).sort()).toEqual([...ids].sort());
    if (cancel) {
      // The real frozen Stop handler aborts its creator fetch. The independently joined
      // actual SSE above must still complete and prove every Child terminal transition.
      try { evidence.browserStream = await response.text(); }
      catch (error) { evidence.browserStreamAborted = String(error); }
    } else {
      evidence.browserStream = await response.text();
    }
    await screenshot(page, info, 'real-children-terminal');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText(cancel ? 'Subtask failed' : 'Subtask completed', { exact: true })).toHaveCount(count, { timeout: remaining() });
    const pageResponse = await context.request.get(`${APP}/api/langgraph/threads/${id.threadId}/messages/page?limit=100`);
    expect(pageResponse.status(), await pageResponse.text()).toBe(200);
    const durable = await pageResponse.json(); evidence.reloadedPage = durable;
    const restoredMessages = durable.data.map(row => row.content);
    const restoredCalls = restoredMessages.flatMap(message => (message.tool_calls ?? []).filter(call => ids.includes(call.id)));
    expect(restoredCalls.map(call => call.id).sort()).toEqual([...ids].sort());
    const restoredResults = restoredMessages.filter(message => message.type === 'tool' && ids.includes(message.tool_call_id));
    expect(restoredResults).toHaveLength(count);
    expect(restoredResults.map(message => message.tool_call_id).sort()).toEqual([...ids].sort());
    for (const restored of restoredResults) {
      const original = childResults.find(message => message.tool_call_id === restored.tool_call_id);
      expect(restored.additional_kwargs.subagent_status).toBe(cancel ? 'cancelled' : 'completed');
      expect(restored.content).toEqual(original.content);
      expect(restored.additional_kwargs.subagent_result_brief).toEqual(original.additional_kwargs.subagent_result_brief);
    }
    for (const call of restoredCalls) await expect(page.getByTitle(call.args.description ?? call.args.prompt, { exact: true })).toBeVisible({ timeout: remaining() });
    await expect(page.getByRole('dialog').filter({ hasText: /error|failed/i })).toHaveCount(0);
    evidence.toasts = await page.locator('[data-sonner-toast]').allInnerTexts();
    expect(evidence.toasts.filter(text => /error|failed/i.test(text))).toEqual([]);
    expect(evidence.errors).toEqual([]);
    evidence.cancelStatuses = cancelResponses.map(response => response.status());
    await screenshot(page, info, 'real-children-reloaded');
  } finally {
    if (id) {
      // Failure cleanup stops only this actual Parent; it never converts a failed assertion to PASS.
      const current = await json(context, RUN(id)).catch(() => null);
      if (current && ['pending', 'running'].includes(current.status)) await context.request.post(`${APP}${RUN(id)}/cancel?wait=0&action=interrupt`, { timeout: 5000 }).catch(() => {});
    }
    if (observer) { observer.abort(); await observer.done; evidence.sse = observer.observation; }
    evidence.elapsedMs = Date.now() - started;
    const file = info.outputPath('real-ultra-delegation.json');
    fs.writeFileSync(file, JSON.stringify(evidence, null, 2));
    await info.attach('real-ultra-delegation', { path: file, contentType: 'application/json' });
  }
}

test("W08 live UI-06 actual Ultra delegates one real Child through completion and reload", async ({ context, page }, info) => runCase(context, page, info, 1, false));
test("W08 live UI-09 actual Ultra delegates two physically started concurrent Children before its final answer", async ({ context, page }, info) => runCase(context, page, info, 2, false));
test("W08 live UI-10 actual Ultra stops two running Children and reloads both terminal cards", async ({ context, page }, info) => runCase(context, page, info, 2, true));
