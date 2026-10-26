"""Reproduce the synthetic attachment RAG component experiment (no application/database startup).

Default mode replays existing model caches. --live-models explicitly enables paid
model requests using local environment variables or an existing private SSH target.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summarize(directory):
    dev = load(directory / "dev-results.json")
    holdout = load(directory / "holdout-results.json")
    parsing = load(directory / "parsing-results.json")
    generated = load(directory / "generation-results.json")
    selection = load(directory / "selection.json")
    answers = []
    for config in dict.fromkeys(row["config"] for row in generated["rows"]):
        rows = [row for row in generated["rows"] if row["config"] == config]
        positive = [row for row in rows if row["answerable"]]
        negative = [row for row in rows if not row["answerable"]]
        answers.append({"config": config, "answerable": len(positive), "unanswerable": len(negative),
                        "jsonValid": sum(row["jsonValid"] for row in rows),
                        "allFactPatterns": sum(row["allFactPatterns"] for row in positive),
                        "citationLabelsValid": sum(row["citationLabelsValid"] for row in positive),
                        "allEvidenceInCitedSources": sum(row["allEvidenceInCitedSources"] for row in positive),
                        "noAnswerCorrect": sum(row["noAnswerCorrect"] for row in negative)})
    network = {}
    for kind in ("embedding", "rerank", "generation", "ocr"):
        cache = load(directory / ("model-" + kind + ".json"))
        request_files = ["rerank-dev-jobs.json", "rerank-holdout-jobs.json"] if kind == "rerank" else [kind + "-jobs.json"]
        active_keys = {item["key"] for filename in request_files for item in load(directory / filename)["items"]}
        active = [item for item in cache["items"] if item["key"] in active_keys]
        measures = {m["callId"]: dict(m, role=item.get("role")) for item in active for m in item.get("measurements", [])}
        all_calls = {m["callId"] for item in cache["items"] for m in item.get("measurements", [])}
        times = [m["elapsedMs"] for m in measures.values()]
        tokens = {}
        for m in measures.values():
            for key, value in m["usage"].items():
                if isinstance(value, (int, float)):
                    tokens[key] = tokens.get(key, 0) + value
        network[kind] = {"model": cache["model"], "cachedItems": len(cache["items"]), "activeItems": len(active), "observedCalls": len(times),
                         "additionalSetupCalls": len(all_calls - set(measures)),
                         "recordedErrors": sum("error" in item for item in active),
                         "medianMs": statistics.median(times) if times else None,
                         "minMs": min(times) if times else None, "maxMs": max(times) if times else None,
                         "usageTotalsByProviderField": tokens}
        if kind == "embedding":
            query_times = [m["elapsedMs"] for m in measures.values() if m["role"] == "query"]
            network[kind]["queryCalls"] = len(query_times)
            network[kind]["queryMedianMs"] = statistics.median(query_times) if query_times else None
    production = ROOT / "lab-chat-backend/src/main/java/com/law/backend"
    source_files = [production / "attachment/AttachmentChunker.java", production / "attachment/AttachmentTokens.java",
                    production / "attachment/AttachmentRetrieval.java", production / "attachment/AttachmentParser.java",
                    production / "service/FileProperties.java",
                    ROOT / "lab-chat-backend/src/test/resources/golden/attachment-quality-set.json",
                    ROOT / "lab-chat-backend/src/test/java/com/law/backend/attachment/AttachmentQualityEvalTest.java",
                    ROOT / "lab-chat-backend/src/test/java/com/law/backend/attachment/AttachmentQualityMetrics.java",
                    ROOT / "scripts/attachment_quality_models.py"]
    hashes = {p.relative_to(ROOT).as_posix(): sha(p) for p in source_files}
    summary = {"datasetVersion": "synthetic-attachment-v1", "documents": 7, "questions": 24,
               "contextBudgetEstimated": 8000, "selection": selection, "dev": dev["summary"], "holdout": holdout["summary"],
               "generation": answers, "parsing": [{k: v for k, v in row.items() if k != "text"} for row in parsing["rows"]],
               "network": network, "sourceSha256": hashes,
               "limitations": ["Synthetic agent-authored labels, no independent human legal review",
                               "Real chunker/retrieval and real model caches, exhaustive in-memory cosine, not pgvector/HTTP end-to-end",
                               "Evidence span recall and regex fact matching are proxies, not semantic faithfulness",
                               "Small held-out set; candidate selection uses development questions only",
                               "Replay timings exclude network; provider usage is not an invoice or currency cost"]}
    (directory / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# 附件 RAG 合成质量评估实测", "", "7份合成资料；24题；固定8000估算token预算。调参集16题，保留集8题。", "",
             "## 调参集", "", "| 配置 | 证据句覆盖 | 全必要证据题数 | 平均上下文估算token | 子块数 | 硬切重叠对数 |", "| --- | --- | --- | --- | --- | --- |"]
    for row in dev["summary"]:
        lines.append("| {config} | {covered}/{required} | {allRequired}/{answerable} | {meanContextTokensEstimated:.0f} | {children} | {pairs} |".format(
            **row, children=row["index"]["children"], pairs=row["index"]["overlappingChildPairs"]))
    lines += ["", "候选配置（仅按调参集预定规则选择）：`" + selection["candidate"] + "`。", "", "## 保留集", "",
              "| 配置 | 证据句覆盖 | 全必要证据题数 | 平均上下文估算token |", "| --- | --- | --- | --- |"]
    for row in holdout["summary"]:
        lines.append("| {config} | {covered}/{required} | {allRequired}/{answerable} | {meanContextTokensEstimated:.0f} |".format(**row))
    lines += ["", "## 真实回答（规则代理指标）", "", "| 配置 | 全要点命中 | 引用标签有效 | 引用来源含全部证据 | 无答案正确 |", "| --- | --- | --- | --- | --- |"]
    for row in answers:
        lines.append("| {config} | {allFactPatterns}/{answerable} | {citationLabelsValid}/{answerable} | {allEvidenceInCitedSources}/{answerable} | {noAnswerCorrect}/{unanswerable} |".format(**row))
    lines += ["", "## 范围", ""] + ["- " + note for note in summary["limitations"]]
    (directory / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("EVAL report ready: " + str(directory / "report.md"))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "lab-chat-backend/target/attachment-quality")
    parser.add_argument("--live-models", action="store_true")
    parser.add_argument("--target-json", type=Path)
    parser.add_argument("--container")
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args()
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if args.report_only:
        summarize(directory)
        return 0
    maven = shutil.which("mvn")
    if not maven:
        parser.error("Maven unavailable; configure Maven and JDK17 first")

    def phase(name):
        print("EVAL phase: " + name, flush=True)
        command = [maven, "-B", "-q", "-pl", "lab-chat-backend", "-Dtest=AttachmentQualityEvalTest",
                   "-Dattachment.eval.phase=" + name, "-Dattachment.eval.dir=" + str(directory), "test"]
        with (directory / (name + ".log")).open("wb") as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError("Evaluation phase failed: " + name + "; see the ignored local phase log")

    def models(kind, jobs):
        if not args.live_models:
            if not (directory / ("model-" + kind + ".json")).is_file():
                raise RuntimeError("Real model cache missing; --live-models is required for external requests")
            return
        command = [sys.executable, str(ROOT / "scripts/attachment_quality_models.py"), "--jobs", str(directory / jobs),
                   "--output", str(directory / ("model-" + kind + ".json"))]
        if args.target_json:
            command += ["--target-json", str(args.target_json)]
        if args.container:
            command += ["--container", args.container]
        result = subprocess.run(command, cwd=ROOT)
        if result.returncode:
            raise RuntimeError("Real model stage failed: " + kind + "; cached failures retained for diagnosis")

    phase("prepare")
    models("embedding", "embedding-jobs.json")
    models("ocr", "ocr-jobs.json")
    phase("rank-dev")
    models("rerank", "rerank-dev-jobs.json")
    phase("evaluate-dev")
    phase("rank-holdout")
    models("rerank", "rerank-holdout-jobs.json")
    phase("evaluate-holdout")
    phase("parse")
    models("generation", "generation-jobs.json")
    phase("generation")
    summarize(directory)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
