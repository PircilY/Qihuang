#!/usr/bin/env python3
"""Real local HTTP smoke with owned Java/Python processes and isolated import store."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
OPENER=urllib.request.build_opener(urllib.request.ProxyHandler({}))

def call(base,path,payload=None,method=None,expected=200):
    req=urllib.request.Request(base+path,data=None if payload is None else json.dumps(payload,ensure_ascii=False).encode(),
                               headers={"Content-Type":"application/json"},method=method)
    try:
        with OPENER.open(req,timeout=90) as response:
            status=response.status; body=json.load(response)
    except urllib.error.HTTPError as ex:
        status=ex.code; body=json.load(ex)
    assert status==expected,(path,status,body)
    return body


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0)); return sock.getsockname()[1]


def ready(base,path,process,python=False):
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        if process.poll() is not None: raise RuntimeError("Service exited; inspect smoke logs")
        try:
            body=call(base,path)
            if body.get("status")=="UP" and (not python or body.get("indexState")=="READY"): return body
        except (OSError,AssertionError): pass
        time.sleep(0.5)
    raise TimeoutError("Service not ready")


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)


def run():
    folder=ROOT/".tmp"/f"e2e-{time.time_ns()}"; folder.mkdir(parents=True)
    jar=ROOT/"backend/target/tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar"
    if not jar.is_file(): raise FileNotFoundError("Build backend first: mvn -o -f backend/pom.xml -DskipTests package")
    rag_port,java_port=free_port(),free_port()
    rag_url=f"http://127.0.0.1:{rag_port}"; java_url=f"http://127.0.0.1:{java_port}"
    env=os.environ.copy(); deps=ROOT/".rag-deps"
    if deps.is_dir(): env["PYTHONPATH"]=str(deps)
    env.update(SERVER_ADDRESS="127.0.0.1",SERVER_PORT=str(java_port),RAG_SERVICE_URL=rag_url,RAG_ENGINE_MODE="auto",
        RAG_RETRY_INTERVAL="1s",KNOWLEDGE_STORE_PATH=str(folder/"imports.json"),MODEL_API_BASE="",MODEL_NAME="",MODEL_API_KEY="",
        EMBEDDING_BACKEND="hash",EMBEDDING_MODEL="hash-sha256-bigram-v1",EMBEDDING_ALLOW_DOWNLOAD="false",
        RAG_QUERY_BRIDGE="false")
    processes=[]; logs=[]; checks=[]
    def start_rag(suffix):
        log=(folder/f"python-{suffix}.log").open("wb"); logs.append(log)
        process=subprocess.Popen([sys.executable,"-m","uvicorn","app.main:app","--app-dir",str(ROOT/"rag-service"),
                         "--host","127.0.0.1","--port",str(rag_port)],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        processes.append(process);ready(rag_url,"/health",process,python=True);return process
    try:
        rag=start_rag("initial")
        log=(folder/"java.log").open("wb"); logs.append(log)
        java=subprocess.Popen(["java","-XX:MaxRAMPercentage=75.0","-jar",str(jar)],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        processes.append(java);ready(java_url,"/actuator/health",java)
        question={"question":"高血压没有症状也需要检查血压吗？","version":"V3_GUARDED","corpus":"BASELINE"}
        answer=call(java_url,"/api/v1/rag/chat",question)["data"]
        assert not answer["refused"] and answer["citations"] and answer["retrieval"]["engine"]=="llama-index"
        checks.append("question: remote V3 evidence")
        comparison=call(java_url,"/api/v1/rag/compare",{"question":question["question"],"corpus":"BASELINE"})["data"]
        assert len(comparison)==4 and comparison["V0_DIRECT"]["citations"]==[] and comparison["V3_GUARDED"]["citations"]
        checks.append("compare: V0–V3 same corpus")
        missing=call(java_url,"/api/v1/rag/chat",dict(question,useModel=True),expected=400)
        assert missing["code"]=="MODEL_NOT_CONFIGURED";checks.append("model: explicit unconfigured error")
        # Reuse an existing attributed public excerpt, no invented clinical fixture or facts.
        source=next(s for s in json.loads((ROOT/"data/sources.json").read_text(encoding="utf-8")) if s["id"]=="NCCIH-LICORICE")
        excerpt=source["excerpts"][0]
        payload={k:source[k] for k in ("title","sourceType","publisher","publicationYear","sourceUrl","licenseNote")}
        payload.update(content=excerpt["content"],disease=excerpt["disease"],keywords=excerpt["keywords"],section=excerpt["section"])
        imported=call(java_url,"/api/v1/knowledge/import",payload)["data"]
        document_id=imported["document"]["id"]
        assert imported["status"]=="READY",imported
        query={"question":"甘草有哪些严重不良反应？","version":"V3_GUARDED"}
        answer=call(java_url,"/api/v1/rag/chat",query)["data"]
        assert not answer["refused"] and answer["citations"]
        assert any(d["documentId"]==document_id for d in call(rag_url,"/health")["documents"])
        checks.append("import: query and remote runtime inventory")
        stop(rag)
        fallback=call(java_url,"/api/v1/rag/chat",question)["data"]
        assert fallback["retrieval"]["engine"]=="java-local"
        assert "fallback=local" in fallback["retrieval"]["notes"] and any("已使用本地引擎" in n for n in fallback["retrieval"]["notes"])
        checks.append("remote unavailable: explicit local fallback")
        rag=start_rag("restart")
        assert call(rag_url,"/health")["documents"]==[]
        deadline=time.monotonic()+30
        while True:
            recovered=call(java_url,"/api/v1/rag/chat",query)["data"]
            if recovered["retrieval"]["engine"]=="llama-index": break
            if time.monotonic()>deadline: raise TimeoutError("Remote recovery failed")
            time.sleep(.5)
        health=call(rag_url,"/health")
        assert any(d["documentId"]==document_id for d in health["documents"])
        assert health["safetyRulesSha256"]==hashlib.sha256((ROOT/"safety-rules.json").read_bytes()).hexdigest()
        checks.append("restart: automatic resync and matching safety hash")
        return {"checks":checks,"logDirectory":str(folder),"javaUrl":java_url,"pythonUrl":rag_url,"passed":True}
    finally:
        for process in reversed(processes): stop(process)
        for log in logs: log.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report",type=Path,default=ROOT/"runtime/e2e-report.json")
    args=parser.parse_args()
    result=run();args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=="__main__": main()
