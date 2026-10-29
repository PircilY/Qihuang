// Offline UI protocol replay. This is not a retrieval or medical-quality evaluation.
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const {chromium} = require("playwright");
const {startPreview} = require("./ui-preview.cjs");
const protocol = require("./fixtures/ui/protocol.json");

const output = path.resolve("docs/ui-redesign/after");
const widths = [1440, 1280, 390, 320];
const views = ["overview", "knowledge", "workbench", "evaluation", "pipeline", "comparison", "model"];
const payload = "<img src=x onerror=alert(1)>";
const report = {provenance: "Offline replay of captured local API envelopes; synthetic injection/state cases only",
    widths, themes: ["light", "dark"], checks: [], failures: [], contrastFailures: [],
    javascriptErrors: [], consoleErrors: [], failedRequests: [], externalRequests: [],
    unexpectedRequests: [], screenshots: [], keyboard: [], contrast: [], expectedErrors: []};
const fixtureState = {chat: "normal", delay: 0, imported: null};
const clone = value => structuredClone(value);

async function fixtureHandler(req, res, url) {
    let body = "";
    for await (const chunk of req) body += chunk;
    const input = body ? JSON.parse(body) : {};
    const key = `${req.method} ${url.pathname}${url.search}`;
    // Replay the small captured dataset under the unified UI scope; this is not a corpus merge test.
    const fixtureKey = key.replace("?corpus=COMBINED", "?corpus=BASELINE");
    let envelope = clone(protocol[fixtureKey]);
    if (req.method === "POST" && url.pathname === "/api/v1/rag/chat") {
        envelope.data.question = input.question;
        envelope.data.retrieval.notes = envelope.data.retrieval.notes
            .map(note => note.startsWith("corpus=") ? `corpus=${input.corpus}` : note);
        if (fixtureState.chat === "injection") envelope.data.answer = `${input.question}\n文本回显测试。[1]`;
        if (fixtureState.chat === "fallback") {
            envelope.data.retrieval.engine = "java-local";
            envelope.data.retrieval.notes.push("engine=java-local", "fallback=local", "已使用本地引擎");
        }
        if (fixtureState.chat === "refused") {
            envelope.data.refused = true;
            envelope.data.answer = "请立即联系急救服务。本研究系统不提供急症诊疗建议。";
            envelope.data.citations = [];
            envelope.data.retrieval.rejected = true;
        }
        if (fixtureState.chat === "error") {
            envelope = {code: "RAG_SERVICE_UNAVAILABLE", message: "离线审计模拟：检索暂不可用"};
        }
        if (fixtureState.delay) await new Promise(resolve => setTimeout(resolve, fixtureState.delay));
    }
    if (req.method === "POST" && url.pathname === "/api/v1/knowledge/import") {
        const template = clone(protocol["GET /api/v1/knowledge/documents?corpus=BASELINE"].data[0]);
        fixtureState.imported = {...template, id: "UI-INJECTION", title: input.title, publisher: input.publisher,
            sourceUrl: input.sourceUrl, chunkCount: 1, description: "Synthetic UI-only imported title"};
        envelope = {code: "OK", data: {status: "READY", chunkCount: 1}};
    }
    if (fixtureState.imported && url.pathname === "/api/v1/knowledge/documents") {
        envelope.data.push(fixtureState.imported);
    }
    if (!envelope) {
        report.unexpectedRequests.push(key);
        res.writeHead(404, {"Content-Type": "application/json"});
        res.end(JSON.stringify({code: "UI_FIXTURE_MISSING", message: key}));
        return;
    }
    res.writeHead(200, {"Content-Type": "application/json"});
    res.end(JSON.stringify(envelope));
}

function observe(page) {
    page.on("pageerror", error => report.javascriptErrors.push(error.message));
    page.on("console", message => {
        if (message.type() === "error") report.consoleErrors.push(message.text());
    });
    page.on("requestfailed", request => report.failedRequests.push({
        url: request.url(), error: request.failure()?.errorText}));
    page.on("request", request => {
        if (!["127.0.0.1", "localhost", "[::1]"].includes(new URL(request.url()).hostname)) {
            report.externalRequests.push(request.url());
        }
    });
    page.on("dialog", async dialog => {
        report.failures.push(`Unexpected browser dialog: ${dialog.message()}`);
        await dialog.dismiss();
    });
}

async function go(page, name) {
    await page.locator(`[data-view="${["comparison", "model"].includes(name) ? "workbench" : name}"]`).click();
    await page.locator("#compareSection").evaluate(element => element.classList.add("hidden"));
    if (name === "comparison") {
        await page.locator("#compareSection").evaluate(element => element.classList.remove("hidden"));
        await page.evaluate(() => {
            const section = document.querySelector("#compareSection");
            window.scrollTo({top: section.getBoundingClientRect().top + scrollY - 86, behavior: "instant"});
        });
    } else {
        await page.evaluate(() => window.scrollTo({top: 0, behavior: "instant"}));
    }
    if (name === "model") {
        await page.locator("#modelSettingsButton").click();
        await page.waitForFunction(() => !document.querySelector("#modelMode").disabled);
    }
}

async function ask(page, question = "甘草有哪些严重不良反应？") {
    await page.locator("#questionInput").fill(question);
    const response = page.waitForResponse(response => response.url().endsWith("/api/v1/rag/chat"));
    await page.locator("#askButton").click();
    await response;
    await page.waitForFunction(() => !document.querySelector("#askButton").disabled);
}

// Inspect all rendered text, not just a small hand-picked palette sample.
function contrastAudit() {
    const visible = element => {
        const box = element.getBoundingClientRect();
        return box.width > 1 && box.height > 1 && getComputedStyle(element).visibility !== "hidden"
            && !element.closest("[hidden],.hidden") && element.getClientRects().length;
    };
    const rgba = value => {
        const values = value.match(/[\d.]+/g)?.map(Number);
        return values?.length >= 3 ? [...values.slice(0, 3), values[3] ?? 1] : [0, 0, 0, 0];
    };
    const over = (front, back) => {
        const alpha = front[3] + back[3] * (1 - front[3]);
        return [0, 1, 2].map(index => alpha
            ? (front[index] * front[3] + back[index] * back[3] * (1 - front[3])) / alpha : 0).concat(alpha);
    };
    const luminance = color => color.slice(0, 3).map(value => {
        const channel = value / 255;
        return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    }).reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
    const samples = new Map();
    const candidates = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
        const text = walker.currentNode.textContent.trim();
        if (text) candidates.push({element: walker.currentNode.parentElement, text});
    }
    document.querySelectorAll("input:not([type=checkbox]):not([type=hidden]),select,textarea").forEach(element => {
        candidates.push({element, text: element.value || element.placeholder, placeholder: !element.value});
    });
    let excludedDisabled = 0;
    for (const {element, text, placeholder} of candidates) {
        if (!text || !visible(element) || element.closest("script,style,option,.sr-only")) continue;
        if (element.matches(":disabled") || element.closest("button:disabled")) {excludedDisabled++; continue;}
        const style = getComputedStyle(element);
        const chain = [];
        for (let parent = element; parent; parent = parent.parentElement) chain.unshift(parent);
        let background = [255, 255, 255, 1];
        let opacity = 1;
        for (const parent of chain) {
            const computed = getComputedStyle(parent);
            background = over(rgba(computed.backgroundColor), background);
            opacity *= Number(computed.opacity);
        }
        const foreground = rgba(placeholder ? getComputedStyle(element, "::placeholder").color : style.color);
        foreground[3] *= opacity;
        const actual = over(foreground, background);
        const light = luminance(actual);
        const dark = luminance(background);
        const ratio = (Math.max(light, dark) + 0.05) / (Math.min(light, dark) + 0.05);
        const large = parseFloat(style.fontSize) >= 24
            || (parseFloat(style.fontSize) >= 18.66 && Number(style.fontWeight) >= 700);
        const required = large ? 3 : 4.5;
        const selector = `${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ""}`
            + [...element.classList].map(name => `.${name}`).join("");
        const key = `${selector}|${style.color}|${background.join(",")}|${required}`;
        if (!samples.has(key)) samples.set(key, {selector, text: text.slice(0, 100),
            foreground: style.color, background: background.slice(0, 3).map(Math.round),
            ratio: Number(ratio.toFixed(3)), required, passed: ratio >= required});
    }
    const values = [...samples.values()];
    return {checked: values.length, minimum: Math.min(...values.map(value => value.ratio)),
        excludedDisabled, failures: values.filter(value => !value.passed)};
}

async function keyboardAudit(page, label) {
    const candidates = await page.evaluate(() => {
        const dialog = document.querySelector("dialog[open]");
        const root = dialog || document;
        let index = 0;
        const actionable = "a[href],button,input,select,textarea,summary,[contenteditable=true],[role=button],[role=link]";
        return [...root.querySelectorAll(`${actionable},[tabindex]`)]
            .filter(element => {
                const box = element.getBoundingClientRect();
                return !element.matches(":disabled,[type=hidden]")
                    && (element.matches(actionable) || element.tabIndex >= 0)
                    && box.width > 1 && box.height > 1 && getComputedStyle(element).visibility !== "hidden"
                    && element.getClientRects().length;
            }).map(element => {
                element.dataset.auditFocus = String(++index);
                return {id: String(index), name: element.getAttribute("aria-label") || element.getAttribute("title")
                    || element.textContent.trim().slice(0, 60) || element.name || element.id};
            });
    });
    // Blur only establishes the start. Reachability is measured exclusively by real Tab presses.
    await page.evaluate(() => document.activeElement.blur());
    const reached = new Set();
    const invisible = new Set();
    for (let index = 0; index < candidates.length * 2 + 12; index++) {
        await page.keyboard.press("Tab");
        const focus = await page.evaluate(() => {
            const element = document.activeElement;
            const style = getComputedStyle(element);
            const outline = style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0;
            const shadow = style.boxShadow !== "none";
            return {id: element.dataset.auditFocus, visible: element.matches(":focus-visible") && (outline || shadow)};
        });
        if (focus.id) {
            reached.add(focus.id);
            if (!focus.visible) invisible.add(focus.id);
        }
        if (reached.size === candidates.length) break;
    }
    const missing = candidates.filter(candidate => !reached.has(candidate.id));
    const hiddenFocus = candidates.filter(candidate => invisible.has(candidate.id));
    report.keyboard.push({label, controls: candidates.length, reached: reached.size, missing, hiddenFocus});
    if (missing.length || hiddenFocus.length) report.failures.push({label, keyboard: {missing, hiddenFocus}});
    await page.evaluate(() => {
        document.querySelectorAll("[data-audit-focus]").forEach(element => delete element.dataset.auditFocus);
        document.activeElement.blur();
    });
}

async function inspect(page, label, keyboard = true) {
    const size = await page.evaluate(() => ({
        width: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth,
        dialogOverflow: [...document.querySelectorAll("dialog[open]")].some(element => element.scrollWidth > element.clientWidth)
    }));
    if (size.scroll > size.width || size.dialogOverflow) report.failures.push({label, overflow: size});
    const contrast = await page.evaluate(contrastAudit);
    report.contrast.push({label, checked: contrast.checked, minimum: contrast.minimum,
        excludedDisabled: contrast.excludedDisabled});
    report.contrastFailures.push(...contrast.failures.map(failure => ({label, ...failure})));
    if (keyboard) await keyboardAudit(page, label);
}

async function screenshot(page, filename) {
    await page.screenshot({path: path.join(output, filename), type: "jpeg", quality: 64});
    const bytes = (await fs.stat(path.join(output, filename))).size;
    report.screenshots.push({filename, bytes});
    if (bytes > 300000) report.failures.push(`${filename} exceeds 300KB: ${bytes}`);
}

async function main() {
    await fs.mkdir(output, {recursive: true});
    const preview = await startPreview({fixtureHandler});
    let browser;
    try {
        browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
        const context = await browser.newContext({viewport: {width: 1440, height: 1000},
            reducedMotion: "reduce", colorScheme: "light"});
        const page = await context.newPage();
        observe(page);
        await page.goto(preview.url);
        await page.waitForFunction(() => document.querySelector("#overviewDocumentCount").textContent === "4");
        await go(page, "workbench");
        await ask(page);
        await page.locator("#compareButton").click();
        await page.waitForFunction(() => document.querySelectorAll("#compareGrid .compare-result").length === 4);
        await go(page, "evaluation");
        await page.locator("#evaluationRunButton").click();
        await page.waitForFunction(() => document.querySelectorAll("#evaluationCaseRows tr").length === 50);
        assert.equal(await page.locator("#failureVersionFilter").inputValue(), "V3_GUARDED");
        await page.locator("#failureVersionFilter").selectOption("");
        assert.equal(await page.locator("#evaluationCaseRows tr").count(), 200);
        await page.locator("#failureVersionFilter").selectOption("V3_GUARDED");
        report.checks.push("Evaluation default V3=50 / all versions=200; no labels modified");
        await page.waitForFunction(() => !document.querySelector("#toast").classList.contains("visible"));
        for (const theme of report.themes) {
            await page.locator(`[data-theme-choice="${theme}"]`).click();
            for (const width of widths) {
                await page.setViewportSize({width, height: width < 500 ? 844 : 1000});
                for (const name of views) {
                    const label = `${name}-${width}-${theme}`;
                    await go(page, name);
                    await inspect(page, label);
                    // Keyboard scanning scrolls controls into view; restore screenshot framing.
                    if (name !== "model") {
                        await page.evaluate(name => {
                            const top = name === "comparison"
                                ? document.querySelector("#compareSection").getBoundingClientRect().top + scrollY - 86 : 0;
                            window.scrollTo({top, behavior: "instant"});
                        }, name);
                    } else {
                        await page.locator("#modelDialog").evaluate(element => element.scrollTop = 0);
                    }
                    await screenshot(page, `${label}.jpg`);
                    if (name === "model") await page.keyboard.press("Escape");
                }
                console.log(`Audited ${theme} ${width}: seven interfaces`);
            }
        }
        // Exercise expanded native details and source provenance links.
        await page.setViewportSize({width: 390, height: 844});
        await go(page, "comparison");
        await page.locator("#compareGrid details summary").first().click();
        await inspect(page, "expanded-comparison-390-dark");
        await go(page, "workbench");
        await page.locator("#answerStage .citation-actions button").first().click();
        await page.locator("#chunkDetailBoard:not(.hidden)").waitFor();
        await inspect(page, "citation-provenance-390-dark");
        await go(page, "knowledge");
        await page.locator("#chunkList [data-chunk-id]").first().click();
        await inspect(page, "chunk-detail-390-dark");
        await page.locator("#closeChunkDetail").click();
        report.checks.push("Native comparison details and citation/knowledge provenance keyboard access");
        for (const theme of report.themes) {
            await page.locator(`[data-theme-choice="${theme}"]`).click();
            await go(page, "workbench");
            for (const mode of ["refused", "fallback", "error"]) {
                fixtureState.chat = mode;
                await ask(page);
                await inspect(page, `${mode}-${theme}`, mode === "error");
                if (mode === "refused") assert.equal(await page.locator(".refusal-banner").count(), 1);
                if (mode === "fallback") assert.match(await page.locator(".fallback-notice").innerText(), /已使用本地引擎/);
                if (mode === "error") {
                    assert.equal(await page.locator("#answerStage .retry-button").count(), 1);
                    fixtureState.chat = "normal";
                    await page.locator("#answerStage .retry-button").click();
                    await page.locator("#answerStage .citation-item").first().waitFor();
                }
            }
            fixtureState.chat = "normal";
            fixtureState.delay = 800;
            await page.locator("#askButton").click();
            await page.locator("#answerStage .loading-state").waitFor();
            assert.equal(await page.locator("#answerStage").getAttribute("aria-busy"), "true");
            await inspect(page, `loading-${theme}`, false);
            const moving = await page.evaluate(() => [...document.querySelectorAll("*")].filter(element => {
                const style = getComputedStyle(element);
                return style.animationName !== "none" || style.transitionDuration.split(",").some(value => parseFloat(value) > 0);
            }).map(element => element.id || element.className));
            assert.deepEqual(moving, [], "Reduced motion must disable animations and transitions");
            await page.waitForFunction(() => !document.querySelector("#askButton").disabled);
            fixtureState.delay = 0;
        }
        report.checks.push("Refusal, fallback, error retry, busy semantics, reduced motion");
        fixtureState.chat = "injection";
        await ask(page, payload);
        assert.ok((await page.locator(".answer-copy").innerText()).includes(payload));
        await go(page, "pipeline");
        await page.locator('[name="title"]').fill(payload);
        await page.locator('[name="publisher"]').fill("UI audit");
        await page.locator('[name="publicationYear"]').fill("2025");
        await page.locator('[name="sourceUrl"]').fill(`${preview.url}/source`);
        await page.locator('[name="licenseNote"]').fill("UI-only synthetic import, not persisted");
        await page.locator('[name="content"]').fill("This is an offline UI test paragraph, not medical evidence or a corpus change.");
        await page.locator("#importButton").click();
        await page.waitForFunction(() => document.querySelector("#registryList").textContent.includes("<img src=x onerror=alert(1)>"));
        await go(page, "knowledge");
        assert.ok((await page.locator("#documentList").innerText()).includes(payload));
        assert.equal(await page.locator("img[src=x],[onerror]").count(), 0);
        for (const file of ["app.js", "theme.js"]) {
            const source = await fs.readFile(path.resolve("backend/src/main/resources/static", file), "utf8");
            assert.ok(!/\b(innerHTML|outerHTML|insertAdjacentHTML)\b/.test(source), `${file}: unsafe HTML sink`);
        }
        report.checks.push("Question/import-title payload rendered literally; no dynamic HTML sinks or injected nodes");
        await page.locator("#knowledgeSearch").fill("UI_NO_MATCH");
        await page.waitForFunction(() => document.querySelector("#chunkResultCount").textContent.includes("0"));
        await inspect(page, "empty-filter-dark", false);
        await page.locator("#chunkList .empty-compact button").click();
        assert.equal(await page.locator("#knowledgeSearch").inputValue(), "");
        report.checks.push("Empty filter reset");
        await page.locator('[data-theme-choice="dark"]').click();
        await page.reload();
        assert.equal(await page.locator("html").getAttribute("data-theme"), "dark");
        await page.locator('[data-theme-choice="system"]').click();
        await page.emulateMedia({colorScheme: "dark"});
        await page.waitForFunction(() => document.documentElement.dataset.theme === "dark");
        await page.emulateMedia({colorScheme: "light"});
        await page.waitForFunction(() => document.documentElement.dataset.theme === "light");
        const blocked = await context.newPage();
        observe(blocked);
        await blocked.addInitScript(() => {
            Storage.prototype.getItem = () => {throw new DOMException("blocked", "SecurityError");};
            Storage.prototype.setItem = () => {throw new DOMException("blocked", "SecurityError");};
        });
        await blocked.goto(preview.url);
        await blocked.waitForLoadState("networkidle");
        await blocked.locator('[data-theme-choice="dark"]').click();
        assert.equal(await blocked.locator("html").getAttribute("data-theme"), "dark");
        await blocked.close();
        report.checks.push("Theme persistence, system changes and denied localStorage");
        await context.close();
    } catch (error) {
        report.failures.push({message: error.message, stack: error.stack});
    } finally {
        if (browser) await browser.close();
        await preview.close();
        report.totalBytes = report.screenshots.reduce((sum, file) => sum + file.bytes, 0);
        if (report.totalBytes > 6000000) report.failures.push("After screenshots exceed 6MB");
        report.passed = !["failures", "contrastFailures", "javascriptErrors", "consoleErrors",
            "failedRequests", "externalRequests", "unexpectedRequests"].some(key => report[key].length);
        await fs.writeFile(path.join(output, "audit-report.json"), JSON.stringify(report, null, 2) + "\n");
        await fs.writeFile(path.join(output, "manifest.json"), JSON.stringify({
            provenance: report.provenance, widths, themes: report.themes,
            files: report.screenshots, totalBytes: report.totalBytes
        }, null, 2) + "\n");
        console.log(JSON.stringify({passed: report.passed, screenshots: report.screenshots.length,
            bytes: report.totalBytes, failures: report.failures, contrastFailures: report.contrastFailures.length,
            errors: report.javascriptErrors, consoleErrors: report.consoleErrors}, null, 2));
        if (!report.passed) process.exitCode = 1;
    }
}

main().catch(error => {console.error(error); process.exitCode = 1;});
