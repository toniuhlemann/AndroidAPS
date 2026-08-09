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
     */
    fun effectiveDeadbandMgdl(
        reboundWindow: Boolean,
        reboundDeadbandMgdl: Double,
        isNight: Boolean,
        nightDeadbandMgdl: Double,
        markerBoost: Boolean,
    ): Double {
        val rebound = if (reboundWindow) reboundDeadbandMgdl else 0.0
        val night = if (isNight && !markerBoost) nightDeadbandMgdl.coerceAtLeast(0.0) else 0.0
        return maxOf(rebound, night)
    }
}
