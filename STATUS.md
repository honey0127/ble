# STATUS — Phase 0

기준일 **2026-10-03**. **기준본은 프로젝트 STATUS(저장소 밖)다.** 이 파일은 저장소 쪽 작업 기록이다.

> 9/11 자 이전 STATUS.md("실측 0건")는 이 저장소에 커밋된 적이 없다(저장소 밖 사본).
> 판정·가정·일정은 프로젝트 STATUS 를 따른다. 둘이 다르면 프로젝트 STATUS 가 맞다.

---

## 한눈에

| 항목 | 상태 | 근거 |
|---|---|---|
| 빌드 | **10/3 빌드 실기기 동작 확인** | 10/3 RAW 측정의 meta 에 `preflight`·`raw_extended` 가 있고, 내보낸 zip 의 `files.txt` sha256 3개가 받은 파일과 일치 (내보내기·공유 시트 동작) |
| 실측 | **2건** | A3 1차 `raw_20260922_092605.csv`, A3 2차 `raw_20261003_194906.csv` (둘 다 RAW 레거시, 10분) |
| A3 (10분 연속 스캔) | **미검증** | 아래 A3 절 |
| 고정 주소 기준 (ESP32) | 없음 | 보드 도착 전. 1차 측정 때 송신 중인 보드가 없었다 |

---

## A3 — 2차 측정 (10/3, 이벤트 로그 포함)

`raw_20261003_194906.csv` · SM-S931N(Android 16, `BP4A.251205.006`) · RAW 레거시(9/22 과 같은 설정) ·
화면 켬, USB 충전, 배터리 최적화 제외. 런 표시는 안 함(`flag` null).

| 관측 | 값 |
|---|---|
| 0 인 분 | 없음 (콜백 생존) |
| 분당 수신량 | 0~4분 8,236~8,682 → **5분부터 104~221**, 약 68배 급락 |
| 급락 시각 | **스캔 시작(`scan_start` post_ns) 후 300.0~300.7 s.** 299~300 s 151개 → 300~301 s 15개 → 이후 초당 0~7개. 계단형 |
| 그 순간 폰 상태 | ±20 s 안에 바뀐 것 **없음** — 화면 켬·`onResume`·중요도 100·절전 0·Doze off·USB 충전·발열 0·headroom 0.53~0.55·BT on |
| 분당 고유 주소 | ~500 → 25~39. 주소당 행 16 → 3~8 |
| 받는 기기 구성 | 급락 전 4분대: Microsoft(0006) 39 %, Apple(004C) 38 %, FEF3 서비스데이터 9 % … → **급락 뒤: FEF3 서비스데이터 95 %**, Apple·Microsoft 각 1 % |
| 급락 뒤 도착 간격 | 5.12·10.24·4.096 s 주기로 접어도 몰림 없음 (≈ 우연) — 듀티사이클을 줄인 모양이 아니다 |

UUID·회사 소유자 (Bluetooth SIG assigned numbers): FEF3·FCF1·FE2C = Google LLC, FD82·0x012D = Sony,
0x004C = Apple, 0x0006 = Microsoft, **FD5A·FD59 = Samsung** (TAG 필터 가정과 일치).

해석 [추론]:
- **스캔 시작 후 정확히 5분에 걸리는 시간 규칙**이다. 화면·포그라운드·절전·Doze·충전·발열·BT 는
  이 로그상 원인이 아니다 (9/22 의 '화면/포그라운드' 후보는 배제)
- 듀티사이클 강등이면 모든 기기가 고르게 줄어야 하는데, **특정 종류(Google 서비스 광고)만 남았다.**
  우리 스캔이 opportunistic 으로 바뀌어 다른 앱(Play 서비스 등)의 필터 스캔 결과만 받는 모양과 맞는다.
  AOSP 블루투스 모듈(Android 16 계열) `ScanManager` 는 스캔을 시작할 때 타임아웃을 예약하고, 시간이 되면
  무필터 스캔을 opportunistic 으로 옮긴다("Moving unfiltered scan client to opportunistic scan") [문헌].
  기본값은 10분(`DEFAULT_SCAN_TIMEOUT_MILLIS`)이고 DeviceConfig `bluetooth/scan_timeout_millis` 로 바뀐다 [문헌].
  흔히 인용되는 "30분"은 옛 값이다. S25 의 300 s 는 이 값이 300000 인 경우로 보인다 [추론] —
  `adb shell device_config get bluetooth scan_timeout_millis` 로 확인
- 확정은 logcat 으로 한다: 그 시각에 `BtGatt.ScanManager` 의 opportunistic 전환 로그가 찍히는지

TAG 측정에 주는 뜻:
- **TAG 런(카운트다운 10 s + 120 s = 130 s)은 5분 안이고 런마다 스캔을 새로 시작한다.**
  새 `startScan` 이 5분 시계를 새로 시작하는지는 아직 모른다 → 런을 연달아 돌려 확인 필요
- 10분 연속 무필터 스캔(원래의 A3 가정)은 이 폰에서 성립하지 않는다

## A3 — 1차 측정 (9/22)

| 관측 | 값 |
|---|---|
| 0 인 분 | 없음 → 스캔 콜백은 살아 있었다 |
| 분당 수신량 | **5분 지점에서 7657 → 255 pkt/min, 약 30배 급락** |
| 기기별 분해 (`raw_breakdown.py`) | 이전 주소의 90% 가 **같은 1분 경계에서 동시에** 사라짐 |

해석 [미검증]: 주소 약 700개가 각자의 RPA 타이머로 바뀌었다면 같은 1분에 몰려 사라지지
않는다. 수신기 쪽 사건(스캔 듀티사이클 강등 등)의 정황이다. 다만 고정 주소 기준이 없었고,
그 순간의 폰 상태가 기록되지 않아 원인은 추정에 머문다.

"0 인 분이 없다"는 A3 통과 기준이 아니다. 콜백 생존과 처리량 유지는 다른 주장이다
(README 5장).

9/22 급락은 설정 변경 재생성 버그(아래) 때문이 아니다. 그랬다면 CSV 가 닫혀 5분 이후
행이 아예 없었어야 하는데, 분당 255 행이 계속 들어왔다.

### 원인 후보

| 후보 | 상태 | 가르는 방법 |
|---|---|---|
| 화면 / 포그라운드 전환에 따른 스캔 강등 | 미검증 | events: `screen_on`, `activity`, `importance` |
| 절전 모드 / Doze | 미검증 | events: `power_save`, `doze` |
| 발열 제한 | 미검증 | events: `thermal`, `headroom`, `batt_temp_c` |
| 충전 상태 변화 | 미검증 | events: `plugged` |
| 주변 기기 밀도 변화 (환경) | 정황상 약함 (90% 동시 소멸) | 고정 주소 ESP32 행의 배율 |
| 이벤트 로그가 못 잡는 원인 (제조사 스캔 정책 등) | 미검증 | 위가 전부 그대로인데 꺾이면 이쪽 |
| **스캔 타임아웃 → opportunistic 전환** | **유력 (10/3)** | 9/22 에 "30분 규칙이라 5분을 설명 못 한다"며 제외했으나 틀렸다 — 30분은 옛 값이고 지금 AOSP 기본 10분, 기기 설정으로 바뀐다. 10/3 측정이 정확히 300 s 계단형. logcat·`device_config` 로 확정 |

---

## 9/27 반영

| 내용 | 이유 |
|---|---|
| `events_<stamp>.csv` 상태 이벤트 로그 (README 4.1) | 급락 순간의 폰 상태를 남긴다. 모든 행에 상태 스냅샷, 10초 `tick` 에 누적 rows |
| Collector 를 ViewModel 로 이동 | 다크모드·글꼴 크기 변경으로 Activity 가 다시 만들어지면 `onDestroy` → `stop()` 으로 측정 중 CSV 가 닫히던 버그. T5 10분 측정 중 일어날 수 있다 |
| `ts_nanos` 컬럼 (두 데이터 CSV) | `ScanResult.getTimestampNanos()` — 공식 문서는 "부팅 후, 스캔 레코드가 관측된 시각"까지만 말한다. 컨트롤러 시각인지 호스트 처리 시각인지는 [미검증] (9/29 정정). 패킷 간격·M4 분석용 |
| `address` 컬럼 (BEACON) | T4c 에서 nRF Sniffer 캡처와 대조 |
| `raw_check.py` 판정 문구 | "A3 통과"라고 찍던 것을 "콜백 생존만 확인"으로. README 의 기준과 맞춤 |

## 9/29 반영 — APP_DESIGN v1 1주차 앱 작업

| 내용 | 비고 |
|---|---|
| 단일 파일을 패키지로 분리 (collect·parse·model·source·estimate·ui) | 동작 변경 없이 옮기기만 한 커밋을 따로 뒀다 |
| TAG 모드: `setLegacy(false)` + `setPhy(PHY_LE_1M)`, FD5A/FD59 서비스 데이터 **원본** 기록 | `tag_<stamp>.csv`. 해석(TagParser)은 B1 확인 뒤 |
| BEACON 스캔 설정을 TAG 와 같게 | M4 결과를 TAG 측정에 옮기려면 같아야 한다. RAW 는 9/22 비교를 위해 legacy 그대로 |
| 조건 칩 → 조건 코드 + `meta_<stamp>.json` (정답 직선거리 자동 계산) | 폰·태그 높이가 비면 시작하지 않는다 |
| 런 3종(무가림 120 s · 사람 가림 30-60-30 · 정적 120 s), 10 s 카운트다운, 알림음, 자동 종료 | 가림 시각 = 알림음 시각 (`block_in_planned`/`block_out_planned`) |
| 이벤트 추가: `scan_start`(pre/post ns) · `timer` · `block_*_planned` · `run_end` · `run_flag` | `scan_restart` 는 런 도중 재시작 경로가 없어 아직 안 나온다 |
| 점검 표시: pkt/s · 마지막 RSSI · legacy/확장 · 마지막 수신 후 경과(5 s 넘으면 빨강) | |
| 추정기(F1·경로 A·F2·F3)·재생 소스는 인터페이스만 | GO(10/16) 전에는 구현하지 않는다 |
| `ts_nanos` 설명 정정 | "컨트롤러 관측 시각, 전달 지연 제외" → [미검증] |

## 9/29 반영 — 다음 단계 준비 (커밋 2e2f7d9)

| 내용 | 비고 |
|---|---|
| RAW CSV 에 `svc_data_uuids`·`svc_uuids`·`mfg_ids` (맨 끝) | B1 을 태그 도착 당일 1분 만에 확인하려고. 9/22 비교에 쓰는 스캔 설정은 그대로 |
| `scripts/events_view.py` | A3 재측정 3단계 — 낙폭과 상태 변화를 시간순으로 |
| `scripts/b1_find.py` | B1 — 가장 센 기기의 식별자가 TAG 필터와 같은가 |
| `scripts/tag_check.py` | TAG 런 점검 (APP_DESIGN 6절 2주차) |
| `scripts/m4_channel.py` | M4 (2주차). 합성 대조군 기준 탐색 적중률의 우연 수준 ≈ 0.40 |
| UI 정리 | 조건·점검·수신 현황을 카드로 묶음, 시작 버튼 크게, 비콘 전용 설명은 BEACON 에서만 |

네 스크립트 모두 정답을 심은 합성 데이터로 확인했다 (README 10장). `events_view` 는 처음에
마지막 0.5 초 구간(마지막 tick → session_stop)을 "0배 급락"으로 잘못 짚었고, 5 초 미만 구간을
낙폭 후보에서 빼서 고쳤다.

## 10/3 반영 — SmartTag2 측정 도구 마무리 (APP_DESIGN 6.2a)

| 내용 | 비고 |
|---|---|
| RAW '확장 광고 포함' 옵션 (`ScanConfig.forMode(m, rawExtended)`) | 켜면 TAG 와 같은 설정. 기본은 지금처럼 레거시만 — RAW 기본 스캔 설정·`session_start` 문자열은 그대로 |
| RAW CSV 끝에 `is_legacy,primary_phy,secondary_phy,adv_sid,adv_hex` | `adv_hex` = `ScanRecord.getBytes()`. 태그 도착 날 B1(다른 UUID)과 B9(확장 광고)를 가른다 |
| 하루치 내보내기 — zip + `files.txt`(파일명·크기·sha256) → FileProvider → 공유 시트 | 앱을 지우면 CSV 도 지워진다. sha256 = "그날 그 파일" 증거 |
| 무효 표시 사유 칩 6개, 무효는 칩 필수, 한 번 표시하면 고정, 표시 전 pkt/s·RSSI 가림 | 결과를 보고 런을 빼지 않았다는 것을 보이려고 (README 9.7) |
| 시작 전 점검 카드 — 자동 6항목 + 사람 4항목, meta `preflight` | 사람 가림 런은 알람 음량 0 · 완전 무음 · 알림음 시험 미확인이면 시작 차단 (README 9.8) |
| `b1_find` legacy 비율·AD 구조 해석·레거시만 RAW 경고 / `tag_check` 표시·사유·점검 / `m4_channel` 스캔 설정이 TAG 와 다르면 경고 / `raw_check` 확장 RAW 경고 | 합성 데이터로 정답 확인 |
| 작은 수정: 9/30 → 9/29, "30초 5회" 출처를 AOSP 소스로, `ts_nanos` 표현 정리, 이 파일 첫 줄 | |

APP_DESIGN 6.2a 원문은 저장소에 없어서, 사유 칩 6개의 이름·코드와 점검 항목 표시는 요청 문장을
바탕으로 정했다 → 10/3 밤에 v1.2 원문으로 맞췄다 (아래).

## 10/3 저녁 — 프로젝트 STATUS 검토 반영

- 스캔 타임아웃 서술 정정: 기본 10분·`scan_timeout_millis` [문헌], 필터 스캔도 같은 시각에 강등 → 필터 스캔 옵션은 만들지 않는다.
  원인 후보 표의 "장기 스캔 강등 — 제외"는 틀렸다(유력으로 바꿈)
- 스크립트 3개 수정 (APP_DESIGN 6.2 의 3~5 원문은 이 저장소에 없어서, 프로젝트 STATUS 에 적힌 실패 요약을 기준으로 했다)
  - `b1_find`: 주소별 순위 + `--control`(태그를 치운 캡처) — 치우면 사라지는 센 주소를 태그로
  - `tag_check`: 우리 태그 주소 사슬만으로 계산, 비슷한 다른 사슬이 있으면 '모호' 경고·`--addr`
  - `m4_channel`: 예측 구간 적중률(주 지표), 고정 위상 시간 변화, 시뮬레이션 우연 수준, ALL 대조군, 290 s 컷
  - `scripts/tests/test_scripts.py` 반례 14건 PASS. 프로젝트의 `adversarial_cases.py` 를 `scripts/tests/` 에 넣으면 그것도 돌려 맞춘다
  - 고치다 생긴 버그(`tag_check` 의 `stats()` 를 지움)를 이 시험이 잡았다
- `ts_nanos` = AOSP 코드상 호스트 시각 [문헌] — 주석·README·`tag_check` [5] 정정
- `scripts/__pycache__` 를 저장소에서 지우고 `.gitignore` 에 추가

## 10/3 밤 — APP_DESIGN v1.2 원문에 맞춤

v1.2 의 6.2(3~5)·6.2a(3·4) 와 코드가 다른 곳을 고쳤다. 실기기 빌드 전 (컴파일 검사만).

| 원문 | 전 (`b337a6e`) | 지금 |
|---|---|---|
| 사유 코드 `move_timing / intruder / touch / beep / app_error / other(메모 필수)` | `touched / setup / no_beep / device` 포함 6개, 여러 개 선택 | 원문 6개, **하나만** 고름 + 메모(기타는 필수). `run_flag` detail = `reason=<code> memo=<…>`, meta `result.flag_reason`·`flag_memo` |
| `collect/PreCheck.kt`, meta `precheck` | `Preflight.kt`, meta `preflight` | 이름 맞춤 (분석 스크립트는 둘 다 읽는다) |
| 사람 확인 칩은 배치마다 초기화 | 앱을 다시 켤 때만 초기화 | 배치 번호·날이 바뀌면 초기화 (알림음 시험 포함) |
| `tag_state.battery_mode` = 사람 확인값 | `"normal"` 고정 | 확인했으면 `normal`, 아니면 `unconfirmed` |
| `b1_find` 행 10개 이상·위 3개·사실 세 줄 결론 | 행 5개·위 10개·"필터를 고칠 근거" 문구 | 원문대로. 치운 태그가 약하게(−15 dB 밖) 남아도 '사라짐' |
| `tag_check` [0] 주소별 표, 후보 = 중앙값이 가장 센 주소, `countdown_end` 로 타이머 | 행 수가 가장 많은 주소 사슬, `value="10"` | 원문대로 + 주소 교체 잇기, 같은 시각 비슷한 세기면 '모호' |
| `m4` 경과 시간별 표는 스캔 시작 기준 위상, D 는 HCI → 다른 런 탐색, 같은 런 탐색이면 섞은 간격 200회 95% | 첫 30 s 에서 맞춘 위상, 균일 무작위 20회 | 원문대로 (`--fit-on`, `--null 200`) |

반례 시험 20건 PASS. 같은 시험을 `b337a6e` 스크립트에 돌리면 8건이 FAIL — 시험이 차이를 가른다.
프로젝트의 `adversarial_cases.py` 는 아직 저장소에 없다.

## 10/3 밤 실기기 확인 (`38468ae` 빌드)

- 설치 확인: 내보낸 zip 의 meta 에 `precheck`, `result.flag_memo` 가 들어 있다
- **문제: '기타' + 메모를 써도 무효가 안 눌렸다.** 그 런(`raw_20261003_220439`, tag `test1003`)은 결국
  유효 + 메모 "앱 확인용" 으로 표시됐다 — 유효는 사유가 풀려 있을 때만 눌리므로, 표시 순간 '기타'가 풀려 있었다.
  코드 논리(사유 + 메모 → 무효 활성)는 맞아서, 화면 쪽 원인 둘을 고쳤다 [추론 — 폰에서 재확인 필요]
  - targetSdk 36 은 edge-to-edge 가 강제라 키보드가 메모 칸 아래 버튼을 덮는다 → `safeDrawingPadding` + `adjustResize`
  - 고른 사유·메모가 화면 재생성에 풀릴 수 있다 → `rememberSaveable`
  - 버튼 위에 "지금 무엇이 눌리는지" 한 줄 (칩이 풀린 줄 모르는 일을 막는다), 키보드 [완료] 로 닫기
- 이 테스트 런의 표시는 바꾸지 않는다 (한 번 표시하면 고정이 규칙). tag 가 `test1003` 이라 분석에서 빠진다
- `86dd138` 빌드: '기타' + 메모 → 무효 표시 동작 확인 (`raw_20261003_221504`, `flag_reason=other`).
  처음 '안 눌림'은 사유 칩을 안 고른 상태였다 — 버튼 위 안내 줄로 드러남
- **TAG 사람 가림 런 1회 (태그 없이, `tag_20261003_222959`)**: 조건 코드 `d1_p1-mid_day1_pl01`, 직선거리 1.0 m,
  `scan=ext+legacy/phy=LE_1M`, `battery_mode=normal`, `precheck` 전부 확인값. 이벤트 시각이 계획과 ms 단위로 맞음 —
  `countdown_end` 10.017 s, `block_in_planned` 40.019 s, `block_out_planned` 100.019 s, `timer 120` 130.017 s → `run_end auto`.
  태그 행 0 (태그 없음 — 주변에 FD5A/FD59 광고 기기도 없었다). zip `files.txt` sha256 15개 일치
- 앞선 RAW 런(`raw_20261003_222357`)은 TAG 대신 RAW 로 시작해 알림음이 없었다 (RAW 는 런 진행이 없다)
- 조작자 확인: 알림음 4번 모두 들림, 배치 번호를 바꾸면 사람 확인 칩·알림음 확인이 풀림

## 열린 문제

- ~~10/3 밤 변경분 실기기 빌드~~ — 10/3 밤 확인 끝 (사유 칩·메모, 배치별 점검 초기화, `precheck`·`battery_mode`, 사람 가림 런 알림음 4번·자동 종료)
- **TAG 서비스 UUID(FD5A/FD59)** — B1 확인 전 가정. 다르면 `parse/TagFilter.kt` 한 줄
- **'마지막 수신' 빨강 기준 5 s** — 태그 광고 주기를 모르고 정한 값. B1 뒤 조정
- **전환 전후 채점 제외 구간(초)** — Phase 1 전에 고정 (분석 쪽 규칙, 앱은 알림음 시각만 남긴다)
- ~~`raw_breakdown.py` 마지막 부분 분~~ — **9/27 수정.** 기록이 새 1분에 몇 초만 걸쳐
  끝나면 자동 분할이 그 경계를 급락으로 골랐고, 부분 분을 1분으로 세서 분할 후 수신량을
  낮게·배율을 높게 잡았다. 이제 분당 수신량을 실제 기록 길이로 나누고, 30초 미만 분은
  자동 분할 후보에서 뺀다. 합성 데이터(5분 지점 30배 급락, 10.008분에 종료) 기준:
  자동 분할 10분 → 5분, 고정 MAC 배율 38.8배 → 32.3배 (파일에서 직접 센 값 32.35배)

---

## 다음 순서

1. ~~RAW 10분 재측정~~ — **10/3 완료** (위 2차 측정. 10/3 빌드, RAW 기본 설정 `scan=LOW_LATENCY/legacy/no_filter` 확인)
2. **5분 규칙 확인 (태그 없이 지금 가능)**
   - `adb logcat -v time | grep -iE "ScanManager|opportunistic|too frequently|scan timeout"` 를 켠 채
     RAW 6분 → 300 s 근처에 전환 로그가 찍히나
   - 5분 시계가 `startScan` 마다 새로 시작하나: RAW 4분 정지 → 바로 RAW 4분 (두 번째 런이 4분 내내 유지되면 런마다 리셋)
   - ~~필터 스캔이면 피하나~~ — 아니다. 같은 AOSP 코드에서 필터 스캔은 같은 시각에 강등된 스캔 모드로 바뀐다 [문헌].
     필터 스캔 옵션은 만들지 않는다. 대책은 스캔 한 번을 4분 이하로 두는 것
3. 태그 도착 → 폰에 붙이고 **RAW 확장 포함** 1분 → `b1_find.py` (B1·B9). 이어서 TAG 모드 무가림 120 s 1회 →
   `tag_check.py` 로 `광고` legacy/확장(B9)·수신 간격·주소 교체 확인
4. 알림음 들리는지, 사람 가림 30-60-30 런 1회 → `tag_check.py` 구간별 RSSI
5. ESP32 도착 → 고정 주소 기준 `scripts/raw_breakdown.py <csv> <분> <MAC>` 로 A3 확정,
   BEACON FIXED 37/38/39 런 → `m4_channel.py` (M4)
6. T1~T3

## 태그 도착 뒤 (B1 결과를 보고) — 아직 안 함

- `parse/TagFilter.kt` 의 UUID 목록 확정
- 우리 태그 식별: 후보 주소와 주변 FD5A 기기 수 표시
- "새 배치" 버튼
- TAG CSV 끝에 `adv_hex`

## 하지 않기로 한 것

- **포그라운드 서비스·백그라운드 수집** — 이벤트 로그로 "화면을 켜 둬도 떨어진다"가
  확인되기 전까지 보류
- **그래프·측위·필터 UI** — Phase 0 범위 밖. ② 라이브 그래프는 Phase 1 시작 전,
  ③ 찾기·④ 재생은 Phase 2 (APP_DESIGN 5·6절)
- **가림 감지·보정 알고리즘** — GO(10/16) 전에는 코드에 넣지 않는다. 인터페이스만 있다
