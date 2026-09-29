#!/usr/bin/env python3
"""
TAG 런 점검 — 런 1개(tag_<stamp>.csv)가 쓸 만하게 모였는지 숫자로 본다.

    python3 tag_check.py tag_20261005_141200.csv [--margin 3]

같은 폴더의 events_<stamp>.csv, meta_<stamp>.json 을 자동으로 찾는다.
판정은 하지 않는다. T1~T3 의 통과 기준은 PLAN 이 정하고, 이 스크립트는 숫자만 낸다.

  1. 수집량       행 수, pkt/s, 5초 넘는 공백
  2. 광고 형식    legacy/확장 비율, PHY, adv_sid (B9), 서비스 UUID·페이로드 종류 (B1)
  3. 주소 교체    주소가 몇 번, 언제 바뀌었나 (T2)
  4. 수신 간격    ts_nanos 차이의 분포 — 채널 역산·수신 간격 분석의 바탕
  5. 전달 지연    rx_elapsed_ms(콜백) − ts_nanos(관측) 의 분포. ts_nanos 가 무슨 시각인지
                  [미검증] 이라 이 차이 자체가 확인 대상이다
  6. 구간별 RSSI  알림음 시각으로 나눈 구간(카운트다운 제외, 전환 전후 --margin 초 제외)
"""
import sys
from collections import Counter
from _common import read_csv, read_meta, num, pct, sibling

GAP_S = 5.0


def stats(v):
    if not v:
        return "표본 없음"
    m = sum(v) / len(v)
    sd = (sum((x - m) ** 2 for x in v) / len(v)) ** 0.5
    return f"n={len(v):<5} 평균 {m:6.1f}  중앙 {pct(v, 50):5}  sd {sd:4.1f}  범위 {min(v)}~{max(v)}"


def main(path, margin):
    rows = read_csv(path)
    ev_path, meta_path = sibling(path, "events"), sibling(path, "meta", "json")
    ev = read_csv(ev_path) if ev_path else []
    meta = read_meta(meta_path)
    print(f"\n파일   {path}\nevents {ev_path or '없음'}\nmeta   {meta_path or '없음'}")
    if meta:
        tr = meta.get("truth") or {}
        print(f"조건   {meta.get('cond')}   정답 직선거리 {tr.get('direct_m')} m   "
              f"런 {(meta.get('run') or {}).get('type')}   끝 {(meta.get('result') or {}).get('end')}   "
              f"표시 {(meta.get('result') or {}).get('flag')}")
    if not rows:
        print("\n태그 행이 없습니다. B1(서비스 UUID)·B9(확장 광고)를 먼저 확인하세요 (b1_find.py).")
        return

    el = [num(r["rx_elapsed_ms"], int) for r in rows]
    ts = [num(r["ts_nanos"], int) for r in rows]
    dur = (max(el) - min(el)) / 1000.0 if len(el) > 1 else 0.0

    print("\n[1] 수집량")
    print(f"  행 {len(rows)}   구간 {dur:.1f}s   {len(rows) / dur if dur else 0:.2f} pkt/s")
    ts_sorted = sorted(t for t in ts if t is not None)
    gaps = [(b - a) / 1e9 for a, b in zip(ts_sorted, ts_sorted[1:])]
    big = [g for g in gaps if g > GAP_S]
    print(f"  {GAP_S:.0f}초 넘는 공백 {len(big)}번" + (f" (최대 {max(big):.1f}s)" if big else ""))

    print("\n[2] 광고 형식 (B1·B9)")
    print(f"  legacy {Counter(r['is_legacy'] for r in rows)}   primary_phy {Counter(r['primary_phy'] for r in rows)}"
          f"   secondary_phy {Counter(r['secondary_phy'] for r in rows)}")
    print(f"  adv_sid {Counter(r['adv_sid'] for r in rows)}   svc_uuid {Counter(r['svc_uuid'] for r in rows)}")
    payloads = Counter(r["svc_data_hex"] for r in rows)
    print(f"  페이로드 종류 {len(payloads)}개 — 가장 많은 것: {payloads.most_common(1)[0][0][:40]}…")

    print("\n[3] 주소 교체 (T2)")
    segs, cur = [], None
    for r, e in zip(rows, el):
        if r["address"] != cur:
            segs.append([r["address"], e, e]); cur = r["address"]
        else:
            segs[-1][2] = e
    print(f"  고유 주소 {len({r['address'] for r in rows})}개, 바뀐 횟수 {len(segs) - 1}")
    for a, s, e in segs[:10]:
        print(f"   {a}  {s / 1000:7.1f}s ~ {e / 1000:7.1f}s")

    print("\n[4] 수신 간격 (ts_nanos 차이, 초)")
    if gaps:
        print("  " + "  ".join(f"p{q}={pct(gaps, q):.3f}" for q in (10, 50, 90, 99)) + f"  최대={max(gaps):.3f}")

    print("\n[5] 전달 지연 = 콜백 시각 − 관측 시각 (ms)")
    pair = next((e for e in ev if e.get("ts_nanos") and e.get("rx_elapsed_ms")), None)
    if pair:
        off = num(pair["ts_nanos"], int) / 1e6 - num(pair["rx_elapsed_ms"], int)   # 같은 순간의 두 시계 차
        lat = [e - (t / 1e6 - off) for e, t in zip(el, ts) if e is not None and t is not None]
        print("  " + "  ".join(f"p{q}={pct(lat, q):.1f}" for q in (10, 50, 90, 99)))
        print("  (음수가 많으면 ts_nanos 가 콜백보다 늦은 시각 — 관측 시각이라는 해석이 틀렸다는 신호)")
    else:
        print("  events 파일이 없어 두 시계를 맞출 수 없다")

    print(f"\n[6] 구간별 RSSI (카운트다운 제외, 전환 전후 {margin:g}초 제외)")
    timers = {e["value"]: num(e["rx_elapsed_ms"], int) / 1000.0 for e in ev if e.get("event") == "timer"}
    t0 = timers.get("10")
    if t0 is None:
        print("  timer 이벤트가 없다 (TAG 런이 아니거나 events 가 없다)")
    else:
        cuts = [0.0] + [float(k) for k in ("30", "90") if k in timers] + [120.0]
        names = ["무가림(앞)", "가림", "무가림(뒤)"] if len(cuts) == 4 else ["전체"]
        for name, a, b in zip(names, cuts, cuts[1:]):
            lo, hi = t0 + a + margin, t0 + b - margin
            v = [num(r["rssi"], int) for r, e in zip(rows, el) if lo <= e / 1000.0 < hi]
            print(f"  {name:<9} {a:5.0f}~{b:<5.0f}s  {stats(v)}   {len(v) / (hi - lo) if hi > lo else 0:.2f} pkt/s")
    flags = [e for e in ev if e.get("event") == "run_flag"]
    if flags:
        f = flags[-1]
        print(f"\n런 표시: {f['value']}  {f.get('detail', '')}")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    m = float(sys.argv[sys.argv.index("--margin") + 1]) if "--margin" in sys.argv else 3.0
    main(sys.argv[1], m)
