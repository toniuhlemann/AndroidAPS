package app.aaps.fuse.plugin

import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.fuse.core.controller.FuseController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * C7c (Codex-Adjudication D-Tabelle C7 / Frage 2, K2 Punkt 10): EIN
 * Abbruch-Vertrag fuer alle Pfade, auch fuer den Ausnahmepfad.
 *
 * Geprueft wird die Regel und das RT, das der Ausnahmepfad daraus baut - die
 * Verdrahtung in `FusePlugin.invoke()` ruft genau diese beiden Funktionen auf
 * und hat keine eigene Logik mehr.
 */
class FuseAbortTbrTest {

    private interface FakeVirtual : Pump, VirtualPump

    private val allowed = FusePumpGate.evaluate(mock(FakeVirtual::class.java))
    private val blocked = FusePumpGate.evaluate(mock(Pump::class.java))
    private val now = 1_700_000_000_000L

    // ---- die Regel -------------------------------------------------------

    /** F-P0-07: eine laufende POSITIVE TBR wird abgebrochen - rate 0,
     *  duration 0, die eindeutige Cancel-Kodierung. */
    @Test
    fun `eine laufende positive TBR wird abgebrochen`() {
        val o = FuseAbortTbr.classify(fakeExtended = false, runningRateUPerH = 1.20, scheduledBasalUPerH = 0.70)
        assertEquals(FuseController.TbrRequest(0.0, 0), o.request)
        assertFalse(o.alarm)
    }

    /** Ein blinder Cancel wuerde eine Absenkung beenden und damit MEHR Insulin
     *  freigeben - Null und Absenkung bleiben deshalb unangetastet. */
    @Test
    fun `Null- und Absenk-TBRs bleiben unangetastet`() {
        for (rate in listOf(0.0, 0.20, 0.70)) {
            val o = FuseAbortTbr.classify(false, rate, 0.70)
            assertNull(o.request, "rate=$rate")
            assertFalse(o.alarm, "rate=$rate")
        }
    }

    /** Nicht klassifizierbar heisst: kein Eingriff, aber Alarm. "Es lief
     *  nichts" und "wir wissen es nicht" duerfen nicht dasselbe sein. */
    @Test
    fun `unlesbare Lage ergibt keinen Eingriff, aber Alarm`() {
        val faelle = listOf(
            "FAKE_EXTENDED" to FuseAbortTbr.classify(fakeExtended = true, runningRateUPerH = 1.2, scheduledBasalUPerH = 0.7),
            "kein Profil" to FuseAbortTbr.classify(false, 1.2, null),
            "Rate unbekannt" to FuseAbortTbr.classify(false, null, 0.7),
            "Rate NaN" to FuseAbortTbr.classify(false, Double.NaN, 0.7),
            "Basal NaN" to FuseAbortTbr.classify(false, 1.2, Double.NaN),
        )
        for ((name, o) in faelle) {
            assertNull(o.request, name)
            assertTrue(o.alarm, name)
        }
    }

    // ---- das RT des Ausnahmepfads ----------------------------------------

    /**
     * DER BEFUND: eine Ausnahme aus `runner.run()` erzeugte ein RT mit
     * `tbr = null` - eine bereits laufende positive TBR lief ungehindert
     * weiter, obwohl der Abbruch-Vertrag genau das verhindern soll.
     */
    @Test
    fun `wirft der Runner, traegt das RT den Cancel der laufenden positiven TBR`() {
        val o = FuseAbortTbr.classify(false, 1.20, 0.70)
        val rt = FuseAbortTbr.abortRt(now, allowed, o)
        assertEquals(0.0, rt.rate)
        assertEquals(0, rt.duration)
        // Keine Menge: der Ausnahmepfad hat nichts gerechnet.
        assertNull(rt.units)
        assertTrue(rt.reason.toString().contains(FuseAbortTbr.EXCEPTION_REASON), rt.reason.toString())
    }

    @Test
    fun `bei Null-TBR fordert der Ausnahmepfad nichts an`() {
        val rt = FuseAbortTbr.abortRt(now, allowed, FuseAbortTbr.classify(false, 0.0, 0.70))
        assertNull(rt.rate)
        assertNull(rt.duration)
    }

    @Test
    fun `der Alarmfall steht im Grund und fordert nichts an`() {
        val rt = FuseAbortTbr.abortRt(now, allowed, FuseAbortTbr.classify(false, 1.2, null))
        assertNull(rt.rate)
        assertTrue(rt.reason.toString().contains("TBR_UNCLASSIFIABLE"), rt.reason.toString())
    }

    /** Der Pumpen-Riegel steht auch vor dem Ausnahmepfad. */
    @Test
    fun `gegen eine reale Pumpe wird auch im Ausnahmepfad nichts aktuiert`() {
        val rt = FuseAbortTbr.abortRt(now, blocked, FuseAbortTbr.classify(false, 1.20, 0.70))
        assertNull(rt.rate)
        assertNull(rt.duration)
        assertNull(rt.units)
    }
}
