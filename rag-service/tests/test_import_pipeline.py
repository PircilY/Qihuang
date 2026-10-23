"""[FIXTURE] Import software tests; no medical facts are generated."""
import json
from pathlib import Path
import subprocess
import sys
import pytest
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"tools"))
from import_knowledge import build, read_records, split_chunks, audit_markdown


def fixture():
    return read_records(ROOT/"tools/fixtures/import.jsonl")[0]


def test_csv_jsonl_txt_produce_same_documents_and_chunks():
    outputs=[build(read_records(ROOT/f"tools/fixtures/import.{kind}")) for kind in ["csv","jsonl","txt"]]
    assert outputs[0]==outputs[1]==outputs[2]
    chunk=outputs[0]["knowledge-base.json"][0]
    assert chunk["section"]=="[FIXTURE] 占位章节"
    assert chunk["pageStart"]==2 and chunk["pageEnd"]==3
    assert chunk["evidenceLevel"]=="未评级"
    assert "主诉：" in chunk["content"] and "方药组成：" in chunk["content"] and "疗效：" in chunk["content"]
    assert outputs[0]["structured-records.json"][0]["fields"]["efficacy"]==fixture()["efficacy"]


def test_validation_reports_reasons_without_deleting_duplicate_records():
    record=fixture()
    duplicate=dict(record,title="[FIXTURE] second title")
    malformed=dict(record,licenseNote="",content="short")
    garbled=dict(record,content=record["content"]+"\ufffd")
    bad_page=dict(record,pageStart="invalid")
    result=build([record,duplicate,malformed,garbled,bad_page])
    assert len(result["knowledge-documents.json"])==2
    assert result["import-report.json"]["duplicates"][0]["kind"]=="exact"
    reasons=[reason for row in result["import-report.json"]["filtered"] for reason in row["reasons"]]
    assert "missing:licenseNote" in reasons and "too-short" in reasons and "garbled-or-control-character" in reasons and "invalid-page" in reasons
    near=dict(record,title="[FIXTURE] near",content=record["content"]+"字段")
    assert build([record,near])["import-report.json"]["duplicates"][0]["kind"]=="near"


def test_redaction_covers_body_structured_and_metadata():
    record=dict(fixture(),content=fixture()["content"]+"\n姓名：测试人物\n13800138000 test@example.org 12345678901234567X",chiefComplaint="姓名：占位人物\n13800138000",title="[FIXTURE] 姓名：占位人物")
    result=build([record],privacy=True)
    text=json.dumps(result,ensure_ascii=False)
    assert "13800138000" not in text and "test@example.org" not in text and "12345678901234567X" not in text
    assert "[已脱敏]" in text and "[手机号已脱敏]" in text


def test_chunk_packing_follows_java_boundary_and_preserves_body():
    content="甲"*300+"。"+"乙"*500
    chunks=split_chunks(content)
    assert chunks[0]=="甲"*300+"。"
    assert "".join(chunks)==content
    assert all(len(chunk)<=620 for chunk in split_chunks("字段"*1000))
    assert split_chunks("占位一\n\n占位二")==["占位一\n\n占位二"]


def test_check_mode_is_read_only_and_detects_drift(tmp_path):
    script=ROOT/"tools/import_knowledge.py"
    command=[sys.executable,str(script),str(ROOT/"tools/fixtures/import.jsonl"),"--output",str(tmp_path),"--redact"]
    subprocess.run(command,check=True,capture_output=True)
    before={path.name:path.read_bytes() for path in tmp_path.iterdir()}
    subprocess.run(command+["--check"],check=True,capture_output=True)
    assert before=={path.name:path.read_bytes() for path in tmp_path.iterdir()}
    (tmp_path/"knowledge-base.json").write_text("[]",encoding="utf-8")
    assert subprocess.run(command+["--check"],capture_output=True).returncode!=0
    assert "BASELINE" in audit_markdown() and "EXPANDED" in audit_markdown()


def test_fixture_cannot_target_formal_corpus():
    command=[sys.executable,str(ROOT/"tools/import_knowledge.py"),str(ROOT/"tools/fixtures/import.jsonl"),"--output",str(ROOT/"backend/src/main/resources/data")]
    assert subprocess.run(command,capture_output=True).returncode!=0
