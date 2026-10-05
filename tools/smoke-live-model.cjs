const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const {chromium} = require("playwright");

async function main() {
    const baseURL = process.env.APP_URL || "http://127.0.0.1:8081";
    const output = path.resolve(".tmp/live-model");
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
    try {
        await fs.mkdir(output, {recursive: true});
        const page = await browser.newPage({viewport: {width: 1440, height: 1000}, reducedMotion: "reduce"});
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await page.goto(`${baseURL}/#workbench`);
        await page.waitForFunction(() => document.querySelector("#askButton").textContent.includes("智能问答"));
        const config = (await (await page.context().request.get(`${baseURL}/api/v1/rag/model`)).json()).data;
        assert.ok(config.configured && config.enabled);
        assert.equal(config.apiKey, undefined);
        await page.locator("#questionInput").fill("高血压患者没有症状也需要检查血压吗？");
        const pending = page.waitForResponse(r => r.url().endsWith("/api/v1/rag/chat"), {timeout: 120000});
        await page.locator("#askButton").click();
        const result = (await (await pending).json()).data;
        assert.ok(result && !result.refused);
        assert.ok(result.retrieval.notes.includes("generation=llm"));
        assert.ok(result.retrieval.notes.includes(`model=${config.model}`));
        assert.ok(result.citations.some(c => c.chunkId === "WHO-HTN-001"));
        await page.waitForFunction(() => !document.querySelector("#askButton").disabled);
        for (const width of [1440, 390]) {
            await page.setViewportSize({width, height: width < 500 ? 844 : 1000});
            await page.evaluate(() => window.scrollTo({top: 0, behavior: "instant"}));
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
            await page.screenshot({path: path.join(output, `answer-${width}.png`), fullPage: true});
            await page.locator("#modelSettingsButton").click();
            await page.waitForFunction(() => !document.querySelector("#modelMode").disabled);
            assert.equal(await page.locator("#modelApiKey").inputValue(), "");
            await page.screenshot({path: path.join(output, `config-${width}.png`)});
            await page.keyboard.press("Escape");
        }
        assert.deepEqual(errors, []);
        await fs.writeFile(path.join(output, "result.json"), JSON.stringify(result, null, 2));
        console.log(`PASS: live ${config.model} RAG, ${result.citations.length} citations, ${result.latencyMs} ms`);
    } finally {
        await browser.close();
    }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
