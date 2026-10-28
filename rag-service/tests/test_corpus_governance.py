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
