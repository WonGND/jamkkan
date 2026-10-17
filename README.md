# 잠깐만 (지출 브레이크 PWA)

사기 전에 한 번 더 생각하게 돕는 개인용 안드로이드 PWA. 서버 없음, 데이터는 기기 localStorage(`jamkkan.v1`).

## 구조
```
jamkkan/
├─ index.html              설치 안내 페이지 (APK 받기)
├─ android/                안드로이드 앱 (WebView + 앱 실행 감지)
├─ docs/NATIVE.md          웹↔앱 규약, 구조
├─ sw.js                   서비스 워커 (app/, brake/ 함께 관리)
├─ icons/                  icon-* = 본 앱(파랑), brake-* = 브레이크(빨강)
├─ app/                    잠깐만: 기록·판단 본 앱
│  ├─ index.html
│  └─ manifest.webmanifest
└─ brake/                  잠깐만 브레이크: 모드 및 루틴이 여는 전용 입구
   ├─ index.html           ← app/index.html과 같은 파일 (주소로 입구를 구분)
   └─ manifest.webmanifest
```

## 수정할 때 규칙
1. 앱 코드는 `app/index.html`만 고친다.
2. 고친 뒤 `app/index.html`을 `brake/index.html`로 그대로 복사한다. (두 파일은 항상 같아야 함)
3. `app/index.html`의 `VERSION`과 `sw.js`의 `CACHE` 버전을 함께 올린다.

## 배포 (GitHub Pages)
- 저장소 이름 `jamkkan` → 주소 `https://wongnd.github.io/jamkkan/`
- 이 폴더 내용을 저장소 루트에 올리고, Settings → Pages → Branch `main` / `/ (root)`
- 휴대폰 크롬에서 `/jamkkan/app/`, `/jamkkan/brake/`를 각각 열어 ⋮ → 앱 설치

## 루틴 연결 (지금: 웹)
잠깐만 앱 → 설정 → 앱과 연결하기 화면의 순서를 따른다.
모드 및 루틴: 조건 `앱 열림`(연결할 앱들) → 동작 `앱 열기`(잠깐만 브레이크).

## 안드로이드 앱 (android/) — 제품 방향
- 앱 하나만 설치하면 고른 쇼핑·배달·결제 앱이 켜질 때 잠깐만이 먼저 켜진다 (루틴 불필요)
- `main`에 push하면 GitHub Actions가 APK를 빌드해서 Release에 올린다
  → 휴대폰: https://github.com/WonGND/jamkkan/releases/latest/download/jamkkan.apk
- 화면은 `app/index.html` 그대로 (빌드 때 앱 안으로 복사). 규약·구조는 [docs/NATIVE.md](docs/NATIVE.md)
