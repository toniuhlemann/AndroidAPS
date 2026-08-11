#!/usr/bin/env python3
"""
AUSWERTUNG EINES MAHLZEITENLAUFS aus dem FUSE-Trail.

Er beantwortet genau die sechs Fragen, die Toni am 11.08.2026 fuer den ersten
sauberen Lauf gestellt hat - und keine mehr:

  1. publizierte Menge bei T+5/10/15/30
  2. Ende des Prime-Fensters (wann und mit welchem Grund)
  3. welcher Kanal danach uebernimmt
  4. Blockgruende bis T+90
  5. verbleibende Marker- und Onset-Huelle
  6. Luecke oder Doppelfinanzierung am Uebergang

WARUM ALS SKRIPT UND NICHT IM APK: der Build ist bis zum Abschluss des Laufs
eingefroren (Tag fuse-freeze-mahlzeitenlauf-1). Alles, was hier steht, liest
ausschliesslich fuse_state_history.jsonl - die App merkt davon nichts.

WAS ER NICHT KANN, ehrlich vorweg: er misst, was FUSE ANGEFORDERT und was das
Pumpen-Gate DURCHGELASSEN hat. Auf dem Testgeraet fliesst kein Insulin; die
BG-Kurve stammt vom Produktivgeraet und ist von DESSEN Insulin geformt. Eine
Wirkungsaussage ist aus diesem Lauf nicht zu holen, nur eine ueber Zeitpunkt,
Menge und Verteilung der Anforderung.

Aufruf:
    python tools/fuse/mahlzeitenlauf.py <trail.jsonl> [--marker <epoch_ms>]

Ohne --marker wird die LETZTE Markerepisode im Trail genommen.
"""
from __future__ import annotations

import json
import sys
from collections import Counter

MIN = 60_000


def lade(pfad: str) -> list[dict]:
    """Nur vollstaendige JSON-Zeilen. Ein abgeschnittener Anfang ist normal -
    der Trail wird per `tail -c` geholt, die erste Zeile ist dann halb."""
    saetze = []
    for zeile in open(pfad, encoding='utf-8', errors='ignore'):
        zeile = zeile.strip()
        if not zeile.startswith('{'):
            continue
        try:
            saetze.append(json.loads(zeile))
        except json.JSONDecodeError:
            continue
    saetze.sort(key=lambda d: d.get('computeTs') or 0)
    return saetze


def markerepisoden(saetze: list[dict]) -> list[int]:
    """Alle unterschiedlichen Markerzeitpunkte, aufsteigend."""
    gesehen = []
    for d in saetze:
        ts = (d.get('state') or {}).get('markerArmedTs') or 0
        if ts > 0 and ts not in gesehen:
            gesehen.append(ts)
    return sorted(gesehen)


def f(x, n=2):
    return '-' if x is None else f'{x:.{n}f}'


def auswerten(saetze: list[dict], marker: int) -> None:
    fenster = [d for d in saetze if marker <= (d.get('computeTs') or 0) <= marker + 95 * MIN]
    if not fenster:
        print('keine Zyklen im Fenster - falscher Marker oder Trail zu kurz')
        return

    print(f'MARKER {marker}  ({len(fenster)} Zyklen bis T+95)')
    # DER WAECHTER GEGEN EINEN UNSAUBEREN LAUF. `build.head` ist die
    # git-describe-Kennung des laufenden Standes - genau das, was sich beim
    # Flashen mitten im Fenster aendert. Ohne diese Zeile misst man eine
    # Mischung und merkt es nicht.
    builds = {(d.get('build') or {}).get('head') for d in fenster}
    builds = {b for b in builds if b}
    unbestaetigt = [d for d in fenster if (d.get('build') or {}).get('committed') is False]
    print(f'BUILD  {" / ".join(sorted(map(str, builds))) or "unbekannt"}')
    if len(builds) > 1:
        print('  !! MEHR ALS EIN BUILD IM FENSTER - DER LAUF IST NICHT SAUBER.')
        print('     Gemessen wird dann eine Mischung aus zwei Staenden, und die',
              'Verteilung')
        print('     gehoert keinem von beiden.')
    if unbestaetigt:
        print(f'  !! {len(unbestaetigt)} Zyklen aus einem NICHT committeten Baum -',
              'nicht reproduzierbar.')
    print()

    # ---- 1) publizierte Menge ueber die Zeit ----------------------------
    print('1) PUBLIZIERTE MENGE')
    print('   Angefordert = decision.smbU, publiziert = rt.units (nach Gate).')
    kum_a = kum_p = 0.0
    marken = [5, 10, 15, 30, 45, 60, 90]
    naechste = 0
    for d in fenster:
        t = ((d.get('computeTs') or 0) - marker) / MIN
        kum_a += (d.get('decision') or {}).get('smbU') or 0.0
        kum_p += ((d.get('rt') or {}).get('units')) or 0.0
        while naechste < len(marken) and t >= marken[naechste]:
            print(f'   T+{marken[naechste]:>2} min   angefordert {kum_a:5.2f} U   publiziert {kum_p:5.2f} U')
            naechste += 1
    for rest in marken[naechste:]:
        print(f'   T+{rest:>2} min   (Trail endet vorher)')
    print()

    # ---- 2) Ende des Prime-Fensters -------------------------------------
    print('2) PRIME-FENSTER')
    letzter_aktiv = None
    ende = None
    for d in fenster:
        p = (d.get('drive') or {}).get('prime') or {}
        t = ((d.get('computeTs') or 0) - marker) / MIN
        if p.get('active'):
            letzter_aktiv = (t, p)
        elif letzter_aktiv and ende is None:
            ende = (t, p.get('reason'))
    if letzter_aktiv:
        print(f'   zuletzt aktiv   T+{letzter_aktiv[0]:.0f} min  (floorU {f(letzter_aktiv[1].get("floorU"))} U)')
    else:
        print('   NIE aktiv - der Prime-Kanal hat in diesem Lauf nichts freigegeben')
    if ende:
        print(f'   Ende            T+{ende[0]:.0f} min  Grund {ende[1]}')
    print()

    # ---- 3) welcher Kanal uebernimmt -------------------------------------
    print('3) KANAL NACH DEM PRIME-FENSTER')
    if ende:
        danach = [d for d in fenster if ((d.get('computeTs') or 0) - marker) / MIN >= ende[0]]
        mengen = [d for d in danach if ((d.get('decision') or {}).get('smbU') or 0) > 0]
        if not mengen:
            print(f'   KEINE Abgabe nach T+{ende[0]:.0f} min bis zum Ende des Fensters.')
            print('   Das ist die Luecke, um die es bei der Uebergabe geht.')
        else:
            for d in mengen[:12]:
                t = ((d.get('computeTs') or 0) - marker) / MIN
                dec = d.get('decision') or {}
                on = (d.get('drive') or {}).get('onset') or {}
                print(f'   T+{t:5.1f}  {f(dec.get("smbU"))} U  limit={dec.get("bindingLimit")}'
                      f'  onset={"an" if on.get("active") else "aus"}({on.get("reason")})')
            if len(mengen) > 12:
                print(f'   ... und {len(mengen) - 12} weitere')
    else:
        print('   (kein Prime-Ende im Fenster)')
    print()

    # ---- 4) Blockgruende --------------------------------------------------
    print('4) BLOCKGRUENDE BIS T+90')
    zaehler = Counter()
    for d in fenster:
        dec = d.get('decision') or {}
        if (dec.get('smbU') or 0) <= 0:
            zaehler[dec.get('block') or '?'] += 1
    for grund, n in zaehler.most_common():
        print(f'   {n:>3} Zyklen   {grund}')
    print()

    # ---- 5) verbleibende Huellen -----------------------------------------
    print('5) HUELLEN AM ENDE DES FENSTERS')
    letzte = fenster[-1]
    p = (letzte.get('drive') or {}).get('prime') or {}
    o = (letzte.get('drive') or {}).get('onset') or {}
    werte = (letzte.get('policy') or {}).get('values') or {}
    print(f'   Marker  Rest {f(p.get("remainingU"))} U von {f(werte.get("primeEnvelopeU"))} U   ({p.get("reason")})')
    print(f'   Onset   Rest {f(o.get("remainingU"))} U von {f(werte.get("onsetEnvelopeU"))} U   ({o.get("reason")})')
    print()

    # ---- 6) Luecke oder Doppelfinanzierung -------------------------------
    print('6) UEBERGANG')
    if not ende:
        print('   kein Uebergang im Fenster')
        return
    t_ende = ende[0]
    vor = sum((d.get('decision') or {}).get('smbU') or 0 for d in fenster
              if ((d.get('computeTs') or 0) - marker) / MIN < t_ende)
    nach = sum((d.get('decision') or {}).get('smbU') or 0 for d in fenster
               if ((d.get('computeTs') or 0) - marker) / MIN >= t_ende)
    print(f'   vor  dem Ende  {vor:.2f} U')
    print(f'   nach dem Ende  {nach:.2f} U')
    # DOPPELFINANZIERUNG heisst hier: die Onset-Huelle traegt nach dem Ende
    # Mengen, waehrend die Marker-Huelle noch Rest hat - beides waere fuer
    # DIESELBE Mahlzeit.
    o_rest = o.get('remainingU')
    o_voll = werte.get('onsetEnvelopeU')
    if nach > 0 and o_rest is not None and o_voll is not None and o_rest < o_voll - 1e-9:
        print(f'   PRUEFEN: nach dem Ende floss Insulin UND die Onset-Huelle ist')
        print(f'   angebrochen ({f(o_rest)} von {f(o_voll)} U). Zwei Huellen fuer eine')
        print(f'   Mahlzeit ist genau die Doppelfinanzierung, die der Vertrag')
        print(f'   ausschliessen soll.')
    elif nach == 0:
        erste_nach = [d for d in fenster if ((d.get('computeTs') or 0) - marker) / MIN >= t_ende]
        print(f'   LUECKE: {len(erste_nach)} Zyklen nach dem Prime-Ende ohne jede Abgabe.')


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    saetze = lade(sys.argv[1])
    if not saetze:
        print('keine lesbaren Datensaetze')
        return 1
    print(f'{len(saetze)} Zyklen im Trail\n')
    if '--marker' in sys.argv:
        marker = int(sys.argv[sys.argv.index('--marker') + 1])
    else:
        episoden = markerepisoden(saetze)
        if not episoden:
            print('kein Markerdruck im Trail')
            return 1
        if len(episoden) > 1:
            print(f'{len(episoden)} Markerepisoden gefunden, nehme die letzte.')
            print('Andere per --marker <epoch_ms>:', episoden)
            print()
        marker = episoden[-1]
    auswerten(saetze, marker)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
