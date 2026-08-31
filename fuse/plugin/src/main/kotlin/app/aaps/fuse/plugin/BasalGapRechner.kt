package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.TbrPolicy

/**
 * DIE NULLPHASEN-RECHNUNG DER BASALLUECKE (Bauauftrag Schritt B).
 *
 * Anlass war ein gemessener Livefall: eine Mahlzeit traf auf eine
 * laufende Schutz-Nullphase, das Basal-IOB am Marker war negativ.
 * Beim Markerdruck soll die
 * Lage EINMALIG typisiert eingefroren werden - REIN BEOBACHTEND, damit
 * Trail und Viewer die Basalluecke ausweisen koennen. Keine Kompensation,
 * kein Headroom, kein Auto-Bolus.
 *
 * Diese Funktion ist PUR und rechnet auf Zeitscheiben, die der Runner aus
 * der echten TBR-Historie (ProcessedTbrEbData-Range) und den
 * Zeitpunkt-Profilen baut. Ehrlichkeitsgrenzen (Vertrag): lieber
 * typisiert `null` als eine Schaetzung aus unvollstaendiger Historie -
 * fehlt einem Slice der Nullphase das Profil, faellt NUR omittedU auf
 * null, das Alter bleibt belegt; laeuft am Marker gar keine Null, gibt es
 * keine Phase.
 */
object BasalGapRechner {

    /** Ein Zeitscheiben-Blick; Slices AUFSTEIGEND, letzter = Markermoment. */
    data class Slice(
        val tsMs: Long,
        /** Absolute TBR-Rate [U/h]; null = keine TBR laeuft (Profil) oder
         *  nicht absolutisierbar (Profil des Zeitpunkts fehlt). */
        val tbrAbsUph: Double?,
        /** Profilbasal [U/h] zum Slice-Zeitpunkt; null = unbekannt. */
        val profilUph: Double?,
    )

    data class Nullphase(
        /** Minuten, seit die zusammenhaengende Null laeuft. Deckt das
         *  Slice-Fenster die Phase nicht ganz, ist das eine UNTERGRENZE. */
        val ageMin: Int,
        /** Ausgelassenes Profilbasal ueber die Phase [U]; null, sobald ein
         *  Slice der Phase kein Profil traegt. */
        val omittedU: Double?,
    )

    /**
     * @return null, wenn am Marker (letzter Slice) keine Null laeuft.
     * Die Null-Erkennung nutzt DIESELBE Toleranz wie die Kanalpolitik
     * ([TbrPolicy.isZeroRate]) - eine 0,05-U/h-Restrate ist keine Null.
     */
    fun nullphase(slices: List<Slice>, basalStepUph: Double, stepMs: Long): Nullphase? {
        if (slices.isEmpty() || stepMs <= 0L) return null
        fun istNull(s: Slice) = s.tbrAbsUph != null && TbrPolicy.isZeroRate(s.tbrAbsUph, basalStepUph)
        if (!istNull(slices.last())) return null
        var i = slices.size - 1
        var omitted: Double? = 0.0
        var minuten = 0.0
        while (i >= 0 && istNull(slices[i])) {
            minuten += stepMs / 60_000.0
            val p = slices[i].profilUph
            omitted = if (omitted != null && p != null) omitted + p * (stepMs / 3_600_000.0) else null
            i--
        }
        return Nullphase(ageMin = minuten.toInt(), omittedU = omitted)
    }
}
