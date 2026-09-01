package app.aaps.fuse.core.controller

import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.profile.ProfileSlots
import kotlin.math.floor

/**
 * DIE HOECHSTE BASALRATE, DIE DIE SCHUTZBAHN NOCH TRAEGT.
 *
 *     r* = max { r in [0, Profilbasal] : minSafetyLower(Bahn MIT r) >= guardFloor }
 *
 * WARUM AUS DEM GUARD UND NICHT AUS DEM BEDARF. Die naheliegende Idee,
 * eine Teilbasal-Rate aus `insulinReq` zu bilden, scheitert dreifach:
 * die Zahl ist im Guard-Ausstieg gar nicht belegt (der Controller endet
 * davor), ihr Tiefpunktschutz liegt im Nachbartor statt in ihr selbst -
 * und vor allem enthaelt sie den Effekt der laufenden Null bereits. Das
 * negative Basal-IOB hebt die Bahn, erzeugt also genau den Bedarf, den
 * eine daraus gebildete Rate bediente. Weil in 30 min nur die AKTIVITAET
 * der ausgelassenen Menge sichtbar wird (grob 5-10 %), muesste eine
 * solche Rate ein Vielfaches anfordern und naehme es sich im naechsten
 * Zyklus selbst wieder weg: eine Rueckkopplung mit Schleifenverstaerkung
 * um 10-20, kein wegdividierbarer Faktor.
 *
 * Diese Suche kann das strukturell nicht: der Kandidat wird IN die Bahn
 * eingerechnet, BEVOR geurteilt wird. Seine eigene Wirkung ist im Urteil
 * enthalten - er kann sich nicht selbst finanzieren.
 *
 * WARUM NICHT [CandidateSearch.search]. Die traegt SMB-Bedarfstore,
 * Budgets und die Annahme einer EINZELDOSIS. Hier wird nur die pure
 * Bahn-/Guard-Pruefung nachgebaut - dieselben Regeln (rechte
 * Integrationsregel, Rasterpruefung, Anker-Minimum), aber fuer eine
 * ueber die TBR-Dauer VERTEILTE Menge.
 *
 * DER VORZEICHENFEHLER, DEN DIESE DATEI VERMEIDET (Review-P0): bei einer
 * MAXIMALRATEN-Suche ist "alles am Fensterende geliefert" NICHT
 * konservativ. Spaete Abgabe heisst weniger Wirkung im Fenster, die Bahn
 * bleibt hoeher, und die Suche gibt eine GROESSERE Rate frei - fail-OPEN.
 * Deshalb wird jede Minutenmenge mit dem FRUEHESTEN plausiblen
 * Lieferzeitpunkt ihrer Minute eingesetzt (dieselbe Richtung, die
 * `PendingInsulinEffect.deliveryTs` fuer die Bahn vorschreibt).
 */
object BasalRecoverySearch {

    /** Warum keine Rate bestimmt werden konnte. Jede Ablehnung ist ein
     *  benannter Grund - nie eine stille Null. */
    enum class Reject {
        INVALID_INPUT,
        INVALID_BAND,
        HORIZON_MISSING,
        GRID_INCONSISTENT,
        ISF_SLOT_MISSING,
        /** Der Kern deckt das Bewertungsfenster der spaetesten
         *  Minutenmenge nicht mehr. */
        MODEL_HORIZON_TOO_SHORT,
        NON_FINITE,
    }

    data class Ergebnis(
        /**
         * Die groesste Rate auf dem Pumpenraster, die die Bahn traegt
         * [U/h]. 0 = keine, dann gilt weiter die Schutz-Null.
         */
        val rateUPerH: Double,
        /** != null: die Bahn ist nicht auswertbar - fail-closed auf Null. */
        val reject: Reject?,
        /** Wieviele Ticks geprueft wurden (Diagnose). */
        val gepruefteTicks: Int,
        /** Die Sicherheitsbahn bei [rateUPerH]; NaN wenn keine Rate. */
        val minLowerBeiRate: Double,
        /**
         * Die Sicherheitsbahn beim NAECHSTHOEHEREN Tick. NaN, wenn der
         * naechste Tick ueber dem Profilbasal laege - dann ist die
         * Obergrenze das Profil, nicht der Guard.
         *
         * DIESE ZAHL IST DER VERTRAG: liegt sie unter dem Guard-Boden,
         * ist die gewaehlte Rate nachweislich die groesste tragbare.
         */
        val minLowerNaechsterTick: Double,
    ) {

        /** Der naechste Tick lag ueber dem Profilbasal - die Rate ist
         *  durch das PROFIL begrenzt, nicht durch den Guard. */
        val durchProfilBegrenzt: Boolean get() = minLowerNaechsterTick.isNaN() && reject == null
    }

    private fun ab(reject: Reject) = Ergebnis(0.0, reject, 0, Double.NaN, Double.NaN)

    /**
     * @param kernel der Einheitskern. Er traegt seinen eigenen
     *   `deliveryTs`; gebraucht wird nur seine FORM, weil die Aktivitaet
     *   allein vom Abstand zur Lieferung abhaengt. Jede Minutenmenge wird
     *   deshalb ueber eine Zeitverschiebung ausgewertet.
     * @param tbrDurationMin die Dauer, ueber die die Rate laufen wuerde.
     *   Sie bestimmt, wieviele Minutenmengen eingesetzt werden.
     */
    @Suppress("LongParameterList")
    fun hoechsteSichereRate(
        prediction: PredictorResult,
        kernel: UnitInsulinKernel,
        isfSlots: List<IsfSlot>,
        band: CandidateSearch.Band,
        scheduledBasalUPerH: Double,
        basalStepUPerH: Double,
        tbrDurationMin: Int,
        restraint: PredictorResult? = null,
    ): Ergebnis {
        if (!scheduledBasalUPerH.isFinite() || scheduledBasalUPerH <= 0.0) return ab(Reject.INVALID_INPUT)
        if (!basalStepUPerH.isFinite() || basalStepUPerH <= 0.0) return ab(Reject.INVALID_INPUT)
        if (tbrDurationMin <= 0) return ab(Reject.INVALID_INPUT)
        if (band.violation() != null) return ab(Reject.INVALID_BAND)

        val points = prediction.points
        if (points.isEmpty()) return ab(Reject.HORIZON_MISSING)
        val liabilityIdx = points.indexOfFirst { it.offsetMin == band.liabilityHorizonMin }
        if (liabilityIdx < 0) return ab(Reject.HORIZON_MISSING)
        val anchorTs = prediction.predictionAnchorTs
        if (!prediction.bgAtAnchor.isFinite()) return ab(Reject.GRID_INCONSISTENT)
        // Dieselbe Rasterpruefung wie in der Kandidatensuche: das
        // Minutenraster ist die Voraussetzung der Integration.
        for (i in points.indices) {
            val p = points[i]
            if (p.offsetMin != i + 1 || p.tsMs != anchorTs + (i + 1) * 60_000L) return ab(Reject.GRID_INCONSISTENT)
        }

        // ---- DIE VERTEILTE MENGE, EINMAL VORBERECHNET --------------------
        //
        // Die Senkung ist LINEAR in der Rate. Deshalb wird hier die
        // kumulierte Senkung je BAHNPUNKT fuer eine Rate von 1 U/h
        // gebildet; jeder Kandidat ist danach eine Multiplikation.
        //
        // Jede Minute m der TBR traegt die Menge rate/60 und wird mit dem
        // FRUEHESTEN plausiblen Lieferzeitpunkt ihrer Minute eingesetzt -
        // also dem Beginn des Minutenintervalls. Frueh ist hier die
        // konservative Richtung: mehr Wirkung im Fenster, tiefere Bahn,
        // kleinere freigegebene Rate.
        val senkungProUPerH = DoubleArray(liabilityIdx + 1)
        for (m in 0 until tbrDurationMin) {
            val lieferTs = anchorTs + m * 60_000L
            // Die Form des Kerns haengt nur vom Abstand zur Lieferung ab -
            // deshalb wird der Zeitpunkt verschoben, nicht der Kern kopiert.
            val versatz = lieferTs - kernel.deliveryTs
            if (!kernel.covers(points[liabilityIdx].tsMs - versatz)) return ab(Reject.MODEL_HORIZON_TOO_SHORT)
            var acc = 0.0
            for (i in 0..liabilityIdx) {
                val p = points[i]
                val isf = ProfileSlots.isfAt(isfSlots, p.tsMs) ?: return ab(Reject.ISF_SLOT_MISSING)
                val previousTs = if (i == 0) anchorTs else points[i - 1].tsMs
                val intervalStart = maxOf(previousTs, lieferTs)
                val overlapMin = if (p.tsMs > intervalStart) (p.tsMs - intervalStart) / 60_000.0 else 0.0
                if (overlapMin > 0.0)
                    acc += kernel.activityAt(p.tsMs - versatz, 1.0) * isf * overlapMin
                if (!acc.isFinite()) return ab(Reject.NON_FINITE)
                // Menge dieser Minute bei 1 U/h: 1/60 U.
                senkungProUPerH[i] += acc / 60.0
            }
        }

        // ---- DIE BAHN OHNE KANDIDAT --------------------------------------
        val basis = DoubleArray(liabilityIdx + 1)
        for (i in 0..liabilityIdx) {
            val b = CandidateSearch.safetyLowerAtForRecovery(prediction, restraint, i)
            if (!b.isFinite()) return ab(Reject.NON_FINITE)
            basis[i] = b
        }
        val ankerMin = CandidateSearch.safetyAnchorForRecovery(prediction, restraint)
        if (!ankerMin.isFinite()) return ab(Reject.NON_FINITE)

        fun minLowerBei(rate: Double): Double {
            var min = ankerMin
            for (i in 0..liabilityIdx) {
                val v = basis[i] - rate * senkungProUPerH[i]
                if (v < min) min = v
            }
            return min
        }

        // ---- DIE RASTERSUCHE ---------------------------------------------
        //
        // Kandidaten ausschliesslich auf dem Pumpenraster in
        // [0, Profilbasal]. Gesucht ist der GROESSTE tragbare Tick; der
        // naechsthoehere muss den Guard verletzen (oder ueber dem Profil
        // liegen) - genau das weist das Ergebnis aus.
        val maxTicks = floor(scheduledBasalUPerH / basalStepUPerH + 1e-9).toInt()
        var bester = 0
        var minLowerBester = Double.NaN
        for (t in 1..maxTicks) {
            val rate = t * basalStepUPerH
            val m = minLowerBei(rate)
            if (!m.isFinite()) return ab(Reject.NON_FINITE)
            if (m >= band.guardFloorMgdl) { bester = t; minLowerBester = m } else break
        }
        val naechster = bester + 1
        val minLowerNaechster =
            if (naechster <= maxTicks) minLowerBei(naechster * basalStepUPerH) else Double.NaN
        return Ergebnis(
            rateUPerH = bester * basalStepUPerH,
            reject = null,
            gepruefteTicks = maxTicks,
            minLowerBeiRate = if (bester > 0) minLowerBester else minLowerBei(0.0),
            minLowerNaechsterTick = minLowerNaechster,
        )
    }
}
