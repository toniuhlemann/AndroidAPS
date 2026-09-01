package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.TbrPolicy
import app.aaps.fuse.plugin.ledger.EpisodeBudgets

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

    /**
     * Groesster Zeitschritt [min], den ein einzelner Tick der laufenden
     * Bilanz zuschreibt. Ein Zyklus dauert rund eine Minute; alles darueber
     * ist eine Luecke (Zyklusausfall, Neustart, Uhrsprung). Die
     * ueberschiessende Zeit wird NICHT gezaehlt - eine Bilanz, die eine
     * halbe Stunde Prozesspause als Nullbasal verbucht, waere schlimmer als
     * eine, die zu wenig zeigt. Was dabei verloren geht, weist
     * `gapCappedMin` aus.
     */
    const val ZERO_TALLY_MAX_STEP_MIN = 3.0

    /** Ab welcher UKF-Rate [mg/dl/min] eine Lage nicht mehr als "faellt
     *  weiter" gilt. Bewusst leicht negativ: exakt 0 gibt es im Rauschen
     *  nicht, und ein Hauch Abwaertsdrift ist noch kein Fall. */
    const val ZERO_TALLY_FLAT_RATE = -0.03

    /**
     * EIN TICK DER LAUFENDEN NULLPHASEN-BILANZ - pur, ohne Zustand, ohne Uhr.
     *
     * @param vorher der Stand des letzten Zyklus; null = es lief keine Phase.
     * @param zeroActive laeuft in DIESEM Zyklus eine Null-TBR (Pumpensicht)?
     * @param scheduledBasalUph das LAUFENDE Profilbasal; null = unbekannt,
     *   dann waechst die Zeit weiter, die MENGE aber nicht (lieber eine
     *   unvollstaendige Menge als eine geschaetzte).
     * @param reasonPresent liegt in diesem Zyklus ein Schutzgrund an
     *   (LowThreat-Verdikt)? Steuert die Zeitklassen, sonst nichts.
     * @param fallRatePerMin die gemessene Rate; null = keine Aussage, dann
     *   zaehlt der Zyklus NICHT als flach (fail-closed fuer die Klasse, die
     *   spaeter als "vermeidbar" gelesen wird).
     * @return der neue Stand, oder null wenn keine Phase (mehr) laeuft.
     */
    fun zeroTally(
        vorher: EpisodeBudgets.ZeroPhaseTally?,
        nowTs: Long,
        zeroActive: Boolean,
        scheduledBasalUph: Double?,
        reasonPresent: Boolean,
        fallRatePerMin: Double?,
    ): EpisodeBudgets.ZeroPhaseTally? {
        if (!zeroActive || nowTs <= 0L) return null
        if (vorher == null) {
            // Der ERSTE Zyklus einer Phase traegt keine Zeit: die Null hat
            // gerade erst begonnen, und rueckwaerts zu raten, wie lange sie
            // vor diesem Zyklus schon lief, waere eine Erfindung.
            return EpisodeBudgets.ZeroPhaseTally(
                sinceTs = nowTs, lastTickTs = nowTs, minutes = 0.0, omittedU = 0.0,
                reasonAbsentMin = 0.0, flatAbsentMin = 0.0, gapCappedMin = 0.0,
            )
        }
        val rohMin = (nowTs - vorher.lastTickTs) / 60_000.0
        // Rueckwaerts laufende Uhr schreibt nichts fort (und dreht nichts
        // zurueck): der Zeitstempel wandert mit, die Bilanz bleibt stehen.
        if (rohMin <= 0.0) return vorher.copy(lastTickTs = nowTs)
        val dt = minOf(rohMin, ZERO_TALLY_MAX_STEP_MIN)
        val flach = !reasonPresent && fallRatePerMin != null &&
            fallRatePerMin.isFinite() && fallRatePerMin >= ZERO_TALLY_FLAT_RATE
        val menge = scheduledBasalUph
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { it * dt / 60.0 } ?: 0.0
        return vorher.copy(
            lastTickTs = nowTs,
            minutes = vorher.minutes + dt,
            omittedU = vorher.omittedU + menge,
            reasonAbsentMin = vorher.reasonAbsentMin + if (reasonPresent) 0.0 else dt,
            flatAbsentMin = vorher.flatAbsentMin + if (flach) dt else 0.0,
            gapCappedMin = vorher.gapCappedMin + (rohMin - dt),
        )
    }

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
