// Read-only checks against genuine source metadata; generation stays disabled.
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const {chromium} = require("playwright");

async function main() {
    const root = path.resolve(__dirname, "..");
    const baseURL = process.env.APP_URL || "http://127.0.0.1:8081";
    const output = path.resolve(process.env.QA_OUTPUT || ".tmp/chinese-corpus");
    const ledger = JSON.parse(await fs.readFile(path.join(root, "data/expanded-sources.json"), "utf8"));
    const expected = ledger.records.filter(r => r.language === "zh"
        && r.inclusionDecision === "include" && r.corpusRole !== "excluded");
    await fs.mkdir(output, {recursive: true});
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
    try {
        const page = await browser.newPage({viewport: {width: 1159, height: 764}});
        page.setDefaultTimeout(90000);
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await page.route("**/api/v1/rag/model", route => route.request().method() === "GET"
            ? route.fulfill({contentType: "application/json", body: JSON.stringify({code: "OK", data: {enabled: false}})})
            : route.abort());
        const get = async endpoint => {
            const response = await page.request.get(`${baseURL}${endpoint}`);
            assert.equal(response.status(), 200, endpoint);
            return (await response.json()).data;
        };
        const documents = await get("/api/v1/knowledge/documents?corpus=COMBINED");
        const byId = new Map(documents.map(d => [d.id, d]));
        for (const record of expected) {
            assert.equal(byId.get(record.documentId)?.title, record.title);
            assert.equal(byId.get(record.documentId)?.description, record.abstractOrDescription);
        }
        assert.ok(!byId.has("ZH-PMC7342940"), "mismatched original must remain isolated");
        const baseline = await get("/api/v1/knowledge/overview?corpus=BASELINE");
        assert.equal(baseline.documentCount, 4);
        assert.equal(baseline.chunkCount, 11);
        await page.goto(`${baseURL}/#knowledge`);
        await page.waitForFunction(count =>
            document.querySelectorAll("#documentList [data-document-id]").length === count, documents.length);
        await page.locator("#knowledgeSearch").fill("三参通脉");
        await page.waitForFunction(() =>
            state.filteredChunks.length > 0 && state.filteredChunks.every(c => c.content.includes("三参通脉")
                || c.title.includes("三参通脉")));
        await page.locator('#documentList [data-document-id="ZH-PMC11211798"]').click();
        await page.waitForFunction(() => state.filteredChunks.length > 0
            && state.filteredChunks.every(c => c.documentId === "ZH-PMC11211798"));
        await page.locator("#chunkList [data-chunk-id]").first().click();
        await page.locator("#chunkDetailBoard:not(.hidden)").waitFor();
        assert.match(await page.locator("#chunkDetailId").textContent(), /ZH-PMC11211798/);
        assert.ok((await page.locator("#chunkDetail .source-link").getAttribute("href"))
            .includes("PMC11211798"));
        assert.ok((await page.locator("#chunkDetail").textContent()).includes("仅限非商业研究"));
        for (const width of [1440, 1159, 800, 390, 320]) {
            await page.setViewportSize({width, height: width < 500 ? 844 : 764});
            assert.equal(await page.evaluate(() =>
                document.documentElement.scrollWidth > innerWidth + 1), false, `width=${width}`);
            if ([1159, 390].includes(width)) {
                await page.locator("#chunkDetailBoard").scrollIntoViewIfNeeded();
                await page.screenshot({path: path.join(output, `chinese-source-${width}.png`), fullPage: true});
            }
        }
        assert.deepEqual(errors, []);
        const result = {passed: true, chineseDocuments: expected.length, documentCount: documents.length,
            source: "ZH-PMC11211798", chineseMetadata: true, noncommercialLabel: true,
            isolatedMismatch: true, baselineUnchanged: true, widths: [1440, 1159, 800, 390, 320]};
        await fs.writeFile(path.join(output, "verification.json"), JSON.stringify(result, null, 2) + "\n");
        console.log(JSON.stringify(result));
    } finally {
        await browser.close();
    }
}

main().catch(error => {console.error(error); process.exitCode = 1;});
