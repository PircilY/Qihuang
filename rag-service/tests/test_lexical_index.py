"""[FIXTURE] Shared scoring placeholders; non-medical facts."""
from llama_index.core.schema import TextNode
from app.lexical_index import LexicalIndex
from app.engine import _first_sentence
from app.evidence_coverage import assess_evidence_coverage


def test_repeated_frequency_and_full_corpus_idf_are_cached(monkeypatch):
    index = LexicalIndex()
    nodes = [TextNode(id_="FIXTURE-1", text="占位占位 alpha alpha"),
             TextNode(id_="FIXTURE-2", text="其他字段 beta gamma")]
    index.update(nodes)
    assert index.entries["FIXTURE-1"][1]["占位"] == 2
    before = dict(index.idf)
    old = index.entries["FIXTURE-1"]
    hits = index.retrieve("占位", 4, lambda node: node.node.node_id == "FIXTURE-1")
    assert len(hits) == 1
    assert index.idf == before
    index.update(nodes + [TextNode(id_="FIXTURE-3", text="增加字段 delta")])
    assert index.entries["FIXTURE-1"] is old
    assert index.idf["占位"] > before["占位"]


def test_excerpt_and_chunk_coverage():
    assert _first_sentence("Alpha beta. Second sentence") == "Alpha beta."
    assert _first_sentence("Alpha beta\nSecond") == "Alpha beta"
    assert len(_first_sentence("x" * 500)) == 401
    result = assess_evidence_coverage("甘草相互作用涉及皮质类固醇吗？",
        ["[FIXTURE] licorice", "[FIXTURE] corticosteroids interaction"])
    assert not result.covered
