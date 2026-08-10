package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.Pump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * B3, die LESENDE Seite: GENAU EINE ABFRAGE, KEIN RUECKFALL.
 *
 * [FusePatchEpochTest] prueft die Regel - sie sieht aber immer nur das EINE
 * Ereignis, das man ihr gibt. Ob der Aufrufer daneben noch nach einem
 * aelteren PASSENDEN sucht, kann sie nicht zeigen. Genau das steht hier.
 *
 * Warum das eine eigene Pruefung verdient: ein spaeterer "Fallback" waere im
 * Zyklus eine Zeile, saehe hilfreich aus und wuerde die Trennlinie von B3
 * aufheben - eine alte Zeile duerfte wieder einen Bolus des neuen Patches
 * binden.
 *
 * Die Testwerte sind die am Geraet GEMESSENEN (Pixel 9, 10.08.2026):
 * `pumpId = null`, Serial als Kleinbuchstaben-Hex, Typ ueber die oeffentliche
 * API als MEDTRUM_NANO.
 */
class FusePatchEpochSourceTest {

    private val t0 = 1_700_000_000_000L
    private val jetzt = t0 + 3600_000L
    private val serial = "9c1de26d"

    private val persistence: PersistenceLayer = mock(PersistenceLayer::class.java)

    private fun pumpe(typ: PumpType? = PumpType.MEDTRUM_NANO, s: String? = serial): Pump =
        mock(Pump::class.java).also {
            whenever(it.model()) doReturn (typ ?: PumpType.GENERIC_AAPS)
            whenever(it.serialNumber()) doReturn (s ?: "")
        }

    /**
     * Der Zustand wird aus DERSELBEN Instanz abgeleitet, die auch uebergeben
     * wird - genau so macht es der Zyklus. Ein Mock ist keine VirtualPump,
     * also gilt hier ueberall "echte Pumpe".
     */
    private fun aktuell(p: Pump?, nowTs: Long = jetzt) =
        FusePatchEpochSource.current(persistence, p, FuseActivePump.of(p), nowTs)

    /** Die ECHTE Form: kein pumpId, Serial klein. */
    private fun echterWechsel(ts: Long = t0) = TE(
        timestamp = ts, type = TE.Type.CANNULA_CHANGE, glucoseUnit = GlucoseUnit.MGDL,
        ids = IDs(pumpType = PumpType.MEDTRUM_NANO, pumpSerial = serial, pumpId = null),
    )

    /** Ein Handeintrag - er traegt keine Pumpenkennung. */
    private fun handeintrag(ts: Long) = TE(
        timestamp = ts, type = TE.Type.CANNULA_CHANGE, glucoseUnit = GlucoseUnit.MGDL,
        ids = IDs(),
    )

    @Test
    fun `der echte Geraetedatensatz definiert die Epoche`() {
        whenever(persistence.getLastTherapyRecordUpToNow(any())) doReturn echterWechsel()
        val r = aktuell(pumpe())
        assertEquals(t0, r.epochTs)
        assertEquals(FusePatchEpoch.Reason.MATCHING_PUMP_IDENTITY, r.reason)
    }

    /**
     * DER KERN: genau EIN Aufruf, und zwar mit CANNULA_CHANGE.
     *
     * Waeren es zwei, koennte der zweite eine aeltere passende Zeile suchen -
     * und genau das darf es nicht geben.
     */
    @Test
    fun `es wird genau einmal gefragt`() {
        whenever(persistence.getLastTherapyRecordUpToNow(any())) doReturn echterWechsel()
        aktuell(pumpe())
        verify(persistence, times(1)).getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        verify(persistence, never()).getTherapyEventDataFromTime(any(), any())
        verify(persistence, never()).getTherapyEventDataFromToTime(any(), any())
    }

    /**
     * KEIN RUECKFALL: der neueste Datensatz ist ein Handeintrag. Die Epoche
     * bleibt unbekannt - und es wird NICHT ein zweites Mal gesucht, um doch
     * noch einen passenden aelteren zu finden.
     */
    @Test
    fun `ein neuerer Handeintrag laesst die Epoche unbekannt und loest keine zweite Suche aus`() {
        whenever(persistence.getLastTherapyRecordUpToNow(any())) doReturn handeintrag(t0 + 60_000L)

        val r = aktuell(pumpe())

        assertFalse(r.known) { "unbekannt heisst unbekannt - nicht 'suche weiter'" }
        assertEquals(FusePatchEpoch.Reason.NOT_PUMP_ORIGIN, r.reason)
        verify(persistence, times(1)).getLastTherapyRecordUpToNow(any())
        verify(persistence, never()).getTherapyEventDataFromTime(any(), any())
        verify(persistence, never()).getTherapyEventDataFromToTime(any(), any())
    }

    /** Auch eine werfende Abfrage fuehrt nicht zu einem zweiten Versuch -
     *  sie ist "wir wissen es nicht", nicht "frag nochmal anders". */
    @Test
    fun `eine werfende Abfrage ergibt unbekannt ohne zweiten Versuch`() {
        whenever(persistence.getLastTherapyRecordUpToNow(any()))
            .thenThrow(IllegalStateException("db weg"))
        val r = aktuell(pumpe())
        assertFalse(r.known)
        verify(persistence, times(1)).getLastTherapyRecordUpToNow(any())
    }

    /** Ohne Pumpe ist die aktive Identitaet unbekannt - eigener Befund, und
     *  ebenfalls ohne zweite Abfrage. */
    @Test
    fun `ohne Pumpe bleibt die Epoche unbekannt`() {
        whenever(persistence.getLastTherapyRecordUpToNow(any())) doReturn echterWechsel()
        val r = aktuell(null)
        assertFalse(r.known)
        verify(persistence, times(1)).getLastTherapyRecordUpToNow(any())
    }
}
