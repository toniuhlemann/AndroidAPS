package app.aaps.fuse.core.controller

import kotlin.math.max

/**
 * WIE LANGE EINE EVIDENZ-EPISODE WIEDERERKENNBAR BLEIBT - eine Regel, zwei
 * Verbraucher.
 *
 * ANLASS (Toni am Geraet, 16.08.2026). Sein Fruehstuecks-Marker um 09:33
 * eroeffnete die Episode; der Marker um 14:38 fuer eine ECHTE zweite Mahlzeit
 * lag 305 Minuten spaeter, also innerhalb des 360-Minuten-Deckels, und erbte
 * den alten Topf samt Uhr ([MarkerEpisodeGate], Denial.MARKER_ALREADY_CONSUMED).
 * Um 15:33 lief dieser Topf ab - mitten in der zweiten Mahlzeit, T+55 Minuten,
 * kurz VOR der Staerkewelle der Nudeln. Danach: Phase.EXPIRED, "kein
 * Wiederaufleben".
 *
 * Tonis Einwand gegen die erste, schwaechere Antwort ("wir zeigen das Restalter
 * im Marker-Dialog an") trifft: eine Information ohne Handlungsoption ist
 * keine Loesung. Wer beim Druecken liest "laeuft in 55 min ab", kann weder die
 * Mahlzeit verschieben noch die Uhr anhalten.
 *
 * ## Die Regel
 *
 * Der Deckel laeuft weiter ab dem URSPRUNG der Episode - aber er endet nicht
 * vor `letzterMarker + [MARKER_EXTENSION_MIN]`. Ein Markerdruck ist eine
 * ausdrueckliche Erklaerung "hier laeuft eine Mahlzeit"; solange die frisch
 * ist, bleibt die Episode wiedererkennbar.
 *
 * ## Warum das den Notaus nicht aufweicht
 *
 * Der Deckel existiert laut [EvidenceStock] gegen genau drei Gefahren:
 * "Gegenregulation, Sensordrift, eine schlechte Infusionsstelle - alles drei
 * sieht wie Stoerung aus und ist keine Mahlzeit." KEINE davon kommt mit einem
 * Markerdruck. Die Verlaengerung haengt damit an einer WILLENSERKLAERUNG und
 * nicht an der Zeit allein - sie trennt genau entlang der Linie, fuer die der
 * Notaus gebaut wurde. Ohne Marker gilt der Basisdeckel unveraendert.
 *
 * Und sie ist KEINE Doppelfinanzierung: verlaengert wird DIESELBE Episode,
 * nicht eine neue eroeffnet. `lastCommittedU`, `episodeStartTs` und der ganze
 * Abzugsstand laufen durch. Genau das unterscheidet "am Leben halten" von
 * "neu eroeffnen" - letzteres setzte den Zaehler auf 0, und dieselbe Stoerung
 * waere ein zweites Mal unbezahlt.
 *
 * ## Was die Dauer trotzdem begrenzt
 *
 * Nicht dieser Wert. [EvidenceStock] sagt es selbst: "Die DAUER regelt nicht
 * dieser Wert, sondern der Bestand selbst ueber Phase - ohne Evidenz faellt die
 * Episode von allein auf DORMANT und gibt keinen Kredit mehr. Der Deckel sagt
 * nur noch, wie lange sie ueberhaupt WIEDERERKENNBAR bleiben darf." Eine
 * verlaengerte Episode ohne Zufluss ist deshalb genauso still wie eine
 * abgelaufene - nur kann sie bei einer zweiten Welle wieder aufwachen.
 */
object EpisodeDeadline {

    /**
     * Wie lange ein Markerdruck die Episode mindestens noch traegt [min].
     *
     * 180 nach Tonis Vorschlag. Die Zahl ist PROVISORISCH und bewusst kuerzer
     * als der Basisdeckel: sie soll eine laufende Mahlzeit ueber ihre zweite
     * Welle tragen, nicht die Episode unbegrenzt fortschreiben. Wer alle drei
     * Stunden drueckt, haelt die Episode offen - aber der Bestand verfaellt
     * dabei unveraendert mit `decayMin`, und jede Abgabe wird weiter abgezogen.
     */
    const val MARKER_EXTENSION_MIN = 180

    /**
     * Der wirksame Deckel [ms] ab Episodenbeginn.
     *
     * @param baseCapMs der harte Basisdeckel (heute 360 min)
     * @param episodeStartTs Beginn der laufenden Episode; 0 = keine
     * @param lastMarkerTs juengster Markerdruck; 0 = keiner
     * @param extensionMin Verlaengerung ab Markerdruck
     * @return Deckel ab `episodeStartTs`, nie kleiner als [baseCapMs]
     */
    fun effectiveCapMs(
        baseCapMs: Long,
        episodeStartTs: Long,
        lastMarkerTs: Long,
        extensionMin: Int = MARKER_EXTENSION_MIN,
    ): Long {
        if (baseCapMs <= 0L) return baseCapMs
        if (episodeStartTs <= 0L || lastMarkerTs <= 0L || extensionMin <= 0) return baseCapMs
        // NUR EIN ZWEITER DRUCK VERLAENGERT - der EROEFFNENDE nicht.
        //
        // `episodeStartTs` IST der Zeitpunkt des eroeffnenden Markers. Waere
        // hier `<` statt `<=`, verlaengerte jede Episode sich selbst schon bei
        // ihrer Entstehung, und der Basisdeckel waere praktisch abgeschafft:
        // jede Episode liefe automatisch Basis + Verlaengerung. Meine erste
        // Fassung hatte genau das - vier bestehende Tests haben es gefangen.
        //
        // Ein Marker VOR dem Episodenbeginn gehoert ohnehin zu einer frueheren
        // Lage.
        if (lastMarkerTs <= episodeStartTs) return baseCapMs
        // UND EIN MARKER NACH DEM BASISDECKEL VERLAENGERT AUCH NICHT - er
        // EROEFFNET eine neue Episode.
        //
        // Das ist die Grenze, die meine erste Fassung uebersehen hat, und drei
        // bestehende Tests haben sie gefangen: ohne diese Zeile verlaengert
        // ausgerechnet der Druck, der eine NEUE Episode eroeffnen will, die
        // alte - eine Episode koennte dann nie enden, solange jemand drueckt,
        // und "der 360-Deckel ist damit hart" (Option A, Toni 15.08.) waere
        // aufgehoben.
        //
        // Die Regel lautet damit: wer INNERHALB der Episode eine Mahlzeit
        // erklaert, traegt sie weiter; wer DANACH drueckt, faengt neu an. Die
        // Gesamtdauer ist so nach oben begrenzt (Basis + Verlaengerung), statt
        // beliebig fortschreibbar zu sein.
        if (lastMarkerTs - episodeStartTs >= baseCapMs) return baseCapMs
        val bisMarkerEnde = lastMarkerTs - episodeStartTs + extensionMin * 60_000L
        // Nie VERKUERZEN: der Basisdeckel ist die Untergrenze, nicht die
        // Alternative. Ein Marker unmittelbar zum Episodenbeginn darf die
        // Episode nicht auf 180 Minuten stutzen.
        return max(baseCapMs, bisMarkerEnde)
    }
}
