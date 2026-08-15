package app.aaps.fuse.core.controller

/**
 * NACHTFENSTER mit eigenem Totband (Tonis Vorschlag 09.08.).
 *
 * Warum ueberhaupt: die gemessene Nacht 08./09.08. zeigt den Fehlermodus in
 * Reinform - zwischen 05:25 und 06:24 dosierte FUSE 1,10 U bei BG 89 bis 116
 * mit r um NULL und negativem Basal-IOB. Der Bedarf kam nicht von einer
 * Stoerung, sondern aus dem zurueckgehaltenen Basal: das Modell erwartet
 * einen Anstieg, weil Insulin fehlt, das es selbst zurueckgehalten hat.
 * Nachts ist niemand wach, der das korrigiert - und die Rueckholkapazitaet
 * ist mit ~0,3 U je 30 min die kleinste des Tages.
 *
 * Warum als TOTBAND und nicht als Ratio-Deckel: dasselbe Muster hat schon
 * die Rebound-Naechte gerettet (s. [FuseController.REBOUND_DEADBAND_MGDL]).
 * Ein Totband sperrt sauber unterhalb einer BG-Schwelle und laesst darueber
 * die volle Regelung arbeiten - eine gedaempfte Ratio dagegen wuerde AUCH
 * die berechtigte grosse Korrektur verschleppen.
 *
 * Der ANKER entscheidet, nicht die Bahn: die Bahn traegt genau den
 * Phantomterm, gegen den das Fenster schuetzt.
 */
object NightWindow {

    /**
     * @param secondsFromMidnight lokale Uhrzeit des Ankers
     * @param startMin Beginn der Nacht [min ab Mitternacht], z. B. 23:00 = 1380
     * @param endMin Ende der Nacht [min ab Mitternacht], z. B. 07:00 = 420
     * @return true, wenn der Anker im Nachtfenster liegt. Das Fenster darf
     *   Mitternacht ueberschreiten (start > end); start == end heisst AUS.
     */
    fun isNight(secondsFromMidnight: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        val m = secondsFromMidnight / 60
        return if (startMin < endMin) m >= startMin && m < endMin
        else m >= startMin || m < endMin
    }

    /**
     * Das WIRKSAME Totband: der groessere von Rebound- und Nachtwert - zwei
     * Schutzgruende duerfen sich nie gegenseitig aufweichen.
     *
     * AUSNAHME MARKER: eine ERKLAERTE Mahlzeit hebt das Nacht-Totband auf
     * (nicht aber das Rebound-Totband). Wer um 23:30 isst und es ansagt, soll
     * die normale Regelung bekommen; ein UNANGEKUENDIGTER Anstieg bleibt
     * nachts gesperrt, bis er die Schwelle wirklich ueberschreitet - dort ist
     * Dawn/Rebound die haeufigere Erklaerung als eine heimliche Mahlzeit.
     *
     * AUSNAHME EVIDENZKREDIT (Toni 15.08.: "Rebound-Totfenster darf das
     * Mahlzeitenfenster niemals blocken"): fliesst in diesem Zyklus Kredit
     * aus dem VERSIEGELTEN Evidenzbestand, sind BEIDE Totbaender entwaffnet.
     *
     * Der 2-Tage-Lauf hat die Luecke belegt: die Marker-Sonderrechte enden
     * nach 45/90 Minuten, die Evidenz-Episode laeuft bis 360 - dazwischen
     * blockten Nacht- und Rebound-Totband Zyklen, in denen die Episode ACTIVE
     * war und gemessene, unbezahlte Stoerung auswies (81 Live-Zyklen).
     *
     * Warum das die Begruendung der Totbaender nicht aushoehlt: beide
     * schuetzen vor dem Jagen UNANGEKUENDIGTER kleiner Abweichungen (Dawn,
     * Hypo-Gegenesser). Der Evidenzkredit existiert nur in einer Episode, die
     * ein MARKERDRUCK eroeffnet hat - die Mahlzeit IST angekuendigt - und nur
     * fuer Stoerung, die als BGI-bereinigter Anstieg gemessen, versiegelt und
     * noch nicht mit Insulin bezahlt ist. Beides zusammen ist das Gegenteil
     * der Lage, fuer die die Totbaender gebaut wurden. Ohne Kredit (DORMANT,
     * SUSPENDED, PENDING_SEAL, Widerruf, Hold) gelten sie unveraendert.
     */
    fun effectiveDeadbandMgdl(
        reboundWindow: Boolean,
        reboundDeadbandMgdl: Double,
        isNight: Boolean,
        nightDeadbandMgdl: Double,
        markerBoost: Boolean,
        /** OHNE DEFAULT: ein Default `false` waere hier zwar fail-closed,
         *  aber ein vergessener Anschluss hielte die Totbaender still im
         *  Mahlzeitenfenster scharf - genau der Fehler, der zwei Tage lang
         *  auf dem Geraet lief. Lieber ein Kompilierfehler je Aufrufstelle. */
        evidenceCreditActive: Boolean,
    ): Double {
        val rebound = if (reboundWindow && !evidenceCreditActive) reboundDeadbandMgdl else 0.0
        val night = if (isNight && !(markerBoost || evidenceCreditActive)) nightDeadbandMgdl.coerceAtLeast(0.0) else 0.0
        return maxOf(rebound, night)
    }
}
