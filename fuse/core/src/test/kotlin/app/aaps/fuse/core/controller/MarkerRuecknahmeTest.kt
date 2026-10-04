package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkerRuecknahmeTest {

    private val marker = 1_000_000L
    private val q = MarkerRuecknahme.Quelle.UEBERSICHT

    @Test
    fun `ohne laufenden Marker gibt es nichts vorzumerken`() {
        assertNull(MarkerRuecknahme.vormerken(marker + 5_000L, 0L, false, 3L, q, null))
        assertNull(MarkerRuecknahme.vormerken(marker + 5_000L, marker, false, 3L, q, null))
    }

    @Test
    fun `Vormerkung wird erst nach der Frist faellig und bleibt bis dahin ohne Wirkung`() {
        val jetzt = marker + 60_000L
        val v = MarkerRuecknahme.vormerken(jetzt, marker, true, 7L, q, null)!!
        assertEquals(jetzt + MarkerRuecknahme.FRIST_MS, v.faelligTs)
        assertEquals(7L, v.ordnung)
        assertEquals(MarkerRuecknahme.Entscheid.Nichts, MarkerRuecknahme.pruefe(v.faelligTs - 1L, v, marker, true))
        val e = MarkerRuecknahme.pruefe(v.faelligTs, v, marker, true)
        assertTrue(e is MarkerRuecknahme.Entscheid.Ausfuehren)
        assertSame(v, (e as MarkerRuecknahme.Entscheid.Ausfuehren).vormerkung)
    }

    @Test
    fun `zweiter Tipp verlaengert die Frist nicht`() {
        val v = MarkerRuecknahme.vormerken(marker + 60_000L, marker, true, 7L, q, null)!!
        val zweiter = MarkerRuecknahme.vormerken(marker + 65_000L, marker, true, 8L, MarkerRuecknahme.Quelle.FUSE_TAB, v)
        assertSame(v, zweiter)
    }

    @Test
    fun `neuer oder abgelaufener Marker verwirft die Vormerkung statt sie auszufuehren`() {
        val v = MarkerRuecknahme.vormerken(marker + 60_000L, marker, true, 7L, q, null)!!
        val neu = MarkerRuecknahme.pruefe(v.faelligTs + 1L, v, marker + 600_000L, true)
        assertEquals("MARKER_CHANGED", (neu as MarkerRuecknahme.Entscheid.Verwerfen).grund)
        val ab = MarkerRuecknahme.pruefe(v.faelligTs + 1L, v, marker, false)
        assertEquals("MARKER_NOT_ACTIVE", (ab as MarkerRuecknahme.Entscheid.Verwerfen).grund)
        // Auch VOR der Faelligkeit: ein anderer Marker macht die Vormerkung sofort gegenstandslos.
        assertTrue(MarkerRuecknahme.pruefe(v.faelligTs - 5_000L, v, marker + 600_000L, true) is MarkerRuecknahme.Entscheid.Verwerfen)
    }

    @Test
    fun `ohne Vormerkung geschieht nichts`() {
        assertEquals(MarkerRuecknahme.Entscheid.Nichts, MarkerRuecknahme.pruefe(marker, null, marker, true))
    }

    @Test
    fun `Quelle uebersteht die Speicherung als Zahl`() {
        MarkerRuecknahme.Quelle.entries.forEach { assertEquals(it, MarkerRuecknahme.Quelle.von(it.code)) }
        assertEquals(MarkerRuecknahme.Quelle.UNBEKANNT, MarkerRuecknahme.Quelle.von(99L))
    }
}
