package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER ERWARTUNGS-LEDGER, erster Baustein des Sackgassenwaechters.
 *
 * Er misst und entscheidet nichts - deshalb pruefen diese Tests keine
 * Dosierung, sondern die Beweisfuehrung: was zaehlt als Nachweis, dass die
 * versprochene Senkung ausgeblieben ist, und was ausdruecklich nicht.
 */
class ExpectationLedgerTest {

    private val t0 = 1_787_000_000_000L
    private val H = 30

    // ---- Einreihen ------------------------------------------------------

    /** NUR SENKUNGEN sind widerlegbar. Eine Prognose "bleibt gleich" oder
     *  "steigt" kann nicht ausbleiben. */
    @Test
    fun `nur eine behauptete Senkung wird eingereiht`() {
        assertTrue(ExpectationLedger.issue(t0, 200.0, 150.0, H) != null, "50 mg/dl Senkung")
        assertNull(ExpectationLedger.issue(t0, 200.0, 200.0, H), "unveraendert ist keine Behauptung")
        assertNull(ExpectationLedger.issue(t0, 200.0, 240.0, H), "ein Anstieg erst recht nicht")
        assertNull(
            ExpectationLedger.issue(t0, 200.0, 194.0, H),
            "6 mg/dl liegen im Messrauschen - daraus laesst sich nichts belegen",
        )
    }

    /** Unbrauchbare Eingaben ergeben keinen Eintrag - ein Ledger aus
     *  Phantomwerten waere schlimmer als keiner. */
    @Test
    fun `unbrauchbare Eingaben werden nicht eingereiht`() {
        assertNull(ExpectationLedger.issue(t0, null, 150.0, H))
        assertNull(ExpectationLedger.issue(t0, 200.0, null, H))
        assertNull(ExpectationLedger.issue(t0, Double.NaN, 150.0, H))
        assertNull(ExpectationLedger.issue(t0, 200.0, Double.NaN, H))
        assertNull(ExpectationLedger.issue(t0, 200.0, 150.0, 0), "ohne Horizont keine Faelligkeit")
    }

    @Test
    fun `die Faelligkeit liegt einen Horizont spaeter`() {
        val e = ExpectationLedger.issue(t0, 200.0, 150.0, H)!!
        assertEquals(t0 + 30 * 60_000L, e.dueTs)
        assertEquals(50.0, e.promisedDropMgdl, 1e-9)
    }

    // ---- Abrechnen ------------------------------------------------------

    private fun eintrag(issued: Long = t0, anchor: Double = 200.0, pred: Double = 150.0) =
        ExpectationLedger.issue(issued, anchor, pred, H)!!

    /** Noch nicht faellige Eintraege bleiben unangetastet stehen. */
    @Test
    fun `vor der Faelligkeit wird nichts abgerechnet`() {
        val e = eintrag()
        val (out, offen) = ExpectationLedger.settle(listOf(e), t0 + 10 * 60_000L, 190.0, true)
        assertTrue(out.isEmpty())
        assertEquals(listOf(e), offen)
    }

    /** DER ANLASSFALL: versprochen 150, gemessen 205 - die Senkung ist
     *  ausgeblieben, und zwar um 55 mg/dl. */
    @Test
    fun `eine ausgebliebene Senkung wird als MISSED verbucht`() {
        val e = eintrag()
        val (out, offen) = ExpectationLedger.settle(listOf(e), e.dueTs, 205.0, true)
        assertEquals(1, out.size)
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(55.0, out[0].shortfallMgdl!!, 1e-9)
        assertTrue(offen.isEmpty(), "abgerechnete Eintraege werden nicht weiter vorgehalten")
    }

    /** Eine eingetroffene Senkung ist MET - und beendet damit jede Serie. */
    @Test
    fun `eine eingetroffene Senkung wird als MET verbucht`() {
        val e = eintrag()
        val (out, _) = ExpectationLedger.settle(listOf(e), e.dueTs, 148.0, true)
        assertEquals(ExpectationLedger.Verdict.MET, out[0].verdict)
    }

    /**
     * KNAPPES VERFEHLEN IST KEIN NACHWEIS. Die Toleranz sitzt bewusst auf
     * der Seite des Modells: 153 gegen versprochene 150 liegt im Rauschen
     * und darf die pessimistische Annahme nicht widerlegen.
     */
    @Test
    fun `ein knappes Verfehlen gilt noch als eingetroffen`() {
        val e = eintrag()
        val (knapp, _) = ExpectationLedger.settle(listOf(e), e.dueTs, 153.0, true)
        assertEquals(ExpectationLedger.Verdict.MET, knapp[0].verdict)
        val (deutlich, _) = ExpectationLedger.settle(listOf(e), e.dueTs, 158.0, true)
        assertEquals(ExpectationLedger.Verdict.MISSED, deutlich[0].verdict)
    }

    /**
     * EIN UNBEOBACHTETER ZEITPUNKT IST KEIN EINGEHALTENES VERSPRECHEN.
     *
     * Ihn als MET zu zaehlen waere die bequeme Variante und wuerde den
     * Nachweis verwaessern; ihn als MISSED zu zaehlen wuerde aus einem
     * Sensorausfall einen Freibrief fuer mehr Insulin machen. Beides waere
     * falsch, deshalb die dritte Kategorie.
     */
    @Test
    fun `ohne brauchbare Messung ist die Prognose nicht bewertbar`() {
        val e = eintrag()
        for (fall in listOf(
            ExpectationLedger.settle(listOf(e), e.dueTs, null, true),
            ExpectationLedger.settle(listOf(e), e.dueTs, 205.0, false),
            ExpectationLedger.settle(listOf(e), e.dueTs, Double.NaN, true),
        )) {
            assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, fall.first[0].verdict)
            assertNull(fall.first[0].shortfallMgdl, "ohne Messwert kein Fehlbetrag")
        }
    }

    // ---- Die Serie ------------------------------------------------------

    private fun ergebnis(due: Long, v: ExpectationLedger.Verdict) =
        ExpectationLedger.Outcome(
            ExpectationLedger.Entry(due - H * 60_000L, due, 200.0, 150.0),
            v, if (v == ExpectationLedger.Verdict.UNVERIFIABLE) null else 205.0,
        )

    /** Die Serie zaehlt die JUENGSTEN Ausbleiber - und ein einziges
     *  Eintreffen beendet sie. Wirkt das Insulin auch nur einmal wie
     *  versprochen, beginnt der Nachweis von vorn. */
    @Test
    fun `ein einzelnes Eintreffen beendet die Serie`() {
        val m = ExpectationLedger.Verdict.MISSED
        val t = ExpectationLedger.Verdict.MET
        assertEquals(
            3,
            ExpectationLedger.consecutiveMissed(
                listOf(ergebnis(t0, m), ergebnis(t0 + 60_000, m), ergebnis(t0 + 120_000, m)),
            ),
        )
        assertEquals(
            2,
            ExpectationLedger.consecutiveMissed(
                listOf(ergebnis(t0, m), ergebnis(t0 + 60_000, t), ergebnis(t0 + 120_000, m), ergebnis(t0 + 180_000, m)),
            ),
            "die beiden aeltesten zaehlen nicht mehr mit",
        )
        assertEquals(0, ExpectationLedger.consecutiveMissed(listOf(ergebnis(t0, t))))
        assertEquals(0, ExpectationLedger.consecutiveMissed(emptyList()))
    }

    /** UNVERIFIABLE zaehlt weder mit noch bricht es - ein unbeobachteter
     *  Zeitpunkt darf den Nachweis weder tragen noch zerstoeren. */
    @Test
    fun `nicht bewertbare Zyklen unterbrechen die Serie nicht`() {
        val m = ExpectationLedger.Verdict.MISSED
        val u = ExpectationLedger.Verdict.UNVERIFIABLE
        assertEquals(
            2,
            ExpectationLedger.consecutiveMissed(
                listOf(ergebnis(t0, m), ergebnis(t0 + 60_000, u), ergebnis(t0 + 120_000, m)),
            ),
        )
        assertEquals(
            0,
            ExpectationLedger.consecutiveMissed(listOf(ergebnis(t0, u), ergebnis(t0 + 60_000, u))),
            "aus lauter Unbeobachtetem entsteht kein Nachweis",
        )
    }

    /** Mehrere Faelligkeiten in einem Zyklus werden alle abgerechnet - sonst
     *  bliebe nach einer Luecke ein Rest liegen und verfaelschte die Serie. */
    @Test
    fun `mehrere faellige Eintraege werden gemeinsam abgerechnet`() {
        val a = eintrag(issued = t0)
        val b = eintrag(issued = t0 + 60_000)
        val c = eintrag(issued = t0 + 25 * 60_000L)
        val (out, offen) = ExpectationLedger.settle(listOf(a, b, c), b.dueTs, 205.0, true)
        assertEquals(2, out.size)
        assertEquals(listOf(c), offen)
    }
}
