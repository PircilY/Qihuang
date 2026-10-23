#!/usr/bin/env python3
"""Run enabled generation experiment and export all results using only stdlib."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import urllib.request


def load_questions(path):
    if path.name == "holdout-cases.json" or "holdout" in path.name.lower():
        raise ValueError("Frozen evaluation files are outside this tool's scope")
    text = path.read_text(encoding="utf-8-sig")
    values = json.loads(text) if path.suffix.lower() == ".json" else [json.loads(line) for line in text.splitlines() if line.strip()]
    return [{"id": row.get("id", str(i + 1)), "question": row["question"]}
            for i, row in enumerate(values) if not row.get("shouldRefuse", False)]


def export(result, output, report):
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps({k: v for k, v in result.items() if k != "rows"}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output / "results.jsonl").open("w", encoding="utf-8", newline="") as stream:
        for row in result["rows"]:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    fields = list(result["rows"][0]) if result["rows"] else []
    with (output / "results.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in result["rows"]:
            values = {k: json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v for k, v in row.items()}
            # Prevent CSV formula execution when opening untrusted model text in a spreadsheet.
            writer.writerow({k: "'" + v if isinstance(v, str) and v.startswith(("=", "+", "-", "@")) else v for k, v in values.items()})
    lines = ["# 生成对照实验自动报告", "", "本报告指标是自动代理指标，不等同于人工事实核查。A 无证据模型；B V3 RAG + 同一模型；C V3 抽取式。", "", "|模式|指标|均值|95% bootstrap CI|样本数|", "|---|---|---:|---|---:|"]
    for mode, summary in result["summary"].items():
        for metric, stats in summary.items():
            lines.append(f"|{mode}|{metric}|{stats['mean']:.4f}|[{stats['lower95']:.4f}, {stats['upper95']:.4f}]|{stats['n']}|")
    lines += ["", "## 运行元数据", "", "```json", json.dumps(result["metadata"], ensure_ascii=False, indent=2), "```", "", "## 失败记录", ""]
    lines += [f"- {r['questionId']} / {r['mode']}: {r['failureReason'] or r['judgeFailureReason']}" for r in result["rows"] if r['failureReason'] or r['judgeFailureReason']]
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--questions", type=Path)
    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--temperature", type=float, default=0)
    parser.add_argument("--corpus", choices=["BASELINE", "EXPANDED"], default="BASELINE")
    parser.add_argument("--output", type=Path, default=Path("runtime/generation-experiment"))
    parser.add_argument("--report", type=Path, default=Path("runtime/generation-experiment/report.md"))
    args = parser.parse_args()
    payload = {"limit": args.limit, "seed": args.seed, "temperature": args.temperature, "corpus": args.corpus}
    if args.questions:
        payload["questions"] = load_questions(args.questions)
        payload["sourceSha256"] = hashlib.sha256(args.questions.read_bytes()).hexdigest()
    source_hash = payload.pop("sourceSha256", None)
    request = urllib.request.Request(args.base_url.rstrip("/") + "/api/v1/experiments/generation",
                                     data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=18000) as response:
        envelope = json.load(response)
    result = envelope["data"]
    if source_hash:
        result["metadata"]["questionFileSha256"] = source_hash
    export(result, args.output, args.report)
    print(f"Exported {len(result['rows'])} rows to {args.output}")


if __name__ == "__main__":
    main()
