#!/usr/bin/env python3
"""
B1 찾기 — SmartTag2 가 어떤 식별자로 광고하는지 RAW CSV 에서 찾는다.

    python3 b1_find.py raw_20260930_101500.csv [--top 10]

측정법: 태그를 폰 뒷면에 거의 붙인 채(10 cm 이내) RAW 로 1분 받는다.
가장 센(RSSI 중앙값이 가장 높은) 기기가 태그일 가능성이 크다.
그 기기의 서비스 데이터 UUID 가 앱의 TAG 필터(FD5A/FD59, parse/TagFilter.kt)와 같은지 본다.

한계 [공식]: RAW 는 legacy=true 로 스캔해서 확장 광고만 쓰는 기기는 여기 안 나온다.
태그가 여기 안 보이는데 TAG 모드에서도 안 잡히면 B9(확장 광고) 쪽을 의심한다.
주소가 바뀌어도(RPA) 식별자 조합이 같으면 한 줄로 묶는다.
"""
import sys
from collections import Counter, defaultdict
from _common import read_csv, num, pct

TAG_FILTER = {"FD5A", "FD59"}


def main(path, top):
    rows = read_csv(path)
    if not rows or "svc_data_uuids" not in rows[0]:
        print("svc_data_uuids 컬럼이 없습니다. 9/30 이후 앱의 RAW CSV 가 필요합니다.")
        return
    groups = defaultdict(list)
    for r in rows:
        sig = (r.get("svc_data_uuids", ""), r.get("svc_uuids", ""), r.get("mfg_ids", ""))
        groups[sig].append(r)

    ranked = []
    for sig, rs in groups.items():
        rssi = [num(r["rssi"], int) for r in rs if num(r["rssi"], int) is not None]
        if not rssi:
            continue
        ranked.append((pct(rssi, 50), max(rssi), len(rs), len({r["address"] for r in rs}),
                       Counter(r.get("name", "") for r in rs).most_common(1)[0][0], sig))
    ranked.sort(key=lambda x: (x[0], x[1]), reverse=True)

    print(f"\n파일 {path}   행 {len(rows)}   식별자 조합 {len(ranked)}개\n")
    print(f"{'RSSI중앙':>8}{'최대':>6}{'행':>7}{'주소수':>6}  {'서비스데이터':<14}{'서비스UUID':<14}{'제조사ID':<12}이름")
    for med, mx, n, na, name, (d, u, m) in ranked[:top]:
        print(f"{med:>8}{mx:>6}{n:>7}{na:>6}  {d or '-':<14}{u or '-':<14}{m or '-':<12}{name}")

    if ranked:
        med, mx, n, na, name, (d, u, m) = ranked[0]
        found = set(filter(None, d.split("|")))
        print("\n[가장 센 기기]")
        if found & TAG_FILTER:
            print(f"  서비스 데이터 {sorted(found & TAG_FILTER)} — 앱 TAG 필터와 일치. B1 가정이 맞다.")
        else:
            print(f"  서비스 데이터 {sorted(found) or '없음'} — 앱 TAG 필터(FD5A/FD59)와 다르다.")
            print("  태그가 맞다면 parse/TagFilter.kt 의 TAG_SERVICE_UUIDS 를 고친다.")
        print("  확인: 태그를 멀리 치웠다가 다시 붙여 한 번 더 받았을 때도 같은 줄이 맨 위면 태그다.")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 10
    main(sys.argv[1], top)
