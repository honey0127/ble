# BLE Channel Probe — Phase 0

ESP32 비콘이 광고 payload 에 적어 보낸 **채널 라벨**과 안드로이드가 측정한 **RSSI** 를
CSV 로 남기는 수집 앱이다. 측위·삼변측량·모델은 들어 있지 않다.
Phase 0 의 질문은 하나뿐이다 — **수집이 끊기지 않고 되는가.**

---

## 1. 범위

| 들어 있음 | 들어 있지 않음 |
|---|---|
| BLE 광고 수신, RSSI 기록 | 거리 추정 / 측위 |
| payload 의 channel_id 파싱 | RF 채널 실측 (→ nRF Sniffer) |
| 채널별 rows / pkt/s / seq 연속성 | 필터·칼만·핏팅 |
| CSV 저장 (analyze.py 컬럼명 그대로) | 백그라운드 장시간 수집 |

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

측정 1회에 파일이 **2개** 생긴다. 같은 `<stamp>` 로 짝을 맞춘다.

| 파일 | 컬럼 |
|---|---|
| `beacon_<stamp>.csv` | `rx_wall_ms,rx_elapsed_ms,beacon_id,channel_id,seq,rssi,tx_uptime_ms,tx_power_dbm,tag,ts_nanos,address` |
| `raw_<stamp>.csv` | `rx_wall_ms,rx_elapsed_ms,address,rssi,name,tag,ts_nanos` |
| `events_<stamp>.csv` | 폰 상태 이벤트 로그 (→ 4.1) |

`<stamp>` 는 `yyyyMMdd_HHmmss`. 9/27 에 `ts_nanos`(두 파일)와 `address`(BEACON)를
**맨 끝에** 추가했다. `scripts/` 의 분석 스크립트는 `DictReader` 로 이름을 읽으므로
이전 CSV 와 섞어 써도 깨지지 않는다.

- `rx_wall_ms` — 수신 시각, 벽시계 epoch ms. 파일·세션 간 정렬용
- `rx_elapsed_ms` — 측정 시작부터의 경과 ms. **단조시계**(`elapsedRealtime`) 기준이라
  시간 자동보정이 끼어도 뒤로 가지 않는다
- `channel_id` — 비콘이 적어 보낸 라벨. `37|38|39`, 그리고 `0 = ALL_CONTROL`
  (모드 표식이며 RF 채널 아님. 화면에는 `ALL*` 로 표시)
- `tag` — 실험 조건 문자열. **비어 있으면 앱이 시작을 거부한다**
- `ts_nanos` — `ScanResult.getTimestampNanos()`. 컨트롤러가 패킷을 **관측한 시각**,
  부팅 후 ns (`elapsedRealtimeNanos` 와 같은 시계, 수면 시간 포함).
  `rx_elapsed_ms` 는 콜백이 불린 시각이라 전달 지연이 더해져 있다.
  패킷 간격·M4(채널 전환 소요 시간) 분석은 이 컬럼으로 한다
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
| `session_start` | `RAW`/`BEACON` | 측정 시작. `detail` 에 파일명·기종·SDK·스캔 설정·`batt_opt_exempt`(6장 3번이 실제로 적용됐는지) |
| `tick` | | 10초마다. `rows` 차이 = 10초 처리량 |
| `activity` | `onCreate` … `onDestroy` | Activity 생명주기. `onCreate` 의 `detail` 에 `recreated`·`night`·`fontScale`. `onDestroy` 행은 설정 변경 재생성일 때만 남는다(`changingConfig=1`) — 정말 끝날 때는 ViewModel 이 먼저 정리돼 `session_stop vm_cleared` 가 마지막 행이 된다 |
| `screen` | `on`/`off` | 화면 켜짐·꺼짐 |
| `power_save` | 1/0 | 절전 모드 변경 |
| `doze` | `off`/`light`/`deep` | Doze 진입·해제 |
| `charging` | `connected`/`disconnected` | 충전기 연결·분리 |
| `bt` | 어댑터 상태 | 블루투스 켜짐·꺼짐 |
| `thermal` | 0–6 | 발열 상태 변경 (API 29+) |
| `scan_failed` | errorCode | `onScanFailed` |
| `session_stop` | `user`/`scan_failed`/`vm_cleared` | 측정 종료 이유 |

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

`startScan` 은 30초에 5회를 넘기면 시스템이 **조용히** 결과를 끊는다. 에러도 안 난다.
그래서 앱은 이렇게 만들었다.

- 스캔은 한 번 시작해 계속 유지한다. **모드를 바꿔도 재시작하지 않는다** —
  필터 없이 켜 두고 기록 대상만 바꾼다
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
  9/27 의 이벤트 로그·ViewModel·`ts_nanos`·`address` 변경은 **아직 실기기 빌드 전**이다.
  작업 환경에 Android SDK 가 없어, API 36 프레임워크(Robolectric `android-all`)와
  Compose 공통 API 에 대해 Kotlin 컴파일 검사만 통과시켰다. AndroidX
  activity/lifecycle/core 는 공개 시그니처를 옮긴 스텁으로 대신했다
- 화면을 켠 채 측정한다. 포그라운드 서비스가 없어 장시간 백그라운드 수집은 못 한다
- `channel_id` 는 라벨이다. 실제 RF 채널 확정은 T4c (nRF Sniffer) 로만 한다
- RAW 모드는 `seq` 가 없으므로 `seqObs% / dup / back` 이 의미 없다 (`-` 로 표시)
