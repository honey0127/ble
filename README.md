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

| 모드 | 파일명 | 컬럼 |
|---|---|---|
| BEACON | `beacon_<yyyyMMdd_HHmmss>.csv` | `rx_wall_ms,rx_elapsed_ms,beacon_id,channel_id,seq,rssi,tx_uptime_ms,tx_power_dbm,tag` |
| RAW | `raw_<yyyyMMdd_HHmmss>.csv` | `rx_wall_ms,rx_elapsed_ms,address,rssi,name,tag` |

- `rx_wall_ms` — 수신 시각, 벽시계 epoch ms. 파일·세션 간 정렬용
- `rx_elapsed_ms` — 측정 시작부터의 경과 ms. **단조시계**(`elapsedRealtime`) 기준이라
  시간 자동보정이 끼어도 뒤로 가지 않는다
- `channel_id` — 비콘이 적어 보낸 라벨. `37|38|39`, 그리고 `0 = ALL_CONTROL`
  (모드 표식이며 RF 채널 아님. 화면에는 `ALL*` 로 표시)
- `tag` — 실험 조건 문자열. **비어 있으면 앱이 시작을 거부한다**

화면 지표 정의:

| 지표 | 정의 | 용도 |
|---|---|---|
| `rows` | 수신 행 수 (같은 seq 중복 포함) | 원자료 |
| `pkt/s` | `rows ÷ 경과초` | **조건 비교는 이 값으로.** 가정 없는 직접 측정값 |
| `seqObs%` | `고유 seq ÷ (max−min+1)` | 연속성. 100% 를 넘을 수 없다 |
| `dup` | `rows ÷ 고유 seq` | 타이밍 진단값 |
| `back` | seq 역행 횟수 | 0 이 아니면 T2 확인 대상 |

---

## 5. 오늘 할 수 있는 검증 (보드 없이)

RAW 모드는 주변 아무 BLE 기기나 잡아서 쌓는다. 앱 자체 검증에 그걸 쓴다.

| 확인 | 방법 | 통과 기준 |
|---|---|---|
| 권한 | 시작 누르기 | 권한 팝업 → 허용 후 `rows` 증가 |
| 수신 | 30초 방치 | `rows` 가 계속 늘어남 |
| **10분 연속** | 충전기 꽂고 방치 | 중간에 끊기지 않음 ← **A3 가정 판명** |
| 쓰로틀링 | 시작/정지를 빠르게 5번 | "쓰로틀링 보호" 문구가 뜸 |
| CSV | 4장 경로 확인 | `raw_*.csv` 생성, 열어서 행 증가 확인 |

10분 테스트 전에 **6장을 먼저 한다.** 안 하면 중간에 죽는다.

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

- **컴파일 검증 안 됨.** 이 저장소를 만든 환경에 Android SDK 가 없어 `assembleDebug` 를
  돌리지 못했다. 코드 검토로만 확인했다. 빌드 에러가 나면 메시지 그대로 알려주면 된다.
  Compose BOM 버전이 다르면 `HorizontalDivider` / `FilterChip` 쪽이 제일 먼저 깨진다
- 화면을 켠 채 측정한다. 포그라운드 서비스가 없어 장시간 백그라운드 수집은 못 한다
- `channel_id` 는 라벨이다. 실제 RF 채널 확정은 T4c (nRF Sniffer) 로만 한다
- RAW 모드는 `seq` 가 없으므로 `seqObs% / dup / back` 이 의미 없다 (`-` 로 표시)
