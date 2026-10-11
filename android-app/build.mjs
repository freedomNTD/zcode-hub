// 不依赖 Gradle 的 Android 构建脚本：
//   aapt2 compile/link → javac → d8 → 打包 → zipalign → apksigner
// 运行：node build.mjs
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const JDK = process.env.JAVA_HOME || 'D:/AndroidToolchain/jdk-17.0.20.1+1';
const SDK = process.env.ANDROID_SDK_ROOT || 'D:/AndroidToolchain/sdk';
const BT = path.join(SDK, 'build-tools', process.env.BUILD_TOOLS || '34.0.0');
const PLATFORM = process.env.ANDROID_PLATFORM || 'android-34';

const androidJar = path.join(SDK, 'platforms', PLATFORM, 'android.jar');
const aapt2 = path.join(BT, 'aapt2.exe');
const zipalign = path.join(BT, 'zipalign.exe');
const apksigner = path.join(BT, 'apksigner.bat');
const d8 = path.join(BT, 'd8.bat');
const javac = path.join(JDK, 'bin', 'javac.exe');
const keytool = path.join(JDK, 'bin', 'keytool.exe');
const java = path.join(JDK, 'bin', 'java.exe');

const APP_ID = 'com.zcodehub.app';
const VERSION = '0.2.0';
const VERSION_CODE = '1';
const KS = path.join(here, 'keystore', 'zcode-hub.jks');
const KS_PASS = 'zcodehub';
const KS_ALIAS = 'zcodehub';

const build = path.join(here, 'build');
const gen = path.join(build, 'gen');
const classes = path.join(build, 'classes');
const dexDir = path.join(build, 'dex');
const dist = path.join(here, 'dist');

// .bat/.cmd 走 shell，必须自己把含空格的参数引起来（本项目路径里就有空格）
const quote = (arg) => (/[\s"]/.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg);

function run(label, cmd, args, opts = {}) {
  const useShell = /\.(bat|cmd)$/i.test(cmd);
  const res = spawnSync(cmd, useShell ? args.map(quote) : args, {
    encoding: 'utf8',
    env: { ...process.env, JAVA_HOME: JDK, PATH: `${path.join(JDK, 'bin')};${process.env.PATH}` },
    shell: useShell,
    ...opts,
  });
  const out = `${res.stdout || ''}${res.stderr || ''}`.trim();
  if (out) console.log(`  ${out.split('\n').slice(0, 6).join('\n  ')}`);
  if (res.status !== 0) {
    console.error(`\n[失败] ${label} (exit ${res.status})`);
    process.exit(1);
  }
  console.log(`[ok] ${label}`);
}

function walk(dir, ext) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full, ext));
    else if (entry.name.endsWith(ext)) out.push(full);
  }
  return out;
}

for (const tool of [androidJar, aapt2, zipalign, apksigner, d8, javac, keytool]) {
  if (!fs.existsSync(tool)) {
    console.error(`缺少工具：${tool}\n先安装 Android SDK build-tools / platform，或设置 JAVA_HOME、ANDROID_SDK_ROOT`);
    process.exit(1);
  }
}

fs.rmSync(build, { recursive: true, force: true });
fs.mkdirSync(gen, { recursive: true });
fs.mkdirSync(classes, { recursive: true });
fs.mkdirSync(dexDir, { recursive: true });
fs.mkdirSync(dist, { recursive: true });

console.log('\n== 1/7 编译资源 (aapt2 compile) ==');
run('aapt2 compile', aapt2, ['compile', '--dir', path.join(here, 'res'), '-o', path.join(build, 'res.zip')]);

console.log('\n== 2/7 链接资源与清单 (aapt2 link) ==');
run('aapt2 link', aapt2, [
  'link',
  '-o', path.join(build, 'base.apk'),
  '-I', androidJar,
  '--manifest', path.join(here, 'AndroidManifest.xml'),
  '-R', path.join(build, 'res.zip'),
  '--java', gen,
  '--min-sdk-version', '21',
  '--target-sdk-version', '34',
  '--version-code', VERSION_CODE,
  '--version-name', VERSION,
  '--auto-add-overlay',
]);

// 第三方依赖（ZXing 二维码解码）不进仓库，缺了就自动下载
const ZXING_VERSION = '3.5.3';
const ZXING_URL = 'https://repo1.maven.org/maven2/com/google/zxing/core/' + ZXING_VERSION + '/core-' + ZXING_VERSION + '.jar';
const libsDir = path.join(here, 'libs');
async function ensureZxing() {
  fs.mkdirSync(libsDir, { recursive: true });
  const target = path.join(libsDir, 'core-' + ZXING_VERSION + '.jar');
  if (fs.existsSync(target) && fs.statSync(target).size > 100000) return;
  console.log('  下载 ZXing ' + ZXING_VERSION + ' …');
  const res = await fetch(ZXING_URL);
  if (!res.ok) throw new Error('下载 ZXing 失败：HTTP ' + res.status);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length < 100000) throw new Error('下载的 ZXing 文件不完整');
  fs.writeFileSync(target, buf);
  console.log('  已保存 libs/core-' + ZXING_VERSION + '.jar（' + (buf.length / 1024).toFixed(0) + ' KB）');
}
await ensureZxing();
console.log('\n== 3/7 编译 Java (javac) ==');
const jars = fs.existsSync(libsDir) ? fs.readdirSync(libsDir).filter(f => f.endsWith('.jar')).map(f => path.join(libsDir, f)) : [];
if (jars.length) console.log(`  依赖 jar：${jars.map(j => path.basename(j)).join(', ')}`);
const sources = [...walk(path.join(here, 'src'), '.java'), ...walk(gen, '.java')];
const classpath = [androidJar, ...jars].join(path.delimiter);
run('javac', javac, [
  ...sources,
  '-d', classes,
  '-classpath', classpath,
  '-bootclasspath', androidJar,
  '-source', '8',
  '-target', '8',
  '-encoding', 'UTF-8',
  '-nowarn',
]);

console.log('\n== 4/7 转 dex (d8) ==');
run('d8', d8, [
  '--lib', androidJar,
  '--min-api', '21',
  '--output', dexDir,
  ...walk(classes, '.class'),
  ...jars,
]);

console.log('\n== 5/7 打包 apk（保留 resources.arsc 的存储方式）==');
const py = `
import shutil, sys, zipfile
src, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(out, 'w')
for item in zin.infolist():
    data = zin.read(item.filename)
    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
    zi.compress_type = item.compress_type
    zi.external_attr = item.external_attr
    zout.writestr(zi, data)
zout.write(dex, 'classes.dex')
zout.close()
zin.close()
print('packed', out)
`;
fs.writeFileSync(path.join(build, 'pack.py'), py, 'utf8');
run('pack', 'python', [path.join(build, 'pack.py'), path.join(build, 'base.apk'),
  path.join(dexDir, 'classes.dex'), path.join(build, 'unsigned.apk')]);

console.log('\n== 6/7 对齐 (zipalign) ==');
run('zipalign', zipalign, ['-f', '4', path.join(build, 'unsigned.apk'), path.join(build, 'aligned.apk')]);

console.log('\n== 7/7 签名 (apksigner) ==');
if (!fs.existsSync(KS)) {
  fs.mkdirSync(path.dirname(KS), { recursive: true });
  run('keytool genkey', keytool, [
    '-genkeypair', '-keystore', KS, '-alias', KS_ALIAS,
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
    '-storepass', KS_PASS, '-keypass', KS_PASS,
    '-dname', 'CN=ZCode Hub, OU=Personal, O=Personal, L=-, ST=-, C=CN',
  ]);
}
const apkOut = path.join(dist, `zcode-hub-${VERSION}.apk`);
run('apksigner sign', apksigner, [
  'sign', '--ks', KS, '--ks-key-alias', KS_ALIAS,
  '--ks-pass', `pass:${KS_PASS}`, '--key-pass', `pass:${KS_PASS}`,
  '--out', apkOut, path.join(build, 'aligned.apk'),
]);
run('apksigner verify', apksigner, ['verify', '--print-certs', apkOut]);

// 顺手放到面板的静态目录，手机可以直接下载安装
const publicApk = path.join(here, '..', 'public', 'zcode-hub.apk');
fs.copyFileSync(apkOut, publicApk);

const size = (fs.statSync(apkOut).size / 1024).toFixed(1);
console.log(`\n完成：${apkOut}  (${size} KB)`);
console.log(`已复制到面板：public/zcode-hub.apk（手机打开面板即可下载安装）`);
console.log(`签名证书：${KS}（口令 ${KS_PASS}，别名 ${KS_ALIAS}）`);
