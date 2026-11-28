# HKG HUD Display — 외장 안드로이드 클러스터 HUD (`com.hkg.huddisplay`)

현대·기아 표준형 5세대 와이드 내비게이션(Gen5W) 및 Comma 3X(`openpilot`) 데이터를 Wi-Fi/핫스팟으로 수신하여, **외장 안드로이드 보조 모니터(12.3" 1920×720, Android 8.0+ / T88 등)** 에 실시간 3D 차량 클러스터 HUD를 표시하는 안드로이드 앱(`package_t88_hud`)입니다.

> **브랜치 안내**
> - **`android-hud` (현재 브랜치)**: 외장 안드로이드 보조 모니터용 네이티브 Android HUD 앱 (`package_t88_hud/`, APK `com.hkg.huddisplay`)
> - **`main` 브랜치**: Gen5W 헤드유닛용 NEMESIS 패키지 (`package_turzx_monitor/`, `hkg.turzxmonitor` **v1.5.17+**)
>   - 헤드유닛 패키지는 기존 **TURZX USB 모니터(`1cbe:0092` / `0088` / `0123`)** 기능을 그대로 유지하면서, USB 모니터가 연결되지 않은 상태에서는 USB 렌더링을 건너뛰고 `:8766` HTTP + `:8767` UDP 텔레메트리 허브로만 동작합니다. 따라서 기존 USB 모니터와 안드로이드 모니터를 번갈아 또는 함께 사용할 수 있습니다.

---

## 1. 연결 아키텍처 (`TelemetryClient`)

동일 Wi-Fi 또는 모바일 핫스팟에 연결되면 별도 IP 입력 없이 자동으로 헤드유닛과 콤마 3X를 탐색합니다.

| 프로토콜 / 포트 | 대상 | 역할 |
|---|---|---|
| **UDP `:8767`** | Gen5W 헤드유닛 (`hkg.turzxmonitor` v1.5.17+) | 1초 간격 UDP 비콘(`hkg_turzx_hub`) 수신 → 헤드유닛 IP 및 콤마 IP 자동 탐색 |
| **HTTP `:8766`** | Gen5W 헤드유닛 | `/status.json`(기어·차속·EV/ICE·TPMS·공조·도어·램프·미디어·TBT·SDI), `/albumart.jpg` 수신 |
| **WebSocket `ws://<comma_ip>:7000/ws/compact_state`** | Comma 3X (`carrot-wip`) | `CVB1` / `CVS1` 바이너리 프로토콜 직결 (20~33 Hz 차속·곡률·조향·선행차·주변차·TBT 및 `carState` `@40/@41` 후측방 경고·`@47/@48` 방향지시등, 미연결 시 헤드유닛 폴백) |
| **HTTP `:8768`** | 안드로이드 HUD 앱 자체 | 내장 웹 테스트 콘솔 (`HudDebugWebServer`) |

---

## 2. 주요 기능

> **시연 영상 ([hud-v1.0.1 Showcase MP4](https://github.com/GRT47/HKG_5W_TurzxMonitor/releases/download/hud-v1.0.1/hkg_hud_showcase.mp4))**
> 문열림 경고·3D 자동 포커스, 주행 중 좌우 3D 스크롤·5초 자동 복귀, 방향지시등 및 후측방(BSD/BCA) 경고 동작을 실기기 화면 녹화로 확인할 수 있습니다.

### 실시간 OpenGL ES 3.0 3D 니로 EV (`Car3dView`)
- **컴팩트 `C3D2` 메시 (`niro_ev.c3d`)**: 5개 분리 파츠(차체 + 4륜)와 13개 재질을 지원하며, `niro_ev.c3d`가 없거나 비활성화된 환경에서는 기존 2D 스프라이트(`navdy_*`)로 자동 폴백됩니다.
- **차체 색상 5종**: 실버(`0`), 그라파이트(`1`), 화이트(`2`), 블루(`3`), 블랙(`4`).
- **실시간 휠 & 조향 기구학**:
  - 핸들 조향각(`±500°`)에 비례하는 프로그레시브 Ackermann 앞바퀴 조향 (`±33.2°` 내/외륜 차등 회전)
  - 전진(`D`)/후진(`R`) 차속에 맞춘 4륜 3D 회전 애니메이션
- **실시간 램프 & 지면 셰이더**:
  - **전조등 / 미등 / DRL**: 투명 폴리카보네이트 헤드램프 외피, 프로젝터 발광, 전방 도로 헤드라이트 빔 콘
  - **제동등**: 스포일러 HMSL + 좌우 테일램프 3점 LED 블룸 + 노면 붉은 제동 반사광
  - **후진등 (`R`)**: 범퍼 클리어 렌즈 발광 + 2점 화이트 LED 블룸 + 후방 쌍광원 백색 노면 콘
  - **방향지시등 (좌/우/비상등)**: 전면 상단 스위치백 DRL·범퍼 하단·사이드미러 리피터·후면 상단 엠버 램프 및 3D LED 블룸 + 노면 주황 반사광 동기화 점멸
  - **후측방 경고 (BSD/BCA 노면 레이더)**: 주행 기어(`R`/`N`/`D`)에서 좌·우 사각지대 감지 시 후측방 바닥 레이더 아크 글로우(평시 호박색 → 동일 방향 깜빡이 점등 시 적색 위험 승격)
  - **도어 퍼들 라이트**: 열린 도어/트렁크/후드 위치에 맞춘 지면 경고 펄스 셰이더

### 주행 시각화 & 오버레이 (`HudRenderer`)
- **주행 도로 뷰**: 3D·2D 공유 원근 카메라 위에서 동작하는 차선(`l_poly` / `r_poly`), `Canvas.drawVertices` 페더링 그라데이션 AP 경로 리본, Navdy 다각도 주변 차량·선행차 거리(`m`) 표시.
- **2D 방향지시등 & 후측방 경고 오버레이**:
  - **방향지시등(`◀` / `▶`)**: 상단 중앙 녹색 화살표 배지(`P` 기어 비상등 포함 전 기어 지원), 3D 리셋 알약·도어 배너와 겹치지 않도록 자동 배치.
  - **후측방 경고(BSD/BCA)**: `R`/`N`/`D` 주행 중 좌·우 `viz` 테두리 곡선형 글로우 커튼 + `⚠` 레이더 배지 표시(동일 방향 깜빡이 작동 시 적색 고속 점멸 + `좌측/우측 후측방` 경고 알약 승격, 실제 `P` 기어에서는 숨김).
- **오버레이 자동 가시성**:
  - **실제 `P` 기어**일 때는 도로 제한속도, 구간단속, 크루즈 목표속도, ADAS 조향 아이콘, 차선 신뢰도, TBT 회전 안내를 자동으로 숨겨 깔끔한 주차 쇼케이스 화면을 제공합니다.
  - **`R`/`N`/`D` 주행 기어**에서는 TPMS 쇼케이스(`P고정`) 시점을 켜더라도 제한속도·크루즈·구간단속·차선·TBT 오버레이를 계속 표시합니다.
- **TPMS & 도어 열림 경고**:
  - 타이어 공기압(TPMS) 카드는 주행/주차 무관하게 좌측 시각화 카드 우하단에 고정됩니다.
  - 도어 4개·트렁크·후드 열림 시 3D 마커(레이더 파동)·상단 경고 배너·노면 퍼들이 동기화되어 점멸하며, `P` 상태에서는 열린 도어 방향으로 3D 카메라가 최단 각도로 자동 회전 포커스합니다.

### 대시보드 & 편의 기능
- **파워트레인 자동/수동 전환**: EV(SoC, 주행가능거리, 전비, BMS/SOH, 배터리 온도, 셀 편차, 출력 바) 및 ICE(연료 잔량, DTE, 연비, RPM/냉각수/12V, 누적·트립 거리) 자동 전환.
- **자동 테마 (다크 / 라이트)**: 헤드유닛 전조등/일루미네이션 신호에 맞춰 주간(라이트)·야간(다크) 테마를 자동 전환(설정에서 수동 고정 가능).
- **미디어 & 공조 바**: 앨범 아트, 곡 정보/진행 바, 실내·외 온도, 설정 온도, `AUTO`/`A/C`/`HEAT`/열선/내기 순환, 3방향 풍향·7단계 풍량 표시.
- **전원·부팅 자동 실행**: 시동 OFF 수신 시 절전 대기화면 전환, 일반 Android 기기용 `BootReceiver` 및 T88(`Android 10 Go`)용 Direct-Boot **기본 홈 앱(`.HomeLauncherAlias`) 모드** 부팅 자동실행 지원(HUD 화면에서 🏠 홈 버튼으로 T88 기본 런처와 즉시 전환).
- **GitHub 자동 업데이트 (`AppUpdater` · `UpdateInstallReceiver`)**:
  - `package_t88_hud/update.json` 및 GitHub Releases(`.apk` + SHA-256)를 이중 확인해 신규 버전을 감지합니다.
  - 주행 중에는 팝업을 띄우지 않고 상단 상태 배지(`· 업데이트 vX.Y.Z`)로만 안내하며, 주차/대기 상태 또는 설정 화면에서 다운로드 → ZIP/SHA-256/패키지명 무결성 검증 → Android `PackageInstaller.Session` 설치 및 완료 후 자동 전체화면 복귀를 수행합니다.

---

## 3. 터치 조작 가이드

| 터치 영역 | 제스처 | 동작 |
|---|---|---|
| **우측 미디어 카드** | 한 번 탭 | 설정 다이얼로그 열기 (모드, 헤드유닛/콤마 IP, 테마, 차종, 3D 색상, 부팅 자동실행 등) |
| **좌측 하단 TPMS 카드** | 한 번 탭 | **`R`/`N`/`D` 주행 중 P 쇼케이스 시점 고정 토글** (`psi · P고정` 표시, 주행 오버레이 유지) |
| **좌측 3D 차량 화면** | 드래그 (스크롤) | 360° 시점 자유 회전 (Yaw / Pitch) |
| **좌측 3D 차량 화면** | 핀치 줌 | 카메라 줌 인/아웃 |
| **좌측 3D 차량 화면** | 무조작 **5초** 경과 | 손을 뗀 뒤 5초간 추가 터치가 없으면 **기본 시점으로 부드럽게 자동 복귀** |
| **상단 `↺ 기본 시점` 배지** | 한 번 탭 | 주행 중 회전한 시점을 즉시 기본 주행 시점으로 복귀 |

---

## 4. 내장 웹 테스트 콘솔 (`:8768`)

앱 실행 중 `:8768` 포트에서 경량 HTTP 테스트 콘솔(`assets/debug_web.html`)이 함께 동작합니다.

- **Wi-Fi 접속**: `http://<안드로이드_기기_IP>:8768` (앱 설정 창 상단에서 확인 가능)
- **USB(ADB) 접속**:
  ```powershell
  adb forward tcp:8768 tcp:8768
  # 브라우저에서 http://127.0.0.1:8768 열기
  ```
- 지원 제어: `P/R/N/D` 기어, 차속, 크루즈/조향 보조, 도로 곡률(`curveC2`), 핸들 조향각(`±500°` / S커브·핸들 왕복), 램프(전조등/제동등/EPB/좌·우 깜빡이/비상등/좌·우 후측방 BSD), 도어 6종 개별/전체 열기, 주변 차량 거리·제동 시뮬레이션(`bsd_warn` 포함), 테마·EV/ICE·차체 색상·카메라 프리셋, 앱 GitHub 업데이트 확인/설치(`/api/update`).

---

## 5. 소스 구성 (`package_t88_hud/`)

| 경로 | 역할 |
|---|---|
| `AndroidManifest.xml` | `com.hkg.huddisplay`, `minSdk 26` / `targetSdk 29`, 가로 전체화면, Direct-Boot 기본 홈 앱(`.HomeLauncherAlias`), `BootReceiver` |
| `src/com/hkg/huddisplay/MainActivity.java` | 전체화면 몰입 UI, 설정 다이얼로그, `SharedPreferences`, 인텐트·웹 콘솔 연동 |
| `src/com/hkg/huddisplay/HudView.java` | 3층 합성 루프(`HudVizBgView` + `Car3dView` + `HudOverlayView`), 우측 대시보드, 미디어 카드 탭 |
| `src/com/hkg/huddisplay/Car3dView.java` | OpenGL ES 3.0 `C3D2` 렌더러, 셰이더 램프·노면 빔·퍼들·조향/회전 휠 |
| `src/com/hkg/huddisplay/HudRenderer.java` | 테슬라 스타일 HUD 렌더러, 3D/2D 공유 카메라, 5초 자동 복귀, 도어 포커스, P고정 토글 |
| `src/com/hkg/huddisplay/HudOverlayView.java` | 상단 투명 오버레이 뷰, 드래그/핀치/TPMS 탭/기본시점 복귀 터치 처리 |
| `src/com/hkg/huddisplay/HudVizBgView.java` | 좌측 시각화 카드 배경·2D 도로/리본/폴백 레이어 |
| `src/com/hkg/huddisplay/TelemetryClient.java` | UDP `:8767` 자동탐색 + HTTP `:8766` + Comma `ws://:7000/ws/compact_state` 수신 |
| `src/com/hkg/huddisplay/HudState.java` | 텔레메트리 스냅샷 모델 및 주행/주차 데모 시뮬레이션 상태 생성 |
| `src/com/hkg/huddisplay/Car3dState.java` | `HudRenderer` → `Car3dView` 동기화 상태 및 공유 VP 행렬 |
| `src/com/hkg/huddisplay/HudDebugWebServer.java` | `:8768` 내장 웹 테스트 콘솔 HTTP/JSON 서버 (`/api/state`, `/api/control`, `/api/update`) |
| `src/com/hkg/huddisplay/AppUpdater.java` | GitHub `update.json`/Releases 확인, APK 다운로드, SHA-256·패키지 무결성 검증 및 `PackageInstaller` 세션 설치 |
| `src/com/hkg/huddisplay/UpdateInstallReceiver.java` | 설치 확인 인텐트 전달 및 `MY_PACKAGE_REPLACED` 업데이트 완료 후 자동 재실행 |
| `src/com/hkg/huddisplay/BootReceiver.java` | 부팅/QuickBoot 완료 시 자동 실행 브로드캐스트 리시버 |
| `update.json` | 브랜치 기반 자동 업데이트 매니페스트 (`versionCode`, `versionName`, `apkUrl`, `sha256`, `notes`) |
| `assets/debug_web.html` | `:8768` 웹 테스트 콘솔 UI |
| `build_apk.py` | 에셋 스테이징 + JDK(`--release 8`) + `d8`/`aapt`/`zipalign`/`apksigner` 원스텝 APK 빌드·설치 및 `update.json` 갱신 |
| `build_car3d_asset.py` / `simplify_meshopt.mjs` | `3d/scene.gltf` → `niro_ev.c3d` 바이너리 메시 생성기 (선택) |

---

## 6. 빌드 및 설치 방법

### 사전 요구사항
- Python 3
- JDK (`javac --release 8` 및 `keytool` 지원)
- Android SDK (`ANDROID_SDK_ROOT` 또는 `%LOCALAPPDATA%\Android\Sdk` 내 `platforms/android-*`, `build-tools/*`, `platform-tools/adb`)
- `package_turzx_monitor/design_tesla/` 내 `1920×720` 디자인 에셋(저장소에 포함됨 — `build_apk.py`가 중복 없이 재사용)
- *(선택)* `3d/scene.gltf` + `3d/scene.bin`이 루트에 있으면 `build_car3d_asset.py`가 `niro_ev.c3d`를 자동 생성합니다(저작권/용량상 원본 3D 파일은 저장소에 미포함, 없을 경우 2D 스프라이트로 동작).

### 빌드 및 T88 설치
```powershell
# APK 빌드 (출력: package_t88_hud/build/HkgHudDisplay.apk)
python package_t88_hud/build_apk.py

# 빌드 후 ADB로 기기 설치 (T88 서드파티 설치 허용 설정 포함)
python package_t88_hud/build_apk.py --install

# 새 릴리스 버전 빌드 및 package_t88_hud/update.json SHA-256 자동 갱신
python package_t88_hud/build_apk.py --version 1.0.1 --version-code 101 --write-update-json --notes "릴리스 노트"
```

### 헤드유닛 요구사항
- Gen5W 헤드유닛에 `main` 브랜치의 `hkg.turzxmonitor` **v1.5.17 이상**이 설치되어 있어야 `:8767` 자동탐색 비콘과 확장된 `/status.json` 텔레메트리 연동을 사용할 수 있습니다.

---

## 7. 3D 모델 크레딧

- 내 차 3D 모델/이미지는 ["2019 Kia e-Niro"](https://sketchfab.com/3d-models/kia-e-niro-2019-49067e3857bd4081918b7f3f5898ebc3) by tonielpro520, licensed under [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/)을 기반으로 재질·조명·램프 발광 및 파츠 분리를 커스터마이징했습니다.
- 원본 3D 모델(`3d/`)은 저장소에 포함하지 않습니다.
