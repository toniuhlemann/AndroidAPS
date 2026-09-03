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
 *  - DER BEWUSSTE DRUCK, auf zwei Wegen nachweisbar: entweder wurde
 *    GENAU DIESER Marker in DIESEM Prozess gedrueckt, oder das Fundament
 *    ist persistent unter der laufenden Kennung armiert - und armiert
 *    wird nur mit beobachtetem Druck. Der fluechtige Merker allein
 *    haette eine bestehende Autorisierung nach jedem AAPS-Neustart
 *    ausgesperrt, und der einzige Ausweg waere ein NEUER Druck gewesen -
 *    also eine neue volle Huelle. S. die Begruendung an [holds].
 *  - `foundationArmedByAuthId == currentAuthId`, beide NICHT leer -
 *    die belastbare Zuordnung zur laufenden Autorisierung. Eine
 *    FEHLENDE Kennung ist keine Zustimmung: Altbestand aus einer
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
     * @param pressObservedForTs fuer welchen Zeitpunkt ein Druck in
     *   DIESEM Prozess beobachtet wurde (0 = keiner).
     * @param foundationArmedByAuthId die Kennung, unter der das
     *   Fundament armiert wurde.
     * @param currentAuthId die Kennung der laufenden Autorisierung.
     */
    fun holds(
        auth: MealFoundation.Authorization,
        activeMarkerTs: Long,
        markerActive: Boolean,
        pressObservedForTs: Long,
        foundationArmedByAuthId: String?,
        currentAuthId: String?,
    ): Boolean {
        if (!markerActive || activeMarkerTs <= 0L) return false
        if (!auth.valid) return false
        if (!auth.pinnedMarkerAuthorized) return false
        if (auth.armedTs != activeMarkerTs) return false

        // ---- DIE ZUORDNUNG - IMMER PFLICHT ------------------------------
        //
        // Beide Kennungen muessen DA sein und uebereinstimmen; eine
        // fehlende Identitaet ist keine Freigabe (Altbestand traegt keine).
        val id = currentAuthId
        val zuordnungBelegt = !id.isNullOrEmpty() && foundationArmedByAuthId == id
        if (!zuordnungBelegt) return false

        // ---- DER BEWUSSTE DRUCK - ZWEI WEGE, DERSELBE NACHWEIS ----------
        //
        // (1) LIVE: in DIESEM Prozess wurde genau dieser Marker gedrueckt.
        // (2) PERSISTENT: das Fundament ist unter GENAU der Kennung
        //     armiert, die jetzt laeuft.
        //
        // WARUM (2) DENSELBEN NACHWEIS TRAEGT: die Zuordnung entsteht
        // ausschliesslich im Armierungsblock des Runners, und dort armiert
        // [MealFoundation.arm] nur mit beobachtetem Druck - ohne ihn bleibt
        // eine [MealFoundation.Authorization.none] zurueck, die schon an
        // `auth.valid` scheitert. Eine gueltige Autorisierung UNTER DER
        // LAUFENDEN KENNUNG kann es also ohne bewussten Druck nicht geben.
        // Fundament, Kennung, Widerrufsstand, Marker und die verbrauchten
        // Mengen liegen alle im Ledger und ueberleben den Prozess.
        //
        // WARUM DER LIVE-MERKER ALLEIN NICHT REICHT: `markerPressObservedTs`
        // ist absichtlich fluechtig und steht nach einem AAPS-Neustart
        // wieder auf 0. Verlangte man ihn weiter, sperrte das rohe
        // Rebound-Fenster eine laengst gueltige Direktdosis erneut - und der
        // einzige Ausweg waere ein NEUER Druck. Der oeffnet nach dem
        // Neuautorisierungs-Vertrag aber eine NEUE VOLLE HUELLE
        // ([MarkerReauthorization]); ein Neustart darf nicht in eine
        // zusaetzliche Autorisierung draengen.
        //
        // Fuer das ERSTMALIGE Bewaffnen bleibt der beobachtete Druck
        // erforderlich - das erzwingt [MealFoundation.arm], nicht diese
        // Stelle. Hier wird nur eine BESTEHENDE Autorisierung weitergefuehrt:
        // keine neue Huelle, kein zurueckgesetzter Verbrauch. Wie viel noch
        // gehen darf, sagt unveraendert die Bilanz.
        val druckBelegt = pressObservedForTs == activeMarkerTs || zuordnungBelegt
        if (!druckBelegt) return false

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
