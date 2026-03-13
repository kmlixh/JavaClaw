import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const repoRoot = path.resolve("..");
const backendPort = "18083";
const frontendPort = "4174";
const debugPort = "9223";
const chromePath = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const MAX_TIMEOUT_MS = 180000;
const children = [];
const logDir = path.resolve("test-logs");

fs.mkdirSync(logDir, { recursive: true });

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForHttp(url, attempts = 60) {
  for (let i = 0; i < attempts; i += 1) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        return;
      }
    } catch {
      // retry
    }
    await wait(1000);
  }
  throw new Error(`timeout waiting for ${url}`);
}

function startProcess(command, args, cwd, env = {}) {
  const slug = path.basename(command).replace(/[^a-z0-9]/gi, "-").toLowerCase();
  const stamp = Date.now();
  const outFd = fs.openSync(path.join(logDir, `${slug}-${stamp}.out.log`), "a");
  const errFd = fs.openSync(path.join(logDir, `${slug}-${stamp}.err.log`), "a");
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, ...env },
    stdio: ["ignore", outFd, errFd],
    shell: false
  });
  children.push(child);
  return child;
}

class CdpClient {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    ws.addEventListener("message", (event) => {
      const payload = JSON.parse(event.data);
      if (payload.id && this.pending.has(payload.id)) {
        const { resolve, reject } = this.pending.get(payload.id);
        this.pending.delete(payload.id);
        if (payload.error) {
          reject(new Error(payload.error.message));
        } else {
          resolve(payload.result);
        }
      }
    });
  }

  send(method, params = {}) {
    const id = ++this.seq;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
  }
}

async function waitForCondition(client, expression, timeoutMs = 120000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const result = await client.send("Runtime.evaluate", {
      expression,
      returnByValue: true
    });
    if (result?.result?.value) {
      return;
    }
    await wait(1000);
  }
  throw new Error(`condition timeout: ${expression}`);
}

async function setValue(client, selector, value) {
  const escaped = JSON.stringify(value);
  await client.send("Runtime.evaluate", {
    expression: `(() => {
      const node = document.querySelector(${JSON.stringify(selector)});
      if (!node) return false;
      node.value = ${escaped};
      node.dispatchEvent(new Event('input', { bubbles: true }));
      node.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    })()`,
    returnByValue: true
  });
}

async function click(client, selector) {
  await client.send("Runtime.evaluate", {
    expression: `(() => {
      const node = document.querySelector(${JSON.stringify(selector)});
      if (!node) return false;
      node.click();
      return true;
    })()`,
    returnByValue: true
  });
}

function logStep(message) {
  fs.appendFileSync(path.join(logDir, "e2e-flow.log"), `${new Date().toISOString()} ${message}\n`);
}

async function cleanup() {
  for (const child of children.reverse()) {
    if (child && !child.killed) {
      try {
        child.kill("SIGKILL");
      } catch {
        // ignore
      }
    }
  }
}

const timeout = setTimeout(async () => {
  console.error("WEB E2E FLOW TEST TIMEOUT");
  logStep("timeout reached");
  await cleanup();
  process.exit(1);
}, MAX_TIMEOUT_MS);

try {
  logStep("starting backend");
  const backend = startProcess(
    "java",
    ["-jar", "agent-app/build/libs/agent-app.jar", "--spring.profiles.active=postgres", `--server.port=${backendPort}`],
    repoRoot,
    {
      DB_HOST: process.env.DB_HOST || "10.173.108.120",
      DB_PORT: process.env.DB_PORT || "5433",
      DB_NAME: process.env.DB_NAME || "java_claw",
      DB_USER: process.env.DB_USER || "postgres",
      DB_PASSWORD: process.env.DB_PASSWORD || "JL0Is1KqGuQMayeF"
    }
  );

  logStep("starting frontend proxy");
  startProcess(
    "node",
    ["scripts/static-proxy-server.mjs"],
    path.resolve("."),
    {
      API_TARGET: `http://127.0.0.1:${backendPort}`,
      WEB_PORT: frontendPort
    }
  );

  const userDataDir = path.resolve("test-logs", "chrome-profile");
  fs.rmSync(userDataDir, { recursive: true, force: true });
  fs.mkdirSync(userDataDir, { recursive: true });
  logStep(`starting chrome with userDataDir=${userDataDir}`);
  startProcess(
    chromePath,
    [
      `--remote-debugging-port=${debugPort}`,
      "--remote-allow-origins=*",
      `--user-data-dir=${userDataDir}`,
      "--headless=new",
      "--no-sandbox",
      "--disable-breakpad",
      "--disable-crash-reporter",
      "--disable-gpu",
      "--no-first-run",
      "--no-default-browser-check",
      "about:blank"
    ],
    repoRoot
  );

  logStep("waiting backend /api/llms");
  await waitForHttp(`http://127.0.0.1:${backendPort}/api/llms`);
  logStep("waiting frontend root");
  await waitForHttp(`http://127.0.0.1:${frontendPort}`);
  logStep("waiting chrome devtools");
  await waitForHttp(`http://127.0.0.1:${debugPort}/json/version`);

  logStep("creating chrome page target");
  const pageTarget = await fetch(`http://127.0.0.1:${debugPort}/json/new?${encodeURIComponent(`http://127.0.0.1:${frontendPort}`)}`, {
    method: "PUT"
  }).then((res) => res.json());
  if (!pageTarget?.webSocketDebuggerUrl) {
    throw new Error("chrome devtools page target not found");
  }

  logStep("connecting chrome websocket");
  const ws = new WebSocket(pageTarget.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
  });

  const client = new CdpClient(ws);
  await client.send("Page.enable");
  await client.send("Runtime.enable");
  logStep("connected chrome websocket");
  await waitForCondition(client, "document.readyState === 'complete'");
  await waitForCondition(client, "document.querySelector('[data-testid=\"llm-select\"]') !== null");

  logStep("page ready, sending message");
  await setValue(client, '[data-testid="llm-select"]', "siliconflow-glm47");
  await setValue(client, '[data-testid="message-input"]', "请用中文简短回复“前端E2E通过”，不要输出其他内容。");
  await click(client, '[data-testid="send-button"]');

  logStep("waiting run id");
  await waitForCondition(client, `
    (() => {
      const value = document.querySelector('[data-testid="run-id"]')?.textContent || '';
      return value && value !== 'N/A' && value.trim().length > 5;
    })()
  `);
  logStep("waiting llm model");
  await waitForCondition(client, `
    (() => {
      const value = document.querySelector('[data-testid="llm-model"]')?.textContent || '';
      return value.includes('GLM-4.7');
    })()
  `);
  logStep("waiting assistant bubble");
  await waitForCondition(client, `
    (() => Array.from(document.querySelectorAll('.bubble.assistant p'))
      .some((node) => (node.textContent || '').trim().length > 0))()
  `, 180000);

  console.log("WEB E2E FLOW TEST PASSED");
  logStep("success");
  ws.close();
  clearTimeout(timeout);
  await cleanup();
  process.exit(0);
} finally {
  clearTimeout(timeout);
  await cleanup();
}
