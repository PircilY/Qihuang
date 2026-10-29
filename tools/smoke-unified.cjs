// Read-only live checks. Import/persistence is tested separately on an isolated store.
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const {chromium} = require("playwright");

async function main() {
    const baseURL = process.env.APP_URL || "http://127.0.0.1:8081";
    const output = path.resolve(process.env.QA_OUTPUT || ".tmp/unified-corpus");
    await fs.mkdir(output, {recursive: true});
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
    try {
        const page = await browser.newPage({viewport: {width: 1159, height: 764}});
        page.setDefaultTimeout(60000);
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        // Disable generation in this browser only; leave saved model configuration untouched.
        await page.route("**/api/v1/rag/model", route => route.request().method() === "GET"
            ? route.fulfill({contentType: "application/json", body: JSON.stringify({code: "OK", data: {enabled: false}})})
            : route.abort());
        const get = async endpoint => {
            const response = await page.request.get(`${baseURL}${endpoint}`);
            assert.equal(response.status(), 200, endpoint);
            return (await response.json()).data;
        };
        const corpora = await get("/api/v1/knowledge/corpora");
        const combined = corpora.find(item => item.id === "COMBINED");
        assert.ok(combined);
        assert.equal(combined.documentCount, corpora[0].documentCount + corpora[1].documentCount);
        assert.equal(combined.chunkCount, corpora[0].chunkCount + corpora[1].chunkCount);
        await page.goto(`${baseURL}/#knowledge`);
        await page.waitForFunction(count =>
            document.querySelectorAll("#documentList [data-document-id]").length === count, combined.documentCount);
        assert.equal(await page.locator("#knowledgeCorpus, #workbenchCorpus, #workbenchCorpusNotice").count(), 0);
        assert.equal(await page.evaluate(() => state.activeCorpus), "COMBINED");
        const baselineChunks = await get("/api/v1/knowledge/chunks?corpus=BASELINE");
        const expandedChunks = await get("/api/v1/knowledge/chunks?corpus=EXPANDED");
        for (const chunk of [baselineChunks[0], expandedChunks[0]]) {
            await page.locator("#knowledgeSearch").fill(chunk.id);
            await page.waitForFunction(id =>
                document.querySelector(`#chunkList [data-chunk-id="${CSS.escape(id)}"]`), chunk.id);
            await page.locator(`#chunkList [data-chunk-id="${chunk.id}"]`).click();
            assert.equal(await page.locator("#chunkDetailId").textContent(), `${chunk.id} · ${chunk.documentId}`);
            assert.match(await page.locator("#chunkDetail .source-link").getAttribute("href"), /^https?:\/\//);
            await page.locator("#resetKnowledgeFilters").click();
        }
        async function view(name) {
            await page.locator(`[data-view="${name}"]`).click();
            await page.locator(`#view-${name}.active`).waitFor();
        }
        async function ask(question) {
            await page.locator("#questionInput").fill(question);
            const waiting = page.waitForResponse(response => response.url().endsWith("/api/v1/rag/chat"));
            await page.locator("#askButton").click();
            const response = await waiting;
            assert.equal(response.status(), 200);
            assert.equal(response.request().postDataJSON().corpus, "COMBINED");
            assert.equal(response.request().postDataJSON().useModel, false);
            await page.waitForFunction(() => !document.querySelector("#askButton").disabled);
            return (await response.json()).data;
        }
        await view("workbench");
        await page.locator('#versionRail [data-version="V1_VECTOR"]').click();
        const response = await ask("甘草有哪些严重不良反应？");
        assert.ok(response.citations.length > 0);
        assert.ok(response.retrieval.notes.includes("corpus=COMBINED"));
        await page.locator("#answerStage .citation-actions button").first().click();
        await page.locator("#chunkDetailBoard:not(.hidden)").waitFor();
        assert.equal(await page.locator("#chunkDetailId").textContent(),
            `${response.citations[0].chunkId} · ${response.citations[0].documentId}`);
        await view("workbench");
        const waiting = page.waitForResponse(r => r.url().endsWith("/api/v1/rag/compare"));
        await page.locator("#compareButton").click();
        const comparison = await waiting;
        assert.equal(comparison.status(), 200);
        assert.equal(comparison.request().postDataJSON().corpus, "COMBINED");
        await page.waitForFunction(() => !document.querySelector("#compareButton").disabled);
        assert.equal(await page.locator("#compareGrid .compare-result").count(), 4);
        await page.locator('#versionRail [data-version="V3_GUARDED"]').click();
        const emergency = await ask("胸痛持续不缓解，怎么办？");
        assert.equal(emergency.refused, true);
        assert.equal(emergency.citations.length, 0);
        for (const width of [1440, 1159, 800, 390, 320]) {
            await page.setViewportSize({width, height: width < 500 ? 844 : 764});
            for (const name of ["knowledge", "workbench", "overview", "pipeline"]) {
                await view(name);
                await page.evaluate(() => window.scrollTo({top: 0, behavior: "instant"}));
                const layout = await page.evaluate(() => ({
                    overflow: document.documentElement.scrollWidth > innerWidth + 1,
                    overlappingFilters: [...document.querySelectorAll("#view-knowledge .filter-bar > *")]
                        .map(el => el.getBoundingClientRect()).some((box, index, boxes) => boxes.slice(index + 1)
                            .some(other => box.left < other.right && box.right > other.left
                                && box.top < other.bottom && box.bottom > other.top))
                }));
                assert.equal(layout.overflow, false, `${name}: ${width}`);
                if (name === "knowledge") assert.equal(layout.overlappingFilters, false, `${name}: ${width}`);
                if ([1159, 390].includes(width)) {
                    await page.screenshot({path: path.join(output, `${name}-${width}.png`), fullPage: true});
                }
            }
        }
        assert.deepEqual(errors, []);
        console.log(`PASS: one knowledge base, ${combined.documentCount} documents / ${combined.chunkCount} chunks, scoped lookups, chat, citations, comparison, refusal, 5 widths.`);
    } finally {
        await browser.close();
    }
}

main().catch(error => {console.error(error); process.exitCode = 1;});
