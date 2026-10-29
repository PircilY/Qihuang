"""Incremental Europe PMC discovery and verified full-text ingestion."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import difflib
import hashlib
import html
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

from corpus_governance import (ROOT, noncommercial_license, parse_article,
                              redistribution_license, save_json, source_path, topic_review)

API = "https://www.ebi.ac.uk/europepmc/webservices/rest"
TOPICS = {
    "astragalus-heart-failure": ('黄芪', 'astragalus', '"heart failure"'),
    "danshen-coronary": ('丹参', '"salvia miltiorrhiza"', 'coronary'),
    "tcm-hypertension": ('中医药', '"traditional chinese medicine"', 'hypertension'),
    "syndrome-heart-failure": ('气虚血瘀', '"blood stasis"', '"heart failure"'),
    "formula-heart-failure": ('方剂', 'qiliqiangxin', '"heart failure"'),
    "herbal-cardiovascular": ('中药', '"Chinese herbal"', 'cardiovascular'),
}
CHINESE_QUERIES = {
    "astragalus-heart-failure": '(astragalus OR astragaloside OR huangqi OR 黄芪) AND '
                              '("heart failure" OR cardiac OR myocardial OR 心衰 OR 心肌)',
    "danshen-coronary": '("salvia miltiorrhiza" OR danshen OR tanshinone OR 丹参 OR 三参通脉) AND '
                       '(coronary OR myocardial OR atherosclerosis OR cardiovascular OR 心肌 OR 冠心病)',
    "tcm-hypertension": '("Chinese medicine" OR acupuncture OR herbal OR decoction OR 中医 OR 中药) AND '
                        '(hypertension OR hypertensive OR 高血压)',
    "syndrome-heart-failure": '("Chinese medicine" OR syndrome OR prescription OR 血瘀 OR 证候) AND '
                              '("heart failure" OR cardiovascular OR atherosclerosis OR 心衰)',
    "formula-heart-failure": '(prescription OR powder OR decoction OR mixture OR formula OR 方剂 OR 活血) AND '
                            '(cardiac OR myocardial OR "heart failure" OR atherosclerosis OR 心肌)',
    "herbal-cardiovascular": '("Chinese medicine" OR herbal OR acupuncture OR decoction OR 中医 OR 中药) AND '
                            '(cardiovascular OR myocardial OR coronary OR hypertension OR 心血管)',
    "tcm-syndromes": '("Chinese medicine" OR 中医) AND (syndrome OR 证候 OR 证型 OR 辨证)',
    "tcm-materials": '("Chinese medicine" OR "Chinese herbal" OR herbalome OR "herbal material" OR 中药) AND '
                    '(quality OR components OR constituents OR chromatography OR 质量 OR 成分 OR 研究方法)',
    "tcm-formulas": '(decoction OR "Chinese medicine" OR "Chinese herbal" OR acupuncture OR moxibustion '
                    'OR tanshinone OR astragalus OR 方剂 OR 中药)',
}


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha(text: str):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def plain(value: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", html.unescape(value))).strip()


def write_raw(destination: Path, text: str):
    if destination.exists():
        if destination.read_text(encoding="utf-8") != text:
            raise ValueError("unregistered raw path differs; manual review required")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    temporary.write_text(text, encoding="utf-8")
    temporary.replace(destination)


def duplicates(record: dict, records: list[dict]) -> bool:
    for previous in records:
        for key in ("pmcid", "doi", "pmid", "sourceUrl", "sourceChecksum"):
            value = str(record.get(key) or "").strip().casefold()
            if value and value == str(previous.get(key) or "").strip().casefold():
                return True
    return False


def api_url(url: str) -> bool:
    parsed = urllib.parse.urlsplit(url)
    return (parsed.scheme == "https" and parsed.hostname == "www.ebi.ac.uk"
            and parsed.port in {None, 443} and not parsed.username and not parsed.password
            and parsed.path.startswith("/europepmc/webservices/rest/"))


class ApiRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        if not api_url(newurl):
            raise ValueError("unexpected source redirect")
        return super().redirect_request(request, response, code, message, headers, newurl)


class Client:
    def __init__(self, interval=.5, timeout=30, retries=2):
        self.interval, self.timeout, self.retries = interval, timeout, retries
        self.last = 0.0
        self.opener = urllib.request.build_opener(ApiRedirectHandler())

    def get(self, url: str) -> bytes:
        if not api_url(url):
            raise ValueError("collector only accesses the Europe PMC HTTPS API")
        for attempt in range(self.retries + 1):
            time.sleep(max(0, self.interval - (time.monotonic() - self.last)))
            self.last = time.monotonic()
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "QihuangResearchCorpus/1.0"})
                with self.opener.open(request, timeout=self.timeout) as response:
                    if not api_url(response.geturl()):
                        raise ValueError("unexpected source redirect")
                    body = response.read(16_000_001)
                    if len(body) > 16_000_000:
                        raise ValueError("source response too large")
                    return body
            except urllib.error.HTTPError as error:
                if error.code not in {429, 500, 502, 503, 504} or attempt == self.retries:
                    raise
                time.sleep(min(10, 2 ** attempt))
            except (urllib.error.URLError, TimeoutError):
                if attempt == self.retries:
                    raise
                time.sleep(2 ** attempt)
        raise RuntimeError("request retries exhausted")


def discover(client: Client, state: dict, pages: int, state_path: Path,
             languages=("zh", "en"), topics=None):
    known = {item["pmcid"]: item for item in state.setdefault("candidates", [])}
    progress = state.setdefault("queries", {})
    for topic in topics or TOPICS:
        for language in languages:
            if language == "zh":
                query = f"OPEN_ACCESS:Y AND LANG:chi AND TITLE_ABS:({CHINESE_QUERIES[topic]})"
            else:
                zh, en, disease = TOPICS[topic]
                query = f"OPEN_ACCESS:Y AND TITLE_ABS:{en} AND TITLE_ABS:{disease}"
            key = f"{topic}/{language}"
            previous = progress.get(key, {})
            # A changed search must not reuse the old query's cursor/completion.
            if previous.get("query") != query:
                previous = {}
            cursor = previous.get("cursor", "*")
            if previous.get("complete"):
                continue
            for _ in range(pages):
                url = API + "/search?" + urllib.parse.urlencode({
                    "query": query, "format": "json", "pageSize": 50,
                    "resultType": "core", "cursorMark": cursor,
                })
                try:
                    payload = json.loads(client.get(url))
                except (OSError, ValueError) as error:
                    state.setdefault("failures", []).append({"stage": "search", "query": key, "error": str(error), "at": now()})
                    save_json(state_path, state)
                    break
                items = payload.get("resultList", {}).get("result", [])
                for item in items:
                    pmcid = item.get("pmcid", "")
                    if not re.fullmatch(r"PMC\d+", pmcid):
                        continue
                    if pmcid in known:
                        topics = known[pmcid].setdefault("topics", [])
                        if topic not in topics:
                            topics.append(topic)
                        continue
                    candidate = {
                        "pmcid": pmcid, "pmid": item.get("pmid", ""), "doi": item.get("doi", ""),
                        "title": plain(item.get("title", "")),
                        "publicationYear": int(item.get("pubYear") or 0),
                        "abstractOrDescription": plain(item.get("abstractText", "")),
                        "authors": [a.get("fullName") or " ".join(filter(None, (a.get("firstName"), a.get("lastName"))))
                                    for a in item.get("authorList", {}).get("author", [])],
                        "journal": item.get("journalTitle", ""),
                        "language": language, "sourceLanguage": item.get("language", ""),
                        "apiLicense": item.get("license", ""),
                        "documentType": "research article", "topics": [topic],
                        "status": "candidate", "discoveredAt": now(),
                    }
                    known[pmcid] = candidate
                    state["candidates"].append(candidate)
                next_cursor = payload.get("nextCursorMark")
                complete = not items or not next_cursor or next_cursor == cursor
                progress[key] = {"cursor": next_cursor or cursor, "complete": complete,
                                 "hitCount": payload.get("hitCount"), "query": query}
                state["updatedAt"] = now()
                save_json(state_path, state)
                if complete:
                    break
                cursor = next_cursor


def ingest(client: Client, state: dict, state_path: Path, limit: int,
           allow_noncommercial=False, include_background=False, languages=None):
    ledger_path = ROOT / "data/expanded-sources.json"
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    records = ledger["records"]
    counts = Counter()
    included = sum(c.get("status") == "included" and any(
        r["documentId"] == c.get("documentId") and (include_background or r.get("corpusRole") == "core")
        for r in records) for c in state["candidates"])
    candidates = sorted(state["candidates"], key=lambda item: (
        item["language"] != "zh", item["pmcid"]))
    # Round-robin discovery themes so one broad search cannot consume the budget.
    buckets = {topic: [] for topic in CHINESE_QUERIES}
    for candidate in candidates:
        candidate["title"] = plain(candidate["title"])
        candidate["abstractOrDescription"] = plain(candidate.get("abstractOrDescription", ""))
        buckets[candidate.get("topics", [next(iter(TOPICS))])[0]].append(candidate)
    candidates = []
    while any(buckets.values()):
        for bucket in buckets.values():
            if bucket:
                candidates.append(bucket.pop(0))
    for candidate in candidates:
        if included + counts["added"] >= limit:
            break
        if candidate.get("status") in {"included", "rejected", "near-duplicate"}:
            continue
        if languages and candidate["language"] not in languages:
            continue
        if duplicates(candidate, records):
            match = next((r for r in records if r.get("pmcid") == candidate["pmcid"]), None)
            if match and match.get("collectionBatch") == state.get("batchId"):
                candidate.update(status="included", documentId=match["documentId"])
                included += int(include_background or match.get("corpusRole") == "core")
                save_json(state_path, state)
                continue
            candidate.update(status="rejected", reason="already-in-ledger")
            counts["duplicate"] += 1
            save_json(state_path, state)
            continue
        try:
            preliminary = topic_review(candidate, "")
            if preliminary["corpusRole"] != "core" and candidate["language"] != "zh":
                raise ValueError("title does not establish both TCM and cardiovascular topics")
            xml = client.get(f"{API}/{candidate['pmcid']}/fullTextXML").decode("utf-8")
            article = parse_article(xml)
            source_title = candidate["title"]
            candidate["title"] = article["title"] or candidate["title"]
            candidate["sourceTranslatedTitles"] = article["translatedTitles"]
            license_url = redistribution_license(article["licenses"])
            nc_url = noncommercial_license(article["licenses"]) if allow_noncommercial else None
            if not (license_url or nc_url):
                raise ValueError("no permissive license verified in full-text XML")
            han = len(re.findall(r"[\u3400-\u9fff]", article["text"])) / len(article["text"])
            language = "zh" if han >= .1 else "en"
            if languages and language not in languages:
                raise ValueError("full-text language outside requested scope")
            if article["language"] in {"zh", "en"} and article["language"] != language:
                raise ValueError("body language conflicts with XML language")
            abstracts = article["abstracts"]
            if language == "zh":
                chinese = [text for text in abstracts if len(re.findall(r"[\u3400-\u9fff]", text)) >= 20]
                if chinese:
                    candidate["abstractOrDescription"] = chinese[0]
            review = topic_review(candidate, article["text"])
            background_ok = (include_background and review["corpusRole"] == "background"
                             and review["topicReview"]["titleTcmTerms"]
                             and review["topicReview"]["bodyTcmTerms"])
            if review["corpusRole"] != "core" and not background_ok:
                raise ValueError("metadata does not establish both TCM and cardiovascular topics")
            digest = sha(article["text"])
            if duplicates({"sourceChecksum": digest}, records):
                raise ValueError("duplicate full-text checksum")
            title = candidate["title"]
            near = [r["documentId"] for r in records
                    if difflib.SequenceMatcher(None, title.casefold(), r["title"].casefold()).ratio() >= .94]
            if near:
                candidate.update(status="near-duplicate", reason="title similarity >= 0.94", matches=near)
                counts["nearDuplicate"] += 1
                continue
            stem = candidate["pmcid"].lower()
            raw_path, text_path = f"data/expanded-raw/{stem}.xml", f"data/expanded-raw/{stem}.txt"
            write_raw(source_path(raw_path, ROOT), xml)
            write_raw(source_path(text_path, ROOT), article["text"])
            record = {key: candidate[key] for key in (
                "pmcid", "pmid", "doi", "title", "publicationYear", "abstractOrDescription", "authors", "documentType")}
            record.update({
                "documentId": f"{language.upper()}-{candidate['pmcid']}", "language": language,
                "sourceName": "Europe PMC / PubMed Central",
                "sourceUrl": f"https://pmc.ncbi.nlm.nih.gov/articles/{candidate['pmcid']}/",
                "fullTextPath": text_path, "rawSourcePath": raw_path,
                "sourceChecksum": digest, "rawSourceChecksum": sha(xml), "accessedAt": now(),
                "license": "cc by-nc" if not license_url else "cc0" if "publicdomain/zero" in license_url else "public domain"
                if license_url == "public-domain-us-government" else "cc by",
                "licenseStatus": "verified", "verificationStatus": "verified-full-text",
                "licenseEvidence": article["licenses"], "redistributionLicenseUrl": license_url,
                "redistributionStatus": "permissive-verified" if license_url else "restricted-review-required",
                "noncommercialLicenseUrl": nc_url if not license_url else None,
                "usageScope": "noncommercial-research-only" if not license_url else "permissive-with-attribution",
                "licenseEvidenceSource": f"{API}/{candidate['pmcid']}/fullTextXML",
                "licenseEvidenceAccessedAt": now(),
                "inclusionDecision": "include",
                "inclusionReason": "verified source title/body and XML license; noncommercial sources excluded from public delivery",
                "topicTags": (["中医药", "心血管"] if review["corpusRole"] == "core" else ["中医药"])
                if language == "zh" else ["traditional Chinese medicine", "cardiovascular"],
                "textExtractionVersion": 2, **review,
                "sourceSearchTitle": source_title, "sourceTranslatedTitles": article["translatedTitles"],
                "documentType": article["documentType"] or record["documentType"],
                "collectionBatch": state["batchId"], "collectionTopics": candidate.get("topics", []),
            })
            records.append(record)
            ledger["updatedAt"] = now()
            save_json(ledger_path, ledger)
            candidate.update(status="included", documentId=record["documentId"], includedAt=now())
            counts["added"] += 1
            print(json.dumps({"added": counts["added"], "id": record["documentId"], "title": title}, ensure_ascii=False), flush=True)
        except (OSError, ValueError) as error:
            candidate.update(status="failed" if isinstance(error, OSError) else "rejected",
                             reason=str(error), attemptedAt=now())
            counts[candidate["status"]] += 1
        finally:
            state["updatedAt"] = now()
            save_json(state_path, state)
    batch_core = sum(r.get("collectionBatch") == state.get("batchId") and r.get("corpusRole") == "core"
                     for r in records)
    state["lastIngestion"] = {"at": now(), "counts": dict(counts), "includedTotal": included + counts["added"],
                              "coreIncludedTotal": batch_core, "backgroundCountsTowardLimit": include_background}
    save_json(state_path, state)
    print(json.dumps(state["lastIngestion"], ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--ingest", action="store_true")
    parser.add_argument("--pages", type=int, default=2)
    parser.add_argument("--limit", type=int, default=40)
    parser.add_argument("--state", type=Path, default=ROOT / "runtime/corpus-collection/state.json")
    parser.add_argument("--language", choices=("zh", "en", "all"), default="all")
    parser.add_argument("--topics", nargs="+", choices=tuple(CHINESE_QUERIES))
    parser.add_argument("--allow-noncommercial", action="store_true",
                        help="Explicit BY-NC only, for noncommercial research; excluded from public delivery")
    parser.add_argument("--include-background", action="store_true",
                        help="Also admit title/body-supported TCM background; never unrelated sources")
    parser.add_argument("--retry-topic-rejections", action="store_true",
                        help="Recheck topic-only rejections after source-title parsing changes; retain prior attempts")
    args = parser.parse_args()
    if not (args.discover or args.ingest) or not 1 <= args.limit <= 50 or not 1 <= args.pages <= 10:
        parser.error("choose discover/ingest; limit 1..50 and pages 1..10")
    if not args.state.resolve().is_relative_to((ROOT / "runtime").resolve()):
        parser.error("candidate state must stay in runtime")
    languages = ("zh", "en") if args.language == "all" else (args.language,)
    if "en" in languages and any(topic not in TOPICS for topic in args.topics or []):
        parser.error("TCM background discovery topics require --language zh")
    state = json.loads(args.state.read_text(encoding="utf-8")) if args.state.exists() else {"schemaVersion": 1, "candidates": []}
    state.setdefault("batchId", now())
    state["requestedLanguages"] = list(languages)
    state["noncommercialOptIn"] = args.allow_noncommercial
    state["includeBackground"] = args.include_background
    if args.retry_topic_rejections:
        for candidate in state["candidates"]:
            if candidate.get("status") == "rejected" and candidate.get("reason") == "metadata does not establish both TCM and cardiovascular topics":
                candidate.setdefault("previousAttempts", []).append({
                    key: candidate[key] for key in ("status", "reason", "attemptedAt") if key in candidate
                })
                candidate["status"] = "candidate"
    client = Client()
    if args.discover:
        discover(client, state, args.pages, args.state, languages, args.topics)
        print(json.dumps({"candidates": len(state["candidates"])}), flush=True)
    if args.ingest:
        ingest(client, state, args.state, args.limit, args.allow_noncommercial, args.include_background, languages)


if __name__ == "__main__":
    main()
