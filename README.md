# FUSE — Full-loop Unannounced-disturbance Safety & Exposure Controller

**Entwicklungsbranch (`fuse-dev`) — reines Forschungsprojekt.**

> **WARNING / WARNUNG**
> This is a personal research branch. It is NOT a usable AAPS distribution,
> receives NO support, and must NOT be built or flashed for real-world therapy.
> Dies ist ein persoenlicher Forschungsbranch — keine nutzbare AAPS-Distribution,
> kein Support, niemals fuer die reale Therapie bauen oder flashen.

## Was ist das?

FUSE ist ein in Entwicklung befindliches APS-Forschungs-Plugin fuer reines FCL
(1-min-CGM, keine Kohlenhydrat-/Bolus-Eingaben), das perspektivisch autoISF in
diesem Fork abloesen soll. Die Architektur (signiertes insulinbereinigtes
Residual, Epochen-State-Machine, Release-Envelope, ursachenspezifische
Safety-Zustaende) entsteht in einer mehrstufig gegengeprueften Review-Kette
und wird ausschliesslich offline sowie auf einem separaten Testgeraet mit
virtueller Pumpe erprobt.

## Branch-Struktur

```text
3.4.2.4+aisf3.2.0-toni      Produktiv-Basis (autoISF-Fork) — NICHT dieser Branch
└── fuse-dev                dauerhafte FUSE-Entwicklung (dieser Branch)
    └── feature/fuse-*      kurzlebige, sequenziell gemergte Feature-Branches

Tags 3.4.2.4+fuseX.Y.Z-toni  unveraenderliche, getestete APK-Staende
                             (annotiert, GitHub-Ruleset-geschuetzt;
                             versionName == Tag; Commit-SHA + APK-SHA-256
                             im Testmanifest)
```

`fuse-dev` basiert bewusst auf der Produktiv-Basis (nicht auf master), damit die
CGM-Verarbeitungskette (Glaettung, 1-min-Kadenz, Exporte) baugleich mit dem
Referenzsystem ist.

## Status

Geruestphase — noch kein Plugin-Code. Die Entwicklung beginnt erst nach
Abschluss der vorregistrierten Forschungs-Gates (Datensammler-Livegate,
prospektive Beobachter-Kohorte, Kandidatenwahl).

## Herkunft

Basis ist AndroidAPS mit der autoISF-Erweiterung. Deren Dokumentation gilt fuer
die Basis-Schicht unveraendert weiter: [AndroidAPS-Wiki](https://androidaps.readthedocs.io),
[autoISF-Doku (ga-zelle)](https://github.com/ga-zelle/autoISF),
[autoISF-Integrationsfork (T-o-b-i-a-s)](https://github.com/T-o-b-i-a-s/AndroidAPS).
Dieses README ersetzt die Upstream-Beschreibung nur auf den fuse-Branches.
