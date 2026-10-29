// Read-only overview checks against the local API, plus browser-only metadata edge cases.
const assert = require("node:assert/strict");
const path = require("node:path");
const fs = require("node:fs/promises");
const {chromium} = require("playwright");
const {startPreview} = require("./ui-preview.cjs");

async function main() {
    const output = path.resolve(process.env.QA_OUTPUT || ".tmp/overview-registration");
    await fs.mkdir(output, {recursive: true});
    const preview = await startPreview({apiURL: process.env.APP_URL || "http://127.0.0.1:8081"});
    let browser;
    try {
        browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
        for (const width of [1440, 1354, 800, 390, 320]) {
            const page = await browser.newPage({viewport: {width, height: 900}});
            const errors = [];
            page.on("pageerror", error => errors.push(error.message));
            await page.goto(`${preview.url}/#overview`);
            await page.waitForFunction(() => document.querySelectorAll("#overviewRegistration .registration-row").length === 3);
            assert.equal(await page.locator("#knowledgeCorpus, #workbenchCorpus").count(), 0);
            for (const corpus of ["COMBINED", "BASELINE", "EXPANDED"]) {
                if (corpus !== "COMBINED") {
                    await page.evaluate(async value => {
                        state.activeCorpus = value;
                        await refreshKnowledgeData();
                    }, corpus);
                }
                const result = await page.evaluate(() => {
                    const summary = summarizeRegistration(state.documents);
                    const left = document.querySelector(".corpus-layout").getBoundingClientRect();
                    const right = document.querySelector(".evidence-board .status-line").getBoundingClientRect();
                    return {
                        count: state.documents.length,
                        linked: summary.linked,
                        displayedLinked: document.querySelector("#overviewLinkedDocumentCount").textContent,
                        years: [...document.querySelectorAll("#overviewSourceDistribution .source-row-head strong")]
                            .map(el => Number(el.textContent.replace(/[^\d]/g, ""))),
                        oldRing: !!document.querySelector("#evidenceRing"),
                        oldClaims: /A 级占比|高等级证据占比|可用于检索|research article/.test(
                            document.querySelector(".overview-grid").textContent),
                        bottomDelta: Math.abs(left.bottom - right.bottom),
                        overflow: document.documentElement.scrollWidth > innerWidth,
                        height: document.documentElement.scrollHeight
                    };
                });
                assert.equal(result.oldRing, false);
                assert.equal(result.oldClaims, false);
                assert.equal(result.displayedLinked, result.linked.toLocaleString("zh-CN"));
                assert.equal(result.years.reduce((sum, count) => sum + count, 0), result.count);
                assert.equal(result.overflow, false);
                assert.ok(result.height < 3000);
                if (width > 720) assert.ok(result.bottomDelta < 1, JSON.stringify(result));
                await page.screenshot({path: path.join(output, `${corpus}-${width}.png`), fullPage: true});
            }
            assert.deepEqual(errors, []);
            await page.close();
        }
        const page = await browser.newPage({viewport: {width: 1354, height: 900}});
        const cases = [
            {id: "missing", publicationYear: 0, sourceUrl: "javascript:alert(1)",
                checksum: "not-a-hash", accessedAt: "invalid-date", evidenceLevel: null},
            {id: "valid", publicationYear: 2025, sourceUrl: "https://www.who.int/example",
                checksum: "a".repeat(64), accessedAt: "2026-10-28T00:00:00Z", evidenceLevel: "C"}
        ];
        await page.route("**/api/v1/knowledge/documents?corpus=COMBINED", route => route.fulfill({
            contentType: "application/json", body: JSON.stringify({code: "OK", data: cases})
        }));
        await page.goto(`${preview.url}/#overview`);
        await page.waitForFunction(() => document.querySelector("#overviewMissingYearCount").textContent === "1 篇");
        assert.equal(await page.locator("#overviewYearRange").textContent(), "2025");
        assert.equal(await page.locator("#overviewPlatformCount").textContent(), "1");
        assert.equal(await page.locator("#overviewLinkedDocumentCount").textContent(), "1");
        assert.equal(await page.locator("#overviewUnratedDocumentCount").textContent(), "1 篇");
        assert.match(await page.locator("#overviewProvenanceText").textContent(), /WHO/);
        assert.deepEqual(await page.locator("#overviewRegistration dd").allTextContents(),
            ["1 / 2 篇", "1 / 2 篇", "1 / 2 篇"]);
        await page.evaluate(() => {
            state.documents = [];
            renderOverview();
        });
        assert.equal(await page.locator("#overviewYearRange").textContent(), "未登记");
        assert.equal(await page.locator("#overviewLastAccessedAt").textContent(), "未登记");
        assert.equal(await page.locator("#overviewSourceDistribution").textContent(), "暂无文档");
        assert.deepEqual(await page.locator("#overviewRegistration dd").allTextContents(),
            ["暂无文档", "暂无文档", "暂无文档"]);
        await page.close();
        console.log("Overview checks passed: unified view and historical scopes, 5 widths, metadata edge cases.");
    } finally {
        if (browser) await browser.close();
        await preview.close();
    }
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
