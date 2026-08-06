package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** KC2-01, 02, 03, 11, 12, 13, 16, 26..31, 50, 51, 65. */
class TbrPolicyTest {

    private val cfg = TbrPolicy.Config(basalStepUPerH = 0.05, tbrRenewMinRemainingMin = 10, defaultDurationMin = 30)
    private val scheduled = 0.70

    private fun tbr(rate: Double, remainingMin: Int = 20, type: TbrPolicy.SourceType = TbrPolicy.SourceType.TEMP_BASAL) =
        TbrPolicy.Current(rate, remainingMin, type)

    private fun decide(
        intent: TbrPolicy.Intent,
        current: TbrPolicy.Current?,
        fault: TbrPolicy.FaultCode = TbrPolicy.FaultCode.NONE,
    ) = TbrPolicy.decide(intent, current, scheduled, cfg, fault)

    // ---- NO_POSITIVE -----------------------------------------------------

    @Test
    fun `KC2-01 und KC2-51 eine laufende positive TBR wird abgebrochen`() {
        val d = decide(TbrPolicy.Intent.NO_POSITIVE, tbr(1.50))
        // v0.2 §6 ersetzt die v0.1-Formulierung "Abbruch auf Profilbasal":
        // eine absolute Rate nahe pump.baseBasalRate ist als Cancel nicht
        // eindeutig.
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome)
        assertFalse(d.smbBlocked)
    }

    @Test
    fun `KC2-02 und KC2-51 eine laufende Absenkung bleibt unangetastet`() {
        for (rate in listOf(0.0, 0.20, 0.45)) {
            val d = decide(TbrPolicy.Intent.NO_POSITIVE, tbr(rate))
            assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome, "rate=$rate")
        }
    }

    @Test
    fun `KC2-26 ohne Bedarf gibt es kein blindes Zero-Temp`() {
        assertEquals(TbrPolicy.Outcome.NoRequest, decide(TbrPolicy.Intent.NO_POSITIVE, null).outcome)
        assertEquals(TbrPolicy.Outcome.NoRequest, decide(TbrPolicy.Intent.KEEP, tbr(0.70)).outcome)
    }

    @Test
    fun `KC2-29 Abbrechen ist rate 0 und duration 0, Zero-Temp ist rate 0 mit Dauer`() {
        val cancel = decide(TbrPolicy.Intent.NO_POSITIVE, tbr(1.50)).outcome as TbrPolicy.Outcome.Request
        val zero = decide(TbrPolicy.Intent.SAFETY_ZERO, null).outcome as TbrPolicy.Outcome.Request
        assertEquals(0.0, cancel.rateUPerH)
        assertEquals(0, cancel.durationMin)
        assertEquals(0.0, zero.rateUPerH)
        assertEquals(30, zero.durationMin)
        assertNotEquals(cancel, zero)
    }

    // ---- SAFETY_ZERO -----------------------------------------------------

    @Test
    fun `KC2-27 eine absenkende, aber nicht nullende Rate wird sofort auf null gezogen`() {
        val d = decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(0.30, remainingMin = 25))
        assertEquals(TbrPolicy.Outcome.Request(0.0, 30), d.outcome)
        assertTrue(d.smbBlocked)
    }

    @Test
    fun `KC2-03 und KC2-28 eine laufende Null wird nicht minuetlich neu gesetzt`() {
        assertEquals(TbrPolicy.Outcome.NoRequest, decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(0.0, remainingMin = 20)).outcome)
        assertEquals(TbrPolicy.Outcome.NoRequest, decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(0.0, remainingMin = 10)).outcome)
        // erst wenn sie auszulaufen droht, wird erneuert
        assertEquals(TbrPolicy.Outcome.Request(0.0, 30), decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(0.0, remainingMin = 9)).outcome)
    }

    @Test
    fun `SAFETY_ZERO ohne laufende TBR setzt eine echte Null`() {
        assertEquals(TbrPolicy.Outcome.Request(0.0, 30), decide(TbrPolicy.Intent.SAFETY_ZERO, null).outcome)
    }

    // ---- Normalisierung --------------------------------------------------

    @Test
    fun `KC2-30 das Vorzeichen wird mit Pumpentoleranz bestimmt, nie per exaktem Vergleich`() {
        // 0,70 U/h Profil, basalStep 0,05 -> Toleranz 0,025
        assertEquals(TbrPolicy.Direction.NEUTRAL, TbrPolicy.classify(0.7000000000000001, scheduled, cfg.basalStepUPerH))
        assertEquals(TbrPolicy.Direction.NEUTRAL, TbrPolicy.classify(0.72, scheduled, cfg.basalStepUPerH))
        assertEquals(TbrPolicy.Direction.POSITIVE, TbrPolicy.classify(0.73, scheduled, cfg.basalStepUPerH))
        assertEquals(TbrPolicy.Direction.NEGATIVE, TbrPolicy.classify(0.67, scheduled, cfg.basalStepUPerH))
        assertTrue(TbrPolicy.isZeroRate(0.0, cfg.basalStepUPerH))
        assertFalse(TbrPolicy.isZeroRate(0.10, cfg.basalStepUPerH))

        // Der Kern sieht NUR absolute Raten. Ein durchgereichter Prozentwert
        // (130 % statt 0,91 U/h) waere hier eine gewaltige positive TBR - der
        // Grund, warum die Normalisierung in der Android-Schicht passiert.
        assertEquals(TbrPolicy.Direction.POSITIVE, TbrPolicy.classify(130.0, scheduled, cfg.basalStepUPerH))
        assertEquals(TbrPolicy.Direction.POSITIVE, TbrPolicy.classify(0.91, scheduled, cfg.basalStepUPerH))
    }

    // ---- FAKE_EXTENDED ---------------------------------------------------

    @Test
    fun `KC2-31 ein FAKE_EXTENDED wird gelesen, nie ersetzt`() {
        for (intent in TbrPolicy.Intent.entries) {
            val d = decide(intent, tbr(1.20, type = TbrPolicy.SourceType.FAKE_EXTENDED))
            assertEquals(TbrPolicy.Outcome.ReadOnlyHold, d.outcome, intent.name)
        }
    }

    @Test
    fun `KC2-50 und KC2-65 FAKE_EXTENDED bei unsicherer Bahn - kein SMB, keine TBR, Alarm`() {
        val d = decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(1.20, type = TbrPolicy.SourceType.FAKE_EXTENDED))
        assertEquals(TbrPolicy.Outcome.ReadOnlyHold, d.outcome)
        assertEquals("FAKE_EXTENDED_READ_ONLY", d.reason)
        assertTrue(d.alarm)
        assertTrue(d.smbBlocked)
    }

    @Test
    fun `FAKE_EXTENDED bei sicherer Bahn haelt still, ohne den SMB zu sperren`() {
        val d = decide(TbrPolicy.Intent.KEEP, tbr(1.20, type = TbrPolicy.SourceType.FAKE_EXTENDED))
        assertEquals(TbrPolicy.Outcome.ReadOnlyHold, d.outcome)
        assertFalse(d.alarm)
        assertFalse(d.smbBlocked)
    }

    // ---- Fehlervertrag ---------------------------------------------------

    @Test
    fun `KC2-11 ein Kernfehler mit frischem Snapshot bricht die positive TBR ab`() {
        val d = decide(TbrPolicy.Intent.KEEP, tbr(1.50), fault = TbrPolicy.FaultCode.CORE_INPUT_INVALID)
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome)
        assertTrue(d.smbBlocked)
        assertTrue(d.reason.startsWith("CORE_INPUT_INVALID"))
        // eine laufende Absenkung bleibt auch im Fehlerfall unangetastet
        assertEquals(
            TbrPolicy.Outcome.NoRequest,
            decide(TbrPolicy.Intent.KEEP, tbr(0.20), fault = TbrPolicy.FaultCode.CORE_INPUT_INVALID).outcome
        )
    }

    @Test
    fun `KC2-12 ohne frischen Safety-Snapshot wird gar nichts angefordert`() {
        val d = decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(1.50), fault = TbrPolicy.FaultCode.SAFETY_SNAPSHOT_MISSING)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertTrue(d.alarm)
        assertTrue(d.smbBlocked)
        assertEquals(TbrPolicy.FaultCode.SAFETY_SNAPSHOT_MISSING.name, d.reason)
        // Die beiden Fehlercodes bleiben getrennt.
        assertNotEquals(TbrPolicy.FaultCode.SAFETY_SNAPSHOT_MISSING, TbrPolicy.FaultCode.CORE_INPUT_INVALID)
    }

    @Test
    fun `KC2-13 im tempBasalFallback laeuft die TBR normal, der SMB ist gesperrt`() {
        val d = decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(0.30), fault = TbrPolicy.FaultCode.TEMP_BASAL_FALLBACK)
        assertEquals(TbrPolicy.Outcome.Request(0.0, 30), d.outcome)
        assertTrue(d.smbBlocked)
        assertTrue(d.reason.startsWith("TEMP_BASAL_FALLBACK"))
    }

    // ---- R79-F6: Pumpe beschaeftigt ist eine eigene Achse -----------------

    @Test
    fun `bei arbeitender Pumpe wird nichts angefordert`() {
        for (current in listOf(null, tbr(1.50), tbr(0.0), tbr(0.30))) {
            for (intent in TbrPolicy.Intent.entries) {
                val d = TbrPolicy.decide(intent, current, scheduled, cfg, pumpBusy = true)
                assertTrue(d.outcome !is TbrPolicy.Outcome.Request, "$intent/$current")
                assertTrue(d.smbBlocked, "$intent/$current")
            }
        }
    }

    @Test
    fun `R79-F6 unsichere Bahn bei beschaeftigter Pumpe behaelt Grund und Alarm`() {
        // Frueher war PUMP_BUSY ein Intent und ERSETZTE SAFETY_ZERO - der
        // Zustand "Guard unsicher, aber Pumpe beschaeftigt" war nicht
        // darstellbar, und der Alarmgrund verschwand mit ihm.
        val busy = TbrPolicy.decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(1.50), scheduled, cfg, pumpBusy = true)
        assertEquals(TbrPolicy.Outcome.NoRequest, busy.outcome)
        assertTrue(busy.smbBlocked)
        assertTrue(busy.reason.startsWith("PUMP_BUSY|"))
        assertTrue(busy.reason.contains("SAFETY_ZERO"), busy.reason)

        // dasselbe mit einem nicht stoppbaren FAKE_EXTENDED: der Alarm bleibt
        val fake = TbrPolicy.decide(
            TbrPolicy.Intent.SAFETY_ZERO, tbr(1.20, type = TbrPolicy.SourceType.FAKE_EXTENDED),
            scheduled, cfg, pumpBusy = true
        )
        assertTrue(fake.alarm)
        assertTrue(fake.smbBlocked)
        assertTrue(fake.reason.contains("FAKE_EXTENDED_READ_ONLY"))
    }

    @Test
    fun `R79-F6 ohne beschaeftigte Pumpe bleibt die Entscheidung unveraendert`() {
        val free = TbrPolicy.decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(1.50), scheduled, cfg, pumpBusy = false)
        assertEquals(TbrPolicy.Outcome.Request(0.0, 30), free.outcome)
    }

    // ---- R79-F7: Eingangsvalidierung --------------------------------------

    @Test
    fun `R79-F7 ungueltige Eingaben werden fail-closed beantwortet, nicht geworfen`() {
        val bad = listOf(
            "rate NaN" to TbrPolicy.decide(TbrPolicy.Intent.SAFETY_ZERO, tbr(Double.NaN), scheduled, cfg),
            "rate negativ" to TbrPolicy.decide(TbrPolicy.Intent.NO_POSITIVE, tbr(-1.0), scheduled, cfg),
            "remaining negativ" to TbrPolicy.decide(TbrPolicy.Intent.KEEP, tbr(0.5, remainingMin = -5), scheduled, cfg),
            "basalStep 0" to TbrPolicy.decide(TbrPolicy.Intent.KEEP, tbr(0.5), scheduled, cfg.copy(basalStepUPerH = 0.0)),
            "dauer 0" to TbrPolicy.decide(TbrPolicy.Intent.SAFETY_ZERO, null, scheduled, cfg.copy(defaultDurationMin = 0)),
            "scheduled NaN" to TbrPolicy.decide(TbrPolicy.Intent.NO_POSITIVE, tbr(0.5), Double.NaN, cfg),
        )
        for ((name, d) in bad) {
            assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome, name)
            assertTrue(d.alarm, name)
            assertTrue(d.smbBlocked, name)
            assertTrue(d.reason.startsWith("INVALID_INPUT|"), "$name -> ${d.reason}")
        }
    }

    // ---- iobTH -----------------------------------------------------------

    @Test
    fun `KC2-16 iobTH wird genau einmal aus Prozent umgerechnet und die Formel steht dabei`() {
        assertEquals(3.0, IobThreshold.fromPercent(50.0, 6.0), 1e-12)
        assertEquals(0.0, IobThreshold.fromPercent(0.0, 6.0), 1e-12)
        // die autoISF-Legacyformel haette die 130 % noch einmal aufgeschlagen
        assertNotEquals(50.0 / 100.0 * 1.3 * 6.0, IobThreshold.fromPercent(50.0, 6.0))
        assertTrue(IobThreshold.FORMULA_ID.contains("percent/100*maxIobU"))
        // Bindungsgroesse ist capIob, nicht netIob
        assertEquals(1.0, IobThreshold.headroomU(3.0, capIobU = 2.0), 1e-12)
    }

    // ---- Die eigene Null zuruecknehmen -----------------------------------

    /**
     * GEMESSEN am 06.08.: FUSE setzte 13:01 eine 30-min-Null aus einem falschen
     * GUARD_FLOOR, gab ab 13:14 wieder SMBs und liess die Null bis 13:31
     * laufen. Basal aus und schneller Kanal offen — gleichzeitig.
     */
    @Test
    fun `dosiert der Regler, wird eine laufende Null abgebrochen`() {
        val laufendeNull = TbrPolicy.Current(0.0, 20, TbrPolicy.SourceType.TEMP_BASAL)
        val d = TbrPolicy.decide(TbrPolicy.Intent.KEEP, laufendeNull, 0.8, TbrPolicy.Config(basalStepUPerH = 0.05))
        val r = d.outcome as TbrPolicy.Outcome.Request
        assertEquals(0.0, r.rateUPerH, 0.0)
        assertEquals(0, r.durationMin)          // rate 0 + duration 0 = Abbruch
        assertEquals("KEEP_CANCEL_STALE_ZERO", d.reason)
        assertFalse(d.smbBlocked)
    }

    /** Eine bloss ABGESENKTE TBR bleibt unangetastet — sie kann von woanders
     *  stammen und wirkt weiter in die sichere Richtung. */
    @Test
    fun `eine abgesenkte TBR wird beim Dosieren nicht angetastet`() {
        val abgesenkt = TbrPolicy.Current(0.3, 20, TbrPolicy.SourceType.TEMP_BASAL)
        val d = TbrPolicy.decide(TbrPolicy.Intent.KEEP, abgesenkt, 0.8, TbrPolicy.Config(basalStepUPerH = 0.05))
        assertTrue(d.outcome is TbrPolicy.Outcome.NoRequest)
        assertEquals("KEEP", d.reason)
    }

    /** Ohne laufende TBR bleibt KEEP was es war: nichts anfordern. */
    @Test
    fun `ohne laufende TBR fordert KEEP weiterhin nichts an`() {
        val d = TbrPolicy.decide(TbrPolicy.Intent.KEEP, null, 0.8, TbrPolicy.Config(basalStepUPerH = 0.05))
        assertTrue(d.outcome is TbrPolicy.Outcome.NoRequest)
        assertEquals("KEEP", d.reason)
    }
}
