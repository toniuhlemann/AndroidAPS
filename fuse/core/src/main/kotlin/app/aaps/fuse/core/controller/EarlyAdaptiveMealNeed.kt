package app.aaps.fuse.core.controller

/**
 * FRUEHER ADAPTIVER MEAL-BEDARF (H8) - Bauauftrag Toni 18.09., Default AUS.
 *
 * ANLASS (Replay-Matrix 18.09., feste CGM-Reihe mit Eigeninsulin-Rueckfuehrung):
 * zwischen Marker +10 und +45 min bleibt FUSE im autorisierten Mahlzeitenfenster
 * oft bei 0,05-U-Foundation-Schritten, obwohl der BGI-bereinigte W10-Antrieb
 * einen Anstieg bereits robust zeigt. Der 60-min-Freigabepunkt der Mittelbahn
 * gewichtet den Antrieb dort zu schwach.
 *
 * MECHANIK: ein kurzer Bruttobedarf auf eigenem Horizont
 *
 *     earlyReleaseMean = target + W10 * horizonMin
 *
 * wird mit der produktiven Freigabe-Mittelbahn per `max` zusammengefuehrt -
 * nie addiert. Aus der gewaehlten Bahn leitet der bestehende Liveness-/MEAL-
 * Bedarfsweg den Kandidaten ab; der Kanal selbst fuehrt `max(normal, live)`
 * zusammen, die Foundation steckt im Normalpfad. Es entsteht keine zweite
 * Insulinbuchhaltung und kein Zustand.
 *
 * BEWUSST OHNE EIGENABGABE IM BEDARF (Tonis Entscheidung 18.09.): W10 ist
 * insulinbereinigt und beschreibt den fortbestehenden Stoerungsdruck; der
 * Bruttobedarf darf nach einer Abgabe weiter bestehen. Fruehere eigene Abgaben
 * wirken ueber die vorhandenen IOB-/Aktivitaets-, Exposure-/Headroom-, Ratio-,
 * maxSMB- und Seriengrenzen auf den freigegebenen Kandidaten.
 *
 * EINTRITTSSCHWELLE (Tonis Korrektur 18.09.): der W10-Antrieb muss die
 * bestehende Druckschwelle des Liveness-Kanals erreichen,
 * [LivenessChannel.R_MIN_MGDL_PER_MIN] = 1,0 mg/dl/min - genau die Bedingung
 * des bewerteten Codex-Replays. `riseRampLowR` (live darueber eingestellt) ist davon getrennt
 * und bleibt ausschliesslich Unterkante der SMB-Ratio-Rampe: zwischen 1,0 und
 * riseRampLowR darf H8 aktiv sein, die Ratio bleibt am unteren Rampenwert.
 *
 * WAS UNVERAENDERT BLEIBT: das Theil-Sen-Fenster (W10), der globale
 * Release-Horizont (60), der Safety-/Liability-Horizont, die W18-Bahn des
 * Reversal-Schutzes und jedes Tor dahinter. Diese Komponente entscheidet nur,
 * OB der Bruttobedarf in diesem Zyklus gebildet wird, und bildet ihn.
 *
 * Konsolidierung 2.0: H8 dosiert nicht mehr - es wirkte nur im Mahlzeitkontext ohne Konto (im Kontofenster war der
 * Horizont 0), und dort dosiert der Rueckfall wie ohne Marker. Es bleibt die Horizont-Auswahl, die Einstellung,
 * Politik und Politik-Hash bis Schritt 5 lesen. Die Beschreibung oben ist der Stand davor.
 */
object EarlyAdaptiveMealNeed {

    /** Zulaessige Horizonte [min]; 0 = AUS (Produktionsdefault). */
    val ALLOWED_HORIZONS_MIN: Set<Int> = setOf(0, 6, 8, 10)

    /**
     * Wirksamer Horizont: nur die zulaessigen Werte, alles andere ist AUS.
     * Ein fehlender oder unbekannter Schluessel (alte Settings, alte Backups)
     * faellt damit sicher auf 0.
     */
    fun effectiveHorizonMin(configuredMin: Int): Int =
        if (configuredMin in ALLOWED_HORIZONS_MIN) configuredMin else 0

    // Konsolidierung 2.0: Eingabe, Entscheidung, Ablehnungsgruende und die Bahnwahl des Liveness-Kanals sind
    // entfallen.
}
