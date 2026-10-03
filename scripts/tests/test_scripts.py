#!/usr/bin/env python3
"""
분석 스크립트 반례 시험 — 정답을 아는 합성 데이터로 각 스크립트가 맞는 답을 내는지 본다.

    python3 scripts/tests/test_scripts.py        # 전부 PASS 여야 한다

APP_DESIGN 9/29 검토에서 실패했던 경우를 그대로 만든다:
  b1_find   같은 식별자(FD5A)를 쓰는 다른 기기가 많이 섞여도 태그를 찾는가 (식별자로 묶으면 놓친다)
  tag_check 다른 FD5A 기기가 같은 시각에 섞여도 우리 태그 행만으로 구간 RSSI 를 내는가
  m4        위상이 밀리는 데이터에서 고정 위상 적중률이 떨어지는가 (구간마다 다시 맞추면 가려진다)
  m4        무작위 데이터의 적중률이 시뮬레이션 우연 수준 안에 드는가 (0.4 같은 고정값이 아니라)
"""
import json
import os
import random
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
BOOT = 9_000_000_000_000
EH = ("rx_wall_ms,rx_elapsed_ms,ts_nanos,event,value,rows,screen_on,activity,importance,power_save,doze,"
      "plugged,batt_pct,batt_temp_c,thermal,headroom,bt,detail,tag")
RAWH = ("rx_wall_ms,rx_elapsed_ms,address,rssi,name,tag,ts_nanos,svc_data_uuids,svc_uuids,mfg_ids,"
        "is_legacy,primary_phy,secondary_phy,adv_sid,adv_hex")
results = []


def run(script, *args):
    p = subprocess.run([sys.executable, os.path.join(SCRIPTS, script), *args],
                       capture_output=True, text=True, cwd=SCRIPTS)
    return p.stdout + p.stderr


def check(name, ok, out=""):
    results.append((name, ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}")
    if not ok:
        print("      " + "\n      ".join(out.strip().splitlines()[-25:]))


def erow(t_ms, ev, val, rows=0, detail=""):
    return f"0,{t_ms},{BOOT + t_ms * 1_000_000},{ev},{val},{rows},1,onResume,100,0,off,usb,80,30.0,0,,on,{detail},t"


def raw_capture(path, tag_on, seed):
    """태그(FD5A, -40) + 같은 식별자 기기 8대(-80, 행 많음) + 가까운 폰(004C, -55) + 잡음 기기들"""
    rnd = random.Random(seed)
    with open(path, "w") as f:
        f.write(RAWH + "\n")
        for i in range(4000):
            t = i * 15
            k = i % 10
            if k == 0 and tag_on:
                a, r, d, m, leg = "4A:AA:AA:AA:AA:01", -40 + rnd.randint(-2, 2), "FD5A", "0075", "0"
            elif k in (1, 2, 3, 4, 5):
                a, r, d, m, leg = f"5{k}:BB:BB:BB:BB:0{rnd.randint(0, 1)}", -80 + rnd.randint(-4, 4), "FD5A", "0075", "1"
            elif k == 6:
                a, r, d, m, leg = "11:22:33:44:55:66", -55 + rnd.randint(-3, 3), "", "004C", "1"
            else:
                a, r, d, m, leg = f"7{rnd.randint(0, 9)}:00:00:00:00:{rnd.randint(10, 99)}", -85 + rnd.randint(-6, 6), "", "0006", "1"
            f.write(f"0,{t},{a},{r},,b1,{BOOT + t * 1_000_000},{d},,{m},{leg},1,{0 if leg == '1' else 2},5,020106\n")
    json.dump({"mode": "RAW", "scan": {"legacy": False, "phy": "LE_1M", "raw_extended": True}},
              open(path.replace("raw_", "meta_").replace(".csv", ".json"), "w"))


def test_b1(tmp):
    on, off = os.path.join(tmp, "raw_20261004_100000.csv"), os.path.join(tmp, "raw_20261004_100200.csv")
    raw_capture(on, True, 1)
    raw_capture(off, False, 2)
    out = run("b1_find.py", on, "--control", off)
    check("b1_find: 같은 식별자 기기 8대가 섞여도 태그 주소를 찾는다",
          "[태그 후보]" in out and "4A:AA:AA:AA:AA:01" in out.split("[태그 후보]")[1].splitlines()[0], out)
    check("b1_find: 태그 식별자가 TAG 필터와 일치한다고 판정한다", "앱 TAG 필터와 일치" in out, out)
    check("b1_find: 태그의 확장 광고(레거시 0%)를 보고한다", "광고 형식 확장만" in out, out)
    out2 = run("b1_find.py", on)
    check("b1_find: --control 없으면 판정 미확정이라고 말한다", "판정 미확정" in out2, out2)


def test_tag_check(tmp):
    st = "20261005_141200"
    with open(os.path.join(tmp, f"events_{st}.csv"), "w") as f:
        f.write(EH + "\n")
        f.write(erow(3, "scan_start", "1", detail=f"pre_ns={BOOT} post_ns={BOOT + 2_000_000}") + "\n")
        for v, t in (("10", 10000), ("30", 40000), ("90", 100000), ("120", 130000)):
            f.write(erow(t, "timer", v) + "\n")
        f.write(erow(140000, "run_flag", "valid") + "\n")
    rnd = random.Random(3)
    with open(os.path.join(tmp, f"tag_{st}.csv"), "w") as f:
        f.write("rx_wall_ms,rx_elapsed_ms,ts_nanos,address,rssi,is_legacy,primary_phy,secondary_phy,adv_sid,"
                "svc_uuid,svc_data_hex,scan_seq,cond\n")
        t = 500
        while t < 130000:
            rc = (t - 10000) / 1000
            r = -60 + (-8 if 30 <= rc < 90 else 0) + rnd.randint(-1, 1)
            a = "5A:11:11:11:11:11" if t < 70000 else "6B:22:22:22:22:22"     # 주소 교체
            f.write(f"0,{t + 15},{BOOT + t * 1_000_000},{a},{r},0,1,2,3,FD5A,aa,1,c\n")
            # 같은 시각의 다른 FD5A 기기 (-90, 거의 같은 빈도) — 섞이면 구간 평균이 오염된다
            f.write(f"0,{t + 20},{BOOT + (t + 5) * 1_000_000},9C:99:99:99:99:99,{-90 + rnd.randint(-1, 1)},0,1,2,3,FD5A,bb,1,c\n")
            t += 2000
    path = os.path.join(tmp, f"tag_{st}.csv")
    # 다른 기기가 런 내내 한 주소로 보이고 우리 태그는 주소를 바꾼다 → 행 수만으로는 가를 수 없다
    out0 = run("tag_check.py", path)
    check("tag_check: 사슬이 둘 다 그럴듯하면 '모호'라고 경고한다 (조용히 섞지 않음)", "!! 모호" in out0, out0)
    out = run("tag_check.py", path, "--addr", "5A:11:11:11:11:11,6B:22:22:22:22:22")
    def seg(name):
        m = re.search(r"^\s+" + name + r"\s+[\d~]+\s+s\s+n=\s*\d+\s+평균\s+(-?\d+\.\d)", out, re.M)
        return float(m.group(1)) if m else None
    a, b = seg(r"무가림\(앞\)"), seg("가림")
    check("tag_check: 다른 FD5A 기기를 빼고 구간 RSSI 를 낸다 (가림 ≈ -68, 앞 ≈ -60)",
          a is not None and b is not None and abs(a + 60) < 1.5 and abs(b + 68) < 1.5, out)
    check("tag_check: --addr 로 준 사슬만 쓰고 9C 는 다른 기기로 뺀다",
          "5A:11:11:11:11:11" in out and "6B:22:22:22:22:22" in out and "다른 기기 1개" in out, out)

    # 다른 기기가 띄엄띄엄(행 적게) 보이면 자동으로 우리 태그 사슬을 고른다
    with open(path) as f:
        lines = f.read().splitlines()
    keep = [lines[0]] + [l for i, l in enumerate(lines[1:]) if "9C:99" not in l or i % 10 == 1]
    open(path, "w").write("\n".join(keep) + "\n")
    out2 = run("tag_check.py", path)
    a2, b2 = None, None
    m = re.search(r"^\s+가림\s+[\d~]+\s+s\s+n=\s*\d+\s+평균\s+(-?\d+\.\d)", out2, re.M)
    check("tag_check: 다른 기기가 드문드문이면 자동 사슬로 맞는 구간 RSSI (가림 ≈ -68)",
          "!! 모호" not in out2 and m is not None and abs(float(m.group(1)) + 68) < 1.5, out2)


def beacon_run(tmp, st, gen, cut_extra=False):
    with open(os.path.join(tmp, f"events_{st}.csv"), "w") as f:
        f.write(EH + "\n")
        f.write(erow(4, "scan_start", "1", detail=f"pre_ns={BOOT} post_ns={BOOT + 4_000_000}") + "\n")
    json.dump({"mode": "BEACON", "scan": {"legacy": False, "phy": "LE_1M", "raw_extended": False}},
              open(os.path.join(tmp, f"meta_{st}.json"), "w"))
    with open(os.path.join(tmp, f"beacon_{st}.csv"), "w") as f:
        f.write("rx_wall_ms,rx_elapsed_ms,beacon_id,channel_id,seq,rssi,tx_uptime_ms,tx_power_dbm,tag,ts_nanos,address\n")
        for ch, t in gen:
            f.write(f"0,{int(t * 1000)},1,{ch},0,-55,0,0,m4,{BOOT + 4_000_000 + int(t * 1e9)},AA\n")
    return os.path.join(tmp, f"beacon_{st}.csv")


def slotted(ch, d, phase_s, t0, t1, rnd, step=0.1):
    t = t0
    while t < t1:
        if int(((t - phase_s) % (3 * d)) // d) == ch - 37 and rnd.random() < 0.9:
            yield ch, t
        t += step


def test_m4(tmp):
    rnd = random.Random(4)
    # 예측: 스캔 시작이 37 구간 시작(위상 0), D=4.096, 런 280 s
    g = list(slotted(37, 4.096, 0.0, 0, 280, rnd))
    p = beacon_run(tmp, "20261006_100000", g)
    out = run("m4_channel.py", p, "--dwell", "4.096", "--null", "10")
    m = re.search(r"\[1\][^\n]*\n\s+ch37: ([0-9.]+)", out)
    check("m4: 예측 구간 적중률 — 위상이 맞으면 ≈ 1", m is not None and float(m.group(1)) > 0.95, out)

    # 드리프트: 실제 D=4.2 인데 4.096 으로 분석 → 고정 위상 적중률이 시간이 갈수록 떨어진다
    g = list(slotted(37, 4.2, 0.0, 0, 280, rnd))
    p = beacon_run(tmp, "20261006_101000", g)
    out = run("m4_channel.py", p, "--dwell", "4.096", "--null", "5")
    rows = re.findall(r"^\s+(\d+)~\d+\s+([0-9.]+)\(", out, re.M)
    vals = [float(v) for _, v in rows]
    check("m4: 위상이 밀리면 고정 위상 적중률이 떨어진다 (첫 구간 높고 뒤 구간 낮음)",
          len(vals) >= 5 and vals[0] > 0.85 and min(vals[3:]) < 0.6, out)

    # 무작위: 시간대와 무관한 수신 — 적중률이 시뮬레이션 우연 95% 안
    for dur in (60, 280):
        g = [(37, rnd.uniform(0, dur)) for _ in range(int(dur * 3))]
        p = beacon_run(tmp, f"20261006_10{dur:03d}0", g)
        out = run("m4_channel.py", p, "--null", "20")
        m = re.search(r"행 가중 평균 ([0-9.]+)\s+vs 우연 평균 ([0-9.]+) · 95% ([0-9.]+)", out)
        ok = m is not None and float(m.group(1)) <= float(m.group(3)) + 0.02
        check(f"m4: 무작위 {dur}s — 적중률이 시뮬레이션 우연 수준 안", ok, out)

    # 290 s 넘은 행은 뺀다
    g = list(slotted(37, 4.096, 0.0, 0, 280, rnd)) + [(37, rnd.uniform(300, 360)) for _ in range(50)]
    p = beacon_run(tmp, "20261006_102000", g)
    out = run("m4_channel.py", p, "--dwell", "4.096", "--null", "3")
    check("m4: 스캔 시작 후 290 s 넘은 행을 뺀다", "넘은 행 뺌: ch37 50" in out, out)


def test_events_view(tmp):
    st = "20260930_100000"
    with open(os.path.join(tmp, f"events_{st}.csv"), "w") as f:
        f.write(EH + "\n")
        f.write(erow(3, "session_start", "RAW") + "\n")
        rows = 0
        for k in range(1, 61):
            rows += 1500 if k * 10 <= 300 else 20
            f.write(erow(k * 10000, "tick", "", rows) + "\n")
        f.write(erow(600500, "session_stop", "user", rows) + "\n")
    out = run("events_view.py", os.path.join(tmp, f"events_{st}.csv"))
    check("events_view: 300 s 급락을 짚는다 (마지막 0.5 s 구간에 속지 않음)", "가장 큰 낙폭  310s" in out, out)


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as tmp:
        test_b1(tmp)
        test_tag_check(tmp)
        test_m4(tmp)
        test_events_view(tmp)
    bad = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(bad)}/{len(results)} PASS")
    sys.exit(1 if bad else 0)
