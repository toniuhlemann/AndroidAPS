package app.aaps.fuse.plugin.replay

import app.aaps.fuse.core.controller.BasalRecoverySearch
import app.aaps.fuse.core.controller.CandidateSearch
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import app.aaps.fuse.core.profile.BasalSlot

/**
 * DER RIG FUER DIE TEILBASAL-RUECKKEHR - was die Guard-Umkehr in den
 * aufgezeichneten Nullphasen freigegeben HAETTE.
 *
 * ===================================================================
 * DIE EINE EINSCHRAENKUNG, DIE ALLES ANDERE EINRAHMT
 * ===================================================================
 * [BasalRecoverySearch] braucht die BAHN als Punktreihe. Der Trail
 * traegt sie nicht - er traegt je Zyklus nur ihr MINIMUM
 * (`decision/minLowerMgdl`) und den Offset, an dem es liegt. Ein
 * exakter Nachlauf der Suche ist aus dem aufgezeichneten Trail
 * deshalb nicht moeglich, und ohne zusaetzlichen Live-Export wird er
 * es auch nicht.
 *
 * Was statt dessen moeglich ist - und was dieser Rig tut:
 *
 *     Die Suche wird mit einer FLACHGELEGTEN Bahn aufgerufen, deren
 *     jeder Punkt auf dem aufgezeichneten Minimum L0 liegt.
 *
 * Das ist kein Modellfit und keine Naeherung, sondern eine SCHRANKE
 * mit bekanntem Vorzeichen. Die Suche urteilt ueber
 * `min_i (basis[i] - r * s[i])`, wobei `s[i]` die Senkung an Punkt i
 * je 1 U/h ist. `s[i]` waechst monoton (es sammelt nichtnegative
 * Aktivitaet), also gilt fuer jedes i:
 *
 *     basis[i] - r*s[i]  >=  min_j basis[j] - r*max_j s[j]
 *                        =   L0 - r*s[letzter Punkt]
 *
 * und die rechte Seite ist genau das, was die Suche auf der flachen
 * Bahn ausrechnet. Jede Rate, die hier besteht, haette also auch auf
 * der ECHTEN Bahn bestanden. **Das Ergebnis ist eine untere Schranke
 * fuer r\*, niemals eine Ueberschaetzung.**
 *
 * Die Schranke ist einseitig: eine obere Schranke gaebe es nur ueber
 * `min_i s[i]`, und das ist am Anker null - sie waere immer der
 * Profildeckel und damit wertlos. Wo hier eine Rate herauskommt, ist
 * sie belastbar; wo hier 0 herauskommt, ist damit NICHT gesagt, dass
 * die echte Bahn nichts getragen haette.
 *
 * ===================================================================
 * WAS ECHT IST
 * ===================================================================
 * Gerechnet wird mit der PRODUKTIVEN Suche (nicht nachgebaut), dem
 * PRODUKTIVEN Insulinplugin als Kern (nicht nachgebaut), dem im Trail
 * aufgezeichneten Guard-Boden, ISF, Haftungshorizont und Insulinmodell.
 * Auch das Eintrittstor ist die produktive Bedingung, Feld fuer Feld.
 *
 * ===================================================================
 * WAS NICHT BEHAUPTET WIRD
 * ===================================================================
 * Kein Wort ueber Glukoseverlaeufe. Der aufgezeichnete Trail gehoert
 * zu einem Verlauf, in dem die Null WEITERLIEF; was bei gegebener
 * Teilrate passiert waere, steht darin nicht und laesst sich daraus
 * nicht ablesen. Dieser Rig sagt ausschliesslich, WELCHE RATE DIE
 * SUCHE FREIGEGEBEN HAETTE - eine Aussage ueber den Regler, nicht
 * ueber den Koerper.
 */
object TeilbasalRig {

    /** Ein Zyklus, so weit der Trail ihn fuer diese Frage hergibt. */
    data class RigZyklus(
        val computeTs: Long,
        val sourceTs: Long,
        val zeroActive: Boolean,
        /** LowThreat-Verdikt ist NONE - der akute Schutzgrund ist weg. */
        val verdictNone: Boolean,
        val signalHealthy: Boolean,
        val measuredLow: Boolean,
        val descentRiskActive: Boolean,
        val ukfRatePerMin: Double?,
        /** L0: das aufgezeichnete Bahnminimum ueber den Haftungshorizont. */
        val minLowerMgdl: Double?,
        /** Wo dieses Minimum liegt (`timeToMinSafetyLowerCombinedMin`). */
        val baselineBindenderOffsetMin: Int?,
        /**
         * Erster Offset, an dem die untere Bahn den Boden unterschreitet;
         * `null` = nie im Fenster. Damit ist die Guard-Frage fuer JEDEN
         * kuerzeren Horizont h beantwortbar (bestanden genau dann, wenn
         * `null` oder `> h`) - die RATE bei h aber nicht, weil dafuer das
         * Niveau bei h noetig waere und der Trail nur das Minimum traegt.
         */
        val timeToFloorMin: Int?,
        val guardFloorMgdl: Double?,
        val isfMgdlPerU: Double?,
        val liabilityHorizonMin: Int?,
        /**
         * Profilbasal. ACHTUNG: im aufgezeichneten Trail ist das der
         * MARKER-SCHNAPPSCHUSS (`preMarkerScheduledBasalUph`), der Stunden
         * alt sein kann - das laufende Feld kam erst mit rs48 und ist in
         * keiner Aufzeichnung. Wo der Profildeckel bindet, ist die Zahl
         * deshalb nur so gut wie dieser Schnappschuss; wo der Guard
         * bindet, ist sie fuer das Ergebnis ohne Belang.
         */
        val profilbasalUph: Double?,
        /** Publizierte SMB-Menge dieses Zyklus [U]. */
        val smbPublishedU: Double,
    )

    enum class Zustand { KEINE_NULL, ZERO, PARTIAL }

    data class RigErgebnis(
        val zustand: Zustand,
        val rateUPerH: Double,
        val streak: Int,
        val torOffen: Boolean,
        val suche: BasalRecoverySearch.Ergebnis?,
    )

    /** Derselbe Eintrittszaehler wie in der Produktion. */
    const val EINTRITT_ZYKLEN = 3

    /** Derselbe Anschlussabstand wie in der Produktion. */
    const val ANSCHLUSS_MAX_MS = 90_000L

    /** Die produktive UKF-Schwelle des Eintrittstors [mg/dl je min]. */
    const val UKF_SCHWELLE = -0.03

    /**
     * Das produktive Eintrittstor, Feld fuer Feld - ausdruecklich OHNE
     * `q1NichtFallend`: der milde Restabfall ist der Fall, fuer den die
     * Teilstufe gebaut ist.
     *
     * [ukfSchwelle] ist NUR fuer die Messung parametrisiert. Der Standard
     * ist der Produktionswert; ein anderer Wert misst eine ANDERE Regel
     * und muss als solcher ausgewiesen werden.
     */
    fun torOffen(z: RigZyklus, ukfSchwelle: Double? = UKF_SCHWELLE): Boolean =
        z.zeroActive &&
            !z.measuredLow &&
            !z.descentRiskActive &&
            z.signalHealthy &&
            z.verdictNone &&
            // `null` = KEIN zusaetzliches UKF-Tor. Alle uebrigen Bedingungen
            // bleiben, und die vollstaendige BasalRecoverySearch ebenfalls -
            // der Guard ist dann die einzige Fallpruefung statt der zweiten.
            (ukfSchwelle == null ||
                (z.ukfRatePerMin != null && z.ukfRatePerMin.isFinite() && z.ukfRatePerMin >= ukfSchwelle))

    /**
     * Die FLACHGELEGTE Bahn auf Niveau [minLower] - siehe Klassenkopf.
     * Der Anker liegt auf demselben Niveau; er traegt keine Ratenwirkung
     * und kann das Minimum daher nur nach unten, nie nach oben ziehen.
     */
    fun flacheBahn(minLower: Double, anchorTs: Long, horizontMin: Int): PredictorResult {
        val pts = (1..horizontMin).map {
            TrajectoryPoint(it, anchorTs + it * 60_000L, minLower, minLower, 0.0, 0.0, 0.0)
        }
        return PredictorResult(
            points = pts, predictionAnchorTs = anchorTs, bgAtAnchor = minLower,
            minMeanBg = minLower, minLowerBg = minLower, timeToMinLowerMin = horizontMin,
            bgAtHorizonMean = minLower, bgAtHorizonLower = minLower,
            lineageKind = "RIG_FLAT", trajectoryContentHash = "rig",
            iobArraySpanMin = 600.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    /**
     * Ruft die PRODUKTIVE Suche auf. `null` heisst: der Trail traegt fuer
     * diesen Zyklus nicht genug (fail-closed, gezaehlt als Ablehnung
     * TRAIL_UNVOLLSTAENDIG).
     */
    fun rate(
        z: RigZyklus,
        kernelFuer: (Long) -> UnitInsulinKernel?,
        basalStepUPerH: Double,
        tbrDauerMin: Int,
        horizontMin: Int? = null,
        /**
         * ERSATZDECKEL fuer Trails OHNE Profilbasal (alles vor rs47).
         *
         * Das ist AUSDRUECKLICH KEIN PROFIL, sondern eine bewusst hoch
         * gesetzte Schranke, damit die Suche ueberhaupt laufen kann. Nur
         * Ergebnisse mit `begrenzung == GUARD` sind dann Ratenaussagen -
         * und auch die nur unter der Annahme, dass das echte Profilbasal
         * mindestens so hoch lag. Alles mit `begrenzung == PROFIL` heisst
         * hier NUR "der Guard haette mehr als den Ersatzdeckel getragen"
         * und ist KEINE Rate.
         */
        ersatzdeckelUPerH: Double? = null,
    ): BasalRecoverySearch.Ergebnis? {
        val l0 = z.minLowerMgdl ?: return null
        val floor = z.guardFloorMgdl ?: return null
        val isf = z.isfMgdlPerU?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val h = horizontMin ?: z.liabilityHorizonMin ?: return null
        val profil = z.profilbasalUph?.takeIf { it.isFinite() && it > 0.0 }
            ?: ersatzdeckelUPerH?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        val kernel = kernelFuer(z.computeTs) ?: return null
        val anker = z.computeTs
        val band = CandidateSearch.Band(
            releaseTargetLowMgdl = 100.0, releaseTargetHighMgdl = 140.0,
            demandDeadbandMgdl = 10.0, guardFloorMgdl = floor,
            releaseHorizonMin = 30, liabilityHorizonMin = h,
        )
        val spanne = 24 * 3_600_000L
        return BasalRecoverySearch.hoechsteSichereRate(
            prediction = flacheBahn(l0, anker, h),
            kernel = kernel,
            isfSlots = listOf(IsfSlot(anker - spanne, anker + spanne, isf)),
            band = band,
            basalSlots = listOf(BasalSlot(anker - spanne, anker + spanne, profil)),
            basalStepUPerH = basalStepUPerH,
            tbrDurationMin = tbrDauerMin,
            pruefHorizontMin = h,
        )
    }

    /**
     * Der Zustandslauf ueber die Zyklen - dieselbe Zustandsmaschine wie
     * im Runner: Streak nur bei ANSCHLUSS auf der SIGNAL-Uhr (streng
     * steigend, hoechstens 90 s), Rueckfall auf ZERO im selben Zyklus,
     * sobald eine Bedingung faellt oder die Suche nichts traegt.
     */
    fun lauf(
        zyklen: List<RigZyklus>,
        kernelFuer: (Long) -> UnitInsulinKernel?,
        basalStepUPerH: Double = 0.05,
        tbrDauerMin: Int = 30,
        horizontMin: Int? = null,
        ukfSchwelle: Double? = UKF_SCHWELLE,
        eintrittZyklen: Int = EINTRITT_ZYKLEN,
        ersatzdeckelUPerH: Double? = null,
    ): List<Pair<RigZyklus, RigErgebnis>> {
        var streak = 0
        var letzterSourceTs = 0L
        return zyklen.map { z ->
            if (!z.zeroActive) {
                streak = 0; letzterSourceTs = 0L
                return@map z to RigErgebnis(Zustand.KEINE_NULL, 0.0, 0, false, null)
            }
            val offen = torOffen(z, ukfSchwelle)
            streak = if (offen) {
                val anschluss = letzterSourceTs > 0L &&
                    z.sourceTs > letzterSourceTs &&
                    z.sourceTs - letzterSourceTs <= ANSCHLUSS_MAX_MS
                if (anschluss) streak + 1 else 1
            } else 0
            if (offen) letzterSourceTs = z.sourceTs
            val suche = if (offen && streak >= eintrittZyklen)
                rate(z, kernelFuer, basalStepUPerH, tbrDauerMin, horizontMin, ersatzdeckelUPerH) else null
            val aktiv = suche != null && suche.reject == null && suche.rateUPerH > 0.0
            z to RigErgebnis(
                zustand = if (aktiv) Zustand.PARTIAL else Zustand.ZERO,
                rateUPerH = if (aktiv) suche.rateUPerH else 0.0,
                streak = streak, torOffen = offen, suche = suche,
            )
        }
    }
    // =====================================================================
    // DIE BILANZ
    // =====================================================================

    /** Die Erneuerungsregel der Produktion (`TbrPolicy.Config`). */
    const val ERNEUERN_AB_RESTMIN = 10

    data class Bilanz(
        val nullphasen: List<Double>,
        val minZero: Double,
        val minPartial: Double,
        val minReleased: Double,
        /** `null` = kein Profilbasal im Trail, also KEINE Mengenaussage. */
        val basalU: Double?,
        val raten: List<Double>,
        val zyklenProfilbegrenzt: Int,
        val zyklenGuardbegrenzt: Int,
        val eintritte: Int,
        val rueckfaelle: Int,
        /**
         * AKTUATIONSKANTEN nach Kommando-Unterdrueckung - gezaehlt mit
         * derselben Regel wie `TbrPolicy`: gleiche Rate (plus/minus halber
         * Pumpenschritt) UND Restlaufzeit >= 10 min ergibt KEIN Kommando.
         * Gezaehlt werden Teilraten UND die Rueckfaelle auf Null, denn
         * beide sind Pumpenkommandos.
         */
        val kanten: Int,
        val bindendeOffsets: Map<Int, Int>,
        val ablehnungen: Map<String, Int>,
        val ohneSuche: Int,
        /**
         * DIE SMB-MENGE, DIE DIE TEILSTUFE UNTERDRUECKT HAETTE - also was
         * die AUFZEICHNUNG in genau den Zyklen abgab, die zu PARTIAL
         * wuerden.
         *
         * DAS IST NICHT NULL, und die Annahme, es muesse null sein, war
         * falsch: eine laufende Schutz-Null sperrt den schnellen Kanal
         * NICHT von sich aus (`decision.smbU` bleibt unangetastet, solange
         * keine Teilstufe laeuft). Erst die Teilstufe sperrt ihn - ueber
         * die Blockursache PARTIAL_RECOVERY im Uebersetzer und zusaetzlich
         * ueber die harte Nullung im Runner.
         *
         * Diese Zahl ist deshalb eine KOSTENGROESSE, keine Pruefgroesse:
         * so viel schneller Kanal gibt die Teilstufe auf, um langsames
         * Basal zurueckzuholen. Sie gehoert neben die zurueckgeholte
         * Basalmenge, nicht in eine Zusicherung.
         */
        val smbUnterdruecktU: Double,
    )

    fun bilanz(
        lauf: List<Pair<RigZyklus, RigErgebnis>>,
        basalStepUPerH: Double = 0.05,
        tbrDauerMin: Int = 30,
        maxLueckeMin: Double = 5.0,
    ): Bilanz {
        fun dauer(i: Int): Double {
            val bis = lauf.getOrNull(i + 1)?.first?.computeTs ?: return 0.0
            val dt = (bis - lauf[i].first.computeTs) / 60_000.0
            return if (dt in 0.0..maxLueckeMin) dt else 0.0
        }
        var mz = 0.0; var mp = 0.0; var mr = 0.0
        var menge = 0.0; var mengeBekannt = true; var smb = 0.0
        var ein = 0; var rueck = 0; var kanten = 0
        var profilBegrenzt = 0; var guardBegrenzt = 0; var ohneSuche = 0
        val raten = mutableListOf<Double>()
        val offsets = mutableMapOf<Int, Int>()
        val ablehnungen = mutableMapOf<String, Int>()
        val phasen = mutableListOf<Double>()
        var phaseVon = 0L
        var vor = Zustand.KEINE_NULL
        // Der Zustand der PUMPE, nicht der des Reglers
        var laufendeRate = Double.NaN
        var laufendSeit = 0L
        lauf.forEachIndexed { i, (z, e) ->
            val m = dauer(i)
            when (e.zustand) {
                Zustand.ZERO -> mz += m
                Zustand.PARTIAL -> {
                    mp += m
                    raten += e.rateUPerH
                    if (z.profilbasalUph == null) mengeBekannt = false
                    menge += e.rateUPerH * m / 60.0
                    smb += z.smbPublishedU
                    when (e.suche?.begrenzung) {
                        BasalRecoverySearch.Begrenzung.PROFIL -> profilBegrenzt++
                        BasalRecoverySearch.Begrenzung.GUARD  -> guardBegrenzt++
                        else                                  -> Unit
                    }
                    e.suche?.bindenderOffsetMin?.let { offsets[it] = (offsets[it] ?: 0) + 1 }
                }
                Zustand.KEINE_NULL -> mr += m
            }
            e.suche?.reject?.name?.let { ablehnungen[it] = (ablehnungen[it] ?: 0) + 1 }
            if (e.torOffen && e.streak >= EINTRITT_ZYKLEN && e.suche == null) ohneSuche++
            if (z.zeroActive && phaseVon == 0L) phaseVon = z.computeTs
            if (!z.zeroActive && phaseVon != 0L) {
                phasen += (lauf[i - 1].first.computeTs - phaseVon) / 60_000.0; phaseVon = 0L
            }
            if (e.zustand == Zustand.PARTIAL && vor != Zustand.PARTIAL) ein++
            if (vor == Zustand.PARTIAL && e.zustand != Zustand.PARTIAL) rueck++
            // KOMMANDO-UNTERDRUECKUNG, exakt wie TbrPolicy
            val gewuenscht = when (e.zustand) {
                Zustand.PARTIAL    -> e.rateUPerH
                Zustand.ZERO       -> 0.0
                Zustand.KEINE_NULL -> Double.NaN     // dort steuert dieser Rig nicht
            }
            if (!gewuenscht.isNaN()) {
                val restMin = if (laufendeRate.isNaN()) -1.0
                else tbrDauerMin - (z.computeTs - laufendSeit) / 60_000.0
                val gleich = !laufendeRate.isNaN() &&
                    kotlin.math.abs(laufendeRate - gewuenscht) <= basalStepUPerH / 2.0
                if (!(gleich && restMin >= ERNEUERN_AB_RESTMIN)) {
                    kanten++; laufendeRate = gewuenscht; laufendSeit = z.computeTs
                }
            } else { laufendeRate = Double.NaN; laufendSeit = 0L }
            vor = e.zustand
        }
        if (phaseVon != 0L) phasen += (lauf.last().first.computeTs - phaseVon) / 60_000.0
        return Bilanz(
            nullphasen = phasen, minZero = mz, minPartial = mp, minReleased = mr,
            basalU = if (mengeBekannt) menge else null,
            raten = raten, zyklenProfilbegrenzt = profilBegrenzt, zyklenGuardbegrenzt = guardBegrenzt,
            eintritte = ein, rueckfaelle = rueck, kanten = kanten,
            bindendeOffsets = offsets.toSortedMap(), ablehnungen = ablehnungen,
            ohneSuche = ohneSuche, smbUnterdruecktU = smb,
        )
    }
}
