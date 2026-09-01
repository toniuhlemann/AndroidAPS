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
    enum class Intent {
        SAFETY_ZERO,
        /**
         * STUFENWEISE BASALRUECKKEHR: eine ABSENKENDE Rate zwischen 0 und
         * dem Profilbasal, waehrend die Schutz-Null verriegelt bleibt.
         *
         * Ausdruecklich KEINE positive TBR: die Rate liegt immer unter dem
         * Profilbasal, gibt also weniger als der Normalzustand. Sie holt
         * nichts nach und traegt kein Budget - sie mildert nur die binaere
         * Null.
         */
        PARTIAL_BASAL,
        NO_POSITIVE,
        KEEP,
    }

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
        /**
         * Darf eine laufende Null enden, sobald ihr SCHUTZGRUND im aktuellen
         * Zyklus nachweislich weg ist? Default AUS = Verhalten wie vor dem
         * 15.08., bitgleich (s. TbrPolicyV4bParityTest). Der Runner setzt ihn
         * aus der Preference.
         */
        val endZeroWhenReasonGone: Boolean = false,
        /**
         * Wieviele erfolglose Abbruchversuche in Folge, bevor die Null stehen
         * bleibt. Medtrum-Auflage: ohne Deckel wiederholt FUSE das Kommando
         * minuetlich, solange die Pumpe es nicht annimmt.
         */
        val endZeroMaxAttempts: Int = 3,
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

    /**
     * WARUM die Menge geblockt wird - typisiert, nicht als Bit (11.08.).
     *
     * Das eine Bit `smbBlocked` trug SECHS verschiedene Gruende, und der
     * Aufrufer konnte sie nicht auseinanderhalten. Fuer die manuelle
     * Insulin-Autorisierung ist genau das noetig: ein Schutz-Null darf den
     * markerfinanzierten Anteil durchlassen, eine belegte Pumpe oder ein
     * Kernfehler NIEMALS. `smbBlocked = false` waere deshalb die falsche
     * Loesung gewesen - sie haette alle sechs auf einmal geoeffnet.
     *
     * Und ausdruecklich NICHT ueber Grundtexte ableitbar: `reason` ist fuer
     * Menschen, die Herkunft einer Menge muss typisiert sein.
     */
    enum class SmbBlockCause {
        NONE,
        /** Schutz-Null wegen Tief/Sicherheitsbahn - der EINZIGE Grund, den
         *  eine ausdrueckliche manuelle Autorisierung ueberstimmen darf. */
        SAFETY_ZERO,
        /**
         * Teilbasal-Rueckkehr laeuft - waehrenddessen ist die SMB-Achse
         * gesperrt. Das ist nicht nur Vorsicht: die C7a-Pruefung in
         * FuseTbrTranslator verwirft eine anhebende TBR-Anforderung genau
         * dann, wenn im selben Zyklus ein SMB positiv ist. Ohne diese
         * Sperre verschwaende die Teilbasal-Anforderung still.
         */
        PARTIAL_RECOVERY,
        PUMP_BUSY,
        INVALID_INPUT,
        SAFETY_SNAPSHOT_MISSING,
        FAKE_EXTENDED,
        FAULT,
    }

    data class Decision(
        val outcome: Outcome,
        val reason: String,
        val alarm: Boolean,
        /** v0.3.1 C8: Kann FUSE die laufende Abgabe nicht stoppen, darf es nicht
         *  gleichzeitig zusaetzliches Insulin geben. */
        val smbBlockCause: SmbBlockCause,
    ) {
        /** ABGELEITET, damit die bestehenden Leser unveraendert bleiben und
         *  Bit und Grund nicht auseinanderlaufen koennen. */
        val smbBlocked: Boolean get() = smbBlockCause != SmbBlockCause.NONE
    }

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

    /**
     * HARTE Obergrenze der Null-Toleranz [U/h] (C7b, Codex-Adjudication D/C7).
     *
     * `basalStepUPerH / 2` allein ist KEINE Null-Toleranz, sondern eine
     * Vorzeichen-Toleranz: sie skaliert mit dem Pumpenraster. Auf einer Pumpe
     * mit grobem Basalschritt (0,5 U/h) galten damit 0,20 U/h als "Null" - und
     * eine FREMDE Absenkung wurde ueber [Intent.KEEP] abgebrochen. Ein Abbruch
     * einer Absenkung ist eine Insulin-ERHOEHUNG durch Nichtstun.
     *
     * Die Toleranz existiert gegen Double-Rauschen, nicht gegen Rasterbreite;
     * deshalb zusaetzlich absolut gedeckelt. Einseitig: die Toleranz kann nur
     * kleiner werden, also wird seltener etwas faelschlich als Null gelesen.
     */
    const val ZERO_RATE_TOL_MAX_UPERH = 0.025

    fun isZeroRate(absoluteRateUPerH: Double, basalStepUPerH: Double): Boolean =
        abs(absoluteRateUPerH) <= minOf(basalStepUPerH / 2.0, ZERO_RATE_TOL_MAX_UPERH)

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
        /**
         * Ist der SCHUTZGRUND einer laufenden Null in DIESEM Zyklus
         * nachweislich weg? Die Tabelle kann das nicht selbst wissen - sie
         * sieht weder Bahn noch Guardboden noch Ledger. Der Nachweis wird vom
         * Aufrufer gefuehrt (s. FuseTbrTranslator.reasonGone).
         */
        protectionCleared: Boolean = false,
        /**
         * Die fertige Teilbasal-RATE [U/h] fuer [Intent.PARTIAL_BASAL].
         *
         * Sie kommt aus `BasalRecoverySearch` - der groessten Rate, die
         * die Schutzbahn noch traegt -, NICHT aus einem festen Anteil. Ein
         * Prozentsatz weiss nichts ueber die Bahn; diese Rate ist gegen
         * genau den Guard geprueft, der die Null ausgeloest hat.
         * 0 = keine Teilstufe.
         */
        partialRateUPerH: Double = 0.0,
        /** Bisher erfolglose Abbruchversuche in Folge (Backoff). */
        endZeroAttempts: Int = 0,
        /**
         * IST DIE LAGE UNSICHER, unabhaengig vom [Intent]?
         *
         * Bis zum 17.08. trug [Intent.SAFETY_ZERO] beides zugleich: "die Lage
         * ist unsicher" UND "deshalb wird das Basal genullt". Seit das
         * Fundament auch in unsicherer Lage stehen bleibt (LowThreatGate),
         * sind das zwei verschiedene Aussagen - und die C8-Sperre haengt an
         * der ERSTEN. Ohne diesen Parameter gab ein Guard-Zyklus unter
         * FAKE_EXTENDED wieder Insulin frei (im Verdrahtungstest gemessen:
         * 0,1 U statt 0), weil der Intent nicht mehr SAFETY_ZERO hiess.
         *
         * C8: kann FUSE die laufende Abgabe nicht stoppen, darf es nicht
         * gleichzeitig zusaetzliches Insulin geben.
         */
        unsafeSituation: Boolean = false,
    ): Decision {
        val base = decideIgnoringPump(intent, current, scheduledBasalUPerH, cfg, fault, protectionCleared, partialRateUPerH, endZeroAttempts, unsafeSituation)
        if (!pumpBusy) return base
        // Eine arbeitende Pumpe bekommt keine zweite Anweisung — aber der
        // Safety-Grund und sein Alarm bleiben erhalten. Unterdrueckt wird die
        // ANFORDERUNG, nicht die Erkenntnis.
        return base.copy(
            outcome = if (base.outcome is Outcome.Request) Outcome.NoRequest else base.outcome,
            reason = "PUMP_BUSY|${base.reason}",
            // Eine belegte Pumpe schlaegt jeden Basisgrund - sie ist nie
            // ueberstimmbar, auch nicht durch eine manuelle Autorisierung.
            smbBlockCause = SmbBlockCause.PUMP_BUSY,
        )
    }

    private fun decideIgnoringPump(
        intent: Intent,
        current: Current?,
        scheduledBasalUPerH: Double,
        cfg: Config,
        fault: FaultCode,
        protectionCleared: Boolean = false,
        partialRateUPerH: Double = 0.0,
        endZeroAttempts: Int = 0,
        unsafeSituation: Boolean = false,
    ): Decision {
        // Ungueltige Eingaben werden nicht geworfen, sondern fail-closed
        // beantwortet: eine Ausnahme aus dem Regelpfad landet sonst im Loop.
        val invalid = cfg.violation() ?: current?.violation()
            ?: if (!scheduledBasalUPerH.isFinite() || scheduledBasalUPerH < 0.0) "scheduledBasal=$scheduledBasalUPerH" else null
        if (invalid != null)
            return Decision(Outcome.NoRequest, "INVALID_INPUT|$invalid", alarm = true, smbBlockCause = SmbBlockCause.INVALID_INPUT)

        // Ohne frischen Safety-Snapshot wird GAR NICHTS angefordert — auch kein
        // Abbruch, dessen Wirkung ohne Zustandskenntnis unbekannt waere.
        if (fault == FaultCode.SAFETY_SNAPSHOT_MISSING)
            return Decision(
                Outcome.NoRequest, FaultCode.SAFETY_SNAPSHOT_MISSING.name,
                alarm = true, smbBlockCause = SmbBlockCause.SAFETY_SNAPSHOT_MISSING,
            )

        // Ein Kern-/Eingangsfehler kann keine Kategorie mehr begruenden: es
        // bleibt "nichts Positives" (v0.3 §12).
        val effective = if (fault == FaultCode.CORE_INPUT_INVALID) Intent.NO_POSITIVE else intent
        // Die Unsicherheit kommt aus ZWEI Quellen: der eigenen Absicht
        // (Schutz-Null) und dem Befund des Aufrufers - Guard und Schwanz
        // lassen seit dem 17.08. das Basal stehen und beschreiben trotzdem
        // eine unsichere Lage.
        val unsafe = effective == Intent.SAFETY_ZERO || unsafeSituation
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
                smbBlockCause = when {
                    unsafe            -> SmbBlockCause.FAKE_EXTENDED
                    smbBlockedByFault -> SmbBlockCause.FAULT
                    else              -> SmbBlockCause.NONE
                },
            )

        val base = when (effective) {
            Intent.SAFETY_ZERO -> safetyZero(current, cfg)
            // Nur auf FEHLERFREIEM Pfad: CORE_INPUT_INVALID erzeugt
            // NO_POSITIVE aus einem FEHLER heraus, TEMP_BASAL_FALLBACK
            // heisst, die laufende TBR kam aus einer Ersatzquelle - in
            // beiden Faellen ist "der Grund ist nachweislich weg" gerade
            // NICHT nachgewiesen.
            Intent.NO_POSITIVE -> noPositive(
                current, scheduledBasalUPerH, cfg,
                protectionCleared = protectionCleared && fault == FaultCode.NONE,
                endZeroAttempts = endZeroAttempts,
            )
            Intent.PARTIAL_BASAL -> partialBasal(current, scheduledBasalUPerH, cfg, partialRateUPerH)
            Intent.KEEP        -> keep(current, scheduledBasalUPerH, cfg)
        }
        return if (fault == FaultCode.NONE) base
        else base.copy(reason = "${fault.name}|${base.reason}", smbBlockCause = SmbBlockCause.FAULT)
    }

    /**
     * KEEP heisst "der Regler dosiert" — und dann darf keine eigene
     * Sicherheits-Null mehr laufen. Bis zur v0.3.1 gab es dafuer KEINEN Pfad:
     * `noPositive` behaelt eine nicht-positive TBR absichtlich, und KEEP
     * forderte gar nichts an. Auf dem Geraet hat FUSE deshalb am 06.08. um
     * 13:01 eine 30-min-Null aus einem FALSCHEN GUARD_FLOOR gesetzt, ab 13:14
     * wieder SMBs gegeben und die Null trotzdem bis 13:31 laufen lassen: Basal
     * aus und schneller Kanal offen, gleichzeitig.
     *
     * Das ist ein Widerspruch in sich. Entweder ist die Lage unsicher, dann
     * kein SMB — oder sie ist sicher, dann kein Basalstopp. Die Umkehrung von
     * C8 ("kann FUSE nicht stoppen, darf es nicht geben") in die andere
     * Richtung.
     *
     * C7b (Codex-Adjudication D/C7): der Abbruch greift NUR noch bei
     *
     *   1. einer ECHTEN Null (harte absolute Toleranz, s. [isZeroRate]) —
     *      die setzt in Alpha 1 ausschliesslich FUSE selbst, und
     *   2. einer Rate UEBER dem Profilbasal — sie zu beenden SENKT Insulin.
     *
     * Eine FREMDE Absenkung (z.B. 30 % Basal aus einer Automation) bleibt
     * unangetastet: sie wirkt weiter in die sichere Richtung, und ihr Abbruch
     * waere eine Insulin-Erhoehung durch Nichtstun. Vorher entschied das
     * allein `abs(rate) <= basalStep/2` — auf grobem Raster hat das eine
     * laufende Absenkung als "Null" gelesen.
     *
     * ZUSAETZLICH gilt oberhalb, im FuseTbrTranslator (Plugin-Modul, deshalb
     * hier kein Verweis), das C7a-Zertifikat:
     * faellt in DEMSELBEN Zyklus ein positiver SMB an, bleibt die
     * Zurueckhaltung stehen und der Abbruch entfaellt.
     */
    private fun keep(current: Current?, scheduledBasalUPerH: Double, cfg: Config): Decision {
        val keep = Decision(Outcome.NoRequest, "KEEP", alarm = false, smbBlockCause = SmbBlockCause.NONE)
        if (current == null) return keep
        fun cancel(reason: String) = Decision(Outcome.Request(0.0, 0), reason, alarm = false, smbBlockCause = SmbBlockCause.NONE)
        if (isZeroRate(current.absoluteRateUPerH, cfg.basalStepUPerH)) return cancel(KEEP_CANCEL_STALE_ZERO_REASON)
        // Ueber Profilbasal: derselbe Abbruch wie unter NO_POSITIVE. Waehrend
        // FUSE dosiert, ist eine laufende positive TBR eine zweite, ungeprueft
        // mitlaufende Insulinquelle (H3: die Aktion ist SMB PLUS TBR).
        if (classify(current.absoluteRateUPerH, scheduledBasalUPerH, cfg.basalStepUPerH) == Direction.POSITIVE)
            return cancel("KEEP_CANCEL_POSITIVE")
        return keep
    }

    /**
     * DIE TEILBASAL-RATE - abgesenkt, nie angehoben.
     *
     * Die Rate kommt FERTIG aus `BasalRecoverySearch` - der groessten
     * Rate, die die Schutzbahn noch traegt. Hier wird sie nur noch auf den
     * Pumpen-Basalschritt NACH UNTEN gerastert (was die Pumpe nicht
     * darstellen kann, wird nicht behauptet) und hart am Profilbasal
     * gedeckelt; beides ist Verteidigung in der Tiefe, die Suche tut es
     * bereits. Eine unbrauchbare Vorgabe oder ein unbrauchbares Profil
     * ergibt die Schutz-Null - fail-closed in die Richtung, die WENIGER
     * Insulin gibt.
     *
     * KEIN KOMMANDO-SPAM: laeuft die Zielrate schon und reicht die
     * Restdauer, wird nichts gesendet; erneuert wird nur vor dem Ablauf -
     * dieselbe Regel wie bei der Schutz-Null.
     */
    private fun partialBasal(
        current: Current?,
        scheduledBasalUPerH: Double,
        cfg: Config,
        vorgabeUPerH: Double,
    ): Decision {
        val ursache = SmbBlockCause.PARTIAL_RECOVERY
        if (!vorgabeUPerH.isFinite() || vorgabeUPerH <= 0.0 ||
            !scheduledBasalUPerH.isFinite() || scheduledBasalUPerH <= 0.0
        ) return safetyZero(current, cfg).let { it.copy(reason = "PARTIAL_INVALID_INPUT|" + it.reason) }
        // Die Suche rastert bereits; die Wiederholung hier ist
        // Verteidigung in der Tiefe, und die Klemme am Profilbasal ist die
        // harte Zusage, dass nie mehr als der Normalzustand laeuft.
        val schritt = cfg.basalStepUPerH
        val gerastert =
            if (schritt > 0.0) kotlin.math.floor(vorgabeUPerH / schritt + 1e-9) * schritt else vorgabeUPerH
        val rate = gerastert.coerceIn(0.0, scheduledBasalUPerH)
        // Rastert der Anteil auf 0 herunter, ist die Teilstufe nicht
        // darstellbar - dann gilt weiter die Schutz-Null.
        if (isZeroRate(rate, schritt))
            return safetyZero(current, cfg).let { it.copy(reason = "PARTIAL_BELOW_STEP|" + it.reason) }
        val req = Outcome.Request(rate, cfg.defaultDurationMin)
        if (current == null) return Decision(req, "PARTIAL_NEW", alarm = false, smbBlockCause = ursache)
        val laeuftSchon = abs(current.absoluteRateUPerH - rate) <= schritt / 2.0
        if (laeuftSchon)
            return if (current.remainingMin >= cfg.tbrRenewMinRemainingMin)
                Decision(Outcome.NoRequest, "PARTIAL_ALREADY_RUNNING", alarm = false, smbBlockCause = ursache)
            else Decision(req, "PARTIAL_RENEW", alarm = false, smbBlockCause = ursache)
        return Decision(req, "PARTIAL_SET", alarm = false, smbBlockCause = ursache)
    }

    private fun safetyZero(current: Current?, cfg: Config): Decision {
        val zero = Outcome.Request(0.0, cfg.defaultDurationMin)
        if (current == null) return Decision(zero, "SAFETY_ZERO_NEW", alarm = false, smbBlockCause = SmbBlockCause.SAFETY_ZERO)
        if (isZeroRate(current.absoluteRateUPerH, cfg.basalStepUPerH)) {
            // Eine laufende Null wird nicht minuetlich neu gesetzt — erst wenn
            // sie auszulaufen droht.
            return if (current.remainingMin >= cfg.tbrRenewMinRemainingMin)
                Decision(Outcome.NoRequest, "SAFETY_ZERO_ALREADY_RUNNING", alarm = false, smbBlockCause = SmbBlockCause.SAFETY_ZERO)
            else Decision(zero, "SAFETY_ZERO_RENEW", alarm = false, smbBlockCause = SmbBlockCause.SAFETY_ZERO)
        }
        // Auch eine bereits absenkende, aber nicht nullende Rate wird SOFORT
        // auf 0 gezogen — nicht auslaufen gelassen.
        return Decision(zero, "SAFETY_ZERO_REPLACE", alarm = false, smbBlockCause = SmbBlockCause.SAFETY_ZERO)
    }

    /**
     * DER GRUND IST WEG - Trail-Kennung, nach der man suchen kann. Sie muss
     * sich vom Ablauf einer Null (gar keine Zeile) und vom KEEP-Pfad
     * ([KEEP_CANCEL_STALE_ZERO_REASON]) unterscheiden.
     */
    const val END_ZERO_REASON = "NO_POSITIVE_END_ZERO_REASON_GONE"

    const val KEEP_CANCEL_STALE_ZERO_REASON = "KEEP_CANCEL_STALE_ZERO"

    /** Der Cancel wurde unterdrueckt, weil er in diesem Fenster schon zu oft
     *  scheiterte - s. [Config.endZeroMaxAttempts]. */
    const val END_ZERO_BACKOFF_REASON = "NO_POSITIVE_END_ZERO_BACKOFF"

    private fun noPositive(
        current: Current?,
        scheduledBasalUPerH: Double,
        cfg: Config,
        protectionCleared: Boolean = false,
        endZeroAttempts: Int = 0,
    ): Decision {
        if (current == null) return Decision(Outcome.NoRequest, "NO_POSITIVE_NOTHING_RUNNING", alarm = false, smbBlockCause = SmbBlockCause.NONE)
        val dir = classify(current.absoluteRateUPerH, scheduledBasalUPerH, cfg.basalStepUPerH)
        if (dir == Direction.POSITIVE)
        // Cancel: rate 0, duration 0 — eindeutig, kein "zurueck auf Profilbasal
        // per absoluter Rate".
            return Decision(Outcome.Request(0.0, 0), "NO_POSITIVE_CANCEL", alarm = false, smbBlockCause = SmbBlockCause.NONE)

        // ---- DIE NULL VERLASSEN, SOBALD IHR GRUND WEG IST (Toni 15.08.) ----
        //
        // Der Anlass ist gemessen, nicht theoretisch: eine Schutz-Null
        // ueberdauerte ihren Grund im 4-Tage-Trail rund 100 Minuten je Nacht.
        // Der einzige aktive Ausgang war bis dahin KEEP_CANCEL_STALE_ZERO, und
        // der braucht Intent.KEEP - also einen Zyklus, der bis
        // BELOW_PUMP_INCREMENT durchlaeuft. Hinter Guard, Schwanz oder Totband
        // entsteht der nie: 497 gesetzte Nullen standen 43 Abbruechen
        // gegenueber, nachts 147 zu 6. Das zurueckgehaltene Basal finanziert
        // dann ueber die Bedarfsseite die Morgen-SMBs (Brief D.1/D.4).
        //
        // DREI UND-BEDINGUNGEN, keine weglassbar:
        //  1. der Schalter ist an,
        //  2. der Aufrufer hat den Wegfall des Schutzgrundes fuer DIESEN
        //     Zyklus nachgewiesen (Guard ueber Boden, kein SafetyHold, kein
        //     Tail-Block, Health READY, kein Ledger-Hold, kein Fehlerpfad,
        //     kein Rebound-Fenster - s. FuseTbrTranslator.reasonGone),
        //  3. es laeuft eine ECHTE Null (harte Toleranz, [isZeroRate]) -
        //     NICHT irgendeine Absenkung. Das ist derselbe C7b-Vertrag wie im
        //     KEEP-Pfad: eine FREMDE Absenkung wirkt in die sichere Richtung,
        //     und sie abzubrechen waere eine Insulin-ERHOEHUNG durch Nichtstun.
        //
        // WAS HIER NICHT ENTSTEHEN KANN: eine positive Rate. Der einzige
        // Ausgang ist Request(0.0, 0) - Abbruch, also Rueckfall auf
        // Profilbasal. Mehr als Profilbasal gibt dieser Pfad nie frei.
        if (cfg.endZeroWhenReasonGone && protectionCleared &&
            isZeroRate(current.absoluteRateUPerH, cfg.basalStepUPerH)
        ) {
            // MEDTRUM-AUFLAGE (Replay 15.08.): scheitert das Kommando, wuerde
            // FUSE es sonst in JEDEM Folgezyklus wiederholen - im Rig 26 Mal
            // in 30 Minuten, auf einer echten Pumpe neuer Funkverkehr genau
            // dann, wenn sie ohnehin zickt. Nach [Config.endZeroMaxAttempts]
            // erfolglosen Versuchen bleibt die Null stehen und laeuft ab; die
            // Fehlerrichtung ist damit die alte (zu wenig Basal), nicht eine
            // neue.
            if (endZeroAttempts >= cfg.endZeroMaxAttempts)
                return Decision(Outcome.NoRequest, END_ZERO_BACKOFF_REASON, alarm = false, smbBlockCause = SmbBlockCause.NONE)
            return Decision(Outcome.Request(0.0, 0), END_ZERO_REASON, alarm = false, smbBlockCause = SmbBlockCause.NONE)
        }

        return Decision(Outcome.NoRequest, "NO_POSITIVE_KEEP_NON_POSITIVE", alarm = false, smbBlockCause = SmbBlockCause.NONE)
    }
}
