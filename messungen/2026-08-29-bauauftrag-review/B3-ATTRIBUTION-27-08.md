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

## Nachtrag: Neurechnung unter v43 (P1-Fix, 29.08. spaet)

Tonis Review fand die versteckte Altgrenze: die Legacy-IOB-Prozentdeckel
wirkten im Zentralmodus weiter. Nach dem P1-Fix (f2741e84f1) wurde die
komplette Matrix NEU gerechnet (identisches Fenster, identische
Kandidaten):

- ctxbase (LEGACY): bitgleich 3,40 U - die Neutralitaet des Altpfads
  haelt auch nach dem Fix.
- ctx01 (2,5) und ctx02 (3,0): ZYKLUSGLEICH unveraendert (0,45 / 1,15 U)
  - bei diesen engen Kandidaten war das Exposure-Limit ohnehin die
  engere Grenze; die Zahlen der Haupttabelle oben GELTEN unveraendert.
- ctx03 (offene Grenzen): 3,95 -> 4,30 U (+0,35 U; erste Abweichung
  22:02:22, 0,350 -> 0,550). Die versteckte Altgrenze hat dort REAL
  gedrosselt - exakt Tonis P1-Befund, jetzt in Zahlen. Der Merksatz
  aus Befund 2 verschaerft sich: im Zentralmodus ohne bewusste
  Kandidaten ist der Kanal noch schneller als zuvor gemessen.
- Neue CSV-Attribution (typisierter Status): am Beispiel ctxbase
  204x STOP/GUARD (alles echte GUARD_FLOOR-Bloecke ohne gerechneten
  Bedarf - das Tor ist real zu), 56x FREE, 5x UNKNOWN
  (Kaltstart-Abbrueche), 3x STOP/TAIL. OFFENE ANZEIGEFRAGE an Toni:
  ein GUARD-Stop OHNE positiven Bedarf ist ehrlich "Tor zu", faerbt
  den Abend im Widget aber weitgehend rot - falls unerwuenscht, waere
  das eine bewusste Darstellungsentscheidung (z.B. gedaempfter Ton bei
  fehlendem Bedarf), KEINE Aenderung der Statusableitung.

## Nachtrag 2: CENTRAL-only unter v44 mit Startsatz-Defaults

Nach Tonis Cleanup-Vertrag (v44, 5553147540) existiert der LEGACY-Pfad
nicht mehr; der Replay-Lauf-Reset steht auf den echten Startsatz-
Defaults (3,0 / 7,0 / 0,20 / 0,35). Dieselbe 27.08.-Rechnung:

- ctxbase (= Startsatz als Default): Burst-Fenster 0,85 U - zwischen
  dem scharfen 2,5er-Kandidaten (0,45) und 3,0 mit offener Ratio
  (1,15). Der 0,20-Ratio-Cap drosselt den Kanal ZUSAETZLICH zur
  3,0-Grenze auf die alte Korrektur-Geschwindigkeit - exakt die
  Begruendung des Startsatzes.
- ctx01 (2,5/1,0): 0,45 U - konsistent zur v42/v43-Messung.
- ctx02 (Startsatz EXPLIZIT gesetzt): identisch zu ctxbase (0,85 U) -
  Konsistenzbeweis Default-Reset == explizite Kandidaten.
- Je Lauf 6 Gate-Vollblocks, Status durchgehend typisiert.
- Die fruehere LEGACY-Basislinie (2,45/3,40 U) ist seit dem Cleanup
  bewusst nicht mehr nachbildbar; sie bleibt als historische Messung
  der Haupttabelle dokumentiert.

## Grenzen

- Rueckkopplungsblind: ob der gestoppte Burst hyperglykaemische Folgezeit
  gekostet haette, kann dieses Replay strukturell nicht sagen (die
  aufgezeichnete BG-Kurve entstand unter der Base-Dosierung).
- Zykluspause vs. CGM-Luecke im Fenster nicht gesondert getrennt; fuer
  die erste Divergenz irrelevant (kontiguierlicher Abschnitt).
- Werte-Empfehlung bleibt Schritt C vorbehalten.
