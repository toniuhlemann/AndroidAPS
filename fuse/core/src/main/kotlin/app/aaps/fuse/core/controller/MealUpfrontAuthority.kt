package app.aaps.fuse.core.controller

/**
 * TRAEGT DIE MAHLZEITENAUTORISIERUNG DIESE DIREKTDOSIS?
 *
 * ===================================================================
 * WOFUER DAS DA IST - UND WOFUER NICHT
 * ===================================================================
 * Das Rebound-Fenster schuetzt vor dem Jagen UNANGEKUENDIGTER
 * Hypo-Gegenesser: nach einem gemessenen Tief ist ein Anstieg
 * wahrscheinlich Traubenzucker und keine Mahlzeit. Genau diese
 * Unwissenheit hebt ein Markerdruck auf - er IST die Ankuendigung
 * kommender Kohlenhydrate.
 *
 * Bis hierher entwaffnete der Marker nur die HEURISTIK-Bremse
 * (`reboundWindow`: Ratio-Deckel, Totband, tau-Kuerzung). Die
 * Direktdosis-Kette las weiter das ROHE Kennzeichen `reboundRaw` und
 * schob deshalb auf, obwohl dieselbe Mahlzeit nebenher einzelne SMBs
 * anforderte und den Phase-A-Verbrauch erhoehte. Der Livebefund zeigte
 * `markerBoost=true`, `reboundSuppressedByMarker=true`,
 * `reboundWindow=false` - und gleichzeitig `recoveryDenial=CURRENT_HAZARD`
 * mit `currentHazard=rebound` bei `phaseAUpfrontRequestedU=0`.
 *
 * DAS IST KEINE PAUSCHALE REGEL "MEAL DARF IMMER". Sie hebt EINEN
 * Riegel auf, und nur den: das historische Rebound-Kennzeichen. Alles
 * andere bleibt unberuehrt und wird an seinen eigenen Stellen
 * geprueft - gemessenes Tief, aktuelles Abwaertsrisiko, der am Marker
 * gepinnte Mahlzeiten-Risikovertrag, Signal- und Modellfehler,
 * technische Integritaet, Ledger-Hold, Mengengrenzen, Reservierungen
 * und die Publikations-Gates.
 *
 * SIE AUTORISIERT AUCH KEINE ZUSAETZLICHE MENGE. Was freigegeben werden
 * darf, sagt weiterhin die Buchhaltung (`remainingUpfrontU` gegen die
 * bereits verbuchten und offenen Mengen); diese Funktion sagt nur, dass
 * der Rebound allein die Freigabe nicht mehr verhindert.
 *
 * ===================================================================
 * KEINE ZWEITE AUTORISIERUNGSLOGIK
 * ===================================================================
 * Jede Bedingung hier ist eine BESTEHENDE Regel, nur an einer Stelle
 * zusammengefuehrt - damit alle Tore der Kette dieselbe Antwort
 * bekommen und nicht drei Fassungen entstehen:
 *
 *  - [MealFoundation.Authorization.valid] - die strukturelle
 *    Gueltigkeit (Budget, Anteile, Fenster).
 *  - `pinnedMarkerAuthorized` - die beim Druck GEPINNTE Zusage, dass
 *    dieser Marker freigeben darf. Eine spaeter geaenderte Einstellung
 *    verschiebt die laufende Mahlzeit nicht.
 *  - `armedTs == activeMarkerTs` - dieselbe Mahlzeit, nicht eine
 *    frueher armierte. `activeMarkerTs` ist im Runner bereits gegen die
 *    WIDERRUFSMARKE im Ledger abgeglichen; ein zurueckgenommener Marker
 *    kommt hier also gar nicht an.
 *  - `foundationArmedByAuthId == currentAuthId`, beide NICHT leer -
 *    die belastbare Zuordnung zur laufenden Autorisierung, und zugleich
 *    der FORTFUEHRUNGSNACHWEIS des bewussten Drucks. Sie entsteht
 *    ausschliesslich im Armierungsblock des Runners, und dort armiert
 *    [MealFoundation.arm] nur mit einem in DIESEM Prozess beobachteten
 *    Druck - ohne ihn bleibt eine [MealFoundation.Authorization.none]
 *    zurueck, die schon an `valid` scheitert. Eine GUELTIGE
 *    Autorisierung unter der LAUFENDEN Kennung kann es ohne bewussten
 *    Druck also nicht geben.
 *
 *    DER LIVE-MERKER WIRD HIER BEWUSST NICHT GELESEN.
 *    `markerPressObservedTs` ist fluechtig und steht nach einem
 *    AAPS-Neustart wieder auf 0. Verlangte man ihn, sperrte das rohe
 *    Rebound-Fenster eine laengst gueltige Direktdosis erneut - und der
 *    einzige Ausweg waere ein NEUER Druck, der nach dem
 *    Neuautorisierungs-Vertrag eine NEUE VOLLE HUELLE oeffnet
 *    ([MarkerReauthorization]). Ein Neustart darf niemanden in eine
 *    zusaetzliche Autorisierung draengen. Die Druckpflicht liegt
 *    deshalb dort, wo sie hingehoert: beim erstmaligen Armieren.
 *
 *    Eine fehlende Kennung ist KEINE Zustimmung: Altbestand aus einer
 *    aelteren Fassung traegt keine, und "unbewiesen" darf hier nicht
 *    dosieren duerfen. Dieselbe Wahl wie in der Rueckbuchung.
 *  - `phaseAUpfrontU > 0` - es wurde ueberhaupt eine Direktdosis
 *    gewaehlt. Ein Marker ohne Sofortanteil bekommt durch diese
 *    Funktion keinen. ("Ohne Vorschuss" fuehrt schon in
 *    [MealFoundation.arm] zu [MealFoundation.Authorization.none].)
 *
 * KEIN EVIDENZKREDIT. Der bestehende Rebound-Uebergang des Reglers
 * (`NightWindow.evidenceMayOverrideRebound`) verlangt einen positiven
 * Evidenzbestand - sichtbare Absorption also. Fuer die ausdruecklich
 * autorisierte Direktdosis waere das die falsche Bedingung: sie soll
 * gerade VOR der sichtbaren Absorption wirken. Deshalb ist das hier
 * eine eigene Entscheidung und keine Wiederverwendung jener.
 */
object MealUpfrontAuthority {

    /**
     * @param auth die armierte Mahlzeitenautorisierung.
     * @param activeMarkerTs der AKTIVE Markerzeitpunkt des Runners -
     *   bereits gegen die Widerrufsmarke abgeglichen.
     * @param markerActive ob der Marker in seinem Fenster laeuft.
     * @param foundationArmedByAuthId die Kennung, unter der das
     *   Fundament armiert wurde.
     * @param currentAuthId die Kennung der laufenden Autorisierung.
     */
    fun holds(
        auth: MealFoundation.Authorization,
        activeMarkerTs: Long,
        markerActive: Boolean,
        foundationArmedByAuthId: String?,
        currentAuthId: String?,
    ): Boolean {
        if (!markerActive || activeMarkerTs <= 0L) return false
        if (!auth.valid) return false
        if (!auth.pinnedMarkerAuthorized) return false
        if (auth.armedTs != activeMarkerTs) return false

        // ---- DIE ZUORDNUNG IST DER FORTFUEHRUNGSNACHWEIS ----------------
        //
        // Beide Kennungen muessen DA sein und uebereinstimmen. Das ist
        // zugleich der Nachweis des bewussten Drucks - s. die Begruendung
        // im Kopf: armiert wird nur mit beobachtetem Druck, und ohne ihn
        // scheitert die Autorisierung schon an `valid`.
        //
        // Hier stand einmal zusaetzlich ein `druckBelegt`-Zweig mit dem
        // fluechtigen Live-Merker als Alternative. Er war nach dieser
        // Pruefung algebraisch immer wahr, also tot - und er beschrieb den
        // Vertrag falsch, als gaebe es zwei gleichwertige Drucknachweise.
        // Es gibt genau einen, und er liegt in [MealFoundation.arm].
        val id = currentAuthId
        if (id.isNullOrEmpty() || foundationArmedByAuthId != id) return false

        // Hier wird nur eine BESTEHENDE Autorisierung weitergefuehrt: keine
        // neue Huelle, kein zurueckgesetzter Verbrauch. Wie viel noch gehen
        // darf, sagt unveraendert die Bilanz.
        //
        // Ohne gewaehlte Direktdosis gibt es nichts zu entsperren.
        return auth.phaseAUpfrontU.isFinite() && auth.phaseAUpfrontU > 0.0
    }

    /**
     * DER RIEGEL, DEN DIE DIREKTDOSIS-KETTE BENUTZT.
     *
     * EINE Stelle fuer alle Tore der Kette - das erste Aufschub-Tor, die
     * Wiederfreigabe, der Phase-A-Uebertrag und die aufgeschobene
     * Prime-Freigabe fragen dasselbe. Drei Fassungen derselben Regel
     * waeren genau die zweite Wahrheit, an der diese Kette schon einmal
     * auseinandergelaufen ist.
     *
     * `reboundRaw` bleibt ueberall sonst unveraendert in Kraft.
     */
    fun reboundBlocks(reboundRaw: Boolean, authorityHolds: Boolean): Boolean =
        reboundRaw && !authorityHolds
}
