#!/usr/bin/env python3
"""
RAW 모드 CSV 점검기 — 10분 연속 수집이 끊겼는지만 본다. (A3 가정 / T5)

    python3 raw_check.py raw_20260922_143000.csv

analyze.py 는 BEACON 전용이라 RAW CSV 에는 쓸 수 없다. 이 스크립트를 쓴다.

판정은 숫자 기준이 아니라 '중단이 있었는가' 를 직접 본다.
분당 수신량이 0 인 구간이나 비정상적으로 긴 공백이 있으면 스캔이 끊긴 것이다.
"""
import sys
import csv


def main(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            try:
                rows.append({
                    "el": int(r["rx_elapsed_ms"]),
                    "rssi": int(r["rssi"]),
                    "addr": r.get("address", ""),
                    "tag": r.get("tag", ""),
                })
            except (KeyError, ValueError):
                continue

    if not rows:
        print("파싱된 행이 없습니다. RAW 모드 CSV 가 맞는지 확인하세요.")
        return

    rows.sort(key=lambda x: x["el"])
    dur_s = (rows[-1]["el"] - rows[0]["el"]) / 1000.0
    tags = sorted({r["tag"] for r in rows if r["tag"]})
    devices = {r["addr"] for r in rows}

    print(f"\n파일      {path}")
    print(f"tag       {tags}")
    print(f"행 수     {len(rows)}")
    print(f"측정 시간 {dur_s/60:.2f} 분 ({dur_s:.1f} 초)")
    print(f"고유 기기 {len(devices)} 대")
    print(f"평균      {len(rows)/dur_s:.2f} pkt/s" if dur_s > 0 else "")

    # ---- 분당 수신량 : 중간에 죽었는지 한눈에 본다 ----
    print("\n[분당 수신량]  0 인 분이 있으면 그 구간에서 스캔이 끊긴 것")
    per_min = {}
    for r in rows:
        per_min[r["el"] // 60000] = per_min.get(r["el"] // 60000, 0) + 1
    last_min = rows[-1]["el"] // 60000
    peak = max(per_min.values()) if per_min else 1
    zero_minutes = []
    for m in range(last_min + 1):
        n = per_min.get(m, 0)
        if n == 0:
            zero_minutes.append(m)
        bar = "#" * int(40 * n / peak) if peak else ""
        mark = "  <-- 수신 없음" if n == 0 else ""
        print(f"  {m:>3} 분  {n:>6}  {bar}{mark}")

    # ---- 가장 긴 공백 ----
    print("\n[가장 긴 공백 상위 5개]")
    gaps = []
    for a, b in zip(rows, rows[1:]):
        gaps.append((b["el"] - a["el"], a["el"]))
    gaps.sort(reverse=True)
    for g, at in gaps[:5]:
        print(f"  {g/1000.0:>8.1f} 초 공백   (시작 {at/1000.0:.1f} 초 지점)")

    # ---- 판정 ----
    print("\n[판정]")
    ok = True
    if dur_s < 9.5 * 60:
        print(f"  ! 측정 시간이 {dur_s/60:.1f} 분입니다. 10분을 채우지 못했습니다.")
        ok = False
    if zero_minutes:
        print(f"  ! 수신이 전혀 없는 분: {zero_minutes}  → 스캔이 끊겼습니다.")
        ok = False
    if ok:
        print("  중단 없이 10분을 채웠습니다.  A3 가정 → 통과 (1회차)")
        print("  다른 시간대에 1~2회 더 돌려 재현되는지 확인하세요.")
    else:
        print("  배터리 설정 5개를 다시 확인하고 재측정하세요.")
        print("  (삼성은 펌웨어 업데이트 후 이 설정들을 되돌리는 사례가 있습니다)")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
