const assert = require("node:assert/strict");
const http = require("node:http");
const fs = require("node:fs/promises");
const path = require("node:path");
const {chromium} = require("playwright");

const baseURL = process.env.APP_URL || "http://127.0.0.1:8081";
const output = path.resolve(".tmp/model-ui");

async function main() {
    const calls = [];
    let fail = false;
    const provider = http.createServer(async (req, res) => {
        assert.equal(req.url, "/v1/chat/completions");
        let body = "";
        for await (const chunk of req) body += chunk;
        const payload = JSON.parse(body);
        calls.push(payload);
        res.writeHead(fail ? 401 : 200, {"Content-Type": "application/json"});
        res.end(JSON.stringify(fail ? {error: "test credential rejected"} : {
            choices: [{message: {role: "assistant",
                content: payload.messages.length === 1 ? "OK" : "协议测试返回：应通过血压测量识别高血压。[1]"},
            finish_reason: "stop"}]
        }));
    });
    await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
    try {
        await fs.mkdir(output, {recursive: true});
        const context = await browser.newContext({viewport: {width: 1440, height: 1000}, reducedMotion: "reduce"});
        await context.request.delete(`${baseURL}/api/v1/rag/model`);
        const page = await context.newPage();
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await page.goto(`${baseURL}/#workbench`);
        await page.locator("#versionRail button").first().waitFor();
        await page.waitForFunction(() => document.querySelector("#methodNote").textContent.includes("原文摘录"));
        const originalGrid = await page.locator(".workbench-grid").first().boundingBox();
        await page.locator("#modelSettingsButton").click();
        await page.waitForFunction(() => !document.querySelector("#modelMode").disabled);
        assert.equal(await page.locator("#modelDialog").evaluate(el => el.open), true);
        await page.locator("#modelMode").selectOption("MODEL");
        await page.locator("#modelBaseUrl").fill(`http://127.0.0.1:${provider.address().port}/v1`);
        await page.locator("#modelName").fill("protocol-test");
        await page.locator("#modelApiKey").fill("protocol-test-key");
        await page.locator("#testModelButton").click();
        await page.waitForFunction(() => document.querySelector("#modelConnectionStatus").textContent.includes("连接成功"));
        assert.equal((await (await context.request.get(`${baseURL}/api/v1/rag/model`)).json()).data.configured, false);
        await page.locator("#modelConsent").check();
        await page.locator("#saveModelButton").click();
        await page.waitForFunction(() => !document.querySelector("#modelDialog").open);
        const currentGrid = await page.locator(".workbench-grid").first().boundingBox();
        for (const key of ["x", "y", "width", "height"]) {
            assert.ok(Math.abs(currentGrid[key] - originalGrid[key]) < 1, `grid changed: ${key}`);
        }
        assert.equal(await page.locator("#modelApiKey").inputValue(), "");
        assert.equal(await page.locator("#askButton").innerText(), "智能问答");
        await page.reload();
        await page.waitForFunction(() => document.querySelector("#askButton").textContent.includes("智能问答"));

        async function ask(question) {
            await page.locator("#questionInput").fill(question);
            const pending = page.waitForResponse(r => r.url().endsWith("/api/v1/rag/chat"));
            await page.locator("#askButton").click();
            const result = await (await pending).json();
            await page.waitForFunction(() => !document.querySelector("#askButton").disabled);
            return result;
        }
        const result = await ask("高血压患者没有症状也需要检查血压吗？");
        assert.equal(result.code, "OK");
        assert.ok(result.data.retrieval.notes.includes("generation=llm"));
        assert.ok(JSON.parse(calls.at(-1).messages[1].content).evidence.length);
        const count = calls.length;
        assert.equal((await ask("胸痛持续不缓解，怎么办？")).data.refused, true);
        assert.equal(calls.length, count);
        fail = true;
        assert.equal((await ask("高血压的症状是什么？")).code, "MODEL_AUTH_FAILED");
        assert.match(await page.locator("#answerStage").innerText(), /鉴权失败/);
        fail = false;
        assert.equal((await ask("高血压患者没有症状也需要检查血压吗？")).code, "OK");
        const storage = await context.storageState();
        assert.ok(!JSON.stringify(storage).includes("protocol-test-key"));
        await page.waitForFunction(() => !document.querySelector("#toast").classList.contains("visible"));

        for (const width of [1440, 1280, 390, 320]) {
            await page.setViewportSize({width, height: width < 500 ? 844 : 1000});
            await page.evaluate(() => window.scrollTo({top: 0, behavior: "instant"}));
            await page.screenshot({path: path.join(output, `workbench-${width}.png`), fullPage: true});
            await page.locator("#modelSettingsButton").click();
            await page.waitForFunction(() => !document.querySelector("#modelMode").disabled);
            assert.equal(await page.locator("#modelApiKey").inputValue(), "");
            assert.match(await page.locator("#modelKeyState").innerText(), /已设置/);
            const box = await page.locator("#modelDialog").boundingBox();
            assert.ok(box.x >= 0 && box.x + box.width <= width + 1);
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
            assert.equal(await page.locator("#modelDialog").evaluate(el => el.scrollWidth <= el.clientWidth), true);
            await page.screenshot({path: path.join(output, `config-${width}.png`)});
            await page.keyboard.press("Escape");
            assert.equal(await page.locator("#modelSettingsButton").evaluate(el => el === document.activeElement), true);
        }
        await page.locator("#modelSettingsButton").click();
        await page.waitForFunction(() => !document.querySelector("#modelMode").disabled);
        await page.locator("#clearModelButton").click();
        await page.waitForFunction(() => document.querySelector("#modelConnectionStatus").textContent.includes("已清除"));
        await page.locator("#closeModelButton").click();
        assert.equal(await page.locator("#askButton").innerText(), "运行检索");
        assert.deepEqual(errors, []);
        console.log("PASS: model configuration, session isolation, grounded protocol, error recovery and unchanged grid");
    } finally {
        await browser.close();
        await new Promise(resolve => provider.close(resolve));
    }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
