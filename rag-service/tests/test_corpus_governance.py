import importlib
import sys
import json
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
governance = importlib.import_module("corpus_governance")
collector = importlib.import_module("collect_expanded_sources")


def article(license_url="https://creativecommons.org/licenses/by/4.0/"):
    return ('<article xmlns:xlink="http://www.w3.org/1999/xlink"><front>'
            '<permissions><license xlink:href="' + license_url + '"><license-p>License</license-p>'
            '</license></permissions></front><body><sec><title>Section</title>'
            '<p>' + "Alpha source text. " * 40 + '</p></sec></body></article>')


def test_structured_source_keeps_body_and_section_not_abstract():
    result = governance.parse_article(article())
    assert result["text"].startswith("Section\n\n")
    assert governance.redistribution_license(result["licenses"]).endswith("/by/4.0/")
    with pytest.raises(ValueError, match="body missing"):
        governance.parse_article("<article><abstract>Abstract only</abstract></article>")


@pytest.mark.parametrize("url", [
    "https://creativecommons.org/licenses/by-nc/4.0/",
    "https://creativecommons.org/licenses/by-nc-nd/4.0/",
    "https://creativecommons.org.attacker.invalid/licenses/by/4.0/",
    "javascript://creativecommons.org/licenses/by/4.0/",
    "https://credential:secret@creativecommons.org/licenses/by/4.0/",
    "https://creativecommons.org:444/licenses/by/4.0/",
])
def test_no_automatic_redistribution_for_restricted_or_spoofed_license(url):
    assert governance.redistribution_license(governance.parse_article(article(url))["licenses"]) is None


def test_source_path_cannot_escape_even_through_absolute_path(tmp_path):
    assert governance.source_path("data/expanded-raw/source.txt", tmp_path).is_relative_to(tmp_path)
    for path in ("../../private.txt", "data/expanded-raw/../secret.txt", str(tmp_path / "secret.txt")):
        with pytest.raises(ValueError):
            governance.source_path(path, tmp_path)


def test_legacy_records_keep_indexing_and_excluded_records_do_not():
    assert governance.indexed({"inclusionDecision": "include"})
    assert not governance.indexed({"inclusionDecision": "include", "corpusRole": "excluded"})
    assert not governance.indexed({"inclusionDecision": "exclude", "corpusRole": "core"})


def test_discovery_duplicate_keys_are_normalized_and_ignore_empty_values():
    assert collector.duplicates({"doi": " ABC "}, [{"doi": "abc"}])
    assert collector.duplicates({"pmcid": "PMC123"}, [{"pmcid": "PMC123"}])
    assert not collector.duplicates({"pmcid": ""}, [{"pmcid": ""}])


def test_topic_screen_does_not_claim_clinical_expert_review():
    result = governance.topic_review({"title": "Astragalus heart failure research"},
                                     "Astragalus research.\n\nHeart failure outcomes.")
    assert result["corpusRole"] == "core"
    assert result["topicReview"]["status"] == "automatic-screened-not-clinical-review"
    assert governance.topic_review({"title": "Hypertension research"}, "Source")["corpusRole"] == "background"
    assert governance.topic_review({"title": "Unrelated research"}, "Source")["corpusRole"] == "excluded"
    missing = governance.topic_review({"title": "Astragalus heart failure research"}, "Unrelated body.")
    assert missing["corpusRole"] == "excluded"
    assert missing["topicReview"]["reason"] == "missing-dual-topic-body"
    assert len(result["topicReview"]["bodyEvidence"]) == 2


def test_source_url_rejects_credential_and_non_https():
    assert governance.source_url("https://pmc.ncbi.nlm.nih.gov/articles/PMC123/")
    assert not governance.source_url("https://secret:token@example.org/")
    assert not governance.source_url("file:///private.txt")


def test_collector_redirect_checked_before_request():
    assert collector.api_url(collector.API + "/search?query=astragalus")
    for url in ("http://www.ebi.ac.uk/europepmc/webservices/rest/search",
                "https://www.ebi.ac.uk:444/europepmc/webservices/rest/search",
                "https://token@www.ebi.ac.uk/europepmc/webservices/rest/search",
                "https://www.ebi.ac.uk/unrelated",
                "https://127.0.0.1/private"):
        assert not collector.api_url(url)
        with pytest.raises(ValueError, match="redirect"):
            collector.ApiRedirectHandler().redirect_request(None, None, 302, "", {}, url)


def test_entities_are_rejected_before_parsing():
    with pytest.raises(ValueError, match="unsafe"):
        governance.parse_article('<!DOCTYPE article [<!ENTITY x "secret">]>' + article())


def test_incidental_abstract_topics_and_notices_do_not_enter_core():
    assert governance.topic_review({
        "title": "Radial pulse prediction of diabetic foot risk",
        "abstractOrDescription": "Traditional Chinese medicine and hypertension are mentioned.",
    }, "")["corpusRole"] == "excluded"
    assert governance.topic_review({"title": "Retraction: Astragalus heart failure study"}, "")["corpusRole"] == "excluded"
    assert collector.plain("&lt;i&gt;Astragalus&lt;/i&gt; Ca&lt;sup&gt;2+&lt;/sup&gt;") == "Astragalus Ca2+"


def test_raw_retry_is_idempotent_and_never_overwrites_conflicting_source(tmp_path):
    path = tmp_path / "source.xml"
    collector.write_raw(path, "retained source")
    collector.write_raw(path, "retained source")
    with pytest.raises(ValueError, match="differs"):
        collector.write_raw(path, "different source")
    assert path.read_text() == "retained source"


def test_pagination_resume_and_duplicate_candidate_merge(tmp_path):
    class Client:
        calls = []

        def get(self, url):
            self.calls.append(url)
            return json.dumps({"resultList": {"result": [{"pmcid": "PMC123",
                "title": "Astragalus heart failure", "pubYear": "2024"}]},
                "nextCursorMark": "next"}).encode()

    client = Client()
    state = {"candidates": []}
    collector.discover(client, state, 1, tmp_path / "state.json")
    assert len(state["candidates"]) == 1
    assert len(state["candidates"][0]["topics"]) == 6
    collector.discover(client, state, 1, tmp_path / "state.json")
    assert len(state["candidates"]) == 1
    assert all("cursorMark=next" in url for url in client.calls[12:])


def test_ingestion_cumulative_limit_and_rejection_leave_ledger_unchanged(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "ROOT", tmp_path)
    ledger_path = tmp_path / "data/expanded-sources.json"
    ledger = {"records": [{"documentId": "EN-PMC123", "pmcid": "PMC123", "corpusRole": "core"}]}
    governance.save_json(ledger_path, ledger)

    class Client:
        def get(self, url):
            raise AssertionError("full text must not be requested")

    state = {"batchId": "test", "candidates": [
        {"pmcid": "PMC123", "status": "included", "documentId": "EN-PMC123", "title": "Astragalus heart failure", "language": "en"},
        {"pmcid": "PMC456", "title": "Astragalus heart failure", "language": "en"},
    ]}
    collector.ingest(Client(), state, tmp_path / "state.json", 1)
    assert json.loads(ledger_path.read_text()) == ledger
    state["candidates"] = [{"pmcid": "PMC789", "title": "Unrelated topic", "language": "en"}]
    collector.ingest(Client(), state, tmp_path / "state.json", 1)
    assert state["candidates"][0]["status"] == "rejected"
    assert json.loads(ledger_path.read_text()) == ledger


def chinese_article(title="黄芪改善心力衰竭的研究", url="https://creativecommons.org/licenses/by/4.0/", statement="CC BY 4.0"):
    return ('<article xml:lang="zh" article-type="review-article" xmlns:xlink="http://www.w3.org/1999/xlink">'
            '<front><article-meta><title-group><article-title>' + title + '</article-title>'
            '<trans-title-group xml:lang="en"><trans-title xml:lang="en">Astragalus heart failure research'
            '</trans-title></trans-title-group></title-group><abstract><p>' + '原文摘要讨论黄芪与心力衰竭。' * 8
            + '</p></abstract><permissions><license><license-p>' + statement
            + '<ext-link xlink:href="' + url + '">License</ext-link></license-p></license></permissions>'
            '</article-meta></front><body><sec><title>研究背景</title><p>' + '黄芪用于心力衰竭研究，结果尚待验证。' * 60
            + '</p></sec></body></article>')


def test_chinese_title_abstract_and_article_type_are_source_authored():
    result = governance.parse_article(chinese_article())
    assert result["title"] == "黄芪改善心力衰竭的研究"
    assert result["translatedTitles"][0]["text"] == "Astragalus heart failure research"
    assert result["abstracts"][0].startswith("原文摘要")
    assert "原文摘要" not in result["text"]
    assert result["language"] == "zh"
    assert result["documentType"] == "review article"


@pytest.mark.parametrize("url,statement", [
    ("https://creativecommons.org/licenses/by-nc-nd/4.0/", "CC BY-NC-ND 4.0"),
    ("https://creativecommons.org/licenses/by-nc-sa/4.0/", "CC BY-NC-SA 4.0"),
    ("https://creativecommons.org.attacker.invalid/licenses/by-nc/4.0/", "CC BY-NC 4.0"),
    ("https://secret@creativecommons.org/licenses/by-nc/4.0/", "CC BY-NC 4.0"),
    ("https://creativecommons.org/licenses/by-nc/4.0/", "Open access unspecified"),
    ("https://creativecommons.org:444/licenses/by-nc/4.0/", "CC BY-NC 4.0"),
])
def test_noncommercial_requires_explicit_unspoofed_grant(url, statement):
    licenses = governance.parse_article(chinese_article(url=url, statement=statement))["licenses"]
    assert governance.noncommercial_license(licenses) is None


def test_noncommercial_license_is_not_a_permissive_license():
    licenses = governance.parse_article(chinese_article(
        url="https://creativecommons.org/licenses/by-nc/4.0/", statement="CC BY-NC 4.0 非商业"))["licenses"]
    assert governance.noncommercial_license(licenses).endswith("/by-nc/4.0/")
    assert governance.redistribution_license(licenses) is None


@pytest.mark.parametrize("opt_in,expected", [(False, "rejected"), (True, "included")])
def test_chinese_ingestion_noncommercial_opt_in_and_append(tmp_path, monkeypatch, opt_in, expected):
    monkeypatch.setattr(collector, "ROOT", tmp_path)
    ledger_path = tmp_path / "data/expanded-sources.json"
    old = {"documentId": "ZH-PMC100", "title": "既有文档", "pmcid": "PMC100", "corpusRole": "background"}
    governance.save_json(ledger_path, {"records": [old]})

    class Client:
        def get(self, url):
            return chinese_article(url="https://creativecommons.org/licenses/by-nc/4.0/", statement="CC BY-NC 4.0").encode()

    state = {"batchId": "test", "candidates": [
        {"pmcid": "PMC200", "pmid": "", "doi": "", "title": "English API title without topic clues",
         "publicationYear": 2025, "abstractOrDescription": "", "authors": [], "documentType": "research article",
         "language": "zh", "topics": ["astragalus-heart-failure"]},
    ]}
    collector.ingest(Client(), state, tmp_path / "state.json", 1, allow_noncommercial=opt_in, languages=("zh",))
    assert state["candidates"][0]["status"] == expected
    records = json.loads(ledger_path.read_text(encoding="utf-8"))["records"]
    assert records[0] == old
    if opt_in:
        record = records[1]
        assert record["title"] == "黄芪改善心力衰竭的研究"
        assert record["language"] == "zh"
        assert record["documentType"] == "review article"
        assert record["redistributionStatus"] == "restricted-review-required"
        assert record["redistributionLicenseUrl"] is None
        assert record["usageScope"] == "noncommercial-research-only"
        assert record["abstractOrDescription"].startswith("原文摘要")
    else:
        assert len(records) == 1


def test_discovery_language_filter_and_changed_query_cursor_reset(tmp_path):
    class Client:
        calls = []

        def get(self, url):
            self.calls.append(url)
            return json.dumps({"resultList": {"result": []}}).encode()

    client = Client()
    state = {"candidates": [], "queries": {"tcm-syndromes/zh": {
        "query": "old query", "cursor": "stale", "complete": True,
    }}}
    collector.discover(client, state, 1, tmp_path / "state.json", languages=("zh",), topics=("tcm-syndromes",))
    assert len(client.calls) == 1
    assert "cursorMark=%2A" in client.calls[0]
    assert "LANG%3Achi" in client.calls[0]


def test_chinese_cardiovascular_names_have_the_same_dual_topic_requirement():
    record = {"title": "理气活血方剂抗动脉粥样硬化的研究"}
    assert governance.topic_review(record, "中药方剂与动脉粥样硬化研究。")["corpusRole"] == "core"
    assert governance.topic_review(record, "没有相关实验正文。")["corpusRole"] == "excluded"
    assert governance.topic_review({"title": "参蛤散治疗心力衰竭"},
                                   "参蛤散治疗心力衰竭的研究。")["corpusRole"] == "core"


def test_restoring_chinese_metadata_does_not_touch_body_role_or_generate_translation(tmp_path, monkeypatch):
    prepare = importlib.import_module("prepare_chinese_corpus")
    path = tmp_path / "source.xml"
    path.write_text(chinese_article(), encoding="utf-8")
    monkeypatch.setattr(prepare, "source_path", lambda value: tmp_path / value)
    original = {"records": [{"documentId": "ZH-PMC123", "language": "zh", "title": "Old English API title",
                            "rawSourcePath": "source.xml", "corpusRole": "background", "sourceChecksum": "unchanged"}]}
    restored, changes = prepare.restore_metadata(original)
    assert original["records"][0]["title"] == "Old English API title"
    record = restored["records"][0]
    assert record["title"] == "黄芪改善心力衰竭的研究"
    assert record["sourceChecksum"] == "unchanged"
    assert record["corpusRole"] == "background"
    assert record["metadataProvenance"]["translatedByProject"] is False
    assert len(changes) == 1
    assert prepare.restore_metadata(restored)[1] == []


def test_source_translated_title_can_establish_formula_but_abstract_cannot():
    source = {"title": "肝豆补肾汤作用研究",
              "sourceTranslatedTitles": [{"language": "en", "text": "Gandou Bushen decoction experimental study"}]}
    assert governance.topic_review(source, "中药方剂肝豆补肾汤的研究。")["corpusRole"] == "background"
    assert governance.topic_review({"title": "无关研究", "abstractOrDescription": "decoction and heart failure"},
                                   "中药和心衰只是附带介绍。")["corpusRole"] == "excluded"


def test_paraventricular_nucleus_is_not_cardiac_ventricular_title_evidence():
    source = {"title": "下丘脑室旁核参与针灸治疗疾病的机制",
              "sourceTranslatedTitles": [{"language": "en",
                  "text": "Paraventricular hypothalamic nucleus in acupuncture treatment"}]}
    review = governance.topic_review(source, "针灸与paraventricular nucleus的综述，附带讨论高血压。")
    assert review["corpusRole"] == "background"
    assert review["topicReview"]["titleCardioTerms"] == []
    assert "ventricular" not in review["topicReview"]["bodyCardioTerms"]
    assert governance.topic_review({"title": "Astragalus left ventricular remodeling"},
                                   "Astragalus and ventricular remodeling.")["corpusRole"] == "core"


def test_dictionary_check_uses_authored_title_pair_not_chinese_title_abbreviations():
    checker = importlib.import_module("check_terminology_consistency")
    entries = [{"id": "disease-1", "zh": "高血压", "en": ["hypertension"]}]
    source = {"title": "高血压的CMR研究", "sourceTranslatedTitles": []}
    assert checker.check(entries, [source])[0]["status"] == "无证据"
    source["sourceTranslatedTitles"] = [{"language": "en", "text": "CMR study of hypertension"}]
    assert checker.check(entries, [source])[0]["status"] == "已验证"
    source["sourceTranslatedTitles"] = [{"language": "en", "text": "Unrelated title"}]
    assert checker.check(entries, [source])[0]["status"] == "冲突"


def test_contradictory_noncommercial_statement_is_not_treated_as_an_unrestricted_grant():
    assert governance.noncommercial_license([{
        "urls": ["https://creativecommons.org/licenses/by-nc/4.0/"],
        "statement": "CC BY-NC 4.0 but no derivatives are allowed",
    }]) is None


def test_license_note_keeps_link_and_noncommercial_scope():
    builder = importlib.import_module("build_expanded_corpus")
    note = builder.license_note({
        "license": "cc by-nc", "licenseStatus": "verified",
        "noncommercialLicenseUrl": "https://creativecommons.org/licenses/by-nc/4.0/",
        "usageScope": "noncommercial-research-only",
    })
    assert "https://creativecommons.org/licenses/by-nc/4.0/" in note
    assert "仅限非商业研究" in note


def test_resume_after_ledger_append_counts_background_toward_explicit_limit(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "ROOT", tmp_path)
    governance.save_json(tmp_path / "data/expanded-sources.json", {"records": [{
        "documentId": "ZH-PMC123", "pmcid": "PMC123", "corpusRole": "background",
        "collectionBatch": "test",
    }]})

    class Client:
        def get(self, url):
            raise AssertionError("resumed background already fills batch limit")

    state = {"batchId": "test", "candidates": [
        {"pmcid": "PMC123", "title": "中医证型", "language": "zh", "status": "candidate"},
        {"pmcid": "PMC456", "title": "中医证型", "language": "zh", "status": "candidate"},
    ]}
    collector.ingest(Client(), state, tmp_path / "state.json", 1, include_background=True)
    assert state["candidates"][0]["status"] == "included"
    assert state["candidates"][1]["status"] == "candidate"
    assert state["lastIngestion"]["includedTotal"] == 1


def test_chinese_check_freeze_preserves_source_but_normalizes_mixed_case_anchors(tmp_path, monkeypatch):
    check = importlib.import_module("check_chinese_retrieval")
    corpus = tmp_path / "knowledge-base.json"
    body = "原文针灸与PVN机制研究。"
    governance.save_json(corpus, [{"documentId": "ZH-PMC123", "id": "C1", "content": body}])
    governance.save_json(tmp_path / "data/expanded-sources.json", {"records": [{
        "documentId": "ZH-PMC123", "sourceUrl": "https://example.org/",
        "sourceChecksum": "retained", "corpusRole": "background", "license": "cc by-nc",
    }]})
    monkeypatch.setattr(check, "ROOT", tmp_path)
    monkeypatch.setattr(check, "CORPUS", corpus)
    monkeypatch.setattr(check, "CASES", tmp_path / "cases.json")
    monkeypatch.setattr(check, "POSITIVE", [("针灸PVN机制", "ZH-PMC123", ["针灸", "PVN"])])
    monkeypatch.setattr(check, "NEGATIVE", [])
    check.freeze()
    row = json.loads(check.CASES.read_text(encoding="utf-8"))["rows"][0]
    assert row["bodyAnchors"] == ["针灸", "pvn"]
    assert row["supportingEvidence"][0]["quote"] == body
    supported, hit = check.audit_relevance.citation_checks(
        [{"chunkId": "C1", "documentId": "ZH-PMC123", "quote": body}],
        {"C1": {"documentId": "ZH-PMC123", "content": body}}, row)
    assert supported and hit
    with pytest.raises(ValueError, match="refusing to overwrite"):
        check.freeze()


def test_excluded_originals_keep_hash_and_license_validation_without_topic_gate(tmp_path):
    validator = importlib.import_module("validate_expanded_sources")
    xml = article()
    text = governance.parse_article(xml)["text"]
    collector.write_raw(tmp_path / "data/expanded-raw/source.xml", xml)
    collector.write_raw(tmp_path / "data/expanded-raw/source.txt", text)
    record = {
        "documentId": "ZH-PMC123", "sourceUrl": "https://example.org/",
        "title": "Unrelated source", "language": "zh", "corpusRole": "excluded",
        "inclusionDecision": "include", "licenseStatus": "verified",
        "verificationStatus": "verified-full-text", "sourceChecksum": collector.sha(text),
        "rawSourceChecksum": collector.sha(xml),
        "fullTextPath": "data/expanded-raw/source.txt", "rawSourcePath": "data/expanded-raw/source.xml",
        "redistributionStatus": "permissive-verified",
    }
    ledger = tmp_path / "data/expanded-sources.json"
    governance.save_json(ledger, {"records": [record]})
    assert validator.validate(ledger, tmp_path)["errors"] == []
    record["sourceChecksum"] = "wrong"
    governance.save_json(ledger, {"records": [record]})
    assert validator.validate(ledger, tmp_path)["errors"] == ["source checksum mismatch: ZH-PMC123"]
    record["sourceChecksum"] = collector.sha(text)
    record["corpusRole"] = "background"
    governance.save_json(ledger, {"records": [record]})
    assert validator.validate(ledger, tmp_path)["errors"] == ["topic terms not found in full text: ZH-PMC123"]
