package app.aaps.fuse.plugin

import app.aaps.fuse.plugin.ledger.EpisodeBudgets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE LAUFENDE NULLPHASEN-BILANZ (Vertrag Punkt 1) - pure Rechnung.
 *
 * Sie beantwortet genau eine Frage: wie lange lief eine Null-TBR, wieviel
 * Profilbasal blieb dabei aus, und WELCHER ANTEIL davon lief ohne
 * anliegenden Schutzgrund bzw. sogar ohne weiter fallenden Zucker. Die drei
 * Zeitklassen bleiben getrennt - wer sie zusammenzieht, verwechselt die
 * Massnahme mit ihrer Hysterese.
 */
class ZeroPhaseTallyTest {

    private val t0 = 1_700_000_000_000L
    private fun min(m: Double) = t0 + (m * 60_000).toLong()

    private fun tick(
        vorher: EpisodeBudgets.ZeroPhaseTally?,
        minute: Double,
        zero: Boolean = true,
        basal: Double? = 0.6,
        grund: Boolean = true,
        rate: Double? = -0.5,
    ) = BasalGapRechner.zeroTally(vorher, min(minute), zero, basal, grund, rate)

    @Test
    fun `der erste Zyklus einer Phase traegt keine Zeit`() {
        val a = tick(null, 0.0)!!
        assertEquals(t0, a.sinceTs)
        assertEquals(0.0, a.minutes, 1e-9, "rueckwaerts raten, wie lange die Null schon lief, waere erfunden")
        assertEquals(0.0, a.omittedU, 1e-9)
    }

    @Test
    fun `Minuten und ausgelassenes Basal wachsen mit dem Profil`() {
        var s = tick(null, 0.0)
        repeat(30) { s = tick(s, (it + 1).toDouble()) }
        assertEquals(30.0, s!!.minutes, 1e-9)
        // 30 min x 0,6 U/h = 0,30 U
        assertEquals(0.30, s!!.omittedU, 1e-9)
    }

    @Test
    fun `die drei Zeitklassen trennen Massnahme, Hysterese und flache Haltezeit`() {
        var s = tick(null, 0.0)
        // 10 min MIT Schutzgrund (Klasse A)
        repeat(10) { s = tick(s, (it + 1).toDouble(), grund = true, rate = -0.8) }
        // 10 min ohne Grund, aber weiter fallend (Klasse B - die Hysterese)
        repeat(10) { s = tick(s, (11 + it).toDouble(), grund = false, rate = -0.4) }
        // 10 min ohne Grund und flach (Klasse C - die einzige unstrittige)
        repeat(10) { s = tick(s, (21 + it).toDouble(), grund = false, rate = 0.1) }
        val f = s!!
        assertEquals(30.0, f.minutes, 1e-9)
        assertEquals(20.0, f.reasonAbsentMin, 1e-9, "B und C zusammen laufen ohne Grund")
        assertEquals(10.0, f.flatAbsentMin, 1e-9, "nur C ist ohne Grund UND nicht fallend")
        assertTrue(f.flatAbsentMin < f.reasonAbsentMin, "C ist immer eine Teilmenge von B+C")
    }

    @Test
    fun `eine unbekannte Rate zaehlt NICHT als flach`() {
        // Fail-closed fuer die Klasse, die spaeter als "vermeidbar" gelesen
        // wird: ohne Messung keine Behauptung.
        var s = tick(null, 0.0)
        repeat(5) { s = tick(s, (it + 1).toDouble(), grund = false, rate = null) }
        assertEquals(5.0, s!!.reasonAbsentMin, 1e-9)
        assertEquals(0.0, s!!.flatAbsentMin, 1e-9)
    }

    @Test
    fun `ein fehlendes Profil stoppt die Menge, nicht die Zeit`() {
        var s = tick(null, 0.0)
        repeat(10) { s = tick(s, (it + 1).toDouble(), basal = null) }
        assertEquals(10.0, s!!.minutes, 1e-9)
        assertEquals(0.0, s!!.omittedU, 1e-9, "lieber unvollstaendig als geschaetzt")
    }

    @Test
    fun `eine Zyklusluecke wird gekappt und ausgewiesen`() {
        var s = tick(null, 0.0)
        s = tick(s, 30.0)   // 30 min Sprung (Prozesspause)
        val f = s!!
        assertEquals(BasalGapRechner.ZERO_TALLY_MAX_STEP_MIN, f.minutes, 1e-9) {
            "eine halbe Stunde Pause darf nicht als Nullbasal verbucht werden"
        }
        assertEquals(30.0 - BasalGapRechner.ZERO_TALLY_MAX_STEP_MIN, f.gapCappedMin, 1e-9) {
            "was gekappt wurde, bleibt sichtbar"
        }
    }

    @Test
    fun `eine rueckwaerts springende Uhr dreht die Bilanz nicht zurueck`() {
        var s = tick(null, 10.0)
        s = tick(s, 11.0)
        val vorher = s!!.minutes
        s = tick(s, 5.0)    // NTP-Korrektur zurueck
        assertEquals(vorher, s!!.minutes, 1e-9)
        assertEquals(min(5.0), s!!.lastTickTs, "der Zeitstempel wandert mit, die Bilanz steht")
    }

    @Test
    fun `ohne laufende Null gibt es keine Bilanz`() {
        assertNull(tick(null, 0.0, zero = false))
        val laufend = tick(null, 0.0)
        assertNotNull(laufend)
        assertNull(tick(laufend, 1.0, zero = false), "das Ende beendet die Phase")
    }

    @Test
    fun `die Summenidentitaet haelt ueber jeden Verlauf - A plus B plus C ist die Dauer`() {
        // DER AUSWERTUNGSFEHLER, DEN DIESER TEST VERHINDERT: die drei
        // Klassen werden aus zwei gespeicherten Zaehlern abgeleitet
        // (A = minutes - reasonAbsent, B = reasonAbsent - flatAbsent,
        // C = flatAbsent). Wer sie aus verschiedenen Laeufen oder ueber
        // verschiedene Phasenmengen zusammentraegt, bekommt Summen, die
        // nicht mehr zur Dauer passen - genau so ist eine Auswertung
        // entstanden, in der 178 Minuten Nullzeit als 202 erschienen.
        var s = tick(null, 0.0)
        var m = 0.0
        // Ein bewusst gemischter Verlauf: Grund an/aus, Rate fallend/flach,
        // dazwischen eine Luecke und ein Uhrsprung zurueck.
        val muster = listOf(
            Triple(true, -0.9, 1.0), Triple(true, -0.5, 1.0), Triple(false, -0.4, 1.0),
            Triple(false, 0.2, 1.0), Triple(false, null, 1.0), Triple(true, -0.1, 5.0),
            Triple(false, 0.0, 1.0), Triple(false, -0.02, 1.0),
        )
        for ((grund, rate, dt) in muster) {
            m += dt
            s = BasalGapRechner.zeroTally(s, min(m), true, 0.6, grund, rate)
        }
        val f = s!!
        val a = f.minutes - f.reasonAbsentMin
        val b = f.reasonAbsentMin - f.flatAbsentMin
        val c = f.flatAbsentMin
        assertEquals(f.minutes, a + b + c, 1e-9, "A + B + C MUSS die gezaehlte Dauer ergeben")
        assertTrue(a >= 0.0 && b >= 0.0 && c >= 0.0, "keine Klasse kann negativ werden")
        assertTrue(f.reasonAbsentMin <= f.minutes, "ohne Grund kann nie mehr sein als gesamt")
        assertTrue(f.flatAbsentMin <= f.reasonAbsentMin, "flach ist eine Teilmenge von ohne Grund")
        // Und die Menge folgt DERSELBEN gezaehlten Zeit, nicht der rohen:
        // die gekappte Luecke darf nicht als Basal verbucht sein.
        assertEquals(0.6 * f.minutes / 60.0, f.omittedU, 1e-9)
        assertTrue(f.gapCappedMin > 0.0, "der 5-min-Schritt muss als Luecke sichtbar sein")
    }

    @Test
    fun `die Bilanz ist keine Anspruchsgrundlage - sie kennt nur Zeit und Menge`() {
        // Waechter gegen den Fehler, gegen den der Vertrag ausdruecklich
        // warnt: die Klasse traegt KEIN Budget-, Recovery- oder
        // Bedarfsfeld. Die fehlende Basalwirkung steht im Basal-IOB.
        val felder = EpisodeBudgets.ZeroPhaseTally::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(
            setOf("sinceTs", "lastTickTs", "minutes", "omittedU", "reasonAbsentMin", "flatAbsentMin", "gapCappedMin"),
            felder,
            "neue Felder hier brauchen eine eigene Entscheidung - besonders alles, was nach Anspruch klingt",
        )
    }
}
