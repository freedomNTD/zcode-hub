// 设备清单存储：data/devices.json + data/secret.json（访问令牌）
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { parsePairingLink } from './relay.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
export const dataDir = path.join(root, '..', 'data');
const devicesFile = path.join(dataDir, 'devices.json');
const secretFile = path.join(dataDir, 'secret.json');

function ensureDir() {
  fs.mkdirSync(dataDir, { recursive: true });
}

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}

export function loadDevices() {
  const raw = readJson(devicesFile, { devices: [] });
  return Array.isArray(raw.devices) ? raw.devices : [];
}

export function saveDevices(devices) {
  ensureDir();
  fs.writeFileSync(devicesFile, JSON.stringify({ devices }, null, 2), 'utf8');
}

/** 新增或更新（按 sid 去重） */
export function upsertDevice(link, extra = {}) {
  const parsed = parsePairingLink(link);
  const devices = loadDevices();
  const existing = devices.find((d) => d.id === parsed.id);
  if (existing) {
    Object.assign(existing, parsed, extra, { updatedAt: Date.now() });
    if (!extra.name) existing.name = existing.name || parsed.name;
    saveDevices(devices);
    return existing;
  }
  const device = {
    ...parsed,
    label: extra.label || parsed.name || parsed.mid || '未命名设备',
    addedAt: Date.now(),
    updatedAt: Date.now(),
  };
  devices.push(device);
  saveDevices(devices);
  return device;
}

export function updateDevice(id, patch) {
  const devices = loadDevices();
  const device = devices.find((d) => d.id === id);
  if (!device) return null;
  Object.assign(device, patch, { updatedAt: Date.now() });
  saveDevices(devices);
  return device;
}

export function removeDevice(id) {
  const devices = loadDevices();
  const next = devices.filter((d) => d.id !== id);
  saveDevices(next);
  return devices.length !== next.length;
}

/** 对外输出时剥离密钥字段 */
export function publicDevice(device) {
  const { hash, link, ...rest } = device;
  return rest;
}

export function getToken() {
  ensureDir();
  const existing = readJson(secretFile, null);
  if (existing?.token) return existing.token;
  const token = crypto.randomBytes(16).toString('base64url');
  fs.writeFileSync(secretFile, JSON.stringify({ token }, null, 2), 'utf8');
  return token;
}

/**
 * 从本机 ZCode 安装里导入已配对设备（无需扫码）。
 * deviceSid 在 setting.json，pass_hash 在 credentials.json（aes-256-gcm 加密）。
 */
export function importLocalDevice(origin = 'https://zcode.z.ai') {
  const zcodeDir = path.join(os.homedir(), '.zcode', 'v2');
  const settings = readJson(path.join(zcodeDir, 'setting.json'), null);
  const creds = readJson(path.join(zcodeDir, 'credentials.json'), null);
  const deviceSid = settings?.webRemoteControlExternalRelayDevice?.deviceSid;
  const encHash = creds?.['web-remote-control:external-relay:pass_hash'];
  if (!deviceSid || !encHash) {
    throw new Error('本机 ZCode 里没有找到已保存的配对信息，请先在桌面端开启一次「手机远控」');
  }
  const passHash = decryptCredential(encHash);
  const link = `${origin}/remote/v4?sid=${encodeURIComponent(deviceSid)}&hash=${encodeURIComponent(passHash)}`
    + `&t=${Date.now()}&name=${encodeURIComponent(os.hostname())}`;
  return upsertDevice(link, { label: os.hostname(), local: true });
}

function decryptCredential(value) {
  if (!String(value).startsWith('enc:v1:')) return value;
  const secret = process.env.ZCODE_CREDENTIAL_SECRET
    || `zcode-credential-fallback:${process.platform}:${os.homedir()}:${os.userInfo().username}`;
  const key = crypto.createHash('sha256').update(secret).digest();
  const [iv, tag, ct] = value.slice(7).split('.');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64url'));
  decipher.setAuthTag(Buffer.from(tag, 'base64url'));
  return Buffer.concat([decipher.update(Buffer.from(ct, 'base64url')), decipher.final()]).toString('utf8');
}
