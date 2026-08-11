# Referenz-Mahlzeitenlauf, 11.08.2026 — Flammkuchen

**Build durchgehend `fuse-freeze-mahlzeitenlauf-1-4-g9bab996c9d`**, eingefroren von
19:24 bis zum Ende. Erster Lauf ohne Build-Wechsel im Fenster — der Wächter im
Auswerter meldet keinen zweiten Stand.

Aufbau: Pixel 6 Pro, VirtualPump, offene Schleife. Die BG-Kurve ist vom
Produktivgerät (autoISF) geformt; FUSEs Abgaben wirken nicht.

## Was gemessen wurde

| | FUSE | autoISF |
|---|---|---|
| gesamt | **4,85 U** / 251 min | ~8,05 U (Stand T+190) |
| erste 15 min | **3,00 U** | 0,00 U (erste Abgabe T+12) |

Verteilung im Prime-Fenster exakt gleichmäßig: 1,20 / 2,20 / 3,00 U bei
T+5 / T+10 / T+15. Prime endete planmäßig bei T+16 (`WINDOW_OVER`).

Kurve: 100 → **166** (T+60) → 108 (T+120) → **155** (T+180) → **102** (T+240).
Zwei Wellen, weiche Landung, kein Unterschwinger.

## Der Kernbefund

**41 Minuten Nullfenster von T+16 bis T+55**, im steilsten Anstieg
(BG 102 → 168, `r` bis 3,3 mg/dl/min). Nicht wegen fehlender Erkennung —
`mealWindow` true, Ratio auf vollen 0,35 — sondern `GUARD_FLOOR`.

Ursache ist die eigene Frühdosis: 3,0 U drücken die Sicherheitsbahn unter den
Boden. **Die Frühdosis macht FUSE blind für den Anstieg, den sie vorwegnehmen
sollte.** Danach nur noch Krümel (0,05–0,15), durchgehend `tailHeadroom`-limitiert
— im Takt des Schwanzabbaus, nicht im Takt der Absorption.

Die Onset-Hülle blieb über den ganzen Lauf **unangetastet** (1,50 von 1,50 U):
sie ist ein *Vor*-Evidenz-Kanal und schließt bei `R_CONFIRMED`. Die Empfangsseite
nach bestätigtem Anstieg existiert nicht.

## Entmaskierung der Blocker

Gate-eligible-Zyklen (= dieses Gate hätte im aufgezeichneten Schnappschuss nicht
geblockt; **keine** Dosiszahl):

| Variante | gate-eligible | danach blockt | Divergenz |
|---|---|---|---|
| 1 Referenz | 35 | 169 Guard, 46 Tail | T+61 |
| 2 Schwanz gegen Hauptbahn | 63 | 169 Guard, 18 Tail | T+51 |
| 3 Guard gegen Hauptbahn | **35** | 139 Guard, 76 Tail | T+61 |
| 4 beides | 80 | 139 Guard, 31 Tail | T+51 |

**Variante 3 gewinnt nichts** — 35 bleibt 35, sie verschiebt nur 30 Blocks vom
Guard zum Schwanz. Reine Maskierung, sichtbar gemacht.

**Keine Variante öffnet das frühe Loch.** Die Divergenz wandert bestenfalls von
T+61 auf T+51 — das Loch beginnt bei T+16. Und selbst mit beiden Änderungen
blockt der Guard noch 139-mal. Das ist das Argument gegen die kleine
Bahnkorrektur und für den evidenzgetragenen Empfänger, der die Bahnannahme
selbst ändert.

## Was dieser Lauf NICHT hergibt

Die Mengen sind nicht vergleichbar. FUSE kennt autoISFs real wirkendes Insulin
nicht, seine eigenen virtuellen Dosen stehen dafür in der BGI-Bereinigung, ohne
zu wirken. Die gemessene Störung ist dadurch systematisch zu niedrig — grob um
zwei Einheiten Wirkung. Struktur und Zeitverlauf sind belastbar, die Zahlen nicht.

Dass autoISFs 8,05 U bei 102 mg/dl landeten, spricht gegen eine Überdosierung
dort. Ob FUSEs 4,85 U gereicht hätten, ist aus diesem Lauf **nicht** zu
beantworten: in einem geschlossenen Kreis wäre die Kurve höher gelaufen, FUSE
hätte mehr Störung gesehen und mehr angefordert.

## Rohdaten - NICHT im Repo

Der Trail enthaelt Gesundheitsdaten und gehoert nicht in ein Git-Repository,
auch nicht gepackt und auch nicht in einem privaten. Er liegt lokal:

    C:/Users/toniu/FUSE-Messungen/2026-08-11-mahlzeitenlauf/trail.jsonl.gz
    SHA-256  94004473f7c62940befc4bc1c2baf0edeb38b99331f1bb642a8c5fd7a7a22473

250 Zyklen ab Markerdruck. Auswertung:

    FUSE_FENSTER_MIN=260 python tools/fuse/mahlzeitenlauf.py <trail.jsonl.gz>
    FUSE_FENSTER_MIN=260 python tools/fuse/blocker_entmaskieren.py <trail.jsonl.gz>

Ins Repo gehoert hoechstens ein synthetischer, identitaetsfreier Golden Vector -
der ist noch nicht gebaut.
