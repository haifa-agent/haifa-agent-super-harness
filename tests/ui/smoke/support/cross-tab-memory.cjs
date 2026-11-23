const assert = require("node:assert/strict");
const { setTimeout: pause } = require("node:timers/promises");

// The caller supplies two independently authenticated accounts, each with one
// saved fact. All three tabs below share the first account's browser context.
async function verifyCrossTabMemory({ alice, bob, a, b, signInAsB, openMemory, expect, record }) {
  const retained = alice.page;
  const delayed = await alice.context.newPage();
  const switching = await alice.context.newPage();
  const fact = (tab, item) => tab.getByText(item.content, { exact: true });
  await expect(fact(retained, a)).toBeVisible();
  let capture, release, finish;
  const captured = new Promise(resolve => { capture = resolve; });
  const releasing = new Promise(resolve => { release = resolve; });
  const finished = new Promise(resolve => { finish = resolve; });
  let held = false;
  await delayed.route("**/api/memory", async route => {
    if (held || route.request().method() !== "GET") return route.continue();
    held = true;
    try {
      const response = await route.fetch();
      const memory = await response.json();
      record("held-A-memory.json", { status: response.status(), memory });
      capture(response.ok() && memory.facts.some(f => f.id === a.id) && !memory.facts.some(f => f.id === b.id));
      await releasing;
      await route.fulfill({ response });
      finish(true);
    } catch { finish(false); }
  });
  try {
    await openMemory(delayed);
    assert.equal(await Promise.race([captured, pause(10000, false)]), true, "capture authenticated A response");
    await openMemory(switching);
    await expect(fact(switching, a)).toBeVisible();
    for (const tab of [retained, delayed]) {
      await tab.evaluate(marker => {
        const inspect = () => {
          if (localStorage.getItem("w13-test-watch-B") && document.body?.innerText.includes(marker)) {
            window.w13ObservedA = true;
          }
        };
        const watch = new MutationObserver(inspect);
        watch.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });
        window.w13Inspect = inspect;
      }, a.content);
    }
    await signInAsB(switching);
    const who = await alice.context.request.get("/api/v1/auth/me");
    const current = await who.json();
    assert.equal(current.id, bob.account.id);
    const memory = await (await alice.context.request.get("/api/memory")).json();
    assert.deepEqual(memory.facts.map(f => f.id), [b.id]);
    for (const tab of [retained, delayed]) {
      await tab.bringToFront();
      await tab.evaluate(() => window.w13Inspect());
      await expect(fact(tab, a)).toBeHidden();
      await expect(fact(tab, b)).toBeVisible({ timeout: 10000 });
    }
    const received = delayed.waitForResponse(r => r.request().method() === "GET" && new URL(r.url()).pathname === "/api/memory",
      { timeout: 10000 });
    release();
    assert.equal(await Promise.race([finished, pause(10000, false)]), true, "deliver old A response after B rendered");
    const late = await (await received).json();
    assert.ok(late.facts.some(f => f.id === a.id), "browser received A payload");
    for (const tab of [retained, delayed]) {
      await tab.bringToFront();
      await tab.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await expect(fact(tab, b)).toBeVisible();
      await expect(fact(tab, a)).toBeHidden();
      assert.equal(await tab.evaluate(() => { window.w13Inspect(); return Boolean(window.w13ObservedA); }), false);
    }
    record("cross-tab-privacy.json", { accountA: alice.account.id, accountB: current.id, factA: a.id,
      factB: b.id, bApiIds: memory.facts.map(f => f.id), retainedShowsB: true, oldAReceivedAfterB: true,
      delayedShowsB: true, observedAAfterB: false });
  } finally { release(); }
}
module.exports = { verifyCrossTabMemory };
