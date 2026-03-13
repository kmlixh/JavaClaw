import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve("dist");
const apiTarget = process.env.API_TARGET || "http://127.0.0.1:18081";
const port = Number(process.env.WEB_PORT || "4173");

const contentTypes = new Map([
  [".html", "text/html; charset=utf-8"],
  [".js", "application/javascript; charset=utf-8"],
  [".css", "text/css; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".svg", "image/svg+xml"],
  [".png", "image/png"],
  [".ico", "image/x-icon"]
]);

function sendFile(res, targetPath) {
  const filePath = path.join(root, targetPath === "/" ? "index.html" : targetPath);
  const normalized = path.normalize(filePath);
  if (!normalized.startsWith(root)) {
    res.writeHead(403);
    res.end("forbidden");
    return;
  }

  const actualPath = fs.existsSync(normalized) && fs.statSync(normalized).isFile()
    ? normalized
    : path.join(root, "index.html");

  const ext = path.extname(actualPath);
  res.writeHead(200, { "Content-Type": contentTypes.get(ext) || "application/octet-stream" });
  fs.createReadStream(actualPath).pipe(res);
}

const server = http.createServer(async (req, res) => {
  if (!req.url) {
    res.writeHead(400);
    res.end("bad request");
    return;
  }

  if (req.url.startsWith("/api")) {
    const upstream = new URL(req.url, apiTarget);
    const response = await fetch(upstream, {
      method: req.method,
      headers: {
        "content-type": req.headers["content-type"] || "application/json"
      },
      body: req.method === "GET" || req.method === "HEAD"
        ? undefined
        : await new Promise((resolve) => {
            const chunks = [];
            req.on("data", (chunk) => chunks.push(chunk));
            req.on("end", () => resolve(Buffer.concat(chunks)));
          })
    });

    res.writeHead(response.status, Object.fromEntries(response.headers.entries()));
    const body = Buffer.from(await response.arrayBuffer());
    res.end(body);
    return;
  }

  sendFile(res, req.url.split("?")[0]);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`static proxy listening on http://127.0.0.1:${port}`);
});
