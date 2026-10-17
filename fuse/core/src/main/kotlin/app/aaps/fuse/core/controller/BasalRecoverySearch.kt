package app.aaps.fuse.core.controller

import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.profile.BasalSlot
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

    /** Was die Rate letztlich begrenzt hat. */
    enum class Begrenzung {
        /** Der naechsthoehere Tick verletzt den Guard. */
        GUARD,
        /** Das Profilbasal ist erreicht - der Guard bindet gar nicht. */
        PROFIL,
        /** Schon der erste Tick verletzt den Guard (oder die Baseline liegt
         *  selbst unter dem Boden): es bleibt bei der Null. */
        KEINE_RATE,
        /** Die Bahn war nicht auswertbar. */
        ABLEHNUNG,
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
        /**
         * Das Minimum der Sicherheitsbahn OHNE jede Teilrate. Liegt es
         * schon unter dem Guard-Boden, kann keine Rate tragen - und dann
         * ist die Ursache nicht die Teilstufe, sondern die Bahn selbst.
         */
        val baselineMinLowerMgdl: Double,
        /**
         * WO das bindende Minimum liegt [min nach dem Anker], gerechnet
         * fuer den gewaehlten Tick (bei Rate 0 fuer die Baseline);
         * -1 = der Anker selbst war das Minimum.
         *
         * DIESE ZAHL BEANTWORTET DIE HORIZONTFRAGE (Review-P1): liegt der
         * bindende Punkt weit hinten, hat eine FERNE tiefe Bahn die Rate
         * verhindert, obwohl der akute Schutzgrund laengst weg ist. Ohne
         * sie waere im Replay nicht zu sehen, welcher Horizont tatsaechlich
         * bindet - und jede Horizontwahl bliebe eine Behauptung.
         */
        val bindenderOffsetMin: Int,
        /** Bis wohin geprueft wurde [min] - explizit, nie implizit. */
        val pruefHorizontMin: Int,
        val begrenzung: Begrenzung,
        /** Der wirksame Profildeckel [U/h]: das KLEINSTE Profilbasal im
         *  TBR-Fenster, nicht das am Entscheidungszeitpunkt. */
        val profildeckelUPerH: Double,
    ) {

        /** Der naechste Tick lag ueber dem Profilbasal - die Rate ist
         *  durch das PROFIL begrenzt, nicht durch den Guard. */
        val durchProfilBegrenzt: Boolean get() = begrenzung == Begrenzung.PROFIL
    }

    private fun ab(reject: Reject) = Ergebnis(
        0.0, reject, 0, Double.NaN, Double.NaN, Double.NaN, -1, 0, Begrenzung.ABLEHNUNG, 0.0,
    )

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
        /**
         * DAS PROFILBASAL UEBER DAS GANZE TBR-FENSTER, nicht nur am
         * Entscheidungszeitpunkt (Review-P1).
         *
         * Faellt das Profil innerhalb der laufenden TBR, laege eine
         * absolute Rate, die am Anfang noch unter dem Profil lag, spaeter
         * DARUEBER - und aus der Milderung einer Absenkung wuerde eine
         * Anhebung. Gedeckelt wird deshalb auf das KLEINSTE Profilbasal
         * im Fenster.
         */
        basalSlots: List<BasalSlot>,
        basalStepUPerH: Double,
        tbrDurationMin: Int,
        /**
         * Bis wohin die Bahn geprueft wird [min]. AUSDRUECKLICH OHNE
         * DEFAULT (Review-P1): der Zero-Latch entsteht aus dem
         * LowThreatGate (extrapolierte Bodenzeit aus Fallrate und
         * Bolus-IOB), diese Suche urteilt dagegen ueber die MODELLIERTE
         * Bahn. Beide tragen heute zufaellig die Zahl 120, meinen aber
         * Verschiedenes. Welcher Horizont fuer die Teilbasal-Rueckkehr
         * gelten soll, ist eine Entscheidung - sie darf hier nicht still
         * getroffen werden, und `bindenderOffsetMin` im Ergebnis zeigt,
         * wo sie tatsaechlich greift.
         */
        pruefHorizontMin: Int,
        restraint: PredictorResult? = null,
    ): Ergebnis {
        if (!basalStepUPerH.isFinite() || basalStepUPerH <= 0.0) return ab(Reject.INVALID_INPUT)
        if (tbrDurationMin <= 0 || pruefHorizontMin <= 0) return ab(Reject.INVALID_INPUT)
        if (band.violation() != null) return ab(Reject.INVALID_BAND)

        val points = prediction.points
        if (points.isEmpty()) return ab(Reject.HORIZON_MISSING)
        val liabilityIdx = points.indexOfFirst { it.offsetMin == pruefHorizontMin }
        if (liabilityIdx < 0) return ab(Reject.HORIZON_MISSING)
        val anchorTs = prediction.predictionAnchorTs
        if (!prediction.bgAtAnchor.isFinite()) return ab(Reject.GRID_INCONSISTENT)
        // Dieselbe Rasterpruefung wie in der Kandidatensuche: das
        // Minutenraster ist die Voraussetzung der Integration.
        for (i in points.indices) {
            val p = points[i]
            if (p.offsetMin != i + 1 || p.tsMs != anchorTs + (i + 1) * 60_000L) return ab(Reject.GRID_INCONSISTENT)
        }

        // ---- DER PROFILDECKEL UEBER DAS GANZE TBR-FENSTER ----------------
        //
        // Nicht `profile.getBasal(jetzt)`: faellt das Profil waehrend der
        // TBR, waere die Rate danach eine ANHEBUNG. Gedeckelt wird auf das
        // kleinste Profilbasal im Fenster; deckt das Profil das Fenster
        // nicht, wird abgelehnt statt geraten.
        val fensterEndeTs = anchorTs + tbrDurationMin * 60_000L
        if (!ProfileSlots.basalCovers(basalSlots, anchorTs, fensterEndeTs)) return ab(Reject.INVALID_INPUT)
        var profildeckel = Double.MAX_VALUE
        run {
            var t = anchorTs
            while (t <= fensterEndeTs) {
                val b = ProfileSlots.basalAt(basalSlots, minOf(t, fensterEndeTs - 1)) ?: return ab(Reject.INVALID_INPUT)
                if (!b.isFinite() || b < 0.0) return ab(Reject.INVALID_INPUT)
                if (b < profildeckel) profildeckel = b
                t += 60_000L
            }
        }
        if (!profildeckel.isFinite() || profildeckel <= 0.0) return ab(Reject.INVALID_INPUT)

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

        /** Minimum der Bahn bei [rate] - und WO es liegt (-1 = Anker). */
        fun minLowerBei(rate: Double): Pair<Double, Int> {
            var min = ankerMin
            var wo = -1
            for (i in 0..liabilityIdx) {
                val v = basis[i] - rate * senkungProUPerH[i]
                if (v < min) { min = v; wo = points[i].offsetMin }
            }
            return min to wo
        }

        // ---- DIE RASTERSUCHE ---------------------------------------------
        //
        // Kandidaten ausschliesslich auf dem Pumpenraster in
        // [0, Profilbasal]. Gesucht ist der GROESSTE tragbare Tick; der
        // naechsthoehere muss den Guard verletzen (oder ueber dem Profil
        // liegen) - genau das weist das Ergebnis aus.
        val maxTicks = floor(profildeckel / basalStepUPerH + 1e-9).toInt()
        val baseline = minLowerBei(0.0)
        if (!baseline.first.isFinite()) return ab(Reject.NON_FINITE)
        var bester = 0
        var besteBahn = baseline
        for (t in 1..maxTicks) {
            val m = minLowerBei(t * basalStepUPerH)
            if (!m.first.isFinite()) return ab(Reject.NON_FINITE)
            if (m.first >= band.guardFloorMgdl) { bester = t; besteBahn = m } else break
        }
        val naechster = bester + 1
        val minLowerNaechster =
            if (naechster <= maxTicks) minLowerBei(naechster * basalStepUPerH).first else Double.NaN
        return Ergebnis(
            rateUPerH = bester * basalStepUPerH,
            reject = null,
            gepruefteTicks = maxTicks,
            minLowerBeiRate = besteBahn.first,
            minLowerNaechsterTick = minLowerNaechster,
            baselineMinLowerMgdl = baseline.first,
            bindenderOffsetMin = besteBahn.second,
            pruefHorizontMin = pruefHorizontMin,
            begrenzung = when {
                bester == 0 -> Begrenzung.KEINE_RATE
                naechster > maxTicks -> Begrenzung.PROFIL
                else -> Begrenzung.GUARD
            },
            profildeckelUPerH = profildeckel,
        )
    }
}
