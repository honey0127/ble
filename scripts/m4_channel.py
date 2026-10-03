#!/usr/bin/env python3
"""
M4 채널 역산 점검 — 받은 시각(ts_nanos)이 스캐너의 채널별 시간대에 들어가는가.

    python3 m4_channel.py beacon_<stamp>.csv [--dwell D] [--offset S] [--bin 30] [--max-s 290] [--null 20]

가정 [추론·B4]: 스캐너는 스캔 창마다 37→38→39 를 D 초씩 차례로 듣고, 스캔 시작은 37 이다.
그러면 한 채널에서만 광고하는 비콘(FIXED 37/38/39 런)의 패킷은 3D 주기 중 그 채널의 D 구간에만 온다.

지표 (주 지표가 맨 위)
  1. 예측 구간 적중률 — scan_start(post_ns) + offset 을 37 구간의 시작으로 놓고, 채널 c 의 패킷이
     (c−37)번째 D 구간에 든 비율. 위상을 데이터에 맞추지 않으므로 우연 수준이 정확히 1/3 이다.
     D 는 HCI 스눕 로그로 잰 값을 --dwell 로 준다 (탐색값을 쓰면 주 지표가 아니다)
  2. 고정 위상 적중률의 시간 변화 — 첫 구간(--bin)에서 맞춘 위상을 그대로 두고 경과 시간별로 잰다.
     위상이 밀리면(D 오차·드리프트) 시간이 갈수록 떨어진다. 구간마다 위상을 다시 맞추면 이게 가려진다
  3. 맞춘 위상 적중률 + 시뮬레이션 우연 수준 — 위상(과 --dwell 이 없으면 D)을 데이터에 맞추면
     우연만으로도 1/3 보다 높게 나온다. 같은 행 수·같은 길이의 균일 무작위 시각으로 같은 절차를
     --null 번 돌려 평균과 95% 값을 낸다 (길이·행 수에 따라 다르다 — 고정값을 쓰지 않는다)
  4. 대조군 — ALL 런(channel_id 0) 행에 같은 계산을 하면 우연 수준 근처여야 한다

스캔 시작 후 --max-s(기본 290 s) 넘은 행은 뺀다. S25 는 300 s 에 무필터 스캔을 opportunistic 으로
옮겨, 그 뒤 수신은 우리 스캔 창과 무관하다 [실측 10/3 + 문헌 AOSP ScanManager].
channel_id 는 비콘이 적어 보낸 라벨이라 실제 RF 채널 확정은 T4c(nRF Sniffer)로만 한다.
"""
import random
import sys
from collections import defaultdict
from _common import read_csv, read_meta, num, sibling, scan_start_ns, scan_desc

NBIN = 60   # 3D 주기를 60칸으로 접는다 → 한 채널 구간 = 20칸


def slot_frac(t, d, phase):
    """주기 3D 에서 위상 phase(0~1)부터 시작하는 D 구간 안이면 True"""
    x = (t / (3 * d) - phase) % 1.0
    return x < 1.0 / 3


def hit_fixed(ts, d, phase):
    return sum(slot_frac(t, d, phase) for t in ts) / len(ts) if ts else float("nan")


def hit_fit(ts, d):
    """위상을 맞춘 적중률: (가장 붐비는 D 구간 비율, 그 시작 위상)"""
    if not ts:
        return 0.0, 0.0
    hist = [0] * NBIN
    for t in ts:
        hist[int((t / (3 * d)) % 1.0 * NBIN) % NBIN] += 1
    w = NBIN // 3
    s = sum(hist[:w]); best, start = s, 0
    for i in range(1, NBIN):
        s += hist[(i + w - 1) % NBIN] - hist[i - 1]
        if s > best:
            best, start = s, i
    return best / len(ts), start / NBIN


def search_d(chs):
    """D 탐색 (20 ms 로 훑고 상위 주변을 1 ms 로). 채널별로 위상을 따로 맞춘 적중률의 행 가중 평균"""
    n = sum(len(v) for v in chs.values())
    def score(d):
        return sum(hit_fit(v, d)[0] * len(v) for v in chs.values()) / n
    coarse = sorted(((score(d), d) for d in (0.2 + 0.02 * i for i in range(491))), reverse=True)[:5]
    fine = [(score(d), d) for _, c in coarse for d in (c - 0.02 + 0.001 * i for i in range(41))]
    return max(fine)


def null_level(chs, d, search, reps, seed=1):
    """같은 행 수·같은 시간 범위의 균일 무작위 시각으로 같은 절차 → (평균, 95% 값)"""
    rnd = random.Random(seed)
    vals = []
    for _ in range(reps):
        fake = {c: [rnd.uniform(min(v), max(v)) for _ in v] for c, v in chs.items() if v}
        if search:
            vals.append(search_d(fake)[0])
        else:
            n = sum(len(v) for v in fake.values())
            vals.append(sum(hit_fit(v, d)[0] * len(v) for v in fake.values()) / n)
    vals.sort()
    return sum(vals) / len(vals), vals[min(len(vals) - 1, int(0.95 * len(vals)))]


def main(path, dwell, offset, bin_s, max_s, reps):
    rows = read_csv(path)
    ev_path = sibling(path, "events")
    ev = read_csv(ev_path) if ev_path else []
    meta = read_meta(sibling(path, "meta", "json"))
    base = scan_start_ns(ev)

    print(f"\n파일 {path}\nevents {ev_path or '없음'}")
    sc = (meta or {}).get("scan") or {}
    print(f"스캔 설정 {scan_desc(meta) or '(meta 없음)'}")
    if meta and not (sc.get("legacy") is False and sc.get("phy") == "LE_1M"):
        print("  ! TAG 와 스캔 설정이 다르다 (TAG = legacy=False, phy=LE_1M) — 이 런의 M4 결과는 TAG 측정에 옮길 수 없다")
    flag = next((e for e in reversed(ev) if e.get("event") == "run_flag"), None)
    print(f"런 표시 {flag['value'] + ' ' + flag.get('detail', '') if flag else '없음'}")
    if base is None:
        print("! scan_start 이벤트가 없다 — 예측 구간 적중률(주 지표)을 계산할 수 없어 첫 패킷을 기준으로 둔다")

    by = defaultdict(list)
    for r in rows:
        ch, t = num(r.get("channel_id"), int), num(r.get("ts_nanos"), int)
        if ch in (0, 37, 38, 39) and t is not None:
            by[ch].append(t)
    if not any(by.get(c) for c in (37, 38, 39)):
        print("channel_id 37/38/39 행이 없다 (FIXED 모드 런이 아니거나 ts_nanos 가 없는 CSV).")
        return
    if base is None:
        base = min(min(v) for v in by.values() if v)
    rel_all = {c: sorted((t - base) / 1e9 for t in v) for c, v in by.items()}
    cut = {c: sum(1 for t in v if t > max_s) for c, v in rel_all.items()}
    rel = {c: [t for t in v if 0 <= t <= max_s] for c, v in rel_all.items()}
    if any(cut.values()):
        print(f"스캔 시작 후 {max_s:g}s 넘은 행 뺌: " + ", ".join(f"ch{c} {n}" for c, n in sorted(cut.items()) if n))
    chs = {c: v for c, v in rel.items() if c in (37, 38, 39) and v}
    for c, v in sorted(rel.items()):
        print(f"  ch{c if c else 'ALL'}: {len(v)}행, {min(v):.1f}~{max(v):.1f}s" if v else f"  ch{c}: 0행")

    searched = dwell is None
    if searched:
        h, dwell = search_d(chs)
        print(f"\nD = {dwell:.3f} s — 데이터로 탐색한 값 [추론]. HCI 스눕 로그로 잰 값이 있으면 --dwell 로 줄 것")
    else:
        print(f"\nD = {dwell:.3f} s (지정)")

    print(f"\n[1] 예측 구간 적중률 — 위상 = 스캔 시작 + {offset:g}s, 37→38→39 (우연 = 0.333, 맞춤 없음)")
    for c, v in sorted(chs.items()):
        print(f"  ch{c}: {hit_fixed(v, dwell, offset / (3 * dwell) + (c - 37) / 3):.3f}  ({len(v)}행)")
    if searched:
        print("  (D 를 이 데이터로 탐색했으므로 이 값도 낙관적이다 — 주 지표로는 HCI 로그의 D 를 쓴다)")

    print(f"\n[2] 고정 위상 적중률의 시간 변화 — 첫 {bin_s:g}s 에서 맞춘 위상을 고정 (구간별 다시 맞추지 않음)")
    first = {c: [t for t in v if t < bin_s] for c, v in chs.items()}
    phase0 = {c: hit_fit(first[c], dwell)[1] if len(first[c]) >= 5 else hit_fit(v, dwell)[1] for c, v in chs.items()}
    print(f"  {'구간(s)':<12}" + "".join(f"ch{c:<10}" for c in sorted(chs)))
    b = 0.0
    tmax = max(max(v) for v in chs.values())
    while b <= tmax:
        cells = []
        for c in sorted(chs):
            seg = [t for t in chs[c] if b <= t < b + bin_s]
            cells.append(f"{hit_fixed(seg, dwell, phase0[c]):.2f}({len(seg)})" if len(seg) >= 5 else "-")
        print(f"  {b:5.0f}~{b + bin_s:<5.0f} " + "".join(f"{x:<12}" for x in cells))
        b += bin_s
    print("  우연 = 0.333. 시간이 갈수록 떨어지면 위상이 밀린다 → 재시작 주기를 그 전에 둔다")

    print(f"\n[3] 맞춘 위상 적중률 (전체) + 같은 절차의 우연 수준 ({reps}회 시뮬레이션)")
    n = sum(len(v) for v in chs.values())
    for c, v in sorted(chs.items()):
        h, ph = hit_fit(v, dwell)
        print(f"  ch{c}: {h:.3f}   위상 {ph:.2f}")
    if len(chs) > 1:
        print("  채널끼리 위상이 약 1/3 씩 어긋나 있으면 '차례로 돈다'는 가정과 맞는다")
    total = sum(hit_fit(v, dwell)[0] * len(v) for v in chs.values()) / n
    mean0, p95 = null_level(chs, dwell, searched, reps)
    print(f"  행 가중 평균 {total:.3f}  vs 우연 평균 {mean0:.3f} · 95% {p95:.3f}"
          + ("  (D 탐색까지 포함한 우연)" if searched else ""))

    allv = rel.get(0, [])
    print("\n[4] 대조군 — ALL 런(channel_id 0)")
    if len(allv) >= 5:
        for c in (37, 38, 39):
            print(f"  ch{c} 예측 구간에 든 ALL 행 비율: {hit_fixed(allv, dwell, offset / (3 * dwell) + (c - 37) / 3):.3f}  (≈ 0.333 이어야 한다)")
    else:
        print("  ALL 런 행이 없다 — 같은 날 ALL 런을 같이 돌려 대조군으로 쓴다")
    print()


if __name__ == "__main__":
    a = sys.argv
    if len(a) < 2:
        print(__doc__)
        sys.exit(1)
    g = lambda k, d, f=float: f(a[a.index(k) + 1]) if k in a else d
    main(a[1], g("--dwell", None), g("--offset", 0.0), g("--bin", 30.0), g("--max-s", 290.0), g("--null", 20, int))
