#!/usr/bin/env python3
"""
WELCHER BLOCKER WAR WIRKLICH BINDEND - Gegenlauf auf demselben Schnappschuss.

WOZU: eine Haeufigkeitsliste der Blockgruende ist KEINE kausale Gewichtung.
Wer zuerst greift, verdeckt alle nachfolgenden. Ein Zyklus, der als
GUARD_FLOOR erscheint, kann nach dessen Lockerung sofort am TAIL scheitern -
und dann bringt die Lockerung nichts. "38x Guard gegen 12x Tail" sagt ueber
die Ursache also nichts.

VIER VARIANTEN, alle aus DENSELBEN aufgezeichneten Zahlen:

  1 Referenz          wie gelaufen
  2 Schwanz-Haupt     Schwanz rechnet gegen die Hauptbahn statt gegen das
                      Minimum aus Haupt und Bremse
  3 Guard-Haupt       Guard prueft gegen die Hauptbahn, Schwanz unveraendert
  4 beides

WAS DAS WERKZEUG BEANTWORTEN KANN, exakt und ohne Simulation: ob ein Zyklus
unter der Variante noch geblockt waere - und welcher Blocker dann uebernimmt.
Die Zahlen dafuer stehen alle im Trail (beide Bahnen werden seit C7 getrennt
mitgefuehrt).

WAS ES NICHT KANN, und das haelt es auch durch: sobald eine Variante EINEN
Zyklus freigibt, floss dort Insulin, das es real nie gab - ab da laeuft die
Wirklichkeit auseinander, und jede weitere Zeile waere ein Counterfactual.
Deshalb meldet der Bericht den ERSTEN abweichenden Zyklus als
Divergenzpunkt und beziffert danach nichts mehr. "Was waere gewesen" ist
nicht messbar; "welcher Riegel haette an dieser Stelle noch gehalten" schon.

DESHALB HEISST DIE ZAHL "gate-eligible" UND NICHT "dosiert". Sie sagt: in
diesem AUFGEZEICHNETEN Referenz-Schnappschuss haette dieses Gate nicht
geblockt. Sie sagt NICHT, dass die Variante so viele Dosen abgegeben haette -
nach der ersten zusaetzlichen Dosis waeren IOB und Aktivitaet andere, damit
die BGI-Bereinigung und `r`, damit Guard- und Schwanzbahnen, damit
Transporthaftung, Kandidaten und Budgets. Alle Schnappschuesse danach
beschreiben eine Welt, die es in der Variante nicht mehr gaebe.

Und `predAtRelease > target` ist ein BEDARFSINDIKATOR, kein Nachweis, dass
eine pumpenschrittgrosse Dosis dort sicher gewesen waere. Die Kandidatensuche
haette weiterhin ihr eigenes Urteil gefaellt.

Aufruf:
    python tools/fuse/blocker_entmaskieren.py <trail.jsonl> [--marker <ms>]
"""
from __future__ import annotations

import json
import sys
import time
from collections import Counter

MIN = 60_000
FENSTER_MIN = int(__import__('os').environ.get('FUSE_FENSTER_MIN','95'))


def lade(pfad):
    aus = []
    for z in open(pfad, encoding='utf-8', errors='ignore'):
        z = z.strip()
        if z.startswith('{'):
            try:
                aus.append(json.loads(z))
            except json.JSONDecodeError:
                pass
    aus.sort(key=lambda d: d.get('computeTs') or 0)
    return aus


def schwanz_headroom(lower_bg, floor, isf_tail, recovery, existing):
    """Dieselbe Rechnung wie TailLiability - eine zweite Fassung waere eine
    zweite Wahrheit. Der unphysiologische Zweig (Bahn unter dem Boden) liefert
    dort headroom = -existing; das bilden wir nach, sonst sieht eine
    abgestuerzte Bahn wie ein grosses negatives Budget aus statt wie eine
    Sperre."""
    if lower_bg is None or isf_tail in (None, 0):
        return None
    if lower_bg < 50.0:                       # PHYSIOLOGICAL_FLOOR_MGDL
        return -(existing or 0.0)
    return (lower_bg - floor) / isf_tail + (recovery or 0.0) - (existing or 0.0)


def hat_defizit(d):
    """Gate-unabhaengiges Defizit: Prognose am Freigabehorizont ueber dem Ziel.

    Bewusst NICHT insulinReqU - das ist auf geblockten Zyklen per Konstruktion
    0 und wuerde die Maskierung reproduzieren statt sie aufzuloesen."""
    dec = d.get('decision') or {}
    st = d.get('state') or {}
    pred = dec.get('predAtReleaseMgdl')
    ziel = st.get('targetMgdl')
    return pred is not None and ziel is not None and pred > ziel


def auswerten(saetze, marker):
    fenster = [d for d in saetze if marker <= (d.get('computeTs') or 0) <= marker + FENSTER_MIN * MIN]
    if not fenster:
        print('keine Zyklen im Fenster')
        return

    varianten = [
        ('1 Referenz', False, False),
        ('2 Schwanz-Haupt', False, True),
        ('3 Guard-Haupt', True, False),
        ('4 beides', True, True),
    ]

    # ---- Erst die Frage VOR allen Riegeln: gab es ueberhaupt Bedarf? -------
    # BEDARF NUR AUS EINER GATE-UNABHAENGIGEN GROESSE.
    #
    # `insulinReqU` taugt dafuer NICHT: FuseController baut eine geblockte
    # Entscheidung mit insulinReqU = 0.0 (GUARD_FLOOR und TAIL, Zeilen 604/621).
    # Die Null ist dort FOLGE des Riegels, nicht Aussage ueber Bedarf - wer sie
    # als 'kein Bedarf' liest, schliesst im Kreis und schreibt genau die
    # Maskierung fest, die er aufloesen will. Erste Fassung dieses Skripts hat
    # genau das getan.
    #
    # predAtReleaseMgdl bleibt dagegen auch auf geblockten Zyklen gefuellt. Ein
    # Defizit heisst: die Bahn am Freigabehorizont liegt ueber dem Ziel.
    ohne_bedarf = sum(1 for d in fenster if not hat_defizit(d))
    print(f'{len(fenster)} Zyklen im Fenster, davon {ohne_bedarf} ohne Defizit')
    print('(Bahn am Freigabehorizont NICHT ueber dem Ziel). Dort aendert das')
    print('Lockern eines Riegels nichts - unabhaengig davon gemessen, welcher')
    print('Blocker drangestanden hat.')
    print()
    print('LESEART: "gate-eligible" = dieses Gate haette in DIESEM aufgezeichneten')
    print('Schnappschuss nicht geblockt. NICHT: so viele Dosen waeren geflossen.')
    print('Ab dem Divergenzpunkt beschreibt jeder weitere Schnappschuss eine Welt,')
    print('die es in der Variante nicht mehr gaebe (anderes IOB, anderes r,')
    print('andere Bahnen). Belastbar ist der Divergenzpunkt, nicht die Summe.')
    print()

    for name, guard_haupt, tail_haupt in varianten:
        gate_eligible = 0
        uebernehmer = Counter()
        divergenz = None
        for d in fenster:
            dec = d.get('decision') or {}
            t = d.get('tail') or {}
            tl = d.get('tailLower') or {}
            pv = (d.get('policy') or {}).get('values') or {}
            floor = pv.get('guardFloorMgdl') or 70.0

            min_lower = dec.get('minLowerMainMgdl') if guard_haupt else dec.get('minLowerMgdl')
            guard_blockt = min_lower is not None and min_lower < floor

            if tail_haupt:
                hr = schwanz_headroom(tl.get('mainUncondMgdl'), pv.get('tailFloorMgdl') or 70.0,
                                      t.get('isfTailMgdlPerU'), pv.get('tailRecoveryU'),
                                      t.get('existingU'))
            else:
                hr = t.get('headroomU')
            tail_blockt = hr is not None and hr <= 0.0

            hat_bedarf = hat_defizit(d)
            real_geflossen = ((d.get('rt') or {}).get('units') or 0) > 0

            if guard_blockt:
                uebernehmer['GUARD_FLOOR'] += 1
            elif tail_blockt:
                uebernehmer['TAIL'] += 1
            elif not hat_bedarf:
                uebernehmer['KEIN_BEDARF'] += 1
            else:
                gate_eligible += 1
                if divergenz is None and not real_geflossen:
                    divergenz = d

        print(f'{name}')
        print(f'   gate-eligible: {gate_eligible}')
        for k, v in uebernehmer.most_common():
            print(f'   {v:>3}  {k}')
        if divergenz is not None:
            tv = (divergenz['computeTs'] - marker) / MIN
            print(f'   >> DIVERGENZPUNKT bei T+{tv:.0f} min '
                  f'({time.strftime("%H:%M", time.localtime(divergenz["computeTs"] / 1000))}): '
                  f'hier waere zum ersten Mal geflossen, was real nicht floss.')
            print(f'      Ab dieser Zeile ist der Rest ein Counterfactual und wird')
            print(f'      NICHT beziffert - die Bahn haette sich ab hier geaendert.')
        elif name != '1 Referenz':
            print('   >> kein Divergenzpunkt: diese Variante haette das Loch NICHT geoeffnet.')
        print()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    saetze = lade(sys.argv[1])
    if '--marker' in sys.argv:
        marker = int(sys.argv[sys.argv.index('--marker') + 1])
    else:
        ts = [(d.get('state') or {}).get('markerArmedTs') or 0 for d in saetze]
        ts = sorted({t for t in ts if t > 0})
        if not ts:
            print('kein Marker im Trail')
            return 1
        marker = ts[-1]
    print(f'Marker {time.strftime("%H:%M", time.localtime(marker / 1000))}\n')
    auswerten(saetze, marker)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
