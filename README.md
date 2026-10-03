# BLE Channel Probe — Phase 0

BLE 광고의 **RSSI·시각·원본**을 정답(줄자 거리·가림 시각)과 함께 CSV 로 남기는 수집 앱이다.
모드는 셋이다 — RAW(주변 아무 기기), BEACON(우리 ESP32 비콘), TAG(SmartTag2, 본 측정, 9장).
측위·가림 감지·보정은 들어 있지 않다 — Phase 0' GO(10/16) 전에는 넣지 않는다(APP_DESIGN v1).
Phase 0 의 질문은 하나뿐이다 — **수집이 끊기지 않고 되는가.**

---

## 1. 범위

| 들어 있음 | 들어 있지 않음 |
|---|---|
| BLE 광고 수신, RSSI 기록 | 거리 추정 / 측위 |
| payload 의 channel_id 파싱 | RF 채널 실측 (→ nRF Sniffer) |
| 채널별 rows / pkt/s / seq 연속성 | 필터·칼만·핏팅 |
| CSV 저장 (analyze.py 컬럼명 그대로) | 백그라운드 장시간 수집 |
| TAG: 서비스 데이터 원본, 조건 코드, `meta_<stamp>.json` 정답 | 태그 서비스 데이터 해석 (B1 확인 뒤) |
| 추정기 자리(인터페이스) | 거리·가림·보정 구현, 그래프·찾기·재생 화면 (GO 이후) |

`channel_id` 는 **비콘이 스스로 적어 보낸 라벨**이다. 수신기가 실제로 그 채널에서
들었다는 증거가 아니다. 실제 채널 확정은 nRF Sniffer 의 Channel Index 로만 한다.

---

## 2. 준비물

| 품목 | 수량 | 메모 |
|---|---|---|
| ESP32-C3 SuperMini | 1 | 비콘 |
| nRF52840 Dongle | 1 | T4c 채널 실측용 스니퍼 |
| USB-C 케이블 (**데이터 전송용**) | 1 | 충전 전용 케이블은 업로드가 안 된다 |
| 안드로이드 단말 (API 26+) | 1 | API 31+ 권장 (위치 권한 불필요) |

보드가 없어도 **RAW 모드**로 앱 검증은 지금 할 수 있다 (→ 5장).

---

## 3. 빌드

Android Studio → Open → 이 디렉터리 → Sync → Run.

| 항목 | 값 |
|---|---|
| namespace / applicationId | `com.knu.blechprobe` |
| minSdk | 26 |
| compileSdk / targetSdk | 36 |
| Gradle / AGP / Kotlin | 9.4.1 / 9.2.1 / 2.2.10 |
| 테마 | `@android:style/Theme.Material.Light.NoActionBar` (플랫폼 기본) |

`applicationId` 를 바꾸면 **CSV 경로(4장)도 같이 바뀐다.** 문서와 어긋나므로 권하지 않는다.

`compileSdk 36` 에서 AGP 가 불평하면 Android Studio 를 올리거나 일시적으로 `35` 로 낮춰도
Phase 0 측정에는 지장이 없다.

테마에 `Theme.Material3.*` 를 쓰면 안 된다. `com.google.android.material` 의존이 없어
`resource not found` 로 깨진다.

---

## 4. CSV 출력

경로 (앱 전용 외부 저장소 — 권한 없이 쓰이고, 앱을 지우면 같이 지워진다):

```
내장저장소/Android/data/com.knu.blechprobe/files/ble_logs/
```

측정 1회에 파일이 **3개** 생긴다. 같은 `<stamp>` 로 짝을 맞춘다.

| 파일 | 컬럼 |
|---|---|
| `raw_<stamp>.csv` | `rx_wall_ms,rx_elapsed_ms,address,rssi,name,tag,ts_nanos,svc_data_uuids,svc_uuids,mfg_ids,is_legacy,primary_phy,secondary_phy,adv_sid,adv_hex` |
| `beacon_<stamp>.csv` | `rx_wall_ms,rx_elapsed_ms,beacon_id,channel_id,seq,rssi,tx_uptime_ms,tx_power_dbm,tag,ts_nanos,address` |
| `tag_<stamp>.csv` | `rx_wall_ms,rx_elapsed_ms,ts_nanos,address,rssi,is_legacy,primary_phy,secondary_phy,adv_sid,svc_uuid,svc_data_hex,scan_seq,cond` (→ 9장) |
| `events_<stamp>.csv` | 폰 상태·런 이벤트 로그 (→ 4.1) |
| `meta_<stamp>.json` | 런 1개의 정답과 조건·스캔 설정·기종·앱 버전 (→ 9장) |

`<stamp>` 는 `yyyyMMdd_HHmmss`. 9/27 에 `ts_nanos`(두 파일)와 `address`(BEACON)를
**맨 끝에** 추가했다. 새 컬럼은 앞으로도 맨 끝에 붙인다. `scripts/` 의 분석 스크립트는
`DictReader` 로 이름을 읽으므로 이전 CSV 와 섞어 써도 깨지지 않는다.

- `rx_wall_ms` — 수신 시각, 벽시계 epoch ms. 파일·세션 간 정렬용
- `rx_elapsed_ms` — 측정 시작부터의 경과 ms. **단조시계**(`elapsedRealtime`) 기준이라
  시간 자동보정이 끼어도 뒤로 가지 않는다
- `channel_id` — 비콘이 적어 보낸 라벨. `37|38|39`, 그리고 `0 = ALL_CONTROL`
  (모드 표식이며 RF 채널 아님. 화면에는 `ALL*` 로 표시)
- `tag` — 실험 조건 문자열. **비어 있으면 앱이 시작을 거부한다**
- `ts_nanos` — `ScanResult.getTimestampNanos()`. 공식 문서가 말하는 것은 "부팅 후,
  스캔 레코드가 관측된 시각"까지다 [공식]. **컨트롤러가 받은 시각인지 호스트가 처리한
  시각인지는 문서에 없다 [미검증]** — 9/27 판 README·주석의 "컨트롤러 관측 시각,
  전달 지연 제외"는 근거 없는 서술이었다. `elapsedRealtimeNanos` 와 같은 시계라
  이벤트 CSV 와 그대로 맞댈 수 있다. `rx_elapsed_ms` 는 콜백이 불린 시각이다.
  패킷 간격·M4 분석의 기준 시각으로 쓰되, 두 시각의 차이 자체가 확인 대상이다
- `svc_data_uuids`, `svc_uuids`, `mfg_ids` (RAW, 9/29) — 광고에 든 서비스 데이터 UUID ·
  광고 서비스 UUID · 제조사 ID (`|` 로 이음). B1(태그가 어떤 식별자로 광고하나) 찾기용 (10장)
- `is_legacy`, `primary_phy`, `secondary_phy`, `adv_sid`, `adv_hex` (RAW, 10/3) — 광고 형식과
  `ScanRecord.getBytes()` 원본. 레거시 광고는 원본 뒤가 0 으로 채워져 있을 수 있다.
  RAW '확장 광고 포함'(9.1)으로 받아야 확장 광고가 보인다 — B1·B9 를 한 번에 가른다
- `address` (BEACON) — 송신 MAC. T4c 에서 nRF Sniffer 캡처와 대조하고,
  비콘이 여러 대가 되면 `beacon_id` 와 교차 확인하는 데 쓴다

화면 지표 정의:

| 지표 | 정의 | 용도 |
|---|---|---|
| `rows` | 수신 행 수 (같은 seq 중복 포함) | 원자료 |
| `pkt/s` | `rows ÷ 경과초` | **조건 비교는 이 값으로.** 가정 없는 직접 측정값 |
| `seqObs%` | `고유 seq ÷ (max−min+1)` | 연속성. 100% 를 넘을 수 없다 |
| `dup` | `rows ÷ 고유 seq` | 타이밍 진단값 |
| `back` | seq 역행 횟수 | 0 이 아니면 T2 확인 대상 |
| `events` | 이벤트 로그 행 수 | 10초마다 최소 1씩 늘어야 정상 |

### 4.1 이벤트 로그 `events_<stamp>.csv`

처리량이 꺾인 순간 폰이 어떤 상태였는지 남긴다. 9/22 1차 측정에서 5분 지점 급락의
원인을 추정밖에 못 한 이유가 이 기록이 없어서였다.

**모든 행에 그 순간의 상태 스냅샷이 같이 붙는다.** 방송(broadcast)을 놓치거나 늦게
받아도 다음 `tick` 이 현재 값을 직접 다시 읽어 적는다.

| 컬럼 | 뜻 |
|---|---|
| `rx_wall_ms`, `rx_elapsed_ms` | 데이터 CSV 와 같은 기준. `rx_elapsed_ms` 로 두 파일을 바로 맞댈 수 있다 |
| `ts_nanos` | 기록 시각, `elapsedRealtimeNanos`. 데이터 CSV 의 `ts_nanos` 와 **같은 시계** |
| `event`, `value` | 무슨 일이 있었나 (아래 표) |
| `rows` | 그 시점의 누적 수신 행 수 |
| `screen_on` | 1/0 (`PowerManager.isInteractive`) |
| `activity` | 마지막 Activity 생명주기 (`onResume`, `onPause`, `onStop` …) |
| `importance` | 시스템이 보는 프로세스 중요도. 100=foreground, 125=foreground service, 200=visible, 325=top sleeping, 400=cached |
| `power_save` | 절전 모드 1/0 |
| `doze` | `off` / `light` (API 33+) / `deep` |
| `plugged` | `none` / `ac` / `usb` / `wireless` |
| `batt_pct`, `batt_temp_c` | 배터리 잔량 %, 배터리 온도 °C |
| `thermal` | `getCurrentThermalStatus` 0=NONE … 3=SEVERE … 6=SHUTDOWN (API 29+) |
| `headroom` | `getThermalHeadroom(0)`. 1.0 = SEVERE 스로틀 도달. **`tick` 행에만** 있다 (API 30+) |
| `bt` | 블루투스 어댑터 `on` / `off` / `turning_on` / `turning_off` |
| `detail` | 이벤트별 부가 정보 |
| `tag` | 데이터 CSV 와 같은 tag |

| `event` | `value` | 언제 |
|---|---|---|
| `session_start` | `RAW`/`BEACON`/`TAG` | 측정 시작. `detail` 에 파일명·기종·SDK·스캔 설정·`batt_opt_exempt`(6장 3번이 실제로 적용됐는지) |
| `scan_start` | `scan_seq` | `startScan` 호출. `detail` 에 호출 직전·직후 `pre_ns`/`post_ns` — 채널 역산(M4)의 기준 시각 |
| `timer` | `10`/`30`/`90`/`120` | TAG 런 알림음이 울린 시각. `10` = 카운트다운 끝(런 시계 0 s), 나머지는 런 시계 초 |
| `block_in_planned` / `block_out_planned` | 사람 수 | 사람 가림 런의 예정 전환 시각(알림음 시각). `detail` 에 가림 위치 |
| `run_end` | `auto`/`user`/… | 런 종료. `auto` = 120 s 자동 종료 |
| `run_flag` | `valid`/`invalid` | 런이 끝난 뒤 조작자가 표시. `detail` = 이유. 여러 번 누르면 마지막 행이 유효 |
| `tick` | | 10초마다. `rows` 차이 = 10초 처리량 |
| `activity` | `onCreate` … `onDestroy` | Activity 생명주기. `onCreate` 의 `detail` 에 `recreated`·`night`·`fontScale`. `onDestroy` 행은 설정 변경 재생성일 때만 남는다(`changingConfig=1`) — 정말 끝날 때는 ViewModel 이 먼저 정리돼 `session_stop vm_cleared` 가 마지막 행이 된다 |
| `screen` | `on`/`off` | 화면 켜짐·꺼짐 |
| `power_save` | 1/0 | 절전 모드 변경 |
| `doze` | `off`/`light`/`deep` | Doze 진입·해제 |
| `charging` | `connected`/`disconnected` | 충전기 연결·분리 |
| `bt` | 어댑터 상태 | 블루투스 켜짐·꺼짐 |
| `thermal` | 0–6 | 발열 상태 변경 (API 29+) |
| `scan_failed` | errorCode | `onScanFailed` |
| `session_stop` | `user`/`auto`/`scan_failed`/`vm_cleared` | 측정 종료 이유 (`run_end` 바로 다음 행) |

`scan_restart` 는 아직 나오지 않는다. 런 도중에 스캔을 다시 시작하는 경로가 없어서
`scan_seq` 는 늘 1 이다 (런마다 새 스캔).

읽을 때 주의:

- **이벤트 시각은 "앱이 알게 된 시각"이다.** Android 14+ 는 cached 상태 앱에
  `SCREEN_ON` 같은 방송을 미뤘다가 준다 (developer.android.com, Broadcasts overview)
- **`tick` 간격이 10초보다 크게 벌어지면 그 구간에서 앱 자체가 멈춰 있었다는 뜻이다.**
  그 자체가 증거다
- 화면이 켜져 있으면 Doze 는 걸리지 않는다. `doze` 는 "안 걸렸다"를 확인하는 용도다

---

## 5. 오늘 할 수 있는 검증 (보드 없이)

RAW 모드는 주변 아무 BLE 기기나 잡아서 쌓는다. 앱 자체 검증에 그걸 쓴다.

| 확인 | 방법 | 통과 기준 |
|---|---|---|
| 권한 | 시작 누르기 | 권한 팝업 → 허용 후 `rows` 증가 |
| 수신 | 30초 방치 | `rows` 가 계속 늘어남 |
| **10분 연속** | 충전기 꽂고 방치 | 0인 분이 없음 (콜백 생존) **그리고** 분당 수신량이 유지됨 |
| 쓰로틀링 | 시작/정지를 빠르게 5번 | "쓰로틀링 보호" 문구가 뜸 |
| CSV | 4장 경로 확인 | `raw_*.csv` 생성, 열어서 행 증가 확인 |

10분 테스트 전에 **6장을 먼저 한다.** 안 하면 중간에 죽는다.

**"0인 분이 없다"는 A3 통과 기준이 아니다.** 스캔 콜백이 죽지 않은 것과
처리량이 유지된 것은 다른 얘기다. 1차 측정(`raw_20260922_092605.csv`)에서
0인 분은 없었지만 5분 지점에서 분당 수신량이 약 30배 급락했다 — 콜백은
살아 있었지만 사실상 다른 조건으로 측정 구간이 갈린 것이다. 검증 절차:

1. `scripts/raw_check.py <csv>` — 0인 분이 있는지, 즉 콜백이 죽었는지만 본다.
   **통과 판정이 아니다**
2. `scripts/raw_breakdown.py <csv>` — 분당 수신량이 중간에 꺾이면, 기기별로
   갈라서 수신기 측(듀티사이클 강등 등) 원인인지 환경 측(주변 기기 밀도
   변화)인지 정황을 본다. **표본이 희박한 구간(분당 수신량이 원래의
   1/10 이하)에서는 기기별 배율 판정 자체가 잡음에 취약하니 정황 이상으로
   읽지 않는다**
3. 꺾인 시각을 `events_<stamp>.csv` 와 맞댄다 — `tick` 행의 `rows` 차이로
   10초 단위 처리량을 보고, 꺾이기 직전·직후 행의 상태 열(`screen_on`,
   `activity`, `importance`, `power_save`, `doze`, `plugged`, `thermal`,
   `headroom`, `bt`)이 바뀌었는지 본다. 아무것도 안 바뀌었는데 꺾였다면
   그것도 결과다 — 이 로그가 잡지 못하는 원인(제조사 스캔 정책 등) 쪽이다
4. 확정은 고정 주소 기기(ESP32 등)로만 한다 —
   `scripts/raw_breakdown.py <csv> <분할분> <MAC일부>`. RPA 를 쓰는
   일반 스마트폰 주소는 "소멸/신규"가 실제 이동이 아니라 주소 교체일 수
   있어 결정적 근거가 못 된다

A3 는 현재 **미검증**이다. 재측정부터는 앱이 상태를 `events_<stamp>.csv` 에
같이 남긴다 (4.1). 원인 후보와 진행 상황은 [`STATUS.md`](STATUS.md) 에 있다.

재측정 중에 다크모드 자동 전환이나 글꼴 크기 변경이 일어나도 측정은 끊기지 않는다.
측정기가 Activity 가 아니라 ViewModel 에 있어서다. 그런 일이 있었다면 이벤트 로그에
`activity onDestroy changingConfig=1` → `onCreate recreated=1` 로 남는다.

### 스캔 재시작 제한

`startScan` 을 30초에 5회 넘게 부르면 시스템이 그 스캔을 **시작하지 않는다.** 에러 콜백 없이
로그("App ... is scanning too frequently")만 남는다. 공식 문서가 아니라 AOSP 소스
(Bluetooth `AppScanStats` 의 `NUM_SCAN_DURATIONS_KEPT=5`, `EXCESSIVE_SCANNING_PERIOD_MS=30 s`,
Android 7+)의 규칙이다 [AOSP 소스] — 제조사가 바꿨을 수 있다. 그래서 앱은 이렇게 만들었다.

- 스캔은 측정(런)마다 한 번 시작해 끝까지 유지한다. **런 도중에는 재시작하지 않는다** —
  필터 없이 켜 두고 모드에 따라 기록 대상만 고른다. 모드는 측정 중에 바꿀 수 없다
- 화면에 `스캔 시작 여유 N / 4` 를 표시해 시스템 한도 5 보다 하나 앞에서 막는다

---

## 6. 측정 전 배터리 설정 (필수, 5개)

Galaxy / One UI 기준이다. 버전에 따라 메뉴 이름이 조금씩 다르니 이름으로 찾는다.
**하나라도 남겨 두면 10분 테스트가 중간에 죽는다.**

1. **절전 모드 OFF** — 설정 → 배터리
2. **사용하지 않는 앱 절전 / 적응형 배터리 OFF** — 설정 → 배터리 → 기타 배터리 설정
3. **앱별 배터리 제한 해제** — 설정 → 앱 → BLE Channel Probe → 배터리 → **제한 없음**
4. **백그라운드 사용 제한 목록에서 제외** — 설정 → 배터리 → 백그라운드 사용 제한
5. **화면 자동 꺼짐 10분 이상 + 충전기 연결** — 설정 → 디스플레이 → 화면 자동 꺼짐 시간

5번은 앱이 `FLAG_KEEP_SCREEN_ON` 을 걸어 두긴 하지만, 필터 없는 스캔은 화면이 꺼지면
결과가 안 올라올 수 있어 이중으로 막는다. Phase 0 은 **화면을 켠 채** 측정한다.

---

## 7. 펌웨어

`beacon_ch_sweep.ino` 는 이 저장소에 없다 (아두이노 스케치 쪽에 있다).
적용해야 할 주석 수정은 [`docs/firmware-comment-patch.md`](docs/firmware-comment-patch.md)
에 원문/수정문 그대로 적어 두었다. 손으로 두 군데만 바꾸면 된다.

---

## 8. 알려진 한계

- **빌드:** 커밋 `79bd045` 기준으로 실기기에서 `assembleDebug` 와 실행을 확인했다.
  10/3 빌드도 실기기에서 돌았다 (10/3 RAW 측정 meta 에 `preflight`, 내보내기 sha256 일치).
  9/27(이벤트 로그·ViewModel)·9/29(패키지 분리·TAG 모드)·9/29(RAW 식별자 컬럼·UI 정리)·10/3(RAW 확장 포함·내보내기·표시 사유·시작 전 점검) 변경은 **아직 실기기 빌드 전**이다.
  작업 환경에 Android SDK 가 없어, API 36 프레임워크(Robolectric `android-all`)와
  Compose 공통 API 에 대해 Kotlin 컴파일 검사만 통과시켰다. AndroidX
  activity/lifecycle/core 는 공개 시그니처를 옮긴 스텁으로 대신했다
- **TAG 필터의 서비스 UUID(FD5A/FD59)는 B1 에서 확인할 가정이다 [미검증].** 태그가 다른
  UUID 로 광고하면 TAG 모드는 아무것도 기록하지 않는다(점검 표시 '마지막 수신'이 빨개진다).
  `parse/TagFilter.kt` 의 목록 한 줄만 고치면 된다
- 화면을 켠 채 측정한다. 포그라운드 서비스가 없어 장시간 백그라운드 수집은 못 한다
- `channel_id` 는 라벨이다. 실제 RF 채널 확정은 T4c (nRF Sniffer) 로만 한다
- RAW 모드는 `seq` 가 없으므로 `seqObs% / dup / back` 이 의미 없다 (`-` 로 표시)

---

## 9. TAG 모드 측정 (APP_DESIGN v1, Phase 0')

### 9.1 모드별 스캔 설정

공통: `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, report delay 0, 필터 없음.

| 모드 | legacy | PHY | 이유 |
|---|---|---|---|
| RAW (기본) | `true` | 지정 안 함 | 9/22 A3 측정과 같아야 재측정 결과를 비교할 수 있다 |
| RAW + 확장 광고 포함 | `false` | `PHY_LE_1M` | TAG 와 같은 설정. 태그 도착 날 B1(다른 UUID?)·B9(확장 광고?)를 가르려고. **9/22 와 비교하지 않는다** (`meta.scan.raw_extended`=true, `raw_check.py` 가 경고) |
| TAG | `false` | `PHY_LE_1M` | `setLegacy` 기본값 true 는 레거시 광고만 돌려준다 [공식] — 태그가 확장 광고를 쓰면 안 보인다(B9). `setPhy` 는 legacy=false 일 때만 쓰이고 기본값은 문서에 없다 [공식]. `PHY_LE_ALL_SUPPORTED` 면 Coded PHY 도 스캔해 스캔 창 시간표가 바뀔 수 있어 [추론] 1M 으로 고정 |
| BEACON | `false` | `PHY_LE_1M` | TAG 와 같게 — M4 결과를 TAG 측정에 옮기려면 스캔 설정이 같아야 한다 |

legacy=false 여도 레거시 광고는 그대로 받는다. `meta_<stamp>.json` 의 `scan` 에 설정과
`le_extended_adv_supported`·`le_coded_phy_supported`(어댑터 지원 여부)가 남는다.

### 9.2 조건 입력 → 조건 코드

자유 입력 대신 칩으로 고른다. 오타 한 번이면 정답 매칭(T5)이 깨지기 때문이다.

- 거리 1/3/5 m · 가림(없음/1/2/3명/벽/장비) · 가림 위치(태그 앞/중간/폰 앞) · 날(1~3) · 배치 번호
- **조건 코드** 자동 생성 — 예: `d3_p2-mid_day1_pl04`, `d1_none_day2_pl01`.
  `tag_<stamp>.csv` 의 `cond`, 이벤트 CSV 의 `tag`, meta 의 `cond` 에 같은 값이 들어간다
- 고정 설정(펼쳐서 입력, 앱을 다시 켜도 남는다): 폰·태그 높이(cm), 방향, 조작자 위치,
  태그 등록 여부, SmartThings 연결 여부
- **폰·태그 높이가 비어 있으면 시작하지 않는다.** 정답 직선거리 = √(수평거리² + 높이차²)
  를 meta 에 적어야 해서다. 벽이면 재질, 장비면 종류도 필수

### 9.3 런 진행 — 측정 중에는 아무도 폰을 만지지 않는다

런 종류는 가림 종류가 정한다(따로 고르면 둘이 어긋날 수 있다).

| 가림 | 런 | 진행 (런 시계 초) |
|---|---|---|
| 없음 | 무가림 120 s | 시작 → 10 s 카운트다운 → 0 ~ 120 기록 → 자동 종료 |
| 1/2/3명 | 사람 가림 30-60-30 | 카운트다운 → 0 무가림 → **30 들어오세요** → **90 나가세요** → 120 자동 종료 |
| 벽/장비 | 정적 120 s | 무가림 120 s 와 같다 |

알림음 (알람 음량을 쓴다 — **측정 전에 알람 볼륨을 올려 둘 것**):

| 소리 | 뜻 |
|---|---|
| ▬ (1번) | 카운트다운 끝, 기록 시작 (런 시계 0) |
| ▬ ▬ (2번) | 30 s — 들어오세요 |
| ▬ ▬ ▬ (3번) | 90 s — 나가세요 |
| ▬▬▬ (길게) | 120 s — 끝, 자동 종료 |

- 가림 시각은 버튼이 아니라 **알림음 시각으로 자동 기록**한다(`block_in_planned` / `block_out_planned`).
  손이나 몸이 폰에 가까워지면 그것 자체가 가림이 된다 [추론]
- 카운트다운 10 초 동안 조작자는 정해진 자리로 물러난다. 이 구간도 기록하지만 채점에서 뺀다
- 사람이 움직이는 몇 초도 채점에서 뺀다 — 몇 초로 할지는 Phase 1 전에 고정
- 화면 맨 위에 큰 타이머와 안내 문구가 뜬다(멀리서 보이게)
- 런이 끝나면 **유효 / 무효** 를 표시한다 (9.7). `run_flag` 이벤트와 meta 의
  `result.flag` 에 남는다. 표시는 런이 끝난 뒤에만 한다

### 9.4 점검 표시

| 표시 | 뜻 |
|---|---|
| `pkt/s` | 기록 대상 패킷 수 ÷ 경과초 |
| 마지막 RSSI | 가장 최근 기록 행 |
| 광고 | 마지막 패킷이 `legacy` 인지 `확장` 인지 (B9) |
| 마지막 수신 | 마지막 기록 행 이후 경과 초. **5 초를 넘으면 빨간색**(태그 광고 주기 확인 뒤 조정 [추론]) |

### 9.5 `meta_<stamp>.json`

런을 시작할 때 쓰고, 끝날 때(`result.end`·`rows`·`duration_s`)와 유효/무효 표시 때
(`result.flag`·`flag_reason`) 다시 쓴다. 모르는 값은 `null`.

`truth`(수평거리·높이·직선거리) · `condition`(거리·가림·사람 수·위치·벽 재질·장비·날·배치·조작자 위치) ·
`orientation` · `tag_state`(등록·SmartThings·배터리 모드 `normal` 고정) · `run`(종류·카운트다운·전환 시각) ·
`scan` · `device`(제조사·기종·SDK·빌드·fingerprint) · `app`(버전) · `photo` · `result`

RAW·BEACON 도 meta 를 남긴다(`truth`·`condition` 은 `null`, `run.type` = `MANUAL`).

### 9.6 코드 구조

```
collect/   Collector(스캔·기록·런 진행) PhoneState CsvSink SampleCsv ScanConfig RunMeta Beeper FormStore ViewModel
parse/     BeaconParser  TagFilter(FD5A/FD59 원본만 — 해석은 B1 뒤)
model/     Sample RunEvent TagForm·RunType(조건) Mode 화면 스냅샷
source/    SampleSource — 실시간(Collector)과 GO 이후 재생(CsvReplaySource)이 같은 흐름을 쓴다
estimate/  DistanceEstimator ChannelClassifier BlockageDetector Corrector — 인터페이스만. GO 이후 구현
ui/        RecordScreen(① 측정) TagFormSection
```

새 라이브러리는 넣지 않았다. ② 라이브 그래프 · ③ 찾기 · ④ 재생 화면은 GO 이후.

### 9.7 유효/무효 표시 — 결과를 보지 않고 절차로만

"잘 안 나온 런을 뺀 것 아닌가"라는 질문에 답하려고 만든 규칙이다.

- **측정 중과 표시 전에는 pkt/s·RSSI·rows·수신 표를 가린다.** '마지막 수신 N s 전'과
  `광고` legacy/확장은 보인다 — 결과가 아니라 태그가 살아 있는지 보는 절차 점검이라서다
- **무효는 절차 사유 칩을 하나 이상 골라야 누를 수 있다** (여러 개 가능). 사유 코드:

  | 코드 | 칩 |
  |---|---|
  | `move_timing` | 알림음과 다르게 움직임 |
  | `touched` | 폰·태그를 건드림 |
  | `intruder` | 계획 밖 사람·물체 |
  | `setup` | 배치·거리·높이 오류 |
  | `no_beep` | 알림음을 못 들음 |
  | `device` | 폰·앱·태그 이상 |

- 유효는 사유 없이 누른다 (칩을 고른 상태에서는 유효가 눌리지 않는다)
- **한 번 표시하면 바꿀 수 없다.** 결과를 본 뒤 표시를 바꾸는 일을 막는다
- `run_flag` 의 `detail` = 사유 코드(`|` 로 이음), meta `result.flag_reasons` = 코드 배열

### 9.8 시작 전 점검

측정 중이 아닐 때 화면에 뜬다. 그 런을 시작한 순간의 값이 meta `preflight` 에 남는다.

| 앱이 읽음 | 사람이 확인 |
|---|---|
| 절전 모드 · 배터리 최적화 제외 · 충전 · 알람 음량 · 방해 금지 · Wi-Fi(기록만) | SmartThings 종료 · 워치·버즈 연결 끊기 · 태그 일반 모드 · 알림음 시험 |

**사람 가림 런은 다음 중 하나면 시작 버튼이 막힌다** — 알림음이 곧 가림 시각의 정답이라,
소리가 안 나면 그 런은 정답이 없다.

- 알람 음량 0
- 방해 금지가 완전 무음 (`INTERRUPTION_FILTER_NONE`: 전화 외 모든 오디오 스트림을 끈다 [공식])
- 알림음 시험 미확인 — [알림음 시험]을 눌러 들리면 [들렸으면 누르기]. 알람 음량이 바뀌면 다시 해야 한다

사람이 확인하는 항목은 앱을 다시 켜면 처음부터 다시 확인한다. 나머지 런은 막지 않고 보여 주기만 한다.

### 9.9 하루치 내보내기

앱을 지우면 앱 전용 저장소의 CSV 도 지워진다. 그날 측정이 끝나면 화면 맨 아래
**측정 내보내기**에서 날짜를 눌러 zip 으로 묶고 공유 시트(드라이브·메일·PC 등)로 보낸다.

- zip = 그날(`<yyyyMMdd>_` stamp) 의 `raw_/beacon_/tag_/events_/meta_` 파일 전부 + `files.txt`
- `files.txt` = 파일명 · 바이트 · **sha256**, 머리에 내보낸 시각·기종·앱 버전.
  개발일/평가일을 나눌 때 "그날 그 파일"이라는 증거가 된다 (파일이 바뀌면 sha256 이 달라진다)
- 측정 중에는 내보내지 않는다 (쓰는 중인 파일이 있다). zip 은 `files/exports/` 에 남고,
  FileProvider 로 그 폴더만 공유한다

---

## 10. 분석 스크립트 (`scripts/`)

전부 파이썬 표준 라이브러리만 쓴다. 같은 폴더의 `events_`/`meta_<stamp>` 를 자동으로 찾는다.
**판정은 하지 않고 숫자만 낸다** — 통과 기준은 PLAN 이 정한다.

| 스크립트 | 언제 | 무엇 |
|---|---|---|
| `raw_check.py <raw.csv>` | A3 1단계 | 0 인 분이 있나 (콜백 생존) |
| `raw_breakdown.py <raw.csv> [분 MAC]` | A3 2·4단계 | 급락이 수신기 측인가 환경 측인가, 고정 MAC 배율 |
| `events_view.py <events.csv>` | A3 3단계 | 10초 처리량과 상태 변화를 시간순으로. 가장 큰 낙폭(▼)과 그 ±20초 이벤트 |
| `b1_find.py <raw.csv>` | B1·B9 | 태그를 폰에 붙이고 **RAW 확장 포함** 1분 → 가장 센 기기의 식별자가 TAG 필터(FD5A/FD59)와 같은가, legacy/확장 비율, 광고 원본 AD 구조 |
| `tag_check.py <tag.csv> [--margin 3]` | TAG 런마다 | 수집량·공백, legacy/PHY(B9), 주소 교체(T2), 수신 간격, 콜백−ts_nanos 시각 차이, 구간별 RSSI, 표시·사유·시작 전 점검·스캔 설정 |
| `m4_channel.py <beacon.csv> [--dwell D]` | M4 | FIXED 채널 런의 받은 시각이 채널 시간대에 몰리나. D 탐색, 경과 시간별 적중률. **스캔 설정이 TAG 와 다르면 경고** |

합성 데이터로 확인한 것 (정답을 심어 두고 찾는지 봤다):

- `events_view` — 300 s 에 화면 꺼짐 + 처리량 1/30 → 310 s 구간 0.03배, 직전 `screen off`·`onPause` 표시
- `b1_find` — FD5A 기기(-38 dBm)가 맨 위, "B1 가정이 맞다"
- `tag_check` — 가림 구간 RSSI -68 vs 앞뒤 -60, 콜백 지연 15 ms, 주소 교체 1번, 9 s 공백 1번
- `m4_channel` — D=4.096 s 순환을 4.097 s 로 찾음, 적중률 0.99, 채널 위상이 1/3 씩 어긋남.
  **대조군**(시간대와 무관한 수신)은 0.40 — D 를 탐색하면 우연만으로도 0.4 가 나온다

B1·B9 찾는 법: 태그를 폰 뒷면에 붙인 채 **RAW '확장 광고 포함'** 으로 1분 → `b1_find.py`.
가장 센 줄의 서비스 데이터 UUID(B1)와 legacy 비율(B9)을 본다. 태그를 멀리 치웠다가 다시
붙여 한 번 더 받아 같은 줄이 맨 위면 태그다. 기본(레거시만) RAW 로 받으면 확장 광고만 쓰는
태그는 안 나오고, 스크립트가 그렇다고 경고한다.
