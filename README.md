# HKG 5W TURZX 9.2" / 12.3" USB Cluster HUD Monitor (`hkg.turzxmonitor`)

현대·기아 표준형 5세대 와이드 내비게이션(Standard Gen5W, Android 4.4.2 API 19)에서 **TURZX USB 보조 모니터**로 실시간 클러스터 HUD 화면을 직접 출력하는 **NEMESIS 패키지**입니다.

| 패널 | USB ID (VID `0x1CBE`) | HUD 해상도 | 패널 전송 형식 |
|---|---|---|---|
| TURZX 9.2" | PID `0x0092` | `1920 × 462` | `464 × 1920` 세로 JPEG (CMD 101) |
| TURZX 8.8" | PID `0x0088` | `1920 × 462` | `480 × 1920` 세로 JPEG |
| TURZX 12.3" (v1.5.0~) | PID `0x0123` | `1920 × 720` | `720 × 1920` 세로 JPEG |

연결된 패널을 자동으로 감지해 해상도·레이아웃·이미지를 바꿉니다. 현재 버전: **v1.5.3**

---

## 0. 테슬라 스타일 HUD (v1.5.0~, 기본값)

- **디자인 원본**: 저장소 루트의 `turzx_redesign_tesla.html` (9.2"/12.3" 시안, 툴바에서 상황·램프·애니메이션을 바꿔 볼 수 있음).
  `python package_turzx_monitor/tools/make_tesla_assets.py`가 이 HTML을 Chromium으로 렌더링해 `package_turzx_monitor/design_tesla/`에
  정적 레이어(`tesla_base*.png`), 켜짐 상태 레이어(`tesla_active*.png`), 값·사각형 좌표(`tesla_layout_92/123.txt`), 앞차·내 차·주차 이미지를 만들고,
  빌드가 jar에 넣습니다. Java `TurzxFrameRenderer.TeslaHud`가 이 좌표대로 숫자·막대·칩을 그립니다.
- **주행 화면**: 흰 차선 + 크루즈 시 파란 경로, 앞차(회색) · 내 차(기아 니로 EV 3D 렌더).
  - 단속카메라 구간은 빨간 원, 이벤트가 없으면 현재 도로 제한속도를 파란 원으로 표시
  - 회전 안내(TBT) 카드는 목적지 안내 중에만 표시
  - 타이어 공기압은 오른쪽 아래 칩(주차 중에는 오른쪽 위), 조향각은 크루즈 옆 핸들 아이콘 회전으로만 표시
  - P(주차)에서는 도로 대신 주차된 차 사진을 표시
- **배터리 카드**: 잔량(kWh) · SoC · 주행가능거리 · 전비 · BMS · SOH · 배터리 온도 · 셀 최고/최저/편차 · 출력/회생 막대.
- **미디어 카드**: 큰 앨범 아트, 앱 이름, 제목, 아티스트, 재생 진행(버튼 없음).
- **공조 바**: 운전석 설정 온도 + `AUTO`(꺼져도 흐리게 표시), 풍향(상체/하체), 풍량, 실내·실외 온도, `A/C` · `HEAT` · 앞유리 · 뒷유리 · 내기 칩.
- **램프 효과** (v1.5.3):
  - 제동등: 콤마 3X가 연결돼 있으면 `carState.brakeLights`를 그대로 씁니다(opendbc 기준 CAN `TCS13.BrakeLight` 또는 브레이크 페달). 콤마가 없으면 ESC 브레이크 압력(`getCylPrsrVal`, 한 번이라도 0보다 크게 들어온 경우), 그것도 없으면 D/R 정차 또는 감속 > 1.6 m/s²로 추정합니다.
  - 전조등: 헤드유닛이 미등을 켜면 받는 `com.hkmc.intent.action.SYSTEM_STATE_ILLUMINATE_ON/OFF` 브로드캐스트를 우선 쓰고, 받기 전에는 `IBCMService.getIlluminateStatus()` 값을 씁니다. 이전에 쓰던 `getAFLS11_HeadLampStatus`는 DBC상 AFLS LED 램프 상태(고장 표시 계열)라 켜짐/꺼짐과 무관하게 3으로 나와 더 이상 쓰지 않습니다. 콤마(openpilot)는 전조등 신호를 읽지 않아 보내지 않습니다.
  - 판단 근거와 원값은 대시보드 `전력 · 주행` 카드 마지막 두 줄, 리포트의 `Lamp sources` / `BCM lamp getters` / `Lamp signals` 줄, 로그의 `LAMP` 이벤트에서 볼 수 있습니다.
- **기존 디자인으로 되돌리기**: 실험 패널 `디자인` 줄(신규 / 기존) 또는 설정 `hud_design=classic`. 새 디자인 그리기에 실패한 프레임은 자동으로 기존 HUD로 그립니다.
- **3D 모델 크레딧**: 내 차 이미지는 ["2019 Kia e-Niro"](https://sketchfab.com/3d-models/kia-e-niro-2019-49067e3857bd4081918b7f3f5898eb3c)
  by tonielpro520, licensed under [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/) — 재질 색 지정 · 조명 · 램프 발광을 바꿔 렌더링했습니다.
  원본 모델(`3d/`)과 사진(`2d/`)은 저장소에 포함하지 않으며, 렌더링된 이미지만 배포합니다.

## 1. 기존 HUD 3열 레이아웃 (`디자인: 기존`, Chromium 1:1)

- **1열 (좌측 `EV 계기판 + TPMS`)**:
  - **2×2 EV 핵심 지표**: 고전압 배터리 `SoC(%) / SOH(%) / 가용 에너지(kWh)`, 주행가능거리 `DTE(km) / 공조 OFF 변화량(+km) / 평균 전비(km/kWh)`, 실시간 소비전력(`kW` 및 모터·공조·전장·배터리케어 서브시스템), 배터리 온도(`°C`) 및 셀 밸런싱 편차(`ΔmV`, `12V` 보조배터리 전압).
  - **4륜 TPMS 타이어 공기압 카드**: `FL / FR / RL / RR (psi)` 및 차량 상면 실루엣.
- **2열 (중앙 `CarrotNavi + 3D Comma ADAS HUD`)**:
  - **Comma 3X (`ajouatom/openpilot` `carrot-wip`) & `HKG_5W_CarrotNavi` 연동**: UDP (`:7705` / `:7706`), `/data/local/tmp/carrotbridge_status.json`, 및 `ws://<comma_ip>:7000/ws/compact_state` (`CVB1` / `CVS1` 바이너리 프로토콜) 수신. 헤드유닛 단독 주행 시에도 `IBCMService.getSpeed_KMHUnit()` 및 `ITM_HSCANService.getSasAngleVal()`을 통해 실시간 차속·조향각을 반영합니다.
  - **3D 퍼스펙티브 주행 경로(`d_poly`), 좌/우 차선(`l_poly`, `r_poly`), 선행 차량 거리 박스(`m`)** 렌더링.
  - **SDI 과속단속 원형 표지판**: 단속 구간은 빨간색(`#EF4444`) + 제한속도·남은 거리, 일반 도로는 파란색(`#2563EB`) + 도로 제한속도.
  - **TBT 경로 안내 카드** 및 중앙 대형 현재 속도(`km/h`) · 크루즈 설정 속도(`CRUISE`) · 기어 단수(`P/R/N/D`).
- **3열 (우측 `미디어 정보 1 : 공조 모니터 1`)**:
  - **상단 (`재생중인 미디어`)**: 라운드 앨범 아트, 곡 제목, 아티스트/앨범명, 재생 진행 바.
  - **하단 (`공조 모니터 DATC`)**: 설정 온도, `AUTO` · `A/C` · 내기/외기 · `SYNC`, 공조 전력(`kW`), 3방향 풍향 화살표, 7단계 풍량 바, 실내·실외 온도.
- 12.3" 패널에서는 같은 구성을 `1920 × 720` 레이아웃(`HudLayout.LAYOUT_720`)으로 그립니다.

## 2. 공통 기능

- **앨범 아트 수집**: `BTAudio`/`CarPlay`/`Android Auto`/`USBMusic` `artwork_raw_image`·`artwork_uri` + `AvrcpControllerService` `/mnt/bt_cover_art/0` BIP 커버아트 + `IRemoteControlDisplay` 280×280 비트맵 + iTunes Search API `TLSv1.2` 커버아트 폴백 및 LRU 캐시.
- **GitHub OTA 자동 업데이트 (`OtaUpdateManager`)**:
  - `https://github.com/GRT47/HKG_5W_TurzxMonitor` 저장소의 `ota/version.json` 및 `ota/turzxmonitor.b64`를 주기적으로 확인(부팅 45초 후 및 3시간 간격)하여 SHA-256 무결성 검증 후 무중단 자동 업데이트 및 재시작.
  - 진단 대시보드의 `[☁️ 업데이트]` 버튼, 웹 서버 `http://<헤드유닛_IP>:8766/update`, 또는 `launch.sh update` 명령으로 즉시 수동 업데이트 가능.
- **진단 로그 수집 및 USB 저장 (`DebugManager`)**:
  - 대시보드의 `[💾 로그저장]` 버튼, 웹 `http://<헤드유닛_IP>:8766/debug.txt?save=1`, 또는 **USB 메모리를 새로 꽂았을 때 1회** `/storage/usb0/HKG_TurzxMonitor_Log/` 폴더에 상세 진단 리포트(`HKG_TurzxMonitor_Debug.txt`), 데몬 로그(`hkg_turzxmonitor.log`), USB 커널 로그(`dmesg_usb.txt`), 연결된 USB 장치 목록(`usb_devices.txt`)을 저장합니다.
  - 시동 OFF 순간의 쓰기로 FAT32 USB가 손상되지 않도록 주기적 동기화는 하지 않습니다(v1.4.0~). 부팅·QuickBoot 복귀 직후 90초 안에 다시 마운트되는 USB는 "새로 꽂음"으로 보지 않습니다.
- **JPEG 인코딩 (v1.4.0~)**: libjpeg-turbo 3.0.4 NEON SIMD 네이티브 인코더(`libturzxjpeg.so`, jar에 포함되어 실행 시 추출)로 패널 회전과 인코딩을 한 번에 처리합니다. 실패하면 Skia `Bitmap.compress()`로 대체됩니다. 네이티브 로드나 첫 50프레임 도중 프로세스가 연속 2회 죽으면 해당 `.so` 버전의 네이티브 인코딩을 꺼서(`/data/local/tmp/hkg_turzxjpeg_disabled_<hash>`) HUD와 OTA가 계속 동작하게 합니다. 이 파일을 지우면 다시 시도합니다.
- **부분 인코딩 (v1.4.5~)**: 패널 JPEG에 MCU 행(16픽셀)마다 restart marker를 넣고, 직전 프레임과 비교해 바뀐 행만 다시 압축한 뒤 나머지는 이전 압축 결과를 이어 붙입니다(`native/turzx_inc.c`). 결과는 전체 압축과 바이트 단위로 같습니다. 9.2"·12.3" 패널 모두 restart marker를 지원하는 것을 확인했습니다.
- **렌더 파이프라인**: 텔레메트리 수집 / Canvas 렌더 / JPEG 인코딩 / USB 전송이 별도 스레드입니다. v1.4.8부터 기본값은 `싱글코어`(렌더 → 인코딩을 차례로)이며, `멀티코어`(렌더와 인코딩 동시)는 실험 패널에서 고를 수 있습니다.
- **크래시 백오프 (v1.4.0~)**: Java 프로세스가 연속으로 죽으면 재시작 간격이 0 → 10 → 30 → 60 → 120 → 300초로 늘어나고, 120초 이상 정상 동작하면 초기화됩니다. Dalvik 캐시는 연속 크래시 2회째에 한 번만 지웁니다.

## 3. 진단 대시보드와 실험 패널

- **열기/닫기**: **MAP 버튼을 3초 길게 누르기**. MAP 키 본래 동작(내비 실행)은 그대로 유지됩니다. 키 코드는 `/system/usr/keylayout/daudio*.kl`의 `SHORTCUT_MAP`에서 자동으로 찾고, 찾지 못하면 대시보드의 `최근 키` 항목에서 확인해 `overlay_hotkey_code=<코드>`로 지정합니다.
- 헤드유닛 전체 화면(1920×720)에 USB·프레임 파이프라인 / 배터리 / 전력·주행 / 공조 / 미디어 / 연결·시스템 카드를 보여줍니다. 후진(R)·후방카메라·화면 꺼짐 시 자동으로 숨겨집니다.
- **실험 패널** (왼쪽 열, 넘치면 스크롤): `[－]/[＋]`로 바로 적용되고, 값을 바꾸면 2초 안정화 후 5초 동안 FPS·단계별 ms·JPEG 크기·CPU를 측정합니다. `[💾저장]`을 눌러야 `monitor.conf`에 남고, `[↺복원]`은 저장된 값으로 되돌립니다.

| 줄 | 값 | 설정 키 |
|---|---|---|
| JPEG 품질 | 50 ~ 95 (기본 70) | `usb_jpeg_quality` |
| 목표 FPS | 5 ~ 25 | `usb_fps` |
| 패널 FPS (0x0F) | 끔 / 10 ~ 30 | `usb_panel_fps` |
| ADAS 도로 | ON / OFF | `hud_road` |
| 수집 주기 | 100 ~ 1000 ms | `telemetry_poll_ms` |
| 부분 인코딩 | ON / OFF | `usb_incremental_encode` |
| 렌더 방식 | 싱글코어 / 멀티코어 | `render_parallel` |
| 디자인 (v1.5.0) | 신규 / 기존 | `hud_design=tesla\|classic` |
| 화면 회전 (v1.5.1) | 기본 / 180° (패널을 거꾸로 달았을 때) | `usb_rotation=270\|90` |

- `[🧪RST]`: 패널의 JPEG restart marker 지원 여부를 A/B/C 테스트 화면으로 확인합니다(9.2" 전용, 이미지는 `package_turzx_monitor/panel_test/`).

---

## 4. 패키지 구성 및 빌드

- **설치용 USB 번들**: [Releases](https://github.com/GRT47/HKG_5W_TurzxMonitor/releases) (`HKG_5W_TurzxMonitor_USB.zip`)
- **OTA 아티팩트**: `ota/` (`version.json`, `turzxmonitor.b64`, `turzx-daemon.sh`, `launch.sh`)
- **패키지 소스**: `package_turzx_monitor/`
  - `NEMESIS.data`: 패키지 메타데이터 (`id=hkg.turzxmonitor`)
  - `src_java/com/hkg/turzxmonitor/TurzxMonitorMain.java`:
    - `TurzxUsbDriver`: 512바이트 DES-CBC (`"slv3tuzx"`) 명령 헤더, 투명 PNG 오버레이 초기화(`CMD 102`, 480×1920 / 12.3"는 720×1920), JPEG 프레임 USB Bulk (`EP 0x01`) 전송
    - `TurzxFrameRenderer`: Android `Canvas` HUD 렌더러 (`TeslaHud` 신규 디자인 + 기존 Chromium 1:1 레이어, `90°/270°` 세로 회전)
    - `TelemetryCollector`: Gen5W Binder (`hev`, `hscanmicomservice`, `tm_hscan_service`, `datc`, `pgs`, `system`) + `TM_HSCANPacketRunner` logcat 폴백 + Comma 3X UDP/`CVS1`/`CVB1` WebSocket 수집기
    - `TurzxOverlayController`, `HotkeyWatcher`, `LiveTuning` / `FpsBench`, `PanelRstTest`: 진단 대시보드 · MAP 길게 누르기 · 실험 패널 · RST 테스트 (내장 웹 서버 `:8766` 포함)
    - `DebugManager` & `OtaUpdateManager`: 진단 로그 덤프 및 GitHub `TLSv1.2` OTA
  - `native/`: libjpeg-turbo NEON JNI 인코더(`turzxjpeg.c`), 부분 인코딩(`turzx_inc.c`), NDK 빌드(`build_native.py`). 빌드 결과물 `native/prebuilt/armeabi-v7a/libturzxjpeg.so`는 저장소에 포함되어 NDK 없이도 패키징할 수 있습니다. `native/test/run_host_test.py`는 PC에서 `zig cc`로 부분 인코딩이 전체 인코딩과 같은지 검사합니다.
  - `design_tesla/`: 테슬라 스타일 HUD 이미지·좌표 (`tools/make_tesla_assets.py`로 생성)
  - `tools/`: `make_tesla_assets.py`(신규 디자인 이미지 생성), `make_panel_test_jpegs.py`(RST 테스트 이미지), `pc_usb_monitor.py`(PC에 꽂은 TURZX 9.2"/8.8"/12.3"로 HUD를 직접 보내는 도구, `--list` / `--hu-url` / `--blank`)
  - `build_package.py`: JDK 8 컴파일 + 호스트 자체 검증 테스트 + Android API 19 Dalvik DEX(`035`) 변환 + `ota/` 생성 및 패키징
- **웹 시안**: `turzx_redesign_tesla.html`(신규 디자인), `turzx_92_mockup.html`(기존 디자인 및 라이브 뷰어)

### 다시 빌드하기
```powershell
python package_turzx_monitor/build_package.py
```
- d8은 **build-tools 35 이상(R8 8.5+)** 을 사용하세요. build-tools 34의 d8(R8 8.2.2)은 이 소스의 `TurzxOverlayController$3$1`에서 내부 NPE로 실패합니다.
- Android NDK **r25c**(API 19를 지원하는 마지막 계열)와 CMake가 있으면 `libturzxjpeg.so`를 다시 빌드하고, 없으면 커밋된 prebuilt를 사용합니다. `ANDROID_NDK_HOME`으로 경로를 지정하거나 `TURZX_SKIP_NATIVE_BUILD=1`로 prebuilt 사용을 강제할 수 있습니다.
- `TURZX_REUSE_OTA_ASSETS=1`: Chromium 없이 현재 `ota/turzxmonitor.b64`의 기존 디자인 PNG 레이어·폰트를 재사용합니다.
- 신규 디자인 시안(`turzx_redesign_tesla.html`)을 고쳤다면 먼저 `python package_turzx_monitor/tools/make_tesla_assets.py`로 `design_tesla/`를 다시 만드세요(Edge/Chrome 필요).

---

## 5. 설치 및 사용 방법

1. [GitHub Releases](https://github.com/GRT47/HKG_5W_TurzxMonitor/releases)에서 `HKG_5W_TurzxMonitor_USB.zip`을 내려받아 압축을 FAT32 포맷된 USB 메모리 루트(`NEMESIS.data`, `packages/hkg.turzxmonitor.zip`)에 풀고 헤드유닛에 삽입하여 NEMESIS로 설치합니다. 이후 버전은 OTA로 자동 업데이트됩니다.
2. 설치 후 `hkg-turzxmonitor` 백그라운드 서비스가 자동 실행되며, 유선 USB 허브에 연결된 **TURZX 모니터(`1cbe:0092` / `0088` / `0123`)**를 감지하여 HUD 화면을 전송합니다.
3. 설정 파일 `config/monitor.conf`의 주요 키는 위 실험 패널 표와 같습니다. 그 밖에 `usb_brightness`, `comma_ip`, `auto_update`, `overlay_hotkey_code`가 있습니다.
4. 내장 웹 서버(`http://<헤드유닛_IP>:8766/`) 엔드포인트:
   - `/`: 실시간 HUD 웹 뷰어
   - `/status.json`: 텔레메트리 + USB + OTA 상태 JSON (`panel`, `fps`, `jpeg_encoder`, `incremental`, `dirty_rows`, `render_ms`, `road_ms`, `encode_ms`, `encode_wait_ms`, `poll_ms`, `usb_tx_ms`, `ack_ms`, `slot_wait_ms`, `jpeg_kb` 포함)
   - `/frame.jpg`: 현재 HUD 가로 JPEG 프레임 (요청이 있을 때만 생성, 첫 요청은 최대 0.8초 대기)
   - `/albumart.jpg`: 현재 재생 곡의 원본 앨범 커버 이미지
   - `/debug.txt`: 진단 리포트 조회 (`?save=1`이면 USB에도 저장)
   - `/update`: GitHub OTA 최신 버전 확인 및 업데이트

---

## 6. 최근 변경 내역

| 버전 | 내용 |
|---|---|
| v1.5.3 | 제동등: 콤마 `carState.brakeLights`(CAN `TCS13.BrakeLight` 또는 페달) 우선. 전조등: 헤드유닛 일루미네이션(`SYSTEM_STATE_ILLUMINATE_ON/OFF`, `IBCMService.getIlluminateStatus`) 기준, BCM 램프 콜백·getter 원값 기록 |
| v1.5.2 | 램프·브레이크 신호 진단(대시보드 `전력 · 주행` 카드, 로그 `LAMP` 이벤트), 제동등을 브레이크 압력(`getCylPrsrVal`) 기준으로, 회생 출력 기준 제거 |
| v1.5.1 | 실험 패널 `화면 회전`(기본 / 180°), 실험 패널 스크롤 |
| v1.5.0 | 테슬라 스타일 HUD(기본값, `디자인` 줄로 전환), 12.3" (`0x0123`, 1920×720) 패널 지원, 전조등/제동등 표시 |
| v1.4.8 | 싱글코어 렌더 + 부분 인코딩을 기본값으로 |
| v1.4.7 | 대시보드가 열리지 않던 문제 수정 |
| v1.4.6 | MAP 길게 누르기 대시보드 토글 안정화 |
| v1.4.5 | 부분 인코딩(바뀐 MCU 행만 재압축), 렌더 방식 선택, 프로세스 CPU 표시 |
| v1.4.4 | 렌더/인코딩 병렬 스레드, ADAS 도로 일괄 그리기, RST 테스트 |
| v1.4.3 | 전체 화면 진단 대시보드, MAP 길게 누르기, 실험 패널 |
| v1.4.0 | libjpeg-turbo NEON 인코더, USB 로그 저장 방식 변경, 크래시 백오프 |
