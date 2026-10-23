package app.aaps.fuse.core.controller

import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.predictor.PredictorResult
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * DIE FRUEHE VERSORGUNG (E-034 v2, Entwurf Toni) - reine Rechnung, keine Verdrahtung.
 *
 * Vier Groessen, getrennt gehalten:
 *  - die ERWARTUNGSKURVE F(t): erwartete Mahlzeitwirkung [U-Aequivalent] bis t ab Marker ([ExpectationAccount.Curve]);
 *  - die ZEITLICHE INSULINWIRKUNG C(tau): Anteil der Wirkung einer JETZT gegebenen Einheit bis tau, aus dem aktiven
 *    Insulinmodell (vom Aufrufer als Funktion, damit Rechnung und Geraet dasselbe Modell nutzen);
 *  - das GESAMTBUDGET B: die eine fruehe Gabe, deren Wirkung F ab [MIN_COVER_MIN] rechtzeitig abdeckt, skaliert und
 *    gedeckelt - am Marker gebucht, nur verbraucht;
 *  - die LIEFERGESCHWINDIGKEIT: Anfangsanteil, maximale Portion, Menge je Zeitraum.
 *
 * Der aktuelle Zielabstand gehoert NICHT in diese Rechnung: er wird wie bisher "irgendwann" ueber den gemessenen
 * Anteil korrigiert. In einer Deckung bis 30 min wuerde er sonst durch C(30) vervielfacht. Vorhandenes Insulin zaehlt
 * hier nur mit dem Teil, der nicht schon fuer den Zielabstand gebraucht wird.
 */
object EarlySupply {

    /** Ab hier muss die Insulinwirkung mit der Mahlzeit Schritt halten [min]; frueher ist es physikalisch nicht moeglich. */
    const val MIN_COVER_MIN = 30

    /** Raster der Deckungspruefung [min]. */
    const val STEP_MIN = 5

    /** Bis hierhin keine Anpassung an die Beobachtung [min] - vorher zeigt die Mahlzeit noch nichts. */
    const val ADAPT_FROM_MIN = 20.0

    /**
     * Bis hierhin darf die Anfangsportion fliessen [min]. Danach traegt jede Menge die Pruefung mit Erwartung - eine
     * spaete Anfangsportion ohne Guard waere genau das spaete Nachschieben, das v2 ersetzen soll.
     */
    const val INITIAL_WINDOW_MIN = ADAPT_FROM_MIN

    // ---- Grenzen der Einstellungen (letzte Verteidigung im Kern) ------------
    const val MAX_SCALE = 2.0
    const val MAX_BUDGET_U = 20.0
    const val MAX_PORTION_U = 10.0
    const val MAX_PER_PERIOD_U = 20.0
    const val MIN_PERIOD_MIN = 5
    const val MAX_PERIOD_MIN = 30

    private const val EPS = 1e-9

    /** Die Einstellungen, geprueft. `null` = ungueltig: fruehe Versorgung aus. */
    data class Settings(
        val scaleStable: Double,
        val scaleAfterLow: Double,
        val budgetMaxU: Double,
        val initialShare: Double,
        val maxPortionU: Double,
        val maxPerPeriodU: Double,
        val periodMin: Int,
        /** Toni 23.10.: Nachschlag hoechstens, als Anteil des Budgets (0..1); 0 = kein Nachschlag ([Pins.topUpShare]). */
        val topUpShare: Double = 0.0,
    ) {
        companion object {
            fun of(
                scaleStable: Double, scaleAfterLow: Double, budgetMaxU: Double, initialShare: Double,
                maxPortionU: Double, maxPerPeriodU: Double, periodMin: Int, topUpShare: Double = 0.0,
            ): Settings? {
                fun inRange(x: Double, hi: Double) = x.isFinite() && x >= 0.0 && x <= hi
                if (!inRange(scaleStable, MAX_SCALE) || !inRange(scaleAfterLow, MAX_SCALE)) return null
                if (!inRange(budgetMaxU, MAX_BUDGET_U) || !inRange(initialShare, 1.0)) return null
                if (!inRange(maxPortionU, MAX_PORTION_U) || !inRange(maxPerPeriodU, MAX_PER_PERIOD_U)) return null
                if (periodMin !in MIN_PERIOD_MIN..MAX_PERIOD_MIN) return null
                if (!inRange(topUpShare, 1.0)) return null
                return Settings(scaleStable, scaleAfterLow, budgetMaxU, initialShare, maxPortionU, maxPerPeriodU, periodMin, topUpShare)
            }

            /**
             * Konsolidierung 2.0: die Einstellungen eines Kontos OHNE fruehe Versorgung (Hauptschalter aus oder
             * ungueltige Werte): kein Budget, keine Portion - es fliesst nur der gemessene Anteil.
             */
            val NEUTRAL = Settings(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, MAX_PERIOD_MIN)
        }
    }

    /**
     * DIE VIER AENDERUNGEN AN GUARD UND SCHWANZ (Toni: einzeln benannt, einzeln abschaltbar; die
     * Skalierbarkeit ist fuer sie keine pauschale Freigabe).
     *
     *  - G1 [initialLiftsGuard]: die Anfangsportion hebt das Guard-Urteil durch die Markerautorisierung - wie heute
     *    die Direktdosis. Begrenzt durch den Anfangsanteil und [INITIAL_WINDOW_MIN].
     *  - G2 [initialLiftsTail]: dasselbe fuer den Schwanz.
     *  - G3 [guardWithExpectation]: weitere Portionen pruefen den Guard MIT der angekuendigten Mahlzeit als bekannter
     *    Stoerung - keine Hebung, eine geaenderte Eingabe derselben Pruefung. Angerechnet wird nur die BESTAETIGTE
     *    Erwartung ([confirmedGuardScale]): nur die Anfangsportion darf auf der unbestaetigten Erwartung beruhen.
     *  - G4 [tailWithExpectation]: dasselbe fuer den Schwanz.
     *
     * Aus heisst: die jeweilige Pruefung gilt unveraendert. Abschalten wirkt sofort, Einschalten ab dem naechsten
     * Marker (die Werte werden am Marker festgehalten).
     */
    data class Switches(
        val initialLiftsGuard: Boolean,
        val initialLiftsTail: Boolean,
        val guardWithExpectation: Boolean,
        val tailWithExpectation: Boolean,
    ) {
        /** Was jetzt gilt: festgehalten UND jetzt eingeschaltet. */
        fun and(now: Switches): Switches = Switches(
            initialLiftsGuard && now.initialLiftsGuard,
            initialLiftsTail && now.initialLiftsTail,
            guardWithExpectation && now.guardWithExpectation,
            tailWithExpectation && now.tailWithExpectation,
        )

        companion object {
            /** Alle vier aus: Guard und Schwanz gelten unveraendert. */
            val NONE = Switches(false, false, false, false)
        }
    }

    /**
     * Was eine Episode am Marker festhaelt (neben Kurve, Skalierer und Budget im Konto). [withdrawn]: der Hauptschalter
     * war in dieser Episode aus - die fruehe Versorgung ist fuer den Rest der Mahlzeit entzogen (Abschalten wirkt
     * sofort und fuer die ganze Episode, wie bei der v1-Ausnahme).
     */
    data class Pins(
        val initialShare: Double,
        val maxPortionU: Double,
        val maxPerPeriodU: Double,
        val periodMin: Int,
        val switches: Switches,
        val withdrawn: Boolean = false,
        /**
         * NACHSCHLAG hoechstens (Toni 23.10.), als Anteil des Budgets (0..1): so viel darf das Konto zusaetzlich geben,
         * wenn sich die Mahlzeit groesser zeigt als erwartet ([ExpectationAccount.State.topUpU]). Am Marker festgehalten;
         * herunterstellen wirkt sofort (der Aufrufer fuehrt den Wert nach unten), heraufstellen ab dem naechsten Marker.
         * 0 = kein Nachschlag.
         */
        val topUpShare: Double = 0.0,
    ) {
        companion object {
            /**
             * Konsolidierung 2.0: die Pins eines Kontos OHNE fruehe Versorgung - keine Anfangsportion, keine weitere
             * Portion, kein Schalter. So laeuft ein Konto bei ausgeschalteter oder ungueltiger frueher Versorgung, bei
             * ungueltigen Kontoparametern und ein v1-Konto eines aelteren Standes.
             */
            val NEUTRAL = Pins(0.0, 0.0, 0.0, MAX_PERIOD_MIN, Switches.NONE)
        }
    }

    /** Eine weitere Portion im Zeitraumbuch: Zeitpunkt und Erwartungsanteil [U]. */
    data class Portion(val ts: Long, val amountU: Double)

    /**
     * SCHRITT 2 (Toni): die BEOBACHTETE FORTSETZUNG der laufenden Mahlzeit - die Stoerung r dieses Zyklus als Rate
     * [rateUPerMin] (r / ISF), abklingend mit [tauMin] ab jetzt (dasselbe Abklingen wie der Antrieb der Bahn). Es gibt
     * sie nur, solange die Mahlzeit laeuft (r ueber der Rampen-Unterkante).
     */
    data class Continuation(val rateUPerMin: Double, val tauMin: Double) {

        /** Erwartete Stoerung [U] zwischen jetzt + [fromMin] und jetzt + [toMin]; ungueltig oder leer = 0. */
        fun amountU(fromMin: Double, toMin: Double): Double {
            if (!rateUPerMin.isFinite() || rateUPerMin <= 0.0 || !tauMin.isFinite() || tauMin <= 0.0) return 0.0
            val von = max(0.0, fromMin)
            if (!(toMin > von)) return 0.0
            val u = rateUPerMin * tauMin * (exp(-von / tauMin) - exp(-toMin / tauMin))
            return if (u.isFinite()) max(0.0, u) else 0.0
        }
    }

    /**
     * Die vom Konto JETZT erwartete Mahlzeitwirkung fuer die Anzeige [mg/dl/min]: die Steigung der Kurve im Markeralter
     * mal Skalierer mal ISF. Ausserhalb der Kurve 0. Dosierneutral - nur Graph und Trail lesen sie.
     */
    fun expectedDriveMgdlPerMin(curve: ExpectationAccount.Curve, ageMin: Double, scale: Double, isfMgdlPerU: Double): Double {
        if (!ageMin.isFinite() || ageMin < 0.0 || ageMin >= ExpectationAccount.CURVE_END_MIN) return 0.0
        if (!scale.isFinite() || scale <= 0.0 || !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0) return 0.0
        val von = max(0.0, ageMin - 1.0)
        val bis = min(ExpectationAccount.CURVE_END_MIN.toDouble(), ageMin + 1.0)
        val v = scale * (curve.at(bis) - curve.at(von)) / (bis - von) * isfMgdlPerU
        return if (v.isFinite()) max(0.0, v) else 0.0
    }

    /** Die Skala, mit der Guard und Schwanz die Mahlzeit sehen: hoechstens die eingestellte Kurve, nie mehr. */
    fun guardScale(scaleEff: Double): Double = if (scaleEff.isFinite()) min(1.0, max(0.0, scaleEff)) else 0.0

    /**
     * G3/G4: WIE VIEL DER ANGEKUENDIGTEN MAHLZEIT GUARD UND SCHWANZ ANRECHNEN - nur, was die Beobachtung bestaetigt hat.
     *
     * Vor [ADAPT_FROM_MIN] nichts: die Mahlzeit zeigt sich noch nicht, und eine weitere Portion auf die unbestaetigte
     * Erwartung waere eine zweite Anfangsportion ohne Autorisierung (Bank N2, A1: Anfangsportion und erste
     * Zeitraummenge flossen vor jeder Pruefung der Mahlzeit; bei ausbleibender Mahlzeit fiel jede Stufe tief).
     * Danach der Skalierer im Verhaeltnis beobachtet/erwartet, hoechstens die Kurve. Eine ungueltige Beobachtung
     * bestaetigt nichts - anders als bei der Anforderung ([effectiveScale]), die dann unveraendert bleibt.
     */
    fun confirmedGuardScale(
        scale: Double, ageMin: Double, observedU: Double, observedValid: Boolean, curve: ExpectationAccount.Curve,
    ): Double {
        if (!ageMin.isFinite() || ageMin < ADAPT_FROM_MIN || !observedValid || !observedU.isFinite() || !scale.isFinite())
            return 0.0
        val erwartet = curve.at(ageMin)
        if (erwartet <= EPS) return 0.0
        return min(1.0, max(0.0, scale) * min(1.0, max(0.0, observedU) / erwartet))
    }

    /**
     * KANDIDAT W (Pruefstand): IN DER WENDEPHASE rechnen Guard und Schwanz dieselbe Mahlzeit an wie die Anforderung
     * ([effectiveScale]: der gebuchte Skalierer im Verhaeltnis beobachtet/erwartet, hoechstens er) - ohne den Deckel der
     * Kurve, den [confirmedGuardScale] sonst setzt. Nur dort; sonst gilt [confirmedGuardScale]. Nie weniger als sie.
     * Dieselben Bedingungen: vor [ADAPT_FROM_MIN] und ohne gueltige Beobachtung nichts.
     */
    fun confirmedGuardScaleToBooked(
        scale: Double, ageMin: Double, observedU: Double, observedValid: Boolean, curve: ExpectationAccount.Curve,
    ): Double {
        if (!ageMin.isFinite() || ageMin < ADAPT_FROM_MIN || !observedValid || !observedU.isFinite() || !scale.isFinite())
            return 0.0
        val erwartet = curve.at(ageMin)
        if (erwartet <= EPS) return 0.0
        return max(0.0, scale) * min(1.0, max(0.0, observedU) / erwartet)
    }

    /**
     * Erwarteter Anstieg [mg/dl] durch die angekuendigte Mahlzeit zwischen Markeralter [ageMin] und
     * [ageMin] + [offsetMin], hoechstens bis zum Ende der Vorschau. Nie negativ; nicht berechenbar = 0.
     *
     * SCHRITT 2 (Toni): mit einer [continuation] (die Mahlzeit laeuft) kommt JENSEITS des Kurvenendes dieselbe
     * beobachtete Fortsetzung hinzu wie im Kontobedarf ([demandU]) - ab dem Kurvenende, hoechstens
     * [ExpectationAccount.CURVE_END_MIN] nach jetzt, unskaliert (sie ist schon Beobachtung). Ohne sie fordert das
     * Konto Insulin fuer eine laufende Mahlzeit, das Guard und Schwanz nie durchlassen. Nur solange die Mahlzeit
     * bestaetigt ist ([scaleGuard] > 0): vor der Bestaetigung zaehlt auch die Fortsetzung nicht.
     */
    fun expectedRiseMgdl(
        curve: ExpectationAccount.Curve, horizonMin: Int, ageMin: Double, offsetMin: Double,
        scaleGuard: Double, isfMgdlPerU: Double, continuation: Continuation? = null,
    ): Double {
        if (!ageMin.isFinite() || !offsetMin.isFinite() || !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0) return 0.0
        val ende = min(horizonMin.toDouble(), ExpectationAccount.CURVE_END_MIN.toDouble())
        val von = min(max(0.0, ageMin), ende)
        val bis = min(max(0.0, ageMin + offsetMin), ende)
        val fortsetzung = if (continuation == null || !(scaleGuard > 0.0)) 0.0
        else continuation.amountU(max(0.0, ende - ageMin), min(offsetMin, ExpectationAccount.CURVE_END_MIN.toDouble()))
        val r = (scaleGuard * max(0.0, curve.at(bis) - curve.at(von)) + fortsetzung) * isfMgdlPerU
        return if (r.isFinite()) max(0.0, r) else 0.0
    }

    /**
     * DIESELBE KANTE MIT ERWARTUNG: `max(v, min(v, io) + E)`.
     *
     * [insulinOnlyMgdl] ist die Bahn ohne jeden Antrieb (nur Insulin und Transport). Liegt die Kante darueber, hat sie
     * schon einen positiven Antrieb - der zaehlt nicht zweimal, die Erwartung ersetzt ihn nur, wo sie groesser ist.
     * Liegt sie darunter (gemessenes Fallen), bleibt das Fallen voll erhalten und die Erwartung kommt hinzu.
     */
    fun liftedEdge(edgeMgdl: Double, insulinOnlyMgdl: Double, riseMgdl: Double): Double =
        if (!edgeMgdl.isFinite() || !insulinOnlyMgdl.isFinite() || !riseMgdl.isFinite() || riseMgdl <= 0.0) edgeMgdl
        else max(edgeMgdl, min(edgeMgdl, insulinOnlyMgdl) + riseMgdl)

    /**
     * Die Bahn ohne Antrieb [mg/dl] je Punkt: Mittelbahn minus der aufsummierte Mittelbahn-Antrieb. Der Kern addiert
     * je Minute `dMean + bgi + Transport`, also bleibt genau Insulin und Transport. Gleitkomma-genau bis ~1e-9.
     */
    fun insulinOnly(prediction: PredictorResult): DoubleArray {
        var antrieb = 0.0
        return DoubleArray(prediction.points.size) { i ->
            antrieb += prediction.points[i].driveMean
            prediction.points[i].meanBg - antrieb
        }
    }

    /**
     * G3: DIE BAHN MIT ERWARTUNG - jede Punktkante nach [liftedEdge] gehoben, der Anker unveraendert (dort ist der
     * Anstieg 0). Die Kennzahlen werden aus den gehobenen Punkten neu gebildet, damit das Ergebnis in sich stimmt.
     * Geprueft wird damit dieselbe Guard-Pruefung wie sonst ([CandidateSearch.verifyGuardFloor]); nur die Eingabe
     * kennt die angekuendigte Mahlzeit.
     */
    fun withExpectation(
        prediction: PredictorResult, ageAtAnchorMin: Double, curve: ExpectationAccount.Curve, horizonMin: Int,
        scaleGuard: Double, isfMgdlPerU: Double, continuation: Continuation? = null,
    ): PredictorResult {
        val io = insulinOnly(prediction)
        val punkte = prediction.points.mapIndexed { i, p ->
            val e = expectedRiseMgdl(curve, horizonMin, ageAtAnchorMin, p.offsetMin.toDouble(), scaleGuard, isfMgdlPerU, continuation)
            val sicher = liftedEdge(p.safetyLowerBg, io[i], e)
            val unten = max(p.lowerBg, sicher)
            p.copy(meanBg = max(p.meanBg, unten), lowerBg = unten, lowerBgPriorFree = sicher)
        }
        if (punkte.isEmpty()) return prediction
        val anker = prediction.bgAtAnchor
        val minMean = min(anker, punkte.minOf { it.meanBg })
        val minUnten = min(anker, punkte.minOf { it.lowerBg })
        val minSicher = min(anker, punkte.minOf { it.safetyLowerBg })
        return prediction.copy(
            points = punkte,
            minMeanBg = minMean,
            minLowerBg = minUnten,
            bgAtHorizonMean = punkte.last().meanBg,
            bgAtHorizonLower = punkte.last().lowerBg,
            minLowerBgPriorFree = minSicher,
            bgAtHorizonLowerPriorFree = punkte.last().safetyLowerBg,
            timeToMinLowerMin = punkte.indexOfFirst { it.lowerBg == minUnten }.let { if (it < 0 || minUnten == anker) 0 else it + 1 },
            timeToMinLowerPriorFreeMin = punkte.indexOfFirst { it.safetyLowerBg == minSicher }
                .let { if (it < 0 || minSicher == anker) 0 else it + 1 },
            // Die Zerlegung gehoert zur ungehobenen Bahn - sie hier stehen zu lassen, behauptete Summanden, die die
            // gehobene Kante nicht hat.
            hubAtHorizon = null,
            hubAtMinSafetyLower = null,
        )
    }

    /**
     * G4: DIE SCHWANZKANTE AM HORIZONT MIT ERWARTUNG - dieselbe Regel wie [withExpectation] fuer den einen Wert, den
     * der Schwanz liest. Nach dem Ende der Kurve rechnet der Schwanz keine angekuendigte Mahlzeit mehr (konservativ) -
     * nur, solange sie laeuft, ihre beobachtete Fortsetzung ([expectedRiseMgdl]).
     */
    fun tailLowerWithExpectation(
        lowerAtHorizonMgdl: Double, prediction: PredictorResult, ageAtAnchorMin: Double,
        curve: ExpectationAccount.Curve, horizonMin: Int, scaleGuard: Double, isfMgdlPerU: Double,
        continuation: Continuation? = null,
    ): Double {
        val letzter = prediction.points.lastOrNull() ?: return lowerAtHorizonMgdl
        val io = insulinOnly(prediction).last()
        val e = expectedRiseMgdl(
            curve, horizonMin, ageAtAnchorMin, letzter.offsetMin.toDouble(), scaleGuard, isfMgdlPerU, continuation,
        )
        return liftedEdge(lowerAtHorizonMgdl, io, e)
    }

    /**
     * Anteil der Wirkung einer jetzt gegebenen Einheit bis [tauMin] - das exponentielle oref-Modell, wie AAPS es fuer
     * Insuline mit freiem Gipfel rechnet. Fuer Tests und als Rueckfall; im Geraet liefert der Aufrufer das Modell.
     */
    fun orefActionFraction(tauMin: Double, peakMin: Double, diaMin: Double): Double {
        if (!tauMin.isFinite() || tauMin <= 0.0) return 0.0
        if (tauMin >= diaMin) return 1.0
        val tau = peakMin * (1 - peakMin / diaMin) / (1 - 2 * peakMin / diaMin)
        val a = 2 * tau / diaMin
        val s = 1 / (1 - a + (1 + a) * exp(-diaMin / tau))
        val iob = 1 - s * (1 - a) * ((tauMin * tauMin / (tau * diaMin * (1 - a)) - tauMin / tau - 1) * exp(-tauMin / tau) + 1)
        return min(1.0, max(0.0, 1 - iob))
    }

    /**
     * C(tau) aus DEM Einheitskern des Zyklus: Anteil der Wirkung einer zum Kern-Lieferzeitpunkt gegebenen Einheit bis
     * tau [min]. Jenseits des Modellhorizonts ist die Wirkung vollstaendig.
     */
    fun unitActionOf(kernel: UnitInsulinKernel): (Double) -> Double = { tau ->
        if (!(tau > 0.0)) 0.0 else {
            val ts = kernel.deliveryTs + (tau * 60_000.0).toLong()
            if (!kernel.covers(ts)) 1.0 else 1.0 - kernel.iobAt(ts, 1.0)
        }
    }

    /** Die Deckungszeitpunkte tau [min ab jetzt] fuer Markeralter [ageMin]: 30 .. Ende, im Raster. */
    private fun deckung(ageMin: Double, horizonMin: Int): List<Int> {
        val bis = (min(horizonMin.toDouble(), ExpectationAccount.CURVE_END_MIN.toDouble()) - ageMin).toInt()
        if (bis < MIN_COVER_MIN) return emptyList()
        return (MIN_COVER_MIN..bis step STEP_MIN).toList()
    }

    /**
     * GESAMTBUDGET am Marker [U]: `min(budgetMax, scale * max_tau F(tau) / C(tau))` fuer tau ab 30 min - die eine
     * fruehe Gabe, deren Wirkung die erwartete Mahlzeitwirkung rechtzeitig abdeckt. Nie negativ.
     */
    fun budgetU(curve: ExpectationAccount.Curve, horizonMin: Int, scale: Double, budgetMaxU: Double, action: (Double) -> Double): Double {
        val bedarf = deckung(0.0, horizonMin).maxOfOrNull { t ->
            val c = action(t.toDouble())
            if (c > EPS) curve.at(t.toDouble()) / c else 0.0
        } ?: 0.0
        return max(0.0, min(budgetMaxU, scale * bedarf))
    }

    /** Der fruehe Bedarf ohne Skalierer und Deckel [U] und der Kurvenpunkt, der ihn bestimmt [min ab Marker]. */
    data class Need(val rawU: Double, val bindingTauMin: Int)

    /** Wie [budgetU], aber ungedeckelt und mit dem bestimmenden Punkt - fuer die Anzeige der Wirkung. `null` = keine Deckung. */
    fun need(curve: ExpectationAccount.Curve, horizonMin: Int, action: (Double) -> Double): Need? {
        var best: Need? = null
        for (t in deckung(0.0, horizonMin)) {
            val c = action(t.toDouble())
            if (c <= EPS) continue
            val r = curve.at(t.toDouble()) / c
            if (r.isFinite() && (best == null || r > best.rawU + EPS)) best = Need(r, t)
        }
        return best
    }

    /** Was ein Eckpunkt der Kurve fuer den fruehen Bedarf bedeutet (Anzeige). */
    sealed interface CornerRole {
        /** Er bestimmt den Bedarf jetzt: jede Erhoehung erhoeht ihn. */
        data object Binding : CornerRole

        /** Er bestimmt ihn ab diesem Wert [U]. */
        data class From(val valueU: Double) : CornerRole

        /** Innerhalb einer gueltigen Kurve (bis zum naechsten Eckwert) nie. */
        data object Never : CornerRole
    }

    /**
     * Die Rolle eines Eckpunkts fuer den fruehen Bedarf - fuer die Anzeige "bestimmt das Budget (ab ...)".
     * [corners] = Eckwerte nach 30/60/90/120 min, [corner] = 0..3. Der Bedarf ist im Eckwert monoton; gesucht wird per
     * Halbierung der kleinste Wert, ab dem er waechst - hoechstens bis zum naechsten Eckwert, denn darueber waere die
     * Kurve nicht mehr steigend. `null` = Kurve jetzt ungueltig.
     */
    fun cornerRole(corners: DoubleArray, corner: Int, horizonMin: Int, action: (Double) -> Double): CornerRole? {
        fun bedarf(v: Double): Double? {
            val e = corners.copyOf().also { it[corner] = v }
            val k = ExpectationAccount.Curve.ausEckwerten(e[0], e[1], e[2], e[3]) ?: return null
            return need(k, horizonMin, action)?.rawU
        }
        val jetzt = corners[corner]
        val basis = bedarf(jetzt) ?: return null
        val oben = if (corner < corners.size - 1) corners[corner + 1] else ExpectationAccount.MAX_CURVE_U
        if (oben <= jetzt) return CornerRole.Never
        val probe = min(oben, jetzt + 0.001)
        if ((bedarf(probe) ?: return CornerRole.Never) > basis + 1e-9) return CornerRole.Binding
        if ((bedarf(oben) ?: return CornerRole.Never) <= basis + 1e-9) return CornerRole.Never
        var lo = jetzt
        var hi = oben
        repeat(40) {
            val mid = (lo + hi) / 2
            if ((bedarf(mid) ?: Double.MAX_VALUE) > basis + 1e-9) hi = mid else lo = mid
        }
        return CornerRole.From(hi)
    }

    /**
     * ANPASSUNG an die beobachtete Mahlzeit: bis [ADAPT_FROM_MIN] der eingestellte Skalierer; danach hoechstens er,
     * verkleinert im Verhaeltnis beobachtet/erwartet. Groesser als erwartet erhoeht NICHT (das deckt der gemessene
     * Anteil). Eine ungueltige Beobachtung aendert nichts - die Ruecknahme bei +30 bleibt eigens zustaendig.
     */
    fun effectiveScale(scale: Double, ageMin: Double, observedU: Double, observedValid: Boolean, curve: ExpectationAccount.Curve): Double {
        if (ageMin < ADAPT_FROM_MIN || !observedValid || !observedU.isFinite()) return scale
        val erwartet = curve.at(ageMin)
        if (erwartet <= EPS) return scale
        return scale * min(1.0, max(0.0, observedU) / erwartet)
    }

    /**
     * Vorhandenes Insulin, das NICHT schon fuer den aktuellen Zielabstand gebraucht wird [U]:
     * `max(0, IOB - max(0, (BG - Ziel) / ISF))`.
     */
    fun freeIobU(iobU: Double, bgMgdl: Double, targetMgdl: Double, isfMgdlPerU: Double): Double {
        if (!iobU.isFinite() || !bgMgdl.isFinite() || !targetMgdl.isFinite() || !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0)
            return 0.0
        return max(0.0, iobU - max(0.0, (bgMgdl - targetMgdl) / isfMgdlPerU))
    }

    /**
     * AKTUELLE ANFORDERUNG der fruehen Versorgung [U] im Markeralter [ageMin], zeitbewusst:
     *
     *     X = max(0, max_tau [ scaleEff * (F(a + tau) - F(a)) - W_frei(tau) ] / C(tau)),  tau in 30 .. Ende - a
     *
     * [existingActionU] liefert die erwartete Wirkung des bereits gegebenen UND ausstehenden Insulins zwischen jetzt
     * und jetzt + tau [U]; davon zaehlt der Anteil `freeIobU / iobU` (der Rest gehoert zum Zielabstand). Weniger als
     * 30 min bis zum Ende: 0 - dann gibt es nichts mehr rechtzeitig abzudecken.
     *
     * SCHRITT 2 (Toni): mit einer [continuation] (die Mahlzeit laeuft noch) traegt JENSEITS der Kurve - Deckungspunkte
     * nach `min(Vorschau, Ende)` - der Rest der Kurve bis zu ihrem Ende PLUS die beobachtete Fortsetzung danach,
     * `R_T(tau) = rho * tau_d * (e^(-u0/tau_d) - e^(-tau/tau_d))`, u0 = Abstand bis zum Kurvenende, tau bis
     * [ExpectationAccount.CURVE_END_MIN]. Die Fortsetzung ist schon Beobachtung und wird nicht skaliert; innerhalb der
     * Kurve bleibt der Kurvenanteil. Am Kurvenende gehen beide stetig ineinander ueber - zaehlte jenseits nur die
     * Fortsetzung, braeche die Anforderung ein, sobald der letzte Deckungspunkt die Kurve verlaesst (bei Minute 90,
     * Geraetebefund: von ihrem Hoechstwert auf 0, obwohl die Mahlzeit lief). So endet die Anforderung nicht bei
     * Minute 90, solange die Mahlzeit weiterlaeuft - und faellt mit ihr. Ohne Fortsetzung ist die Rechnung die bisherige.
     */
    fun demandU(
        curve: ExpectationAccount.Curve, horizonMin: Int, ageMin: Double, scaleEff: Double,
        iobU: Double, freeIobU: Double, existingActionU: (Double) -> Double, action: (Double) -> Double,
        continuation: Continuation? = null,
    ): Double {
        val anteilFrei = if (iobU > EPS) min(1.0, max(0.0, freeIobU / iobU)) else 0.0
        val fa = curve.at(ageMin)
        val fortsetzung = continuation?.takeIf { it.amountU(0.0, ExpectationAccount.CURVE_END_MIN.toDouble()) > EPS }
        val kurvenEnde = min(horizonMin.toDouble(), ExpectationAccount.CURVE_END_MIN.toDouble())
        val u0 = max(0.0, kurvenEnde - ageMin)
        val punkte = if (fortsetzung == null) deckung(ageMin, horizonMin)
        else (MIN_COVER_MIN..ExpectationAccount.CURVE_END_MIN step STEP_MIN).toList()
        val x = punkte.maxOfOrNull { t ->
            val c = action(t.toDouble())
            if (c <= EPS) return@maxOfOrNull 0.0
            val erwartet = if (fortsetzung == null || ageMin + t <= kurvenEnde) scaleEff * (curve.at(ageMin + t) - fa)
            else scaleEff * max(0.0, curve.at(kurvenEnde) - fa) + fortsetzung.amountU(u0, t.toDouble())
            val vorhanden = anteilFrei * max(0.0, existingActionU(t.toDouble()))
            (erwartet - vorhanden) / c
        } ?: 0.0
        return if (x.isFinite()) max(0.0, x) else 0.0
    }

    /** Die Anfangsportion: hoechstens der Anfangsanteil des Budgets und nie mehr als die aktuelle Anforderung. */
    fun initialPortionU(budgetU: Double, initialShare: Double, demandU: Double): Double =
        max(0.0, min(initialShare * budgetU, demandU))

    /**
     * Jede weitere Portion: Anforderung, Budgetrest, maximale Portion, Rest der Menge je Zeitraum - das Kleinste.
     * Es gibt kein Ansparen: der Zeitraumrest kommt aus dem tatsaechlich Gelieferten, nicht aus verpassten Zyklen.
     */
    fun portionU(demandU: Double, budgetLeftU: Double, maxPortionU: Double, periodLeftU: Double): Double =
        max(0.0, min(min(demandU, budgetLeftU), min(maxPortionU, periodLeftU)))
}
