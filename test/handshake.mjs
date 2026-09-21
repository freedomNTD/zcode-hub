// 用本机 ZCode 已保存的配对凭据做一次真实握手，验证中继协议实现。
// 运行：node test/handshake.mjs
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { RelaySession, parsePairingLink } from '../lib/relay.mjs';

const zcodeDir = path.join(os.homedir(), '.zcode', 'v2');
const settingFile = path.join(zcodeDir, 'setting.json');
const credsFile = path.join(zcodeDir, 'credentials.json');

function deriveKey() {
  const secret = process.env.ZCODE_CREDENTIAL_SECRET
    || `zcode-credential-fallback:${process.platform}:${os.homedir()}:${os.userInfo().username}`;
  return crypto.createHash('sha256').update(secret).digest();
}

function decryptCredential(value) {
  if (!value.startsWith('enc:v1:')) return value;
  const [iv, tag, ct] = value.slice(7).split('.');
  const d = crypto.createDecipheriv('aes-256-gcm', deriveKey(), Buffer.from(iv, 'base64url'));
  d.setAuthTag(Buffer.from(tag, 'base64url'));
  return Buffer.concat([d.update(Buffer.from(ct, 'base64url')), d.final()]).toString('utf8');
}

const settings = JSON.parse(fs.readFileSync(settingFile, 'utf8'));
const creds = JSON.parse(fs.readFileSync(credsFile, 'utf8'));
const deviceSid = settings?.webRemoteControlExternalRelayDevice?.deviceSid;
const passHash = decryptCredential(creds['web-remote-control:external-relay:pass_hash']);
if (!deviceSid || !passHash) throw new Error('本机没有已保存的远程控制配对信息（先在 ZCode 里开启一次手机远控）');

// origin：本机日志里中继是 wss://zcode.z.ai/ws
const device = {
  ...parsePairingLink(`https://zcode.z.ai/remote/v4?sid=${encodeURIComponent(deviceSid)}&hash=${encodeURIComponent(passHash)}&mid=probe&name=local-probe`),
};

console.log('deviceSid =', deviceSid);
console.log('--- 开始握手 ---');
const session = new RelaySession(device, { log: (m) => console.log('[relay]', m), timeoutMs: 25000 });

try {
  const pairStatus = await session.open();
  console.log('pair status =', pairStatus);
  if (pairStatus !== 'matched') {
    console.log('设备当前未配对（桌面端可能没开“手机远控”），到此为止。');
  } else {
    const list = await session.workspaceList();
    console.log('workspaces =', JSON.stringify(list, null, 2).slice(0, 2500));
    const bs = await session.bootstrap();
    console.log('bootstrap =', JSON.stringify(bs, null, 2).slice(0, 2500));
  }
} catch (err) {
  console.log('失败：', err.code || '', err.message);
} finally {
  session.close();
}
