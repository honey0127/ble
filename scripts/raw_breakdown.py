#!/usr/bin/env python3
"""
RAW CSV 기기별 분해 — pkt/s 급락이 수신기 쪽인지 환경 쪽인지 가른다.

전체 분해 (분할 지점 자동 탐지, 가장 큰 분당 낙폭을 기준으로 삼는다):
    python3 raw_breakdown.py raw_20260922_092605.csv

분할 지점을 직접 지정하고 특정 MAC(부분 일치)만 볼 때
(고정 주소를 쓰는 기기, 예: ESP32 비콘 — 그 행이 같이 떨어지면 원인은 수신기 쪽이다):
    python3 raw_breakdown.py raw_20260922_092605.csv 5 <ESP32_MAC_일부>

주의 [문헌] 대부분의 스마트폰은 RPA(Resolvable Private Address)를 쓴다.
주소가 주기적으로 바뀌므로 "소멸/신규"는 실제 기기 이동이 아니라 주소 교체일
수 있다. 판정은 어디까지나 '이어지는 주소 계열'에서만 신뢰할 수 있고,
가장 깨끗한 근거는 주소가 고정된 기기(ESP32 등)의 행이다.

패턴 A (수신기 측): 살아남는 기기들의 낙폭 비율이 서로 비슷하게 줄어든다
                     → 스캔 듀티사이클 강등 등 수신기 쪽 원인
패턴 B (환경 측):   일부 기기가 통째로 사라지고(=RPA 주소 교체 포함),
                     계속 잡히는 기기의 분당 수신량은 유지된다
                     → 주변 밀도 변화 쪽 원인

분당 수신량은 '그 분에 실제로 기록된 시간'으로 나눈다. 기록은 보통 새 1분에
몇 초만 걸친 채 끝나는데, 그 마지막 부분 분을 1분으로 세면 수신량이 거의 0 으로
보여 자동 분할이 거기를 급락 지점으로 잘못 고르고, 분할 후 수신량도 낮게 잡혀
배율이 부풀려진다. 30초 미만만 기록된 분은 자동 분할 후보에서 뺀다.
"""
import sys
import csv
from collections import defaultdict


def load(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            try:
                rows.append({
                    "el": int(r["rx_elapsed_ms"]),
                    "addr": r.get("address", ""),
                })
            except (KeyError, ValueError):
                continue
    rows.sort(key=lambda x: x["el"])
    return rows


def per_minute_counts(rows):
    counts = defaultdict(int)
    for r in rows:
        counts[r["el"] // 60000] += 1
    return counts


MIN_COVER_MIN = 0.5   # 자동 분할 후보가 되려면 그 분이 최소 30초는 기록돼 있어야 한다


def end_minutes(rows):
    """기록 끝 시각(분, 소수). 마지막 행의 rx_elapsed_ms 기준."""
    return rows[-1]["el"] / 60000.0


def coverage(rows, m):
    """m 번째 분(m ~ m+1) 중 실제로 기록된 길이(분, 0~1). 마지막 부분 분만 1 보다 작다."""
    return max(0.0, min(1.0, end_minutes(rows) - m))


def auto_split(rows):
    """분당 수신량(개수 ÷ 기록된 길이)의 낙폭 비율이 가장 큰 경계를 분할 지점으로 삼는다.
    30초 미만만 기록된 분(보통 마지막 부분 분)은 후보에서 뺀다."""
    counts = per_minute_counts(rows)
    if not counts:
        return 0
    last_min = max(counts)
    best_min, best_drop = 1, -1.0
    for m in range(1, last_min + 1):
        if coverage(rows, m - 1) < MIN_COVER_MIN or coverage(rows, m) < MIN_COVER_MIN:
            continue
        prev = counts.get(m - 1, 0) / coverage(rows, m - 1)
        cur = counts.get(m, 0) / coverage(rows, m)
        if prev == 0:
            continue
        drop = 1.0 - (cur / prev)
        if drop > best_drop:
            best_drop, best_min = drop, m
    return best_min


def spans(rows, split_min):
    """분할 전·후 구간의 실제 길이(분). 분할 후 길이는 기록 끝까지의 실제 시간이다."""
    return float(split_min), end_minutes(rows) - split_min


def breakdown(rows, split_min):
    pre = defaultdict(int)
    post = defaultdict(int)
    for r in rows:
        m = r["el"] // 60000
        (pre if m < split_min else post)[r["addr"]] += 1

    pre_span, post_span = spans(rows, split_min)
    if split_min <= 0 or post_span <= 0:
        print(f"\n분할 지점 {split_min} 분이 기록 범위(0 ~ {end_minutes(rows):.2f} 분) 밖입니다.")
        return

    pre_addrs = set(pre)
    post_addrs = set(post)
    continuing = pre_addrs & post_addrs
    vanished = pre_addrs - post_addrs
    appeared = post_addrs - pre_addrs

    pre_total = sum(pre.values())
    post_total = sum(post.values())

    print(f"\n분할 지점         {split_min} 분")
    print(f"분할 전 ({pre_span:.2f}분)  {pre_total} 행, 고유 주소 {len(pre_addrs)} 개")
    print(f"분할 후 ({post_span:.2f}분)  {post_total} 행, 고유 주소 {len(post_addrs)} 개")
    print(f"전체 낙폭          {pre_total/pre_span:.0f} → {post_total/post_span:.0f} pkt/min"
          f"  (x{(pre_total/pre_span)/(post_total/post_span) if post_total else float('inf'):.1f})")

    print(f"\n분할 전에 있던 주소 {len(pre_addrs)} 개 중")
    print(f"  계속 잡힘   {len(continuing)} 개")
    print(f"  통째로 소멸 {len(vanished)} 개  (RPA 주소 교체일 수 있음, 실제 이동 아닐 수 있음)")
    print(f"신규로 나타난 주소  {len(appeared)} 개  (RPA 주소 교체일 수 있음)")

    if not continuing:
        print("\n계속 잡히는 주소가 없다 — 낙폭 비율을 계산할 근거가 없다.")
        print("→ 패턴 B 쪽 정황 (기기 전량 교체/소멸). 다만 대부분 RPA 특성일 수 있어 이 CSV만으로는 [미검증].")
        return

    # 분할 전 수신량 상위 기기 위주로 낙폭 비율을 본다 (노이즈가 적은 표본)
    ranked = sorted(continuing, key=lambda a: pre[a], reverse=True)
    print(f"\n[계속 잡힌 주소 중 분할 전 수신량 상위 {min(15, len(ranked))}개 — 낙폭 비율]")
    print(f"  {'주소':<20}{'pre/min':>10}{'post/min':>10}{'배율':>8}")
    ratios = []
    weight_sum = 0.0
    for a in ranked[:15]:
        pre_rate = pre[a] / pre_span
        post_rate = post[a] / post_span
        ratio = pre_rate / post_rate if post_rate > 0 else float("inf")
        ratios.append((ratio, pre[a]))
        weight_sum += pre[a]
        r_str = f"{ratio:.1f}x" if ratio != float("inf") else "inf (소멸)"
        print(f"  {a:<20}{pre_rate:>10.1f}{post_rate:>10.1f}{r_str:>8}")

    finite = [r for r, w in ratios if r != float("inf")]
    if finite:
        weighted = sum(r * w for r, w in zip((r for r, w in ratios if r != float('inf')),
                                              (w for r, w in ratios if r != float('inf')))) / \
                   sum(w for r, w in ratios if r != float('inf'))
        spread = max(finite) - min(finite)
        print(f"\n  가중평균 배율 {weighted:.1f}x   범위 {min(finite):.1f}x ~ {max(finite):.1f}x")
        if spread <= weighted * 0.6:
            print("  → 낙폭 비율이 기기들 사이에서 고르게 나타난다. 패턴 A 쪽 정황 (수신기 측).")
        else:
            print("  → 낙폭 비율이 기기마다 크게 갈린다. 패턴 B 쪽 정황 (환경/개별 기기 이동)일 수 있다.")
    print("\n둘 다 [미검증]이다 — 이 스크립트는 정황만 가른다. 확정은 고정 주소(ESP32) 행으로만 한다.")


def mac_filter(rows, split_min, mac_substr):
    m = mac_substr.lower()
    filtered = [r for r in rows if m in r["addr"].lower()]
    if not filtered:
        print(f"\n주소에 '{mac_substr}' 를 포함하는 행이 없습니다. MAC 을 다시 확인하세요.")
        return

    addrs = sorted({r["addr"] for r in filtered})
    print(f"\n'{mac_substr}' 일치 주소: {addrs}")

    pre = sum(1 for r in filtered if r["el"] // 60000 < split_min)
    post = sum(1 for r in filtered if r["el"] // 60000 >= split_min)
    pre_span, post_span = spans(rows, split_min)
    if split_min <= 0 or post_span <= 0:
        print(f"\n분할 지점 {split_min} 분이 기록 범위(0 ~ {end_minutes(rows):.2f} 분) 밖입니다.")
        return
    pre_rate = pre / pre_span
    post_rate = post / post_span

    print(f"분할 지점 {split_min} 분   (기록 끝 {end_minutes(rows):.2f} 분)")
    print(f"분할 전   {pre} 행 / {pre_span:.2f} 분 ({pre_rate:.1f} pkt/min)")
    print(f"분할 후   {post} 행 / {post_span:.2f} 분 ({post_rate:.1f} pkt/min)")

    if post_rate == 0:
        print("\n분할 후 이 주소에서 수신이 전혀 없다 — 판정 불가 (기기가 꺼졌거나 이동했을 수 있다).")
        return

    ratio = pre_rate / post_rate
    print(f"배율 x{ratio:.1f}")
    print("\n[판정] 이 주소는 고정 주소 기기(예: ESP32)로 가정한다.")
    if ratio >= 3:
        print(f"  같이 x{ratio:.1f} 떨어졌다 → 원인은 수신기 측(스캔 듀티사이클 등)이다.")
        print("  주변 기기 밀도 가설은 여기서 배제된다.")
    else:
        print("  거의 안 떨어졌다 → 원인은 환경 측(주변 기기 밀도 변화)에 더 가깝다.")
        print("  수신기가 멀쩡히 계속 듣고 있었다는 뜻이다.")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    path = sys.argv[1]
    rows = load(path)
    if not rows:
        print("파싱된 행이 없습니다. RAW 모드 CSV 가 맞는지 확인하세요.")
        return

    if len(sys.argv) >= 4:
        split_min = int(sys.argv[2])
        mac_filter(rows, split_min, sys.argv[3])
    else:
        split_min = int(sys.argv[2]) if len(sys.argv) >= 3 else auto_split(rows)
        breakdown(rows, split_min)


if __name__ == "__main__":
    main()
