package app.aaps.fuse.core.controller

import kotlin.math.abs

/**
 * TBR-Tabelle als reine Funktion (K2-C v0.2 §6/§7, v0.3 §7, v0.3.1 C8).
 *
 * Zwei Dinge, die v0.1 vermengt hatte und die getrennt bleiben muessen:
 *
 *   SAFETY_ZERO   LOW oder unsichere Guardbahn   -> echte 0-U/h-TBR
 *   NO_POSITIVE   kein Bedarf, Cap, Health       -> positive TBR abbrechen,
 *                                                   negative/zero BEHALTEN
 *
 * "Kein zusaetzlicher Bedarf" heisst nicht, dass das Profilbasal 30 Minuten
 * gestoppt gehoert. Ein blindes Zero-Temp bei sicherer Bahn ist eine eigene
 * Fehldosis, nur mit umgekehrtem Vorzeichen.
 *
 * ABBRECHEN wird als `rate = 0.0, duration = 0` kodiert — diese Kombination
 * erkennt der Loop unmittelbar als Cancel. Die v0.1-Variante (30-min-TBR auf
 * `scheduledBasalUPerH`) verliess sich auf die Naehe zu `pump.baseBasalRate`
 * und war als "Cancel" nicht eindeutig.
 *
 * Alpha 1 kennt KEINE positive FUSE-TBR: der schnelle Kanal ist der 1-min-SMB.
 */
object TbrPolicy {

    /**
     * NUR die Safety-Kategorie. `PUMP_BUSY` stand hier frueher mit drin und
     * ERSETZTE damit `SAFETY_ZERO` — der Zustand "Guard unsicher, aber Pumpe
     * gerade beschaeftigt" war nicht darstellbar, und mit ihm verschwand der
     * Alarmgrund (R79-F6). Ausfuehrbarkeit ist eine eigene Achse.
     */
    enum class Intent { SAFETY_ZERO, NO_POSITIVE, KEEP }

    /**
     * Fehlerachse, GETRENNT von der TBR-Kategorie (v0.3 §12).
     *
     * `SAFETY_SNAPSHOT_MISSING` und `CORE_INPUT_INVALID` duerfen nie zu einem
     * "Fehler" verschmelzen: der eine verbietet jede Anforderung, der andere
     * verlangt genau eine (den Abbruch einer positiven TBR). Ohne frischen
     * Snapshot ist das Verhalten ausserdem fail-SILENT, nicht fail-safe — eine
     * laufende positive TBR laeuft weiter. Fuer VPUMP akzeptiert; vor jeder
     * Realpumpenfreigabe ist das eine eigene Entscheidung.
     */
    enum class FaultCode { NONE, CORE_INPUT_INVALID, SAFETY_SNAPSHOT_MISSING, TEMP_BASAL_FALLBACK }

    /** Ein als TBR konvertierter Extended Bolus traegt Haftung, die FUSE nicht
     *  stoppen kann — er wird deshalb NUR GELESEN. */
    enum class SourceType { TEMP_BASAL, FAKE_EXTENDED }

    /**
     * Normalisierte laufende TBR. `absoluteRateUPerH` ist bereits ueber
     * `convertedToAbsolute(now, profile)` gegangen: bei `isAbsolute = false`
     * ist die AAPS-Rate ein PROZENTWERT, und der Kern darf nie beides in
     * derselben Zahl sehen.
     */
    data class Current(
        val absoluteRateUPerH: Double,
        val remainingMin: Int,
        val sourceType: SourceType,
    ) {

        fun violation(): String? = when {
            !absoluteRateUPerH.isFinite() -> "rate=$absoluteRateUPerH"
            absoluteRateUPerH < 0.0       -> "rate negative"
            remainingMin < 0              -> "remainingMin=$remainingMin"
            else                          -> null
        }
    }

    data class Config(
        val basalStepUPerH: Double,
        val tbrRenewMinRemainingMin: Int = 10,
        val defaultDurationMin: Int = 30,
    ) {

        fun violation(): String? = when {
            !basalStepUPerH.isFinite() || basalStepUPerH <= 0.0 -> "basalStep=$basalStepUPerH"
            tbrRenewMinRemainingMin < 0                         -> "renewMin=$tbrRenewMinRemainingMin"
            defaultDurationMin <= 0                             -> "duration=$defaultDurationMin"
            else                                                -> null
        }
    }

    enum class Direction { POSITIVE, NEUTRAL, NEGATIVE }

    sealed interface Outcome {

        /** Nichts anfordern — eine bestehende TBR laeuft weiter. NICHT Rate 0. */
        data object NoRequest : Outcome

        data class Request(val rateUPerH: Double, val durationMin: Int) : Outcome

        /** FAKE_EXTENDED: weder ersetzen noch abbrechen. */
        data object ReadOnlyHold : Outcome
    }

    data class Decision(
        val outcome: Outcome,
        val reason: String,
        val alarm: Boolean,
        /** v0.3.1 C8: Kann FUSE die laufende Abgabe nicht stoppen, darf es nicht
         *  gleichzeitig zusaetzliches Insulin geben. */
        val smbBlocked: Boolean,
    )

    /** Vorzeichen gegen das Profilbasal — mit Pumpentoleranz, nie per exaktem
     *  Double-Vergleich. */
    fun classify(absoluteRateUPerH: Double, scheduledBasalUPerH: Double, basalStepUPerH: Double): Direction {
        val tol = basalStepUPerH / 2.0
        val diff = absoluteRateUPerH - scheduledBasalUPerH
        return when {
            diff > tol  -> Direction.POSITIVE
            diff < -tol -> Direction.NEGATIVE
            else        -> Direction.NEUTRAL
        }
    }

    fun isZeroRate(absoluteRateUPerH: Double, basalStepUPerH: Double): Boolean =
        abs(absoluteRateUPerH) <= basalStepUPerH / 2.0

    /**
     * [fault] liegt bewusst NEBEN [intent] und ersetzt ihn nicht: der Fehlerfall
     * ist eine andere Achse als die TBR-Kategorie. Ein `tempBasalFallback`-Lauf
     * etwa sperrt den SMB, laesst die TBR aber normal arbeiten.
     */
    fun decide(
        intent: Intent,
        current: Current?,
        scheduledBasalUPerH: Double,
        cfg: Config,
        fault: FaultCode = FaultCode.NONE,
        pumpBusy: Boolean = false,
    ): Decision {
        val base = decideIgnoringPump(intent, current, scheduledBasalUPerH, cfg, fault)
        if (!pumpBusy) return base
        // Eine arbeitende Pumpe bekommt keine zweite Anweisung — aber der
        // Safety-Grund und sein Alarm bleiben erhalten. Unterdrueckt wird die
        // ANFORDERUNG, nicht die Erkenntnis.
        return base.copy(
            outcome = if (base.outcome is Outcome.Request) Outcome.NoRequest else base.outcome,
            reason = "PUMP_BUSY|${base.reason}",
            smbBlocked = true,
        )
    }

    private fun decideIgnoringPump(
        intent: Intent,
        current: Current?,
        scheduledBasalUPerH: Double,
        cfg: Config,
        fault: FaultCode,
    ): Decision {
        // Ungueltige Eingaben werden nicht geworfen, sondern fail-closed
        // beantwortet: eine Ausnahme aus dem Regelpfad landet sonst im Loop.
        val invalid = cfg.violation() ?: current?.violation()
            ?: if (!scheduledBasalUPerH.isFinite() || scheduledBasalUPerH < 0.0) "scheduledBasal=$scheduledBasalUPerH" else null
        if (invalid != null)
            return Decision(Outcome.NoRequest, "INVALID_INPUT|$invalid", alarm = true, smbBlocked = true)

        // Ohne frischen Safety-Snapshot wird GAR NICHTS angefordert — auch kein
        // Abbruch, dessen Wirkung ohne Zustandskenntnis unbekannt waere.
        if (fault == FaultCode.SAFETY_SNAPSHOT_MISSING)
            return Decision(Outcome.NoRequest, FaultCode.SAFETY_SNAPSHOT_MISSING.name, alarm = true, smbBlocked = true)

        // Ein Kern-/Eingangsfehler kann keine Kategorie mehr begruenden: es
        // bleibt "nichts Positives" (v0.3 §12).
        val effective = if (fault == FaultCode.CORE_INPUT_INVALID) Intent.NO_POSITIVE else intent
        val unsafe = effective == Intent.SAFETY_ZERO
        val smbBlockedByFault = fault != FaultCode.NONE

        if (current?.sourceType == SourceType.FAKE_EXTENDED)
        // Vorrangregel (v0.3 §7): FAKE_EXTENDED wird in Alpha 1 NIE ersetzt
        // oder abgebrochen, auch nicht bei SAFETY_ZERO. Ein stiller Weiterlauf
        // waere das Schlechteste: der Regler koennte nicht eingreifen und
        // wuerde es auch nicht sagen.
            return Decision(
                Outcome.ReadOnlyHold,
                "FAKE_EXTENDED_READ_ONLY",
                alarm = unsafe,
                // C8: Kann FUSE die laufende Abgabe nicht stoppen, darf es nicht
                // gleichzeitig zusaetzliches Insulin geben.
                smbBlocked = unsafe || smbBlockedByFault,
            )

        val base = when (effective) {
            Intent.SAFETY_ZERO -> safetyZero(current, cfg)
            Intent.NO_POSITIVE -> noPositive(current, scheduledBasalUPerH, cfg)
            Intent.KEEP        -> Decision(Outcome.NoRequest, "KEEP", alarm = false, smbBlocked = false)
        }
        return if (fault == FaultCode.NONE) base
        else base.copy(reason = "${fault.name}|${base.reason}", smbBlocked = true)
    }

    private fun safetyZero(current: Current?, cfg: Config): Decision {
        val zero = Outcome.Request(0.0, cfg.defaultDurationMin)
        if (current == null) return Decision(zero, "SAFETY_ZERO_NEW", alarm = false, smbBlocked = true)
        if (isZeroRate(current.absoluteRateUPerH, cfg.basalStepUPerH)) {
            // Eine laufende Null wird nicht minuetlich neu gesetzt — erst wenn
            // sie auszulaufen droht.
            return if (current.remainingMin >= cfg.tbrRenewMinRemainingMin)
                Decision(Outcome.NoRequest, "SAFETY_ZERO_ALREADY_RUNNING", alarm = false, smbBlocked = true)
            else Decision(zero, "SAFETY_ZERO_RENEW", alarm = false, smbBlocked = true)
        }
        // Auch eine bereits absenkende, aber nicht nullende Rate wird SOFORT
        // auf 0 gezogen — nicht auslaufen gelassen.
        return Decision(zero, "SAFETY_ZERO_REPLACE", alarm = false, smbBlocked = true)
    }

    private fun noPositive(current: Current?, scheduledBasalUPerH: Double, cfg: Config): Decision {
        if (current == null) return Decision(Outcome.NoRequest, "NO_POSITIVE_NOTHING_RUNNING", alarm = false, smbBlocked = false)
        val dir = classify(current.absoluteRateUPerH, scheduledBasalUPerH, cfg.basalStepUPerH)
        return if (dir == Direction.POSITIVE)
        // Cancel: rate 0, duration 0 — eindeutig, kein "zurueck auf Profilbasal
        // per absoluter Rate".
            Decision(Outcome.Request(0.0, 0), "NO_POSITIVE_CANCEL", alarm = false, smbBlocked = false)
        else Decision(Outcome.NoRequest, "NO_POSITIVE_KEEP_NON_POSITIVE", alarm = false, smbBlocked = false)
    }
}
