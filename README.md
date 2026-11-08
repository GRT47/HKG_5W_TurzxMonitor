# HKG 5W TURZX 9.2" (1920×462) USB Cluster HUD Monitor (`hkg.turzxmonitor`)

현대·기아 표준형 5세대 와이드 내비게이션(Standard Gen5W, Android 4.4.2 API 19)에서 **TURZX 9.2인치(`1920 × 462`, USB VID `0x1CBE` / PID `0x0092`)** 보조 모니터로 실시간 클러스터 HUD 화면을 직접 출력하는 **NEMESIS 패키지**입니다.

---

## 1. 주요 기능 및 3열 레이아웃 (`1920 × 462`)

- **1열 (좌측 `EV 계기판 + TPMS`)**:
  - **2×2 EV 핵심 지표**: 고전압 배터리 `SoC(%) / SOH(%) / 가용 에너지(kWh)`, 주행가능거리 `DTE(km) / 공조 OFF 변화량(+km) / 평균 전비(km/kWh)`, 실시간 소비전력(`kW` 및 모터·공조·전장·배터리케어 서브시스템), 배터리 온도(`°C`) 및 셀 밸런싱 편차(`ΔmV`, `12V` 보조배터리 전압).
  - **4륜 TPMS 타이어 공기압 카드**: `FL / FR / RL / RR (psi)` 및 차량 상면 실루엣.
- **2열 (중앙 `CarrotNavi + 3D Comma ADAS HUD`, 폭 25% 슬림화)**:
  - **Comma 3X (`ajouatom/openpilot` `carrot-wip`) & `HKG_5W_CarrotNavi` 연동**: UDP (`:7705` / `:7706`), `/data/local/tmp/carrotbridge_status.json`, 및 `ws://<comma_ip>:7000/ws/compact_state` (`CVB1` / `CVS1` 바이너리 프로토콜) 수신. 헤드유닛 단독 주행 시에도 `IBCMService.getSpeed_KMHUnit()` 및 `ITM_HSCANService.getSasAngleVal()`을 통해 실시간 차속·조향각을 반영합니다.
  - **3D 퍼스펙티브 주행 경로(`d_poly`), 좌/우 차선(`l_poly`, `r_poly`), 선행 차량 거리 박스(`m`)** 렌더링.
  - **SDI 과속단속 원형 표지판**:
    - **단속 카메라 구간 활성 시**: **빨간색 원형 테두리(`#EF4444`)** + 제한속도 및 남은 거리 표시.
    - **단속 카메라가 없는 일반 도로 시**: **파란색 원형 표지판(`#2563EB`)** + 현재 도로 제한속도 표시.
  - **TBT 경로 안내 카드** 및 중앙 대형 현재 속도(`km/h`) · 크루즈 설정 속도(`CRUISE`) · 기어 단수(`P/R/N/D`).
- **3열 (우측 `미디어 정보 1 : 공조 모니터 1`, 상·하 1:1 높이 분할)**:
  - **상단 50% (`재생중인 미디어`)**: 대형 라운드 앨범 아트(`172×172`, 5중 계층 수집: `BTAudio`/`CarPlay`/`Android Auto`/`USBMusic` `artwork_raw_image`·`artwork_uri` + `AvrcpControllerService` `/mnt/bt_cover_art/0` BIP 커버아트 + `IRemoteControlDisplay` 280×280 비트맵 + iTunes Search API `TLSv1.2` 커버아트 폴백 및 LRU 캐시), 곡 제목, 아티스트/앨범명, 우측 축소형 재생 진행 바.
  - **하단 50% (`공조 모니터 DATC`)**:
    - 운전석/동승석 설정 온도(`°C`), `AUTO`, `A/C`, `내기순환/외기유입`, `SYNC`, 실시간 공조 전력(`kW`).
    - **대형 시트 착좌형 3방향 풍향 화살표 (전면유리 `#FBBF24` · 상체 `#38BDF8` · 하체 `#FB923C`)** + **7단계 상승형 풍량 바 그래프(`풍량 1~7단`)**.
    - **실내 온도 (`INDOOR °C`) & 실외 온도 (`OUTDOOR °C`)** 하단 듀얼 카드.
- **GitHub OTA 자동 업데이트 (`OtaUpdateManager`)**:
  - `https://github.com/GRT47/HKG_5W_TurzxMonitor` 저장소의 `ota/version.json` 및 `ota/turzxmonitor.b64`를 주기적으로 확인(부팅 45초 후 및 3시간 간격)하여 SHA-256 무결성 검증 후 무중단 자동 업데이트 및 재시작.
  - 전체 화면 진단 대시보드의 `[☁️ 업데이트]` 버튼, 웹 서버 `http://<헤드유닛_IP>:8766/update`, 또는 `launch.sh update` 명령으로 즉시 수동 업데이트 가능.
- **진단 로그 수집 및 USB 저장 (`DebugManager`)**:
  - 전체 화면 진단 대시보드의 `[💾 로그저장]` 버튼, 웹 `http://<헤드유닛_IP>:8766/debug.txt?save=1`, 또는 **USB 메모리를 새로 꽂았을 때 1회** `/storage/usb0/HKG_TurzxMonitor_Log/` 폴더에 상세 진단 리포트(`HKG_TurzxMonitor_Debug.txt`), 데몬 로그(`hkg_turzxmonitor.log`), USB 커널 로그(`dmesg_usb.txt`), 연결된 USB 장치 목록(`usb_devices.txt`)을 저장합니다.
  - v1.4.0부터 주기적(30초) 동기화와 서비스 종료 시 동기화는 제거되었습니다. 시동 OFF 순간의 쓰기로 FAT32 USB가 손상되는 것을 막기 위함입니다. 부팅·QuickBoot 복귀 직후 90초 안에 다시 마운트되는 USB는 "새로 꽂음"으로 보지 않습니다.
- **JPEG 인코딩 (v1.4.0)**: libjpeg-turbo 3.0.4 NEON SIMD 네이티브 인코더(`libturzxjpeg.so`, jar에 포함되어 실행 시 추출)로 패널 회전과 인코딩을 한 번에 처리합니다. 로드나 인코딩에 실패하면 자동으로 Skia `Bitmap.compress()`로 대체됩니다. 네이티브 로드나 첫 50프레임 도중 프로세스가 연속 2회 죽으면 해당 `.so` 버전의 네이티브 인코딩을 꺼서(`/data/local/tmp/hkg_turzxjpeg_disabled_<hash>`) HUD와 OTA가 계속 동작하게 합니다. 이 파일을 지우면 다시 시도합니다. 웹 `/frame.jpg`용 가로 JPEG는 누군가 요청할 때만 생성하고, TURZX가 연결되지 않은 동안에는 렌더링하지 않습니다. 기본 JPEG 품질은 85에서 70으로 낮췄습니다(이전 기본값 85로 저장된 설정은 자동으로 70으로 이전).
- **크래시 백오프 (v1.4.0)**: Java 프로세스가 연속으로 죽으면 재시작 간격이 0 → 10 → 30 → 60 → 120 → 300초로 늘어나고, 120초 이상 정상 동작하면 초기화됩니다. Dalvik 캐시는 연속 크래시 2회째에 한 번만 지웁니다.

---

## 2. 패키지 구성 및 빌드

- **설치용 USB 번들**: [Releases](https://github.com/GRT47/HKG_5W_TurzxMonitor/releases) (`HKG_5W_TurzxMonitor_USB.zip`)
- **OTA 아티팩트 디렉터리**: `ota/` (`version.json`, `turzxmonitor.b64`, `turzx-daemon.sh`, `launch.sh`)
- **패키지 소스 디렉터리**: `package_turzx_monitor/`
  - `NEMESIS.data`: 패키지 메타데이터 (`id=hkg.turzxmonitor`, `version=1.4.0`)
  - `native/turzxjpeg.c`, `native/build_native.py`: libjpeg-turbo NEON JNI 인코더 소스 및 NDK 빌드 스크립트. 빌드 결과물 `native/prebuilt/armeabi-v7a/libturzxjpeg.so`는 저장소에 포함되어 NDK 없이도 패키징할 수 있습니다.
  - `src_java/com/hkg/turzxmonitor/TurzxMonitorMain.java`:
    - `TurzxUsbDriver`: TURZX 9.2" (`1cbe:0092`) 512바이트 DES-CBC (`"slv3tuzx"`) 명령 헤더 암호화, `480×1920` 투명 PNG 오버레이 초기화(`CMD 102`), 및 `464×1920` 패딩 JPEG 프레임 USB Bulk (`EP 0x01`) 전송 드라이버
    - `TurzxFrameRenderer`: Android `Canvas` 기반 `1920 × 462` 클러스터 HUD 렌더러 (`90°/270°` 네이티브 포트레이트 회전 지원)
    - `TelemetryCollector`: Gen5W Binder (`hev`, `hscanmicomservice`, `tm_hscan_service`, `datc`, `pgs`, `system`) + `TM_HSCANPacketRunner` logcat 폴백 + Comma 3X UDP/`CVS1`/`CVB1` WebSocket 수집기
    - `TurzxOverlayController`: 헤드유닛 전체 화면(1920×720) 진단 대시보드 (`TYPE_VOLUME_OVERLAY = 2020`). USB·프레임 파이프라인(단계별 ms + 병목 판정) / 배터리 / 전력·주행 / 공조 / 미디어 / 연결·시스템 6개 카드, `[☁️ 업데이트]` / `[💾 로그저장]` / `[✖ 닫기]` 버튼. 부팅 시 숨김 상태로 시작하며 후진(R)·후방카메라·화면 꺼짐 시 자동으로 숨겨집니다. 내장 웹 서버 (`:8766`) 포함
    - `HotkeyWatcher`: **MAP 버튼을 3초 길게 누르면** 대시보드를 열고 닫습니다. D-Audio 키 입력 장치(`/dev/input/eventN`)를 비독점으로 읽으므로 MAP 키 본래 동작(내비 실행)은 그대로 유지됩니다. MAP 키 코드는 `/system/usr/keylayout/daudio*.kl`의 `SHORTCUT_MAP` 라벨에서 자동으로 찾으며, 찾지 못하면 대시보드의 `최근 키` 항목에서 코드를 확인해 `monitor.conf`에 `overlay_hotkey_code=<코드>`로 지정할 수 있습니다
    - `LiveTuning` / `FpsBench`: 대시보드 왼쪽 **실험 패널**에서 JPEG 품질, 목표 FPS, 패널 FPS(`CMD 0x0F`), ADAS 도로 그리기 ON/OFF, 텔레메트리 수집 주기를 `[－]/[＋]`로 즉시 바꿀 수 있습니다. 값을 바꾸면 2초 안정화 후 5초 동안 구간 평균(FPS, 렌더/인코딩/전송/응답/수집 ms, JPEG KB)을 자동 측정해 결과를 쌓습니다. 바꾼 값은 `[💾 저장]`을 누를 때만 `monitor.conf`에 기록되고(그 전에는 재시작하면 원래대로), `[↺ 복원]`으로 저장된 값으로 돌아갑니다. 측정 기록은 로그 저장 리포트의 `FPS bench` 줄에도 남습니다
    - `PanelRstTest`: 실험 패널의 `[🧪RST]` 버튼은 TURZX 패널의 JPEG restart marker(DRI/RST) 지원 여부를 확인합니다. 패널에 A(마커 없음, 기준) → B(MCU 행마다 RST) → C(MCU마다 RST) 테스트 화면을 6초씩 보여주고 전송 성공/실패·응답 시간을 기록합니다(리포트의 `Panel RST test` 줄). B·C가 A와 똑같이 보이면 '바뀐 띠만 재압축' 인코더를 적용할 수 있습니다. 테스트 이미지는 `package_turzx_monitor/panel_test/`에 있고 `tools/make_panel_test_jpegs.py`로 다시 만들 수 있습니다
    - 렌더 파이프라인(v1.4.4): 텔레메트리 수집 / Canvas 렌더 / JPEG 인코딩 / USB 전송이 각각 별도 스레드이고, 랜드스케이프 버퍼 2개를 번갈아 써서 렌더와 인코딩이 동시에 진행됩니다(프레임 시간 = 가장 느린 단계)
    - `DebugManager` & `OtaUpdateManager`: 진단 로그 덤프 수집기 및 GitHub `TLSv1.2` OTA 자동 업데이트 매니저
  - `build_package.py`: JDK 8 컴파일 + 호스트 자체 검증 테스트(`test-self`) + Android API 19 Dalvik DEX(`035`) 변환 + `ota/` 생성 및 패키징 스크립트
- **웹 시안 및 라이브 뷰어**: `turzx_92_mockup.html`

### 다시 빌드하기
```powershell
python package_turzx_monitor/build_package.py
```
- d8은 **build-tools 35 이상(R8 8.5+)** 을 사용하세요. build-tools 34의 d8(R8 8.2.2)은 이 소스의 `TurzxOverlayController$3$1`에서 내부 NPE로 실패합니다.
- Android NDK **r25c**(API 19를 지원하는 마지막 계열)와 CMake가 있으면 `libturzxjpeg.so`를 다시 빌드하고, 없으면 커밋된 prebuilt를 사용합니다. `ANDROID_NDK_HOME`으로 경로를 지정하거나 `TURZX_SKIP_NATIVE_BUILD=1`로 prebuilt 사용을 강제할 수 있습니다.
- `TURZX_REUSE_OTA_ASSETS=1`: Chromium 없이 현재 `ota/turzxmonitor.b64`의 PNG 레이어·폰트를 재사용합니다.

---

## 3. 설치 및 사용 방법

1. [GitHub Releases](https://github.com/GRT47/HKG_5W_TurzxMonitor/releases)에서 `HKG_5W_TurzxMonitor_USB.zip`을 내려받아 압축을 FAT32 포맷된 USB 메모리 루트(`NEMESIS.data`, `packages/hkg.turzxmonitor.zip`)에 풀고 헤드유닛에 삽입하여 NEMESIS로 설치합니다.
2. 설치 후 `hkg-turzxmonitor` 백그라운드 서비스가 자동 실행되며, 유선 USB 허브에 연결된 **TURZX 9.2인치 모니터(`1cbe:0092`)**를 감지하여 자동으로 `1920 × 462` 클러스터 HUD 화면을 전송합니다.
3. 내장 웹 서버(`http://<헤드유닛_IP>:8766/`)에서 다음 엔드포인트를 사용할 수 있습니다:
   - `/`: 실시간 클러스터 HUD 웹 뷰어
   - `/status.json`: 4대 도메인 텔레메트리 + USB + OTA 상태 JSON (`fps`, `jpeg_encoder`, `render_ms`, `road_ms`, `encode_ms`, `encode_wait_ms`, `poll_ms`, `usb_tx_ms`, `ack_ms`, `slot_wait_ms`, `jpeg_kb` 포함)
   - 실험 옵션: `config/monitor.conf`에 `usb_panel_fps=<1~60>`을 넣으면 연결 시 TURZX `CMD 0x0F`(프레임레이트)를 전송합니다 (기본 `0` = 전송 안 함). `hud_road=0`이면 ADAS 3D 도로를 그리지 않고, `telemetry_poll_ms`(기본 `200`)로 텔레메트리 수집 주기를 정합니다
   - `/frame.jpg`: 실시간 렌더링된 `1920 × 462` JPEG 프레임 (요청이 있을 때만 생성, 첫 요청은 최대 0.8초 대기)
   - `/albumart.jpg`: 현재 재생 곡의 원본 앨범 커버 이미지
   - `/debug.txt`: 실시간 진단 로그 리포트 조회 (`?save=1`이면 USB에도 저장)
   - `/update`: GitHub OTA 최신 버전 확인 및 업데이트 트리거
