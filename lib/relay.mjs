// ZCode 远程控制中继客户端（手机端 = "terminal" 角色）。
// 协议来源：ZCode 桌面端 / 官方移动端网页的 relay 实现。
import crypto from 'node:crypto';

export const ROLES = { device: 'device', terminal: 'terminal' };

/** proof = base64url(HMAC-SHA256(passHash, "nonce|role|deviceSid")) */
export function calculateProof(passHash, nonce, role, deviceSid) {
  return crypto.createHmac('sha256', passHash).update(`${nonce}|${role}|${deviceSid}`).digest('base64url');
}

/**
 * 解析 ZCode 配对二维码里的链接。
 * 形如 https://zcode.z.ai/remote/v4?sid=...&hash=...&t=...&mid=...&name=...&app_version=...
 */
export function parsePairingLink(text) {
  let url;
  try {
    url = new URL(String(text || '').trim());
  } catch {
    throw new Error('不是有效的链接');
  }
  const sid = url.searchParams.get('sid');
  const hash = url.searchParams.get('hash');
  if (!sid || !hash) throw new Error('链接缺少 sid/hash，可能不是 ZCode 配对二维码');
  return {
    id: sid,
    sid,
    hash,
    origin: url.origin,
    relayWsUrl: `${url.protocol === 'https:' ? 'wss:' : 'ws:'}//${url.host}/ws`,
    mid: url.searchParams.get('mid') || '',
    name: url.searchParams.get('name') || '',
    appVersion: url.searchParams.get('app_version') || '',
    pairedAt: Number(url.searchParams.get('t')) || 0,
    link: url.toString(),
    // 官方手机端页面：扫码后打开的就是它
    consoleUrl: url.toString(),
  };
}

export class RelayError extends Error {
  constructor(code, message) {
    super(message || code);
    this.code = code;
  }
}

/**
 * 一次中继会话：连接 → 认证 → 收发应用层 payload。
 * 用法：
 *   const s = new RelaySession(device);
 *   await s.open();                      // 等到 paired / waiting
 *   const bs = await s.bootstrap();
 *   const ws = await s.workspaceList();
 *   s.close();
 */
export class RelaySession {
  constructor(device, { log = () => {}, timeoutMs = 20000 } = {}) {
    this.device = device;
    this.log = log;
    this.timeoutMs = timeoutMs;
    this.ws = null;
    this.pairStatus = null;
    this.closed = false;
    this.seq = 0;
    this._waiters = new Set(); // {predicate, resolve, reject, timer}
    this._statusWaiters = new Set(); // 等 pair_status 变化的等待者
    this._opened = null;
  }

  _send(msg) {
    if (!this.ws || this.ws.readyState !== 1) throw new RelayError('NOT_CONNECTED', '中继连接未就绪');
    this.ws.send(JSON.stringify(msg));
  }

  /** 发送应用层消息：relay 包装成 {type:"data", payload} */
  sendPayload(payload) {
    this._send({ type: 'data', payload, seq: ++this.seq });
  }

  /** 等配对状态变成目标值（设备可能晚几秒才连上中继） */
  waitForStatus(target, timeoutMs = 2500) {
    if (this.pairStatus === target) return Promise.resolve(target);
    return new Promise((resolve, reject) => {
      const waiter = {
        predicate: null,
        resolve,
        reject,
        timer: setTimeout(() => {
          this._statusWaiters.delete(waiter);
          reject(new RelayError('TIMEOUT', `等待状态 ${target} 超时`));
        }, timeoutMs),
        statusTarget: target,
      };
      this._statusWaiters.add(waiter);
    });
  }

  /** 等待某个应用层 payload 满足条件 */
  waitPayload(predicate, timeoutMs = this.timeoutMs, what = 'response') {
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        this._waiters.delete(waiter);
        reject(new RelayError('TIMEOUT', `等待 ${what} 超时（${timeoutMs}ms）`));
      }, timeoutMs);
      this._waiters.add(waiter);
    });
  }

  /** 请求/响应：发 payload，等 predicate 命中的 payload */
  request(payload, predicate, timeoutMs = this.timeoutMs, what = 'response') {
    const p = this.waitPayload(predicate, timeoutMs, what);
    this.sendPayload(payload);
    return p;
  }

  open(timeoutMs = this.timeoutMs) {
    if (this._opened) return this._opened;
    const device = this.device;
    this._opened = new Promise((resolve, reject) => {
      const wsUrl = new URL(device.relayWsUrl || `${device.origin.replace(/^http/, 'ws')}/ws`);
      if (device.mid) wsUrl.searchParams.set('mid', device.mid);
      this.log(`连接中继 ${wsUrl.origin}${wsUrl.pathname} sid=${device.sid}`);
      const ws = new WebSocket(wsUrl.toString());
      this.ws = ws;
      const fail = (err) => {
        clearTimeout(timer);
        try { ws.close(); } catch {}
        reject(err instanceof Error ? err : new RelayError('RELAY', String(err)));
      };
      const timer = setTimeout(() => fail(new RelayError('TIMEOUT', `连接中继超时（${timeoutMs}ms）`)), timeoutMs);

      ws.addEventListener('open', () => {
        this.log('中继已连接，发送 auth_init');
        this._send({
          type: 'auth_init',
          role: ROLES.terminal,
          device_sid: device.sid,
          meta: { platform: 'web', version: 'zcode-hub', name: 'zcode-hub' },
          client_ts: Date.now(),
        });
      });

      ws.addEventListener('message', (ev) => {
        let msg;
        try { msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data)); } catch { return; }
        if (!msg || typeof msg !== 'object' || !msg.type) return;
        switch (msg.type) {
          case 'auth_challenge': {
            this._send({
              type: 'auth_response',
              device_sid: device.sid,
              proof: calculateProof(device.hash, msg.nonce, ROLES.terminal, device.sid),
              client_ts: Date.now(),
            });
            break;
          }
          case 'auth_ack':
          case 'pair_status_ack': {
            this.pairStatus = msg.pair_status || this.pairStatus;
            this.log(`pair_status=${this.pairStatus}`);
            for (const w of [...this._statusWaiters]) {
              if (w.statusTarget === this.pairStatus) {
                clearTimeout(w.timer);
                this._statusWaiters.delete(w);
                w.resolve(this.pairStatus);
              }
            }
            if (this.pairStatus === 'matched' || this.pairStatus === 'waiting') {
              clearTimeout(timer);
              resolve(this.pairStatus);
            }
            break;
          }
          case 'data': {
            const payload = normalizePayload(msg.payload);
            if (!payload) return;
            for (const w of [...this._waiters]) {
              if (w.predicate(payload)) {
                clearTimeout(w.timer);
                this._waiters.delete(w);
                w.resolve(payload);
              }
            }
            break;
          }
          case 'error': {
            const code = msg.code || 'INTERNAL';
            // DEVICE_OFFLINE：设备当前没开远程控制，属于正常情况
            fail(new RelayError(code, msg.message || code));
            break;
          }
        }
      });

      ws.addEventListener('error', () => fail(new RelayError('RELAY', '中继连接出错')));
      ws.addEventListener('close', (ev) => {
        if (!this.closed && this._waiters.size) {
          fail(new RelayError('CLOSED', `中继连接关闭（code=${ev.code}）`));
        }
      });
    });
    return this._opened;
  }

  async bootstrap(timeoutMs = this.timeoutMs) {
    const requestId = rid('bootstrap');
    const res = await this.request(
      { zcode_type: 'bootstrap-request', requestId },
      (p) => p.zcode_type === 'bootstrap-response' && p.requestId === requestId,
      timeoutMs,
      'bootstrap',
    );
    return res.result;
  }

  async workspaceList(timeoutMs = this.timeoutMs) {
    const requestId = rid('workspace-list');
    const res = await this.request(
      { zcode_type: 'workspace-list-request', requestId },
      (p) => p.zcode_type === 'workspace-list-response' && p.requestId === requestId,
      timeoutMs,
      'workspace-list',
    );
    return res.result;
  }

  /** 平台能力调用（官方客户端用它取版本等） */
  async platform(method, args, timeoutMs = this.timeoutMs) {
    const requestId = rid('platform');
    const res = await this.request(
      { zcode_type: 'platform-request', requestId, method, args },
      (p) => p.zcode_type === 'platform-response' && p.requestId === requestId && p.method === method,
      timeoutMs,
      `platform:${method}`,
    );
    if (!res.success) throw new RelayError('PLATFORM', res.error || 'platform call failed');
    return res.result;
  }

  close() {
    this.closed = true;
    for (const w of this._waiters) {
      clearTimeout(w.timer);
      w.reject(new RelayError('CLOSED', '会话已关闭'));
    }
    this._waiters.clear();
    for (const w of this._statusWaiters) {
      clearTimeout(w.timer);
      w.reject(new RelayError('CLOSED', '会话已关闭'));
    }
    this._statusWaiters.clear();
    try { this.ws?.close(); } catch {}
    this.ws = null;
  }
}

/** payload 可能是对象，也可能是 JSON 字符串 */
function normalizePayload(raw) {
  if (!raw) return null;
  if (typeof raw === 'string') {
    try { return JSON.parse(raw); } catch { return null; }
  }
  return typeof raw === 'object' ? raw : null;
}

function rid(prefix) {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 便捷方法：连一次、取状态、断开。 */
export async function probeDevice(device, { log = () => {}, timeoutMs = 20000 } = {}) {
  const session = new RelaySession(device, { log, timeoutMs });
  try {
    let pairStatus = await session.open(timeoutMs);
    if (pairStatus === 'waiting') {
      // 设备可能刚开启远控、晚一两秒才连上中继，给个宽限期
      await session.waitForStatus('matched', 2000).catch(() => {});
      pairStatus = session.pairStatus;
    }
    const out = { ok: true, pairStatus, workspaces: null, activeWorkspaceKey: null, activeTaskId: null, device: null };
    if (pairStatus === 'matched') {
      const wsList = await session.workspaceList(timeoutMs);
      out.workspaces = wsList?.workspaces ?? [];
      out.activeWorkspaceKey = wsList?.activeWorkspaceKey ?? null;
      out.activeTaskId = wsList?.activeTaskId ?? null;
      try {
        out.device = (await session.bootstrap(timeoutMs)) ?? null;
      } catch { /* bootstrap 失败不影响工作区列表 */ }
    }
    return out;
  } finally {
    session.close();
  }
}
