"""[FIXTURE] Shared scoring placeholders; non-medical facts."""
from llama_index.core.schema import NodeWithScore, TextNode
from app.lexical_index import LexicalIndex, searchable
from app.engine import _first_sentence, _is_related, _metadata_boost
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


def test_low_metadata_is_not_indexed_or_used_as_related_anchor():
    node = TextNode(id_="FIXTURE-LOW", text="Unrelated fixture body", metadata={
        "disease": "AlphaCondition、BetaCondition", "herbs": "AlphaHerb|BetaHerb",
        "low_metadata_terms": "AlphaCondition|AlphaHerb",
    })
    index = LexicalIndex()
    index.update([node])
    wrapped = NodeWithScore(node=node, score=1)

    assert "AlphaCondition" not in searchable(node)
    assert "AlphaHerb" not in searchable(node)
    assert "BetaCondition" in searchable(node)
    assert "BetaHerb" in searchable(node)
    assert not index.related("AlphaCondition", wrapped)
    assert not index.related("AlphaHerb", wrapped)
    assert not _is_related("AlphaCondition", wrapped)
    assert index.related("BetaCondition", wrapped)
    assert _metadata_boost("AlphaCondition", wrapped) == 0
    assert _metadata_boost("BetaCondition", wrapped) == .22


def test_confidence_only_update_invalidates_terms_and_anchors_but_keeps_source_text():
    node = TextNode(id_="FIXTURE-CHANGE", text="AlphaHerb source mention", metadata={
        "disease": "AlphaCondition", "herbs": "AlphaHerb",
    })
    index = LexicalIndex()
    index.update([node])
    assert index.related("AlphaCondition", NodeWithScore(node=node, score=1))
    changed = TextNode(id_=node.node_id, text=node.text, metadata={
        **node.metadata, "low_metadata_terms": "AlphaCondition|AlphaHerb",
    })
    index.update([changed])

    assert "alphacondition" not in index.entries[node.node_id][1]
    assert not index.related("AlphaCondition", NodeWithScore(node=changed, score=1))
    assert "alphaherb" in index.entries[node.node_id][1]
    assert searchable(changed).count("AlphaHerb") == 1


def test_in_place_confidence_update_refreshes_cached_terms_and_anchors():
    node = TextNode(id_="FIXTURE-MUTABLE", text="Unrelated fixture body",
                    metadata={"disease": "AlphaCondition"})
    index = LexicalIndex()
    index.update([node])
    previous = index.entries[node.node_id]
    assert index.related("AlphaCondition", NodeWithScore(node=node, score=1))

    node.metadata["low_metadata_terms"] = "AlphaCondition"
    index.update([node])

    assert index.entries[node.node_id] is not previous
    assert "alphacondition" not in index.entries[node.node_id][1]
    assert not index.related("AlphaCondition", NodeWithScore(node=node, score=1))
    assert index.retrieve("AlphaCondition", 4, lambda _: True) == []


def test_replacement_node_refreshes_reference_without_retokenizing():
    node = TextNode(id_="FIXTURE-REPLACED", text="AlphaTerm fixture",
                    metadata={"publisher": "Previous fixture"})
    index = LexicalIndex()
    index.update([node])
    previous_terms = index.entries[node.node_id][1]
    replacement = TextNode(id_=node.node_id, text=node.text,
                           metadata={"publisher": "Current fixture"})

    index.update([replacement])

    assert index.entries[node.node_id][1] is previous_terms
    hit = index.retrieve("AlphaTerm", 4, lambda _: True)[0]
    assert hit.node is replacement
    assert hit.metadata["publisher"] == "Current fixture"
