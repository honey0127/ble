#!/usr/bin/env python3
"""
B1·B9 찾기 — SmartTag2 가 어떤 식별자·광고 형식으로 광고하는지 RAW CSV 에서 찾는다.

    python3 b1_find.py 붙인.csv --control 치운.csv [--top 10]

측정법 (둘 다 RAW '확장 광고 포함', 각 1분):
  1. 태그를 폰 뒷면에 붙인 채 1분  → 붙인.csv
  2. 태그를 멀리(다른 방) 치운 채 1분 → 치운.csv (--control)

판정 순서
  - **주소별로** 순위를 매긴다 (RSSI 중앙값). 식별자로 묶어서 순위를 매기면 같은 식별자를 쓰는
    다른 기기(주변의 다른 삼성 기기 등)가 섞여 태그가 묻힌다
  - 태그 후보 = 붙인 캡처에서 센 주소 중, 치운 캡처에 **그 주소가 없고 같은 식별자를 쓰는 센 기기도
    없는** 것. 태그를 치우면 사라지는 식별자가 태그의 식별자다
  - 그 식별자가 앱 TAG 필터(FD5A/FD59, parse/TagFilter.kt)와 같은지(B1), legacy 비율(B9)을 본다
--control 없이 돌리면 '가장 센 주소'만 보여 주고 판정은 미확정으로 둔다.
레거시만 받은 RAW 면 확장 광고만 쓰는 기기는 아예 안 보이므로 경고한다 [공식 setLegacy].
"""
import sys
from collections import Counter, defaultdict
from _common import read_csv, read_meta, num, pct, sibling, parse_ad, scan_desc

TAG_FILTER = {"FD5A", "FD59"}
MIN_ROWS = 5          # 이보다 적게 잡힌 주소는 RSSI 중앙값이 불안정해 순위에서 뺀다
STRONG_MARGIN = 15    # 치운 캡처에서 '같은 식별자의 센 기기' = 후보 RSSI − 15 dB 이상


def sig_of(r):
    return (r.get("svc_data_uuids", ""), r.get("svc_uuids", ""), r.get("mfg_ids", ""))


def sig_str(s):
    d, u, m = s
    return f"data={d or '-'} uuid={u or '-'} mfg={m or '-'}"


def by_address(rows):
    """주소 → 요약. 식별자는 그 주소에서 가장 많이 나온 조합"""
    g = defaultdict(list)
    for r in rows:
        g[r["address"]].append(r)
    out = {}
    for a, rs in g.items():
        rssi = [v for v in (num(r["rssi"], int) for r in rs) if v is not None]
        if not rssi:
            continue
        has_ext = "is_legacy" in rs[0]
        out[a] = {
            "n": len(rs), "med": pct(rssi, 50), "max": max(rssi),
            "sig": Counter(sig_of(r) for r in rs).most_common(1)[0][0],
            "legacy": (sum(r.get("is_legacy") == "1" for r in rs) / len(rs)) if has_ext else None,
            "phy": Counter((r.get("primary_phy"), r.get("secondary_phy")) for r in rs).most_common(1)[0][0],
            "adv": Counter(r.get("adv_hex", "") for r in rs if r.get("adv_hex")).most_common(1),
            "name": Counter(r.get("name", "") for r in rs).most_common(1)[0][0],
        }
    return out


def warn_scan(path, rows):
    meta = read_meta(sibling(path, "meta", "json"))
    has_ext = bool(rows) and "is_legacy" in rows[0]
    legacy_only = (meta or {}).get("scan", {}).get("legacy") is True or \
        (has_ext and all(r.get("is_legacy") == "1" for r in rows))
    print(f"  스캔 설정 {scan_desc(meta) or '(meta 없음)'}")
    if legacy_only or not has_ext:
        print("  ! 레거시만 받은 RAW — 확장 광고만 쓰는 기기는 여기 없다. '확장 광고 포함'으로 다시 받을 것")


def main(path, control, top):
    rows = read_csv(path)
    if not rows or "svc_data_uuids" not in rows[0]:
        print("svc_data_uuids 컬럼이 없습니다. 9/29 이후 앱의 RAW CSV 가 필요합니다.")
        return
    print(f"\n[붙인 캡처] {path}  ({len(rows)}행)")
    warn_scan(path, rows)
    A = by_address(rows)
    ranked = sorted((a for a in A if A[a]["n"] >= MIN_ROWS), key=lambda a: (A[a]["med"], A[a]["max"]), reverse=True)

    C, crow = None, []
    if control:
        crow = read_csv(control)
        print(f"[치운 캡처] {control}  ({len(crow)}행)")
        warn_scan(control, crow)
        C = by_address(crow)

    def in_control(a):
        """(같은 주소가 있나, 같은 식별자의 센 기기 수)"""
        if C is None:
            return None, None
        same_sig = [b for b, v in C.items() if v["sig"] == A[a]["sig"] and v["n"] >= MIN_ROWS
                    and v["med"] >= A[a]["med"] - STRONG_MARGIN]
        return a in C, len(same_sig)

    print(f"\n주소별 순위 (행 {MIN_ROWS}개 이상, RSSI 중앙값 순)")
    print(f"  {'주소':<18}{'중앙':>5}{'최대':>5}{'행':>6}{'legacy':>8}  {'치운 캡처':<16}식별자")
    for a in ranked[:top]:
        v = A[a]
        ls = f"{v['legacy']:.0%}" if v["legacy"] is not None else "-"
        same, nsig = in_control(a)
        cs = "-" if same is None else ("주소 있음" if same else (f"같은식별자 {nsig}" if nsig else "사라짐"))
        print(f"  {a:<18}{v['med']:>5}{v['max']:>5}{v['n']:>6}{ls:>8}  {cs:<16}{sig_str(v['sig'])}")

    if not ranked:
        print("  행이 충분한 주소가 없다")
        return
    if C is None:
        cand = ranked[0]
        print("\n[판정 미확정] --control(태그를 치운 캡처) 없이 '가장 센 주소'만 보였다.")
    else:
        gone = [a for a in ranked if in_control(a) == (False, 0)]
        if not gone:
            print("\n[판정 실패] 치우면 사라지는 센 주소가 없다 — 태그가 꺼졌거나, 붙인 캡처에 안 잡혔다(B9?)")
            return
        cand = gone[0]
        print(f"\n[태그 후보] 치우면 사라지는 가장 센 주소: {cand}")
    v = A[cand]
    same_here = [b for b in A if b != cand and A[b]["sig"] == v["sig"]]
    print(f"  식별자 {sig_str(v['sig'])}   RSSI 중앙 {v['med']}   행 {v['n']}")
    print(f"  같은 식별자를 쓰는 다른 주소 (붙인 캡처): {len(same_here)}개  — T2 의 '주변 같은 식별자 기기 수'")
    if v["legacy"] is not None:
        kind = "레거시만" if v["legacy"] == 1 else ("확장만" if v["legacy"] == 0 else f"섞임(레거시 {v['legacy']:.0%})")
        print(f"  광고 형식 {kind}   (primary, secondary) PHY {v['phy']}  → B9")
    if v["adv"]:
        print("  광고 원본 AD 구조:")
        for t, tn, data in parse_ad(v["adv"][0][0]):
            print(f"    0x{t:02X} {tn:<12} {data}")
    found = set(filter(None, v["sig"][0].split("|")))
    if found & TAG_FILTER:
        print(f"  B1: 서비스 데이터 {sorted(found & TAG_FILTER)} — 앱 TAG 필터와 일치")
    else:
        print(f"  B1: 서비스 데이터 {sorted(found) or '없음'} — 앱 TAG 필터(FD5A/FD59)와 다르다."
              + (" 치운 캡처로 확인된 후보이므로 parse/TagFilter.kt 를 고칠 근거가 된다" if C is not None
                 else " --control 로 확인하기 전에는 고치지 않는다"))
    print()


if __name__ == "__main__":
    a = sys.argv
    if len(a) < 2:
        print(__doc__)
        sys.exit(1)
    ctrl = a[a.index("--control") + 1] if "--control" in a else None
    top = int(a[a.index("--top") + 1]) if "--top" in a else 10
    main(a[1], ctrl, top)
