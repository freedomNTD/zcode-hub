// 验证「二维码 → jsQR 解码 → 解析成设备信息」这条链路（前端扫码逻辑的离线回归测试）。
// 运行：node test/decode.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parsePairingLink } from '../lib/relay.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

/**
 * public/ 下的两个库是 UMD 浏览器包，本项目是 ESM，
 * 这里给它们一个假的 module/window 环境再取导出。
 */
function loadUMD(file, globalName) {
  const src = fs.readFileSync(file, 'utf8');
  const mod = { exports: {} };
  const glob = {};
  // eslint-disable-next-line no-new-func
  new Function('module', 'exports', 'self', 'window', 'define', src)(mod, mod.exports, glob, glob, undefined);
  const exp = mod.exports;
  if (typeof exp === 'function') return exp;
  if (exp && typeof exp[globalName] === 'function') return exp[globalName];
  if (typeof glob[globalName] === 'function') return glob[globalName];
  throw new Error(`无法加载 ${file}`);
}

const qrcode = loadUMD(path.join(here, '..', 'public', 'qrcode.js'), 'qrcode');
const jsQR = loadUMD(path.join(here, '..', 'public', 'jsQR.js'), 'jsQR');

const link = 'https://zcode.z.ai/remote/v4?sid=d_testDEVSID123&hash=AbCdEf%2B1%2F2%3D&t=1789922944182&mid=abc-123&name=my-pc&app_version=0.16.9';

/** 用 qrcode-generator 算出模块矩阵，直接铺成 RGBA 位图（不需要 canvas / 图片编码） */
function renderQR(text, { scale = 6, quiet = 4 } = {}) {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const count = qr.getModuleCount();
  const size = (count + quiet * 2) * scale;
  const rgba = new Uint8ClampedArray(size * size * 4).fill(255);
  for (let r = 0; r < count; r += 1) {
    for (let c = 0; c < count; c += 1) {
      if (!qr.isDark(r, c)) continue;
      for (let y = 0; y < scale; y += 1) {
        for (let x = 0; x < scale; x += 1) {
          const px = ((r + quiet) * scale + y) * size + ((c + quiet) * scale + x);
          rgba[px * 4] = 0;
          rgba[px * 4 + 1] = 0;
          rgba[px * 4 + 2] = 0;
        }
      }
    }
  }
  return { rgba, size, count };
}

let failed = 0;
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
  if (!ok) failed += 1;
}

// 1) 正常识别
const { rgba, size, count } = renderQR(link);
const decoded = jsQR(rgba, size, size, { inversionAttempts: 'attemptBoth' });
check('jsQR 解码二维码', decoded?.data === link, `模块 ${count}x${count}，${size}px`);

// 2) 解码结果能被解析成设备字段
if (decoded) {
  const device = parsePairingLink(decoded.data);
  check('解析 sid', device.sid === 'd_testDEVSID123', device.sid);
  check('解析 hash（含 + / = 需保留）', device.hash === 'AbCdEf+1/2=', device.hash);
  check('解析机器名', device.name === 'my-pc', device.name);
  check('推导中继地址', device.relayWsUrl === 'wss://zcode.z.ai/ws', device.relayWsUrl);
}

// 3) 非二维码图片应当识别失败（避免误添加）
const noise = new Uint8ClampedArray(200 * 200 * 4);
for (let i = 0; i < noise.length; i += 4) {
  const v = Math.random() > 0.5 ? 255 : 0;
  noise[i] = noise[i + 1] = noise[i + 2] = v;
  noise[i + 3] = 255;
}
check('随机噪点不会误识别', jsQR(noise, 200, 200, { inversionAttempts: 'attemptBoth' }) === null);

// 4) 不是配对链接的内容要报错
try {
  parsePairingLink('https://example.com/hello');
  check('非配对链接被拒绝', false);
} catch (err) {
  check('非配对链接被拒绝', /sid\/hash/.test(err.message), err.message);
}

console.log(failed === 0 ? '\n全部通过' : `\n有 ${failed} 项失败`);
process.exit(failed === 0 ? 0 : 1);
