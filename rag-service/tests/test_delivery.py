import importlib.util
from pathlib import Path
import pytest

spec=importlib.util.spec_from_file_location("package_delivery",Path(__file__).resolve().parents[2]/"tools/package_delivery.py")
package=importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)

@pytest.mark.parametrize("name",[".rag-deps/secret.txt","runtime/data.json","backend/target/app.jar",".env","nested/.venv/bin/python","data/holdout-cases.json","docs/evaluation-holdout.md","models/a.safetensors"])
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
