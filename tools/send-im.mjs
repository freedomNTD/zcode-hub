// 通过 ZCode 里已配置的 bot 通道，把文件发到你的 IM。
// 用法：node tools/send-im.mjs [文件路径] [--text "只发文本"]
// 凭据直接读 ZCode 自己的加密存储（credentials.json），不落地、不打印。
//
// 通道说明（逆向自 ZCode 的 bot provider 实现）：
//   飞书  ：可以发文件（im/v1/files 上传 + im/v1/messages 发送）
//   企业微信(iLink)：只能发文本，item_list 只有 type:1 text_item，没有 file 项
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';

const ZCODE_DIR = path.join(os.homedir(), '.zcode', 'v2');
// 自己的飞书自建应用：app id 与接收人 open_id 用环境变量传，别写死在仓库里
const FEISHU_APP_ID = process.env.FEISHU_APP_ID || '';
const FEISHU_OPEN_ID = process.env.FEISHU_OPEN_ID || '';
const API = 'https://open.feishu.cn/open-apis';

const filePath = process.argv[2] || path.join(process.cwd(), 'public', 'zcode-hub.apk');

function credentialKey(id) {
  return `bot:${id}:credential`;
}

function decryptCredential(value) {
  if (!String(value).startsWith('enc:v1:')) return value;
  const secret = process.env.ZCODE_CREDENTIAL_SECRET
    || `zcode-credential-fallback:${process.platform}:${os.homedir()}:${os.userInfo().username}`;
  const key = crypto.createHash('sha256').update(secret).digest();
  const [iv, tag, ct] = value.slice(7).split('.');
  const d = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64url'));
  d.setAuthTag(Buffer.from(tag, 'base64url'));
  return Buffer.concat([d.update(Buffer.from(ct, 'base64url')), d.final()]).toString('utf8');
}

function loadBotConfig() {
  const cfg = JSON.parse(fs.readFileSync(path.join(ZCODE_DIR, 'bot-config.v3.json'), 'utf8'));
  return cfg.bots || [];
}

function loadCreds() {
  return JSON.parse(fs.readFileSync(path.join(ZCODE_DIR, 'credentials.json'), 'utf8'));
}

async function feishuToken(appSecret) {
  const res = await fetch(`${API}/auth/v3/tenant_access_token/internal`, {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ app_id: FEISHU_APP_ID, app_secret: appSecret }),
  });
  const json = await res.json();
  if (json.code !== 0) throw new Error(`取 tenant_access_token 失败：${json.code} ${json.msg}`);
  return json.tenant_access_token;
}

async function feishuSendFile(token, receiveId, filePath_, fileName) {
  const buf = fs.readFileSync(filePath_);
  const form = new FormData();
  form.append('file_type', 'stream');
  form.append('file_name', fileName);
  form.append('file', new Blob([buf]), fileName);

  const up = await fetch(`${API}/im/v1/files`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: form,
  });
  const upJson = await up.json();
  if (upJson.code !== 0) throw new Error(`上传文件失败：${upJson.code} ${upJson.msg}`);
  const fileKey = upJson.data.file_key;
  console.log(`  上传成功 file_key=${String(fileKey).slice(0, 18)}…`);

  const send = await fetch(`${API}/im/v1/messages?receive_id_type=open_id`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify({
      receive_id: receiveId,
      msg_type: 'file',
      content: JSON.stringify({ file_key: fileKey }),
    }),
  });
  const sendJson = await send.json();
  if (sendJson.code !== 0) throw new Error(`发送消息失败：${sendJson.code} ${sendJson.msg}`);
  return sendJson.data;
}

/* ---------------- 企业微信（iLink）：仅文本 ---------------- */
const ILINK_BASE = 'https://ilinkai.weixin.qq.com';
const ILINK_PREFIX = '/ilink/bot';

function ilinkHeaders(token) {
  const uin = Buffer.from(String(Math.floor(Math.random() * 4294967296)), 'utf8').toString('base64');
  return {
    'content-type': 'application/json',
    AuthorizationType: 'ilink_bot_token',
    Authorization: `Bearer ${token}`,
    'X-WECHAT-UIN': uin,
  };
}

async function ilinkSendText(token, fromUserId, toUserId, text) {
  const res = await fetch(`${ILINK_BASE}${ILINK_PREFIX}/sendmessage`, {
    method: 'POST',
    headers: ilinkHeaders(token),
    body: JSON.stringify({
      msg: {
        from_user_id: fromUserId,
        to_user_id: toUserId,
        client_id: `zcode-hub-${crypto.randomUUID()}`,
        message_type: 2,
        message_state: 2,
        item_list: [{ type: 1, text_item: { text: text.replace(/\r\n|\r|\n/g, '\r ') } }],
      },
      base_info: { channel_version: '2.0.0' },
    }),
  });
  const json = await res.json().catch(() => ({}));
  const ret = json.ret ?? json.errcode;
  if (!res.ok || (ret !== undefined && ret !== 0)) {
    throw new Error(`iLink 发送失败：HTTP ${res.status} ${JSON.stringify(json).slice(0, 200)}`);
  }
  return json;
}

const bots = loadBotConfig();
const creds = loadCreds();
const feishuBot = bots.find((b) => b.provider === 'feishu');

if (!fs.existsSync(filePath)) {
  console.error('文件不存在：' + filePath);
  process.exit(1);
}
const size = (fs.statSync(filePath).size / 1024).toFixed(1);
const fileName = path.basename(filePath);
console.log(`要发送：${fileName} (${size} KB)`);

if (!feishuBot) {
  console.error('ZCode 里没有配置飞书 bot');
  process.exit(1);
}
if (!FEISHU_APP_ID || !FEISHU_OPEN_ID) {
  console.error('需要设置环境变量 FEISHU_APP_ID 与 FEISHU_OPEN_ID（你自己的飞书自建应用）');
  process.exit(1);
}

const appSecret = decryptCredential(creds[credentialKey(feishuBot.id)]);
console.log('飞书通道：');
try {
  const token = await feishuToken(appSecret);
  console.log('  拿到 tenant_access_token ✓');
  const data = await feishuSendFile(token, FEISHU_OPEN_ID, filePath, fileName);
  console.log(`  已发送 ✓ message_id=${data.message_id}`);
} catch (err) {
  console.error('  失败：' + err.message);
  process.exitCode = 2;
}

/* ---------------- 企业微信：只能发文本，所以用来通知 ---------------- */
const weixinBot = bots.find((b) => b.provider === 'weixin');
const WEIXIN_USER_ID = process.env.WEIXIN_USER_ID; // 会话里的用户 ID（形如 xxxx@im.bot）
if (weixinBot && WEIXIN_USER_ID) {
  console.log('企业微信(iLink)通道：');
  const token = decryptCredential(creds[credentialKey(weixinBot.id)]);
  const text = process.env.WEIXIN_TEXT
    || `【ZCode 多机面板】修好的安卓 App 已通过飞书发给你（${fileName}）。`
      + `企业微信这条通道只能发文本、发不了文件，所以 APK 走的是飞书。`;
  try {
    await ilinkSendText(token, weixinBot.providerUserId, WEIXIN_USER_ID, text);
    console.log('  文本已发送 ✓');
  } catch (err) {
    console.error('  失败：' + err.message);
  }
}
