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

    /** Mindestwirkung, ab der eine Null als nuetzlich gilt [mg/dl]. Unterhalb
     *  des Sensorrauschens (~5 mg/dl) waere die Wirkung nicht einmal messbar -
     *  eine Massnahme, deren Erfolg man nicht sehen kann, ist keine. */
    const val MIN_BENEFIT_MGDL = 5.0

    /** Kurzfristfenster der Richtungsprobe [min]. Bewusst begrenzt: die Frage
     *  ist "faellt es JETZT Richtung Boden", nicht "wo liegt das Minimum einer
     *  Zweistundenbahn". */
    const val NEAR_TERM_HORIZON_MIN = 120

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
    ): Verdict {
        // Die Wirklichkeit zuerst und ohne jede Rechnung.
        if (measuredLow) return Verdict.MEASURED_LOW
        if (!signalHealthy) return Verdict.NONE
        if (bgMgdl == null || !bgMgdl.isFinite()) return Verdict.NONE
        if (fallRatePerMin == null || !fallRatePerMin.isFinite()) return Verdict.NONE
        if (isfMgdlPerU == null || !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0) return Verdict.NONE
        if (!scheduledBasalUPerH.isFinite() || scheduledBasalUPerH <= 0.0) return Verdict.NONE

        // (1) FAELLT ES GEMESSEN? Ein flacher oder steigender Verlauf traegt
        //     keine Zero-TBR, egal was eine Bahn behauptet.
        if (fallRatePerMin >= 0.0) return Verdict.NONE

        // (2) DECKT DER BOLUS MEHR ALS DIE STRECKE ZUM BODEN?
        //     Nur der Bolusanteil - s. KDoc zu [bolusIobU]. `null` heisst
        //     unbekannt und ist kein Nachweis.
        val bolus = bolusIobU?.takeIf { it.isFinite() } ?: return Verdict.NONE
        val strecke = bgMgdl - guardFloorMgdl
        if (bolus * isfMgdlPerU <= strecke) return Verdict.NONE

        // (3) FUEHRT DER GEMESSENE TREND KURZFRISTIG ZUM BODEN?
        //     Lineare Fortschreibung der GEMESSENEN Rate - keine Modellbahn,
        //     kein Antriebszerfall, keine Verstaerkung. Liegt der Bodenkontakt
        //     jenseits des Fensters, ist es keine nahe Gefahr.
        val minutenBisBoden = strecke / abs(fallRatePerMin)
        if (!minutenBisBoden.isFinite() || minutenBisBoden > horizonMin) return Verdict.NONE

        // (4) BRINGT DIE NULL BIS DAHIN UEBERHAUPT ETWAS?
        //     Jede zurueckgehaltene Minute wirkt erst ab ihrem eigenen
        //     Zeitpunkt - deshalb integriert und nicht "Rate mal Zeit".
        if (nutzenMgdl(minutenBisBoden, scheduledBasalUPerH, isfMgdlPerU, remainingEffect) < minBenefitMgdl)
            return Verdict.NONE

        return Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE
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
