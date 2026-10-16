"""把 JUnit 失败与编译错误发布成 check-run 注解与 job summary，便于无日志环境排障。"""
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


check_id = None
with api(f"https://api.github.com/repos/{repo}/commits/{sha}/check-runs") as r:
    payload = json.load(r)
    for cr in payload.get("check_runs", []):
        check_id = cr["id"]
        break
print("check_run_id =", check_id)

anns = []

# 编译错误：Kotlin 编译器输出行以 "e: " 开头，格式 e: <path>:<line>:<col> <message>
out = "/tmp/test_out.txt"
if os.path.exists(out):
    for line in open(out, encoding="utf-8", errors="replace"):
        if not line.startswith("e: "):
            continue
        rest = line.strip()[3:].replace("file://", "")
        parts = rest.split(":", 2)
        path = "scripts/report_test_failures.py"
        line_no = 1
        message = rest
        if len(parts) == 3:
            idx = parts[0].find("app/")
            if idx >= 0:
                path = parts[0][idx:]
            try:
                line_no = max(1, int(parts[1]))
            except ValueError:
                pass
            message = parts[2].strip()
        anns.append({
            "path": path,
            "start_line": line_no,
            "end_line": line_no,
            "annotation_level": "failure",
            "title": "compile error",
            "message": message[:500],
        })

# 单测失败：解析 JUnit XML
for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    try:
        root = ET.parse(f).getroot()
    except Exception:
        continue
    for tc in root.iter("testcase"):
        for fail in list(tc.iter("failure")) + list(tc.iter("error")):
            cn = tc.get("classname", "")
            name = tc.get("name", "test")
            pkg = cn[: cn.rfind(".")] if "." in cn else ""
            path = (
                "app/src/test/java/" + pkg.replace(".", "/") + "/" + cn.split(".")[-1] + ".kt"
                if pkg
                else "app/src/test/java/UnknownTest.kt"
            )
            msg = (fail.get("message") or (fail.text or "").strip()[:400]) or "no message"
            anns.append({
                "path": path,
                "start_line": 1,
                "end_line": 1,
                "annotation_level": "failure",
                "title": name + " FAILED",
                "message": msg[:500],
            })

print("findings:", len(anns))

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary and anns:
    with open(summary, "a", encoding="utf-8") as fh:
        fh.write("## Unit test / compile failures\n\n")
        for a in anns:
            fh.write("- **" + a["title"] + "** (`" + a["path"] + ":" + str(a["start_line"]) + "`)\n\n  ```\n  " + a["message"] + "\n  ```\n\n")

if check_id and anns:
    try:
        resp = api(
            f"https://api.github.com/repos/{repo}/check-runs/{check_id}/annotations",
            anns[:50],
        )
        print("annotations posted:", resp.status)
    except Exception as ex:
        print("annotation post failed:", ex)
