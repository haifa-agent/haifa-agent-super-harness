const { test, expect } = require('../../frontend/node_modules/@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const app = process.env.E2E_APP_URL;
const gateway = process.env.W18_DIFFERENTIAL_GATEWAY;
const output = '/mnt/user-data/outputs/customer-summary.md';
const csv = path.resolve(__dirname, '../../contracts/customer-demo-v2/fixtures/customers.csv');
const steerText = '加入风险与数据缺口：说明数据时效、缺少行业/利润字段和政策阈值适用边界；在同一 Run 的最终 customer-summary.md 增加风险与数据缺口节。';
const prompt = '请依次实际调用 skill_load 激活 consulting-analysis、read_file 完整读取附件 customers.csv、v2_reporting_get_reporting_policy 获取真实政策。只生成一个简短中文 customer-summary-draft.md（不超过600字）并用 write_file 保存到 /mnt/user-data/outputs/customer-summary-draft.md。准确说明20行、C017的region和amount_cny、East/West及总金额、按政策计算的高额行数、policy_id/阈值/比较符/来源。所有事实从本次工具结果计算，不能猜测。不写中间文件，不调用其他MCP。工具审批后继续完成同一Run。';
const hash = (x) => crypto.createHash('sha256').update(x).digest('hex');
const calls = (state) => (state.values?.messages ?? []).flatMap(m => m.tool_calls ?? []);
const reply = (state, call) => (state.values?.messages ?? []).find(m => m.type === 'tool' && m.tool_call_id === call?.id);
const card = (state) => (state.values?.messages ?? []).map(m => m.artifact?.human_input).filter(x => x && !reply(state, { id: x.tool_call_id })).at(-1);
function identity(response) {
  const headers = typeof response.headers === 'function' ? response.headers() : Object.fromEntries(response.headers);
  const match = new URL(headers['content-location'], app).pathname.match(/threads\/([^/]+)\/runs\/([^/]+)/);
  expect(match).toBeTruthy();
  return { threadId: match[1], runId: match[2] };
}

test('W16 A consecutive UI approvals and B real model same-Run Steer report', async ({ page, context }, testInfo) => {
  test.setTimeout(420_000);
  const record = { startedAt: new Date().toISOString(), prompt, steerText, A: 'NOT_RUN', B: 'NOT_RUN', network: [] };
  const save = (name, value) => fs.writeFileSync(testInfo.outputPath(name + '.json'), JSON.stringify(value, null, 2));
  let capture = 0;
  page.on('response', response => {
    if (!/\/api\/(?:langgraph\/)?threads\//.test(response.url())) return;
    const req = response.request();
    if (req.method() !== 'POST') return;
    const index = ++capture;
    record.network.push({ index, at: new Date().toISOString(), url: response.url(), status: response.status(),
      body: req.postData(), runInput: req.headers()['x-haifa-run-input'], contentLocation: response.headers()['content-location'] });
    response.body().then(bytes => fs.writeFileSync(testInfo.outputPath(`network-${index}.sse`), bytes)).catch(error => save(`network-${index}-body-error`, { error: error.message }));
  });
  const state = async run => {
    const r = await context.request.get(`${gateway}/api/threads/${run.threadId}/state`);
    expect(r.status()).toBe(200); return r.json();
  };
  const status = async run => {
    const r = await context.request.get(`${gateway}/api/threads/${run.threadId}/runs/${run.runId}`);
    expect(r.status()).toBe(200); return r.json();
  };
  const pending = async run => {
    let current;
    await expect.poll(async () => { current = await state(run); const t = await status(run);
      if (t.status === 'error' || t.status === 'success' || (t.status === 'interrupted' && t.diagnostics?.sdk_status !== 'WAITING_APPROVAL')) throw new Error(`Run terminated before pending approval: ${JSON.stringify(t)}`);
      return t.diagnostics?.sdk_status === 'WAITING_APPROVAL' ? card(current)?.request_id : null;
    }, { timeout: 100_000, intervals: [250, 500, 1000] }).toBeTruthy();
    return current;
  };
  const uiApprove = async (run, waiting, label) => {
    const request = card(waiting);
    const button = page.getByTestId('human-input-card').getByRole('button', { name: 'Approve', exact: true }).filter({ visible: true }).last();
    await expect(button).toBeEnabled({ timeout: 20_000 });
    await page.screenshot({ path: testInfo.outputPath(label + '-card.png'), fullPage: true });
    const responsePromise = page.waitForResponse(r => {
      if (r.request().method() !== 'POST') return false;
      try { return r.request().postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs?.human_input_response?.request_id === request.request_id; } catch { return false; }
    }, { timeout: 20_000 });
    await button.click();
    const response = await responsePromise;
    expect(response.status()).toBe(200); expect(identity(response)).toEqual(run);
    save(label + '-approval', { request, response: identity(response), body: response.request().postDataJSON() });
    return response;
  };
  const sendHttp = async (run, body, headers, label) => {
    const response = await fetch(`${gateway}/api/threads/${run.threadId}/runs/stream`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body), signal: AbortSignal.timeout(110_000) });
    const metadata = { at: new Date().toISOString(), status: response.status, body, headers, contentLocation: response.headers.get('content-location') };
    if (response.status !== 200) metadata.responseBody = await response.text(); save(label + '-http', metadata);
    expect(response.status).toBe(200); expect(identity(response)).toEqual(run);
    response.text().then(text => fs.writeFileSync(testInfo.outputPath(label + '.sse'), text)).catch(e => save(label + '-stream-error', { error: e.message }));
    return metadata;
  };
  const steerHttp = async run => {
    const before = await status(run); save('fallback-steer-before', before);
    return sendHttp(run, { assistant_id: 'lead_agent', input: { messages: [{ id: crypto.randomUUID(), type: 'human', content: [{ type: 'text', text: steerText }], additional_kwargs: {} }] },
      context: { model_name: 'w16-deepseek-live' }, stream_mode: ['messages-tuple', 'updates', 'custom'], on_disconnect: 'continue' }, { 'X-Haifa-Run-Input': 'steer' }, 'fallback-steer');
  };
  const approveHttp = async (run, waiting, label) => sendHttp(run, {
    assistant_id: 'lead_agent', input: { messages: [{ type: 'human', content: [{ type: 'text', text: 'approve' }], additional_kwargs: { hide_from_ui: true, human_input_response: {
      version: 1, kind: 'human_input_response', source: 'haifa_interaction', response_kind: 'option', request_id: card(waiting).request_id, option_id: 'approve', value: 'approve' } } }] },
    context: { model_name: 'w16-deepseek-live' }, stream_mode: ['messages-tuple', 'updates', 'custom'], on_disconnect: 'continue'
  }, {}, label);
  const finish = async (run, approvedCall, approvedArgs, label) => {
    let terminal; let additionalApprovals = 0; const laterInputs = [];
    await expect.poll(async () => { terminal = await status(run);
      if (terminal.diagnostics?.sdk_status === 'WAITING_APPROVAL') {
        if (++additionalApprovals > 3) throw new Error('More than three additional write approvals');
        const waiting = await state(run); const target = calls(waiting).find(c => c.id === card(waiting)?.tool_call_id);
        expect(target?.name).toBe('write_file'); expect(target.args.path).toBe(output);
        laterInputs.push({ id: target.id, args: JSON.stringify(target.args), sha256: hash(Buffer.from(JSON.stringify(target.args))) });
        save(label + '-additional-pending-' + additionalApprovals, waiting);
        if (label === 'ui') {
          try { await uiApprove(run, waiting, label + '-additional-' + additionalApprovals); }
          catch (uiError) {
            record.additionalUiFailure = { at: new Date().toISOString(), request: card(waiting), error: uiError.message };
            await page.screenshot({ path: testInfo.outputPath('additional-ui-fail.png'), fullPage: true });
            save('additional-ui-fail', record.additionalUiFailure);
            await approveHttp(run, waiting, label + '-additional-http-' + additionalApprovals); record.BTransport = 'same-Run mixed UI and HTTP';
          }
        } else await approveHttp(run, waiting, label + '-additional-' + additionalApprovals);
        return 'running';
      }
      if (terminal.status === 'error' || terminal.status === 'interrupted') throw new Error(JSON.stringify(terminal));
      return terminal.status; }, { timeout: 100_000, intervals: [250, 500, 1000] }).toBe('success');
    expect(terminal.diagnostics?.sdk_status).toBe('COMPLETED');
    const final = await state(run); save(label + '-final-state', final); save(label + '-terminal', terminal);
    expect(JSON.stringify(calls(final).find(c => c.id === approvedCall.id)?.args)).toBe(approvedArgs);
    if (label === 'ui') expect(JSON.stringify(calls(final).find(c => c.id === record.initialWrite?.id)?.args)).toBe(record.initialWrite.args);
    const activation = calls(final).find(c => c.name === 'skill_load');
    expect(reply(final, activation)?.content).toContain('Activated Skill consulting-analysis');
    expect(reply(final, activation)?.additional_kwargs?.haifa_tool_result).toMatchObject({ skill: 'consulting-analysis', activated: true });
    const read = calls(final).find(c => c.name === 'read_file' && c.args?.path?.endsWith('customers.csv'));
    expect(reply(final, read)?.content).toContain('C017,East,1700');
    const policy = JSON.parse(reply(final, calls(final).find(c => c.name.endsWith('_get_reporting_policy')))?.content ?? 'null');
    expect(policy).toMatchObject({ policy_id: 'v2-pilot-reporting-001', high_amount_threshold_cny: 1500, comparison: '>=', server_id: 'v2-reporting' });
    for (const item of laterInputs) expect(JSON.stringify(calls(final).find(c => c.id === item.id)?.args)).toBe(item.args);
    const draft = calls(final).find(c => c.name === 'write_file' && c.args?.path === '/mnt/user-data/outputs/customer-summary-draft.md');
    expect(draft).toBeTruthy(); const draftReceipt = reply(final, draft)?.additional_kwargs?.haifa_tool_result; expect(draftReceipt?.artifactId).toBeTruthy();
    const write = calls(final).filter(c => c.name === 'write_file' && c.args?.path === output).at(-1);
    const receipt = reply(final, write)?.additional_kwargs?.haifa_tool_result;
    expect(receipt?.artifactId).toBeTruthy(); expect(receipt.artifactId).not.toBe(draftReceipt.artifactId);
    const get = await context.request.get(`${gateway}/api/threads/${run.threadId}/artifacts${output}?download=true`);
    expect(get.status()).toBe(200); const bytes = await get.body(); const text = bytes.toString('utf8');
    fs.writeFileSync(testInfo.outputPath(label + '-customer-summary.md'), bytes);
    expect(text).toBe(write.args.content);
    for (const number of ['20', 'C017', '1700', '10000', '11000', '21000', '1500', 'v2-pilot-reporting-001']) expect(text.replaceAll(',', '')).toContain(number);
    expect(text).toMatch(/East/); expect(text).toMatch(/West/); const highSection = text.match(/高额[^\n]*\n([\s\S]*?)(?=\n##|$)/)?.[1] ?? text;
    expect(highSection).toMatch(/(?:共\s*6\s*行|行数\s*[:：]?\s*6|6\s*行)/);
    for (const id of ['C015','C016','C017','C018','C019','C020']) expect(highSection).toContain(id);
    expect(text).toMatch(/风险.*数据缺口|风险[\s\S]*数据缺口/); expect(text).toMatch(/来源|v2-reporting/);
    const steerMessages = final.values.messages.filter(m => m.type === 'human' && JSON.stringify(m.content).includes(steerText));
    expect(steerMessages).toHaveLength(1); save(label + '-steer-message', steerMessages);
    const rows = fs.readFileSync(csv, 'utf8').trim().split(/\r?\n/).slice(1).map(line => line.split(','));
    const oracle = { count: rows.length, east: rows.filter(r => r[1] === 'East').reduce((n,r)=>n+Number(r[2]),0), west: rows.filter(r => r[1] === 'West').reduce((n,r)=>n+Number(r[2]),0), high: rows.filter(r=>Number(r[2]) >= policy.high_amount_threshold_cny).length, row17: rows[16] };
    expect(oracle).toEqual({ count: 20, east: 10000, west: 11000, high: 6, row17: ['C017', 'East', '1700'] });
    save(label + '-result', { run, terminal, policy, oracle, laterInputs, draft, draftReceipt, approvedCall, approvedArgs, write, receipt, byteLength: bytes.length, sha256: hash(bytes) });
    return { run, receipt, terminal };
  };
  try {
    const enabled = await context.request.put(`${gateway}/api/skills/consulting-analysis`, { data: { enabled: true } });
    expect(enabled.status()).toBe(200); save('skill-prerequisite', await enabled.json());
    await page.goto('/workspace/chats/new', { waitUntil: 'domcontentloaded' });
    const input = page.locator('textarea[name="message"]'); await expect(input).toBeVisible();
    await page.locator('button[aria-haspopup="dialog"]').filter({ hasText: /W18 Controlled|W16 DeepSeek Live/ }).first().click();
    await page.getByRole('button', { name: 'W16 DeepSeek Live (w16-deepseek-live)', exact: true }).click();
    const stream = page.waitForResponse(r => r.request().method() === 'POST' && /\/runs\/stream$/.test(new URL(r.url()).pathname));
    await page.getByLabel('Upload files').setInputFiles({ name: 'customers.csv', mimeType: 'text/csv', buffer: fs.readFileSync(csv) });
    await input.fill(prompt); await page.getByRole('button', { name: 'Submit' }).click();
    const first = await stream; expect(first.status()).toBe(200); const run = identity(first); record.uiRun = run;
    const waitingMcp = await pending(run); save('ui-mcp-pending', waitingMcp);
    const mcp = calls(waitingMcp).find(c => c.id === card(waitingMcp).tool_call_id);
    expect(mcp.name).toMatch(/_get_reporting_policy$/); record.aStage = 'mcp-card'; const args = JSON.stringify(mcp.args);
    await uiApprove(run, waitingMcp, 'ui-mcp');
    record.steerBefore = await status(run);
    const steerResponse = page.waitForResponse(r => r.request().headers()['x-haifa-run-input'] === 'steer', { timeout: 15_000 });
    steerResponse.catch(()=>{});
    await input.fill(steerText); await input.press('Enter');
    try {
      const ack = await steerResponse; expect(ack.status()).toBe(200); expect(identity(ack)).toEqual(run); record.uiSteer = 'PASS';
    } catch(error) { record.uiSteer = 'FAIL'; record.uiSteerError = error.message; }
    const waitingWrite = await pending(run); save('ui-write-pending', waitingWrite);
    expect(card(waitingWrite).request_id).not.toBe(card(waitingMcp).request_id);
    record.aStage = 'write-card'; const write = calls(waitingWrite).find(c => c.id === card(waitingWrite).tool_call_id); expect(write.name).toBe('write_file'); expect(write.args.path).toBe('/mnt/user-data/outputs/customer-summary-draft.md');
    record.initialWrite = { id: write.id, args: JSON.stringify(write.args), sha256: hash(Buffer.from(JSON.stringify(write.args))) }; save('ui-write-input', { id: write.id, args: write.args, utf8Sha256: hash(Buffer.from(JSON.stringify(write.args))) }); await uiApprove(run, waitingWrite, 'ui-write'); record.A = 'PASS';
    expect(record.uiSteer).toBe('PASS'); record.combinedResult = await finish(run, mcp, args, 'ui'); record.B = 'PASS';
  } catch(error) {
    record.A = record.A === 'PASS' ? 'PASS' : record.aStage ? 'FAIL' : 'NOT_RUN'; record.firstFail = { at: new Date().toISOString(), message: error.message, stack: error.stack };
    await page.screenshot({ path: testInfo.outputPath('first-fail.png'), fullPage: true }); save('first-fail', record);
    if (record.A === 'PASS' && record.uiSteer === 'PASS') { record.B = 'FAIL'; throw error; }
    // Preserve the UI failure; independent B uses a fresh actual Run via HTTP.
    try {
      const initial = await context.request.post(`${gateway}/api/threads`, { data: {} }); expect(initial.status()).toBe(200);
      const created = await initial.json(); const threadId = created.thread_id;
      const upload = await context.request.post(`${gateway}/api/threads/${threadId}/uploads`, { multipart: { files: { name: 'customers.csv', mimeType: 'text/csv', buffer: fs.readFileSync(csv) } } });
      expect(upload.status()).toBe(200); const uploaded = await upload.json(); save('fallback-upload-prerequisite', uploaded);
      const start = await fetch(`${gateway}/api/threads/${threadId}/runs/stream`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ assistant_id: 'lead_agent', input: { messages: [{ type: 'human', content: [{ type: 'text', text: prompt + '\n附件路径：' + uploaded.files[0].virtual_path }], additional_kwargs: {} }] }, context: { model_name: 'w16-deepseek-live' }, stream_mode: ['messages-tuple', 'updates', 'custom'], on_disconnect: 'continue' }), signal: AbortSignal.timeout(110_000) });
      if (start.status !== 200) save('fallback-start-error', { status: start.status, body: await start.text() }); expect(start.status).toBe(200); const run = identity(start); record.fallbackRun = run;
      start.text().then(t=>fs.writeFileSync(testInfo.outputPath('fallback-initial.sse'), t)).catch(()=>{});
      const mcpState = await pending(run); save('fallback-mcp-pending', mcpState);
      const mcp = calls(mcpState).find(c=>c.id === card(mcpState).tool_call_id); expect(mcp.name).toMatch(/_get_reporting_policy$/);
      const args = JSON.stringify(mcp.args); await approveHttp(run, mcpState, 'fallback-mcp-approve');
      await steerHttp(run);
      const writeState = await pending(run); save('fallback-write-pending', writeState);
      expect(calls(writeState).find(c=>c.id === card(writeState).tool_call_id)?.name).toBe('write_file');
      await approveHttp(run, writeState, 'fallback-write-approve'); record.fallbackResult = await finish(run, mcp, args, 'fallback'); record.B = 'PASS';
    } catch(fallbackError) { record.B = record.fallbackRun ? 'FAIL' : 'NOT_RUN'; record.fallbackError = { message: fallbackError.message, stack: fallbackError.stack }; }
  } finally {
    record.finishedAt = new Date().toISOString(); save('case-result', record);
  }
  expect(record.A, record.firstFail?.message).toBe('PASS');
  expect(record.B, record.fallbackError?.message).toBe('PASS');
});
