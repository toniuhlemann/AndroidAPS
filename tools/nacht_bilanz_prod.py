# -*- coding: utf-8 -*-
"""Nacht-Basal-Bilanz aus den prod-Logs (Pixel 9, WLAN-adb).

Zieht die stuendlich rotierten AndroidAPS-Logs des Nachtfensters vom
prod-Handy (/sdcard/Documents/aapsLogs/) und rechnet minutengenau:
Profil-Basal (Plan) vs. tatsaechlich gesetzte TBRs (Ist) + SMBs + BG.

Das ist die Phase-A-Handrechnung des Sensitivity Observers auf ECHTEN
Daten (echtes Insulinbuch + echte Kurve) - Phantom-Regel-konform. Der
Test-Trail taugt dafuer NICHT (virtuelles Buch vs. reale Kurve).

Aufruf (morgens, prod per WLAN verbunden):
    python tools/nacht_bilanz_prod.py            # letzte Nacht 21:00-07:30
    python tools/nacht_bilanz_prod.py --datum 2026-08-08 --von 21 --bis 7.5
"""
import argparse
import bisect
import datetime as dt
import glob
import io
import os
import re
import subprocess
import sys
import tempfile

ADB = r"C:\Users\toniu\AppData\Local\Android\Sdk\platform-tools\adb.exe"
LOGDIR = "/sdcard/Documents/aapsLogs"


def prod_serial():
    out = subprocess.run([ADB, "devices", "-l"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "komodo" in line or "Pixel_9" in line:
            return line.split()[0]
    sys.exit("prod (Pixel 9 / komodo) nicht in 'adb devices' - WLAN-adb verbinden (Port-Scan 30000-50000 auf 192.168.178.20)")


def pull_hours(serial, tag_ende, von_h, bis_h, ziel):
    """Holt die Zip-Logs der Nachtstunden; fehlende Stunden werden gemeldet."""
    tag_start = tag_ende - dt.timedelta(days=1)
    stunden = [(tag_start, h) for h in range(von_h, 24)] + \
              [(tag_ende, h) for h in range(0, int(bis_h) + 1)]
    fehlend = []
    for tag, h in stunden:
        name = f"AndroidAPS._{tag:%Y-%m-%d}_{h:02d}.log.zip"
        r = subprocess.run([ADB, "-s", serial, "pull", f"{LOGDIR}/{name}", ziel],
                           capture_output=True, text=True)
        if r.returncode != 0:
            fehlend.append(name)
    if fehlend:
        print("FEHLENDE Stunden (Rotation noch nicht durch? Lueckenstunde?):")
        for f in fehlend:
            print("  ", f)
    import zipfile
    for z in glob.glob(os.path.join(ziel, "*.zip")):
        with zipfile.ZipFile(z) as zf:
            zf.extractall(ziel)


def parse(ziel):
    events = []
    for fn in sorted(glob.glob(os.path.join(ziel, "AndroidAPS._*.log"))):
        m = re.search(r"_(\d{4}-\d\d-\d\d)_(\d\d)", os.path.basename(fn))
        date = m.group(1)
        for line in io.open(fn, encoding="utf-8", errors="replace"):
            tm = re.match(r"(\d\d:\d\d:\d\d)\.\d+", line)
            if not tm:
                continue
            ts = dt.datetime.fromisoformat(date + " " + tm.group(1))
            if "invokeInner" in line and "Profile:" in line and "current_basal=" in line:
                cb = float(re.search(r"current_basal=([\d.]+)", line).group(1))
                events.append((ts, "prof", {"cb": cb}))
            elif "invokeInner" in line and "Result: RT(" in line:
                d = {}
                for k, pat in [("bg", r"\bbg=([\d.]+)"), ("rate", r"rate=([\d.]+)"),
                               ("dur", r"duration=(\d+)"), ("iob", r"\bIOB=([\-\d.]+)"),
                               ("units", r"units=([\d.]+)")]:
                    mm = re.search(pat, line)
                    if mm:
                        d[k] = float(mm.group(1))
                mm = re.search(r"Dev: (-?\d+)", line)
                if mm:
                    d["dev"] = float(mm.group(1))
                events.append((ts, "res", d))
    events.sort(key=lambda e: e[0])
    return events


def bilanz(events, start, ende):
    res = [(t, d) for t, k, d in events if k == "res" and "rate" in d and "dur" in d]
    prof = [(t, d["cb"]) for t, k, d in events if k == "prof"]
    smb = [(t, d["units"]) for t, k, d in events if k == "res" and d.get("units")]
    rts = [x[0] for x in res]
    pts = [x[0] for x in prof]

    per_hour, luecken = {}, 0
    t = start
    while t < ende:
        i = bisect.bisect_right(rts, t) - 1
        j = bisect.bisect_right(pts, t) - 1
        h = per_hour.setdefault(t.strftime("%d.%H"), dict(plan=0.0, act=0.0, zero=0,
                                                          bgs=[], devs=[], iobs=[]))
        if i >= 0 and j >= 0:
            ts_r, d = res[i]
            age = (t - ts_r).total_seconds() / 60
            cb = prof[j][1]
            rate = d["rate"] if age <= d["dur"] else cb  # TBR abgelaufen -> Profil
            h["plan"] += cb / 60
            h["act"] += rate / 60
            if rate < 0.05:
                h["zero"] += 1
            if age <= 6:
                h["bgs"].append(d.get("bg"))
                h["devs"].append(d.get("dev"))
                h["iobs"].append(d.get("iob"))
        else:
            luecken += 1
        t += dt.timedelta(minutes=1)

    print(f"{'Stunde':7}{'BG':>6}{'Dev':>5}{'IOB':>7}{'Plan U':>8}{'Ist U':>8}{'Diff U':>8}{'0-Temp':>7}")
    tp = ta = tz = 0
    for hr in sorted(per_hour):
        h = per_hour[hr]

        def mean(xs):
            xs = [x for x in xs if x is not None]
            return sum(xs) / len(xs) if xs else float("nan")

        print(f"{hr:7}{mean(h['bgs']):6.0f}{mean(h['devs']):5.0f}{mean(h['iobs']):7.2f}"
              f"{h['plan']:8.2f}{h['act']:8.2f}{h['act'] - h['plan']:+8.2f}{h['zero']:7d}")
        tp += h["plan"]; ta += h["act"]; tz += h["zero"]
    print(f"{'SUMME':7}{'':18}{tp:8.2f}{ta:8.2f}{ta - tp:+8.2f}{tz:7d}")
    if luecken:
        print("Minuten ohne Loop-Daten:", luecken)

    smbs = [(t, u) for t, u in smb if start <= t < ende]
    print(f"\nSMBs: {len(smbs)} Stueck, {sum(u for _, u in smbs):.2f} U gesamt")
    for t, u in smbs:
        print(f"  {t:%H:%M}  {u:.2f} U")

    bgs = [(t, d["bg"]) for t, k, d in events if k == "res" and "bg" in d and start <= t < ende]
    if bgs:
        lo = min(bgs, key=lambda x: x[1])
        hi = max(bgs, key=lambda x: x[1])
        print(f"\nBG min {lo[1]:.0f} um {lo[0]:%H:%M}, max {hi[1]:.0f} um {hi[0]:%H:%M}")
        # Unter-70-Episoden clustern (Luecke >10 min = neue Episode)
        eps, cur = [], []
        for t, b in bgs:
            if b < 70:
                if cur and (t - cur[-1][0]).total_seconds() > 600:
                    eps.append(cur); cur = []
                cur.append((t, b))
        if cur:
            eps.append(cur)
        if eps:
            print("Unter-70-Episoden:")
            for ep in eps:
                mn = min(b for _, b in ep)
                print(f"  {ep[0][0]:%H:%M}-{ep[-1][0]:%H:%M}  min {mn:.0f}  ({len(ep)} Loops)")
        else:
            print("Unter 70: nie")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--datum", help="Morgen-Datum (YYYY-MM-DD), Default heute")
    ap.add_argument("--von", type=int, default=21, help="Start Vorabend, volle Stunde (Default 21)")
    ap.add_argument("--bis", type=float, default=7.5, help="Ende am Morgen (Default 7.5 = 07:30)")
    ap.add_argument("--serial", help="adb-Serial von prod (Default: automatisch komodo)")
    a = ap.parse_args()

    tag_ende = dt.date.fromisoformat(a.datum) if a.datum else dt.date.today()
    serial = a.serial or prod_serial()
    ziel = tempfile.mkdtemp(prefix="nacht_prod_")
    print(f"prod {serial}, Nacht {tag_ende - dt.timedelta(days=1):%d.%m.} {a.von}:00 -> {tag_ende:%d.%m.} "
          f"{int(a.bis):02d}:{int(a.bis % 1 * 60):02d}, Arbeitsordner {ziel}")
    pull_hours(serial, tag_ende, a.von, a.bis, ziel)
    events = parse(ziel)
    if not events:
        sys.exit("Keine Log-Ereignisse gefunden")
    start = dt.datetime.combine(tag_ende - dt.timedelta(days=1), dt.time(a.von))
    ende = dt.datetime.combine(tag_ende, dt.time(int(a.bis), int(a.bis % 1 * 60)))
    bilanz(events, start, ende)


if __name__ == "__main__":
    main()
