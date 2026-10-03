#!/usr/bin/env python3
"""
TAG 런 점검 — 런 1개(tag_<stamp>.csv)가 쓸 만하게 모였는지 숫자로 본다.

    python3 tag_check.py tag_20261005_141200.csv [--margin 3] [--addr AA:..,BB:..]

**우리 태그 행만 쓴다.** TAG 필터(FD5A/FD59)는 주변의 다른 삼성 기기·태그도 통과시킨다.
후보 주소 = 시간 구간이 겹치지 않는 주소들의 조합 중 행 수 합이 가장 큰 사슬
(주소 교체는 이어지고, 같은 시각에 함께 보이는 주소는 다른 기기다). 겹치지 않는 다른 사슬이
비슷하게 크면 '모호'로 경고한다 — 그때는 --addr 로 직접 준다. 나머지 주소는 '다른 기기'로 센다.

같은 폴더의 events_<stamp>.csv, meta_<stamp>.json 을 자동으로 찾는다.
판정은 하지 않는다. T1~T3 의 통과 기준은 PLAN 이 정하고, 이 스크립트는 숫자만 낸다.

  1. 수집량       행 수, pkt/s, 5초 넘는 공백
  2. 광고 형식    legacy/확장 비율, PHY, adv_sid (B9), 서비스 UUID·페이로드 종류 (B1)
  3. 주소 교체    주소가 몇 번, 언제 바뀌었나 (T2)
  4. 수신 간격    ts_nanos 차이의 분포 — 채널 역산·수신 간격 분석의 바탕
  5. 시각 차이    rx_elapsed_ms(콜백 시각) − ts_nanos 의 분포. ts_nanos 는 AOSP 코드상 블루투스
                  서비스가 결과를 만들 때 넣는 elapsedRealtimeNanos() — 호스트 시각이다
                  [문헌: Android 12L 계열 GattService, Android 16 코드는 미확인]. 이 차이는 서비스 →
                  앱 콜백 전달 지연이고, 컨트롤러 수신 시각과의 차이는 여기서 알 수 없다
  7. 표시·점검    run_flag(유효/무효 + 절차 사유 코드), 시작 전 점검, 스캔 설정
  6. 구간별 RSSI  알림음 시각으로 나눈 구간(카운트다운 제외, 전환 전후 --margin 초 제외)
"""
import sys
from collections import Counter
from _common import read_csv, read_meta, num, pct, sibling, scan_desc

GAP_S = 5.0
OVERLAP_OK = 0.05  # 주소 교체 순간의 짧은 겹침 허용 — 각 구간 양끝을 5% 줄여서 비교한다
AMBIGUOUS = 0.8    # 겹치지 않는 다른 사슬의 행 수가 최선의 80% 이상이면 '모호'


def spans(rows, el):
    by = {}
    for r, e in zip(rows, el):
        by.setdefault(r["address"], []).append(e)
    out = {}
    for a, v in by.items():
        s0, s1 = min(v), max(v)
        pad = OVERLAP_OK * (s1 - s0)
        out[a] = (s0 + pad, s1 - pad, len(v))
    return out, by


def best_chain(sp, allowed):
    """시간 구간이 겹치지 않는 주소들의 조합 중 행 수 합이 가장 큰 것 (가중 구간 스케줄링)"""
    items = sorted((sp[a] + (a,) for a in allowed), key=lambda x: x[1])
    best = [(0, [])]
    for i, (s0, s1, n, a) in enumerate(items):
        j = max((k + 1 for k in range(i) if items[k][1] < s0), default=0)
        take = (best[j][0] + n, best[j][1] + [a])
        best.append(max(best[-1], take, key=lambda x: x[0]))
    return best[-1]


def pick_chain(rows, el):
    """우리 태그로 볼 주소 사슬과, 겹치지 않는 다른 사슬(모호 판단용)"""
    sp, by = spans(rows, el)
    w, chain = best_chain(sp, list(sp))
    w2, alt = best_chain(sp, [a for a in sp if a not in chain])
    return chain, by, (alt if w and w2 >= AMBIGUOUS * w else None)


def stats(v):
    if not v:
        return "표본 없음"
    m = sum(v) / len(v)
    sd = (sum((x - m) ** 2 for x in v) / len(v)) ** 0.5
    return f"n={len(v):<5} 평균 {m:6.1f}  중앙 {pct(v, 50):5}  sd {sd:4.1f}  범위 {min(v)}~{max(v)}"


def main(path, margin, addrs=None):
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

    el_all = [num(r["rx_elapsed_ms"], int) for r in rows]
    chain, by, alt = pick_chain(rows, el_all)
    if addrs:
        chain, alt = [a for a in addrs], None
    others = {a: len(v) for a, v in by.items() if a not in chain}
    print(f"\n[0] 우리 태그로 쓴 주소 ({'지정' if addrs else '자동 — 시간 구간이 안 겹치고 행이 가장 많은 사슬'})")
    for a in chain:
        v = by.get(a, [])
        print(f"   {a}  {len(v)}행  " + (f"{min(v)/1000:.1f}~{max(v)/1000:.1f}s" if v else "(행 없음)"))
    if others:
        print(f"  ! 다른 기기 {len(others)}개 ({sum(others.values())}행)는 아래 계산에서 뺐다: "
              + ", ".join(f"{a}({n})" for a, n in sorted(others.items(), key=lambda x: -x[1])[:5]))
    if alt:
        print(f"  !! 모호: 겹치지 않는 다른 사슬 {alt} 도 행 수가 비슷하다 — 어느 쪽이 우리 태그인지 모른다.")
        print("     아래 숫자를 쓰지 말고 --addr 로 우리 태그 주소를 지정해 다시 돌린다 (b1_find 후보 주소·RSSI 참고)")
    keep = set(chain)
    rows = [r for r in rows if r["address"] in keep]
    if not rows:
        print("지정한 주소의 행이 없습니다.")
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

    print("\n[5] 시각 차이 = 콜백 시각 − ts_nanos(호스트 시각) (ms) — 앱 콜백 전달 지연")
    pair = next((e for e in ev if e.get("ts_nanos") and e.get("rx_elapsed_ms")), None)
    if pair:
        off = num(pair["ts_nanos"], int) / 1e6 - num(pair["rx_elapsed_ms"], int)   # 같은 순간의 두 시계 차
        lat = [e - (t / 1e6 - off) for e, t in zip(el, ts) if e is not None and t is not None]
        print("  " + "  ".join(f"p{q}={pct(lat, q):.1f}" for q in (10, 50, 90, 99)))
        print("  (음수면 시계 맞춤이 틀렸다는 뜻 — 두 시각 모두 elapsedRealtime 계열이라 음수가 나오면 안 된다)")
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
    print("\n[7] 표시·점검")
    flags = [e for e in ev if e.get("event") == "run_flag"]
    if flags:
        f = flags[-1]
        print(f"  런 표시: {f['value']}  사유 {f.get('detail', '') or '-'}")
    else:
        print("  ! 런 표시 없음 — 유효/무효를 표시하지 않은 런은 분석에서 따로 다룬다")
    if meta:
        print(f"  스캔 설정 {scan_desc(meta)}")
        pf = meta.get("preflight")
        if pf:
            bad = [k for k in ("smartthings_closed", "wearables_off", "tag_normal_mode", "beep_heard") if pf.get(k) is False]
            print(f"  시작 전 점검  알람 {pf.get('alarm_volume')}/{pf.get('alarm_max')}  방해금지 {pf.get('dnd')}  "
                  f"절전 {pf.get('power_save')}  충전 {pf.get('plugged')}  Wi-Fi {pf.get('wifi_on')}")
            if bad:
                print(f"  ! 사람이 확인 안 한 항목: {', '.join(bad)}")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    a = sys.argv
    m = float(a[a.index("--margin") + 1]) if "--margin" in a else 3.0
    ad = a[a.index("--addr") + 1].split(",") if "--addr" in a else None
    main(a[1], m, ad)
