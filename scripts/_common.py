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


def scan_start_ns(events):
    """events 의 scan_start 행 detail 'pre_ns=.. post_ns=..' 에서 post_ns. 없으면 None"""
    for e in events:
        if e.get("event") == "scan_start":
            m = re.search(r"post_ns=(\d+)", e.get("detail", ""))
            if m:
                return int(m.group(1))
    return None
