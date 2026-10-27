const http = require("node:http");
const fs = require("node:fs/promises");
const path = require("node:path");

const staticRoot = path.resolve(__dirname, "../backend/src/main/resources/static");
const types = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8", ".svg": "image/svg+xml"};

async function startPreview({port = 0, apiURL = "http://127.0.0.1:8081", fixtureHandler} = {}) {
    const upstream = new URL(apiURL);
    if (!["127.0.0.1", "localhost", "[::1]"].includes(upstream.hostname)) {
        throw new Error("Preview API must be local");
    }
    const server = http.createServer(async (req, res) => {
        try {
            const url = new URL(req.url, "http://127.0.0.1");
            if (url.pathname.startsWith("/api/") || url.pathname === "/actuator/health") {
                if (fixtureHandler) return await fixtureHandler(req, res, url);
                const proxy = http.request(new URL(req.url, upstream), {
                    method: req.method, headers: req.headers
                }, response => {
                    res.writeHead(response.statusCode, response.headers);
                    response.pipe(res);
                });
                proxy.on("error", () => {
                    res.writeHead(502, {"Content-Type": "application/json"});
                    res.end(JSON.stringify({code: "PREVIEW_API_UNAVAILABLE", message: "本地 API 服务未连接"}));
                });
                req.pipe(proxy);
                return;
            }
            const relative = url.pathname === "/" ? "index.html" : decodeURIComponent(url.pathname.slice(1));
            const filename = path.resolve(staticRoot, relative);
            if (!filename.startsWith(`${staticRoot}${path.sep}`) || !types[path.extname(filename)]) {
                res.writeHead(404);
                res.end("Not found");
                return;
            }
            const data = await fs.readFile(filename);
            res.writeHead(200, {"Content-Type": types[path.extname(filename)], "Cache-Control": "no-store"});
            res.end(data);
        } catch (error) {
            res.writeHead(404);
            res.end("Not found");
        }
    });
    await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, "127.0.0.1", resolve);
    });
    return {server, url: `http://127.0.0.1:${server.address().port}`,
        close: () => new Promise(resolve => server.close(resolve))};
}

module.exports = {startPreview};

if (require.main === module) {
    startPreview({port: Number(process.env.UI_PORT || 8099), apiURL: process.env.API_URL})
        .then(preview => console.log(`UI preview: ${preview.url}`))
        .catch(error => {console.error(error.message); process.exitCode = 1;});
}
