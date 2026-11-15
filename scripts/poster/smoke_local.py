"""Exercise one real FDE poster workflow against a separately started local instance."""

import argparse
import hashlib
import json
import re
import time
from pathlib import Path
from urllib.parse import quote

from playwright.sync_api import expect, sync_playwright

PROMPT = (
    "帮我做一张朋友圈招募海报，给两种版式让我选。标题「FDE 两天训练营」，"
    "副标题「企业 AI 项目交付实战」，活动说明「FastGPT 实战 · 售后工单助手」。"
    "演示日期是 2026 年 12 月 12 日至 13 日，二维码使用测试链接 https://example.com/fde-training。"
)
REVISION = "这张我就用刚才选的。开课改到 2026 年 12 月 19、20 日，其他照旧，给我新版 PNG。"
COPY = "把当前稿的活动说明改成「FastGPT 实战 · 从工单到试点」，日期、主视觉和二维码都保留，给我新版。"
ACTIVITY = "FDE-两天训练营"
COMPARE = f"{ACTIVITY}-A-B对比-v1.png"
OUTPUT = "/mnt/user-data/outputs/"
TERMINAL = {"success", "error", "timeout"}
EXPECTED_QR = "https://example.com/fde-training"


def write_json(directory, name, payload):
    (directory / name).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_state(request, base, thread):
    response = request.get(f"{base}/api/langgraph/threads/{thread}/state", timeout=5000)
    assert response.ok, response.status
    return response.json()["values"]


def run_calls(state, run_id):
    return [call for message in state["messages"] if message.get("run_id") == run_id
            for call in (message.get("tool_calls") or [])]


def submit(page, message):
    target = page.locator('textarea[name="message"]')
    expect(target).to_be_enabled(timeout=15000)
    with page.expect_response(lambda response: response.request.method == "POST" and re.search(
        r"/api/langgraph/threads/[^/]+/runs/stream$", response.url), timeout=30000) as observed:
        target.fill(message)
        target.press("Enter")
    return identity_of(observed.value)


def identity_of(response):
    assert response.status == 200, (response.status, response.text())
    location = response.headers.get("content-location", "")
    match = re.fullmatch(r"/api/threads/([^/]+)/runs/([^/]+)", location)
    assert match, location
    return (*match.groups(), location)


def select_artifact(page, filename):
    preview = page.locator(f'iframe[title="{filename}"]')
    if preview.is_visible():
        return
    page.get_by_test_id("artifact-trigger").first.click(timeout=15000)
    picker = page.get_by_role("combobox").filter(has_text=re.compile(r"\.png$| · v[1-9]\d*")).last
    name = poster_label_pattern(filename)
    item = page.get_by_text(name).last
    expect(picker.or_(item).or_(preview).last).to_be_visible(timeout=15000)
    if preview.is_visible():
        return
    if picker.is_visible():
        picker.click()
        page.get_by_role("option", name=name).click()
    else:
        item.click()
    expect(preview).to_be_visible(timeout=15000)


def poster_label_pattern(filename, current=False):
    match = re.fullmatch(r"(.+)-(A稿|B稿|A-B对比)-v([1-9]\d*)\.png", filename)
    assert match, filename
    stem, draft, version = match.groups()
    label = r"(A/B 对比|A/B comparison)" if draft == "A-B对比" else rf"({draft[0]} 稿|Draft {draft[0]})"
    suffix = r" · (当前版本|Current)" if current else r"( · (当前版本|Current))?"
    return re.compile(rf"^{re.escape(stem.replace('-', ' '))} · {label} · v{version}{suffix}$")


def download(page, file, destination):
    select_artifact(page, file)
    trigger = page.get_by_role("button", name=re.compile(r"^(Download|下载)$")).last
    expect(trigger).to_be_visible(timeout=15000)
    with page.expect_download(timeout=15000) as download_info:
        trigger.click()
    result = download_info.value
    assert result.suggested_filename == file, result.suggested_filename
    result.save_as(destination)


def wait_run(page, base, identity, evidence, phase, budget):
    thread, run, location = identity
    started = time.monotonic()
    status = None
    try:
        while time.monotonic() < started + budget:
            response = page.request.get(base + location, timeout=12000)
            assert response.ok, response.status
            status = response.json()
            diagnostics = status.get("diagnostics") or {}
            approvals = [entry for entry in diagnostics.get("interactions", []) if entry["kind"] == "approval"]
            assert not approvals, f"Unexpected approval in automatic mode during {phase}: {approvals}"
            if status["status"] in TERMINAL:
                break
            page.wait_for_timeout(400)
        assert status and status["status"] == "success", (phase, status, round(time.monotonic() - started, 2))
        assert "interactions" in status["diagnostics"], status
        return read_state(page.request, base, thread), round(time.monotonic() - started, 3)
    finally:
        state = read_state(page.request, base, thread)
        write_json(evidence, phase + "-run.json", {
            "identity": identity, "status": status, "state": state,
        })


def choose_b(page, base, first, state, evidence):
    assert OUTPUT + COMPARE in state["artifacts"]
    choose = page.get_by_test_id("human-input-card").get_by_role("button", name="选 B 稿", exact=True).last
    # The frozen clarification card can arrive with a terminal draft Run. Its browser
    # actions are enabled after the stream closes and the conversation reloads.
    expect(choose).to_be_enabled(timeout=20000)
    select_artifact(page, COMPARE)
    page.screenshot(path=evidence / "select-b.png")
    with page.expect_response(lambda response: response.request.method == "POST" and re.search(
        r"/api/langgraph/threads/[^/]+/runs/stream$", response.url), timeout=30000) as observed:
        choose.click()
    selected = identity_of(observed.value)
    assert selected[0] == first[0]
    state, _ = wait_run(page, base, selected, evidence, "selection", 75)
    choice = [call["args"] for call in run_calls(state, selected[1]) if call["name"] == "poster_edit"]
    assert choice == [{"select_draft": "B"}], choice
    expect(choose).to_be_disabled(timeout=15000)
    return selected


def verify_png(before, after, vertical_range):
    import cv2
    from PIL import Image, ImageChops

    with Image.open(before) as left, Image.open(after) as right:
        assert (left.format, right.format) == ("PNG", "PNG")
        assert left.size == right.size == (1080, 1440)
        bounds = ImageChops.difference(left.convert("RGB"), right.convert("RGB")).getbbox()
        assert bounds and 72 <= bounds[0] < bounds[2] <= 1008 and vertical_range[0] <= bounds[1] < bounds[3] <= vertical_range[1], bounds
    qr = cv2.QRCodeDetector()
    decoded = []
    for path in (before, after):
        png = cv2.imread(str(path))
        small = cv2.resize(png, (540, 720), interpolation=cv2.INTER_AREA)
        for image in (png, small):
            decoded.append(qr.detectAndDecode(image)[0])
    assert decoded == [EXPECTED_QR] * 4, decoded
    return {"dimensions": [1080, 1440], "different_pixels_box": bounds, "decoded_links": decoded}


def evidence_digest(request, base, thread, virtual_path):
    response = request.get(f"{base}/api/threads/{thread}/artifacts/{quote(virtual_path.lstrip('/'))}", timeout=15000)
    assert response.ok, response.status
    return hashlib.sha256(response.body()).hexdigest()


def verify_patch(state, run_id, expected):
    calls = run_calls(state, run_id)
    assert not {"baoyu_image_gen", "execution_run", "poster_render"}.intersection(call["name"] for call in calls)
    patches = [call["args"] for call in calls if call["name"] == "poster_edit"]
    assert patches == [expected], patches
    return patches[0]


def verify_current_sidebar(page, filename, previous_file, evidence):
    select_artifact(page, previous_file)
    picker = page.get_by_role("combobox").filter(has_text=poster_label_pattern(previous_file)).last
    expect(picker).not_to_have_text(poster_label_pattern(previous_file, current=True))
    picker.click()
    current = page.get_by_role("option", name=poster_label_pattern(filename, current=True))
    expect(current).to_be_visible(timeout=15000)
    if evidence:
        page.screenshot(path=evidence)
    current.click()
    expect(page.get_by_role("combobox").filter(has_text=poster_label_pattern(filename, current=True))).to_be_visible()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:26618")
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--resume-draft", type=Path, help="Continue an already published draft Run in its local instance.")
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        # Visible state screenshots are captured at the milestones. A continuous screencast
        # records the spinning wait indicator thousands of times during the real image call.
        context.tracing.start(screenshots=False, snapshots=True, sources=True)
        page = context.new_page()
        try:
            if args.resume_draft:
                saved = json.loads(args.resume_draft.read_text(encoding="utf-8"))
                first = saved["identity"]
                assert saved["status"]["status"] == "success", saved["status"]
                assert all(entry["kind"] != "approval" for entry in saved["status"]["diagnostics"]["interactions"])
                page.goto(args.base + "/workspace/agents/poster-assistant/chats/" + first[0],
                          wait_until="domcontentloaded", timeout=30000)
                state = read_state(page.request, args.base, first[0])
                first_seconds = saved["status"]["diagnostics"]["duration_ms"] / 1000
            else:
                page.goto(args.base + "/workspace/agents", wait_until="domcontentloaded", timeout=30000)
                card = page.locator('[data-slot="card"]').filter(has_text="海报助手")
                expect(card).to_be_visible(timeout=20000)
                page.screenshot(path=evidence / "gallery.png")
                card.get_by_role("button", name=re.compile(r"^(Chat|对话)$")).click()
                first = submit(page, PROMPT)
                print(f"Draft Run {first[1]}", flush=True)
                state, first_seconds = wait_run(page, args.base, first, evidence, "draft", 420)
                page.reload(wait_until="domcontentloaded", timeout=20000)
            draft_calls = run_calls(state, first[1])
            assert [call["name"] for call in draft_calls].count("baoyu_image_gen") == 1, draft_calls
            assert "execution_run" not in [call["name"] for call in draft_calls], draft_calls
            a, b = [OUTPUT + f"{ACTIVITY}-{draft}稿-v1.png" for draft in ("A", "B")]
            assert {a, b}.issubset(state["artifacts"]), state["artifacts"]
            selection = choose_b(page, args.base, first, state, evidence)
            verify_current_sidebar(page, Path(b).name, Path(a).name, evidence / "current-v1.png")
            before = evidence / "poster-b-before.png"
            download(page, Path(a).name, evidence / "poster-a.png")
            download(page, Path(b).name, before)
            assert hashlib.sha256(before.read_bytes()).hexdigest() == evidence_digest(page.request, args.base, first[0], b)

            page.reload(wait_until="domcontentloaded", timeout=20000)
            second = submit(page, REVISION)
            assert second[0] == first[0] and second[1] != first[1], (first, second)
            print(f"Date-only Run {second[1]}", flush=True)
            after_state, second_seconds = wait_run(page, args.base, second, evidence, "revision", 100)
            patch = verify_patch(after_state, second[1], {"start_date": "2026-12-19", "end_date": "2026-12-20"})
            revised = OUTPUT + f"{ACTIVITY}-B稿-v2.png"
            assert {a, b, revised}.issubset(after_state["artifacts"]), after_state["artifacts"]
            verify_current_sidebar(page, Path(revised).name, Path(b).name, evidence / "current-v2.png")
            after = evidence / "poster-b-after.png"
            download(page, Path(revised).name, after)
            page.screenshot(path=evidence / "download-revision.png")
            assert hashlib.sha256(after.read_bytes()).hexdigest() == evidence_digest(
                page.request, args.base, first[0], revised)
            png = verify_png(before, after, (1020, 1100))
            third = submit(page, COPY)
            assert third[0] == first[0]
            print(f"Copy-only Run {third[1]}", flush=True)
            copy_state, copy_seconds = wait_run(page, args.base, third, evidence, "copy", 100)
            copy_patch = verify_patch(copy_state, third[1], {"details": "FastGPT 实战 · 从工单到试点"})
            latest = OUTPUT + f"{ACTIVITY}-B稿-v3.png"
            assert {a, b, revised, latest}.issubset(copy_state["artifacts"]), copy_state["artifacts"]
            page.reload(wait_until="domcontentloaded", timeout=20000)
            verify_current_sidebar(page, Path(latest).name, Path(revised).name, evidence / "current-v3.png")
            copy_path = evidence / "poster-b-copy.png"
            download(page, Path(latest).name, copy_path)
            assert hashlib.sha256(copy_path.read_bytes()).hexdigest() == evidence_digest(
                page.request, args.base, first[0], latest)
            copy_png = verify_png(after, copy_path, (1150, 1220))
            summary = {"result": "PASS", "thread_url": page.url, "runs": [first[1], selection[1], second[1], third[1]],
                       "elapsed_seconds": [first_seconds, second_seconds, copy_seconds],
                       "approval_requests": [0, 0, 0, 0], "baoyu_calls": [1, 0, 0], "filenames": [Path(x).name for x in (a, b, revised, latest)],
                       "date_fields": patch, "date_png": png, "copy_fields": copy_patch, "copy_png": copy_png}
            write_json(evidence, "summary.json", summary)
            print(json.dumps(summary, ensure_ascii=False), flush=True)
        except Exception:
            page.screenshot(path=evidence / "first-failure.png")
            raise
        finally:
            context.tracing.stop(path=evidence / "trace.zip")
            context.close()
            browser.close()


if __name__ == "__main__":
    main()
