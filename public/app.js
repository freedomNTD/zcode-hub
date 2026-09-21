// ZCode 多机面板 —— 前端逻辑（无框架）
const $ = (sel) => document.querySelector(sel);

const state = {
  token: '',
  devices: [],
  status: {},      // id -> 探测结果
  busy: new Set(), // 正在探测的设备 id
  scanning: false,
  stream: null,
};

/* ---------------- 令牌 ---------------- */
function loadToken() {
  const params = new URLSearchParams(location.search);
  const fromUrl = params.get('k') || new URLSearchParams(location.hash.slice(1)).get('k');
  if (fromUrl) {
    localStorage.setItem('zcodehub.token', fromUrl);
    return fromUrl;
  }
  return localStorage.getItem('zcodehub.token') || '';
}

/* ---------------- HTTP ---------------- */
async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: {
      'content-type': 'application/json',
      'x-hub-token': state.token,
      ...(options.headers || {}),
    },
  });
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

/* ---------------- 工具 ---------------- */
function toast(message, ms = 2600) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = message;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), ms);
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function baseName(p) {
  if (!p) return '';
  const parts = String(p).split(/[\\/]/).filter(Boolean);
  return parts[parts.length - 1] || p;
}

function statusView(device) {
  const st = state.status[device.id];
  if (!st) return { cls: '', text: '未检测' };
  if (st.error) {
    const map = {
      DEVICE_OFFLINE: '设备离线',
      AUTH_FAILED: '配对失效',
      WRONG_PARAM: '配对失效',
      TIMEOUT: '超时',
      RELAY: '中继不可达',
      CLOSED: '连接断开',
    };
    return { cls: 'err', text: map[st.code] || st.error };
  }
  if (st.pairStatus === 'matched') return { cls: 'ok', text: '在线' };
  if (st.pairStatus === 'waiting') return { cls: 'warn', text: '待机', note: '配对正常，但桌面端当前没开「手机远控」' };
  return { cls: '', text: '未知' };
}

/* ---------------- 渲染 ---------------- */
function render() {
  const box = $('#devices');
  box.innerHTML = '';
  $('#empty').classList.toggle('hidden', state.devices.length > 0);

  for (const device of state.devices) {
    const st = state.status[device.id];
    const view = statusView(device);
    const busy = state.busy.has(device.id);
    const card = document.createElement('article');
    card.className = 'card' + (busy ? ' busy' : '');
    card.dataset.id = device.id;

    const chips = (st?.workspaces || []).map((w) => {
      const active = w.key && w.key === st.activeWorkspaceKey;
      return `<span class="chip${active ? ' active' : ''}">${escapeHtml(w.name || baseName(w.path) || w.key)}</span>`;
    }).join('');

    card.innerHTML = `
      <div class="card-head">
        <div>
          <div class="card-title">${escapeHtml(device.label || device.name || '未命名设备')}</div>
          <div class="card-sub">${escapeHtml(device.origin || '')} · ${escapeHtml(device.mid || 'no-mid')}${device.appVersion ? ' · v' + escapeHtml(device.appVersion) : ''}</div>
        </div>
        <span class="pill ${view.cls}">${busy ? '检测中…' : escapeHtml(view.text)}</span>
      </div>
      ${chips ? `<div class="chips">${chips}</div>` : ''}
      <div class="card-meta">
        ${view.note ? escapeHtml(view.note) + '<br>' : ''}
        ${st?.activeTaskId ? `当前任务：${escapeHtml(st.activeTaskId)}` : ''}
        ${st?.ms ? ` <span style="opacity:.6">(${st.ms}ms)</span>` : ''}
      </div>
      <div class="card-actions">
        <button class="primary" data-act="open">打开控制台</button>
        <button data-act="probe">检测状态</button>
        <button data-act="rename">改名</button>
        <button class="danger" data-act="delete">删除</button>
      </div>
      <div class="card-sub" style="margin-top:10px">设备号 ${escapeHtml(device.id)}</div>
    `;
    box.appendChild(card);
  }
}

/* ---------------- 数据操作 ---------------- */
async function loadDevices() {
  const data = await api('/api/devices');
  state.devices = data.devices || [];
  render();
}

async function probeOne(id) {
  state.busy.add(id);
  render();
  try {
    const data = await api('/api/probe', { method: 'POST', body: JSON.stringify({ id }) });
    const result = data.results?.[0];
    if (result) state.status[id] = result;
  } catch (err) {
    state.status[id] = { error: err.message };
  } finally {
    state.busy.delete(id);
    render();
  }
}

async function probeAll() {
  const ids = state.devices.map((d) => d.id);
  if (!ids.length) return toast('还没有设备');
  ids.forEach((id) => state.busy.add(id));
  render();
  try {
    const data = await api('/api/probe', { method: 'POST', body: JSON.stringify({}) });
    for (const result of data.results || []) state.status[result.id] = result;
  } catch (err) {
    toast('检测失败：' + err.message);
  } finally {
    state.busy.clear();
    render();
  }
}

async function addDevice(link) {
  const data = await api('/api/devices', { method: 'POST', body: JSON.stringify({ link }) });
  await loadDevices();
  toast('已添加：' + (data.device?.label || data.device?.name || data.device?.mid || '设备'));
  if (data.device?.id) probeOne(data.device.id);
}

/* ---------------- 二维码扫描 ---------------- */
async function openScan() {
  $('#scanDlg').showModal();
  const canLive = !!(navigator.mediaDevices?.getUserMedia && window.isSecureContext);
  if (!canLive) {
    $('#scanTip').textContent = '当前用 http 打开，浏览器不允许直接开摄像头。点「拍照识别」拍下二维码即可（一样能用）。';
    return;
  }
  try {
    state.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
    const video = $('#video');
    video.srcObject = state.stream;
    await video.play();
    state.scanning = true;
    requestAnimationFrame(scanFrame);
    $('#scanTip').textContent = '把二维码放进取景框，识别成功会自动添加';
  } catch {
    $('#scanTip').textContent = '打不开摄像头，请改用「拍照识别」。';
  }
}

function scanFrame() {
  if (!state.scanning) return;
  const video = $('#video');
  if (video.readyState === video.HAVE_ENOUGH_DATA) {
    const canvas = $('#scanCanvas');
    const w = video.videoWidth, h = video.videoHeight;
    if (w && h) {
      canvas.width = w; canvas.height = h;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(video, 0, 0, w, h);
      const image = ctx.getImageData(0, 0, w, h);
      const code = window.jsQR?.(image.data, w, h, { inversionAttempts: 'attemptBoth' });
      if (code?.data) return void handleScanResult(code.data);
    }
  }
  setTimeout(() => requestAnimationFrame(scanFrame), 220);
}

async function handleScanResult(text) {
  state.scanning = false;
  stopStream();
  $('#scanDlg').close();
  try {
    await addDevice(text);
  } catch (err) {
    toast('这个二维码用不了：' + err.message, 4000);
  }
}

function stopStream() {
  state.scanning = false;
  state.stream?.getTracks().forEach((t) => t.stop());
  state.stream = null;
}

async function decodeImageFile(file) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
  const canvas = $('#scanCanvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const code = window.jsQR?.(image.data, canvas.width, canvas.height, { inversionAttempts: 'attemptBoth' });
  return code?.data || null;
}

/* ---------------- 本页二维码 ---------------- */
function showPageQr() {
  const url = location.href;
  $('#qrUrl').textContent = url;
  const box = $('#qrBox');
  box.innerHTML = '';
  try {
    const qr = window.qrcode(0, 'M');
    qr.addData(url);
    qr.make();
    const img = new Image();
    img.src = qr.createDataURL(6, 8);
    img.alt = 'panel url';
    box.appendChild(img);
  } catch {
    box.textContent = url;
  }
  $('#qrDlg').showModal();
}

/* ---------------- 事件绑定 ---------------- */
function bind() {
  $('#btnRefreshAll').addEventListener('click', probeAll);

  $('#btnScan').addEventListener('click', openScan);
  $('#btnScanClose').addEventListener('click', () => {
    stopStream();
    $('#scanDlg').close();
  });

  $('#fileInput').addEventListener('change', async (ev) => {
    const file = ev.target.files?.[0];
    ev.target.value = '';
    if (!file) return;
    $('#scanTip').textContent = '正在识别…';
    try {
      const text = await decodeImageFile(file);
      if (!text) {
        $('#scanTip').textContent = '没识别到二维码，换一张更清晰的照片试试';
        return;
      }
      await handleScanResult(text);
    } catch (err) {
      $('#scanTip').textContent = '识别失败：' + err.message;
    }
  });

  $('#btnPaste').addEventListener('click', () => {
    $('#pasteInput').value = '';
    $('#pasteDlg').showModal();
  });
  $('#btnPasteCancel').addEventListener('click', () => $('#pasteDlg').close());
  $('#btnPasteOk').addEventListener('click', async () => {
    const link = $('#pasteInput').value.trim();
    if (!link) return;
    $('#pasteDlg').close();
    try { await addDevice(link); } catch (err) { toast('添加失败：' + err.message, 4000); }
  });

  $('#btnImport').addEventListener('click', async () => {
    try {
      const data = await api('/api/devices/import-local', { method: 'POST', body: '{}' });
      await loadDevices();
      toast('已导入本机：' + (data.device?.label || ''));
      if (data.device?.id) probeOne(data.device.id);
    } catch (err) {
      toast('导入失败：' + err.message, 4000);
    }
  });

  // 安卓上给一个直接装 App 的入口（APK 由 android-app/build.mjs 生成并放到 public/）
  if (/Android/i.test(navigator.userAgent)) {
    const btn = $('#btnApk');
    if (btn) {
      btn.classList.remove('hidden');
      btn.addEventListener('click', () => { location.href = '/zcode-hub.apk'; });
    }
  }

  // 在 App 里（原生壳暴露了 Android 桥）时，给一个改面板地址的入口
  if (window.Android && typeof window.Android.openSettings === 'function') {
    const btn = $('#btnAppSettings');
    if (btn) {
      btn.classList.remove('hidden');
      btn.addEventListener('click', () => window.Android.openSettings());
    }
  }

  $('#btnShowQr').addEventListener('click', showPageQr);
  $('#btnQrClose').addEventListener('click', () => $('#qrDlg').close());

  $('#devices').addEventListener('click', async (ev) => {
    const button = ev.target.closest('button[data-act]');
    if (!button) return;
    const id = button.closest('.card').dataset.id;
    const device = state.devices.find((d) => d.id === id);
    const act = button.dataset.act;

    if (act === 'probe') return probeOne(id);
    if (act === 'open') return window.open(`/c/${encodeURIComponent(id)}?k=${encodeURIComponent(state.token)}`, '_blank');
    if (act === 'rename') {
      const label = prompt('给这台设备起个名字', device.label || device.name || '');
      if (label === null) return;
      await api(`/api/devices/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify({ label }) });
      return loadDevices();
    }
    if (act === 'delete') {
      if (!confirm('删除这台设备？（只影响本面板的记录）')) return;
      await api(`/api/devices/${encodeURIComponent(id)}`, { method: 'DELETE' });
      delete state.status[id];
      return loadDevices();
    }
  });

  $('#btnSaveToken').addEventListener('click', () => {
    const raw = $('#tokenInput').value.trim();
    if (!raw) return;
    const m = raw.match(/[?&#]k=([^&\s]+)/);
    state.token = m ? decodeURIComponent(m[1]) : raw;
    localStorage.setItem('zcodehub.token', state.token);
    boot();
  });
}

/* ---------------- 启动 ---------------- */
async function boot() {
  if (!state.token) {
    $('#setup').classList.remove('hidden');
    $('#panel').classList.add('hidden');
    return;
  }
  try {
    await loadDevices();
    $('#setup').classList.add('hidden');
    $('#panel').classList.remove('hidden');
    if (state.devices.length) probeAll();
  } catch (err) {
    if (String(err.message).includes('unauthorized')) {
      localStorage.removeItem('zcodehub.token');
      state.token = '';
      $('#setup').classList.remove('hidden');
      $('#panel').classList.add('hidden');
      return;
    }
    toast('加载失败：' + err.message);
  }
}

/** http 访问时的升级提示：https 才能「安装成 App」+ 实时摄像头扫码 */
function showHttpsTip() {
  if (location.protocol !== 'http:') return;
  if (['localhost', '127.0.0.1'].includes(location.hostname)) return;
  const httpsUrl = `https://${location.hostname}:8443/?k=${encodeURIComponent(state.token)}`;
  const tip = document.createElement('div');
  tip.className = 'hint';
  tip.style.marginTop = '12px';
  tip.innerHTML = `当前是 http，浏览器只允许「拍照识别」二维码，也不能安装成应用。<br>`
    + `想要独立图标 + 实时扫码：<br>`
    + `1) 先装一次 <a href="/ca.crt" style="color:var(--accent)">CA 证书</a>（手机设置里搜索「安装证书 / 凭据」）<br>`
    + `2) 再用 <a href="${httpsUrl}" style="color:var(--accent)">${httpsUrl}</a> 打开<br>`
    + `3) 浏览器菜单里选「安装应用 / 添加到主屏幕」`;
  const panel = document.querySelector('#panel');
  const anchor = document.querySelector('#hint');
  anchor ? anchor.after(tip) : panel?.prepend(tip);
}

// 注册 Service Worker，满足 PWA 可安装条件（仅 https/localhost 下浏览器才允许）
if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}

state.token = loadToken();
bind();
boot().then(showHttpsTip);

// 注意：中继同一时刻只允许一个终端在线。切回本页时不要自动探测，
// 否则会把正在用的官方控制台挤下线（错误码 KICKED）。状态更新请手动点「检测状态 / 刷新全部」。
