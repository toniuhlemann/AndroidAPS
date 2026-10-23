package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * DAS ERWARTUNGSKONTO (E-034) - der Mahlzeitenweg.
 *
 * Im Mahlzeitkontext - von der bewussten Markerautorisierung bis zum Ende des gepinnten Fensters - besteht der
 * Bedarf aus zwei Teilen:
 *
 *     R_mess(a) = (BG(a) - Ziel) / ISF - IOB_gesamt(a)     der gemessene Anteil
 *     X(a)                                              der Erwartungsanteil ([EarlySupply.demandU])
 *
 * mit einer FESTEN persoenlichen Erwartungskurve F (Stuetzwerte, nicht gelernt) und IOB_gesamt aus dem Geraet
 * (Boli, manuelle Boli, Basalabweichung). Angefordert wird max(0, R_mess) + X ([Evaluation.requestU]).
 *
 * WAS ES BEGRENZT: das Budget der fruehen Versorgung ([armEarly], [EarlySupply.budgetU]) wird EINMAL am Marker
 * gebucht und nur verbraucht, nie aufgefuellt. Jede Lieferung d teilt sich in d_mess = min(d, max(0, R_mess)) und
 * d_erw = d - d_mess; nur d_erw verbraucht das Budget, d_mess laeuft durch alle Pruefungen wie ohne Konto.
 * Lieferbar ist zuerst die Anfangsportion, danach je Zyklus eine Portion im Zeitraumbuch ([State.early]). Ohne
 * fruehe Versorgung - Hauptschalter aus, ungueltige Werte, ungueltige Kontoparameter - bucht das Konto Budget 0
 * mit neutralen Pins ([EarlySupply.Pins.NEUTRAL]): dann fliesst nur der gemessene Anteil. Konsolidierung 2.0: das
 * Konto v1 (Vorgriff phi_0 auf den Kurvenrest, Budget aus der Huelle, Guard-Ausnahme, Direktdosis-Buchung) ist
 * entfallen.
 *
 * WANN KEIN d_erw: vor erfuellter Stabilitaet (Start nach Tief oder im Fallen, und nach einem gemessenen Tief in
 * der Episode erneut), in jeder Pause (gemessene Sperre, Signal-, Pumpen- oder Ledgerzustand), bei eingefrorenem
 * Rest (Ruecknahme bei ausbleibender Stoerung), nach einem gemessenen Tief, bis es vorbei und die Freigabe wieder
 * erreicht ist (Tief-Sperre, ohne Bestaetigung), nach dem Entzug durch den Hauptschalter und nach dem Fensterende.
 * Unverbrauchte Erlaubnis verpflichtet nie zur Lieferung.
 *
 * STEIGT DIE MAHLZEIT (Toni 22.10., [Inputs.rising]): "solange Bedarf da ist und BG steigt, muss FUSE entsprechend
 * Mahlzeitenbudget liefern". Dann wartet eine weitere Portion weder auf die Strecke noch auf das Einfrieren; aufhalten
 * koennen sie nur noch die Pause (gemessene und technische Gruende) und die Mengengrenzen.
 *
 * NACHSCHLAG (Toni 23.10., [State.topUpU]): zeigt sich die Mahlzeit groesser als erwartet, waechst die Grenze um den
 * Ueberschuss, hoechstens um den eingestellten Anteil des Budgets. Ohne Einstellung (0) bleibt das Budget die Grenze.
 *
 * REINE FUNKTIONEN. Die Verdrahtung und die Sperren leben im Runner; hier stehen nur Rechnung, Buchung und Zustand.
 * Alle gepinnten Groessen liegen im [State], damit eine spaetere Aenderung der Einstellung eine laufende Episode
 * nicht verschiebt.
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

    /**
     * Toni 22.10.: ab diesem UKF-Trend [mg/dl/min] steigt die Mahlzeit - in zwei Messwerten hintereinander ([Rising]).
     */
    const val RISING_MGDL_PER_MIN = 1.0

    private const val EPS_U = 1e-9

    // ---- Grenzen der Einstellungen (letzte Verteidigung im Kern) ------------
    const val MIN_HORIZON_MIN = 30
    const val MAX_HORIZON_MIN = 120
    const val MIN_STABILITY_BG_MGDL = 70.0
    const val MAX_STABILITY_BG_MGDL = 180.0
    /** Toni: auch 0 (Freigabe im ersten stabilen Zyklus) oder wenige Minuten - die Dauer ist seine Einstellung. */
    const val MIN_STABILITY_MIN = 0
    const val MAX_STABILITY_MIN = 60

    /**
     * Fallgrenze der Stabilitaetsstrecke als RUECKGANG [mg/dl/min, positiv]: ein UKF-Trend unter -Grenze unterbricht
     * die Strecke. Standard = die Kanalkonstante ([LivenessChannel.UKF_FLOOR_MGDL_PER_MIN]) - so war die Strecke immer
     * gebaut. Positiv, damit die Einstellung ohne Vorzeichen eingegeben wird. Obergrenze 10 (Toni): er denkt in
     * 1-Minuten-Deltas, ein deutlicher Abfall muss mindestens bis 8 einstellbar sein.
     */
    const val DEFAULT_STABILITY_FALL_MGDL_PER_MIN = -LivenessChannel.UKF_FLOOR_MGDL_PER_MIN
    const val MIN_STABILITY_FALL_MGDL_PER_MIN = 0.0
    const val MAX_STABILITY_FALL_MGDL_PER_MIN = 10.0
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

            /** Abstand der Eckwerte [min]: die Kurve in vier Werten bei 30, 60, 90 und 120 min. */
            const val ECKEN_ABSTAND_MIN = 30

            /**
             * Die Kurve aus den VIER ECKWERTEN des Einstellungsbildschirms
             * (Toni: eine Zeile voller Zahlen ist nicht verstaendlich):
             * die erwartete Mahlzeitwirkung [U] nach 30, 60, 90 und 120 min ab
             * Marker, dazwischen linear, F(0) = 0. Dieselbe Pruefung wie jede
             * Kurve ([of]): fallend, negativ oder Ende 0 ergibt `null` -
             * Erwartung aus. Alle vier 0 (Standard) heisst: nicht gesetzt.
             */
            fun ausEckwerten(f30U: Double, f60U: Double, f90U: Double, f120U: Double): Curve? {
                val ecken = doubleArrayOf(0.0, f30U, f60U, f90U, f120U)
                if (ecken.drop(1).all { it == 0.0 }) return null
                val werte = (0 until CURVE_POINTS).map { i ->
                    val t = i * CURVE_STEP_MIN
                    val k = min(t / ECKEN_ABSTAND_MIN, ecken.size - 2)
                    val w = (t - k * ECKEN_ABSTAND_MIN).toDouble() / ECKEN_ABSTAND_MIN
                    if (w == 1.0) ecken[k + 1] else ecken[k] + (ecken[k + 1] - ecken[k]) * w
                }
                return of(werte)
            }
        }
    }

    /**
     * Die Einstellungen, geprueft. `null` aus [of] heisst: Erwartung aus - das Konto bucht dann ohne Erwartung
     * ([armWithoutExpectation]). Konsolidierung 2.0: Guard-Ausnahme und Vorgriff phi_0 des Kontos v1 sind samt ihren
     * Einstellungen entfallen; den Anteil am Marker waehlt der Skalierer der fruehen Versorgung.
     */
    data class Params(
        val curve: Curve,
        val horizonMin: Int,
        val stabilityBgMgdl: Double,
        val stabilityMin: Int,
        val releaseThresholdU: Double,
        val stabilityFallMgdlPerMin: Double = DEFAULT_STABILITY_FALL_MGDL_PER_MIN,
    ) {
        companion object {

            fun of(
                curveValues: List<Double>,
                horizonMin: Int,
                stabilityBgMgdl: Double,
                stabilityMin: Int,
                releaseThresholdU: Double,
                stabilityFallMgdlPerMin: Double = DEFAULT_STABILITY_FALL_MGDL_PER_MIN,
            ): Params? {
                val curve = Curve.of(curveValues) ?: return null
                if (horizonMin !in MIN_HORIZON_MIN..MAX_HORIZON_MIN) return null
                if (!stabilityBgMgdl.isFinite() || stabilityBgMgdl !in MIN_STABILITY_BG_MGDL..MAX_STABILITY_BG_MGDL)
                    return null
                if (stabilityMin !in MIN_STABILITY_MIN..MAX_STABILITY_MIN) return null
                if (!releaseThresholdU.isFinite() || releaseThresholdU !in 0.0..MAX_RELEASE_THRESHOLD_U) return null
                if (!stabilityFallMgdlPerMin.isFinite() ||
                    stabilityFallMgdlPerMin !in MIN_STABILITY_FALL_MGDL_PER_MIN..MAX_STABILITY_FALL_MGDL_PER_MIN
                ) return null
                return Params(curve, horizonMin, stabilityBgMgdl, stabilityMin, releaseThresholdU, stabilityFallMgdlPerMin)
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
        /**
         * Verbraucht: d_erw [U], nur steigend. Konsolidierung 2.0: die Direktdosis-Familie und ihre beiden Felder
         * (Direktdosis, durch die Markerautorisierung gedeckt) sind entfallen.
         */
        val bookedU: Double,
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
        // Konsolidierung 2.0: die gepinnte v1-Guard-Ausnahme (guardException) ist entfallen.
        val curveValues: List<Double>,
        /** false: am Marker gab es keine gueltigen Parameter - nur Ersetzung, keine Erwartung. */
        val expectationOn: Boolean = true,
        val stabilityBgMgdl: Double = MIN_STABILITY_BG_MGDL,
        val stabilityMin: Int = MAX_STABILITY_MIN,
        /** Fallgrenze der Stabilitaetsstrecke als Rueckgang [mg/dl/min, positiv], gepinnt wie Grenze und Dauer. */
        val stabilityFallMgdlPerMin: Double = DEFAULT_STABILITY_FALL_MGDL_PER_MIN,
        /** Storniert: gebucht, aber nachweislich nie gesendet [U], nur steigend. */
        val cancelledU: Double = 0.0,
        /**
         * E-034 v2: die fruehe Versorgung dieser Episode, am Marker festgehalten. [a0U] ist das Gesamtbudget B und
         * [phi0] der Skalierer (0..[EarlySupply.MAX_SCALE]). Konsolidierung 2.0: jedes neue Konto traegt Pins (ohne
         * fruehe Versorgung [EarlySupply.Pins.NEUTRAL]); `null` kann nur noch ein ausserhalb des Codecs gebauter
         * Zustand tragen - [step] rechnet ihn mit neutralen Pins.
         */
        val early: EarlySupply.Pins? = null,
        /**
         * v2: die Anfangsportion ist vorbei - geliefert oder ihr Fenster abgelaufen. Nie zurueck - mit EINER Ausnahme,
         * die keine ist: eine Anfangsportion, die nachweislich NIE gesendet wurde, war nie geliefert ([cancel], KI-221).
         * Ein gemessenes Tief setzt die Marke ebenfalls, und die bleibt.
         */
        val initialDone: Boolean = false,
        /** v2: die weiteren Portionen der letzten [EarlySupply.MAX_PERIOD_MIN] Minuten (Zeitraumbuch). */
        val portions: List<EarlySupply.Portion> = emptyList(),
        /**
         * v2 (Toni): ein gemessenes Tief SPERRT den Erwartungsanteil dieser Mahlzeit - vor oder nach der Freigabe.
         * Die Ankuendigung verfaellt nicht. Toni 22.10.: die Sperre faellt VON SELBST, sobald das Tief vorbei und die
         * Freigabe wieder erreicht ist (Strecke erfuellt oder die Mahlzeit steigt) - die Bestaetigung ist entfallen.
         */
        val earlyLowLocked: Boolean = false,
        /** v2: Beginn der laufenden Sperre [ms] (Trail und Anzeige). 0 = nie gesperrt. */
        val earlyLowLockSinceTs: Long = 0L,
        /**
         * v2: in dieser Mahlzeit gab es ein gemessenes Tief. Danach keine Anfangsportion mehr, auch nicht ueber ein Storno
         * ([cancel]). Nie zurueck. Toni 22.10.: sonst rechnen die weiteren Portionen danach wie vor dem Tief - nur das
         * freie IOB, mit Fortsetzung (bis dahin: alles IOB, keine Fortsetzung). Guard und Schwanz (G3/G4) rechnen die
         * Mahlzeit nicht an, solange die Sperre gilt.
         */
        val earlyAfterLow: Boolean = false,
        /**
         * NACHSCHLAG (Toni 23.10.: "Peaks ueber 180 verhindern - nicht zuviel bremsen, dafuer besser verteilen"): was das
         * Konto ueber das gebuchte Budget hinaus geben darf [U], weil sich die Mahlzeit groesser zeigt als erwartet - die
         * beobachtete Stoerung ueber Skalierer mal Kurve, hoechstens [EarlySupply.Pins.topUpShare] mal Budget. Waechst
         * nur mit der Beobachtung und sinkt nur mit dem Deckel. Verpflichtet nie zur Abgabe.
         */
        val topUpU: Double = 0.0,
    ) {
        /** Was noch fliessen darf: Budget plus Nachschlag, abzueglich des Verbrauchten. */
        val restU: Double get() = max(0.0, a0U + topUpU - bookedU)
    }

    /**
     * Die Grundbuchung am Marker - Stoerung, Freigabe, gepinnte Strecke und Kurve, OHNE Budget. Ohne gueltige
     * [params] (leer oder ungueltig) ist die Erwartung fuer diese Episode aus: nur Ersetzung. Konsolidierung 2.0: die
     * Huelle des Kontos v1 (`envelopeU`, Vorgriff phi_0) ist entfallen; das Budget bucht [armEarly].
     */
    private fun grundbuchung(
        authId: Long,
        windowEndTs: Long,
        params: Params?,
        startStable: Boolean,
        nowTs: Long,
        bgMgdl: Double,
        isfMgdlPerU: Double,
    ): State = State(
        authId = authId,
        windowEndTs = windowEndTs,
        startStable = startStable,
        phi0 = 0.0,
        a0U = 0.0,
        bookedU = 0.0,
        released = startStable,
        frozen = false,
        releaseChecked = false,
        disturbanceU = 0.0,
        disturbanceValid = bgMgdl.isFinite() && isfMgdlPerU.isFinite() && isfMgdlPerU > 0.0,
        lastObsTs = nowTs,
        lastBgMgdl = bgMgdl,
        isfAtMarkerMgdlPerU = isfMgdlPerU,
        horizonMin = params?.horizonMin ?: MAX_HORIZON_MIN,
        releaseThresholdU = params?.releaseThresholdU ?: 0.0,
        curveValues = params?.curve?.values() ?: emptyList(),
        expectationOn = params != null,
        stabilityBgMgdl = params?.stabilityBgMgdl ?: MIN_STABILITY_BG_MGDL,
        stabilityMin = params?.stabilityMin ?: MAX_STABILITY_MIN,
        stabilityFallMgdlPerMin = params?.stabilityFallMgdlPerMin ?: DEFAULT_STABILITY_FALL_MGDL_PER_MIN,
    )

    /**
     * Das Konto bei UNGUELTIGEN Kontoparametern ([Params.of] ergibt `null`) - ohne Erwartung, ohne Budget, mit
     * neutralen Pins: nur Ersetzung des Bedarfs.
     */
    fun armWithoutExpectation(
        authId: Long,
        windowEndTs: Long,
        startStable: Boolean,
        nowTs: Long,
        bgMgdl: Double,
        isfMgdlPerU: Double,
    ): State = grundbuchung(authId, windowEndTs, null, startStable, nowTs, bgMgdl, isfMgdlPerU)
        .copy(early = EarlySupply.Pins.NEUTRAL)

    /**
     * E-034 v2: bucht am Marker das GESAMTBUDGET der fruehen Versorgung statt der Huelle -
     * `B = min(B_max, scale * max_tau F(tau) / C(tau))` ([EarlySupply.budgetU]). Kurve, Vorschau, Stabilitaet und
     * Ruecknahme kommen aus [params]; der Skalierer aus [settings]. [unitAction] ist die
     * zeitliche Wirkung einer jetzt gegebenen Einheit aus dem aktiven Insulinmodell. Was gehoben werden darf, sagen
     * G1..G4 aus [switches] (die Guard-Ausnahme von v1 ist mit der Konsolidierung 2.0 entfallen). Ohne fruehe
     * Versorgung bucht der Runner hier mit [EarlySupply.Settings.NEUTRAL] und [EarlySupply.Switches.NONE] - Budget 0.
     *
     * ZWEI GETRENNTE FRAGEN (Toni): [startStable] ist die Freigabe am Marker (Strecke erfuellt - sonst wartet die
     * Anfangsportion); [afterLow] waehlt den Skalierer. "Nach Tief" ist nur ein Start IM Tief - der Zucker am Druck
     * unter der Stabilitaetsgrenze oder ein gemessenes Tief (Toni). Ein Tief VOR dem Druck zaehlt nicht, und ein Fallen
     * hat mit Tief nichts zu tun - es verzoegert nur die Anfangsportion.
     */
    fun armEarly(
        authId: Long,
        windowEndTs: Long,
        params: Params,
        settings: EarlySupply.Settings,
        switches: EarlySupply.Switches,
        startStable: Boolean,
        nowTs: Long,
        bgMgdl: Double,
        isfMgdlPerU: Double,
        unitAction: (Double) -> Double,
        afterLow: Boolean = !startStable,
    ): State {
        val scale = if (afterLow) settings.scaleAfterLow else settings.scaleStable
        val b = EarlySupply.budgetU(params.curve, params.horizonMin, scale, settings.budgetMaxU, unitAction)
        return grundbuchung(authId, windowEndTs, params, startStable, nowTs, bgMgdl, isfMgdlPerU).copy(
            phi0 = scale,
            a0U = if (b.isFinite()) max(0.0, b) else 0.0,
            early = EarlySupply.Pins(
                initialShare = settings.initialShare,
                maxPortionU = settings.maxPortionU,
                maxPerPeriodU = settings.maxPerPeriodU,
                periodMin = settings.periodMin,
                switches = switches,
                topUpShare = settings.topUpShare,
            ),
        )
    }

    /**
     * E-034 v2: was die fruehe Versorgung je Zyklus aus dem Geraet braucht. Fehlt es (kein Insulinmodell), fliesst in
     * diesem Zyklus kein Erwartungsanteil.
     */
    class EarlyInputs(
        /** Gesamtes IOB samt Transport [U]. */
        val iobU: Double,
        /** Davon NICHT fuer den aktuellen Zielabstand gebunden [U] ([EarlySupply.freeIobU]). */
        val freeIobU: Double,
        /** Erwartete Wirkung des vorhandenen und ausstehenden Insulins von jetzt bis jetzt + tau [U]. */
        val existingActionU: (Double) -> Double,
        /** Wirkungsanteil einer jetzt gegebenen Einheit bis tau [min] (aktives Insulinmodell). */
        val unitAction: (Double) -> Double,
    )

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
        /** Gemessenes Tief in diesem Zyklus (K5): nimmt die Freigabe zurueck. */
        val measuredLow: Boolean = false,
        /**
         * v2: die Ruecknahme kommt aus einem GEMESSENEN Tief (nicht nur aus einem Neustart) - dann sperrt sie die
         * fruehe Versorgung dieser Mahlzeit, auch vor der Freigabe.
         */
        val lowLocksEarlySupply: Boolean = false,
        /**
         * TONI 22.10.: die Mahlzeit steigt ([Rising]) - nur fuer weitere Portionen, ueber der Stabilitaetsgrenze, ohne
         * gemessenes Tief (das prueft der Aufrufer). Dann gibt sie die Freigabe wie eine erfuellte Strecke, und das
         * Einfrieren haelt die Portion nicht auf. Die Pause meldet der Aufrufer dann nur aus gemessenen und technischen
         * Gruenden.
         */
        val rising: Boolean = false,
        /** v2: die Geraetegroessen der fruehen Versorgung; `null` = in diesem Zyklus keine. */
        val early: EarlyInputs? = null,
        /**
         * Schritt 2 (Toni): die beobachtete Fortsetzung der laufenden Mahlzeit ([EarlySupply.demandU]); `null` = die
         * Mahlzeit laeuft nicht. Sie zaehlt nur fuer weitere Portionen - seit 22.10. auch nach einem gemessenen Tief.
         */
        val continuation: EarlySupply.Continuation? = null,
        /** KANDIDAT W (Pruefstand): Wendephase, Rampe und Abwaertsstopp; `null` = ohne W (Produkt). */
        val turn: Turn? = null,
    )

    /**
     * KANDIDAT W (Pruefstand, [TurnDetector]): in der Wendephase ([active]) kommt je Zyklus hoechstens [rampFactor] mal
     * die eingestellte Rate (Menge je Zeitraum / Zeitraum) mal der Zyklusdauer [cycleMin] - klein an der Wende, mit der
     * Beschleunigung bis zur vollen Rate, nie mehr als die Anforderung; Guard und Schwanz rechnen dort dieselbe Mahlzeit
     * an wie die Anforderung. [stop] = Wende nach unten, keine weitere Portion. Nur fuer weitere Portionen, nie fuer die
     * Anfangsportion.
     */
    data class Turn(
        val active: Boolean,
        val rampFactor: Double,
        val stop: Boolean,
        val sinceTs: Long = 0L,
        val accel5MgdlPerMin: Double = Double.NaN,
        val event: String? = null,
        val cycleMin: Double = 1.0,
    )

    /** v2: was die fruehe Versorgung in diesem Zyklus rechnet (Export und Lieferweg). */
    data class EarlyEvaluation(
        /** X(a): aktuelle Anforderung der fruehen Versorgung [U] - nur die erwartete Mahlzeit, freies IOB angerechnet. */
        val demandU: Double,
        /** phi_eff: der Skalierer nach Anpassung an die Beobachtung. */
        val scaleEff: Double,
        /** true = Anfangsportion (G1/G2), false = weitere Portion (G3/G4). */
        val initial: Boolean,
        /** Rest der Menge je Zeitraum [U]. */
        val periodLeftU: Double,
        /** G3/G4: die bestaetigte Erwartung, die Guard und Schwanz anrechnen (0..1, [EarlySupply.confirmedGuardScale]). */
        val guardScale: Double = 0.0,
        /** Schritt 2: die Rate der beobachteten Fortsetzung, die in [demandU] gezaehlt hat [U/min]; 0 = keine. */
        val continuationUPerMin: Double = 0.0,
        /** Anzeige: die jetzt erwartete Mahlzeitwirkung [mg/dl/min] ([EarlySupply.expectedDriveMgdlPerMin]); 0 = keine. */
        val expectedDriveMgdlPerMin: Double = 0.0,
        /** KANDIDAT W (Pruefstand): die in diesem Zyklus geltende Wende; `null` = ohne W. */
        val turn: Turn? = null,
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
        /** In DIESEM Zyklus hat ein gemessenes Tief die Freigabe zurueckgenommen. */
        val releaseRevoked: Boolean = false,
        /** Zeitpunkt des Zyklus (fuer das Zeitraumbuch der fruehen Versorgung). */
        val atTs: Long = 0L,
        /** v2: die Rechnung der fruehen Versorgung (Konsolidierung 2.0: bei jedem Konto gesetzt). */
        val early: EarlyEvaluation? = null,
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

        // 2 Freigabe. Ein gemessenes Tief in der Episode nimmt sie zurueck (K5,
        //   Toni): vor weiterem d_erw gilt die Strecke erneut - dieselbe Regel
        //   wie am Druck; der gemessene Anteil fliesst weiter, nur der Vorgriff
        //   wartet. Im Zyklus des Tiefs gibt es keine Wiederfreigabe, auch wenn
        //   der Aufrufer (falsch) Stabilitaet meldet. Sonst bleibt sie, einmal
        //   erreicht.
        val zurueckgenommen = inputs.measuredLow && s.released && !expired
        if (zurueckgenommen) s = s.copy(released = false)
        // v2 (Toni): ein gemessenes Tief SPERRT den Erwartungsanteil - auch wenn die Freigabe noch auf die Strecke
        //   wartet. Danach misst die Beobachtung die Rescue-Kohlenhydrate mit; sie als bestaetigte Mahlzeit zu lesen
        //   hiesse, gegen die Rescue zu dosieren (Bank N2, W3). Der gemessene Anteil laeuft weiter wie bisher.
        val tiefSperrt = s.early != null && inputs.measuredLow && inputs.lowLocksEarlySupply && !expired
        if (tiefSperrt) s = s.copy(
            earlyLowLocked = true,
            earlyLowLockSinceTs = if (s.earlyLowLocked) s.earlyLowLockSinceTs else inputs.nowTs,
            earlyAfterLow = true,
            initialDone = true,
        )
        // Toni 22.10.: steigt die Mahlzeit, gibt das die Freigabe wie eine erfuellte Strecke.
        if (!s.released && (inputs.stableNow || inputs.rising) && !inputs.measuredLow && !expired) s = s.copy(released = true)
        // v2: die Ankuendigung verfaellt nicht. Toni 22.10.: die Sperre faellt VON SELBST, sobald das Tief vorbei und die
        //   Freigabe wieder erreicht ist - ohne Bestaetigung ("die Bestaetigung ist Mist, da ich es erst sehe, wenn ich aufs
        //   Handy schaue"). Kein neues Budget, keine Anfangsportion.
        if (s.earlyLowLocked && s.released && !inputs.measuredLow && !expired) s = s.copy(earlyLowLocked = false)

        // 3 Ruecknahme: bei +30 pruefen, danach Wiederfreigabe bei erreichter Schwelle.
        val bestaetigt = s.disturbanceValid && s.disturbanceU >= s.releaseThresholdU
        if (!s.releaseChecked && inputs.ageMin >= RELEASE_CHECK_AGE_MIN) {
            s = s.copy(releaseChecked = true, frozen = !bestaetigt)
        } else if (s.frozen && bestaetigt && !expired) {
            s = s.copy(frozen = false)
        }

        // Konsolidierung 2.0: der Zweig des Kontos v1 (Bedarf R_mess + phi_0 x Kurvenrest, Rest als Huellenbudget) ist
        // entfallen - jedes Konto rechnet nach den Regeln der fruehen Versorgung, ohne Pins mit neutralen.
        return stepEarly(s, inputs, s.early ?: EarlySupply.Pins.NEUTRAL, expired, zurueckgenommen)
    }

    /**
     * E-034 v2, Schritt 4 und 5: Bedarf und lieferbarer Erwartungsanteil der fruehen Versorgung.
     *
     *  - Gemessener Teil: `R_mess = (BG - Ziel)/ISF - IOB`, IOB voll angerechnet.
     *  - Erwartungsteil X(a) ([EarlySupply.demandU]): nur die erwartete Mahlzeit, angerechnet nur das freie IOB.
     *    Damit zaehlt kein IOB zweimal: solange R_mess > 0 ist, ist das ganze IOB fuer den Zielabstand gebunden.
     *  - Anfrage = max(0, R_mess) + X; [Evaluation.rRohU] traegt in v2 genau diese Summe.
     *  - Lieferbarer Erwartungsanteil: Anfangsportion `alpha * B` bis [EarlySupply.INITIAL_WINDOW_MIN], danach je Zyklus
     *    `min(Budgetrest, maximale Portion, Rest der Menge je Zeitraum)`. Kein Ansparen: der Zeitraumrest kommt aus dem
     *    tatsaechlich Gelieferten, nicht aus verpassten Zyklen. Pause, fehlende Freigabe, Ruecknahme oder Fensterende
     *    = 0 (die Anforderung X bleibt sichtbar, verpflichtet aber nie). Steigt die Mahlzeit ([Inputs.rising]), haelt das
     *    Einfrieren eine weitere Portion nicht auf (Toni 22.10.).
     */
    private fun stepEarly(
        state: State,
        inputs: Inputs,
        pins: EarlySupply.Pins,
        expired: Boolean,
        revoked: Boolean,
    ): Pair<State, Evaluation> {
        var s = state
        if (!s.initialDone && inputs.ageMin >= EarlySupply.INITIAL_WINDOW_MIN) s = s.copy(initialDone = true)
        val buch = s.portions.filter { it.ts <= inputs.nowTs && inputs.nowTs - it.ts < EarlySupply.MAX_PERIOD_MIN * 60_000L }
        if (buch.size != s.portions.size) s = s.copy(portions = buch)
        val imZeitraumU = buch.filter { inputs.nowTs - it.ts < pins.periodMin * 60_000L }.sumOf { it.amountU }
        val zeitraumRestU = max(0.0, pins.maxPerPeriodU - imZeitraumU)

        val curve = Curve.of(s.curveValues)
        // NACHSCHLAG (Toni 23.10.): zeigt sich die Mahlzeit groesser als erwartet - beobachtete Stoerung ueber Skalierer
        // mal Kurve, nach dem Kurvenende ihr Endwert -, darf das Konto den Ueberschuss zusaetzlich geben, hoechstens den
        // festgehaltenen Anteil des Budgets. Nur mit gueltiger Beobachtung; einmal erlaubt, bleibt er, ausser der Deckel
        // sinkt. Er hebt nur die Grenze - wie viel fliesst, sagen weiter Anforderung, Pruefungen und Portionsdeckel.
        if (curve != null && !expired) {
            val deckel = max(0.0, pins.topUpShare) * s.a0U
            val ueberschuss = if (s.disturbanceValid && s.disturbanceU.isFinite()) {
                max(0.0, s.disturbanceU - s.phi0 * curve.at(inputs.ageMin))
            } else 0.0
            val nachschlag = min(deckel, max(s.topUpU, ueberschuss))
            if (nachschlag != s.topUpU) s = s.copy(topUpU = nachschlag)
        }
        val scaleEff = if (curve == null) 0.0
        else EarlySupply.effectiveScale(s.phi0, inputs.ageMin, s.disturbanceU, s.disturbanceValid, curve)
        val e = inputs.early
        val anfang = !s.initialDone
        // Schritt 2 (Toni): die beobachtete Fortsetzung zaehlt nur fuer weitere Portionen. Toni 22.10.: nach einem Tief
        // geht es weiter wie davor - mit Fortsetzung, und angerechnet wird nur das freie IOB (bis dahin: alles IOB).
        val fortsetzung = inputs.continuation?.takeIf { !anfang }
        val x = if (expired || curve == null || e == null) 0.0 else EarlySupply.demandU(
            curve, s.horizonMin, inputs.ageMin, scaleEff, e.iobU, e.freeIobU, e.existingActionU, e.unitAction, fortsetzung,
        )
        val rMess = (inputs.bgMgdl - inputs.targetMgdl) / inputs.isfMgdlPerU - inputs.iobTotalU
        val mess = if (rMess.isFinite()) max(0.0, rMess) else 0.0
        // KANDIDAT W (Pruefstand): nur weitere Portionen - nach einer Wende nach unten keine, in der Wendephase hoechstens
        // Rampe mal eingestellte Rate je Zyklus (klein beginnen, nach Beschleunigung steigern), nie mehr als die
        // Anforderung. Ohne W unveraendert.
        val wende = inputs.turn?.takeIf { !anfang }
        // Toni 22.10.: steigt die Mahlzeit, haelt das Einfrieren eine weitere Portion nicht auf.
        val eingefroren = s.frozen && !(inputs.rising && !anfang)
        val restEff = when {
            expired || inputs.paused || !s.released || eingefroren || e == null || pins.withdrawn || s.earlyLowLocked -> 0.0
            anfang -> min(s.restU, pins.initialShare * s.a0U)
            wende?.stop == true -> 0.0
            wende?.active == true -> min(
                min(min(s.restU, pins.maxPortionU), zeitraumRestU),
                min(x, max(0.0, wende.rampFactor) * pins.maxPerPeriodU / max(1, pins.periodMin) * max(0.0, wende.cycleMin)),
            )
            else -> min(min(s.restU, pins.maxPortionU), zeitraumRestU)
        }
        val status = when {
            expired -> "EXPIRED"
            // Das Konto ohne Erwartung (ungueltige Kontoparameter) meldet sich wie bisher.
            !s.expectationOn -> "EXPECTATION_OFF"
            s.earlyLowLocked -> "LOCKED_BY_LOW"
            pins.withdrawn -> "WITHDRAWN"
            !s.released -> "WAITING_FOR_STABILITY"
            inputs.paused -> "PAUSED"
            eingefroren -> "FROZEN"
            s.restU <= EPS_U -> "BUDGET_USED"
            e == null -> "NO_MODEL"
            anfang -> "INITIAL"
            else -> "ACTIVE"
        }
        return s to Evaluation(
            rMessU = rMess, phiZU = x, rRohU = mess + x, requestU = mess + x, restEffU = restEff,
            expired = expired, paused = inputs.paused, released = s.released, frozen = s.frozen, status = status,
            releaseRevoked = revoked, atTs = inputs.nowTs,
            early = EarlyEvaluation(
                demandU = x, scaleEff = scaleEff, initial = anfang, periodLeftU = zeitraumRestU,
                // Nach einem Tief rechnen Guard und Schwanz die Mahlzeit erst wieder an, wenn die Sperre gefallen ist (Toni
                // 22.10.: von selbst) - dann wie sonst, soweit sie sich zeigt und hoechstens die Kurve. Solange sie gilt,
                // keine Anrechnung.
                guardScale = if (curve == null || expired || s.earlyLowLocked) 0.0
                // KANDIDAT W (Pruefstand): bis zur gebuchten Skalierung nur in der Wendephase.
                else if (wende?.active == true)
                    EarlySupply.confirmedGuardScaleToBooked(s.phi0, inputs.ageMin, s.disturbanceU, s.disturbanceValid, curve)
                else EarlySupply.confirmedGuardScale(s.phi0, inputs.ageMin, s.disturbanceU, s.disturbanceValid, curve),
                continuationUPerMin = if (expired || curve == null || e == null) 0.0
                else fortsetzung?.rateUPerMin?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                // Ohne Budget ("Nur Marker", ohne fruehe Versorgung) erwartet das Konto nichts - die Linie bleibt 0.
                expectedDriveMgdlPerMin = if (curve == null || expired || s.a0U <= EPS_U) 0.0
                else EarlySupply.expectedDriveMgdlPerMin(curve, inputs.ageMin, scaleEff, s.isfAtMarkerMgdlPerU),
                turn = wende,
            ),
        )
    }

    /** Der gemessene Anteil max(0, R_mess); nicht berechenbar zaehlt als 0 (nie NaN in einer Menge). */
    private fun messU(e: Evaluation): Double = if (e.rMessU.isFinite()) max(0.0, e.rMessU) else 0.0

    /** Der gemessene Anteil max(0, R_mess) - oeffentlich fuer den Boden der fruehen Versorgung (v2). */
    fun measuredShareU(e: Evaluation): Double = messU(e)

    /** Hoechstmenge dieses Zyklus aus dem Konto: d_mess-Anteil plus d_erw bis Budgetrest. */
    fun deliverableU(e: Evaluation): Double = min(e.requestU, messU(e) + e.restEffU)

    /** Der Anteil, der nur als d_erw fliessen darf (und nur er wird von der fruehen Versorgung gehoben). */
    fun expectationShareU(e: Evaluation): Double = min(e.restEffU, max(0.0, e.requestU - messU(e)))

    data class Split(
        val dMessU: Double,
        val dErwU: Double,
        /** d_erw ueber dem erlaubten Rest - ein Mechanismusverstoss, nie still. */
        val violationU: Double,
        /** Die Menge aus dem Normalbedarf im Kontofenster - weder d_mess noch d_erw, verbraucht nichts. */
        val dNormU: Double = 0.0,
    )

    // Konsolidierung 2.0: der Boden der v1-Guard-Ausnahme (`guardExceptionFloorU`) ist entfallen.

    /**
     * Bucht eine Lieferung aus dem Konto. [finalU] ist die Endmenge nach allen
     * Pruefungen (nach der Expositionsgrenze). Was davon der Normalbedarf im
     * Kontofenster getragen hat ([normalU]), zaehlt zuerst als d_norm und
     * verbraucht nichts; vom Rest d: d_mess = min(d, max(0, R_mess)),
     * d_erw = d - d_mess (Autorisierungsentwurf Abschnitt 2).
     */
    fun book(
        state: State,
        e: Evaluation,
        finalU: Double,
        measuredCapU: Double = Double.POSITIVE_INFINITY,
        normalU: Double = 0.0,
    ): Pair<State, Split> {
        val gesamt = if (finalU.isFinite()) max(0.0, finalU) else 0.0
        val norm = if (normalU.isFinite()) min(gesamt, max(0.0, normalU)) else 0.0
        val fin = gesamt - norm
        // v2: hat eine Anhebung den Erwartungsanteil auf das gelegt, was der Normalpfad MIT Guard freigab, zaehlt als
        // d_mess nur dieser freigegebene Teil - sonst verbuchte ein vom Guard gekuerzter gemessener Anteil
        // Erwartungsinsulin als gemessen, und das Budget waere keine Grenze mehr.
        val kappe = if (measuredCapU.isNaN() || measuredCapU < 0.0) 0.0 else measuredCapU
        val dMess = min(fin, min(messU(e), kappe))
        val dErw = fin - dMess
        val violation = max(0.0, dErw - e.restEffU)
        var s = if (dErw > EPS_U) state.copy(bookedU = state.bookedU + dErw) else state
        if (s.early != null && dErw > EPS_U) s =
            if (e.early?.initial == true) s.copy(initialDone = true)
            else s.copy(portions = s.portions + EarlySupply.Portion(e.atTs, dErw))
        return s to Split(dMess, dErw, if (violation > EPS_U) violation else 0.0, dNormU = norm)
    }

    // Konsolidierung 2.0: die volle Buchung der Direktdosis-Familie (`bookDirectDose`) ist entfallen.

    /**
     * STORNO einer gebuchten, aber nachweislich nie gesendeten Menge
     * (Gate-Reject oder Nicht-Sende-Beweis) - dieselbe Regel wie bei den
     * uebrigen Buechern der Mahlzeit. Kein Auffuellen: A_0 bleibt, und
     * zurueckgenommen wird nur, was nie geflossen ist. [erwU] ist der
     * d_erw-Anteil; er steht in [State.cancelledU], damit jede Erhoehung des
     * Rests erklaert ist (Konsolidierung 2.0: der Direktdosis-Anteil ist entfallen).
     *
     * v2 (KI-221): die fruehe Versorgung genauso - sonst rechnete sie mit einer Lieferung, die es nie gab.
     *  - Die Portion der Buchung [bookedAtTs] verlaesst das Zeitraumbuch um den nie gesendeten Teil: der Zeitraumrest
     *    kommt aus dem tatsaechlich Gelieferten ([stepEarly]).
     *  - Eine GANZ nie gesendete Anfangsportion ([initialNeverSent]) war nie gegeben: die Marke [State.initialDone]
     *    faellt zurueck. Aber nur die Marke, die die Buchung gesetzt hat - nach einem gemessenen Tief bleibt sie (das
     *    Tief hat sie gesetzt, und danach gibt es keine Anfangsportion mehr), und ein abgelaufenes Anfangsfenster setzt
     *    [stepEarly] im naechsten Zyklus wieder. Eine teilweise gesendete Anfangsportion ist gegeben: sie ist einmalig,
     *    wie bei einer kleineren Anforderung.
     * Die Marke wird bewusst nicht erst bei belegter Lieferung gesetzt: bis zum Beleg vergeht mindestens ein Zyklus, und
     * in dem boete das Konto die Anfangsportion ein zweites Mal an. Zurueck geht sie nur mit dem Beweis des Nicht-Sendens.
     */
    fun cancel(
        state: State,
        erwU: Double,
        bookedAtTs: Long = 0L,
        initialNeverSent: Boolean = false,
    ): State {
        val erw = if (erwU.isFinite()) max(0.0, erwU) else 0.0
        val zurueck = min(erw, state.bookedU)
        if (zurueck <= EPS_U) return state
        var s = state.copy(
            bookedU = state.bookedU - zurueck,
            cancelledU = state.cancelledU + zurueck,
        )
        if (s.early == null || erw <= EPS_U) return s
        val i = if (bookedAtTs > 0L) s.portions.indexOfLast { it.ts == bookedAtTs } else -1
        if (i >= 0) {
            val p = s.portions[i]
            val rest = p.amountU - erw
            s = s.copy(
                portions = if (rest > EPS_U) s.portions.mapIndexed { j, q -> if (j == i) q.copy(amountU = rest) else q }
                else s.portions.filterIndexed { j, _ -> j != i },
            )
        }
        if (initialNeverSent && s.initialDone && !s.earlyAfterLow && !s.earlyLowLocked) s = s.copy(initialDone = false)
        return s
    }

    /** Stabilitaetsstrecke: BG ueber der Grenze, kein Fallen, keine gemessene Sperre. */
    object Stability {

        /** Beginn der laufenden Strecke oder null, wenn die Bedingung jetzt nicht gilt. */
        fun since(prevSinceTs: Long?, nowTs: Long, bgMgdl: Double, thresholdMgdl: Double, falling: Boolean, measuredLock: Boolean): Long? =
            if (bgMgdl.isFinite() && bgMgdl > thresholdMgdl && !falling && !measuredLock) (prevSinceTs ?: nowTs) else null

        fun stable(sinceTs: Long?, nowTs: Long, minDurationMin: Int): Boolean =
            sinceTs != null && nowTs - sinceTs >= minDurationMin * 60_000L
    }

    /**
     * TONI 22.10.: STEIGT DIE MAHLZEIT? Der UKF-Trend ist mindestens [RISING_MGDL_PER_MIN] - in zwei Messwerten
     * hintereinander, damit ein einzelner Ausreisser nicht zaehlt. Gezaehlt wird je Messwert, nicht je Rechnung: zweimal
     * auf denselben Wert gerechnet bleibt ein Messwert. Eine Luecke ueber [MAX_OBSERVATION_GAP_MIN] beginnt neu.
     */
    data class Rising(
        /** Messzeit des ersten steigenden Werts der laufenden Reihe [ms]; 0 = keine Reihe. */
        val sinceTs: Long = 0L,
        /** Messzeit des zuletzt gezaehlten Werts [ms]; 0 = keiner. */
        val lastTs: Long = 0L,
    ) {

        /** Mindestens zwei steigende Messwerte hintereinander. */
        val rising: Boolean get() = sinceTs > 0L && lastTs > sinceTs

        /** Die Reihe nach dem Messwert zur Zeit [sampleTs] mit dem UKF-Trend [ukfMgdlPerMin]. */
        fun next(sampleTs: Long, ukfMgdlPerMin: Double): Rising {
            if (sampleTs <= 0L || sampleTs < lastTs) return Rising()
            if (sampleTs == lastTs) return this
            val steigt = ukfMgdlPerMin.isFinite() && ukfMgdlPerMin >= RISING_MGDL_PER_MIN
            val luecke = lastTs > 0L && sampleTs - lastTs > MAX_OBSERVATION_GAP_MIN * 60_000L
            return when {
                !steigt -> Rising(sinceTs = 0L, lastTs = sampleTs)
                sinceTs > 0L && !luecke -> copy(lastTs = sampleTs)
                else -> Rising(sinceTs = sampleTs, lastTs = sampleTs)
            }
        }
    }

    /** Fingerabdruck der Kurve fuer den Export (nicht kryptographisch). */
    fun curveVersion(values: List<Double>): String =
        values.fold(17L) { acc, v -> acc * 31 + java.lang.Double.doubleToLongBits(v) }.let { (it and Long.MAX_VALUE).toString(36) }
}
