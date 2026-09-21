// zcode-hub：本地小服务，给手机提供一个「多台 ZCode 远控面板」。
// 零依赖，只用 Node 内置模块（需要 Node >= 22，用到全局 WebSocket）。
// 同时监听 HTTP(8787) 和 HTTPS(8443，若 certs/ 存在)。
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { probeDevice } from './lib/relay.mjs';
import {
  loadDevices, upsertDevice, updateDevice, removeDevice, publicDevice, getToken, importLocalDevice,
} from './lib/store.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(root, 'public');
const certDir = path.join(root, 'certs');
const PORT = Number(process.env.PORT || 8787);
const PORT_TLS = Number(process.env.PORT_TLS || 8443);
const TOKEN = getToken();

if (typeof WebSocket === 'undefined') {
  console.error('需要 Node 22 及以上版本（内置 WebSocket）。当前：' + process.version);
  process.exit(1);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.crt': 'application/x-x509-ca-cert',
  '.apk': 'application/vnd.android.package-archive',
};

function send(res, status, body, headers = {}) {
  const payload = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', ...headers });
  res.end(payload);
}

function authorized(req, url) {
  return req.headers['x-hub-token'] === TOKEN || url.searchParams.get('k') === TOKEN;
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return {}; }
}

function lanAddress() {
  const candidates = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const info of list || []) {
      if (info.family === 'IPv4' && !info.internal) candidates.push(info.address);
    }
  }
  return candidates.find((ip) => ip.startsWith('192.168.'))
    || candidates.find((ip) => ip.startsWith('10.'))
    || candidates[0]
    || '127.0.0.1';
}

function serveStatic(res, url) {
  const rel = url.pathname === '/' ? '/index.html' : url.pathname;
  const filePath = path.join(publicDir, path.normalize(rel).replace(/^(\.\.[/\\])+/, ''));
  if (!filePath.startsWith(publicDir)) return send(res, 403, { error: 'forbidden' });
  fs.readFile(filePath, (err, data) => {
    if (err) return send(res, 404, { error: 'not found' });
    res.writeHead(200, {
      'content-type': MIME[path.extname(filePath)] || 'application/octet-stream',
      'cache-control': 'no-cache',
    });
    res.end(data);
  });
}

/** 探测一台设备：连中继 → 取配对状态/工作区 → 断开 */
async function probeOne(device) {
  const started = Date.now();
  try {
    const result = await probeDevice(device, { timeoutMs: 15000 });
    const active = (result.workspaces || []).find((w) => (w.workspaceIdentity || w.workspacePath) === result.activeWorkspaceKey);
    return {
      id: device.id,
      ok: true,
      pairStatus: result.pairStatus,
      ms: Date.now() - started,
      workspaces: (result.workspaces || []).map((w) => ({
        key: w.workspaceIdentity || w.workspacePath,
        path: w.workspacePath,
        kind: w.kind,
        name: w.workspaceName || path.basename(w.workspacePath || '') || w.workspacePath,
      })),
      activeWorkspaceKey: result.activeWorkspaceKey,
      activeWorkspace: active ? (active.workspaceIdentity || active.workspacePath) : null,
      activeTaskId: result.activeTaskId,
    };
  } catch (err) {
    return { id: device.id, ok: false, ms: Date.now() - started, error: err?.message || String(err), code: err?.code || null };
  }
}

async function handleRequest(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  // 自签 CA 证书（供手机安装），不需要令牌
  if (url.pathname === '/ca.crt' && req.method === 'GET') {
    const caPath = path.join(certDir, 'ca.pem');
    if (!fs.existsSync(caPath)) return send(res, 404, { error: '没有证书，先运行 make-cert.sh' });
    return send(res, 200, fs.readFileSync(caPath), {
      'content-type': MIME['.crt'],
      'content-disposition': 'attachment; filename="zcode-hub-ca.crt"',
    });
  }

  // 打开官方手机端控制台：302 跳转，密钥不下发给前端
  const consoleMatch = url.pathname.match(/^\/c\/([^/]+)$/);
  if (consoleMatch && req.method === 'GET') {
    if (!authorized(req, url)) return send(res, 401, { error: 'unauthorized' });
    const device = loadDevices().find((d) => d.id === decodeURIComponent(consoleMatch[1]));
    if (!device) return send(res, 404, { error: 'device not found' });
    res.writeHead(302, { location: device.link });
    return res.end();
  }

  if (url.pathname.startsWith('/api/')) {
    if (!authorized(req, url)) return send(res, 401, { error: 'unauthorized' });
    try {
      if (url.pathname === '/api/health' && req.method === 'GET') {
        return send(res, 200, { ok: true, version: process.version, devices: loadDevices().length });
      }

      if (url.pathname === '/api/devices' && req.method === 'GET') {
        return send(res, 200, { devices: loadDevices().map(publicDevice) });
      }

      if (url.pathname === '/api/devices' && req.method === 'POST') {
        const body = await readBody(req);
        const link = body.link || body.url;
        if (!link) return send(res, 400, { error: '缺少 link' });
        return send(res, 200, { device: publicDevice(upsertDevice(link, { label: body.label })) });
      }

      if (url.pathname === '/api/devices/import-local' && req.method === 'POST') {
        const body = await readBody(req);
        return send(res, 200, { device: publicDevice(importLocalDevice(body.origin || undefined)) });
      }

      const deviceMatch = url.pathname.match(/^\/api\/devices\/([^/]+)$/);
      if (deviceMatch) {
        const id = decodeURIComponent(deviceMatch[1]);
        if (req.method === 'DELETE') return send(res, 200, { removed: removeDevice(id) });
        if (req.method === 'PATCH') {
          const body = await readBody(req);
          const device = updateDevice(id, { label: body.label });
          return device ? send(res, 200, { device: publicDevice(device) }) : send(res, 404, { error: 'not found' });
        }
      }

      if (url.pathname === '/api/probe' && req.method === 'POST') {
        const body = await readBody(req);
        const devices = loadDevices();
        const targets = body.id ? devices.filter((d) => d.id === body.id) : devices;
        if (!targets.length) return send(res, 404, { error: '没有可探测的设备' });
        const results = [];
        for (const device of targets) results.push(await probeOne(device));
        return send(res, 200, { results });
      }

      return send(res, 404, { error: 'unknown api' });
    } catch (err) {
      return send(res, 500, { error: err?.message || String(err) });
    }
  }

  if (req.method === 'GET' || req.method === 'HEAD') return serveStatic(res, url);
  return send(res, 405, { error: 'method not allowed' });
}

const ip = lanAddress();
const httpServer = http.createServer(handleRequest);
httpServer.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('  ZCode 多机面板已启动');
  console.log('  ─────────────────────────────────────────────');
  console.log(`  手机打开： http://${ip}:${PORT}/?k=${TOKEN}`);
  console.log(`  本机打开： http://127.0.0.1:${PORT}/?k=${TOKEN}`);
  console.log('');
  console.log('  用法：ZCode 桌面端 → 手机远控（显示二维码）→ 在面板里点「扫码添加」');
  console.log('  也可以直接点「导入本机」把当前这台机器加进来。');
  console.log('');
  console.log('  Ctrl+C 停止');
  console.log('');
});

const keyFile = path.join(certDir, 'server.key');
const certFile = path.join(certDir, 'server.pem');
if (fs.existsSync(keyFile) && fs.existsSync(certFile)) {
  const httpsServer = https.createServer(
    { key: fs.readFileSync(keyFile), cert: fs.readFileSync(certFile) },
    handleRequest,
  );
  httpsServer.listen(PORT_TLS, '0.0.0.0', () => {
    console.log('  HTTPS 已开启（手机装一次证书就能当 App 用 + 实时扫码）：');
    console.log(`    https://${ip}:${PORT_TLS}/?k=${TOKEN}`);
    console.log(`    证书下载： https://${ip}:${PORT_TLS}/ca.crt`);
    console.log('');
  });
} else {
  console.log('  提示：运行 bash make-cert.sh 可开启 HTTPS（解锁「安装成 App」和实时摄像头扫码）');
  console.log('');
}
