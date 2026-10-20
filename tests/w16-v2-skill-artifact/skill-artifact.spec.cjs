const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { expect, test } = require(path.resolve(__dirname, "../../frontend/node_modules/@playwright/test"));

const app = process.env.E2E_APP_URL;
const evidenceRoot = process.env.W18_DIFFERENTIAL_EVIDENCE;
const fixture = path.resolve(__dirname, "../../contracts/customer-demo-v2/fixtures/customers.csv");
const sourceIndex = require("../../contracts/customer-demo-v2/source-index.json");
const frozenSkillPath = "deer-flow/skills/public/consulting-analysis/SKILL.md";
const frozenSkillSha256 = sourceIndex.entries.find((entry) => entry.path === frozenSkillPath)?.sha256;
const skill = "consulting-analysis";
const frameworkOutput = "/mnt/user-data/outputs/customer-analysis-framework.md";
const dataPackageOutput = "/mnt/user-data/outputs/customer-data-package.json";
const reportOutput = "/mnt/user-data/outputs/customer-summary.md";
if (!app || !evidenceRoot) throw new Error("W16 app and external evidence root are required");
if (!frozenSkillSha256) throw new Error(`frozen Skill is absent from source index: ${frozenSkillPath}`);

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function identity(response) {
  const location = response.headers()["content-location"];
  const match = location?.match(/\/api\/threads\/([^/]+)\/runs\/([^/]+)$/);
  expect(match, `invalid Run Content-Location: ${location}`).toBeTruthy();
  return { threadId: match[1], runId: match[2] };
}

async function save(testInfo, name, value) {
  const file = testInfo.outputPath(`${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await testInfo.attach(`${name}.json`, { path: file, contentType: "application/json" });
}

async function runState(request, run) {
  const response = await request.get(`${app}/api/langgraph/threads/${run.threadId}/state`);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function sendUploaded(page, marker, options = {}) {
  await page.goto("/workspace/chats/new", { waitUntil: "domcontentloaded" });
  const input = page.locator('textarea[name="message"]');
  await expect(input).toBeVisible();
  if (options.model) {
    const picker = page.locator('button[aria-haspopup="dialog"]').filter({ hasText: /W18 Controlled|W16 DeepSeek Live/ }).first();
    await expect(picker).toBeEnabled();
    await picker.click();
    const choice = page.getByRole("button", { name: new RegExp(`\\(${options.model}\\)$`) }).first();
    await expect(choice).toBeVisible();
    await choice.click();
  }
  const bytes = fs.readFileSync(fixture);
  const files = options.files ?? [{ name: "customers.csv", mimeType: "text/csv", buffer: bytes }];
  const uploadResponse = page.waitForResponse((response) => response.request().method() === "POST" &&
    /\/api\/threads\/[^/]+\/uploads$/.test(new URL(response.url()).pathname), { timeout: 30_000 });
  await page.getByLabel("Upload files").setInputFiles(files);
  const streamResponse = page.waitForResponse((response) => response.request().method() === "POST" &&
    /\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname),
  { timeout: 30_000 });
  await input.fill(options.prompt ?? `${marker}: read the attached original, activate the available consulting-analysis Skill, call the configured reporting policy MCP Tool, and save an audit of actual Tool replies.`);
  await page.getByRole("button", { name: "Submit" }).click();
  const upload = await uploadResponse;
  expect(upload.status(), await upload.text()).toBe(200);
  const uploaded = await upload.json();
  expect(uploaded.files).toHaveLength(files.length);
  const stream = await streamResponse;
  expect(stream.status(), await stream.text()).toBe(200);
  return { ...identity(stream), upload: uploaded, uploadUrl: upload.url(), csvSha256: sha256(bytes),
    inputFiles: files.map((file) => ({ name: file.name, byteLength: file.buffer.length, sha256: sha256(file.buffer) })) };
}

async function approveNext(page, run) {
  const approve = page.getByTestId("human-input-card")
    .getByRole("button", { name: "Approve", exact: true }).filter({ visible: true }).last();
  await expect(approve).toBeEnabled({ timeout: 40_000 });
  let hidden = null;
  const continuation = page.waitForResponse((response) => {
    if (response.request().method() !== "POST" ||
        !/\/api\/langgraph\/threads\/[^/]+\/runs\/stream$/.test(new URL(response.url()).pathname)) return false;
    try {
      hidden = response.request().postDataJSON()?.input?.messages?.at(-1)?.additional_kwargs?.human_input_response;
      return Boolean(hidden);
    } catch { return false; }
  }, { timeout: 40_000 });
  await approve.click();
  const response = await continuation;
  expect(response.status(), await response.text()).toBe(200);
  expect(identity(response)).toEqual({ threadId: run.threadId, runId: run.runId });
  expect(hidden).toMatchObject({ option_id: "approve", value: "approve" });
  return hidden;
}

async function waitTerminal(request, run) {
  let latest;
  await expect.poll(async () => {
    const response = await request.get(`${app}/api/langgraph/threads/${run.threadId}/runs/${run.runId}`);
    expect(response.status(), await response.text()).toBe(200);
    latest = await response.json();
    return latest.status;
  }, { timeout: 90_000, intervals: [250, 500, 1_000] }).toBe("success");
  return latest;
}

function toolCalls(state) {
  return (state.values?.messages ?? []).flatMap((message) => message.tool_calls ?? []);
}

function toolReply(state, name) {
  return (state.values?.messages ?? []).find((message) => message.type === "tool" && message.name === name);
}

function replyForCall(state, call) {
  if (!call) return undefined;
  return (state.values?.messages ?? []).find((message) =>
    message.type === "tool" && message.tool_call_id === call.id);
}

function assertCommittedWrite(state, run, output, download) {
  expect(download, `${output} must be downloadable`).toBeTruthy();
  const calls = toolCalls(state).filter((call) => call.name === "write_file" && call.args?.path === output);
  expect(calls, `${output} must have exactly one model-authored write_file call`).toHaveLength(1);
  const call = calls[0];
  const replies = (state.values?.messages ?? []).filter((message) =>
    message.type === "tool" && message.name === "write_file" && message.tool_call_id === call.id);
  expect(replies, `${output} must have exactly one matching production Tool reply`).toHaveLength(1);
  const receipt = replies[0].additional_kwargs?.haifa_tool_result;
  expect(receipt).toMatchObject({ artifactExecutionRef: call.id, runId: run.runId,
    path: output, sha256: download.sha256, artifactByteCount: download.byteLength });
  expect(receipt.artifactId).toBeTruthy();
  expect(state.values?.artifacts ?? [], `${output} must be present in Run Artifacts`).toContain(output);
  expect(download.sha256, `${output} downloaded bytes must equal the Tool argument bytes`)
    .toBe(sha256(Buffer.from(call.args.content)));
  const matchingReceipts = (state.values?.messages ?? []).filter((message) =>
    message.type === "tool" && message.name === "write_file" &&
    message.additional_kwargs?.haifa_tool_result?.path === output);
  expect(matchingReceipts, `${output} receipt path must map back to the same ToolCall`).toHaveLength(1);
  expect(matchingReceipts[0].tool_call_id).toBe(call.id);
  return call;
}

function assertInputRead(state, input, virtualPath) {
  const calls = toolCalls(state).filter((call) => call.name === "read_file" && call.args?.path === virtualPath);
  expect(calls.length, `this Run must read ${input.name ?? virtualPath}`).toBeGreaterThan(0);
  const replies = calls.map((call) => replyForCall(state, call)?.additional_kwargs?.haifa_tool_result)
    .filter((reply) => reply?.path === virtualPath && reply.sha256 === input.sha256 && !reply.error);
  expect(replies.length, `${virtualPath} must have successful original-SHA reads`).toBeGreaterThan(0);
  const totalLines = replies[0].totalLines;
  expect(totalLines).toBeGreaterThan(0);
  const lines = new Map();
  for (const reply of replies) {
    expect(reply.totalLines).toBe(totalLines);
    // read_file has a byte cap. Its actual returned endLine (not the requested
    // end_line) defines coverage; truncated pieces can contribute only the
    // bytes they really returned. Preserve every newline byte for hashing.
    const parts = reply.content.match(/[^\n]*(?:\n|$)/g).filter((part) => part !== "");
    expect(parts.length).toBe(reply.endLine - reply.startLine + 1);
    parts.forEach((part, offset) => {
      const line = reply.startLine + offset;
      expect(line).toBeGreaterThanOrEqual(1);
      expect(line).toBeLessThanOrEqual(totalLines);
      if (lines.has(line)) expect(lines.get(line), `overlapping read line ${line} must agree`).toBe(part);
      lines.set(line, part);
    });
  }
  expect(lines.size, `${virtualPath} must cover every original line without gaps`).toBe(totalLines);
  const reconstructed = Buffer.from(Array.from({ length: totalLines }, (_, index) => lines.get(index + 1)).join(""));
  expect(sha256(reconstructed), `${virtualPath} complete Tool-returned bytes must equal uploaded original`).toBe(input.sha256);
  expect(reconstructed.length).toBe(input.byteLength);
  return calls.map((call) => ({ toolCallId: call.id, result: replyForCall(state, call)?.additional_kwargs?.haifa_tool_result,
    fullOriginalProof: { totalLines, coveredLines: lines.size, byteLength: reconstructed.length,
      reconstructedSha256: sha256(reconstructed) } }));
}

function regionCellMatches(cell, region) {
  const translated = { East: "华东", West: "华西" }[region];
  if (!translated) return cell === region;
  const aliases = new Set([region.toLowerCase(), translated]);
  const tokens = cell.normalize("NFKC").trim().split(/[\s/()]+/).filter(Boolean);
  // Every token must name this one region. Mixed or unknown labels fail even
  // when a valid English region name appears elsewhere in the same cell.
  return tokens.length > 0 && tokens.every((token) => aliases.has(token.toLowerCase()));
}

function tableHasOrderedFacts(markdown, key, facts) {
  const rows = markdown.split(/\r?\n/).filter((line) => /^\s*\|/.test(line))
    .map((line) => line.split("|").slice(1, -1)
      .map((cell) => cell.replace(/[,*`]/g, "").replace(/\s*(?:CNY|元)$/i, "").trim()));
  return rows.some((cells) => {
    let cursor = cells.findIndex((cell) => regionCellMatches(cell, key));
    if (cursor < 0) return false;
    for (const fact of facts) {
      cursor = cells.findIndex((cell, index) => index > cursor && regionCellMatches(cell, String(fact)));
      if (cursor < 0) return false;
    }
    return true;
  });
}

function wholeFileHighCountTableClaims(markdown, policy) {
  const claims = [];
  const clean = (cell) => cell.normalize("NFKC").replace(/[*`\s]/g, "");
  for (const match of markdown.matchAll(/(?:^\s*\|[^\n]*\|\s*\n?)+/gm)) {
    const block = match[0];
    const rows = block.trim().split(/\r?\n/).map((line) => line.split("|").slice(1, -1).map(clean));
    const header = rows[0];
    const countAt = header.findIndex((cell) => /^(?:全量达标客户数|全量高额客户数|全量高金额客户数)$/.test(cell));
    const policyAt = header.findIndex((cell) => cell === "政策ID");
    const thresholdAt = header.findIndex((cell) => /^阈值(?:\(CNY\))?$/.test(cell));
    const comparisonAt = header.findIndex((cell) => cell === "比较关系");
    const scopeAt = header.findIndex((cell) => cell === "口径");
    const hitAt = header.findIndex((cell) => cell === "命中行数");
    const before = markdown.slice(0, match.index);
    const chapter = [...before.matchAll(/^##\s+[^\n]+/gm)].at(-1);
    const context = chapter ? before.slice(chapter.index) : "";
    const scopedPolicyTable = scopeAt >= 0 && hitAt >= 0 &&
      /策略阈值|政策阈值|高额|高金额/.test(chapter?.[0] ?? "") &&
      context.includes(policy.policy_id) &&
      clean(context).replace(/,/g, "").includes(String(policy.high_amount_threshold_cny)) &&
      context.includes(policy.comparison);
    if (scopedPolicyTable) {
      for (const cells of rows.slice(2)) {
        if (!/^(?:全文件|全表|全量)$/.test(cells[scopeAt])) continue;
        claims.push({ count: /^\d+$/.test(cells[hitAt]) ? Number(cells[hitAt]) : NaN, row: cells });
      }
      continue;
    }
    if ([countAt, policyAt, thresholdAt, comparisonAt].some((index) => index < 0) ||
        header.some((cell) => /region|地区/i.test(cell))) continue;
    for (const cells of rows.slice(2)) {
      // A whole-file policy result has one actual policy key and no regional
      // row key. Regional counts and unrelated numbers cannot supply this claim.
      if (cells[policyAt] !== policy.policy_id ||
          cells.some((cell) => /(?:^|[/(])(?:East|West|华东|华西)(?:$|[/)])/i.test(cell)) ||
          Number(cells[thresholdAt]) !== policy.high_amount_threshold_cny || cells[comparisonAt] !== policy.comparison) continue;
      claims.push({ count: /^\d+$/.test(cells[countAt]) ? Number(cells[countAt]) : NaN, row: cells });
    }
  }
  return claims;
}

function reportSourceDisciplineIssues(markdown, packageSourceSha256, csvSha256) {
  const issues = [];
  const plain = markdown.replace(/[*`]/g, "");
  for (const line of plain.split(/\r?\n/)) {
    // Target the demonstrated false comparison, rather than claiming to parse
    // arbitrary prose. Other source assertions remain a controller review gate.
    if (packageSourceSha256 === csvSha256 &&
        /(?:Data Package|customer-data-package\.json|数据包)\s*(?:内部|内|中|的)/i.test(line) &&
        /source_file_sha256/.test(line) && /customers\.csv|CSV/.test(line) &&
        /不完全一致|不一致|不匹配/.test(line) && !/MCP/.test(line))
      issues.push({ code: "SOURCE_HASH_FALSE_MISMATCH", claim: line });
    if (/19\s*奇数行\s*\/\s*20\s*偶数行/.test(line))
      issues.push({ code: "REGIONAL_LINE_NUMBER_COUNT_CONFLATION", claim: line });
    if (/^\[\d+\]\s/.test(line)) {
      if (/\b(?:19|20)\d{2}\b/.test(line))
        issues.push({ code: "SOURCE_REFERENCE_YEAR_UNSUPPORTED", claim: line });
      else if (!/n\.d\.|不可得|日期(?:未知|未提供|不详)/i.test(line))
        issues.push({ code: "SOURCE_REFERENCE_UNKNOWN_DATE_NOT_MARKED", claim: line });
    }
  }
  return issues;
}

function wholeFileHighCountNarrativeClaims(markdown, policy) {
  const plain = markdown.replace(/[*`,]/g, "");
  const claims = [];
  for (const match of plain.matchAll(/(?:全文件|全表|全量)(?:高额|高金额)?(?:命中|达标)(?:客户|记录)?\s*[:：]?\s*(\d+)\s*(?:条|行|位)/g)) {
    const before = plain.slice(0, match.index);
    const chapter = [...before.matchAll(/^#{1,6}\s+[^\n]+/gm)].at(-1);
    const context = chapter ? before.slice(chapter.index) : before.split(/\r?\n/).at(-1);
    if (context.includes(policy.policy_id) && context.includes(String(policy.high_amount_threshold_cny)) &&
        /高额|高金额|阈值|筛查/.test(context))
      claims.push({ count: Number(match[1]), claim: match[0] });
  }
  return claims;
}

function providerEvents(marker) {
  const file = path.join(evidenceRoot, "provider.stdout.log");
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, "utf8").split(/\r?\n/).filter((line) => line.startsWith("{"))
    .map((line) => { try { return JSON.parse(line); } catch { return null; } })
    .filter((event) => event?.marker === marker);
}

test("W16 controlled L2 one Run combines Skill, saved original, true MCP transport, Approval and Artifact", async ({ context, page }, testInfo) => {
  const detailResponse = await context.request.get(`${app}/api/skills/${skill}`);
  expect(detailResponse.status(), await detailResponse.text()).toBe(200);
  const detail = await detailResponse.json();
  expect(detail).toMatchObject({ name: skill, enabled: true, source: "bytedance/deer-flow",
    source_sha: sourceIndex.frozenUpstreamCommit, sha256: frozenSkillSha256 });
  const marker = `w16-mechanism-${Date.now()}`;
  const startedAt = Date.now();
  const run = await sendUploaded(page, marker);
  expect(run.csvSha256).toBe("ec326b7d0cf68f660ed769f8eecf9b71b43137af4e9aa83de75aa75bc5302f00");
  const first = await approveNext(page, run);
  const second = await approveNext(page, run);
  expect(first.request_id).not.toBe(second.request_id);
  const terminal = await waitTerminal(context.request, run);
  const state = await runState(context.request, run);
  const calls = toolCalls(state);
  const names = calls.map((call) => call.name);
  expect(names).toContain("skill_load");
  expect(names).toContain("read_file");
  expect(names.some((name) => name.endsWith("_get_reporting_policy"))).toBe(true);
  expect(names.filter((name) => name === "write_file")).toHaveLength(1);
  const pathOut = `/mnt/user-data/outputs/${marker}.json`;
  expect(state.values.artifacts).toContain(pathOut);
  const events = providerEvents(marker).filter((event) => event.type === "w16_mechanism");
  expect(events.map((event) => event.action)).toEqual([
    "skill_load", "read_file", "mcp_policy", "write_file", "done",
  ]);
  expect(events.find((event) => event.action === "write_file").skillVisible).toBe(true);
  await save(testInfo, "w16-controlled-tool-chain", {
    marker, run, detail, approvals: [first, second], status: terminal.status,
    toolCalls: calls, state, providerEvents: events, elapsedMs: Date.now() - startedAt,
  });
});

function independentCsvSummary() {
  const rows = fs.readFileSync(fixture, "utf8").trim().split(/\r?\n/).slice(1)
    .map((line) => {
      const [customerId, region, amountText] = line.split(",");
      const amount = Number(amountText);
      if (!customerId || !region || !Number.isSafeInteger(amount)) throw new Error(`invalid CSV row: ${line}`);
      return { customerId, region, amount };
    });
  const highAmountRows = rows.filter((row) => row.amount >= 1500);
  return {
    rowCount: rows.length,
    sourceRows: rows,
    regionalTotals: Object.fromEntries([...new Set(rows.map((row) => row.region))]
      .map((region) => [region, rows.filter((row) => row.region === region)
        .reduce((sum, row) => sum + row.amount, 0)])),
    total: rows.reduce((sum, row) => sum + row.amount, 0),
    highAmountCount: highAmountRows.length,
    highAmountRows,
    highestRows: [...rows].sort((a, b) => b.amount - a.amount).slice(0, 3),
    highAmountTotalsByRegion: Object.fromEntries([...new Set(rows.map((row) => row.region))]
      .map((region) => [region, highAmountRows.filter((row) => row.region === region)
        .reduce((sum, row) => sum + row.amount, 0)])),
    regionalCounts: Object.fromEntries([...new Set(rows.map((row) => row.region))]
      .map((region) => [region, rows.filter((row) => row.region === region).length])),
    highAmountByRegion: Object.fromEntries([...new Set(rows.map((row) => row.region))]
      .map((region) => [region, rows.filter((row) => row.region === region && row.amount >= 1500).length])),
    regionalStep: Object.fromEntries([...new Set(rows.map((row) => row.region))]
      .map((region) => {
        const amounts = rows.filter((row) => row.region === region).map((row) => row.amount).sort((a, b) => a - b);
        const steps = amounts.slice(1).map((amount, index) => amount - amounts[index]);
        return [region, steps.every((step) => step === steps[0]) ? steps[0] : null];
      })),
    c017: rows.find((row) => row.customerId === "C017"),
    fixtureSha256: sha256(fs.readFileSync(fixture)),
  };
}

function reportBehavior(markdown) {
  const plain = markdown.replace(/\*/g, "");
  const headings = [...plain.matchAll(/^#{1,4}\s+(.+)$/gm)].map((match) => match[1]);
  const abstract = headings.findIndex((heading, index) => index > 0 && /摘要|abstract/i.test(heading));
  const introduction = headings.findIndex((heading, index) => index > 0 && /引言|introduction/i.test(heading));
  const conclusion = headings.findIndex((heading, index) => index > 0 && /结论|conclusion/i.test(heading));
  const references = headings.findIndex((heading, index) => index > 0 && /参考|references/i.test(heading));
  return {
    headings,
    skillSectionOrder: abstract > 0 && introduction > abstract && conclusion > introduction + 1 &&
      references > conclusion && references === headings.length - 1,
    numberedSourceCitations: [...plain.matchAll(/^\s*\[\d+\]\s+\S/gm)].length >= 2,
  };
}

function verifyDataPackage(content, summary, policy, record) {
  const failures = [];
  let data;
  const check = (name, assertion) => {
    try { assertion(); } catch (error) { failures.push({ check: name, message: error.message }); }
  };
  check("valid JSON", () => { data = JSON.parse(content); });
  if (data) {
    check("source and whole-file facts", () => expect(data).toMatchObject({
      source_file_sha256: summary.fixtureSha256, policy_id: policy.policy_id,
      threshold_cny: policy.high_amount_threshold_cny, comparison: policy.comparison,
      data_row_count: summary.rowCount, amount_total_cny: summary.total,
    }));
    for (const region of Object.keys(summary.regionalTotals)) {
      check(`${region} facts`, () => expect(data.regions?.[region]).toMatchObject({
        count: summary.regionalCounts[region], amount_total_cny: summary.regionalTotals[region],
        high_amount_count: summary.highAmountByRegion[region],
        high_amount_total_cny: summary.highAmountTotalsByRegion[region],
      }));
      const rows = summary.sourceRows.filter((row) => row.region === region);
      const audit = data.calc_audit?.regions?.[region];
      check(`${region} source_rows`, () => expect(audit?.source_rows).toEqual(rows.map((row) => ({
        customer_id: row.customerId, amount_cny: row.amount,
      }))));
      check(`${region} subtotal expression`, () => verifySumExpression(audit?.subtotal_expression,
        rows.map((row) => row.amount), summary.regionalTotals[region]));
      check(`${region} high-amount expression`, () => verifySumExpression(audit?.high_amount_expression,
        rows.filter((row) => row.amount >= policy.high_amount_threshold_cny).map((row) => row.amount),
        summary.highAmountTotalsByRegion[region]));
      check(`${region} audit totals`, () => expect(audit).toMatchObject({
        subtotal_cny: summary.regionalTotals[region], high_amount_count: summary.highAmountByRegion[region],
        high_amount_total_cny: summary.highAmountTotalsByRegion[region],
      }));
    }
    check("region total reconciliation", () => expect(Object.values(data.regions ?? {})
      .reduce((sum, region) => sum + region.amount_total_cny, 0)).toBe(data.amount_total_cny));
    check("region row-count reconciliation", () => expect(Object.values(data.regions ?? {})
      .reduce((sum, region) => sum + region.count, 0)).toBe(data.data_row_count));
    check("region high-amount-count reconciliation", () => expect(Object.values(data.regions ?? {})
      .reduce((sum, region) => sum + region.high_amount_count, 0)).toBe(summary.highAmountCount));
    check("threshold-matching rows", () => expect(data.high_amount_customers?.map((row) => ({
      customerId: row.customer_id, region: row.region, amount: row.amount_cny,
    })).sort((a, b) => a.customerId.localeCompare(b.customerId))).toEqual([...summary.highAmountRows]
      .sort((a, b) => a.customerId.localeCompare(b.customerId))));
    check("whole-file subtotal expression", () => verifySumExpression(data.calc_audit?.whole_file?.subtotal_expression,
      summary.sourceRows.map((row) => row.amount), summary.total));
    const highTotal = summary.highAmountRows.reduce((sum, row) => sum + row.amount, 0);
    check("whole-file high-amount expression", () => verifySumExpression(data.calc_audit?.whole_file?.high_amount_expression,
      summary.highAmountRows.map((row) => row.amount), highTotal));
    check("whole-file independent totals", () => expect(data.calc_audit?.whole_file).toMatchObject({
      subtotal_cny: summary.total, data_row_count: summary.rowCount,
      high_amount_count: summary.highAmountCount, high_amount_total_cny: highTotal,
    }));
    check("Data Summary data range", () => expect(data.data_summary?.data_range).toMatchObject({
      source_file_sha256: summary.fixtureSha256, data_row_count: summary.rowCount, amount_total_cny: summary.total,
    }));
    check("Data Summary region copies", () => expect(data.data_summary?.regional_aggregation).toEqual(data.regions));
    check("Data Summary policy chapter", () => expect(data.data_summary?.policy_threshold_screening).toMatchObject({
      policy_id: policy.policy_id, threshold_cny: policy.high_amount_threshold_cny,
      comparison: policy.comparison, high_amount_customers: data.high_amount_customers,
    }));
    check("no fabricated chart files", () => expect(data.chart_files).toEqual([]));
    check("no fabricated search findings", () => expect(data.external_search_findings).toEqual([]));
    check("missing data", () => {
      expect(data.missing_data).toEqual(expect.any(Array));
      expect(data.missing_data.length).toBeGreaterThan(0);
    });
  }
  const failureMessage = `Data Package Oracle found ${failures.length} errors:\n` +
    failures.map((failure) => `${failure.check}: ${failure.message}`).join("\n\n");
  if (record) {
    record.dataPackageValidation = { failures, parsed: Boolean(data), failureMessage };
    // The caller persists the entire validation set before rejecting Approval.
    if (failures.length) return null;
  }
  if (failures.length) throw new Error(failureMessage);
  return data;
}

function verifySumExpression(expression, expectedAmounts, expectedTotal) {
  expect(typeof expression, "model must give a source-row sum expression").toBe("string");
  const normalized = expression.replace(/,/g, "").replace(/\s/g, "");
  expect(normalized, "sum expression must use source amounts joined by + and one = subtotal")
    .toMatch(/^\d+(?:\+\d+)*=\d+$/);
  const [left, right] = normalized.split("=");
  const amounts = left.split("+").map(Number);
  expect(amounts, "expression must enumerate every relevant original row in CSV order").toEqual(expectedAmounts);
  expect(Number(right)).toBe(expectedTotal);
  expect(amounts.reduce((sum, amount) => sum + amount, 0)).toBe(Number(right));
}

function verifyFramework(content) {
  for (const section of [/Research Overview|研究概览|研究总览/, /Framework Selection|框架选择/,
    /Chapter Skeleton|章节框架|章节大纲/, /Data Collection Task List|数据收集任务|数据采集任务/,
    /Analysis Objective|分析目标/, /Analysis Logic|分析逻辑/, /Core Hypothesis|核心假设/,
    /Data Requirements|数据需求/, /Visualization|可视化/, /P0/]) expect(content).toMatch(section);
  // Phase 2's Introduction is required independently of the body skeleton.
  expect(content).toMatch(/Introduction|引言/);
  expect(content).toMatch(/Abstract|摘要/);
  expect(content).toMatch(/Conclusion|结论/);
  expect(content).toMatch(/References|参考/);
}

function reportingPolicy(state) {
  const call = toolCalls(state).find((item) => item.name.endsWith("_get_reporting_policy"));
  const policy = JSON.parse(replyForCall(state, call)?.content ?? "null");
  expect(policy, "this Run must obtain policy through the actual SDK MCP Tool").toMatchObject({
    policy_id: "v2-pilot-reporting-001", high_amount_threshold_cny: 1500, comparison: ">=", server_id: "v2-reporting",
  });
  return policy;
}

function assertUploadedReads(state, run) {
  return run.inputFiles.map((input) => {
    const uploaded = run.upload.files.find((entry) => entry.filename === input.name);
    expect(uploaded, `uploaded original ${input.name}`).toBeTruthy();
    return { ...input, virtualPath: uploaded.virtual_path, reads: assertInputRead(state, input, uploaded.virtual_path) };
  });
}

function checkPendingWrite(state, record, summary) {
  const pending = toolCalls(state).filter((call) => call.name === "write_file" && !replyForCall(state, call)).at(-1);
  if (!pending) return null;
  const { path: output, content } = pending.args ?? {};
  expect(typeof content, `write_file ${output} must carry model-authored bytes`).toBe("string");
  expect(record.label === "prepare" ? [frameworkOutput, dataPackageOutput] : [reportOutput]).toContain(output);
  const savedPaths = state.values?.artifacts ?? [];
  assertUploadedReads(state, record.run);
  const policy = reportingPolicy(state);
  expect(savedPaths, "pending output must not already be a committed Artifact").not.toContain(output);
  if (output === frameworkOutput) {
    verifyFramework(content);
  } else if (output === dataPackageOutput) {
    expect(savedPaths, "Framework must be a committed Artifact before Data Package").toContain(frameworkOutput);
    verifyDataPackage(content, summary, policy, record);
  }
  return { callId: pending.id, path: output, sha256: sha256(Buffer.from(content)) };
}

async function downloadOutput(context, testInfo, record, output) {
  const response = await context.request.get(
    `${app}/api/threads/${record.run.threadId}/artifacts${output}?download=true`);
  expect(response.status(), `Artifact download ${output}: ${await response.text()}`).toBe(200);
  const bytes = await response.body();
  const file = testInfo.outputPath(`${record.label}-${path.basename(output)}`);
  fs.writeFileSync(file, bytes);
  await testInfo.attach(`${record.label}-${path.basename(output)}`, {
    path: file, contentType: output.endsWith(".json") ? "application/json" : "text/markdown",
  });
  const write = toolCalls(record.state).find((call) => call.name === "write_file" && call.args?.path === output);
  const receipt = replyForCall(record.state, write)?.additional_kwargs?.haifa_tool_result;
  return { path: output, artifactId: receipt?.artifactId, toolCallId: write?.id,
    localPath: file, status: response.status(), disposition: response.headers()["content-disposition"],
    byteLength: bytes.length, sha256: sha256(bytes), content: bytes.toString("utf8") };
}

async function runLiveReport(context, page, testInfo, label, prompt, files) {
  const marker = `w16-live-${label}-${Date.now()}`;
  const startedAt = Date.now();
  const record = { label, marker, provider: "https://api.deepseek.com",
    model: process.env.W16_LIVE_PROVIDER_MODEL || "deepseek-v4-pro",
    diagnosticAttempt: Number(process.env.W16_DIAGNOSTIC_ATTEMPT || 1) };
  try {
    // The business prompt is byte-identical for A/B. The diagnostic marker is
    // evidence metadata only and is never prepended to either report prompt.
    record.prompt = prompt;
    record.promptSha256 = sha256(Buffer.from(prompt));
    if (label !== "prepare") {
      record.businessPrompt = reportPrompt;
      record.businessPromptSha256 = sha256(Buffer.from(reportPrompt));
      record.executionNote = reportExecutionNote;
      record.effectivePromptSha256 = record.promptSha256;
      record.liveMaxOutputTokens = Number(process.env.W16_LIVE_MAX_OUTPUT_TOKENS || 4096);
    }
    record.run = await sendUploaded(page, marker, { model: "w16-deepseek-live", prompt, files });
    record.approvals = [];
    record.checkedWrites = [];
    record.downloads = {};
    record.uploadProof = [];
    for (const input of record.run.inputFiles) {
      const uploaded = record.run.upload.files.find((entry) => entry.filename === input.name);
      expect(uploaded).toBeTruthy();
      const response = await context.request.get(`${app}${uploaded.artifact_url}`);
      expect(response.status()).toBe(200);
      const downloaded = await response.body();
      record.uploadProof.push({ ...input, ...uploaded, downloadedSha256: sha256(downloaded),
        downloadedByteLength: downloaded.length });
      expect(sha256(downloaded)).toBe(input.sha256);
      expect(downloaded.length).toBe(input.byteLength);
    }
    await save(testInfo, `w16-live-${label}-uploaded-originals`, record);
    const summary = independentCsvSummary();
    for (let index = 0; index < 16; index += 1) {
      await expect.poll(async () => {
        const response = await context.request.get(`${app}/api/langgraph/threads/${record.run.threadId}/runs/${record.run.runId}`);
        expect(response.status(), await response.text()).toBe(200);
        record.latestRun = await response.json();
        if (record.latestRun.status === "success") return "success";
        if (["error", "failed", "cancelled"].includes(record.latestRun.status)) return record.latestRun.status;
        const buttons = page.getByTestId("human-input-card").getByRole("button", { name: "Approve", exact: true });
        for (let i = await buttons.count() - 1; i >= 0; i -= 1) {
          if (await buttons.nth(i).isEnabled()) return "approval";
        }
        return "waiting";
      }, { timeout: 180_000, intervals: [500, 1_000] }).toMatch(/^(approval|success|error|failed|cancelled)$/);
      // The matcher reports through the captured run/card state; do not infer
      // success from a settled assertion alone.
      if (record.latestRun.status === "success") break;
      if (["error", "failed", "cancelled"].includes(record.latestRun.status))
        throw new Error(`real-model Run ${record.run.runId} ended ${record.latestRun.status}`);
      const state = await runState(context.request, record.run);
      record.state = state;
      // Save before an Oracle assertion so the first FAIL has its real inputs.
      await save(testInfo, `w16-live-${label}-approval-${index}-before`, { run: record.run, state });
      const pendingWrite = checkPendingWrite(state, record, summary);
      if (record.dataPackageValidation?.failures.length) {
        await save(testInfo, `w16-live-${label}-data-package-validation-errors`, {
          run: record.run, pendingWrite, ...record.dataPackageValidation,
        });
        throw new Error(record.dataPackageValidation.failureMessage);
      }
      if (pendingWrite) {
        expect(record.checkedWrites.some((item) => item.callId === pendingWrite.callId),
          "same write_file call cannot be approved twice").toBe(false);
        const absent = await context.request.get(`${app}/api/threads/${record.run.threadId}/artifacts${pendingWrite.path}?download=true`);
        expect(absent.status(), "pending file must not exist before production Approval").toBe(404);
        record.checkedWrites.push(pendingWrite);
      }
      record.approvals.push(await approveNext(page, record.run));
    }
    record.terminal = await waitTerminal(context.request, record.run);
    record.state = await runState(context.request, record.run);
    record.readProof = assertUploadedReads(record.state, record.run);
    reportingPolicy(record.state);
    record.artifactPath = label === "prepare" ? dataPackageOutput : reportOutput;
    record.artifactPresent = (record.state.values?.artifacts ?? []).includes(reportOutput);
    for (const output of label === "prepare" ? [frameworkOutput, dataPackageOutput] : []) {
      if (!record.downloads[output] && (record.state.values?.artifacts ?? []).includes(output))
        record.downloads[output] = await downloadOutput(context, testInfo, record, output);
    }
    if (label !== "prepare" && record.artifactPresent) {
      const download = await downloadOutput(context, testInfo, record, reportOutput);
      record.downloads[reportOutput] = download;
      record.downloadStatus = download.status;
      record.downloadDisposition = download.disposition;
      record.reportSha256 = download.sha256;
      record.reportByteLength = download.byteLength;
      record.report = download.content;
      const packageInput = files.find((input) => input.name === path.basename(dataPackageOutput));
      record.sourceDisciplineIssues = reportSourceDisciplineIssues(record.report,
        JSON.parse(packageInput.buffer.toString("utf8")).source_file_sha256, sha256(fs.readFileSync(fixture)));
    }
    await page.screenshot({ path: testInfo.outputPath(`${label}-terminal.png`), fullPage: true });
    return record;
  } finally {
    record.elapsedMs = Date.now() - startedAt;
    await save(testInfo, `w16-live-${label}-raw`, record);
  }
}

function verifyReportFacts(record, summary, policyReply) {
  expect(record.report).toMatch(/^#\s+\S/);
  expect(record.report).toMatch(/^#{1,6}\s+[^\n]*数据范围/m);
  expect(record.report).toMatch(/^#{1,6}\s+[^\n]*按地区汇总/m);
  const normalized = record.report.replace(/,/g, "");
  const plain = normalized.replace(/\*/g, "");
  const policyIds = [...plain.matchAll(/\bv2-[a-z0-9-]*reporting-[a-z0-9-]*\d{3}\b/gi)]
    .map((match) => match[0]);
  expect(policyIds.length, "report must cite the actual MCP policy ID").toBeGreaterThan(0);
  for (const id of policyIds)
    expect(id, `report contains a policy ID contradicting the MCP reply: ${id}`).toBe(policyReply.policy_id);
  for (const required of ["10000", "11000", "21000", "C017", "1700", "1500", "v2-pilot-reporting-001"])
    expect(normalized, `report missing independently checked ${required}`).toContain(required);
  expect(tableHasOrderedFacts(record.report, "East", [10, summary.regionalTotals.East])).toBe(true);
  expect(tableHasOrderedFacts(record.report, "West", [10, summary.regionalTotals.West])).toBe(true);
  expect(tableHasOrderedFacts(record.report, "C017", ["East", summary.c017.amount])).toBe(true);
  for (const row of summary.highAmountRows)
    expect(tableHasOrderedFacts(record.report, row.customerId, [row.region, row.amount])).toBe(true);
  const highLines = plain.split(/\r?\n/).filter((line) => /高金额|高额/.test(line));
  const wholeFileTableClaims = wholeFileHighCountTableClaims(record.report, policyReply);
  for (const claim of wholeFileTableClaims)
    expect(claim.count, `whole-file high-amount table count contradicts original CSV: ${claim.row.join(" | ")}`)
      .toBe(summary.highAmountCount);
  const wholeFileHitClaims = wholeFileHighCountNarrativeClaims(record.report, policyReply);
  for (const claim of wholeFileHitClaims)
    expect(claim.count, `whole-file hit count contradicts original CSV: ${claim.claim}`).toBe(summary.highAmountCount);
  expect(wholeFileTableClaims.length > 0 || wholeFileHitClaims.length > 0,
    "report must explicitly state the independently computed whole-file high-amount count").toBe(true);
  const distributionClaims = normalized.split(/\r?\n/).flatMap((line) => {
    const highAt = line.search(/高金额|高额/);
    if (highAt < 0) return [];
    return [...line.slice(highAt).matchAll(
      /(?:华东|East)\s*(\d+)\s*(?:条|行|位)[^\n。]{0,36}(?:华西|West)\s*(\d+)\s*(?:条|行|位)/g,
    )];
  });
  for (const claim of distributionClaims) {
    expect(Number(claim[1]), `East high-amount claim contradicts source CSV: ${claim[0]}`)
      .toBe(summary.highAmountByRegion.East);
    expect(Number(claim[2]), `West high-amount claim contradicts source CSV: ${claim[0]}`)
      .toBe(summary.highAmountByRegion.West);
  }
  const globalCountPatterns = [
    /(?:全表|整体|总体|总计|总共|共计|符合记录|高金额记录|高额记录)\s*(?:识别出|共|为|有|计)?\s*(\d+)\s*(?:条|行|位)/g,
    /(?:高金额|高额)(?:客户|记录)?[^\n。]{0,30}?(?:共|合计|总计|总数为|计)\s*(\d+)\s*(?:条|行|位)/g,
    /中的\s*(\d+)\s*(?:条|行|位)/g,
    /识别出\s*(\d+)\s*(?:条|行|位)高金额记录/g,
  ];
  for (const line of highLines) {
    const relevant = line.slice(line.search(/高金额|高额/));
    for (const pattern of globalCountPatterns) {
      for (const claim of relevant.matchAll(pattern))
        expect(Number(claim[1]), `global high-amount count contradicts source CSV: ${line}`)
          .toBe(summary.highAmountCount);
    }
    if (summary.highAmountByRegion.East > 0 && summary.highAmountByRegion.West > 0)
      expect(relevant, "high-amount records cannot all originate from one region").not.toMatch(/全部来自(?:华东|华西|East|West)/);
  }
  for (const line of plain.split(/\r?\n/)) {
    for (const claim of line.matchAll(/(?:数据行数|总行数|文件行数|共)\s*[:：]?\s*(\d+)\s*行[^\n。]{0,35}含\s*(\d+)\s*行表头[^\n。]{0,35}数据行\s*(\d+)\s*行/g)) {
      expect(Number(claim[1]), `physical-line statement contradicts its own header/data counts: ${line}`)
        .toBe(Number(claim[2]) + Number(claim[3]));
      expect(Number(claim[3]), `data-row statement contradicts original CSV: ${line}`)
        .toBe(summary.rowCount);
    }
    for (const claim of line.matchAll(/(East|West|华东|华西)[^\n。]{0,65}?步长\s*(\d+)/g)) {
      const region = claim[1] === "East" || claim[1] === "华东" ? "East" : "West";
      expect(Number(claim[2]), `regional sequence step contradicts source CSV: ${line}`)
        .toBe(summary.regionalStep[region]);
    }
    for (const claim of line.matchAll(/(?:最高(?:的)?(?:三|3)条金额|金额最高(?:的)?(?:三|3)条)[^。；\n]{0,120}/g)) {
      const stated = [...claim[0].matchAll(/(?<![A-Za-z0-9])\d{4,}(?![A-Za-z0-9])/g)]
        .map((match) => Number(match[0])).slice(0, 3);
      if (stated.length === 3)
        expect(stated, `top-three statement contradicts source CSV: ${claim[0]}`)
          .toEqual(summary.highestRows.map((row) => row.amount));
    }
    for (const sentence of line.split(/[。；]/)) {
      if (!/(?:高金额|高额)(?:金额)?合计/.test(sentence) ||
          !/(?:华西|West)/.test(sentence) || !/(?:华东|East)/.test(sentence)) continue;
      const statedDifference = sentence.match(/(?:比|高于)[^。；]{0,45}?多\s*(\d+)\s*(?:CNY|元)/i);
      if (statedDifference)
        expect(Number(statedDifference[1]), `high-amount regional difference contradicts source CSV: ${sentence}`)
          .toBe(Math.abs(summary.highAmountTotalsByRegion.West - summary.highAmountTotalsByRegion.East));
    }
    if (/高金额|高额/.test(line) && /差异全部来自[^。]{0,100}(?:最高|最大)一条/.test(line)) {
      const actualDifference = Math.abs(summary.highAmountTotalsByRegion.West - summary.highAmountTotalsByRegion.East);
      const topPairDifference = Math.abs(
        Math.max(...summary.highAmountRows.filter((row) => row.region === "West").map((row) => row.amount)) -
        Math.max(...summary.highAmountRows.filter((row) => row.region === "East").map((row) => row.amount)));
      expect(actualDifference, `highest-pair attribution contradicts source CSV: ${line}`)
        .toBe(topPairDifference);
    }
  }
  expect(normalized).toMatch(/(source|来源)/i);
}

const preparationPrompt = [
  "Prepare an upstream handoff for a bounded customer review using only the uploaded customers.csv and the configured reporting policy MCP Tool. Read the full original with production read_file and call the real MCP reporting policy Tool. Derive all facts yourself from those replies; no result is supplied in this request. Do not load a Skill, perform searches, run code, or produce the final report in this preparation Run.",
  "First author and save /mnt/user-data/outputs/customer-analysis-framework.md with production write_file. Use a complete Analysis Framework: Research Overview; Framework Selection with complementary, data-feasible frameworks; Chapter Skeleton with Analysis Objective, Analysis Logic, Core Hypothesis, Data Requirements including P0/P1/P2 priority, source/search keywords/time range, and Visualization & Content Plan; and Data Collection Task List. Limit body chapters to data range, regional aggregation and policy threshold screening. Explicitly plan the final report sequence Abstract, Introduction (background, objectives and methodology), these body chapters, Conclusion, References. Use comparison tables because chart generation is unavailable. Scope hypotheses to the fields actually present; mark time, causality and customer psychology unavailable.",
  "After that Framework is committed, author /mnt/user-data/outputs/customer-data-package.json with production write_file. Include source_file_sha256, policy_id, threshold_cny, comparison, data_row_count, amount_total_cny, regions keyed by source region with count, amount_total_cny, high_amount_count and high_amount_total_cny, and high_amount_customers entries with customer_id, region and amount_cny. The frozen Phase 2 handoff also needs data_summary organized into chapters: data_range contains source_file_sha256, data_row_count and amount_total_cny; regional_aggregation copies the regions map; policy_threshold_screening contains policy_id, threshold_cny, comparison and high_amount_customers. Copy the verified facts into their appropriate chapters. For calculation transparency add calc_audit.regions keyed by region. Each region has source_rows listing every original customer_id and amount_cny in CSV order, subtotal_expression joining those source amounts with + and ending = independently summed subtotal, subtotal_cny, high_amount_expression summing only original amounts meeting the MCP threshold, high_amount_count and high_amount_total_cny. calc_audit.whole_file separately has subtotal_expression over ALL original rows in CSV order, subtotal_cny, data_row_count, high_amount_expression over every qualifying original row, high_amount_count and high_amount_total_cny. Compute each region by adding its actual rows individually; compute the whole-file total independently from all rows, then verify the region subtotals sum to that same total. Apply the same reconciliation to counts and high-amount totals. Before submitting write_file, actually re-add the listed amounts and correct any internal inconsistency; never guess a region total from proportions or copy a subtotal without checking its source rows. chart_files and external_search_findings are empty arrays because neither capability was used; missing_data is an explicit array of unavailable fields, with missing P0 data flagged. Preserve the file and actual MCP fact as sources. Check every number and policy claim before saving. Stop after these two files; do not write customer-summary.md. Keep the handoff compact and complete.",
].join("\n\n");

const reportPrompt = [
  "Produce a bounded Chinese Markdown customer review from the three uploaded originals: customers.csv, customer-analysis-framework.md and customer-data-package.json. Read all three completely using production read_file, preferably in one Tool batch, and call the configured real reporting policy MCP Tool in this report Run. Cross-check the upstream Data Summary against the original CSV and this Run's MCP reply. Do not regenerate the upstream Framework or Data Package.",
  "If consulting-analysis is available, load it and perform its Phase 2 validation, report workflow and final checklist using the provided Framework and Data Package. If it is unavailable, complete the same customer review business objective using your best judgment. No chart or external search capability is available; use comparison tables and explain unavailable inputs. Do not invent numerical, trend, causal or customer-psychology findings. Mark missing P0 data.",
  "Write exactly one model-authored report with production write_file at /mnt/user-data/outputs/customer-summary.md. Include 数据范围 and 按地区汇总, data-row count, overall amount, per-region counts/totals, policy threshold and whole-file/per-region high-amount counts, and an itemized table of every qualifying customer with customer_id, region and amount. Distinguish CSV physical lines including header from data rows. Derive every fact from the supplied originals and actual MCP reply. Cite the Data Package filename and its full read_file SHA, as well as the original CSV and actual reporting policy source. Cross-check every numeric, policy ID, highest-value and difference claim before writing; refrain from unverified trend, sequence or causal statements. Stop after the approved report is saved.",
].join("\n\n");

const reportExecutionNote = [
  "Execution note shared by both Skill settings: in your FIRST Tool-call batch, read customers.csv completely (physical lines 1–21), customer-analysis-framework.md in four safe windows (1–23, 24–46, 47–66, 67–71), and customer-data-package.json in two safe windows (1–85, 86–136), using their actual uploaded paths. In that same batch call the configured real reporting policy MCP Tool; if consulting-analysis is available, load it in that batch too. Consume every result and approved pending Tool in this Run before proceeding. These ranges are operational reading boundaries, not supplied business facts. Independently check the original CSV, Framework, Package and this Run's MCP reply; do not duplicate reads whose complete original bytes have already been obtained.",
  "Source discipline: distinguish each complete original file's SHA-256 from source_file_sha256 fields inside its objects. Name the object and source when comparing fields: the Data Package's source_file_sha256 is distinct from a same-named field in an MCP policy reply. Cite the complete Data Package file SHA separately. Never substitute an MCP field for a Data Package field. When actual publication dates or authors are unavailable, state n.d. / 不可得 rather than inventing them. Do not claim odd/even or physical-line patterns unless you have checked the actual original rows individually. Preserve the business objective and factual checks above; produce only the final approved report.",
].join("\n\n");
const effectiveReportPrompt = [reportPrompt, reportExecutionNote].join("\n\n");

test("V2-M2-15 W16 real model A/B report artifact and disabled Skill control", async ({ context, page }, testInfo) => {
  test.skip(process.env.W16_LIVE_MODEL !== "1", "Real-model L3 evidence is run separately with -LiveModel");
  test.setTimeout(900_000);
  const modelsResponse = await context.request.get(`${app}/api/models`);
  expect(modelsResponse.status(), await modelsResponse.text()).toBe(200);
  expect((await modelsResponse.json()).models.map((model) => model.name)).toContain("w16-deepseek-live");
  const initialSkill = await context.request.get(`${app}/api/skills/${skill}`);
  expect(initialSkill.status(), await initialSkill.text()).toBe(200);
  const initialDetail = await initialSkill.json();
  expect(initialDetail).toMatchObject({ name: skill, enabled: true, source: "bytedance/deer-flow",
    source_sha: sourceIndex.frozenUpstreamCommit, sha256: frozenSkillSha256,
    version: `sha256:${frozenSkillSha256}` });
  const summary = independentCsvSummary();
  expect(summary).toMatchObject({ rowCount: 20, regionalTotals: { East: 10000, West: 11000 }, total: 21000,
    highAmountCount: 6, c017: { customerId: "C017", region: "East", amount: 1700 } });
  let preparation;
  let enabled;
  let disabled;
  try {
    const disable = await context.request.put(`${app}/api/skills/${skill}`, { data: { enabled: false } });
    expect(disable.status(), await disable.text()).toBe(200);
    expect((await disable.json()).enabled).toBe(false);
    preparation = await runLiveReport(context, page, testInfo, "prepare", preparationPrompt);
    expect(toolReply(preparation.state, "skill_load")?.content ?? "").not.toContain("Activated Skill");
    expect(toolCalls(preparation.state).filter((call) => call.name === "write_file")
      .map((call) => call.args?.path)).toEqual([frameworkOutput, dataPackageOutput]);
    const upstreamPolicy = reportingPolicy(preparation.state);
    for (const output of [frameworkOutput, dataPackageOutput])
      assertCommittedWrite(preparation.state, preparation.run, output, preparation.downloads[output]);
    verifyFramework(preparation.downloads[frameworkOutput].content);
    verifyDataPackage(preparation.downloads[dataPackageOutput].content, summary, upstreamPolicy);
    // Upload precisely the downloaded Artifact bytes, not reconstructed JSON or
    // model-generated copies. Both report Threads receive the very same files.
    const files = [
      { name: "customers.csv", mimeType: "text/csv", buffer: fs.readFileSync(fixture) },
      ...[frameworkOutput, dataPackageOutput].map((output) => ({
        name: path.basename(output), mimeType: output.endsWith(".json") ? "application/json" : "text/markdown",
        buffer: fs.readFileSync(preparation.downloads[output].localPath),
      })),
    ];
    const sourceChain = [frameworkOutput, dataPackageOutput].map((output) => ({
      upstreamRun: preparation.run.runId, upstreamThread: preparation.run.threadId,
      ...preparation.downloads[output],
    }));
    await save(testInfo, "w16-preparation-frozen-handoff", { sourceChain,
      inputFiles: files.map((file) => ({ name: file.name, byteLength: file.buffer.length, sha256: sha256(file.buffer) })) });
    const enable = await context.request.put(`${app}/api/skills/${skill}`, { data: { enabled: true } });
    expect(enable.status(), await enable.text()).toBe(200);
    expect((await enable.json()).enabled).toBe(true);
    enabled = await runLiveReport(context, page, testInfo, "enabled", effectiveReportPrompt, files);
    const update = await context.request.put(`${app}/api/skills/${skill}`, { data: { enabled: false } });
    expect(update.status(), await update.text()).toBe(200);
    expect((await update.json()).enabled).toBe(false);
    disabled = await runLiveReport(context, page, testInfo, "disabled", effectiveReportPrompt, files);
    expect(enabled.promptSha256).toBe(disabled.promptSha256);
    expect(enabled.run.inputFiles).toEqual(disabled.run.inputFiles);
    for (const record of [enabled, disabled]) {
      for (const upstream of sourceChain) {
        const uploaded = record.uploadProof.find((entry) => entry.name === path.basename(upstream.path));
        expect(uploaded.sha256).toBe(upstream.sha256);
        expect(uploaded.downloadedSha256).toBe(upstream.sha256);
        expect(uploaded.downloadedByteLength).toBe(upstream.byteLength);
      }
    }
    await save(testInfo, "w16-upstream-to-ab-upload-chain", { sourceChain,
      enabled: enabled.uploadProof, disabled: disabled.uploadProof,
      commonPromptSha256: enabled.promptSha256, model: enabled.model });
  } finally {
    const restore = await context.request.put(`${app}/api/skills/${skill}`, { data: { enabled: true } });
    expect(restore.status(), await restore.text()).toBe(200);
  }
  await save(testInfo, "w16-live-ab-assessment-input", { initialDetail, summary,
    preparation: { run: preparation.run, usage: preparation.terminal,
      intermediateSha256: Object.fromEntries([frameworkOutput, dataPackageOutput]
        .map((output) => [output, preparation.downloads[output].sha256])) },
    enabled: { run: enabled.run, reportSha256: enabled.reportSha256,
      calls: toolCalls(enabled.state).map((call) => ({ name: call.name, path: call.args?.path })),
      usage: enabled.terminal },
    disabled: { run: disabled.run, reportSha256: disabled.reportSha256,
      calls: toolCalls(disabled.state).map((call) => ({ name: call.name, path: call.args?.path })),
      usage: disabled.terminal },
  });
  const enabledCalls = toolCalls(enabled.state).map((call) => call.name);
  expect(enabledCalls).toContain("skill_load");
  expect(enabledCalls).toContain("read_file");
  expect(enabledCalls.some((name) => name.endsWith("_get_reporting_policy"))).toBe(true);
  expect(enabledCalls.filter((name) => name === "write_file")).toHaveLength(1);
  expect(initialDetail.sha256).toMatch(/^[a-f0-9]{64}$/i);
  const skillReply = toolReply(enabled.state, "skill_load");
  expect(skillReply?.content).toContain("Activated Skill consulting-analysis");
  expect(skillReply?.additional_kwargs?.haifa_tool_result).toMatchObject({
    skill, activated: true, readableResources: [],
  });
  expect(skillReply?.additional_kwargs?.haifa_tool_result?.digest).toMatch(/^sha256:[a-f0-9]{64}$/);
  expect(toolReply(disabled.state, "skill_load")?.content ?? "").not.toContain("Activated Skill consulting-analysis");
  const csvProof = enabled.readProof.find((entry) => entry.name === "customers.csv");
  const fileReply = csvProof.reads[0].result;
  expect(fileReply).toMatchObject({ sha256: summary.fixtureSha256, truncated: false, totalLines: 21 });
  const policyReply = reportingPolicy(enabled.state);
  expect(policyReply).toMatchObject({ policy_id: "v2-pilot-reporting-001", high_amount_threshold_cny: 1500,
    comparison: ">=", server_id: "v2-reporting" });
  const writeCall = toolCalls(enabled.state).find((call) =>
    call.name === "write_file" && call.args?.path === reportOutput);
  const writeReply = replyForCall(enabled.state, writeCall)?.additional_kwargs?.haifa_tool_result;
  expect(writeReply).toMatchObject({ artifactExecutionRef: writeCall.id, runId: enabled.run.runId,
    path: enabled.artifactPath, sha256: enabled.reportSha256, artifactByteCount: enabled.reportByteLength });
  expect(writeReply.artifactId).toBeTruthy();
  expect(enabled.checkedWrites.map((item) => item.path)).toEqual([reportOutput]);
  expect(toolCalls(enabled.state).filter((call) => call.name === "write_file").map((call) => call.args?.path))
    .toEqual([reportOutput]);
  assertCommittedWrite(enabled.state, enabled.run, reportOutput, enabled.downloads[reportOutput]);
  expect(enabled.report).toContain(path.basename(dataPackageOutput));
  expect(enabled.report.toLowerCase()).toContain(preparation.downloads[dataPackageOutput].sha256);
  expect(enabled.downloadStatus).toBe(200);
  expect(enabled.reportByteLength).toBeGreaterThan(300);
  verifyReportFacts(enabled, summary, policyReply);
  expect(enabled.sourceDisciplineIssues, "enabled report source assertions must match original evidence").toEqual([]);
  verifyReportFacts(disabled, summary, policyReply);
  expect(disabled.sourceDisciplineIssues, "disabled report source assertions must match original evidence").toEqual([]);
  const enabledBehavior = reportBehavior(enabled.report);
  const disabledCalls = toolCalls(disabled.state).map((call) => call.name);
  expect(disabledCalls.filter((name) => name === "write_file"),
    "disabled control must deliver one business report").toHaveLength(1);
  expect(disabled.reportSha256, "disabled control must produce a downloadable report")
    .toMatch(/^[a-f0-9]{64}$/);
  assertCommittedWrite(disabled.state, disabled.run, reportOutput, disabled.downloads[reportOutput]);
  expect(disabled.readProof.find((entry) => entry.name === "customers.csv").reads[0].result)
    .toMatchObject({ sha256: summary.fixtureSha256, truncated: false });
  expect(reportingPolicy(disabled.state))
    .toMatchObject({ policy_id: policyReply.policy_id, high_amount_threshold_cny: policyReply.high_amount_threshold_cny });
  const disabledBehavior = reportBehavior(disabled.report);
  await save(testInfo, "w16-live-ab-behavior-contrast", { enabled: enabledBehavior, disabled: disabledBehavior });
  expect(enabledBehavior.headings.some((heading) => /引言|introduction/i.test(heading)),
    "Introduction is independently mandatory under frozen Phase 2").toBe(true);
  expect(enabledBehavior.skillSectionOrder, "enabled report must follow frozen Skill Phase 2 section order").toBe(true);
  expect(enabledBehavior.numberedSourceCitations, "enabled report must cite original file and MCP source").toBe(true);
  expect(disabledBehavior.skillSectionOrder && disabledBehavior.numberedSourceCitations,
    "disabled control must differ on frozen Skill report structure and sources").toBe(false);
  expect(disabled.terminal.status).toBe("success");
  expect(disabled.reportSha256).not.toBe(enabled.reportSha256);
});

test("W16 independent disabled control from frozen handoff", async ({ context, page }, testInfo) => {
  test.skip(process.env.W16_LIVE_MODEL !== "1" || !process.env.W16_FROZEN_HANDOFF_MANIFEST,
    "Independent B evidence requires an already verified real preparation handoff");
  test.setTimeout(600_000);
  const manifest = JSON.parse(fs.readFileSync(process.env.W16_FROZEN_HANDOFF_MANIFEST, "utf8"));
  const originalA = JSON.parse(fs.readFileSync(manifest.enabledRawPath, "utf8"));
  expect(manifest.model).toBe(originalA.model);
  expect(manifest.model).toBe(process.env.W16_LIVE_PROVIDER_MODEL);
  expect(manifest.reportPromptSha256).toBe(originalA.businessPromptSha256 ?? originalA.promptSha256);
  expect(sha256(Buffer.from(reportPrompt))).toBe(manifest.reportPromptSha256);
  expect(reportPrompt).toBe(originalA.businessPrompt ?? originalA.prompt);
  if (manifest.effectivePromptSha256) {
    expect(sha256(Buffer.from(effectiveReportPrompt))).toBe(manifest.effectivePromptSha256);
    expect(effectiveReportPrompt).toBe(originalA.prompt);
    expect(Number(process.env.W16_LIVE_MAX_OUTPUT_TOKENS)).toBe(originalA.liveMaxOutputTokens);
  }
  const frozen = JSON.parse(fs.readFileSync(manifest.handoffPath, "utf8"));
  const files = [{ name: "customers.csv", mimeType: "text/csv", buffer: fs.readFileSync(fixture) }];
  for (const output of [frameworkOutput, dataPackageOutput]) {
    const upstream = frozen.sourceChain.find((entry) => entry.path === output);
    expect(upstream?.artifactId, "upstream must be an actual committed Artifact").toBeTruthy();
    expect(upstream.toolCallId).toBeTruthy();
    const bytes = fs.readFileSync(upstream.localPath);
    expect(sha256(bytes)).toBe(upstream.sha256);
    expect(bytes.length).toBe(upstream.byteLength);
    files.push({ name: path.basename(output), buffer: bytes,
      mimeType: output.endsWith(".json") ? "application/json" : "text/markdown" });
  }
  expect(files.map((file) => ({ name: file.name, byteLength: file.buffer.length, sha256: sha256(file.buffer) })))
    .toEqual(originalA.run.inputFiles);
  let disabled;
  try {
    const update = await context.request.put(`${app}/api/skills/${skill}`, { data: { enabled: false } });
    expect(update.status(), await update.text()).toBe(200);
    expect((await update.json()).enabled).toBe(false);
    disabled = await runLiveReport(context, page, testInfo, "disabled-independent", effectiveReportPrompt, files);
  } finally {
    const restore = await context.request.put(`${app}/api/skills/${skill}`, { data: { enabled: true } });
    expect(restore.status(), await restore.text()).toBe(200);
  }
  expect(disabled.businessPromptSha256).toBe(manifest.reportPromptSha256);
  expect(disabled.effectivePromptSha256).toBe(sha256(Buffer.from(effectiveReportPrompt)));
  expect(disabled.model).toBe(originalA.model);
  expect(disabled.run.inputFiles).toEqual(originalA.run.inputFiles);
  expect(toolReply(disabled.state, "skill_load")?.content ?? "").not.toContain("Activated Skill consulting-analysis");
  const policy = reportingPolicy(disabled.state);
  verifyReportFacts(disabled, independentCsvSummary(), policy);
  expect(disabled.sourceDisciplineIssues, "disabled report source assertions must match original evidence").toEqual([]);
  assertCommittedWrite(disabled.state, disabled.run, reportOutput, disabled.downloads[reportOutput]);
  expect(toolCalls(disabled.state).filter((call) => call.name === "write_file")).toHaveLength(1);
  await save(testInfo, "w16-independent-b-component-assessment", { manifest,
    sourceChain: frozen.sourceChain, disabledRun: disabled.run, promptSha256: disabled.promptSha256,
    uploadProof: disabled.uploadProof, completeOriginalReads: disabled.readProof,
    terminal: disabled.terminal, reportBehavior: reportBehavior(disabled.report), reportSha256: disabled.reportSha256,
    priorAResult: "FAIL: MODEL_RESPONSE_INVALID / PARTIAL_RESPONSE; no report Artifact", aggregateM2Result: "FAIL" });
});

test("W16 independent enabled control from frozen handoff", async ({ context, page }, testInfo) => {
  test.skip(process.env.W16_LIVE_MODEL !== "1" || !process.env.W16_FROZEN_HANDOFF_MANIFEST,
    "Independent A diagnosis requires an already verified real preparation handoff");
  test.setTimeout(600_000);
  const manifest = JSON.parse(fs.readFileSync(process.env.W16_FROZEN_HANDOFF_MANIFEST, "utf8"));
  const originalA = JSON.parse(fs.readFileSync(manifest.enabledRawPath, "utf8"));
  expect(manifest.model).toBe(originalA.model);
  expect(manifest.model).toBe(process.env.W16_LIVE_PROVIDER_MODEL);
  expect(manifest.reportPromptSha256).toBe(originalA.businessPromptSha256 ?? originalA.promptSha256);
  expect(sha256(Buffer.from(reportPrompt))).toBe(manifest.reportPromptSha256);
  expect(reportPrompt).toBe(originalA.businessPrompt ?? originalA.prompt);
  if (manifest.effectivePromptSha256) {
    expect(sha256(Buffer.from(effectiveReportPrompt))).toBe(manifest.effectivePromptSha256);
    expect(effectiveReportPrompt).toBe(originalA.prompt);
    expect(Number(process.env.W16_LIVE_MAX_OUTPUT_TOKENS)).toBe(originalA.liveMaxOutputTokens);
  }
  const frozen = JSON.parse(fs.readFileSync(manifest.handoffPath, "utf8"));
  const files = [{ name: "customers.csv", mimeType: "text/csv", buffer: fs.readFileSync(fixture) }];
  for (const output of [frameworkOutput, dataPackageOutput]) {
    const upstream = frozen.sourceChain.find((entry) => entry.path === output);
    expect(upstream?.artifactId).toBeTruthy();
    expect(upstream.toolCallId).toBeTruthy();
    const bytes = fs.readFileSync(upstream.localPath);
    expect(sha256(bytes)).toBe(upstream.sha256);
    expect(bytes.length).toBe(upstream.byteLength);
    files.push({ name: path.basename(output), buffer: bytes,
      mimeType: output.endsWith(".json") ? "application/json" : "text/markdown" });
  }
  expect(files.map((file) => ({ name: file.name, byteLength: file.buffer.length, sha256: sha256(file.buffer) })))
    .toEqual(originalA.run.inputFiles);
  const update = await context.request.put(`${app}/api/skills/${skill}`, { data: { enabled: true } });
  expect(update.status(), await update.text()).toBe(200);
  const detail = await update.json();
  expect(detail).toMatchObject({ enabled: true, sha256: frozenSkillSha256 });
  const enabled = await runLiveReport(context, page, testInfo, "enabled-independent", effectiveReportPrompt, files);
  const behavior = reportBehavior(enabled.report);
  await save(testInfo, "w16-independent-a-component-assessment-input", {
    manifest, sourceChain: frozen.sourceChain, run: enabled.run,
    completeOriginalReads: enabled.readProof, uploadProof: enabled.uploadProof,
    terminal: enabled.terminal, behavior, reportSha256: enabled.reportSha256,
    businessPromptSha256: enabled.businessPromptSha256, effectivePromptSha256: enabled.effectivePromptSha256,
    liveMaxOutputTokens: enabled.liveMaxOutputTokens, sourceDisciplineIssues: enabled.sourceDisciplineIssues,
    priorAResult: "FAIL: MODEL_RESPONSE_INVALID / PARTIAL_RESPONSE", formalRound: false,
  });
  const skillReply = toolReply(enabled.state, "skill_load");
  expect(skillReply?.content).toContain("Activated Skill consulting-analysis");
  expect(skillReply?.additional_kwargs?.haifa_tool_result).toMatchObject({
    skill, activated: true, readableResources: [],
  });
  expect(skillReply?.additional_kwargs?.haifa_tool_result?.digest).toMatch(/^sha256:[a-f0-9]{64}$/);
  verifyReportFacts(enabled, independentCsvSummary(), reportingPolicy(enabled.state));
  expect(enabled.sourceDisciplineIssues, "enabled report source assertions must match original evidence").toEqual([]);
  assertCommittedWrite(enabled.state, enabled.run, reportOutput, enabled.downloads[reportOutput]);
  expect(enabled.checkedWrites.map((item) => item.path)).toEqual([reportOutput]);
  expect(toolCalls(enabled.state).filter((call) => call.name === "write_file")).toHaveLength(1);
  expect(enabled.report).toContain(path.basename(dataPackageOutput));
  expect(enabled.report.toLowerCase()).toContain(sha256(files[2].buffer));
  expect(behavior.headings.some((heading) => /引言|introduction/i.test(heading)),
    "Introduction is independently mandatory under frozen Phase 2").toBe(true);
  expect(behavior.skillSectionOrder, "enabled report must follow frozen Skill Phase 2 section order").toBe(true);
  expect(behavior.numberedSourceCitations, "enabled report must cite original file and MCP source").toBe(true);
  expect(enabled.terminal.status).toBe("success");
});
