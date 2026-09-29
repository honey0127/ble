#!/usr/bin/env python3
"""
이벤트 로그 보기 — 처리량이 언제 꺾였고, 그때 폰 상태가 무엇이었나. (A3 재측정 3단계)

    python3 events_view.py events_20260930_101500.csv

tick 행(10초마다)의 누적 rows 차이로 10초 처리량을 계산하고, 상태 열이 바뀐 곳과
tick 이 아닌 이벤트(화면·Activity·절전·충전·발열·BT·런 알림음 …)를 시간순으로 끼워 보여 준다.

판정은 하지 않는다. 표시는 다음 셋뿐이다.
  ◆  직전 tick 과 비교해 상태 열이 바뀐 곳 (바뀐 열 이름을 적는다)
  ▼  구간 처리량이 직전 구간보다 가장 크게 떨어진 곳 (1곳, 5초 미만 구간 제외)
  ⏸  tick 간격이 15초를 넘은 곳 — 그동안 앱 자체가 멈춰 있었다는 뜻
"""
import sys
from _common import read_csv, num

STATE_COLS = ["screen_on", "activity", "importance", "power_save", "doze", "plugged",
              "thermal", "bt"]
GAP_S = 15.0
MIN_SEG_S = 5.0


def main(path):
    ev = read_csv(path)
    if not ev:
        print("행이 없습니다.")
        return
    start = next((e for e in ev if e["event"] == "session_start"), None)
    if start:
        print(f"\n파일   {path}\n모드   {start['value']}   tag {start.get('tag', '')}")
        print(f"시작   {start['detail']}")

    ticks = [e for e in ev if e["event"] in ("session_start", "tick", "session_stop")]
    rates = []   # (t_s, pkt_per_s)
    for a, b in zip(ticks, ticks[1:]):
        dt = (num(b["rx_elapsed_ms"], int, 0) - num(a["rx_elapsed_ms"], int, 0)) / 1000.0
        dr = num(b["rows"], int, 0) - num(a["rows"], int, 0)
        rates.append((num(b["rx_elapsed_ms"], int, 0) / 1000.0, dr / dt if dt > 0 else 0.0, dt))

    # 5초보다 짧은 구간(보통 마지막 tick → session_stop)은 표본이 적어 낙폭 후보에서 뺀다
    worst_i, worst = None, 1.0
    for i in range(1, len(rates)):
        if rates[i][2] < MIN_SEG_S or rates[i - 1][2] < MIN_SEG_S:
            continue
        prev, cur = rates[i - 1][1], rates[i][1]
        if prev > 0 and cur / prev < worst:
            worst, worst_i = cur / prev, i

    print(f"\n{'시각':>8} {'10s pkt/s':>10}  {'event':<18}{'value':<14} 상태")
    prev_state, ri = None, 0
    for e in ev:
        t = num(e["rx_elapsed_ms"], int, 0) / 1000.0
        state = {c: e.get(c, "") for c in STATE_COLS}
        changed = [c for c in STATE_COLS if prev_state and state[c] != prev_state[c]]
        rate_s, mark = "", ""
        if e["event"] in ("tick", "session_stop") and ri < len(rates):
            _, r, dt = rates[ri]
            rate_s = f"{r:.1f}"
            if ri == worst_i:
                mark += f" ▼ 직전 구간의 {worst:.2f}배"
            if dt > GAP_S:
                mark += f" ⏸ tick 간격 {dt:.0f}s"
            ri += 1
        if changed:
            mark += " ◆ " + ",".join(f"{c}={state[c]}" for c in changed)
        # tick 은 뭔가 표시할 게 있을 때만, 나머지 이벤트는 전부 출력
        if e["event"] != "tick" or mark or ri <= 1 or ri == len(rates):
            print(f"{t:8.1f} {rate_s:>10}  {e['event']:<18}{e.get('value', '')[:13]:<14}{mark}")
        prev_state = state

    if rates:
        vals = [r for _, r, dt in rates if dt >= MIN_SEG_S]
        print(f"\n10초 처리량  최대 {max(vals):.1f}  최소 {min(vals):.1f} pkt/s  (구간 {len(vals)}개)")
    if worst_i is not None:
        t0 = rates[worst_i][0]
        near = [e for e in ev if e["event"] != "tick"
                and abs(num(e["rx_elapsed_ms"], int, 0) / 1000.0 - t0) <= 20]
        print(f"가장 큰 낙폭  {t0:.0f}s 에서 끝나는 구간 (직전의 {worst:.2f}배)")
        print("  ±20초 안의 다른 이벤트: " + (", ".join(f"{e['event']}={e['value']}" for e in near) or "없음"))
        print("  없으면 → 이 로그가 잡지 못하는 원인(제조사 스캔 정책 등) 쪽이다 (README 5장)")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
