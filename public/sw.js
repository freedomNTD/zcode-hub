// 只为满足 PWA 可安装条件（Chrome 要求有 fetch 处理器的 Service Worker）。
// 不缓存任何东西：面板状态实时变化，缓存只会带来困扰。
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => { /* 全部走网络 */ });
