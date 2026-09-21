// 生成 PWA 图标（无第三方依赖，自己写 PNG）。
// 运行：node tools/make-icons.mjs
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const outDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');

function crc32(buf) {
  let c;
  const table = [];
  for (let n = 0; n < 256; n += 1) {
    c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (const byte of buf) crc = table[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function png(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // RGBA
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** 圆角渐变底 + 白色 "Z" */
function drawIcon(size) {
  const buf = Buffer.alloc(size * size * 4);
  const radius = size * 0.22;
  const stroke = Math.max(2, Math.round(size * 0.075));
  // Z 的三段：上横、下横、斜线
  const zTop = Math.round(size * 0.28);
  const zBottom = Math.round(size * 0.72);
  const zLeft = Math.round(size * 0.26);
  const zRight = Math.round(size * 0.74);
  const barH = Math.round(size * 0.085);

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const t = (x / size) * 0.6 + (y / size) * 0.4;
      let r = Math.round(76 + t * (123 - 76));
      let g = Math.round(141 + t * (92 - 141));
      let b = Math.round(255 + t * (255 - 255));
      let a = 255;

      // 圆角裁切
      const cx = Math.min(Math.max(x, radius), size - radius);
      const cy = Math.min(Math.max(y, radius), size - radius);
      const d = Math.hypot(x - cx, y - cy);
      if (d > radius) {
        const fade = Math.max(0, 1 - (d - radius) / 1.5);
        a = Math.round(255 * fade);
      }

      // 白色 Z
      const inTopBar = y >= zTop && y < zTop + barH && x >= zLeft && x <= zRight;
      const inBottomBar = y >= zBottom - barH && y <= zBottom && x >= zLeft && x <= zRight;
      let inDiagonal = false;
      if (!inTopBar && !inBottomBar && y > zTop && y < zBottom) {
        const p = (y - zTop) / (zBottom - zTop);       // 0 → 1 自上而下
        const cxNow = zRight - p * (zRight - zLeft);   // 从右上到左下
        inDiagonal = Math.abs(x - cxNow) <= stroke;
      }
      if (inTopBar || inBottomBar || inDiagonal) {
        r = 255; g = 255; b = 255;
      }

      const i = (y * size + x) * 4;
      buf[i] = r; buf[i + 1] = g; buf[i + 2] = b; buf[i + 3] = a;
    }
  }
  return png(size, size, buf);
}

for (const [name, size] of [['icon-192.png', 192], ['icon-512.png', 512], ['apple-touch-icon.png', 180]]) {
  fs.writeFileSync(path.join(outDir, name), drawIcon(size));
  console.log('written', name, size + 'x' + size);
}
