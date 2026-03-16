import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const chromePath = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const debugPort = process.env.CHROME_DEBUG_PORT || "9226";
const targetUrl = process.env.MEASURE_URL || "http://127.0.0.1:5173";
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "java-claw-layout-"));

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

async function main() {
  const chrome = spawn(
    chromePath,
    [
      `--remote-debugging-port=${debugPort}`,
      "--remote-allow-origins=*",
      `--user-data-dir=${userDataDir}`,
      "--headless=new",
      "--no-sandbox",
      "--disable-gpu",
      "--no-first-run",
      "--no-default-browser-check",
      "about:blank"
    ],
    {
      stdio: "ignore",
      shell: false
    }
  );

  try {
    await waitForHttp(`${targetUrl}`);
    await waitForHttp(`http://127.0.0.1:${debugPort}/json/version`);
    const pageTarget = await fetch(
      `http://127.0.0.1:${debugPort}/json/new?${encodeURIComponent(targetUrl)}`,
      { method: "PUT" }
    ).then((res) => res.json());
    const ws = new WebSocket(pageTarget.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.addEventListener("open", resolve, { once: true });
      ws.addEventListener("error", reject, { once: true });
    });
    const client = new CdpClient(ws);
    await client.send("Page.enable");
    await client.send("Runtime.enable");
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: 1440,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false
    });
    await wait(1500);
    const result = await client.send("Runtime.evaluate", {
      expression: `(() => {
        const pick = (selector) => {
          const node = document.querySelector(selector);
          if (!node) return null;
          const rect = node.getBoundingClientRect();
          return {
            selector,
            top: rect.top,
            left: rect.left,
            width: rect.width,
            height: rect.height,
            bottom: rect.bottom,
            scrollHeight: node.scrollHeight,
            clientHeight: node.clientHeight,
            overflowY: getComputedStyle(node).overflowY,
            position: getComputedStyle(node).position
          };
        };
        return {
          viewport: { width: window.innerWidth, height: window.innerHeight },
          shell: pick('.workspace-shell'),
          centerStage: pick('.center-stage'),
          chatWorkspace: pick('.chat-workspace'),
          configBar: pick('.chat-config-bar'),
          sessionTag: pick('.chat-session-tag'),
          firstBarField: pick('.chat-config-bar .bar-field'),
          conversationPanel: pick('.conversation-panel.chat-panel'),
          conversationStream: pick('.conversation-stream'),
          composerDock: pick('.composer-dock')
        };
      })()`,
      returnByValue: true
    });
    console.log(JSON.stringify(result.result.value, null, 2));
    ws.close();
  } finally {
    chrome.kill("SIGKILL");
    await wait(800);
    try {
      fs.rmSync(userDataDir, { recursive: true, force: true });
    } catch {
      // Chrome may still hold crashpad files briefly; measurement already completed.
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
