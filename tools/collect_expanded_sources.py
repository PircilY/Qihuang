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

from corpus_governance import ROOT, parse_article, redistribution_license, save_json, source_path, topic_review

API = "https://www.ebi.ac.uk/europepmc/webservices/rest"
TOPICS = {
    "astragalus-heart-failure": ('黄芪', 'astragalus', '"heart failure"'),
    "danshen-coronary": ('丹参', '"salvia miltiorrhiza"', 'coronary'),
    "tcm-hypertension": ('中医药', '"traditional chinese medicine"', 'hypertension'),
    "syndrome-heart-failure": ('气虚血瘀', '"blood stasis"', '"heart failure"'),
    "formula-heart-failure": ('方剂', 'qiliqiangxin', '"heart failure"'),
    "herbal-cardiovascular": ('中药', '"Chinese herbal"', 'cardiovascular'),
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


def discover(client: Client, state: dict, pages: int, state_path: Path):
    known = {item["pmcid"]: item for item in state.setdefault("candidates", [])}
    progress = state.setdefault("queries", {})
    for topic, (zh, en, disease) in TOPICS.items():
        for language in ("zh", "en"):
            term = f'("{zh}" OR {en})' if language == "zh" else en
            query = f"OPEN_ACCESS:Y AND TITLE_ABS:{term} AND TITLE_ABS:{disease}"
            if language == "zh":
                query += " AND LANG:chi"
            key = f"{topic}/{language}"
            cursor = progress.get(key, {}).get("cursor", "*")
            if progress.get(key, {}).get("complete"):
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
                        "language": language, "apiLicense": item.get("license", ""),
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


def ingest(client: Client, state: dict, state_path: Path, limit: int):
    ledger_path = ROOT / "data/expanded-sources.json"
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    records = ledger["records"]
    counts = Counter()
    included = sum(c.get("status") == "included" and any(
        r["documentId"] == c.get("documentId") and r.get("corpusRole") == "core"
        for r in records) for c in state["candidates"])
    candidates = sorted(state["candidates"], key=lambda item: (
        item["language"] != "zh", item["pmcid"]))
    # Round-robin discovery themes so one broad search cannot consume the budget.
    buckets = {topic: [] for topic in TOPICS}
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
        if duplicates(candidate, records):
            match = next((r for r in records if r.get("pmcid") == candidate["pmcid"]), None)
            if match and match.get("collectionBatch") == state.get("batchId"):
                candidate.update(status="included", documentId=match["documentId"])
                included += int(match.get("corpusRole") == "core")
                save_json(state_path, state)
                continue
            candidate.update(status="rejected", reason="already-in-ledger")
            counts["duplicate"] += 1
            save_json(state_path, state)
            continue
        try:
            if topic_review(candidate, "")["corpusRole"] != "core":
                raise ValueError("title does not establish both TCM and cardiovascular topics")
            xml = client.get(f"{API}/{candidate['pmcid']}/fullTextXML").decode("utf-8")
            article = parse_article(xml)
            candidate["title"] = article["title"] or candidate["title"]
            license_url = redistribution_license(article["licenses"])
            if not license_url:
                raise ValueError("no permissive license verified in full-text XML")
            review = topic_review(candidate, article["text"])
            if review["corpusRole"] != "core":
                raise ValueError("metadata does not establish both TCM and cardiovascular topics")
            digest = sha(article["text"])
            if duplicates({"sourceChecksum": digest}, records):
                raise ValueError("duplicate full-text checksum")
            han = len(re.findall(r"[\u3400-\u9fff]", article["text"])) / len(article["text"])
            language = "zh" if han >= .1 else "en"
            if language != candidate["language"]:
                raise ValueError("body language does not match the search language")
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
                "license": "cc0" if "publicdomain/zero" in license_url else "public domain"
                if license_url == "public-domain-us-government" else "cc by",
                "licenseStatus": "verified", "verificationStatus": "verified-full-text",
                "licenseEvidence": article["licenses"], "redistributionLicenseUrl": license_url,
                "redistributionStatus": "permissive-verified",
                "inclusionDecision": "include",
                "inclusionReason": "dual-topic source metadata; structured full body; permissive XML license",
                "topicTags": ["中医药", "心血管"] if language == "zh" else ["traditional Chinese medicine", "cardiovascular"],
                "textExtractionVersion": 2, **review,
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
    state["lastIngestion"] = {"at": now(), "counts": dict(counts), "coreIncludedTotal": included + counts["added"]}
    save_json(state_path, state)
    print(json.dumps(state["lastIngestion"], ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--ingest", action="store_true")
    parser.add_argument("--pages", type=int, default=2)
    parser.add_argument("--limit", type=int, default=40)
    parser.add_argument("--state", type=Path, default=ROOT / "runtime/corpus-collection/state.json")
    args = parser.parse_args()
    if not (args.discover or args.ingest) or not 1 <= args.limit <= 50 or not 1 <= args.pages <= 10:
        parser.error("choose discover/ingest; limit 1..50 and pages 1..10")
    if not args.state.resolve().is_relative_to((ROOT / "runtime").resolve()):
        parser.error("candidate state must stay in runtime")
    state = json.loads(args.state.read_text(encoding="utf-8")) if args.state.exists() else {"schemaVersion": 1, "candidates": []}
    state.setdefault("batchId", now())
    client = Client()
    if args.discover:
        discover(client, state, args.pages, args.state)
        print(json.dumps({"candidates": len(state["candidates"])}), flush=True)
    if args.ingest:
        ingest(client, state, args.state, args.limit)


if __name__ == "__main__":
    main()
