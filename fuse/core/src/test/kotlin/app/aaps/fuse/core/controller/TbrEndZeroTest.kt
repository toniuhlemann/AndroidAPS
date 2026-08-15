package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE NULL VERLASSEN, SOBALD IHR GRUND WEG IST (Toni 15.08.).
 *
 * Gemessener Anlass: 497 gesetzte Nullen gegen 43 Abbrueche im 4-Tage-Trail
 * (nachts 147 zu 6); die Null ueberdauerte ihren Grund rund 100 min je Nacht,
 * und das zurueckgehaltene Basal finanziert ueber die Bedarfsseite die
 * Morgen-SMBs.
 *
 * Diese Tests halten die vier Zusicherungen fest, an denen die Aenderung
 * haengt: Bitgleichheit bei AUS, Wirkung bei AN, NIE eine positive Rate, und
 * die drei Auflagen aus dem Replay (Rebound, Backoff, fremde Absenkung).
 */
class TbrEndZeroTest {

    private val cfgAus = TbrPolicy.Config(basalStepUPerH = 0.05)
    private val cfgAn = TbrPolicy.Config(basalStepUPerH = 0.05, endZeroWhenReasonGone = true)

    private fun laufendeNull(rest: Int = 24) =
        TbrPolicy.Current(absoluteRateUPerH = 0.0, remainingMin = rest, sourceType = TbrPolicy.SourceType.TEMP_BASAL)

    private fun entscheide(
        cfg: TbrPolicy.Config,
        current: TbrPolicy.Current? = laufendeNull(),
        protectionCleared: Boolean = true,
        endZeroAttempts: Int = 0,
        fault: TbrPolicy.FaultCode = TbrPolicy.FaultCode.NONE,
    ) = TbrPolicy.decide(
        TbrPolicy.Intent.NO_POSITIVE, current, scheduledBasalUPerH = 0.5, cfg = cfg,
        fault = fault, pumpBusy = false,
        protectionCleared = protectionCleared, endZeroAttempts = endZeroAttempts,
    )

    /** DIE TEUERSTE ZUSICHERUNG: aus heisst bitgleich zu vorher. */
    @Test
    fun `mit ausgeschaltetem Schalter bleibt die Null stehen`() {
        val d = entscheide(cfgAus)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals("NO_POSITIVE_KEEP_NON_POSITIVE", d.reason)
    }

    /** Und der Nachweis allein - ohne Schalter - ist folgenlos. */
    @Test
    fun `der Nachweis allein aendert nichts`() {
        for (rest in listOf(0, 5, 10, 29)) {
            val d = entscheide(cfgAus, current = laufendeNull(rest), protectionCleared = true)
            assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome) { "rest=$rest" }
        }
    }

    @Test
    fun `mit Schalter und Nachweis endet die Null sofort`() {
        val d = entscheide(cfgAn)
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome)
        assertEquals(TbrPolicy.END_ZERO_REASON, d.reason)
    }

    /** Ohne Nachweis passiert nichts - der Schalter allein oeffnet nicht. */
    @Test
    fun `ohne Nachweis bleibt die Null stehen`() {
        val d = entscheide(cfgAn, protectionCleared = false)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals("NO_POSITIVE_KEEP_NON_POSITIVE", d.reason)
    }

    /**
     * C7b: eine FREMDE ABSENKUNG bleibt unangetastet. Sie abzubrechen waere
     * eine Insulin-ERHOEHUNG durch Nichtstun - dieselbe Regel wie im
     * KEEP-Pfad, und sie darf hier nicht aufgeweicht werden.
     */
    @Test
    fun `eine fremde Absenkung wird nicht abgebrochen`() {
        val absenkung = TbrPolicy.Current(0.15, 20, TbrPolicy.SourceType.TEMP_BASAL) // 30 % von 0,5
        val d = entscheide(cfgAn, current = absenkung)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals("NO_POSITIVE_KEEP_NON_POSITIVE", d.reason)
    }

    /** NIE eine positive Rate - der einzige neue Ausgang ist der Abbruch. */
    @Test
    fun `der neue Ausgang erzeugt nie eine positive Rate`() {
        for (att in 0..5) for (cleared in listOf(true, false)) {
            val d = entscheide(cfgAn, protectionCleared = cleared, endZeroAttempts = att)
            val out = d.outcome
            if (out is TbrPolicy.Outcome.Request) assertTrue(out.rateUPerH == 0.0) { "rate ${out.rateUPerH}" }
        }
    }

    /**
     * MEDTRUM-AUFLAGE: scheitert der Abbruch, wird er nicht endlos wiederholt.
     * Im Replay waren es 26 Kommandos in 30 Zyklen - auf einer echten Pumpe
     * neuer Funkverkehr genau dann, wenn sie ohnehin zickt.
     */
    @Test
    fun `nach zu vielen Fehlversuchen bleibt die Null stehen`() {
        val deckel = cfgAn.endZeroMaxAttempts
        for (att in 0 until deckel) {
            val d = entscheide(cfgAn, endZeroAttempts = att)
            assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome) { "Versuch $att muss noch gehen" }
        }
        val gestoppt = entscheide(cfgAn, endZeroAttempts = deckel)
        assertEquals(TbrPolicy.Outcome.NoRequest, gestoppt.outcome)
        assertEquals(TbrPolicy.END_ZERO_BACKOFF_REASON, gestoppt.reason)
    }

    /**
     * Auf einem FEHLERPFAD gilt der Nachweis nicht: CORE_INPUT_INVALID
     * erzeugt NO_POSITIVE aus einem Fehler heraus, TEMP_BASAL_FALLBACK
     * heisst, die laufende TBR kam aus einer Ersatzquelle - dann ist
     * "der Grund ist nachweislich weg" gerade nicht nachgewiesen.
     */
    @Test
    fun `auf einem Fehlerpfad endet die Null nicht`() {
        for (f in listOf(TbrPolicy.FaultCode.CORE_INPUT_INVALID, TbrPolicy.FaultCode.TEMP_BASAL_FALLBACK)) {
            val d = entscheide(cfgAn, fault = f)
            assertTrue(d.outcome !is TbrPolicy.Outcome.Request || (d.outcome as TbrPolicy.Outcome.Request).durationMin > 0) {
                "fault=$f ergab ${d.outcome}"
            }
            assertTrue(!d.reason.contains(TbrPolicy.END_ZERO_REASON)) { "fault=$f ergab ${d.reason}" }
        }
    }

    /** Laeuft gar nichts, gibt es auch nichts zu beenden. */
    @Test
    fun `ohne laufende TBR passiert nichts`() {
        val d = entscheide(cfgAn, current = null)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals("NO_POSITIVE_NOTHING_RUNNING", d.reason)
    }

    /** Eine laufende POSITIVE TBR wird weiterhin abgebrochen - der alte Pfad
     *  bleibt unberuehrt und darf nicht vom neuen verdeckt werden. */
    @Test
    fun `eine positive TBR wird weiterhin ueber den alten Pfad abgebrochen`() {
        val positiv = TbrPolicy.Current(1.5, 20, TbrPolicy.SourceType.TEMP_BASAL)
        for (cfg in listOf(cfgAus, cfgAn)) {
            val d = entscheide(cfg, current = positiv)
            assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome)
            assertEquals("NO_POSITIVE_CANCEL", d.reason)
        }
    }
}
