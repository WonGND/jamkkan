# 네이티브(안드로이드 앱) 전환 가이드

> 상태: **아직 만들지 않음.** 웹(PWA) 쪽은 이 문서의 규약대로 준비돼 있다.
> 네이티브 셸을 만들 때 이 문서를 그대로 Claude Code에 넘기면 된다.

## 왜 바꾸나
| | 웹(PWA) + 모드 및 루틴 | 네이티브 앱 |
|---|---|---|
| 휴대폰 앱 목록 | 볼 수 없음 → 추천 목록에서 고르고, 루틴에서 또 고름 | 설치된 앱 목록을 그대로 보여 줌 |
| 앱 실행 감지 | 루틴이 대신 함 | 앱이 직접 감지 |
| 어느 앱 때문에 열렸는지 | 모름 → "어디서 쓰려고 해요?"로 물어봄 | 앎 → "쿠팡이 열려서 잠깐만이 켜졌어요" |
| 쇼핑앱 복귀 시 반복 실행 | 루틴 동작에 달림 (위험) | "잠깐만에서 돌아온 경우는 무시" 규칙으로 막을 수 있음 |
| 원래 앱으로 돌아가기 | window.close()가 될지 미확인 | moveTaskToBack으로 확실 |
| 배포 | GitHub Pages | APK 직접 설치 (개인용) |

## 구조: 얇은 네이티브 셸 + 지금의 웹 화면
- 화면·질문·저장은 지금 `app/index.html`을 그대로 쓴다 (WebView).
- 네이티브는 **앱 감지, 앱 목록, 권한, 원래 앱 복귀**만 맡는다.
- 웹 화면은 `window.JamkkanNative`가 있으면 네이티브 모드로 바뀐다 (`Platform` 객체, index.html 참고).

### 화면 불러오는 방법 (둘 중 하나)
1. **앱 안에 파일을 넣기 (권장, 안정성)**: `assets/web/`에 `app/index.html`과 `icons/` 복사 →
   `WebViewAssetLoader`로 `https://appassets.androidplatform.net/assets/web/app/index.html` 로드.
   오프라인·즉시 실행. 웹을 고치면 APK를 다시 빌드.
2. **GitHub Pages 주소를 열기**: `https://wongnd.github.io/jamkkan/app/` 로드.
   웹만 고치면 바로 반영. 처음 실행 때 네트워크 필요.

WebView 설정: `javaScriptEnabled = true`, `domStorageEnabled = true` (localStorage 사용).

## 웹 ↔ 네이티브 규약
### 1) 네이티브가 넣어 줄 객체: `JamkkanNative`
`webView.addJavascriptInterface(bridge, "JamkkanNative")` — 모든 메서드는 `@JavascriptInterface`, 값은 **JSON 문자열**.

| 메서드 | 돌려주는 값 | 하는 일 |
|---|---|---|
| `getInstalledApps(): String` | `[{"pkg":"com.coupang.mobile","label":"쿠팡"}, ...]` | 실행 가능한 앱 목록(런처 앱). 잠깐만 자신은 빼기 |
| `setWatchConfig(json: String)` | 없음 | `{"apps":[{"pkg","label"}],"cooldownMin":10}` 저장 → 감지 서비스가 사용 |
| `getPermissionState(): String` | `{"usage":true,"overlay":false,"notify":true}` | 권한 상태 |
| `openPermissionSettings(kind: String)` | 없음 | `usage` / `overlay` / `notify` 설정 화면 열기 |
| `returnToPreviousApp()` | 없음 | `moveTaskToBack(true)` — 원래 앱으로 돌아가기 |

웹은 앱 목록·다시 묻지 않는 시간이 바뀔 때마다, 그리고 시작할 때 `setWatchConfig`를 부른다.

### 2) 잠깐만을 띄울 때 알려 주는 정보
- **처음 켜질 때**: `.../app/index.html?entry=brake&pkg=com.coupang.mobile&label=쿠팡`
- **이미 켜져 있을 때** (`onNewIntent`):
  `webView.evaluateJavascript("jamkkanLaunch({entry:'brake',pkg:'com.coupang.mobile',label:'쿠팡'})", null)`
- **사용자가 런처에서 직접 열 때**: `jamkkanLaunch({entry:'app'})` (처음 켜질 때는 주소에 아무것도 안 붙임)

웹은 `entry=brake`면 브레이크 화면을, 판단 도중이면 그대로 이어 간다.

## 네이티브 구성 요소
1. **MainActivity**: WebView 하나. `launchMode="singleTask"`. 인텐트 extra(`entry`, `pkg`, `label`)를 위 규칙대로 웹에 전달.
2. **감지 서비스 (Foreground Service)**: `UsageStatsManager.queryEvents()`를 0.5~1초마다 확인해서
   감시 목록의 앱이 앞으로 오면 MainActivity를 `entry=brake`로 띄움.
   - **반복 실행 막기 규칙**: 바로 전에 앞에 있던 앱이 잠깐만이면 무시(잠깐만에서 돌아간 것).
     같은 앱은 `cooldownMin` 동안 다시 띄우지 않음.
   - 상시 알림 1개 필요("잠깐만이 쇼핑앱을 지켜보는 중").
3. **BootReceiver**: 재부팅 후 서비스 다시 시작.

## 권한 (AndroidManifest)
- `PACKAGE_USAGE_STATS` — 사용 정보 접근 (설정에서 직접 허용)
- `SYSTEM_ALERT_WINDOW` — 다른 앱 위에 표시. 안드로이드 10+에서 백그라운드에서 화면을 띄우려면 필요
- `POST_NOTIFICATIONS` (13+), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (14+)
- `RECEIVE_BOOT_COMPLETED`
- 앱 목록: `QUERY_ALL_PACKAGES` 대신 `<queries><intent>` (MAIN/LAUNCHER)로 충분

갤럭시는 **배터리 → 백그라운드 사용 제한 → 절전 예외 앱**에 잠깐만을 넣어야 서비스가 안 죽는다.
(접근성 서비스로도 감지할 수 있지만 권한이 훨씬 강해서 사용 정보 접근을 쓴다.)

## 전환할 때 주의
- **데이터가 자동으로 넘어오지 않는다.** 크롬 PWA의 저장소와 앱 WebView의 저장소는 서로 다르다.
  → 전환 전에 2단계 목록의 **JSON 백업/복원**을 먼저 만들어서 옮긴다.
- 웹 쪽 테스트는 가짜 `JamkkanNative`를 넣어서 브라우저에서 돌릴 수 있다 (설치 앱 목록, 출처 앱 표시, 원래 앱 복귀 호출, 권한 안내).
- 네이티브 쪽은 실제 갤럭시에서만 검증 가능: 감지 지연, 반복 실행, 배터리 최적화, 재부팅 후 동작.
