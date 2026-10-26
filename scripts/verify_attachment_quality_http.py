"""One synthetic attachment question through the real application, with cleanup.

Uses ordinary guest quota and TLS verification; never changes quotas or production
settings. Documents come only from the frozen generated synthetic corpus.
"""
import argparse
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8082")
    parser.add_argument("--directory", type=Path, default=Path("lab-chat-backend/target/attachment-quality"))
    parser.add_argument("--question-id", default="H06")
    args = parser.parse_args()
    corpus = json.loads((args.directory / "corpus.json").read_text(encoding="utf-8"))
    q = next(item for item in corpus["questions"] if item["id"] == args.question_id)
    guest, session = uuid.uuid4().hex, "quality-" + uuid.uuid4().hex
    report = {"questionId": q["id"], "checks": [], "answer": "", "events": [], "cleanupCompleted": False}
    uploaded = []

    def request(path, method="GET", body=None, headers=None, identity=guest):
        actual = {"X-Guest-Key": identity}
        actual.update(headers or {})
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
            actual["Content-Type"] = "application/json"
        return urllib.request.urlopen(urllib.request.Request(args.base_url.rstrip("/") + path,
                                      data=body, method=method, headers=actual), timeout=240)

    def check(name, condition):
        report["checks"].append({"name": name, "passed": bool(condition)})

    def denied(path, identity=guest):
        try:
            with request(path, identity=identity):
                return False
        except urllib.error.HTTPError as error:
            return error.code == 403

    created = False
    try:
        with request("/auth/quota") as response:
            quota = json.load(response)
        if quota["remaining"] != -1 and quota["remaining"] < 1:
            raise RuntimeError("Ordinary guest quota unavailable; no bypass attempted")
        for doc_id in q["docs"]:
            boundary = "quality" + uuid.uuid4().hex
            content = corpus["documents"][doc_id].encode("utf-8")
            body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="files"; filename="synthetic-'
                    + doc_id + '.txt"\r\nContent-Type: text/plain\r\n\r\n').encode("utf-8") + content + ("\r\n--" + boundary + "--\r\n").encode("utf-8")
            with request("/chat/files?sessionId=" + session, "POST", body, {"Content-Type": "multipart/form-data; boundary=" + boundary}) as response:
                item = json.load(response)[0]
            created = True
            uploaded.append(item["fileId"])
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                with request("/chat/files?sessionId=" + session) as response:
                    state = next(f for f in json.load(response) if f["fileId"] == item["fileId"])
                if state["status"] in {"READY", "PARTIAL", "FAILED"}:
                    break
                time.sleep(2)
            check(doc_id + "-ready", state["status"] == "READY")
            if state["status"] != "READY":
                raise RuntimeError("Synthetic attachment not fully ready")
        started = time.monotonic()
        with request("/chat/stream", "POST", {"sessionId": session, "userMessage": q["question"], "fileIds": uploaded, "mode": "qa"}) as response:
            event, data = "", []
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].lstrip())
                elif not line and event:
                    value = "\n".join(data)
                    report["events"].append(event)
                    if event == "token":
                        try:
                            value = json.loads(value)
                        except ValueError:
                            pass
                        report["answer"] += value
                    elif event == "attachment":
                        attachment = json.loads(value)
                        report["attachment"] = {k: attachment[k] for k in ("selectedSections", "totalSections", "fullCoverage", "warnings") if k in attachment}
                        report["sourceCount"] = len(attachment.get("sources", []))
                    event, data = "", []
        report["httpTurnElapsedMs"] = (time.monotonic() - started) * 1000
        check("stream-completed", "done" in report["events"] and not {"error", "quota"}.intersection(report["events"]))
        check("all-reference-fact-patterns", all(re.search(pattern, report["answer"]) for pattern in q["patterns"]))
        check("attachment-sources-present", report.get("sourceCount", 0) > 0)
        source_path = "/chat/files/" + uploaded[0] + "/source?" + urllib.parse.urlencode({"sessionId": session, "parentId": 0})
        check("other-owner-denied", denied(source_path, uuid.uuid4().hex))
        other_session = source_path.replace(session, "quality-" + uuid.uuid4().hex)
        check("other-session-denied", denied(other_session))
    except Exception:
        report["operationFailed"] = True
    finally:
        if created:
            try:
                with request("/session/" + session, "DELETE") as response:
                    check("session-deleted", json.load(response).get("code") == 200)
                for file_id in uploaded:
                    path = "/chat/files/" + file_id + "/source?" + urllib.parse.urlencode({"sessionId": session, "parentId": 0})
                    check("deleted-source-denied", denied(path))
                with request("/session/list") as response:
                    remaining = json.load(response)["data"]
                check("session-absent-from-list", all(item["sessionId"] != session for item in remaining))
                report["cleanupCompleted"] = all(c["passed"] for c in report["checks"] if c["name"] in {"session-deleted", "deleted-source-denied", "session-absent-from-list"})
            except Exception:
                report["cleanupFailed"] = True
                # The receipt is local, ignored, and private; it is never printed or copied into a blog.
                (args.directory / "http-cleanup-receipt.json").write_text(json.dumps({"sessionId": session, "guestKey": guest, "fileIds": uploaded}), encoding="utf-8")
        (args.directory / "live-http-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    passed = not report.get("operationFailed") and report["cleanupCompleted"] and all(c["passed"] for c in report["checks"])
    print("HTTP synthetic verification: checks=" + str(len(report["checks"])) + " passed=" + str(sum(c["passed"] for c in report["checks"]))
          + " cleanup=" + str(report["cleanupCompleted"]))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
