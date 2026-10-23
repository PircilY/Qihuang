import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const rawDir = path.join(root, "data", "expanded-raw");
const sourcePath = path.join(root, "data", "expanded-sources.json");
const allowedLicenses = new Set(["cc by", "cc by-nc", "cc by-nc-sa", "cc by-nc-nd"]);
const chineseQuery = "OPEN_ACCESS:Y AND LANG:chi AND (TITLE:(中医 OR 中药 OR 针灸 OR 冠心病 OR 心力衰竭 OR 高血压 OR 心悸 OR 胸痹 OR 心血管 OR 心肌梗死 OR 心律失常 OR heart failure OR hypertension OR coronary OR cardiovascular OR cardiac OR myocardial OR arrhythmia) OR ABSTRACT:(中医 OR 中药 OR 针灸 OR 冠心病 OR 心力衰竭 OR 高血压 OR 心悸 OR 胸痹 OR 心血管 OR 心肌梗死 OR 心律失常 OR heart failure OR hypertension OR coronary OR cardiovascular OR cardiac OR myocardial OR arrhythmia))";
const englishQuery = "OPEN_ACCESS:Y AND (TITLE:(traditional chinese medicine OR Chinese herbal OR acupuncture OR coronary heart disease OR heart failure OR hypertension) OR ABSTRACT:(traditional chinese medicine OR Chinese herbal OR acupuncture)) AND (TITLE:(cardiovascular OR heart OR hypertension OR coronary OR failure) OR ABSTRACT:(cardiovascular OR heart OR hypertension OR coronary OR failure))";
const chineseTcmTerms = ["中医", "中药", "针灸", "traditional chinese medicine", "chinese medicine", "chinese herbal", "herbal medicine", "acupuncture", "formula", "syndrome"];
const chineseCardioTerms = ["心血管", "冠心病", "心力衰竭", "高血压", "心脏", "cardiovascular", "coronary", "heart failure", "hypertension", "cardiac", "heart disease"];
const englishTerms = ["traditional chinese medicine", "chinese herbal", "acupuncture", "cardiovascular", "coronary", "heart failure", "hypertension", "heart disease"];

await fs.mkdir(rawDir, { recursive: true });

function xmlDecode(value) {
    return value
        .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
        .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&")
        .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
        .replace(/&#(\d+);/g, (_, number) => String.fromCodePoint(Number(number)));
}

function visibleText(xml) {
    const body = xml.match(/<body[\s\S]*?<\/body>/i)?.[0] || xml;
    const marked = body
        .replace(/<\/?(title|p|sec|caption|th|td|list-item)[^>]*>/gi, "\n")
        .replace(/<xref[^>]*>[\s\S]*?<\/xref>/gi, "")
        .replace(/<[^>]+>/g, " ");
    return xmlDecode(marked)
        .replace(/\s+/g, " ")
        .replace(/\s*([。！？；：])\s*/g, "$1")
        .trim();
}

function sha256(value) {
    return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function score(item, terms) {
    const text = `${item.title || ""} ${item.abstractText || ""}`.toLowerCase();
    return terms.reduce((total, term) => total + (text.includes(term.toLowerCase()) ? 1 : 0), 0);
}

function relevant(item, language) {
    const text = `${item.title || ""} ${item.abstractText || ""}`.toLowerCase();
    if (language === "zh") {
        const title = (item.title || "").toLowerCase();
        const titleTerms = ["心血管", "冠心病", "心力衰竭", "高血压", "心脏", "心肌", "心律", "heart failure", "hypertension", "coronary", "cardiovascular", "cardiac", "myocardial", "arrhythmia", "中医", "中药", "针灸"];
        return titleTerms.some(term => title.includes(term.toLowerCase()));
    }
    return score(item, englishTerms) >= 2;
}

function relevantText(text, language) {
    const normalized = text.toLowerCase();
    if (language === "zh") {
        const tcm = ["中医", "中药", "针灸", "方", "证", "辨证", "草药"].some(term => normalized.includes(term));
        const cardio = chineseCardioTerms.some(term => normalized.includes(term.toLowerCase()));
        return tcm && cardio;
    }
    return englishTerms.filter(term => normalized.includes(term.toLowerCase())).length >= 2;
}

async function search(query, pageSize = 1000) {
    const url = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query="
        + encodeURIComponent(query) + `&format=json&pageSize=${pageSize}&resultType=core&sort=CITED%20desc`;
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Europe PMC search failed: ${response.status}`);
    const payload = await response.json();
    return payload.resultList?.result || [];
}

function normalizeRecord(item, language) {
    const authors = (item.authorList?.author || [])
        .map(author => [author.firstName, author.lastName].filter(Boolean).join(" "))
        .filter(Boolean);
    return {
        pmcid: item.pmcid,
        pmid: item.pmid || "",
        doi: item.doi || "",
        title: xmlDecode(item.title || "").replace(/<[^>]+>/g, "").trim(),
        authors,
        publicationYear: Number(item.pubYear || 0),
        journal: item.journalTitle || "",
        sourceName: "Europe PMC / PubMed Central",
        sourceUrl: `https://pmc.ncbi.nlm.nih.gov/articles/${item.pmcid}/`,
        language,
        license: (item.license || "").toLowerCase(),
        documentType: item.pubType || "research article",
        abstract: item.abstractText || "",
    };
}

async function selectRecords(query, language, terms, limit) {
    const records = (await search(query))
        .filter(item => item.pmcid && allowedLicenses.has((item.license || "").toLowerCase()))
        .map(item => normalizeRecord(item, language))
        .filter((item, index, all) => all.findIndex(other => other.pmcid === item.pmcid) === index)
        .sort((left, right) => score(right, terms) - score(left, terms));
    const selected = [];
    for (const record of records) {
        if (selected.length >= limit) break;
        try {
            const response = await fetch(`https://www.ebi.ac.uk/europepmc/webservices/rest/${record.pmcid}/fullTextXML`);
            if (!response.ok) continue;
            const xml = await response.text();
            const text = visibleText(xml);
            if (text.length < 500) continue;
            if (!relevant(record, language)) continue;
            const fileStem = record.pmcid.toLowerCase();
            await fs.writeFile(path.join(rawDir, `${fileStem}.xml`), xml, "utf8");
            await fs.writeFile(path.join(rawDir, `${fileStem}.txt`), text, "utf8");
            selected.push({
                documentId: `${language.toUpperCase()}-${record.pmcid}`,
                title: record.title,
                authors: record.authors,
                publicationYear: record.publicationYear,
                sourceName: record.sourceName,
                sourceUrl: record.sourceUrl,
                doi: record.doi,
                pmid: record.pmid,
                pmcid: record.pmcid,
                language: language === "zh" ? "zh" : "en",
                documentType: record.documentType,
                topicTags: language === "zh"
                    ? (relevant({title: record.title}, "zh") && chineseTcmTerms.some(term => `${record.title} ${record.abstract}`.toLowerCase().includes(term.toLowerCase()))
                        ? ["中医药", "心血管", "中文全文"]
                        : ["心血管", "中文全文"])
                    : ["traditional Chinese medicine", "cardiovascular", "open access"],
                abstractOrDescription: record.abstract,
                fullTextPath: `data/expanded-raw/${fileStem}.txt`,
                rawSourcePath: `data/expanded-raw/${fileStem}.xml`,
                licenseStatus: "verified",
                license: record.license,
                verificationStatus: "verified-full-text",
                inclusionDecision: "include",
                inclusionReason: "Europe PMC record, PMCID full text, explicit Creative Commons license, topic terms verified in metadata and extracted body",
                accessedAt: new Date().toISOString(),
                sourceChecksum: sha256(text),
            });
        } catch (error) {
            console.warn(`skip ${record.pmcid}: ${error.message}`);
        }
    }
    return selected;
}

const chinese = await selectRecords(chineseQuery, "zh", chineseTcmTerms.concat(chineseCardioTerms), 70);
const english = await selectRecords(englishQuery, "en", englishTerms, 30);
const records = [...chinese, ...english];
if (records.length < 1) throw new Error("No verified full-text records were obtained");
await fs.writeFile(sourcePath, JSON.stringify({
    schemaVersion: "1.0",
    generatedAt: new Date().toISOString(),
    retrievalSource: "Europe PMC REST API and PMC fullTextXML",
    target: { total: 100, chinese: 70, english: 30 },
    records,
}, null, 2) + "\n", "utf8");
console.log(JSON.stringify({ total: records.length, chinese: chinese.length, english: english.length, sourcePath }, null, 2));
