"""把单测/编译失败详情发布成提交评论（主通道）+ check-run 注解（尽力而为）。"""
import glob
import json
import os
import urllib.request
import xml.etree.ElementTree as ET

token = os.environ["GH_TOKEN"]
repo = os.environ["GITHUB_REPOSITORY"]
sha = os.environ["GITHUB_SHA"]


def api(url, data=None):
    req = urllib.request.Request(
        url,
        data=json.dumps(data).encode() if data is not None else None,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
        },
        method="POST" if data is not None else "GET",
    )
    return urllib.request.urlopen(req)


findings = []
err_lines = []
log_tail = []
out = "/tmp/test_out.txt"
if os.path.exists(out):
    lines = open(out, encoding="utf-8", errors="replace").read().splitlines()
    err_lines = [l for l in lines if l.startswith("e: ") or " FAILED" in l or "FAILURE" in l or "Caused by" in l or "error:" in l.lower() or "RuntimeError:" in l or "CMake Error" in l]
    log_tail = lines[-150:]
else:
    findings.append("（/tmp/test_out.txt 不存在，单测步骤可能未执行到写文件）")

for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    try:
        root = ET.parse(f).getroot()
    except Exception:
        continue
    for tc in root.iter("testcase"):
        for fail in list(tc.iter("failure")) + list(tc.iter("error")):
            findings.append(
                "TEST FAILED: " + tc.get("classname", "") + "." + tc.get("name", "") +
                "\n  " + ((fail.get("message") or (fail.text or "").strip()[:300]) or "no message")
            )

print("findings:", len(findings), "err_lines:", len(err_lines))

body = "## CI 排障报告（自动化）\n\n"
if findings:
    body += "### 失败明细\n```\n" + "\n".join(findings[:60]) + "\n```\n\n"
if err_lines:
    body += "### 错误行\n```\n" + "\n".join(err_lines[:100]) + "\n```\n\n"
if log_tail:
    body += "### 日志尾部\n```\n" + "\n".join(log_tail) + "\n```\n"
if len(body) < 30:
    body += "（未捕获到明确错误，请到 run 页面查看完整日志）"

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as fh:
        fh.write(body[:60000])

ok = False
try:
    resp = api(f"https://api.github.com/repos/{repo}/commits/{sha}/comments", {"body": body[:60000]})
    print("commit comment posted:", resp.status)
    ok = True
except Exception as ex:
    print("commit comment failed:", ex)

# 注解尽力而为
try:
    with api(f"https://api.github.com/repos/{repo}/commits/{sha}/check-runs") as r:
        check_id = None
        for cr in json.load(r).get("check_runs", []):
            check_id = cr["id"]
            break
    if check_id and findings:
        anns = []
        for f in findings[:40]:
            anns.append({
                "path": "scripts/report_test_failures.py",
                "start_line": 1,
                "end_line": 1,
                "annotation_level": "failure",
                "title": "test failure",
                "message": f[:500],
            })
        api(f"https://api.github.com/repos/{repo}/check-runs/{check_id}/annotations", anns)
        print("annotations posted")
except Exception as ex:
    print("annotations failed:", ex)
