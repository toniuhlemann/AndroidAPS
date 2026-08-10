package app.aaps.fuse.plugin

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever

/**
 * Der Riegel ist sicherheitskritisch und winzig - genau die Kombination, bei
 * der ein Test spaeter den Unterschied macht.
 */
class FusePumpGateTest {

    private interface FakeVirtual : Pump, VirtualPump

    private fun pumpOf(type: PumpType): Pump = mock(Pump::class.java).also { whenever(it.model()) doReturn type }

    @Test
    fun `VirtualPump ist erlaubt`() {
        val r = FusePumpGate.evaluate(mock(FakeVirtual::class.java))
        assertTrue(r.allowed)
        assertEquals(FusePumpGate.Verdict.ALLOWED, r.verdict)
        assertEquals("VPUMP_ALPHA", r.reason)
    }

    @Test
    fun `nur der belegte Medtrum Nano ist erlaubt`() {
        val r = FusePumpGate.evaluate(pumpOf(PumpType.MEDTRUM_NANO))
        assertTrue(r.allowed)
        assertEquals(FusePumpGate.Verdict.ALLOWED_REAL_MEDTRUM, r.verdict)
        assertTrue(r.realPump)
    }

    /**
     * Gleicher Treiberpfad ist keine Freigabe.
     *
     * MEDTRUM_300U laeuft durch denselben Treiber, hat dieselbe
     * `PumpCapability` und ist vollstaendig spezifiziert - gesperrt ist es
     * trotzdem. Fuer einen Fork mit genau einem Nutzer ist "belegt" nicht
     * "koennte gehen", sondern "ist gelaufen". Eine Erweiterung bekommt einen
     * eigenen Commit; dieser Test faellt dann bewusst und sichtbar.
     */
    @Test
    fun `Medtrum 300U ist trotz gleichem Treiberpfad gesperrt`() {
        val r = FusePumpGate.evaluate(pumpOf(PumpType.MEDTRUM_300U))
        assertFalse(r.allowed)
        assertEquals(FusePumpGate.Verdict.BLOCKED_UNPROVEN_MODEL, r.verdict)
    }

    /**
     * DER KERN DIESES RIEGELS.
     *
     * MEDTRUM_UNTESTED ist im Treiber der else-Zweig der Modellzuordnung und
     * heisst "diese Kennung kennen wir nicht". Ueber `parent = MEDTRUM_NANO`
     * erbt es aber Nanos komplette Dosier-Huelle - es SIEHT aus wie ein
     * fertiger Pumpentyp. Genau daran ist dieses Projekt schon viermal
     * vorbeigelaufen.
     */
    @Test
    fun `eine Medtrum ohne erkanntes Modell ist gesperrt`() {
        val r = FusePumpGate.evaluate(pumpOf(PumpType.MEDTRUM_UNTESTED))
        assertFalse(r.allowed)
        assertEquals(FusePumpGate.Verdict.BLOCKED_UNPROVEN_MODEL, r.verdict)
        assertFalse(r.realPump)
    }

    /**
     * Erlaubnisliste, nicht Sperrliste.
     *
     * Der Test laeuft ueber ALLE PumpType-Werte, auch die, die es heute noch
     * nicht gibt. Kommt bei einem AAPS-Merge ein neuer Typ dazu - auch ein
     * neuer MEDTRUM_* - ist er gesperrt, ohne dass jemand daran denken muss.
     * Das ist die Eigenschaft, die den Riegel merge-fest macht.
     */
    @Test
    fun `alles ausserhalb der Erlaubnisliste ist gesperrt`() {
        PumpType.entries.filter { it != PumpType.MEDTRUM_NANO }.forEach { type ->
            val r = FusePumpGate.evaluate(pumpOf(type))
            assertFalse(r.allowed, "$type darf NICHT erlaubt sein")
        }
    }

    @Test
    fun `eine Fremdpumpe blockiert mit eigenem Grund`() {
        val r = FusePumpGate.evaluate(pumpOf(PumpType.OMNIPOD_DASH))
        assertFalse(r.allowed)
        assertEquals(FusePumpGate.Verdict.BLOCKED_REAL_PUMP, r.verdict)
    }

    /** Keine identifizierbare Pumpe ist NICHT "vermutlich virtuell" - im
     *  Zweifel wird blockiert. */
    @Test
    fun `fehlende Pumpe blockiert ebenfalls`() {
        val r = FusePumpGate.evaluate(null)
        assertFalse(r.allowed)
        assertEquals(FusePumpGate.Verdict.BLOCKED_UNKNOWN_PUMP, r.verdict)
    }

    /**
     * model() ist eine Treiberabfrage. Steht noch keine Verbindung, kann sie
     * werfen oder nichts liefern - beides heisst "unbekannt", und unbekannt
     * ist gesperrt. Eine Ausnahme darf den Riegel weder oeffnen noch den
     * Zyklus mitreissen.
     */
    @Test
    fun `unermittelbarer Typ blockiert statt zu oeffnen`() {
        val wirft = mock(Pump::class.java).also { whenever(it.model()) doThrow IllegalStateException("kein Kontakt") }
        assertEquals(FusePumpGate.Verdict.BLOCKED_UNKNOWN_PUMP, FusePumpGate.evaluate(wirft).verdict)

        val leer = mock(Pump::class.java)
        assertEquals(FusePumpGate.Verdict.BLOCKED_UNKNOWN_PUMP, FusePumpGate.evaluate(leer).verdict)
    }

    /** Eine erlaubte VirtualPump und eine erlaubte Medtrum duerfen im Export
     *  nicht gleich aussehen - sonst laesst sich hinterher nicht sagen, ob
     *  echtes Insulin im Spiel war. */
    @Test
    fun `realPump trennt Entwicklungspfad von echtem Insulin`() {
        assertFalse(FusePumpGate.evaluate(mock(FakeVirtual::class.java)).realPump)
        assertTrue(FusePumpGate.evaluate(pumpOf(PumpType.MEDTRUM_NANO)).realPump)
    }

    /** Der Grund muss immer sichtbar sein - ein blockiertes FUSE darf nicht
     *  wie ein defektes FUSE aussehen. */
    @Test
    fun `jeder Ausgang nennt einen Grund`() {
        listOf(
            FusePumpGate.evaluate(null),
            FusePumpGate.evaluate(pumpOf(PumpType.OMNIPOD_DASH)),
            FusePumpGate.evaluate(pumpOf(PumpType.MEDTRUM_UNTESTED)),
            FusePumpGate.evaluate(pumpOf(PumpType.MEDTRUM_NANO)),
            FusePumpGate.evaluate(mock(FakeVirtual::class.java)),
        ).forEach { assertTrue(it.reason.isNotBlank()) }
    }
}
