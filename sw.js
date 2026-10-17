/* 잠깐만 service worker (app/ 과 brake/ 두 입구를 함께 관리)
   배포할 때마다 CACHE 버전을 올려 주세요 (예: v0.3.0 → v0.3.1).
   화면은 캐시에서 바로 띄우고 뒤에서 새 버전을 받아 둡니다.
   → 루틴으로 열릴 때 네트워크를 기다리지 않음. 새 버전은 다음 실행부터 적용. */
const CACHE = 'jamkkan-v0.5.0';
const ASSETS = [
  './',
  './index.html',
  './app/',
  './app/index.html',
  './app/manifest.webmanifest',
  './brake/',
  './brake/index.html',
  './brake/manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './icons/icon-180.png',
  './icons/brake-192.png',
  './icons/brake-512.png',
  './icons/brake-maskable-512.png',
  './icons/brake-180.png',
];
const BASE = new URL('./', self.location).pathname; // 예: /jamkkan/

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('jamkkan-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// 화면 주소 → 캐시에 저장된 페이지
function pageKey(url) {
  const rest = url.pathname.slice(BASE.length);
  if (rest.startsWith('brake')) return './brake/index.html';
  if (rest.startsWith('app')) return './app/index.html';
  return './index.html';
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || !url.pathname.startsWith(BASE)) return;

  // 화면 이동: 캐시 먼저 응답 + 뒤에서 갱신 (stale-while-revalidate)
  if (req.mode === 'navigate') {
    const key = pageKey(url);
    const update = fetch(req)
      .then((res) => {
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(key, copy));
        }
        return res;
      })
      .catch(() => null);
    e.waitUntil(update);
    e.respondWith(
      caches.open(CACHE)
        .then((c) => c.match(key))
        .then((cached) => cached || update.then((res) => res || Response.error()))
    );
    return;
  }

  // 그 외 파일: 캐시 우선, 없으면 네트워크
  e.respondWith(
    caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
      }
      return res;
    }))
  );
});
