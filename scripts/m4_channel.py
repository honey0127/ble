#!/usr/bin/env python3
"""
M4 채널 역산 점검 — 받은 시각(ts_nanos)이 스캐너의 채널별 시간대에 몰리는가.

    python3 m4_channel.py beacon_20261006_101500.csv [--dwell 4.096] [--bin 30]

생각 [추론]: 스캐너가 37→38→39 를 D 초씩 돌며 듣는다면, 한 채널에서만 광고하는
비콘(FIXED 37/38/39 런)의 패킷은 3D 주기 중 D 길이 한 구간에만 들어온다.
  - 적중률 = 받은 시각을 3D 로 접었을 때 가장 붐비는 D 길이 구간에 든 비율.
    우연이면 약 1/3, 완전히 몰리면 1.0
  - D 를 모르면 0.2~10 s 에서 적중률이 가장 높은 값을 찾는다 (--dwell 로 고정 가능).
    탐색 자체가 우연한 몰림을 골라내므로, 탐색한 D 에서는 우연만으로도 약 0.40 이 나온다
    (시간대와 무관한 합성 대조군 540초: 0.40). 0.4 근처는 "몰림 없음"으로 읽는다
  - 적중률을 스캔 시작 후 경과 시간 구간(--bin 초)별로 다시 계산해, 시간이 갈수록
    위상이 밀리는지(재시작 뒤 얼마나 믿을 수 있는지) 본다
기준 시각은 events 의 scan_start post_ns (없으면 첫 패킷). channel_id 는 비콘이 적어 보낸
라벨이라 실제 RF 채널 확정은 T4c(nRF Sniffer)로만 한다. 0(ALL_CONTROL) 행은 뺀다.
"""
import sys
from collections import defaultdict
from _common import read_csv, num, sibling, scan_start_ns

NBIN = 60   # 3D 주기를 60칸으로 접는다 → 한 채널 구간 = 20칸


def hit_rate(ts, d):
    """(적중률, 가장 붐비는 구간의 시작 위상 0~1)"""
    if not ts:
        return 0.0, 0.0
    hist = [0] * NBIN
    period = 3 * d
    for t in ts:
        hist[int((t % period) / period * NBIN) % NBIN] += 1
    w = NBIN // 3
    best, start = -1, 0
    s = sum(hist[:w])
    for i in range(NBIN):
        if s > best:
            best, start = s, i
        s += hist[(i + w) % NBIN] - hist[i]
    return best / len(ts), start / NBIN


def main(path, dwell, bin_s):
    rows = read_csv(path)
    ev_path = sibling(path, "events")
    ev = read_csv(ev_path) if ev_path else []
    base = scan_start_ns(ev)
    by_ch = defaultdict(list)
    for r in rows:
        ch, t = num(r.get("channel_id"), int), num(r.get("ts_nanos"), int)
        if ch in (37, 38, 39) and t is not None:
            by_ch[ch].append(t)
    if not by_ch:
        print("channel_id 37/38/39 행이 없다 (FIXED 모드 런이 아니거나 ts_nanos 가 없는 CSV).")
        return
    if base is None:
        base = min(min(v) for v in by_ch.values())
        print("scan_start 이벤트가 없어 첫 패킷을 기준 시각으로 쓴다.")
    rel = {ch: [(t - base) / 1e9 for t in v] for ch, v in by_ch.items()}

    print(f"\n파일 {path}   events {ev_path or '없음'}")
    for ch, v in sorted(rel.items()):
        print(f"  ch{ch}: {len(v)}행, 스캔 시작 후 {min(v):.1f}~{max(v):.1f}s")

    if dwell is None:
        allv = [t for v in rel.values() for t in v]
        # 채널마다 따로 접어 평균한다 (채널끼리는 위상이 달라야 정상)
        def score(d):
            return sum(hit_rate(v, d)[0] * len(v) for v in rel.values()) / len(allv)
        # 거칠게(20 ms) 훑은 뒤 상위 후보 주변을 1 ms 로 다시 본다
        coarse = sorted(((score(d), d) for d in (0.2 + 0.02 * i for i in range(491))), reverse=True)[:5]
        scored = []
        for _, c in coarse:
            scored += [(score(d), d) for d in (c - 0.02 + 0.001 * i for i in range(41))]
        best = max(scored)
        dwell = best[1]
        print(f"\n채널 머무는 시간 D 추정: {dwell:.3f} s (적중률 {best[0]:.2f})  [추론 — 탐색값]")
        top = sorted({round(d, 3): (h, d) for h, d in scored}.values(), reverse=True)[:5]
        print("  상위 후보: " + ", ".join(f"{d:.3f}s→{h:.2f}" for h, d in top))
    else:
        print(f"\nD = {dwell:.3f} s (지정)")

    print(f"\n[채널별 적중률]  우연 수준 ≈ 0.33 (D 를 탐색했으면 ≈ 0.40)")
    phases = {}
    for ch, v in sorted(rel.items()):
        h, ph = hit_rate(v, dwell)
        phases[ch] = ph
        print(f"  ch{ch}: 적중률 {h:.2f}   구간 시작 위상 {ph:.2f} (주기 3D 기준, 0~1)")
    if len(phases) > 1:
        print("  채널끼리 위상이 약 1/3 씩 어긋나 있으면 '채널을 차례로 돈다'는 가정과 맞는다")

    print(f"\n[경과 시간별 적중률]  {bin_s:g}초 구간, 위상은 구간마다 새로 맞춘다")
    print(f"  {'구간(s)':<12}" + "".join(f"ch{ch:<8}" for ch in sorted(rel)))
    tmax = max(max(v) for v in rel.values())
    b = 0.0
    while b <= tmax:
        cells = []
        for ch in sorted(rel):
            seg = [t for t in rel[ch] if b <= t < b + bin_s]
            cells.append(f"{hit_rate(seg, dwell)[0]:.2f}({len(seg)})" if len(seg) >= 5 else "-")
        print(f"  {b:5.0f}~{b + bin_s:<5.0f} " + "".join(f"{c:<10}" for c in cells))
        b += bin_s
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    a = sys.argv
    d = float(a[a.index("--dwell") + 1]) if "--dwell" in a else None
    bs = float(a[a.index("--bin") + 1]) if "--bin" in a else 30.0
    main(a[1], d, bs)
