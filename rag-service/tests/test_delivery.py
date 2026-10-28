import importlib.util
from pathlib import Path
import json
import zipfile
import pytest

spec=importlib.util.spec_from_file_location("package_delivery",Path(__file__).resolve().parents[2]/"tools/package_delivery.py")
package=importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)

@pytest.mark.parametrize("name",[".rag-deps/secret.txt","runtime/data.json","backend/target/app.jar",".env","nested/.venv/bin/python","data/holdout-cases.json","docs/evaluation-holdout.md","models/a.safetensors",
                               "data/holdout/cases.json", ".aws/credentials", "data/frozen/labels.json", "Runtime/data.json"])
def test_delivery_blocks_private_generated_and_frozen_files(name):
    assert not package.allowed(name)

@pytest.mark.parametrize("name",["safety-rules.json","backend/src/main/resources/data/knowledge-base.json","tools/fixtures/import.jsonl","rag-service/requirements-lock.txt"])
def test_delivery_keeps_sources_safety_and_fixtures(name):
    assert package.allowed(name)


def test_python_legacy_llm_env_is_ignored(monkeypatch):
    from app.config import Settings
    monkeypatch.setenv("LLM_API_BASE","https://must-not-be-called.invalid")
    monkeypatch.setenv("LLM_MODEL","ignored")
    settings=Settings.from_env()
    assert not hasattr(settings,"llm_api_base")
    assert not hasattr(settings,"llm_model")


def test_delivery_filters_restricted_fulltext_and_encoded_chunks(tmp_path, monkeypatch):
    paths = {
        "data/expanded-sources.json": {"records": [
            {"documentId": "OPEN", "fullTextPath": "data/expanded-raw/open.txt",
             "rawSourcePath": "data/expanded-raw/open.xml", "corpusRole": "core",
             "inclusionDecision": "include", "redistributionStatus": "permissive-verified"},
            {"documentId": "RESTRICTED", "fullTextPath": "data/expanded-raw/private.txt",
             "rawSourcePath": "data/expanded-raw/private.xml", "corpusRole": "background",
             "inclusionDecision": "include", "redistributionStatus": "restricted-review-required"}]},
        "backend/src/main/resources/data/expanded/knowledge-base.json": [
            {"documentId": "OPEN", "content": "open source"}, {"documentId": "RESTRICTED", "content": "restricted source"}],
        "data/expanded-raw/open.txt": "open source", "data/expanded-raw/private.txt": "restricted source",
        "docs/service-probe.json": {"rows": [{"citations": [
            {"documentId": "RESTRICTED", "quote": "restricted source"},
            {"documentId": "OPEN", "quote": "open source"}]}]},
    }
    for name, payload in paths.items():
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload if isinstance(payload, str) else json.dumps(payload), encoding="utf-8")
    monkeypatch.setattr(package.subprocess, "check_output", lambda *a, **kw: ("\0".join(paths) + "\0").encode())
    output = tmp_path / "delivery.zip"
    package.build(tmp_path, output)
    with zipfile.ZipFile(output) as archive:
        assert "data/expanded-raw/private.txt" not in archive.namelist()
        assert "restricted source" not in archive.read("backend/src/main/resources/data/expanded/knowledge-base.json").decode()
        assert len(json.loads(archive.read("data/expanded-sources.json"))["records"]) == 1
        assert json.loads(archive.read("delivery-corpus-isolation.json"))["excluded"][0]["documentId"] == "RESTRICTED"
        report = archive.read("docs/service-probe.json").decode()
        assert '"quote": "restricted source"' not in report
        assert '"quote": "open source"' in report
