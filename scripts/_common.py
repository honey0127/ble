"""scripts/ 공용 — 같은 stamp 의 파일 찾기, CSV 읽기."""
import csv
import json
import os
import re


def stamp_of(path):
    m = re.search(r"(\d{8}_\d{6})", os.path.basename(path))
    return m.group(1) if m else None


def sibling(path, prefix, ext="csv"):
    """raw_/beacon_/tag_<stamp>.csv 옆의 events_/meta_<stamp> 파일. 없으면 None"""
    st = stamp_of(path)
    if not st:
        return None
    p = os.path.join(os.path.dirname(path) or ".", f"{prefix}_{st}.{ext}")
    return p if os.path.exists(p) else None


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def read_meta(path):
    if not path:
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def num(v, cast=float, default=None):
    try:
        return cast(v)
    except (TypeError, ValueError):
        return default


def pct(values, q):
    """단순 백분위 (정렬 후 최근접 순위). 빈 목록이면 None"""
    if not values:
        return None
    v = sorted(values)
    i = min(len(v) - 1, max(0, int(round(q / 100.0 * (len(v) - 1)))))
    return v[i]


AD_TYPES = {
    0x01: "Flags", 0x02: "UUID16(일부)", 0x03: "UUID16", 0x06: "UUID128(일부)", 0x07: "UUID128",
    0x08: "이름(짧은)", 0x09: "이름", 0x0A: "TxPower", 0x16: "서비스데이터16", 0x20: "서비스데이터32",
    0x21: "서비스데이터128", 0xFF: "제조사데이터",
}


def parse_ad(hexstr):
    """ScanRecord.getBytes() hex → [(type, 이름, data_hex)]. 길이 0 이면 끝(뒤는 0 채움)"""
    try:
        b = bytes.fromhex(hexstr or "")
    except ValueError:
        return []
    out, i = [], 0
    while i < len(b):
        n = b[i]
        if n == 0 or i + 1 + n > len(b):
            break
        t, data = b[i + 1], b[i + 2:i + 1 + n]
        out.append((t, AD_TYPES.get(t, f"0x{t:02X}"), data.hex()))
        i += 1 + n
    return out


def scan_desc(meta):
    """meta 의 스캔 설정 한 줄. meta 가 없으면 None"""
    if not meta or not meta.get("scan"):
        return None
    s = meta["scan"]
    return f"legacy={s.get('legacy')} phy={s.get('phy')} raw_extended={s.get('raw_extended')}"


def scan_start_ns(events):
    """events 의 scan_start 행 detail 'pre_ns=.. post_ns=..' 에서 post_ns. 없으면 None"""
    for e in events:
        if e.get("event") == "scan_start":
            m = re.search(r"post_ns=(\d+)", e.get("detail", ""))
            if m:
                return int(m.group(1))
    return None
