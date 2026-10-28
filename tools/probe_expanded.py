"""Run deterministic bilingual EXPANDED queries against local HTTP services."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
VERSION = "V3_GUARDED"
ENGINE = "llama-index"
QUESTIONS = [
    ("黄芪治疗慢性心力衰竭的临床疗效", ["黄芪", "Astragalus", "Huangqi", "Astragalus membranaceus"]),
    ("丹参对冠心病心绞痛有什么作用", ["丹参", "Danshen", "Salvia miltiorrhiza"]),
    ("中医药治疗高血压的研究进展", ["高血压", "hypertension"]),
    ("气虚血瘀证心衰的辨证论治", ["气虚血瘀", "心衰", "heart failure"]),
    ("温阳化饮养心方对心衰利尿剂抵抗的影响", ["温阳化饮养心方", "利尿剂抵抗"]),
    ("astragalus chronic heart failure efficacy", ["Astragalus", "Huangqi", "Astragalus membranaceus", "黄芪"]),
    ("Danshen coronary heart disease angina", ["Danshen", "Salvia miltiorrhiza", "丹参"]),
]


def port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def request(url: str, payload=None):
    req = urllib.request.Request(url, data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as response:
        return json.load(response)


def wait_ready(url: str, process: subprocess.Popen | None = None, timeout: int = 240):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"service exited {process.returncode}; last health={last}")
        try:
            last = request(url)
            if last.get("status") == "UP" and last.get("indexState", "READY") == "READY":
                return last
        except (OSError, urllib.error.URLError) as exc:
            last = repr(exc)
        time.sleep(.5)
    raise TimeoutError(f"service not READY after {timeout}s; last health={last}")


def scan_coverage(chunks: list[dict], terms: list[str]) -> int:
    return sum(any(term.casefold() in (str(chunk.get("title", "")) + " " + str(chunk.get("content", ""))).casefold()
                   for term in terms) for chunk in chunks)


def run_probes(chunks: list[dict], java_url: str, questions=QUESTIONS) -> list[dict]:
    rows = []
    for question, terms in questions:
        coverage = scan_coverage(chunks, terms)
        row = {"question": question, "terms": terms, "coverage": coverage, "hit": False,
               "failed": True, "citations": [], "engine": "unavailable", "version": VERSION}
        try:
            body = request(java_url + "/api/v1/rag/chat", {
                "question": question, "version": VERSION, "corpus": "EXPANDED",
            })["data"]
            if not isinstance(body, dict) or not isinstance(body.get("citations"), list):
                raise ValueError("invalid chat response: expected data.citations list")
            trace = body.get("retrieval")
            if not isinstance(trace, dict) or not isinstance(trace.get("engine"), str):
                raise ValueError("invalid chat response: missing retrieval.engine")
            if not isinstance(body.get("version"), str):
                raise ValueError("invalid chat response: missing version")
            citations = body["citations"][:3]
            if any(not isinstance(item, dict)
                   or not isinstance(item.get("documentTitle"), str)
                   or not isinstance(item.get("quote"), str) for item in citations):
                raise ValueError("invalid citation response: expected documentTitle and quote")
            hit = any(any(term.casefold() in (citation["documentTitle"] + " " + citation["quote"]).casefold()
                          for term in terms) for citation in citations)
            wrong_path = trace["engine"] != ENGINE or body["version"] != VERSION
            row.update(citations=citations, hit=hit, engine=trace["engine"], version=body["version"],
                       refused=body.get("refused"), refusalReason=body.get("refusalReason"),
                       notes=trace.get("notes", []), latencyMs=body.get("latencyMs"),
                       failed=wrong_path or bool(coverage and not hit),
                       status="执行路径不符" if wrong_path else "语料无覆盖" if coverage == 0
                       else "命中" if hit else "有覆盖但未命中")
        except (OSError, ValueError, KeyError, TypeError) as exc:
            row.update(status="请求或响应失败", error=str(exc))
        rows.append(row)
    return rows


def render_report(rows: list[dict], query_bridge: bool | None) -> str:
    bridge = str(query_bridge).lower() if query_bridge is not None else "external (not verified)"
    lines = ["# EXPANDED 真实服务端到端探测", "",
             f"SHA-256 hash 检索；{len(rows)} 个固定问题；`RAG_QUERY_BRIDGE={bridge}`。",
             "按接口 `documentTitle` / `quote` 核验前三条引用。覆盖数为全库标题/正文含任一所列语言术语的片段数。"
             "有覆盖但前三条未命中、HTTP/响应异常或实际引擎/版本不符均使探测失败；无覆盖不冒充检索退化。",
             "词面命中仅是回归代理，不证明医学相关性、疗效或回答正确性。拒答原因和检索轨迹保留在同名 JSON 报告中。",
             "", "G 之前代码对照：`delivery-c9652d3` 同环境服务对照未执行；只展示当前服务结果，不估算旧版结果。",
             "", "|问题|覆盖片段数|前三条命中|状态|引擎|版本|前三条引用标题 / 错误|",
             "|---|---:|---|---|---|---|---|"]

    def cell(value) -> str:
        return str(value).replace("|", "\\|").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>")

    for row in rows:
        titles = "<br>".join(cell(item["documentTitle"]) for item in row["citations"]) or cell(row.get("error", ""))
        lines.append(f"|{cell(row['question'])}|{row['coverage']}|{row['hit']}|{cell(row['status'])}|"
                     f"{cell(row['engine'])}|{cell(row['version'])}|{titles}|")
    failures = sum(row["failed"] for row in rows)
    lines += ["", f"验收结果：{len(rows) - failures}/{len(rows)} 项通过，{failures} 项失败。", ""]
    return "\n".join(lines)


def local_url(value: str) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in {"", "/"}):
        raise ValueError("probe service URLs must be local HTTP origins without credentials")
    return value.rstrip("/")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, default=ROOT / "docs/service-probe.md")
    parser.add_argument("--query-bridge", action="store_true",
                        help="enable the optional bilingual query bridge in owned services")
    args = parser.parse_args()
    # The CLI caller may supply running service URLs for a different process manager.
    java_url = os.environ.get("PROBE_JAVA_URL")
    python_url = os.environ.get("PROBE_PYTHON_URL")
    if bool(java_url) != bool(python_url):
        parser.error("provide both PROBE_JAVA_URL and PROBE_PYTHON_URL, or neither")
    if java_url:
        java_url, python_url = local_url(java_url), local_url(python_url)
        if args.query_bridge:
            parser.error("--query-bridge configures owned services, not external services")
    query_bridge = None if java_url else args.query_bridge
    owned: list[subprocess.Popen] = []
    logs = []
    temp = ROOT / ".tmp" / f"expanded-probe-{time.time_ns()}"
    temp.mkdir(parents=True)
    try:
        if not java_url:
            jar = ROOT / "backend/target/tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar"
            if not jar.is_file():
                raise FileNotFoundError("build Java service first: mvn -f backend/pom.xml -DskipTests package")
            py_port, java_port = port(), port()
            python_url, java_url = f"http://127.0.0.1:{py_port}", f"http://127.0.0.1:{java_port}"
            env = os.environ.copy()
            env.update(SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(java_port), RAG_SERVICE_URL=python_url,
                       RAG_ENGINE_MODE="llama-index", RAG_FALLBACK_TO_LOCAL="false",
                       RAG_QUERY_BRIDGE=str(args.query_bridge).lower(),
                       KNOWLEDGE_STORE_PATH=str(temp / "imports.json"), EMBEDDING_BACKEND="hash",
                       EMBEDDING_MODEL="hash-sha256-bigram-v1", EMBEDDING_ALLOW_DOWNLOAD="false",
                       MODEL_API_BASE="", MODEL_NAME="", MODEL_API_KEY="")
            flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
            py_log = (temp / "python.log").open("wb"); logs.append(py_log)
            py = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--app-dir", str(ROOT / "rag-service"),
                                   "--host", "127.0.0.1", "--port", str(py_port)], cwd=ROOT, env=env,
                                  stdout=py_log, stderr=subprocess.STDOUT, creationflags=flags); owned.append(py)
            python_health = wait_ready(python_url + "/health", py)
            java_log = (temp / "java.log").open("wb"); logs.append(java_log)
            java = subprocess.Popen(["java", "-jar", str(jar)], cwd=ROOT, env=env,
                                    stdout=java_log, stderr=subprocess.STDOUT, creationflags=flags); owned.append(java)
            wait_ready(java_url + "/actuator/health", java)
        else:
            python_health = wait_ready(python_url + "/health")
            wait_ready(java_url + "/actuator/health")
        if python_health.get("embeddingBackend") != "hash":
            raise ValueError("probe requires hash embedding; external service uses another backend")
        chunks = json.loads((ROOT / "backend/src/main/resources/data/expanded/knowledge-base.json").read_text("utf-8"))
        rows = run_probes(chunks, java_url)
        failures = [row["question"] for row in rows if row["failed"]]
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(render_report(rows, query_bridge), encoding="utf-8")
        args.report.with_suffix(".json").write_text(json.dumps({
            "schemaVersion": 1,
            "checkedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
            "embeddingBackend": "hash", "queryBridge": query_bridge,
            "engine": ENGINE, "version": VERSION, "corpus": "EXPANDED",
            "rows": rows, "failureCount": len(failures),
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if failures:
            raise AssertionError("expanded service probe failed: " + ", ".join(failures))
        print(json.dumps({"rows": len(rows), "regressions": failures, "report": str(args.report)}, ensure_ascii=False))
    finally:
        for process in reversed(owned):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        for log in logs: log.close()


if __name__ == "__main__":
    main()
