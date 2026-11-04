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
  - 헤드유닛 상단 오버레이의 `[☁️ 업데이트]` 버튼, 웹 서버 `http://<헤드유닛_IP>:8766/update`, 또는 `launch.sh update` 명령으로 즉시 수동 업데이트 가능.
- **진단 로그 수집 및 USB 자동 동기화 (`DebugManager`)**:
  - 헤드유닛 상단 오버레이의 `[💾 로그저장]` 버튼 클릭, 웹 서버 `http://<헤드유닛_IP>:8766/debug.txt` 접속, 또는 USB 메모리 삽입 시 `/storage/usb0/HKG_TurzxMonitor_Log/` 폴더에 상세 진단 리포트(`HKG_TurzxMonitor_Debug.txt`), 데몬 로그(`hkg_turzxmonitor.log`), USB 커널 로그(`dmesg_usb.txt`), 연결된 USB 장치 목록(`usb_devices.txt`)을 자동으로 수집·저장합니다.

---

## 2. 패키지 구성 및 빌드

- **설치용 USB 번들**: [Releases](https://github.com/GRT47/HKG_5W_TurzxMonitor/releases) (`HKG_5W_TurzxMonitor_USB.zip`)
- **OTA 아티팩트 디렉터리**: `ota/` (`version.json`, `turzxmonitor.b64`, `turzx-daemon.sh`, `launch.sh`)
- **패키지 소스 디렉터리**: `package_turzx_monitor/`
  - `NEMESIS.data`: 패키지 메타데이터 (`id=hkg.turzxmonitor`, `version=1.0.0`)
  - `src_java/com/hkg/turzxmonitor/TurzxMonitorMain.java`:
    - `TurzxUsbDriver`: TURZX 9.2" (`1cbe:0092`) 512바이트 DES-CBC (`"slv3tuzx"`) 명령 헤더 암호화, `480×1920` 투명 PNG 오버레이 초기화(`CMD 102`), 및 `464×1920` 패딩 JPEG 프레임 USB Bulk (`EP 0x01`) 전송 드라이버
    - `TurzxFrameRenderer`: Android `Canvas` 기반 `1920 × 462` 클러스터 HUD 렌더러 (`90°/270°` 네이티브 포트레이트 회전 지원)
    - `TelemetryCollector`: Gen5W Binder (`hev`, `hscanmicomservice`, `tm_hscan_service`, `datc`, `pgs`, `system`) + `TM_HSCANPacketRunner` logcat 폴백 + Comma 3X UDP/`CVS1`/`CVB1` WebSocket 수집기
    - `TurzxOverlayController`: 헤드유닛 화면 상태바 오버레이 (`TYPE_VOLUME_OVERLAY = 2020`, `[☁️ 업데이트]` / `[💾 로그저장]` 버튼 포함) 및 내장 웹 서버 (`:8766`)
    - `DebugManager` & `OtaUpdateManager`: 진단 로그 덤프 수집기 및 GitHub `TLSv1.2` OTA 자동 업데이트 매니저
  - `build_package.py`: JDK 8 컴파일 + 호스트 자체 검증 테스트(`test-self`) + Android API 19 Dalvik DEX(`035`) 변환 + `ota/` 생성 및 패키징 스크립트
- **웹 시안 및 라이브 뷰어**: `turzx_92_mockup.html`

### 다시 빌드하기
```powershell
python package_turzx_monitor/build_package.py
```

---

## 3. 설치 및 사용 방법

1. [GitHub Releases](https://github.com/GRT47/HKG_5W_TurzxMonitor/releases)에서 `HKG_5W_TurzxMonitor_USB.zip`을 내려받아 압축을 FAT32 포맷된 USB 메모리 루트(`NEMESIS.data`, `packages/hkg.turzxmonitor.zip`)에 풀고 헤드유닛에 삽입하여 NEMESIS로 설치합니다.
2. 설치 후 `hkg-turzxmonitor` 백그라운드 서비스가 자동 실행되며, 유선 USB 허브에 연결된 **TURZX 9.2인치 모니터(`1cbe:0092`)**를 감지하여 자동으로 `1920 × 462` 클러스터 HUD 화면을 전송합니다.
3. 내장 웹 서버(`http://<헤드유닛_IP>:8766/`)에서 다음 엔드포인트를 사용할 수 있습니다:
   - `/`: 실시간 클러스터 HUD 웹 뷰어
   - `/status.json`: 4대 도메인 텔레메트리 + USB + OTA 상태 JSON
   - `/frame.jpg`: 실시간 렌더링된 `1920 × 462` JPEG 프레임
   - `/albumart.jpg`: 현재 재생 곡의 원본 앨범 커버 이미지
   - `/debug.txt`: 실시간 진단 로그 리포트 조회 및 USB 저장 트리거
   - `/update`: GitHub OTA 최신 버전 확인 및 업데이트 트리거
