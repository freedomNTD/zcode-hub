# ZCode 多机面板（zcode-hub）

一个跑在你自己电脑上的小服务 + 手机网页，用来**扫码配对多台 ZCode，并在一个页面里统一管理**。

## 它解决什么问题

ZCode 自带「手机远控 / Web 远程控制」：桌面端显示一个二维码，手机扫码后打开官方移动端控制台。
问题是**一台机器一个二维码、一个页面**，手机上管理多台很麻烦。

这个面板做的事：

- 手机**扫码 / 拍照**把每台 ZCode 的配对二维码收进来，多台集中管理
- 一个页面看到所有机器的**在线状态、工作区列表、当前任务**
- 一键**打开任意一台的官方控制台**（对话、执行等操作仍走官方页面，功能完整）

## 快速开始

1. 需要 Node 22 或更高（用到内置 WebSocket）。本机 `node -v` 是 v24，满足。
2. 双击 `start.cmd`，或者命令行运行：

   ```bash
   cd "D:/zcode project/1/zcode-hub"
   node server.mjs
   ```

3. 终端会打印地址，形如 `http://192.168.x.x:8787/?k=<令牌>`。
4. 手机连同一个 Wi-Fi，打开这个地址；建议「添加到主屏幕」，用起来跟 App 一样。
5. 添加设备，三种方式都行：
   - **扫码添加**：ZCode 桌面端打开「手机远控」，对着屏幕上的二维码扫
   - **导入本机**：免扫码，直接读取这台电脑上 ZCode 已保存的配对信息（密钥用 ZCode 自己的算法解密）
   - **粘贴链接**：把配对链接贴进来
6. 多台机器重复第 5 步，之后都在一个页面里看。

## 装成手机 App（推荐做一次）

浏览器只在**安全来源**下才允许开摄像头，也才提供「安装应用」。所以走一次 HTTPS：

1. 在电脑上生成证书（只需一次）：

   ```bash
   bash make-cert.sh          # 自动用局域网 IP 签发，证书在 certs/
   ```

2. 重启服务：`node server.mjs`，启动信息里会出现 `https://<你的IP>:8443/?k=...`

3. 手机**先装一次 CA 证书**：用现在这个 http 地址打开面板，点提示里的「CA 证书」下载，
   然后在手机设置里搜索「安装证书 / 凭据」→ 选「CA 证书」→ 安装。
   （iOS：下载描述文件后，还要去 设置 → 通用 → 关于本机 → 证书信任设置 里手动打开。）

4. 用 `https://<你的IP>:8443/?k=<令牌>` 打开面板 → 浏览器菜单里选**「安装应用 / 添加到主屏幕」**。
   之后就有独立图标、全屏窗口，和真 App 一样，而且**扫码可以用实时摄像头**了。

> 不装证书也能用：http 打开时面板会用 **「拍照识别」**（调系统相机拍二维码、本地解码），
> 功能不缺，只是没有实时取景，也不能"安装"。

## 安卓 App（APK）—— 独立版，不需要任何服务器

产物：`android-app/dist/zcode-hub-0.1.0.apk`（约 580 KB，内置 ZXing 解码，已签名），
同时复制到 `public/zcode-hub.apk`，手机也可以在面板里直接下载。

**架构**：App 自己就是控制台。在 App 里扫一台电脑的配对二维码就存一台，之后每台各自通过
ZCode 的**公网中继** `wss://<origin>/ws`（origin 取自那个二维码）直连。
所以：不需要 hub、不需要隧道，被管的电脑也不需要公网 IP；手机在 4G/5G 或任意 Wi-Fi 下都能用。

界面（全原生，不依赖 WebView）：

- 顶部：标题 + 刷新 + 添加
- 每台设备一张卡片：名称、来源、在线/待机标签、工作区芯片、当前任务，
  按钮为「打开控制台 / 检测 / 改名 / 删除」
- 「添加」→ 扫码（实时取景 + ZXing 解码）或粘贴配对链接
- 「打开控制台」用系统浏览器打开该机器的官方手机控制台（对话等完整能力都在那边）

**实测记录**（Android 14 模拟器，AVD `zctest`）：

- 扫码链路自检 PASS：现场生成二维码 → 转成相机同款 NV21 → 走与取景框完全相同的解码路径，
  正确解出 `sid/hash`（含 hash 里的 `+` `=`）并推导出 `wss://zcode.z.ai/ws`
- 加入真实设备后直连中继成功，拿到 `pair_status`（桌面端没开远控时显示「待机」）
- 摄像头取景画面正常，权限弹窗流程正常

> **修掉的坑（都会在真机上直接踩到）**
>
> 1. **黑屏**：早期版本主界面是 WebView，页面一加载失败就只剩深色空白。现在主界面全原生，
>    任何失败都会在界面上写明具体原因。
> 2. **第一次扫码是黑屏**：`surfaceCreated` 早于摄像头授权，授权后没有重新打开相机 →
>    现在在授权回调里补开。
> 3. **摄像头权限**：只在清单里声明 `CAMERA` 不够，Android 6+ 必须运行时申请。
> 4. 顺手修掉「任务 null」「no-mid」之类的显示瑕疵。

### 自检与调试

```bash
# 扫码链路自检（界面顶部会显示 PASS / FAIL 和解析结果）
adb shell "am start -n com.zcodehub.app/.MainActivity --ez selftest true"

# 直接添加一台设备（方便脚本化 / 远程操作）
adb shell "am start -n com.zcodehub.app/.MainActivity --es link '<配对链接>'"
```

签名证书：`android-app/keystore/zcode-hub.jks`（口令 `zcodehub`，别名 `zcodehub`）。
重新构建：`node android-app/build.mjs`。

## 把 APK 发到手机上

三种方式，按方便程度排：

1. **面板里直接下**：手机打开面板 → 点「下载安卓 App」（走的是本机 `/zcode-hub.apk`）。
2. **IM 推送**：`node tools/send-im.mjs`（默认发 `public/zcode-hub.apk`）。
   用的是你 ZCode 里已经配好的 bot 凭据，凭据直接读 ZCode 的加密存储、不落地。
   - **飞书**：可以发文件 ✓（`im/v1/files` 上传 + `im/v1/messages` 发送）
   - **企业微信 / iLink**：**只能发文本**。ZCode 的实现里 `item_list` 只有 `type:1 text_item`，
     没有 file 项；而且主动发送还需要会话的 `context_token`（只能从收到的消息里拿），所以安装包走不了它。
     要发文本通知：`WEIXIN_USER_ID=xxxx@im.bot node tools/send-im.mjs`。
3. **数据线 / 微信文件传输助手**等常规方式。

## 状态含义

| 显示 | 含义 |
| --- | --- |
| 在线 | 桌面端已开启远控，并且中继上能连到它 |
| 待机（桌面端未开远控） | 配对信息有效，但桌面端此刻没开「手机远控」 |
| 配对失效 | 需要重新在桌面端开启远控并重新扫码 |
| 超时 / 中继不可达 | 这台电脑到 `wss://zcode.z.ai/ws` 不通 |

## 排错

> 历史坑：更早的版本主界面完全是一个 WebView，一旦 WebView 不工作或页面加载失败，
> 就只剩深色空白（看起来就是黑屏），什么信息都没有。现在主界面改成原生，这个问题从根上消失了。

**App 里连不上（界面上会直接写明原因）**

- 先确认电脑上面板在跑：终端里应该有 `http://192.168.x.x:8787/?k=...` 那行
- 手机必须和电脑在**同一个 Wi-Fi**（用流量打不开局域网地址）
- 电脑换了网络/IP 变了：在 App 设置界面里改成新地址（或在面板里点「改面板地址」）
- 用手机浏览器打开同一个地址也打不开 → 是网络/防火墙问题，不是 App 的问题

**点「扫码添加」提示打不开摄像头**

- 系统设置 → 应用 → ZCode 多机面板 → 权限 → 相机 → 允许
- App 默认用 https 打开面板，就是为了让页面处于安全上下文；http 下浏览器/WebView 不给摄像头

**状态显示「待机」**：配对信息是好的，只是 ZCode 桌面端此刻没开「手机远控」。

## 安全说明

- 面板有一个访问令牌，存在 `data/secret.json`，打开地址时通过 `?k=` 带上；接口都校验这个令牌。
- 配对密钥（passHash）只落在跑服务的这台电脑上（`data/devices.json`），**不下发给前端**；
  「打开控制台」走服务端 302 跳转，链接不经过页面 JS。
- 服务监听 `0.0.0.0`，请只在**自己的局域网**里用，不要把端口暴露到公网。
- 删掉 `data/` 目录就等于清空所有配对记录。

## 已知限制

- 面板本身不做对话：聊天的协议（`rpc-frame` 分片 + 校验和 + ack + 桥接会话）很重，重写既容易过时也没必要，
  所以「打开控制台」用的是官方移动端页面，功能完整。
- 同一时刻中继只允许一个终端（手机）在线：官方页面和本面板同时连会互相挤掉（错误码 `KICKED`）。
- 中继地址取二维码里的 origin（本机实测是 `https://zcode.z.ai`）。

## 协议笔记

逆向自 ZCode 桌面端 `resources/app.asar` 和官方移动端 bundle，供后续扩展参考。

**配对二维码内容**

```
https://<origin>/remote/v4?sid=<deviceSid>&hash=<passHash>&t=<ts>&mid=<deviceMid>&name=<设备名>&app_version=<版本>
```

**中继连接**：`<origin>` → `wss://<host>/ws`，可带 `?mid=<deviceMid>`。

**握手（手机侧角色 = `terminal`）**

1. `{ type:"auth_init", role:"terminal", device_sid, meta, client_ts }`
2. ← `{ type:"auth_challenge", nonce }`
3. `{ type:"auth_response", device_sid, proof, client_ts }`
   `proof = base64url(HMAC-SHA256(passHash, "<nonce>|terminal|<deviceSid>"))`
4. ← `{ type:"auth_ack" | "pair_status_ack", pair_status: "waiting" | "matched" }`

心跳：`{ type:"pair_status_query", device_sid, client_ts }`（约每 10 秒）

**应用层**：`{ type:"data", payload }`，`payload.zcode_type` 取值：

- `bootstrap-request` → `bootstrap-response`（含 `result.workspaces`）
- `workspace-list-request` → `workspace-list-response`（`result.workspaces` / `activeWorkspaceKey` / `activeTaskId`）
- `platform-request`、`workspace-bridge-open`、`workspace-reconnect-request`、`mobile-view-state-update`
- 新版对话走 `rpc-frame` / `rpc-frame-ack`（本面板未实现）

**错误码**：`KICKED`、`DEVICE_OFFLINE`、`AUTH_FAILED`、`WRONG_PARAM`、`INTERNAL`

## 目录结构

```
zcode-hub/
├── server.mjs            # HTTP(8787) + HTTPS(8443) 服务，API，令牌校验，/c/:id 跳转，/ca.crt
├── make-cert.sh          # 生成本地 CA + 服务器证书（SAN 带局域网 IP）
├── start.cmd             # Windows 一键启动
├── lib/relay.mjs         # 中继客户端（握手、心跳、payload 收发）
├── lib/store.mjs         # 设备清单存储 + 从本机 ZCode 导入（含凭据解密）
├── tools/make-icons.mjs  # 生成 PWA 图标（自己写 PNG 编码，无依赖）
├── tools/send-im.mjs     # 把 APK 通过 ZCode 里的 bot 通道发到飞书/企业微信
├── public/index.html     # 手机页面
├── public/app.js         # 前端逻辑（扫码、拍照解码、状态渲染、SW 注册）
├── public/style.css
├── public/manifest.webmanifest / sw.js / icon-*.png  # PWA（可安装）
├── public/jsQR.js        # 二维码解码（jsQR, Apache-2.0）
├── public/qrcode.js      # 二维码生成（qrcode-generator, MIT）
├── test/handshake.mjs    # 用本机凭据真实握手一次，验证协议
├── certs/                # 运行 make-cert.sh 后生成（CA + 服务器证书）
├── android-app/          # 安卓 App（独立版，自带中继客户端）
│   ├── build.mjs         # 不用 Gradle 的构建脚本（aapt2 → javac → d8 → 签名）
│   ├── AndroidManifest.xml
│   ├── libs/             # ZXing core（二维码解码）
│   ├── src/com/zcodehub/app/
│   │   ├── MainActivity.java   # 多机列表、状态、打开控制台、添加设备
│   │   ├── ScanActivity.java   # 实时取景扫码（Camera + ZXing）
│   │   ├── RelayClient.java    # ZCode 中继协议（terminal 角色握手 + payload）
│   │   ├── WebSocketClient.java# 自己实现的极简 WebSocket 客户端
│   │   ├── DeviceStore.java    # 多台设备的存储与配对链接解析
│   │   └── SelfTest.java       # 扫码链路自检
│   ├── res/              # 图标与应用名
│   ├── keystore/         # 签名证书（口令 zcodehub）
│   └── dist/             # 产物 APK
└── data/                 # 运行时生成：secret.json（令牌）、devices.json（设备）
```

自检：

```bash
node test/handshake.mjs   # 用本机凭据真实连一次中继，期望看到 pair_status=waiting 或 matched
node test/decode.mjs      # 渲染二维码 → jsQR 解码 → 解析成设备字段，全链路离线回归
```
