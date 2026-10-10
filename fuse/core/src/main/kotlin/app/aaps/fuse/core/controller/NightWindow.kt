package app.aaps.fuse.core.controller

/**
 * NACHTFENSTER mit eigenem Totband (Tonis Vorschlag 09.08.).
 *
 * Warum ueberhaupt: eine gemessene Nacht zeigt den Fehlermodus in
 * Reinform - gegen Morgen dosierte FUSE eine Stunde lang mehrere SMBs bei normalem BG
 * mit r um NULL und negativem Basal-IOB. Der Bedarf kam nicht von einer
 * Stoerung, sondern aus dem zurueckgehaltenen Basal: das Modell erwartet
 * einen Anstieg, weil Insulin fehlt, das es selbst zurueckgehalten hat.
 * Nachts ist niemand wach, der das korrigiert - und die Rueckholkapazitaet
 * ist mit einem Bruchteil einer Einheit je 30 min die kleinste des Tages.
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
     * war und gemessene, unbezahlte Stoerung auswies (zahlreiche Live-Zyklen).
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
        /**
         * DARF DIE EVIDENZ DAS REBOUND-TOTBAND ENTWAFFNEN?
         *
         * ZWEI GETRENNTE BERECHTIGUNGEN, NICHT EIN SIGNAL (Toni 19.08.). Hier
         * stand fuer beide Baender derselbe `evidenceCreditActive`. Das
         * Rebound-Sonderrecht bekommt jetzt eine markerbezogene Frist
         * (`EvidenceReboundOverrideMaxMin`), das NACHT-Verhalten bleibt
         * unveraendert - waere es dasselbe Signal, haette die Befristung
         * ungewollt auch die Nacht getroffen.
         *
         * OHNE DEFAULT, wie bisher: ein vergessener Anschluss hielte die
         * Baender still scharf. Genau dieser Fehler lief einmal zwei Tage
         * lang auf dem Geraet (zahlreiche geblockte Kreditzyklen, waehrend die
         * Commit-Botschaft die Verdrahtung behauptete).
         */
        reboundOverrideByEvidence: Boolean,
        /** Das NACHT-Sonderrecht - unbefristet, Verhalten wie bisher. */
        nightOverrideByEvidence: Boolean,
    ): Double {
        val rebound = if (reboundWindow && !reboundOverrideByEvidence) reboundDeadbandMgdl else 0.0
        val night = if (isNight && !(markerBoost || nightOverrideByEvidence)) nightDeadbandMgdl.coerceAtLeast(0.0) else 0.0
        return maxOf(rebound, night)
    }

    /**
     * DIE FRIST DES REBOUND-SONDERRECHTS (Toni 19.08.).
     *
     * DER GEMESSENE ANLASS. In einem realen Fall war der Marker 287 Minuten alt,
     * das Rebound-Fenster lief noch 32 Minuten, die Evidenzepisode war wieder
     * ACTIVE mit kleinem positivem Kredit - und der Zucker stand deutlich unter
     * der Rebound-Schwelle. Binnen weniger Minuten gingen mehrere
     * SMBs hinaus, die das Totband ohne die unbefristete
     * Kredit-Ausnahme geblockt haette.
     *
     * DAS PROBLEM IST NICHT DIE EVIDENZ, SONDERN IHRE DAUER. Die Episode darf
     * 360 Minuten leben und weiter Bedarf erzeugen; nur ihr Recht, ein
     * AKTIVES Rebound-Totband zu entwaffnen, ist zeitlich zu begrenzen -
     * fuenf Stunden nach dem Markerdruck ist die angekuendigte Mahlzeit kein
     * Argument mehr gegen einen Rebound-Schutz.
     *
     * HALB OFFENES FENSTER: bei exakt T+TTL ist das Privileg beendet.
     *
     * @param deadlineTs der beim MARKERDRUCK festgeschriebene Ablauf. 0 heisst
     *   "kein Privileg" - fehlender Marker, Widerruf oder TTL 0.
     */
    fun evidenceMayOverrideRebound(
        evidenceCreditActive: Boolean,
        deadlineTs: Long,
        computeTs: Long,
    ): Boolean = evidenceCreditActive && deadlineTs > 0L && computeTs < deadlineTs

    /** Warum das Rebound-Sonderrecht NICHT gilt - typisiert fuer den Trail. */
    enum class ReboundOverrideDenial {
        NO_CREDIT,
        NO_MARKER,
        MARKER_FUTURE,
        /** Die gespeicherte Frist gehoert zu einem ANDEREN Markerdruck.
         *  Nach einem Warmstart oder einem extern geaenderten Marker haette
         *  ein neuer Druck sonst die noch laufende Frist des alten geerbt -
         *  "es gibt eine Frist" ist nicht "es ist SEINE Frist". */
        MARKER_MISMATCH,
        EXPIRED,
        REVOKED,
    }

    /**
     * Der Grund, aus dem das Privileg fehlt - oder `null`, wenn es gilt.
     *
     * REIHENFOLGE IST DIAGNOSE: der laenger wirkende Befund zuerst. Ein
     * Widerruf ueberlebt den Zyklus, ein fehlender Kredit betrifft nur ihn.
     */
    fun reboundOverrideDenial(
        evidenceCreditActive: Boolean,
        deadlineTs: Long,
        computeTs: Long,
        markerTs: Long,
        /** An welchen Druck die gespeicherte Frist gepinnt ist. */
        pinnedForTs: Long,
        revoked: Boolean,
    ): ReboundOverrideDenial? = when {
        revoked                                 -> ReboundOverrideDenial.REVOKED
        markerTs <= 0L                          -> ReboundOverrideDenial.NO_MARKER
        markerTs > computeTs                    -> ReboundOverrideDenial.MARKER_FUTURE
        markerTs != pinnedForTs                 -> ReboundOverrideDenial.MARKER_MISMATCH
        deadlineTs <= 0L || computeTs >= deadlineTs -> ReboundOverrideDenial.EXPIRED
        !evidenceCreditActive                   -> ReboundOverrideDenial.NO_CREDIT
        else                                    -> null
    }
}
