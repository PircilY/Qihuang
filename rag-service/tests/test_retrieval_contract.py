from __future__ import annotations

import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
from statistics import mean
from dataclasses import replace
from pathlib import Path

from app.config import Settings
from app.engine import LlamaIndexEngine
from app.schemas import ChatRequest


ROOT = Path(__file__).resolve().parents[2]
CASES_PATH = Path(__file__).parent / "fixtures" / "retrieval-contract-cases.json"
SNAPSHOT_PATH = ROOT / "backend" / "src" / "main" / "resources" / "data" / "knowledge-base.json"


def _free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def _request_json(url: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"} if data is not None else {},
    )
    with urllib.request.urlopen(request, timeout=3) as response:
        return json.loads(response.read().decode("utf-8"))


def _maven_command(*arguments: str) -> list[str]:
    if os.name == "nt":
        return ["cmd.exe", "/d", "/s", "/c", "mvn.cmd " + " ".join(arguments)]
    return ["mvn", *arguments]


def _start_java_engine(port: int, store_path: Path) -> subprocess.Popen:
    command = [
        "java",
        "-jar",
        str(ROOT / "backend" / "target" / "tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar"),
        "--app.rag-engine.mode=local",
        "--server.address=127.0.0.1",
        f"--server.port={port}",
        f"--app.knowledge.store-path={store_path}",
    ]
    return subprocess.Popen(
        command,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def test_java_python_v3_retrieval_contract_agreement() -> None:
    subprocess.run(
        _maven_command("-q", "-f", "backend/pom.xml", "-DskipTests", "package"),
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    cases = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    corpus = json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))
    java_port = _free_port()
    java_base_url = f"http://127.0.0.1:{java_port}"
    java_process = _start_java_engine(
        java_port, ROOT / ".tmp" / f"retrieval-contract-{java_port}.json"
    )

    try:
        ready = False
        for _ in range(120):
            if java_process.poll() is not None:
                output = java_process.communicate()[0].decode("utf-8", errors="replace")
                raise AssertionError(f"Java local engine exited before becoming ready:\n{output[-4000:]}")
            try:
                _request_json(f"{java_base_url}/actuator/health")
                ready = True
                break
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.5)
        assert ready, "Java local engine did not become ready within 60 seconds"

        settings = replace(
            Settings.from_env(),
            data_path=SNAPSHOT_PATH,
            candidate_limit=8,
            final_limit=4,
            rrf_rank_constant=60,
            lexical_weight=1.0,
            vector_weight=0.85,
            minimum_evidence_score=0.016,
            llm_api_base="",
            llm_model="",
        )
        python_engine = LlamaIndexEngine(settings)
        python_engine.reload()
        chunk_documents = {chunk["id"]: chunk["documentId"] for chunk in corpus}

        rejects_match = 0
        top1_matches = 0
        top1_cases = 0
        jaccard_scores = []
        for case in cases:
            question = case["question"]
            java_result = _request_json(
                f"{java_base_url}/api/v1/rag/chat",
                {"question": question, "version": "V3_GUARDED", "useModel": False},
            )["data"]
            python_result = python_engine.query(
                ChatRequest(question=question, version="V3_GUARDED", extractiveOnly=True)
            )

            java_trace = java_result["retrieval"]
            python_trace = python_result.retrieval
            java_rejected = java_trace["rejected"]
            python_rejected = python_trace.rejected
            assert java_result["refused"] is java_rejected
            assert python_result.refused is python_rejected
            rejects_match += int(java_rejected == python_rejected)

            java_citations = java_result["citations"]
            python_citations = python_result.citations
            java_ids = [citation["chunkId"] for citation in java_citations]
            python_ids = [citation.chunk_id for citation in python_citations]
            if "expectedChunkId" in case:
                expected_chunk_id = case["expectedChunkId"]
                expected_refused = case["expectedRefused"]
                assert java_result["refused"] is expected_refused, case["id"]
                assert python_result.refused is expected_refused, case["id"]
                assert expected_chunk_id in java_ids, case["id"]
                assert expected_chunk_id in python_ids, case["id"]
            assert len(java_ids) <= 4 and len(python_ids) <= 4
            assert len(java_ids) == len(set(java_ids)), case["id"]
            assert len(python_ids) == len(set(python_ids)), case["id"]
            assert all(chunk_id in chunk_documents for chunk_id in java_ids + python_ids)
            assert all(
                citation["documentId"] == chunk_documents[citation["chunkId"]]
                for citation in java_citations
            )
            assert all(
                citation.document_id == chunk_documents[citation.chunk_id]
                for citation in python_citations
            )
            assert java_trace["threshold"] == python_trace.threshold == 0.016
            if case["id"] == "cross-channel-dedup":
                assert java_trace["fusedCandidateCount"] < (
                    java_trace["lexicalCandidateCount"] + java_trace["vectorCandidateCount"]
                )
                assert python_trace.fused_candidate_count < (
                    python_trace.lexical_candidate_count + python_trace.vector_candidate_count
                )

            if java_citations and python_citations:
                top1_cases += 1
                top1_matches += int(
                    java_citations[0]["documentId"] == python_citations[0].document_id
                )
            java_set, python_set = set(java_ids), set(python_ids)
            union = java_set | python_set
            jaccard_scores.append(len(java_set & python_set) / len(union) if union else 1.0)

        reject_agreement = rejects_match / len(cases)
        top1_agreement = top1_matches / top1_cases if top1_cases else 1.0
        mean_jaccard = mean(jaccard_scores)
        print(
            "Retrieval Contract agreement: "
            f"reject={reject_agreement:.0%}, top1-document={top1_agreement:.0%} "
            f"({top1_matches}/{top1_cases}), "
            f"mean top-K Jaccard={mean_jaccard:.3f} ({len(cases)} cases)",
            flush=True,
        )
        assert reject_agreement == 1.0
        assert top1_agreement >= 0.90
        assert mean_jaccard >= 0.75
    finally:
        java_process.terminate()
        try:
            java_process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            java_process.kill()
            java_process.wait(timeout=5)
