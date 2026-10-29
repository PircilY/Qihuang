const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const {chromium} = require("playwright");

const baseURL = process.env.APP_URL || "http://127.0.0.1:8081";
const output = path.resolve(process.env.QA_OUTPUT || ".tmp/ui");

async function main() {
    await fs.mkdir(output, {recursive: true});
    const browser = await chromium.launch({
        executablePath: process.env.CHROME_PATH || undefined,
        headless: true
    });
    try {
        const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
        await page.context().request.delete(`${baseURL}/api/v1/rag/model`);
        const overview = (await (await page.context().request.get(
            `${baseURL}/api/v1/knowledge/overview?corpus=COMBINED`)).json()).data;
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await page.goto(baseURL);
        await page.waitForFunction(count =>
            document.querySelector("#overviewDocumentCount").textContent === String(count), overview.documentCount);
        assert.equal(await page.locator("#knowledgeCorpus, #workbenchCorpus").count(), 0);
        const views = ["overview", "knowledge", "workbench", "evaluation", "pipeline"];
        async function view(name) {
            await page.locator(`[data-view="${name}"]`).click();
            await page.locator(`#view-${name}.active`).waitFor();
        }
        async function ask(question) {
            await page.locator("#questionInput").fill(question);
            const response = page.waitForResponse(r => r.url().endsWith("/api/v1/rag/chat"));
            await page.locator("#askButton").click();
            const body = await (await response).json();
            await page.waitForFunction(() => !document.querySelector("#askButton").disabled);
            return body.data;
        }
        await view("knowledge");
        assert.equal(await page.locator("#documentList [data-document-id]").count(), overview.documentCount);
        await page.locator("#knowledgeSearch").fill("NoSuchTopic123456");
        await page.waitForFunction(() => document.querySelector("#chunkResultCount").textContent.includes("0"));
        await page.locator("#resetKnowledgeFilters").click();
        await page.locator("#chunkList [data-chunk-id]").first().click();
        await page.locator("#chunkDetailBoard:not(.hidden)").waitFor();
        const source = page.locator("#chunkDetail .source-link");
        assert.match(await source.getAttribute("href"), /^https:\/\/www\.(who\.int|nccih\.nih\.gov)\//);
        await view("workbench");
        const answer = await ask("甘草有哪些严重不良反应？");
        assert.equal(answer.refused, false);
        assert.ok(answer.citations.some(c => c.chunkId === "NCCIH-LICORICE-001"));
        assert.ok(answer.retrieval.notes.includes("engine=llama-index"));
        assert.ok((await page.locator(".answer-summary").innerText()).includes("COMBINED"));
        assert.ok((await page.locator(".answer-summary").innerText()).includes("hash"));
        await page.screenshot({path: path.join(output, "answer-desktop.png"), fullPage: true});
        await page.route("**/api/v1/rag/chat", route => route.fulfill({
            status: 200, contentType: "application/json",
            body: JSON.stringify({code: "OK", data: {...answer,
                retrieval: {...answer.retrieval, engine: "java-local", notes: [
                    "engine=java-local", "fallback=local", "已使用本地引擎", "corpus=COMBINED", "embedding-backend=hash"
                ]}}})
        }));
        await ask("高血压没有症状也需要检查血压吗？");
        assert.match(await page.locator(".fallback-notice").innerText(), /已使用本地引擎/);
        assert.match(await page.locator(".answer-summary").innerText(), /java-local/);
        await page.screenshot({path: path.join(output, "local-fallback.png"), fullPage: true});
        await page.unroute("**/api/v1/rag/chat");
        const combined = await ask("高血压没有症状也需要检查血压吗？");
        assert.ok(combined.retrieval.notes.includes("corpus=COMBINED"));

        const comparison = page.waitForResponse(r => r.url().endsWith("/api/v1/rag/compare"));
        await page.locator("#compareButton").click();
        await comparison;
        await page.waitForFunction(() => !document.querySelector("#compareButton").disabled);
        assert.equal(await page.locator("#compareGrid .compare-result").count(), 4);
        assert.ok(await page.locator("#compareGrid .citation-item").count() > 0);
        const refused = await ask("胸痛持续不缓解，怎么办？");
        assert.equal(refused.refused, true);
        assert.equal(refused.citations.length, 0);

        await page.route("**/api/v1/rag/chat", route => route.fulfill({
            status: 503, contentType: "application/json",
            body: JSON.stringify({code: "RAG_SERVICE_UNAVAILABLE", message: "检索服务暂不可用"})
        }));
        await ask("高血压的症状是什么？");
        assert.ok(await page.locator("#copyAnswerButton").evaluate(el => el.classList.contains("hidden")));
        await page.unroute("**/api/v1/rag/chat");
        assert.equal((await ask("高血压患者没有症状也需要检查血压吗？")).refused, false);

        await view("evaluation");
        await page.route("**/api/v1/evaluations/run", route => route.fulfill({
            status: 503, contentType: "application/json",
            body: JSON.stringify({code: "RAG_SERVICE_UNAVAILABLE", message: "评估服务暂不可用"})
        }));
        await page.locator("#evaluationRunButton").click();
        await page.waitForFunction(() => !document.querySelector("#evaluationRunButton").disabled);
        assert.equal(await page.locator("#evaluationSummary .loading-ring").count(), 0);
        await page.unroute("**/api/v1/evaluations/run");
        const evaluation = page.waitForResponse(r => r.url().endsWith("/api/v1/evaluations/run"));
        await page.locator("#evaluationRunButton").click();
        const result = (await (await evaluation).json()).data;
        await page.waitForFunction(() => !document.querySelector("#evaluationRunButton").disabled);
        assert.equal(result.caseResults.length, 200);
        const selectedVersion = await page.locator("#failureVersionFilter").inputValue();
        const expectedRows = selectedVersion ? result.caseResults.filter(item => item.version === selectedVersion).length : result.caseResults.length;
        assert.equal(await page.locator("#evaluationCaseRows tr").count(), expectedRows);
        await page.waitForFunction(() => !document.querySelector("#toast").classList.contains("visible"));

        const overflow = [];
        for (const width of [1440, 1280, 390, 320]) {
            await page.setViewportSize({width, height: width < 500 ? 844 : 1000});
            for (const name of views) {
                await view(name);
                await page.evaluate(() => window.scrollTo({top: 0, behavior: "instant"}));
                const size = await page.evaluate(() => ({
                    width: document.documentElement.clientWidth,
                    scroll: document.documentElement.scrollWidth
                }));
                if (size.scroll > size.width + 1) overflow.push({name, width, ...size});
                if ([1440, 390].includes(width)) {
                    await page.screenshot({path: path.join(output, `${name}-${width}.png`), fullPage: true});
                    if (width === 390) {
                        await page.screenshot({path: path.join(output, `${name}-mobile-viewport.png`)});
                    }
                }
            }
        }
        await page.setViewportSize({width: 1440, height: 1000});
        await view("pipeline");
        const sources = JSON.parse(await fs.readFile(path.resolve("data/sources.json"), "utf8"));
        const record = sources.find(item => item.id === "NCCIH-LICORICE");
        const excerpt = record.excerpts[0];
        async function fillImport() {
            for (const name of ["title", "publisher", "publicationYear", "sourceUrl", "licenseNote"]) {
                await page.locator(`#importForm [name="${name}"]`).fill(String(record[name]));
            }
            await page.locator('#importForm [name="sourceType"]').selectOption(record.sourceType);
            await page.locator('#importForm [name="content"]').fill(excerpt.content);
            await page.locator('#importForm [name="section"]').fill(excerpt.section);
        }
        await fillImport();
        const importedResponse = page.waitForResponse(r => r.url().endsWith("/api/v1/knowledge/import"));
        await page.locator("#importButton").click();
        const imported = await importedResponse;
        assert.equal(imported.status(), 200);
        const importedData = (await imported.json()).data;
        assert.ok(["READY", "SYNC_PENDING"].includes(importedData.status));
        await page.waitForFunction(() => !document.querySelector("#importButton").disabled);
        const stored = (await (await page.context().request.get(
            `${baseURL}/api/v1/knowledge/documents/${importedData.document.id}`)).json()).data;
        assert.equal(stored.sourceUrl, record.sourceUrl);
        assert.ok(await page.locator(`#registryList`).innerText().then(text => text.includes(record.title)));
        await fillImport();
        const duplicateResponse = page.waitForResponse(r => r.url().endsWith("/api/v1/knowledge/import"));
        await page.locator("#importButton").click();
        assert.equal((await duplicateResponse).status(), 409);
        await page.waitForFunction(() => !document.querySelector("#importButton").disabled);
        assert.match(await page.locator("#importHint").innerText(), /导入失败/);
        assert.equal(await page.locator('#importForm [name="content"]').inputValue(), excerpt.content);
        assert.deepEqual(errors, []);
        assert.deepEqual(overflow, []);
        await fs.writeFile(path.join(output, "results.json"), JSON.stringify({
            baseURL, javascriptErrors: errors, overflow, evaluation: result.summaries,
            checks: ["five views", "filters", "source links", "remote chat", "comparison",
                "emergency refusal", "local fallback notice", "combined corpus", "request retry", "evaluation retry", "four viewport widths",
                "browser import", "duplicate import and retained form"]
        }, null, 2));
        console.log("PASS: browser workflows and responsive checks");
    } finally {
        await browser.close();
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
