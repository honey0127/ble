#!/usr/bin/env python3
"""
TAG 런 점검 — 런 1개(tag_<stamp>.csv)가 쓸 만하게 모였는지 숫자로 본다.

    python3 tag_check.py tag_20261005_141200.csv [--margin 3] [--addr AA:..,BB:..]

**우리 태그 행만 쓴다.** TAG 필터(FD5A/FD59)는 주변의 다른 삼성 기기·태그도 통과시킨다.
[0] 에서 주소별 표(행 수, pkt/s, RSSI 중앙값, 처음·마지막 시각)를 먼저 낸다.
후보 주소 = RSSI 중앙값이 가장 센 주소(행 10개 이상). --addr 로 바꿀 수 있다.
주소 교체: 후보와 시간 구간이 겹치지 않고 중앙값이 후보보다 ROTATE_DB 이내인 주소는 같은 태그의
이전·다음 주소로 보고 잇는다 (같은 시각에 함께 보이는 주소는 다른 기기다).
후보와 같은 시각에 보이면서 중앙값이 AMBIGUOUS_DB 이내인 주소가 있으면 '모호'로 경고한다 —
세기로는 어느 쪽이 우리 태그인지 가를 수 없으니 --addr 로 직접 준다.
[1]~[6] 은 후보 주소(사슬)의 행만으로 계산한다. 나머지는 '다른 기기'로 센다.

같은 폴더의 events_<stamp>.csv, meta_<stamp>.json 을 자동으로 찾는다.
판정은 하지 않는다. T1~T3 의 통과 기준은 PLAN 이 정하고, 이 스크립트는 숫자만 낸다.

  1. 수집량       행 수, pkt/s, 5초 넘는 공백
  2. 광고 형식    legacy/확장 비율, PHY, adv_sid (B9), 서비스 UUID·페이로드 종류 (B1)
  3. 주소 교체    주소가 몇 번, 언제 바뀌었나 (T2)
  4. 수신 간격    ts_nanos 차이의 분포 — 채널 역산·수신 간격 분석의 바탕
  5. 시각 차이    스택 타임스탬프 → 앱 콜백 지연: rx_elapsed_ms(콜백 시각) − ts_nanos. ts_nanos 는 AOSP 코드상 블루투스
                  서비스가 결과를 만들 때 넣는 elapsedRealtimeNanos() — 호스트 시각이다
                  [문헌: Android 12L 계열 GattService, Android 16 코드는 미확인]. 이 차이는 서비스 →
                  앱 콜백 전달 지연이고, 컨트롤러 수신 시각과의 차이는 여기서 알 수 없다
  7. 표시·점검    run_flag(유효/무효 + 절차 사유 코드), 시작 전 점검, 스캔 설정
  6. 구간별 RSSI  알림음 시각으로 나눈 구간(카운트다운 제외, 전환 전후 --margin 초 제외).
                  런 시계 0 = timer 이벤트 중 detail 에 countdown_end 가 있는 행 (카운트다운 길이가 바뀌어도 된다).
                  가림 전환 = block_in_planned / block_out_planned, 끝 = 런 시계가 가장 큰 timer
"""
import re
import sys
from collections import Counter
from _common import read_csv, read_meta, num, pct, sibling, scan_desc

GAP_S = 5.0
MIN_ROWS = 10      # 이보다 적은 주소는 중앙값이 불안정해 후보에서 뺀다
OVERLAP_OK = 0.05  # 주소 교체 순간의 짧은 겹침 허용 — 각 구간 양끝을 5% 줄여서 비교한다
ROTATE_DB = 10     # 겹치지 않고 중앙값이 후보 −10 dB 이내면 같은 태그의 이전·다음 주소로 잇는다
AMBIGUOUS_DB = 6   # 같은 시각에 보이는 다른 주소가 후보 −6 dB 이내면 '모호'


def addr_table(rows, el):
    """주소 → (행 수, 처음 s, 마지막 s, RSSI 중앙값)"""
    by = {}
    for r, e in zip(rows, el):
        if e is not None:
            by.setdefault(r["address"], []).append((e / 1000.0, num(r["rssi"], int)))
    out = {}
    for a, v in by.items():
        ts = [t for t, _ in v]
        rs = [x for _, x in v if x is not None]
        out[a] = (len(v), min(ts), max(ts), pct(rs, 50) if rs else -999)
    return out


def overlaps(x, y):
    """두 주소의 시간 구간이 겹치나 (양끝을 OVERLAP_OK 만큼 줄여서)"""
    def span(v):
        pad = OVERLAP_OK * (v[2] - v[1])
        return v[1] + pad, v[2] - pad
    (a0, a1), (b0, b1) = span(x), span(y)
    return a0 < b1 and b0 < a1


def pick_chain(tab):
    """(후보 사슬, 모호 주소들). 사슬 = 가장 센 주소 + 겹치지 않는 비슷한 세기의 주소들(시간순)"""
    ok = sorted((a for a, v in tab.items() if v[0] >= MIN_ROWS), key=lambda a: -tab[a][3])
    if not ok:
        return [], []
    seed = ok[0]
    chain = [seed]
    for a in ok[1:]:
        if tab[a][3] >= tab[seed][3] - ROTATE_DB and not any(overlaps(tab[a], tab[c]) for c in chain):
            chain.append(a)
    amb = [a for a in ok if a not in chain and tab[a][3] >= tab[seed][3] - AMBIGUOUS_DB
           and any(overlaps(tab[a], tab[c]) for c in chain)]
    return sorted(chain, key=lambda a: tab[a][1]), amb


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
    tab = addr_table(rows, el_all)
    chain, amb = pick_chain(tab)
    if addrs:
        chain, amb = list(addrs), []
    print(f"\n[0] 주소별 (RSSI 중앙값 순, 행 {MIN_ROWS}개 미만은 후보에서 뺌)")
    print(f"     {'주소':<18}{'행':>6}{'pkt/s':>7}{'중앙':>6}{'처음 s':>9}{'마지막 s':>9}")
    for a in sorted(tab, key=lambda a: -tab[a][3])[:12]:
        n, t0_, t1_, med = tab[a]
        rate = n / (t1_ - t0_) if t1_ > t0_ else 0.0
        mark = "★" if a in chain else ("?" if a in amb else " ")
        print(f"   {mark} {a:<18}{n:>6}{rate:>7.2f}{med:>6}{t0_:>9.1f}{t1_:>9.1f}")
    if len(tab) > 12:
        print(f"     … 외 {len(tab) - 12}개")
    others = {a: tab[a][0] for a in tab if a not in chain}
    print(f"  ★ 후보 ({'지정 --addr' if addrs else '자동 — 중앙값이 가장 센 주소 + 겹치지 않는 비슷한 세기의 주소'}): "
          + (", ".join(chain) or "없음"))
    if others:
        print(f"  ! 다른 기기 {len(others)}개 ({sum(others.values())}행)는 아래 계산에서 뺐다")
    if amb:
        print(f"  !! 모호: 같은 시각에 보이는 {', '.join(amb)} 도 세기가 비슷하다 (−{AMBIGUOUS_DB} dB 이내) — 어느 쪽이 우리 태그인지 모른다.")
        print("     아래 숫자를 쓰지 말고 --addr 로 우리 태그 주소를 지정해 다시 돌린다 (b1_find 후보 주소 참고)")
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

    print("\n[5] 스택 타임스탬프 → 앱 콜백 지연 = 콜백 시각 − ts_nanos (ms)")
    pair = next((e for e in ev if e.get("ts_nanos") and e.get("rx_elapsed_ms")), None)
    if pair:
        off = num(pair["ts_nanos"], int) / 1e6 - num(pair["rx_elapsed_ms"], int)   # 같은 순간의 두 시계 차
        lat = [e - (t / 1e6 - off) for e, t in zip(el, ts) if e is not None and t is not None]
        print("  " + "  ".join(f"p{q}={pct(lat, q):.1f}" for q in (10, 50, 90, 99)))
        print("  (음수면 시계 맞춤이 틀렸다는 뜻 — 두 시각 모두 elapsedRealtime 계열이라 음수가 나오면 안 된다)")
        print("  ts_nanos 가 컨트롤러 수신 시각인지는 이 값으로 알 수 없다. AOSP 코드상 호스트 시각이다 [문헌]")
    else:
        print("  events 파일이 없어 두 시계를 맞출 수 없다")

    print(f"\n[6] 구간별 RSSI (카운트다운 제외, 전환 전후 {margin:g}초 제외)")
    def at_s(e):
        return num(e["rx_elapsed_ms"], int) / 1000.0

    def clock(e):
        m = re.search(r"run_clock_s=(\d+)", e.get("detail", ""))
        return int(m.group(1)) if m else None

    timers = [e for e in ev if e.get("event") == "timer"]
    zero = next((e for e in timers if "countdown_end" in e.get("detail", "")), None)
    if zero is None:
        print("  런 시계 0 (timer, detail 에 countdown_end) 이 없다 — TAG 런이 아니거나 events 가 없다")
    else:
        t0 = at_s(zero)
        ends = [clock(e) for e in timers if clock(e)]
        end = float(max(ends)) if ends else float(((meta or {}).get("run") or {}).get("duration_s") or 120)
        bi = next((at_s(e) - t0 for e in ev if e.get("event") == "block_in_planned"), None)
        bo = next((at_s(e) - t0 for e in ev if e.get("event") == "block_out_planned"), None)
        if bi is not None and bo is not None:
            cuts, names = [0.0, bi, bo, end], ["무가림(앞)", "가림", "무가림(뒤)"]
        else:
            cuts, names = [0.0, end], ["전체"]
        for name, a, b in zip(names, cuts, cuts[1:]):
            lo, hi = t0 + a + margin, t0 + b - margin
            v = [num(r["rssi"], int) for r, e in zip(rows, el) if lo <= e / 1000.0 < hi]
            print(f"  {name:<9} {a:5.0f}~{b:<5.0f}s  {stats(v)}   {len(v) / (hi - lo) if hi > lo else 0:.2f} pkt/s")
    print("\n[7] 표시·점검")
    flags = [e for e in ev if e.get("event") == "run_flag"]
    if flags:
        f = flags[-1]
        print(f"  런 표시: {f['value']}  {f.get('detail', '') or '(사유·메모 없음)'}")
        if len(flags) > 1:
            print(f"  ! run_flag 가 {len(flags)}번 — 마지막 것을 썼다")
    else:
        print("  ! 런 표시 없음 — 유효/무효를 표시하지 않은 런은 분석에서 따로 다룬다")
    if meta:
        print(f"  스캔 설정 {scan_desc(meta)}")
        pf = meta.get("precheck") or meta.get("preflight")   # 10/3 빌드는 preflight
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
