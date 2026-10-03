#!/usr/bin/env python3
"""
분석 스크립트 반례 시험 — 정답을 아는 합성 데이터로 각 스크립트가 맞는 답을 내는지 본다.

    python3 scripts/tests/test_scripts.py        # 전부 PASS 여야 한다

APP_DESIGN 6.2(3~5) 사양과, 9/29 검토에서 실패했던 경우를 그대로 만든다:
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


def raw_capture(path, tag_on, seed, tag_rssi=-40):
    """태그(FD5A, tag_rssi) + 같은 식별자 기기 8대(-80, 행 많음) + 가까운 폰(004C, -55) + 잡음 기기들"""
    rnd = random.Random(seed)
    with open(path, "w") as f:
        f.write(RAWH + "\n")
        for i in range(4000):
            t = i * 15
            k = i % 10
            if k == 0 and (tag_on or tag_rssi < -60):
                a, r, d, m, leg = "4A:AA:AA:AA:AA:01", tag_rssi + rnd.randint(-2, 2), "FD5A", "0075", "0"
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
    check("b1_find: 결론을 사실 세 줄로 — 후보 식별자 / 대조에서 사라짐 / 필터 일치",
          re.search(r"후보 식별자\s+data=FD5A", out) is not None and re.search(r"대조 캡처에서 사라짐\s+예", out) is not None
          and re.search(r"TAG 필터\(FD5A/FD59\)와 일치\s+예", out) is not None and "고칠 근거" not in out, out)
    check("b1_find: 태그의 확장 광고(레거시 0%)를 보고한다", "광고 형식 확장만" in out, out)
    check("b1_find: 순위는 행 10개 이상 주소만, 기본 위 3개", "행 10개 이상" in out
          and len(re.findall(r"^  [0-9A-F]{2}:[0-9A-F:]{14}\s+-?\d+", out, re.M)) == 3, out)
    out2 = run("b1_find.py", on)
    check("b1_find: --control 없으면 판정 미확정이라고 말한다", "판정 미확정" in out2, out2)
    # 치운 태그가 다른 방에서 약하게(-95) 계속 들려도 '사라짐'으로 본다
    weak = os.path.join(tmp, "raw_20261004_100400.csv")
    raw_capture(weak, False, 5, tag_rssi=-95)
    out3 = run("b1_find.py", on, "--control", weak)
    check("b1_find: 치운 태그가 약하게 남아도 후보로 찾는다",
          "[태그 후보]" in out3 and "4A:AA:AA:AA:AA:01" in out3.split("[태그 후보]")[1].splitlines()[0], out3)


def tag_run(tmp, st, other_rssi, other_every=1, countdown=10):
    """우리 태그(-60, 70 s 에 주소 교체, 가림 구간 -68) + 같은 시각의 다른 FD5A 기기 (한 주소, other_rssi).
    카운트다운 countdown 초 → 런 시계 0, 30 s 가림 · 90 s 나옴 · 120 s 끝 (앱이 쓰는 이벤트 그대로)"""
    c0 = countdown * 1000
    with open(os.path.join(tmp, f"events_{st}.csv"), "w") as f:
        f.write(EH + "\n")
        f.write(erow(3, "scan_start", "1", detail=f"pre_ns={BOOT} post_ns={BOOT + 2_000_000}") + "\n")
        f.write(erow(c0, "timer", str(countdown), detail="run_clock_s=0 countdown_end") + "\n")
        f.write(erow(c0 + 30000, "timer", "30", detail="run_clock_s=30") + "\n")
        f.write(erow(c0 + 30000, "block_in_planned", "1", detail="pos=mid") + "\n")
        f.write(erow(c0 + 90000, "timer", "90", detail="run_clock_s=90") + "\n")
        f.write(erow(c0 + 90000, "block_out_planned", "1", detail="pos=mid") + "\n")
        f.write(erow(c0 + 120000, "timer", "120", detail="run_clock_s=120") + "\n")
        f.write(erow(c0 + 121000, "run_flag", "invalid", detail="reason=other memo=test") + "\n")
    json.dump({"mode": "TAG", "run": {"duration_s": 120}, "scan": {"legacy": False, "phy": "LE_1M"},
               "precheck": {"alarm_volume": 5, "alarm_max": 7, "smartthings_closed": True}},
              open(os.path.join(tmp, f"meta_{st}.json"), "w"))
    rnd = random.Random(3)
    path = os.path.join(tmp, f"tag_{st}.csv")
    with open(path, "w") as f:
        f.write("rx_wall_ms,rx_elapsed_ms,ts_nanos,address,rssi,is_legacy,primary_phy,secondary_phy,adv_sid,"
                "svc_uuid,svc_data_hex,scan_seq,cond\n")
        t, i = 500, 0
        while t < c0 + 120000:
            rc = (t - c0) / 1000
            r = -60 + (-8 if 30 <= rc < 90 else 0) + rnd.randint(-1, 1)
            a = "5A:11:11:11:11:11" if t < c0 + 60000 else "6B:22:22:22:22:22"     # 주소 교체
            f.write(f"0,{t + 15},{BOOT + t * 1_000_000},{a},{r},0,1,2,3,FD5A,aa,1,c\n")
            if i % other_every == 0:
                f.write(f"0,{t + 20},{BOOT + (t + 5) * 1_000_000},9C:99:99:99:99:99,"
                        f"{other_rssi + rnd.randint(-1, 1)},0,1,2,3,FD5A,bb,1,c\n")
            t += 2000; i += 1
    return path


def seg(out, name):
    m = re.search(r"^\s+" + name + r"\s+[\d~]+\s+s\s+n=\s*\d+\s+평균\s+(-?\d+\.\d)", out, re.M)
    return float(m.group(1)) if m else None


def test_tag_check(tmp):
    ok_rssi = lambda out: (seg(out, r"무가림\(앞\)") is not None and seg(out, "가림") is not None
                           and abs(seg(out, r"무가림\(앞\)") + 60) < 1.5 and abs(seg(out, "가림") + 68) < 1.5)

    # 다른 FD5A 기기가 같은 시각에 같은 빈도로 섞여도(-90) 가장 센 주소 + 주소 교체로 우리 태그만 쓴다
    p = tag_run(tmp, "20261005_141200", -90)
    out = run("tag_check.py", p)
    check("tag_check: [0] 주소별 표(행·pkt/s·중앙값·처음·마지막)를 먼저 낸다",
          "[0] 주소별" in out and re.search(r"9C:99:99:99:99:99\s+\d+\s+[\d.]+\s+-9\d", out) is not None, out)
    check("tag_check: 다른 기기를 빼고 주소 교체를 이어 구간 RSSI (앞 ≈ -60, 가림 ≈ -68)",
          "!! 모호" not in out and ok_rssi(out) and "바뀐 횟수 1" in out, out)

    # 같은 시각에 비슷한 세기(-61)의 다른 기기 → 세기로 못 가른다 → 모호 경고, --addr 로 해결
    p = tag_run(tmp, "20261005_141500", -61)
    out0 = run("tag_check.py", p)
    check("tag_check: 같은 시각 비슷한 세기의 기기가 있으면 '모호'라고 경고한다", "!! 모호" in out0, out0)
    out = run("tag_check.py", p, "--addr", "5A:11:11:11:11:11,6B:22:22:22:22:22")
    check("tag_check: --addr 로 준 주소만 쓰고 9C 는 다른 기기로 뺀다",
          ok_rssi(out) and "다른 기기 1개" in out and "!! 모호" not in out, out)

    # 카운트다운 길이가 바뀌어도 countdown_end 로 런 시계 0 을 찾는다 (value="10" 에 기대지 않음)
    p = tag_run(tmp, "20261005_142000", -90, countdown=15)
    out = run("tag_check.py", p)
    check("tag_check: 카운트다운 15 s 런도 구간을 맞게 자른다", ok_rssi(out), out)
    check("tag_check: [5] 는 '스택 타임스탬프 → 앱 콜백 지연', [7] 은 사유·메모와 precheck 를 읽는다",
          "스택 타임스탬프 → 앱 콜백 지연" in out and "reason=other memo=test" in out and "알람 5/7" in out, out)


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

    # --fit-on: ch37 런에서 D 를 찾고 ch38 런을 채점 (찾은 데이터 ≠ 채점 데이터 → 우연 1/3)
    p37 = beacon_run(tmp, "20261006_103000", list(slotted(37, 4.096, 0.0, 0, 280, rnd)))
    p38 = beacon_run(tmp, "20261006_103500", list(slotted(38, 4.096, 0.0, 0, 280, rnd)))
    out = run("m4_channel.py", p38, "--fit-on", p37)
    m = re.search(r"\[1\][^\n]*\n\s+ch38: ([0-9.]+)", out)
    check("m4: --fit-on 다른 런에서 찾은 D 로 채점 — 우연 0.333, ch38 적중 ≈ 1",
          "다른 런" in out and "우연 = 0.333" in out and m is not None and float(m.group(1)) > 0.9
          and "[3]" not in out, out)

    # 섞은 시각열 우연 수준의 오경보율 — 채널과 무관한 60 s 런 20개 중 '95% 초과'는 기대 1개 (5%)
    fp = 0
    for k in range(20):
        r2 = random.Random(100 + k)
        g = [(37, r2.uniform(0, 60)) for _ in range(180)]
        p = beacon_run(tmp, f"20261007_1{k:02d}000", g)
        out = run("m4_channel.py", p, "--null", "40")
        m = re.search(r"행 가중 평균 ([0-9.]+)\s+vs 우연 평균 ([0-9.]+) · 95% ([0-9.]+)", out)
        fp += m is not None and float(m.group(1)) > float(m.group(3))
    check(f"m4: 무관한 60 s 런 20개 중 우연 95% 초과 {fp}개 (기대 1, 3 이하면 통과)", fp <= 3)

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
