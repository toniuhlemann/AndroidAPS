package app.aaps.fuse.plugin

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.fuse.core.controller.FuseController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever

/**
 * L5: DER TBR-QUELLVERTRAG.
 *
 * Die klassische Selbsttaeuschung eines Reglers ist, das eigene ANGEFORDERTE
 * fuer das Geschehene zu halten. Genau davor steht dieser Vertrag:
 *
 *   1. Als laufende TBR gilt AUSSCHLIESSLICH der tatsaechlich gelesene
 *      Zustand aus [ProcessedTbrEbData].
 *   2. Eine berechnete, angeforderte oder abgelehnte TBR erzeugt KEINEN
 *      internen "laeuft bereits"-Zustand.
 *   3. Bleibt eine positive TBR nach abgelehntem Cancel real stehen, fordert
 *      der Folgezyklus ERNEUT einen Cancel an.
 *   4. Ist sie verschwunden, entsteht KEIN weiterer Cancel.
 *
 * Die reine Regel [FuseAbortTbr.classify] ist bereits in [FuseAbortTbrTest]
 * gedeckt. Ungedeckt war die LESENDE Fassung `evaluate()` - und damit genau
 * die Quellgarantie. Ersetzte jemand die Quelle durch eine gemerkte eigene
 * Anforderung, waere bisher nichts rot geworden.
 */
class TbrSourceContractTest {

    private val now = 1_700_000_000_000L
    private val basal = 0.70

    private val tbrData: ProcessedTbrEbData = mock(ProcessedTbrEbData::class.java)
    private val profileFunction: ProfileFunction = mock(ProfileFunction::class.java)
    private val profile: Profile = mock(Profile::class.java)

    private fun profilVorhanden() {
        whenever(profile.getBasal(any())) doReturn basal
        whenever(profileFunction.getProfile(any())) doReturn profile
    }

    /** Eine laufende TBR mit ABSOLUTER Rate - `convertedToAbsolute` gibt sie
     *  dann unveraendert zurueck, der Test haengt also nicht an der
     *  Prozentrechnung. */
    private fun laufend(rate: Double, typ: TB.Type = TB.Type.NORMAL) = TB(
        timestamp = now - 5 * 60_000L,
        duration = 30 * 60_000L,
        rate = rate,
        isAbsolute = true,
        type = typ,
    )

    private fun quelle(tb: TB?) = whenever(tbrData.getTempBasalIncludingConvertedExtended(any())) doReturn tb

    private fun urteil() = FuseAbortTbr.evaluate(tbrData, profileFunction, now)

    // ---- Die Matrix ------------------------------------------------------

    @Test
    fun `ohne reale TBR wird nichts angefordert`() {
        profilVorhanden()
        quelle(null)
        val o = urteil()
        assertNull(o.request)
        assertFalse(o.alarm) { "nichts zu tun ist kein Alarmfall" }
    }

    @Test
    fun `eine reale positive TBR ueber Profilbasal wird abgebrochen`() {
        profilVorhanden()
        quelle(laufend(1.20))
        assertEquals(FuseController.TbrRequest(0.0, 0), urteil().request)
    }

    @Test
    fun `eine reale TBR auf oder unter Profilbasal bleibt unangetastet`() {
        profilVorhanden()
        for (rate in listOf(0.0, 0.20, basal)) {
            quelle(laufend(rate))
            val o = urteil()
            assertNull(o.request, "rate=$rate")
            assertFalse(o.alarm, "rate=$rate")
        }
    }

    /** Ein als Temp gefuehrter Extended Bolus ist eine laufende, NICHT
     *  abbrechbare Abgabe - kein Eingriff, aber Alarm. */
    @Test
    fun `FAKE_EXTENDED fordert nichts an und alarmiert`() {
        profilVorhanden()
        quelle(laufend(1.20, TB.Type.FAKE_EXTENDED))
        val o = urteil()
        assertNull(o.request)
        assertTrue(o.alarm)
    }

    @Test
    fun `unlesbares Profil fordert nichts an und alarmiert`() {
        whenever(profileFunction.getProfile(any())) doReturn null
        quelle(laufend(1.20))
        val o = urteil()
        assertNull(o.request)
        assertTrue(o.alarm)
    }

    /** Auch eine WERFENDE Quelle darf den Abbruchpfad nicht mitreissen -
     *  sie ist "wir wissen es nicht", nicht "es lief nichts". */
    @Test
    fun `eine werfende Quelle fordert nichts an und alarmiert`() {
        profilVorhanden()
        whenever(tbrData.getTempBasalIncludingConvertedExtended(any())) doThrow IllegalStateException("db weg")
        val o = urteil()
        assertNull(o.request)
        assertTrue(o.alarm)
    }

    // ---- Zwei Zyklen: die Quelle entscheidet, nicht das Gedaechtnis ------

    /**
     * DER KERN DES VERTRAGS. Der Cancel wird abgelehnt, die Quelle meldet
     * unveraendert dieselbe positive TBR - also muss ERNEUT abgebrochen
     * werden.
     *
     * Ein Regler mit eigenem "habe ich schon angefordert"-Zustand wuerde hier
     * schweigen und die TBR bis zu ihrem Ende weiterlaufen lassen. Genau das
     * ist der fail-silent-Fall, gegen den der Vertrag geschrieben wurde.
     */
    @Test
    fun `nach abgelehntem Cancel wird erneut abgebrochen`() {
        profilVorhanden()
        quelle(laufend(1.20))

        val ersterZyklus = urteil()
        assertEquals(FuseController.TbrRequest(0.0, 0), ersterZyklus.request)

        // Der Cancel ging nicht durch - die Quelle meldet dasselbe wie vorher.
        val zweiterZyklus = urteil()
        assertEquals(FuseController.TbrRequest(0.0, 0), zweiterZyklus.request) {
            "die Anforderung von eben ist kein Zustand - massgeblich ist, was die Quelle meldet"
        }
    }

    /** Und die Gegenrichtung: ist sie wirklich weg, entsteht kein zweiter
     *  Cancel. Sonst wuerde FUSE eine Absenkung beenden, die es gar nicht
     *  gibt. */
    @Test
    fun `nach umgesetztem Cancel entsteht kein weiterer Cancel`() {
        profilVorhanden()
        quelle(laufend(1.20))
        assertEquals(FuseController.TbrRequest(0.0, 0), urteil().request)

        quelle(null)
        val danach = urteil()
        assertNull(danach.request) { "was nicht mehr laeuft, wird nicht mehr abgebrochen" }
        assertFalse(danach.alarm)
    }

    /**
     * Regel 2 einzeln: eine ABGELEHNTE normale TBR darf keinen internen
     * Zustand hinterlassen. Bleibt die Quelle leer, ist die Lage im
     * Folgezyklus unveraendert offen - der Regler darf frei neu entscheiden.
     */
    @Test
    fun `eine abgelehnte Anforderung hinterlaesst keinen laeuft-bereits-Zustand`() {
        profilVorhanden()
        quelle(null)
        repeat(3) {
            val o = urteil()
            assertNull(o.request) { "leere Quelle heisst leer - unabhaengig von frueheren Anforderungen" }
            assertFalse(o.alarm)
        }
    }
}
