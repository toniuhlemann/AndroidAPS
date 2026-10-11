package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * DAS ERWARTUNGSKONTO (Testkandidat hinter Schalter, Vorregistrierung E-034).
 *
 * WAS ES ERSETZT: Im Mahlzeitkontext - von der bewussten Markerautorisierung
 * bis zum Ende des gepinnten Fensters - wird der Bedarf nicht mehr aus der
 * Ratenfortschreibung am Freigabehorizont gelesen, sondern als
 *
 *     R_roh(a) = R_mess(a) + phi * Z(a)
 *     R_mess(a) = (BG(a) - Ziel) / ISF - IOB_gesamt(a)
 *     Z(a)      = F(min(a + H, Ende)) - F(a)
 *
 * mit F einer FESTEN persoenlichen Erwartungskurve (Stuetzwerte, nicht
 * gelernt) und IOB_gesamt aus dem Geraet (Boli, manuelle Boli,
 * Basalabweichung). R_mess darf negativ sein und bleibt es; phi * Z ist die
 * unbudgetierte Erwartung.
 *
 * WAS ES BEGRENZT: Ein Erwartungsbudget A_0 = phi_0 * Z(0), hoechstens die
 * tatsaechliche Huelle der Episode (bei "ohne Vorschuss" also 0), wird EINMAL
 * am Marker gebucht und nur verbraucht, nie aufgefuellt. Jede Lieferung d
 * teilt sich in d_mess = min(d, max(0, R_mess)) und d_erw = d - d_mess
 * (Autorisierungsentwurf Abschnitt 2); nur d_erw verbraucht das Budget, und
 * nur d_erw darf die Guard-Ausnahme nutzen - d_mess laeuft ohne Ausnahme
 * durch alle Pruefungen. Die
 * Direktdosis-Familie bucht voll auf das Konto; was sie ueber A_0 hinaus
 * liefert, ist durch die bestehende Markerautorisierung gedeckt und wird so
 * ausgewiesen - keine stille Ueberschreitung.
 *
 * WANN KEIN d_erw: vor erfuellter Stabilitaet (Start nach Tief oder im
 * Fallen), in jeder Pause (gemessene Sperre, Signal-, Pumpen- oder
 * Ledgerzustand), bei eingefrorenem Rest (Ruecknahme bei ausbleibender
 * Stoerung) und nach dem Fensterende. Unverbrauchte Erlaubnis verpflichtet
 * nie zur Lieferung.
 *
 * REINE FUNKTIONEN. Die Verdrahtung, die Sperren und die Guard-Ausnahme
 * leben im Runner; hier stehen nur Rechnung, Buchung und Zustand. Alle
 * gepinnten Groessen liegen im [State], damit eine spaetere Aenderung der
 * Einstellung eine laufende Episode nicht verschiebt.
 */
object ExpectationAccount {

    /** Abstand der Stuetzwerte der Erwartungskurve [min]. */
    const val CURVE_STEP_MIN = 5

    /** Letzter Stuetzwert = Ende der Erwartung [min nach Marker]. */
    const val CURVE_END_MIN = 120

    /** Anzahl der Stuetzwerte einschliesslich F(0). */
    const val CURVE_POINTS = CURVE_END_MIN / CURVE_STEP_MIN + 1

    /** Bis zu dieser Luecke [min] wird die beobachtete Stoerung fortgeschrieben. */
    const val MAX_OBSERVATION_GAP_MIN = 10.0

    /** Ab diesem Markeralter [min] wird die Ruecknahme geprueft. */
    const val RELEASE_CHECK_AGE_MIN = 30.0

    private const val EPS_U = 1e-9

    // ---- Grenzen der Einstellungen (letzte Verteidigung im Kern) ------------
    const val MIN_HORIZON_MIN = 30
    const val MAX_HORIZON_MIN = 120
    const val MIN_STABILITY_BG_MGDL = 70.0
    const val MAX_STABILITY_BG_MGDL = 180.0
    const val MIN_STABILITY_MIN = 5
    const val MAX_STABILITY_MIN = 60
    const val MAX_RELEASE_THRESHOLD_U = 2.0
    const val MAX_CURVE_U = 30.0

    /**
     * Die feste Erwartungskurve F(a) [U kumulierte Stoerung ab Marker].
     * Nur gueltig, wenn sie vollstaendig, endlich, nicht negativ, bei 0
     * beginnend, monoton steigend und am Ende positiv ist - sonst gibt es
     * keine Kurve, und die Erwartung ist aus.
     */
    class Curve private constructor(private val pointsU: DoubleArray) {

        val endU: Double get() = pointsU[CURVE_POINTS - 1]

        fun values(): List<Double> = pointsU.toList()

        /** F(a), linear zwischen den Stuetzwerten; ausserhalb geklemmt. */
        fun at(ageMin: Double): Double {
            if (!ageMin.isFinite() || ageMin <= 0.0) return pointsU[0]
            if (ageMin >= CURVE_END_MIN) return endU
            val pos = ageMin / CURVE_STEP_MIN
            val i = pos.toInt()
            val w = pos - i
            return pointsU[i] + (pointsU[i + 1] - pointsU[i]) * w
        }

        /** Z(a) = F(min(a + H, Ende)) - F(a), nie negativ. */
        fun remaining(ageMin: Double, horizonMin: Int): Double {
            val a = max(0.0, ageMin)
            return max(0.0, at(min(a + horizonMin, CURVE_END_MIN.toDouble())) - at(a))
        }

        companion object {

            fun of(values: List<Double>): Curve? {
                if (values.size != CURVE_POINTS) return null
                if (values.any { !it.isFinite() || it < 0.0 || it > MAX_CURVE_U }) return null
                if (values[0] != 0.0) return null
                for (i in 1 until values.size) if (values[i] < values[i - 1]) return null
                if (values.last() <= 0.0) return null
                return Curve(values.toDoubleArray())
            }

            /**
             * Die Kurve aus dem Einstellungstext: die Stuetzwerte F(5) bis
             * F(120) [U] (oder zusaetzlich F(0) = 0 vorn), getrennt durch
             * Semikolon oder Leerraum; Dezimaltrenner Punkt oder Komma.
             * Leer oder ungueltig ergibt `null` - die Erwartung ist dann aus,
             * es gibt keinen Ersatzwert.
             */
            fun parse(text: String): Curve? {
                val teile = text.split(';', ' ', '\t', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
                if (teile.isEmpty()) return null
                val werte = teile.map { it.replace(',', '.').toDoubleOrNull() ?: return null }
                return when (werte.size) {
                    CURVE_POINTS - 1 -> of(listOf(0.0) + werte)
                    CURVE_POINTS -> of(werte)
                    else -> null
                }
            }
        }
    }

    /** Die Einstellungen, geprueft. `null` aus [of] heisst: Erwartung aus. */
    data class Params(
        val curve: Curve,
        val horizonMin: Int,
        val phi0Stable: Double,
        val phi0AfterLow: Double,
        val stabilityBgMgdl: Double,
        val stabilityMin: Int,
        val releaseThresholdU: Double,
        val guardException: Boolean,
    ) {
        companion object {

            fun of(
                curveValues: List<Double>,
                horizonMin: Int,
                phi0Stable: Double,
                phi0AfterLow: Double,
                stabilityBgMgdl: Double,
                stabilityMin: Int,
                releaseThresholdU: Double,
                guardException: Boolean,
            ): Params? {
                val curve = Curve.of(curveValues) ?: return null
                if (horizonMin !in MIN_HORIZON_MIN..MAX_HORIZON_MIN) return null
                if (!phi0Stable.isFinite() || phi0Stable !in 0.0..1.0) return null
                if (!phi0AfterLow.isFinite() || phi0AfterLow !in 0.0..1.0) return null
                if (!stabilityBgMgdl.isFinite() || stabilityBgMgdl !in MIN_STABILITY_BG_MGDL..MAX_STABILITY_BG_MGDL)
                    return null
                if (stabilityMin !in MIN_STABILITY_MIN..MAX_STABILITY_MIN) return null
                if (!releaseThresholdU.isFinite() || releaseThresholdU !in 0.0..MAX_RELEASE_THRESHOLD_U) return null
                return Params(
                    curve, horizonMin, phi0Stable, phi0AfterLow, stabilityBgMgdl, stabilityMin,
                    releaseThresholdU, guardException,
                )
            }
        }
    }

    /**
     * Der Zustand EINER Episode. Nur einfache Felder - ein aelterer Stand
     * muss ihn beim Rueckweg ignorieren koennen, ohne an einem unbekannten
     * Aufzaehlungswert zu scheitern.
     */
    data class State(
        /** Gepinnte Markerzeit = Kennung der Autorisierung. */
        val authId: Long,
        val windowEndTs: Long,
        val startStable: Boolean,
        /** Am Marker gewaehlter Anteil (stabil oder nach Tief). */
        val phi0: Double,
        /** Gebuchtes Erwartungsbudget [U]. */
        val a0U: Double,
        /** Verbraucht: d_erw und Direktdosis-Familie [U], nur steigend. */
        val bookedU: Double,
        /** Davon Direktdosis-Familie [U]. */
        val directDoseU: Double,
        /** Direktdosis ueber dem Budget, gedeckt durch die Markerautorisierung [U]. */
        val coveredByMarkerAuthU: Double,
        /** Stabilitaet erreicht; bei stabilem Start sofort. */
        val released: Boolean,
        /** Ruecknahme: Rest eingefroren. */
        val frozen: Boolean,
        /** Die Ruecknahme wurde bei +30 geprueft. */
        val releaseChecked: Boolean,
        /** Beobachtete Stoerung seit dem Marker [U]. */
        val disturbanceU: Double,
        /** false nach einer Luecke ueber [MAX_OBSERVATION_GAP_MIN]. */
        val disturbanceValid: Boolean,
        val lastObsTs: Long,
        val lastBgMgdl: Double,
        val isfAtMarkerMgdlPerU: Double,
        // ---- gepinnt am Marker ----
        val horizonMin: Int,
        val releaseThresholdU: Double,
        val guardException: Boolean,
        val curveValues: List<Double>,
        /** false: am Marker gab es keine gueltigen Parameter - nur Ersetzung, keine Erwartung. */
        val expectationOn: Boolean = true,
        val stabilityBgMgdl: Double = MIN_STABILITY_BG_MGDL,
        val stabilityMin: Int = MAX_STABILITY_MIN,
    ) {
        val restU: Double get() = max(0.0, a0U - bookedU)
    }

    /**
     * Bucht das Budget am Marker. [envelopeU] ist die tatsaechliche Huelle der
     * Episode; bei 0 ("ohne Vorschuss") bleibt das Budget 0, die Ersetzung
     * des Bedarfs gilt trotzdem. Ohne gueltige [params] (leer oder ungueltig)
     * ist die Erwartung fuer diese Episode aus: Vorgriff 0, Budget 0 - auch
     * dann gilt nur die Ersetzung.
     */
    fun arm(
        authId: Long,
        windowEndTs: Long,
        envelopeU: Double,
        params: Params?,
        startStable: Boolean,
        nowTs: Long,
        bgMgdl: Double,
        isfMgdlPerU: Double,
    ): State {
        if (params == null) return State(
            authId = authId, windowEndTs = windowEndTs, startStable = startStable,
            phi0 = 0.0, a0U = 0.0, bookedU = 0.0, directDoseU = 0.0, coveredByMarkerAuthU = 0.0,
            released = startStable, frozen = false, releaseChecked = false,
            disturbanceU = 0.0,
            disturbanceValid = bgMgdl.isFinite() && isfMgdlPerU.isFinite() && isfMgdlPerU > 0.0,
            lastObsTs = nowTs, lastBgMgdl = bgMgdl, isfAtMarkerMgdlPerU = isfMgdlPerU,
            horizonMin = MAX_HORIZON_MIN, releaseThresholdU = 0.0, guardException = false,
            curveValues = emptyList(), expectationOn = false,
        )
        val z0 = params.curve.remaining(0.0, params.horizonMin)
        val phi = if (startStable) params.phi0Stable else params.phi0AfterLow
        val env = if (envelopeU.isFinite()) max(0.0, envelopeU) else 0.0
        val a0 = max(0.0, min(env, phi * z0))
        return State(
            authId = authId,
            windowEndTs = windowEndTs,
            startStable = startStable,
            phi0 = phi,
            a0U = a0,
            bookedU = 0.0,
            directDoseU = 0.0,
            coveredByMarkerAuthU = 0.0,
            released = startStable,
            frozen = false,
            releaseChecked = false,
            disturbanceU = 0.0,
            disturbanceValid = bgMgdl.isFinite() && isfMgdlPerU.isFinite() && isfMgdlPerU > 0.0,
            lastObsTs = nowTs,
            lastBgMgdl = bgMgdl,
            isfAtMarkerMgdlPerU = isfMgdlPerU,
            horizonMin = params.horizonMin,
            releaseThresholdU = params.releaseThresholdU,
            guardException = params.guardException,
            curveValues = params.curve.values(),
            expectationOn = true,
            stabilityBgMgdl = params.stabilityBgMgdl,
            stabilityMin = params.stabilityMin,
        )
    }

    /** Was der Zyklus misst und was er darf. */
    data class Inputs(
        val nowTs: Long,
        val ageMin: Double,
        val bgMgdl: Double,
        val targetMgdl: Double,
        val isfMgdlPerU: Double,
        /** Gesamtes IOB aus dem Geraet [U]. */
        val iobTotalU: Double,
        /** Insulinaktivitaet [U/min] fuer die Stoerungsfortschreibung. */
        val activityUPerMin: Double,
        /** Gemessene Sperre, Signal-, Pumpen- oder Ledgerzustand. */
        val paused: Boolean,
        /** Stabilitaetsbedingung ueber die geforderte Dauer erfuellt. */
        val stableNow: Boolean,
    )

    data class Evaluation(
        val rMessU: Double,
        val phiZU: Double,
        val rRohU: Double,
        /** max(0, R_roh). */
        val requestU: Double,
        /** Der Budgetrest, der in DIESEM Zyklus als d_erw fliessen darf. */
        val restEffU: Double,
        val expired: Boolean,
        val paused: Boolean,
        val released: Boolean,
        val frozen: Boolean,
        /** Lesbarer Status fuer den Export. */
        val status: String,
    )

    /**
     * Ein Zyklus: Stoerung fortschreiben, Stabilitaet, Ruecknahme und Ende
     * pruefen, R_mess / R_roh rechnen. Liefert den neuen Zustand und die
     * Bewertung. Bucht nichts.
     */
    fun step(state: State, inputs: Inputs): Pair<State, Evaluation> {
        var s = state

        // 1 Beobachtete Stoerung fortschreiben (gleiche Rechnung wie die Analyse:
        //   D += (dBG + Aktivitaet * ISF * dt) / ISF_Marker).
        val dtMin = (inputs.nowTs - s.lastObsTs) / 60_000.0
        if (dtMin > 0.0) {
            val gueltig = s.disturbanceValid && dtMin <= MAX_OBSERVATION_GAP_MIN &&
                inputs.bgMgdl.isFinite() && s.lastBgMgdl.isFinite() &&
                inputs.activityUPerMin.isFinite() && inputs.isfMgdlPerU.isFinite() && inputs.isfMgdlPerU > 0.0
            s = if (gueltig) {
                val dMgdl = (inputs.bgMgdl - s.lastBgMgdl) + inputs.activityUPerMin * inputs.isfMgdlPerU * dtMin
                s.copy(
                    disturbanceU = s.disturbanceU + dMgdl / s.isfAtMarkerMgdlPerU,
                    lastObsTs = inputs.nowTs, lastBgMgdl = inputs.bgMgdl,
                )
            } else s.copy(disturbanceValid = false, lastObsTs = inputs.nowTs, lastBgMgdl = inputs.bgMgdl)
        }

        val expired = inputs.nowTs >= s.windowEndTs

        // 2 Stabilitaet nach Tief: einmal erreicht, bleibt die Freigabe.
        if (!s.released && inputs.stableNow && !expired) s = s.copy(released = true)

        // 3 Ruecknahme: bei +30 pruefen, danach Wiederfreigabe bei erreichter Schwelle.
        val bestaetigt = s.disturbanceValid && s.disturbanceU >= s.releaseThresholdU
        if (!s.releaseChecked && inputs.ageMin >= RELEASE_CHECK_AGE_MIN) {
            s = s.copy(releaseChecked = true, frozen = !bestaetigt)
        } else if (s.frozen && bestaetigt && !expired) {
            s = s.copy(frozen = false)
        }

        // 4 Bedarf.
        val curve = Curve.of(s.curveValues)
        val z = if (expired || curve == null) 0.0 else curve.remaining(inputs.ageMin, s.horizonMin)
        val phiZ = s.phi0 * z
        val rMess = (inputs.bgMgdl - inputs.targetMgdl) / inputs.isfMgdlPerU - inputs.iobTotalU
        val rRoh = rMess + phiZ
        val request = if (rRoh.isFinite()) max(0.0, rRoh) else 0.0

        val restEff = if (expired || inputs.paused || !s.released || s.frozen) 0.0 else s.restU
        val status = when {
            expired -> "EXPIRED"
            !s.expectationOn -> "EXPECTATION_OFF"
            !s.released -> "WAITING_FOR_STABILITY"
            inputs.paused -> "PAUSED"
            s.frozen -> "FROZEN"
            s.restU <= EPS_U -> "BUDGET_USED"
            else -> "ACTIVE"
        }
        return s to Evaluation(
            rMessU = rMess, phiZU = phiZ, rRohU = rRoh, requestU = request, restEffU = restEff,
            expired = expired, paused = inputs.paused, released = s.released, frozen = s.frozen, status = status,
        )
    }

    /** Der gemessene Anteil max(0, R_mess); nicht berechenbar zaehlt als 0 (nie NaN in einer Menge). */
    private fun messU(e: Evaluation): Double = if (e.rMessU.isFinite()) max(0.0, e.rMessU) else 0.0

    /** Hoechstmenge dieses Zyklus aus dem Konto: d_mess-Anteil plus d_erw bis Budgetrest. */
    fun deliverableU(e: Evaluation): Double = min(e.requestU, messU(e) + e.restEffU)

    /** Der Anteil, der nur als d_erw fliessen darf (und nur er nutzt die Guard-Ausnahme). */
    fun expectationShareU(e: Evaluation): Double = min(e.restEffU, max(0.0, e.requestU - messU(e)))

    data class Split(
        val dMessU: Double,
        val dErwU: Double,
        /** d_erw ueber dem erlaubten Rest - ein Mechanismusverstoss, nie still. */
        val violationU: Double,
    )

    /**
     * DER BODEN DER GUARD-AUSNAHME in diesem Zyklus - oder 0.
     *
     * [normalPassedU] ist die Menge, die der Normalpfad MIT Guard freigegeben
     * hat. Gebucht wird jede Lieferung zuerst als d_mess bis max(0, R_mess);
     * eine Einheit ueber dem Normalpfad waere also d_mess, solange dieser den
     * gemessenen Anteil nicht vollstaendig traegt - und d_mess hat keine
     * Ausnahme. Deshalb gibt es den Boden nur, wenn der Normalpfad den
     * gemessenen Anteil schon freigegeben hat; dann reicht er bis zur
     * lieferbaren Menge, und alles darueber ist d_erw.
     */
    fun guardExceptionFloorU(e: Evaluation, normalPassedU: Double): Double {
        if (!normalPassedU.isFinite() || expectationShareU(e) <= EPS_U) return 0.0
        return if (normalPassedU + EPS_U >= messU(e)) deliverableU(e) else 0.0
    }

    /**
     * Bucht eine Lieferung aus dem Konto. [finalU] ist die Endmenge nach allen
     * Pruefungen (nach der Expositionsgrenze). d_mess = min(d, max(0, R_mess)),
     * d_erw = d - d_mess (Autorisierungsentwurf Abschnitt 2).
     */
    fun book(state: State, e: Evaluation, finalU: Double): Pair<State, Split> {
        val fin = if (finalU.isFinite()) max(0.0, finalU) else 0.0
        val dMess = min(fin, messU(e))
        val dErw = fin - dMess
        val violation = max(0.0, dErw - e.restEffU)
        val s = if (dErw > EPS_U) state.copy(bookedU = state.bookedU + dErw) else state
        return s to Split(dMess, dErw, if (violation > EPS_U) violation else 0.0)
    }

    /**
     * Bucht eine Lieferung der Direktdosis-Familie voll auf das Konto. Was
     * ueber den Rest hinausgeht, ist durch die bestehende Markerautorisierung
     * gedeckt und wird gesondert gefuehrt.
     */
    fun bookDirectDose(state: State, amountU: Double): State {
        if (!amountU.isFinite() || amountU <= EPS_U) return state
        val ueber = max(0.0, amountU - state.restU)
        return state.copy(
            bookedU = state.bookedU + amountU,
            directDoseU = state.directDoseU + amountU,
            coveredByMarkerAuthU = state.coveredByMarkerAuthU + ueber,
        )
    }

    /** Stabilitaetsstrecke: BG ueber der Grenze, kein Fallen, keine gemessene Sperre. */
    object Stability {

        /** Beginn der laufenden Strecke oder null, wenn die Bedingung jetzt nicht gilt. */
        fun since(prevSinceTs: Long?, nowTs: Long, bgMgdl: Double, thresholdMgdl: Double, falling: Boolean, measuredLock: Boolean): Long? =
            if (bgMgdl.isFinite() && bgMgdl > thresholdMgdl && !falling && !measuredLock) (prevSinceTs ?: nowTs) else null

        fun stable(sinceTs: Long?, nowTs: Long, minDurationMin: Int): Boolean =
            sinceTs != null && nowTs - sinceTs >= minDurationMin * 60_000L
    }

    /** Fingerabdruck der Kurve fuer den Export (nicht kryptographisch). */
    fun curveVersion(values: List<Double>): String =
        values.fold(17L) { acc, v -> acc * 31 + java.lang.Double.doubleToLongBits(v) }.let { (it and Long.MAX_VALUE).toString(36) }
}
