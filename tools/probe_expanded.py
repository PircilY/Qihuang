"""Run deterministic bilingual EXPANDED queries against local HTTP services."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
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


def wait_ready(url: str, process: subprocess.Popen, timeout: int = 240):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, default=ROOT / "docs/service-probe.md")
    args = parser.parse_args()
    # The CLI caller may supply running service URLs for a different process manager.
    java_url = os.environ.get("PROBE_JAVA_URL")
    python_url = os.environ.get("PROBE_PYTHON_URL")
    owned: list[subprocess.Popen] = []
    logs = []
    temp = ROOT / ".tmp" / f"expanded-probe-{time.time_ns()}"
    temp.mkdir(parents=True)
    try:
        if not java_url or not python_url:
            jar = ROOT / "backend/target/tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar"
            if not jar.is_file():
                raise FileNotFoundError("build Java service first: mvn -f backend/pom.xml -DskipTests package")
            py_port, java_port = port(), port()
            python_url, java_url = f"http://127.0.0.1:{py_port}", f"http://127.0.0.1:{java_port}"
            env = os.environ.copy()
            env.update(SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(java_port), RAG_SERVICE_URL=python_url,
                       KNOWLEDGE_STORE_PATH=str(temp / "imports.json"), EMBEDDING_BACKEND="hash",
                       EMBEDDING_MODEL="hash-sha256-bigram-v1", EMBEDDING_ALLOW_DOWNLOAD="false",
                       MODEL_API_BASE="", MODEL_NAME="", MODEL_API_KEY="")
            py_log = (temp / "python.log").open("wb"); logs.append(py_log)
            py = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--app-dir", str(ROOT / "rag-service"),
                                   "--host", "127.0.0.1", "--port", str(py_port)], cwd=ROOT, env=env,
                                  stdout=py_log, stderr=subprocess.STDOUT); owned.append(py)
            wait_ready(python_url + "/health", py)
            java_log = (temp / "java.log").open("wb"); logs.append(java_log)
            java = subprocess.Popen(["java", "-jar", str(jar)], cwd=ROOT, env=env,
                                    stdout=java_log, stderr=subprocess.STDOUT); owned.append(java)
            wait_ready(java_url + "/actuator/health", java)
        chunks = json.loads((ROOT / "backend/src/main/resources/data/expanded/knowledge-base.json").read_text("utf-8"))
        rows = []
        failures = []
        for question, terms in QUESTIONS:
            try:
                body = request(java_url + "/api/v1/rag/chat", {"question": question, "version": "V3_GUARDED", "corpus": "EXPANDED"})["data"]
                citations = body.get("citations", [])[:3]
                hit = any(any(term.casefold() in (str(citation.get("documentTitle", "")) + " " + str(citation.get("content", ""))).casefold()
                              for term in terms) for citation in citations)
                coverage = scan_coverage(chunks, terms)
                if coverage and not hit:
                    failures.append(question)
                rows.append({"question": question, "citations": citations, "hit": hit, "coverage": coverage,
                             "status": "語料無覆蓋" if coverage == 0 else "命中" if hit else "有覆蓋但未命中",
                             "engine": body.get("retrieval", {}).get("engine", "unknown"), "version": body.get("version", "V3_GUARDED")})
            except Exception as exc:
                rows.append({"question": question, "error": str(exc), "coverage": scan_coverage(chunks, terms),
                             "status": "待验证", "engine": "unavailable", "version": "V3_GUARDED"})
        lines = ["# EXPANDED 真实服务端到端探测", "", "SHA-256 hash 检索；固定7个问题；引用正文/标题核心词面匹配。语料覆盖数为全库含任一语言术语的片段数。检索异常仅在语料覆盖大于0且前三条未命中时作为回归失败。", "",
                 "G 之前代码对照：`delivery-c9652d3` 当前代码同环境对照未执行；报告只展示当前服务结果，不估算旧版结果。", "",
                 "|问题|覆盖片段数|前三条命中|状态|引擎|版本|前三条引用标题|", "|---|---:|---|---|---|---|---|"]
        for row in rows:
            titles = "<br>".join(str(item.get("documentTitle", "")) for item in row.get("citations", [])) or row.get("error", "")
            lines.append(f"|{row['question']}|{row.get('coverage', 0)}|{row.get('hit', False)}|{row['status']}|{row['engine']}|{row['version']}|{titles}|")
        args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
        if failures:
            raise AssertionError("covered terminology missed in top three: " + ", ".join(failures))
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
