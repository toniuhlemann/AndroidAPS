# B3: Offline-Attribution am 27.08.-Korrekturfall (zentrale Dosierprofile)

Stand 29.08. abends. Harness-Commit `3366203f5d` (FUSE_REPLAY_DOSING_CONTEXT),
Produktionsstand v42 (B2 `19b6fc1438`). NICHT geflasht, nichts aktiviert,
keine Wahl des Live-Werts - die Kandidaten sind Messpunkte fuer Schritt C.

## Aufbau

- Trail: 27.08. (lokal, mylogs/), Fenster 27.08. 20:00 - 28.08. 00:30
  (268 Zyklen; Kaltstart-Grenze 20:20 - alle Befunde liegen weit dahinter).
- `ctxbase` = aufzeichnungstreue Politik (LEGACY wie am Geraet gefahren).
- Kandidaten (je Lauf voller Satz, Modus CENTRAL_PROFILES):
  - `ctx01`: corrExp 2,5 U / mealExp 6,0 / corrRatio 1,0 / mealRatio 1,0
  - `ctx02`: corrExp 3,0 U / mealExp 6,0 / corrRatio 1,0 / mealRatio 1,0
  - `ctx03`: corrExp 20 / mealExp 20 (offen) / corrRatio 0,5 / mealRatio 1,0
- Rueckkopplungsblind: nach der ersten Dosisdivergenz sind alle Summen nur
  Obergrenzen der Gegenrechnung. Belastbar sind ZEITPUNKT, RICHTUNG und
  ATTRIBUTION der ersten Abweichung sowie die Blockmechanik je Zyklus.
- Fidelity-Boden: ctxbase trifft den bekannten Burst als 2,45 U in 7
  SMB-Zyklen (aufgezeichnet: 2,50 U in 12 tailHeadroom-Zyklen) - Budgets
  und Ledger starten frisch, Base-vs-Kandidat ist der saubere Vergleich,
  Base-vs-Aufzeichnung nicht.

## Befund 1: Neutralitaet ausserhalb des Falls

In ALLEN drei Varianten: 0 Dosisdivergenzen von 20:00 bis 21:54. Die
zentrale Politik fasst den ruhigen Betrieb nicht an.

## Befund 2: Die erste Divergenz attribuiert auf den RATIO-Baustein - als Alt-Cap-Ersetzung, nicht als Griff

Erste Divergenz identisch in allen drei Varianten, 21:58:21:
base 0,150 U (`livenessRatioCap` bindet) vs. Kandidat 0,250 U
(`smbRatio` bindet, expoSource=LIVENESS, Profil CORRECTION/
MARKER_NOT_PINNED). Das ist der B2-Migrationsvertrag in Zahlen: im
zentralen Modus ERSETZT der Kontext-Cap die Liveness-Alt-Caps, und die
aufgezeichnete Alt-Drossel (0,15-Klasse) wirkt nicht mehr - der Kanal
dosiert je Zyklus schneller. Der konfigurierte corrRatio-Kandidat selbst
griff in KEINEM Lauf (auch 0,5 in ctx03 nie: die gefahrenen Basis-Ratios
blieben darunter).

WICHTIG fuer die Wertewahl (Schritt C): wer den zentralen Modus mit
offenen Ratio-Kandidaten (1,0) aktiviert, gibt dem Liveness-Kanal mehr
Geschwindigkeit, als das Geraet unter LEGACY fuhr. Der Kontext-Cap ist
im zentralen Modus die EINZIGE Kanaldrossel dieser Klasse.

## Befund 3: Das Exposure-Limit haette den Burst gestoppt - Attribution EXPOSURE

Fallfenster 21:54-22:05 (Obergrenzen):

| Lauf | Menge | Mechanik |
|---|---|---|
| base | 2,45 U in 7 Zyklen | tail-/ratio-gebundene Kette, kein Ende |
| ctx01 (2,5) | 0,45 U in 2 Zyklen | Teilkappe 21:59 (0,200 bei head 0,238), dann 8x Vollblock EXPOSURE_LIMIT 22:00-22:07 |
| ctx02 (3,0) | 1,15 U in 4 Zyklen | Teilkappen 22:00 (0,400) + 22:01 (0,150), Vollblock ab 22:02 |
| ctx03 (offen) | 3,00 U in 7 Zyklen | kein Gate-Griff; nur die schnellere Kanalrate aus Befund 2 |

Gesamtfenster 20:00-00:30: base 3,40 / ctx01 0,45 / ctx02 1,15 /
ctx03 3,95 U (Obergrenzen).

Invariante 7 auf echten Daten: JEDER EXPOSURE_LIMIT-Zyklus traegt
`NO_NEW_POSITIVE`, keiner Zero-TBR. Die Blockgrenze ist durchgehend als
`correctionExposureLimit` benannt; die Provenienz wechselt sauber von
LIVENESS (letzte dosierende Quelle) auf NONE, sobald nichts mehr fliesst.

## Befund 4: Kontext- und M1-Attribution sauber getrennt

- Kontext: durchgehend CORRECTION/MARKER_NOT_PINNED (kein Marker im
  Fenster - genau richtig fuer den reinen Korrekturfall); die 5 leeren
  Zyklen sind Abbruch-/Fallback-Zyklen ohne Kontextzeile.
- M1-Schwellenquelle (`bgMinQuelle`): 173x DAY, 90x NIGHT, nie MEAL -
  der Tag/Nacht-Wechsel ist im Export attribuierbar, die MEAL-Schwelle
  blieb mangels Vollmacht korrekt unbeteiligt (`pressureThreshold`
  getrennt lesbar).

## Grenzen

- Rueckkopplungsblind: ob der gestoppte Burst hyperglykaemische Folgezeit
  gekostet haette, kann dieses Replay strukturell nicht sagen (die
  aufgezeichnete BG-Kurve entstand unter der Base-Dosierung).
- Zykluspause vs. CGM-Luecke im Fenster nicht gesondert getrennt; fuer
  die erste Divergenz irrelevant (kontiguierlicher Abschnitt).
- Werte-Empfehlung bleibt Schritt C vorbehalten.
