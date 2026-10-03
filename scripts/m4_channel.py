#!/usr/bin/env python3
"""
M4 채널 역산 점검 — 받은 시각(ts_nanos)이 스캐너의 채널별 시간대에 들어가는가 (Gentner 2022 검증 방식).

    python3 m4_channel.py beacon_<stamp>.csv [--dwell D | --fit-on 다른런.csv] [--offset S]
                          [--bin 30] [--max-s 290] [--null 200]

가정 [추론·B4]: 스캐너는 스캔 창마다 37→38→39 를 D 초씩 차례로 듣고, 스캔 시작은 37 이다.
그러면 한 채널에서만 광고하는 비콘(FIXED 37/38/39 런)의 패킷은 3D 주기 중 그 채널의 D 구간에만 온다.

D 를 어디서 얻나 (위가 우선)
  --dwell D        HCI 스눕 로그로 잰 값. 우연 수준이 정확히 1/3 이다
  --fit-on X.csv   다른 런(예: ch37 런)에서 탐색한 D 로 이 런을 채점한다. 찾은 데이터와 채점 데이터가
                   달라서 우연 수준은 그대로 1/3 이다
  (둘 다 없음)     이 런에서 탐색한 D 로 이 런을 채점한다 — 낙관적이다. 이때만 우연 수준을 이 데이터로
                   계산한다: 수신 간격의 순서를 섞어 시각열을 다시 만들고 같은 탐색을 --null 번(기본 200)
                   → 95번째 백분위. 짧은 런 하나는 5% 확률로 우연히 '유의'가 나오므로 주 판정은 위 둘로 한다

지표
  1. 예측 구간 적중률 (주 지표) — 스캔 시작(scan_start post_ns) + offset 을 37 구간 시작으로 놓고
     ch37 → [0, D), ch38 → [D, 2D), ch39 → [2D, 3D) (주기 3D) 에 든 비율. 위상을 맞추지 않는다.
     Gentner 의 채널 식별 정확도(99.5~100%)와 비교할 값이다
  2. 경과 시간별 적중률 — 위상을 **스캔 시작 기준으로 고정**하고 --bin 초마다 잰다. 구간마다 위상을 새로
     맞추면 드리프트가 안 보인다. 떨어지기 시작하는 시각이 재시작 주기의 근거다
  3. (이 런에서 D 를 탐색했을 때만) 탐색 점수 vs 섞은 시각열의 같은 탐색 — 평균·95%
  4. 대조군 — ESP32 ALL 런(channel_id 0) 행의 예측 구간 적중률 ≈ 1/3 이어야 정상

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


def shuffled(v, rnd):
    """수신 간격(연속 수신 시각의 차)의 순서를 섞어 시각열을 다시 만든다 — 간격 분포는 그대로, 시간대 정보만 지운다"""
    gaps = [b - a for a, b in zip(v, v[1:])]
    rnd.shuffle(gaps)
    out, t = [v[0]], v[0]
    for g in gaps:
        t += g
        out.append(t)
    return out


def null_level(chs, offset, reps, seed=1):
    """섞은 시각열로 같은 D 탐색 → (탐색 점수 평균·95%, 그 D 의 예측 구간 적중률 평균·95%)"""
    rnd = random.Random(seed)
    sc, pr = [], []
    for k in range(reps):
        fake = {c: shuffled(v, rnd) for c, v in chs.items() if len(v) > 1}
        h, d = search_d(fake)
        sc.append(h)
        pr.append(predicted(fake, d, offset))
        if reps >= 50 and (k + 1) % 50 == 0:
            print(f"  … {k + 1}/{reps}", file=sys.stderr)
    q = lambda v: (sum(v) / len(v), sorted(v)[min(len(v) - 1, int(0.95 * len(v)))])
    return q(sc), q(pr)


def predicted(chs, d, offset):
    """예측 구간 적중률 (행 가중 평균)"""
    n = sum(len(v) for v in chs.values())
    return sum(hit_fixed(v, d, offset / (3 * d) + (c - 37) / 3) * len(v) for c, v in chs.items()) / n if n else float("nan")


def load(path, max_s, quiet=False):
    """(채널 → 스캔 시작 기준 시각 목록, 뺀 행 수, meta, events, scan_start 유무)"""
    rows = read_csv(path)
    ev_path = sibling(path, "events")
    ev = read_csv(ev_path) if ev_path else []
    meta = read_meta(sibling(path, "meta", "json"))
    base = scan_start_ns(ev)
    by = defaultdict(list)
    for r in rows:
        ch, t = num(r.get("channel_id"), int), num(r.get("ts_nanos"), int)
        if ch in (0, 37, 38, 39) and t is not None:
            by[ch].append(t)
    has_base = base is not None
    if not any(by.values()):
        return {}, {}, meta, ev, has_base
    if base is None:
        base = min(min(v) for v in by.values() if v)
    rel_all = {c: sorted((t - base) / 1e9 for t in v) for c, v in by.items()}
    cut = {c: sum(1 for t in v if t > max_s) for c, v in rel_all.items()}
    rel = {c: [t for t in v if 0 <= t <= max_s] for c, v in rel_all.items()}
    return rel, cut, meta, ev, has_base


def main(path, dwell, fit_on, offset, bin_s, max_s, reps):
    rel, cut, meta, ev, has_base = load(path, max_s)
    print(f"\n파일 {path}\nevents {sibling(path, 'events') or '없음'}")
    sc = (meta or {}).get("scan") or {}
    print(f"스캔 설정 {scan_desc(meta) or '(meta 없음)'}")
    if meta and not (sc.get("legacy") is False and sc.get("phy") == "LE_1M"):
        print("  ! TAG 와 스캔 설정이 다르다 (TAG = legacy=False, phy=LE_1M) — 이 런의 M4 결과는 TAG 측정에 옮길 수 없다")
    flag = next((e for e in reversed(ev) if e.get("event") == "run_flag"), None)
    print(f"런 표시 {flag['value'] + ' ' + flag.get('detail', '') if flag else '없음'}")
    if not has_base:
        print("! scan_start 이벤트가 없다 — 예측 구간 적중률(주 지표)을 계산할 수 없어 첫 패킷을 기준으로 둔다")
    if not any(rel.get(c) for c in (37, 38, 39)) and not rel.get(0):
        print("channel_id 0/37/38/39 행이 없다 (BEACON 런이 아니거나 ts_nanos 가 없는 CSV).")
        return
    if any(cut.values()):
        print(f"스캔 시작 후 {max_s:g}s 넘은 행 뺌: " + ", ".join(f"ch{c} {n}" for c, n in sorted(cut.items()) if n))
    chs = {c: v for c, v in rel.items() if c in (37, 38, 39) and v}
    for c, v in sorted(rel.items()):
        print(f"  ch{c if c else 'ALL'}: {len(v)}행, {min(v):.1f}~{max(v):.1f}s" if v else f"  ch{c}: 0행")

    source = "hci" if dwell is not None else ("other" if fit_on else "self")
    if source == "hci":
        print(f"\nD = {dwell:.3f} s (지정 — HCI 로그 값이어야 한다)")
    elif source == "other":
        frel, _, _, _, _ = load(fit_on, max_s)
        fch = {c: v for c, v in frel.items() if c in (37, 38, 39) and v}
        if not fch:
            print(f"\n--fit-on {fit_on}: channel_id 37/38/39 행이 없어 D 를 찾을 수 없다")
            return
        _, dwell = search_d(fch)
        print(f"\nD = {dwell:.3f} s — 다른 런({fit_on}, " + "·".join(f"ch{c}" for c in sorted(fch))
              + ")에서 탐색 [추론]. 이 런은 채점만 한다")
    else:
        if not chs:
            print("\nFIXED 채널 행이 없어 D 를 탐색할 수 없다 — --dwell 또는 --fit-on 을 준다")
            return
        h_self, dwell = search_d(chs)
        print(f"\nD = {dwell:.3f} s — 이 런에서 탐색 [추론]. 같은 데이터로 찾고 채점하므로 [1] 은 낙관적이다.")
        print("  주 판정은 --dwell(HCI 로그) 또는 --fit-on(다른 런에서 탐색)으로 한다")

    chance = "0.333 (D 를 이 데이터와 무관하게 얻음)" if source != "self" else "[3] 의 섞은 시각열 값"
    print(f"\n[1] 예측 구간 적중률 — 위상 = 스캔 시작 + {offset:g}s, 37→38→39, 맞춤 없음. 우연 = {chance}")
    for c, v in sorted(chs.items()):
        print(f"  ch{c}: {hit_fixed(v, dwell, offset / (3 * dwell) + (c - 37) / 3):.3f}  ({len(v)}행)")
    if chs:
        print(f"  행 가중 {predicted(chs, dwell, offset):.3f}")

    print(f"\n[2] 경과 시간별 예측 구간 적중률 — 위상을 스캔 시작 기준으로 고정 ({bin_s:g}s 구간, 구간마다 다시 맞추지 않음)")
    if chs:
        print(f"  {'구간(s)':<12}" + "".join(f"ch{c:<10}" for c in sorted(chs)))
        b = 0.0
        tmax = max(max(v) for v in chs.values())
        while b <= tmax:
            cells = []
            for c in sorted(chs):
                seg = [t for t in chs[c] if b <= t < b + bin_s]
                ph = offset / (3 * dwell) + (c - 37) / 3
                cells.append(f"{hit_fixed(seg, dwell, ph):.2f}({len(seg)})" if len(seg) >= 5 else "-")
            print(f"  {b:5.0f}~{b + bin_s:<5.0f} " + "".join(f"{x:<12}" for x in cells))
            b += bin_s
        print("  우연 = 0.333. 시간이 갈수록 떨어지면 위상이 밀린다(D 오차·드리프트) → 재시작 주기를 그 전에 둔다")

    if source == "self":
        print(f"\n[3] 탐색 점수 vs 우연 — 수신 간격 순서를 섞은 시각열에 같은 탐색 {reps}회")
        (m_sc, p_sc), (m_pr, p_pr) = null_level(chs, offset, reps)
        print(f"  탐색 점수(위상까지 맞춘 적중률) {h_self:.3f}  — 섞은 것: 평균 {m_sc:.3f}, 95% {p_sc:.3f}")
        print(f"  행 가중 평균 {predicted(chs, dwell, offset):.3f}  vs 우연 평균 {m_pr:.3f} · 95% {p_pr:.3f}"
              "   ([1] 예측 구간 적중률 기준)")
        print("  95% 값보다 크면 '우연으로는 드물다'. 짧은 런 하나는 5% 확률로 우연히 넘는다")

    allv = rel.get(0, [])
    print("\n[4] 대조군 — ESP32 ALL 런(channel_id 0)")
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
    main(a[1], g("--dwell", None), g("--fit-on", None, str), g("--offset", 0.0), g("--bin", 30.0),
         g("--max-s", 290.0), g("--null", 200, int))
