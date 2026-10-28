const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const net = require("node:net");
const {spawn} = require("node:child_process");
const {createHash, randomUUID} = require("node:crypto");
const {startPreview} = require("./ui-preview.cjs");

const root = path.resolve(__dirname, "..");
const jar = path.join(root, "backend/target/tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar");

async function freePort() {
    const listener = net.createServer();
    await new Promise((resolve, reject) => {
        listener.once("error", reject);
        listener.listen(0, "127.0.0.1", resolve);
    });
    const port = listener.address().port;
    await new Promise(resolve => listener.close(resolve));
    return port;
}

async function main() {
    await fs.access(jar);
    const port = await freePort();
    const javaURL = `http://127.0.0.1:${port}`;
    const java = spawn("java", ["-jar", jar, "--app.rag-engine.mode=local",
        "--server.address=127.0.0.1", `--server.port=${port}`,
        `--app.knowledge.store-path=${path.join(root, ".tmp", `model-smoke-${randomUUID()}.json`)}`],
    {cwd: root, windowsHide: true, env: {...process.env,
        MODEL_API_BASE: "", MODEL_NAME: "", MODEL_API_KEY: ""},
        stdio: ["ignore", "pipe", "pipe"]});
    let log = "";
    let launchError;
    let preview;
    java.on("error", error => {launchError = error;});
    const collect = data => {log = (log + data).slice(-12000);};
    java.stdout.on("data", collect);
    java.stderr.on("data", collect);
    const javaExited = new Promise(resolve => java.once("exit", resolve));
    try {
        let ready = false;
        for (let attempt = 0; attempt < 120; attempt++) {
            if (launchError) throw launchError;
            assert.equal(java.exitCode, null, `Isolated Java exited:\n${log}`);
            try {
                const response = await fetch(`${javaURL}/actuator/health`,
                    {signal: AbortSignal.timeout(1000)});
                if (response.ok) {ready = true; break;}
            } catch {}
            await new Promise(resolve => setTimeout(resolve, 500));
        }
        assert.ok(ready, `Isolated Java not ready:\n${log}`);
        preview = await startPreview({apiURL: javaURL});
        console.log(`Isolated model smoke: ${preview.url} -> ${javaURL}`);
        const child = spawn(process.execPath, [path.join(__dirname, "smoke-model-ui.cjs")],
            {cwd: root, windowsHide: true, env: {...process.env, APP_URL: preview.url},
                stdio: ["ignore", "pipe", "pipe"]});
        let output = "";
        child.stdout.on("data", data => {output += data; process.stdout.write(data);});
        child.stderr.on("data", data => {output += data; process.stderr.write(data);});
        const code = await new Promise((resolve, reject) => {
            child.once("error", reject);
            child.once("exit", resolve);
        });
        const source = await fs.readFile(path.join(__dirname, "smoke-model-ui.cjs"));
        const result = {passed: code === 0, exitCode: code, javaURL, previewURL: preview.url,
            engine: "java-local", securityOverrides: false, assertionsModified: false,
            jarSha256: createHash("sha256").update(await fs.readFile(jar)).digest("hex"),
            scriptSha256: createHash("sha256").update(source).digest("hex"), output};
        await fs.mkdir(path.join(root, ".tmp/model-ui"), {recursive: true});
        await fs.writeFile(path.join(root, ".tmp/model-ui/result.json"),
            JSON.stringify(result, null, 2));
        assert.equal(code, 0, "Original model UI smoke failed");
    } finally {
        if (preview) await preview.close();
        if (java.exitCode === null) java.kill();
        await javaExited;
    }
}

main().catch(error => {console.error(error.message); process.exitCode = 1;});
