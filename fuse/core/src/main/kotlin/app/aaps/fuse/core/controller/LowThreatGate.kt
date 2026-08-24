package app.aaps.fuse.core.controller

import kotlin.math.abs

/**
 * DAS LOW-TOR - der einzige Weg zu einer Zero-TBR (Tonis Vertrag, 17.08.2026).
 *
 * "Profilbasal ist der Normalzustand. Zero-TBR ist eine aussergewoehnliche
 *  Low-Schutzmassnahme und darf nur aus einem eigenen, positiv nachgewiesenen
 *  Low-Tor entstehen."
 *
 * DER BEFUND, der das ausgeloest hat: an einem vollen Tag lief die Null 677
 * von 1129 Zyklen - 60 % der Zeit ohne Fundament, bei einem BG zwischen 53
 * und 270. Ursache war die Kette `minLower < guardFloor -> ZERO_TEMP`
 * (FuseController): eine langfristige, kohlenhydratfrei gerechnete Modellbahn
 * durfte die Grundversorgung vollstaendig entfernen. Das Muster danach war
 * immer dasselbe - Basal fehlt, BG hebt ab, FUSE laeuft mit SMBs hinterher.
 *
 * WARUM EIN EIGENES ERGEBNIS UND KEIN WEITERER SONDERFALL AN GUARD_FLOOR
 * (Toni): Sonderfaelle haengen sich an eine Kette, die zu etwas anderem
 * gebaut wurde, und jeder neue muss alle vorherigen kennen. Ein eigenes Tor
 * kehrt die Beweislast um - eine Null braucht einen POSITIVEN Nachweis,
 * nicht das Ausbleiben eines Einwands.
 *
 * WAS DIE NULL UEBERHAUPT LEISTEN KANN, gerechnet an Tonis Profil
 * (0,60 U/h, ISF 63, Lyumjev peak 45 / DIA 9 h) - der Grund fuer die
 * Nutzenprobe:
 *
 *     Zeit bis Boden    20 min    30 min    60 min    90 min   120 min
 *     Wirkung            0,4       1,1       6,2      15,8      28,7  mg/dl
 *
 * Unter 30 Minuten Vorlauf liegt der Effekt bei einem Zehntel des
 * Sensorrauschens - die Null ist dort keine schwache Massnahme, sondern gar
 * keine. Toni: "0 tbr muss auch einen messbaren nutzen haben und eine sich
 * anbahnende hypo tatsaechlich rechnerisch ausbremsen koennen." Daraus folgt
 * die kontraintuitive, aber physikalisch richtige Umkehrung: beim SCHNELLEN
 * Sturz hilft Basal nicht (da helfen Kohlenhydrate), beim LANGSAMEN Absinken
 * hilft es.
 *
 * WAS DIESES TOR AUSDRUECKLICH NICHT TUT: es begrenzt keine Mengen. `minLower`,
 * der Schwanz-Guard, Marker, Evidenz und Mahlzeitenphase duerfen SMBs
 * weiterhin blocken und begrenzen - sie duerfen nur nicht mehr das
 * Profilbasal entfernen.
 */
object LowThreatGate {

    /**
     * Warum eine Zero-TBR zulaessig ist - oder eben nicht.
     *
     * NUR [MEASURED_LOW] und [FALLING_WITH_BOLUS_OVERCOVERAGE] duerfen
     * [FuseController.TbrAction.ZERO_TEMP] erzeugen.
     */
    enum class Verdict {
        /** Kein Low-Befund. Profilbasal bleibt - unabhaengig davon, was
         *  Bahn, Schwanz oder Marker sagen. */
        NONE,

        /**
         * GEMESSENES Tief, jetzt. Der unmittelbare Schutz laeuft OHNE
         * Nutzenrechnung: bei einem realen Tief laesst man nichts unversucht,
         * auch wenn die rechnerische Wirkung klein ist.
         */
        MEASURED_LOW,

        /**
         * Der Verlauf faellt gemessen, der BOLUS-Anteil deckt mehr als die
         * Strecke zum Boden, und die Null kommt frueh genug, um noch etwas
         * auszurichten.
         */
        FALLING_WITH_BOLUS_OVERCOVERAGE,
    }

    /**
     * DIE VOLLSTAENDIGE RECHENSPUR des Tores (Tonis Auflage 17.08. vor dem
     * Produktiv-Flash: "Ohne diese Telemetrie waeren die neuen proaktiven
     * Zero-TBRs spaeter nicht nachvollziehbar").
     *
     * Jede Zahl, die in die Entscheidung eingeht, steht hier - auch bei den
     * ABGELEHNTEN Faellen, und gerade die sind die interessanten: eine Null,
     * die NICHT kam, ist im Trail sonst von einem Zyklus ohne Befund nicht zu
     * unterscheiden. `null` heisst "bis dahin gar nicht gerechnet", nicht
     * "der Wert war 0".
     */
    data class Result(
        val verdict: Verdict,
        /** Die GEMESSENE Rate, gegen die entschieden wurde [mg/dl/min]. */
        val fallRatePerMin: Double? = null,
        /** Der verwendete BOLUS-Anteil [U] - nie das Netto-IOB. */
        val bolusIobU: Double? = null,
        /** Abstand des Ankers zum Boden [mg/dl]. */
        val distanceToFloorMgdl: Double? = null,
        /** Bodenkontakt bei linearer Fortschreibung der gemessenen Rate [min]. */
        val minutesToFloor: Double? = null,
        /** Was eine ab jetzt laufende Null bis dahin verhindert [mg/dl]. */
        val benefitMgdl: Double? = null,
        /**
         * UEBERDECKUNGSSTAERKE [mg/dl]: bolusIobU x ISF minus Abstand zum
         * Boden - wie weit die Bolusdeckung ueber den Fall hinausreicht
         * (v29, Toni 24.08. nacht: der 21:58-Grenzfall trug nur +0,55).
         * REINES MESSFELD fuer Export und Replay; eine Mindestmarge wird
         * erst nach Replay-Auswertung festgelegt, nicht geraten.
         */
        val overcoverageMarginMgdl: Double? = null,
        /** Abstand des Bodenkontakts zur Horizontkante [min] - wie knapp das
         *  120-Minuten-Fenster den Fall noch fasst (21:58: nur 2,8 min).
         *  Reines Messfeld, dieselbe Regel wie oben. */
        val horizonMarginMin: Double? = null,
        /** WORAN es gescheitert ist; `null` bei offenem Tor. */
        val denial: String? = null,
    )

    /** Ablehnungsgruende - als Konstanten, weil im Trail danach gesucht wird. */
    const val DENY_UNHEALTHY = "SIGNAL_UNHEALTHY"
    const val DENY_INPUT = "INPUT_UNUSABLE"
    const val DENY_NOT_FALLING = "NOT_FALLING"
    const val DENY_NO_OVERCOVERAGE = "NO_BOLUS_OVERCOVERAGE"
    const val DENY_TOO_FAR = "FLOOR_BEYOND_HORIZON"
    const val DENY_NO_BENEFIT = "BENEFIT_BELOW_THRESHOLD"

    /** Mindestwirkung, ab der eine Null als nuetzlich gilt [mg/dl]. Unterhalb
     *  des Sensorrauschens (~5 mg/dl) waere die Wirkung nicht einmal messbar -
     *  eine Massnahme, deren Erfolg man nicht sehen kann, ist keine. */
    const val MIN_BENEFIT_MGDL = 5.0

    /** Kurzfristfenster der Richtungsprobe [min]. Bewusst begrenzt: die Frage
     *  ist "faellt es JETZT Richtung Boden", nicht "wo liegt das Minimum einer
     *  Zweistundenbahn". */
    const val NEAR_TERM_HORIZON_MIN = 120

    /**
     * DAS GEMESSENE ABWAERTSRISIKO - getrennt vom Basalnutzen (Toni 19.08.).
     *
     * DER ARCHITEKTURFEHLER, DEN DIESE TRENNUNG BEHEBT. [evaluate] beantwortet
     * zwei verschiedene Fragen in EINEM Verdikt: "faellt es gemessen und ist
     * es durch Bolus ueberdeckt?" UND "bringt eine Zero-TBR noch mindestens
     * 5 mg/dl?". Nur wenn BEIDES zutrifft, entstand
     * [Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE] - und dieses Verdikt steuerte
     * ausschliesslich die TBR. Fuer den SMB-Pfad war es kein Riegel.
     *
     * Daraus wurde am Abend des 19.08. eine widerspruechliche Antwort:
     *
     *     gemessene Lage   fallend und durch Bolus ueberdeckt
     *     Basalpfad        ZERO_TEMP
     *     SMB-Pfad         Marker hebt GUARD_FLOOR -> weitere 0,15 U
     *
     * Vier Minuten Zero-TBR halten bei 0,50 U/h rund 0,033 U zurueck, waehrend
     * gleichzeitig 0,60 U SMB dazukamen. Das ist keine Kompensation, das ist
     * eine Groessenordnung daneben. Gemessen: Marker 17:49, danach 24 positive
     * Zyklen mit 3,70 U; ab 17:55 meldete FUSE bereits
     * FALLING_WITH_BOLUS_OVERCOVERAGE und trotzdem gingen noch 19 SMBs mit
     * 2,95 U hinaus; Minimum 58,2 mg/dl um 18:47 bei 3,20 U IOB.
     *
     * UND UM 18:13 WURDE ES DEUTLICHER: die Zero-TBR galt wegen
     * BENEFIT_BELOW_THRESHOLD als zu spaet und nutzlos. Daraus folgte
     * faktisch, dass zusaetzliche SMBs wieder erlaubt waren. "Basal
     * zurueckhalten hilft nicht mehr" und "mehr Bolus ist sicher" sind aber
     * zwei vollstaendig verschiedene Aussagen.
     *
     * DIESE FUNKTION BEANTWORTET NUR DIE ERSTE: ist eine gemessene
     * Abwaertsgefahr da? Der Basalnutzen bleibt [evaluate] vorbehalten und
     * entscheidet AUSSCHLIESSLICH ueber die TBR.
     *
     * DAS MODELL SPIELT HIER KEINE ROLLE. Es darf weiter extrem negative Werte
     * liefern; entscheidend ist ausschliesslich der gemessene Verlauf.
     */
    data class DescentRisk(
        val active: Boolean,
        /** Warum KEIN Risiko - dieselben Gruende wie in [Result]. */
        val denial: String?,
        val fallRatePerMin: Double?,
        val bolusIobU: Double?,
        /** Wie weit der Bolus ueber die Strecke zum Boden hinausreicht
         *  [mg/dl]. Positiv = ueberdeckt. */
        val overcoverageMgdl: Double?,
        val minutesToFloor: Double?,
    )

    /**
     * Schritte 1-3: gesundes Signal, gemessen fallend, Bolus deckt die Strecke
     * zum Boden, und der gemessene Trend erreicht ihn im Nahhorizont.
     *
     * KEIN [measuredLow] hier: das gemessene Tief ist ein eigener, schaerferer
     * Riegel (SAFETY_HOLD) und liegt vor dieser Frage.
     */
    fun measuredDescentRisk(
        signalHealthy: Boolean,
        bgMgdl: Double?,
        fallRatePerMin: Double?,
        bolusIobU: Double?,
        isfMgdlPerU: Double?,
        guardFloorMgdl: Double,
        horizonMin: Double = NEAR_TERM_HORIZON_MIN.toDouble(),
    ): DescentRisk {
        if (!signalHealthy) return DescentRisk(false, DENY_UNHEALTHY, fallRatePerMin, bolusIobU, null, null)
        if (bgMgdl == null || !bgMgdl.isFinite() ||
            fallRatePerMin == null || !fallRatePerMin.isFinite() ||
            isfMgdlPerU == null || !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0
        ) return DescentRisk(false, DENY_INPUT, fallRatePerMin, bolusIobU, null, null)

        val strecke = bgMgdl - guardFloorMgdl
        if (fallRatePerMin >= 0.0)
            return DescentRisk(false, DENY_NOT_FALLING, fallRatePerMin, bolusIobU, null, null)

        val bolus = bolusIobU?.takeIf { it.isFinite() }
            ?: return DescentRisk(false, DENY_NO_OVERCOVERAGE, fallRatePerMin, null, null, null)
        val ueberdeckung = bolus * isfMgdlPerU - strecke
        if (ueberdeckung <= 0.0)
            return DescentRisk(false, DENY_NO_OVERCOVERAGE, fallRatePerMin, bolus, ueberdeckung, null)

        val minutenBisBoden = strecke / abs(fallRatePerMin)
        if (!minutenBisBoden.isFinite() || minutenBisBoden > horizonMin)
            return DescentRisk(
                false, DENY_TOO_FAR, fallRatePerMin, bolus, ueberdeckung,
                minutenBisBoden.takeIf { it.isFinite() },
            )

        return DescentRisk(true, null, fallRatePerMin, bolus, ueberdeckung, minutenBisBoden)
    }

    /**
     * @param measuredLow es liegt JETZT ein gemessenes Tief vor (Observer).
     * @param signalHealthy die Signalreihe ist brauchbar - ohne sie gibt es
     *   keinen positiven Nachweis, und ohne Nachweis keine Null.
     * @param bgMgdl der aktuelle Ankerwert [mg/dl].
     * @param fallRatePerMin GEMESSENE Rate [mg/dl/min], negativ = fallend.
     *   Der schnelle UKF-/Rohtrend, NICHT das traege `r`: `r` ist
     *   BGI-bereinigt und traegt ein 18-min-Fenster, haengt also an jedem
     *   Wendepunkt rund sechs Minuten nach.
     * @param bolusIobU AUSSCHLIESSLICH der Bolus-Anteil [U]. Nie das
     *   Netto-IOB: ein negativer Basal-Anteil (aus einer vorherigen
     *   Zurueckhaltung) wuerde die Ueberdeckung rechnerisch verdecken und
     *   genau dann eine Null verhindern, wenn zuvor schon zu wenig Basal
     *   lief - die Rueckkopplung, die dieses Tor beenden soll.
     * @param isfMgdlPerU Sensitivitaet [mg/dl je U].
     * @param guardFloorMgdl der Boden [mg/dl].
     * @param scheduledBasalUPerH die Profilrate [U/h] - sie bestimmt, wieviel
     *   eine Null ueberhaupt zurueckhalten KANN.
     * @param remainingEffect Anteil der Insulinwirkung, der nach t Minuten
     *   bereits eingetreten ist (0..1). Kommt aus dem Einheitskern des
     *   Zyklus; als Funktion uebergeben, damit diese Klasse kein Modell
     *   kennt und rein testbar bleibt.
     */
    fun evaluate(
        measuredLow: Boolean,
        signalHealthy: Boolean,
        bgMgdl: Double?,
        fallRatePerMin: Double?,
        bolusIobU: Double?,
        isfMgdlPerU: Double?,
        guardFloorMgdl: Double,
        scheduledBasalUPerH: Double,
        remainingEffect: (Double) -> Double,
        minBenefitMgdl: Double = MIN_BENEFIT_MGDL,
        horizonMin: Double = NEAR_TERM_HORIZON_MIN.toDouble(),
    ): Result {
        // Die Wirklichkeit zuerst und ohne jede Rechnung.
        if (measuredLow) return Result(Verdict.MEASURED_LOW, fallRatePerMin, bolusIobU)
        if (!signalHealthy) return Result(Verdict.NONE, fallRatePerMin, bolusIobU, denial = DENY_UNHEALTHY)
        if (bgMgdl == null || !bgMgdl.isFinite() ||
            fallRatePerMin == null || !fallRatePerMin.isFinite() ||
            isfMgdlPerU == null || !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0 ||
            !scheduledBasalUPerH.isFinite() || scheduledBasalUPerH <= 0.0
        ) return Result(Verdict.NONE, fallRatePerMin, bolusIobU, denial = DENY_INPUT)

        val strecke = bgMgdl - guardFloorMgdl

        // SCHRITTE 1-3 SIND DAS GEMESSENE ABWAERTSRISIKO und stehen seit dem
        // 19.08. in [measuredDescentRisk] - EINE Implementierung, hier nur
        // gerufen. Zwei Kopien wuerden auseinanderlaufen, und dann sperrte der
        // Insulinriegel bei einer anderen Lage als die TBR-Antwort.
        val risiko = measuredDescentRisk(
            signalHealthy = true,   // oben schon geprueft
            bgMgdl = bgMgdl,
            fallRatePerMin = fallRatePerMin,
            bolusIobU = bolusIobU,
            isfMgdlPerU = isfMgdlPerU,
            guardFloorMgdl = guardFloorMgdl,
            horizonMin = horizonMin,
        )
        if (!risiko.active) return Result(
            Verdict.NONE, fallRatePerMin, risiko.bolusIobU, strecke,
            risiko.minutesToFloor, denial = risiko.denial,
        )
        val bolus = risiko.bolusIobU
        val minutenBisBoden = risiko.minutesToFloor!!
        // Die beiden GRENZFALL-Messgroessen (v29): wie robust die
        // Ueberdeckung und wie knapp die Horizontkante war. Sie STEUERN
        // nichts - sie machen den 21:58-Grenzfall im Trail beziffbar.
        val ueberdeckungsMarge = (bolus ?: 0.0) * isfMgdlPerU - strecke
        val horizontMarge = horizonMin - minutenBisBoden

        // (4) BRINGT DIE NULL BIS DAHIN UEBERHAUPT ETWAS?
        //     Jede zurueckgehaltene Minute wirkt erst ab ihrem eigenen
        //     Zeitpunkt - deshalb integriert und nicht "Rate mal Zeit".
        val nutzen = nutzenMgdl(minutenBisBoden, scheduledBasalUPerH, isfMgdlPerU, remainingEffect)
        if (nutzen < minBenefitMgdl)
            return Result(
                Verdict.NONE, fallRatePerMin, bolus, strecke, minutenBisBoden, nutzen,
                overcoverageMarginMgdl = ueberdeckungsMarge, horizonMarginMin = horizontMarge,
                denial = DENY_NO_BENEFIT,
            )

        return Result(
            Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE,
            fallRatePerMin, bolus, strecke, minutenBisBoden, nutzen,
            overcoverageMarginMgdl = ueberdeckungsMarge, horizonMarginMin = horizontMarge,
        )
    }

    /**
     * Was eine ab jetzt laufende Null bis [horizontMin] an Absenkung
     * VERHINDERT [mg/dl].
     *
     * Die Minute, die gerade nicht flieszt, hat bis zum Bodenkontakt noch
     * fast die volle Zeit zu wirken; die letzte Minute davor praktisch keine.
     * Genau diese Verteilung ist der Grund, warum eine Null kurz vor dem Tief
     * wirkungslos ist - "Rate mal Zeit" wuerde das um eine Groessenordnung
     * ueberschaetzen.
     */
    fun nutzenMgdl(
        horizontMin: Double,
        scheduledBasalUPerH: Double,
        isfMgdlPerU: Double,
        remainingEffect: (Double) -> Double,
    ): Double {
        if (!horizontMin.isFinite() || horizontMin <= 0.0) return 0.0
        val jeMinute = scheduledBasalUPerH / 60.0
        var wirksam = 0.0
        var m = 0
        while (m < horizontMin.toInt()) {
            wirksam += jeMinute * remainingEffect(horizontMin - m)
            m++
        }
        return wirksam * isfMgdlPerU
    }
}
