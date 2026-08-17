package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER ERWARTUNGS-LEDGER, erster Baustein des Sackgassenwaechters.
 *
 * Er misst und entscheidet nichts - deshalb pruefen diese Tests keine
 * Dosierung, sondern die BEWEISFUEHRUNG: was zaehlt als Nachweis, dass die
 * versprochene Senkung ausgeblieben ist, und was ausdruecklich nicht.
 */
class ExpectationLedgerTest {

    private val t0 = 1_787_000_000_000L
    private val H = 30
    private val SEG = 1L

    private fun eintrag(
        source: Long = t0,
        seg: Long = SEG,
        anchor: Double = 200.0,
        mean: Double = 150.0,
    ) = ExpectationLedger.issue(source, seg, anchor, mean, H)!!

    private fun probe(ts: Long, mgdl: Double) = ExpectationLedger.Sample(ts, mgdl)

    // ---- Einreihen ------------------------------------------------------

    /** NUR SENKUNGEN DER MITTELBAHN sind widerlegbar. */
    @Test
    fun `nur eine behauptete Senkung wird eingereiht`() {
        assertTrue(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, H) != null, "50 mg/dl Senkung")
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 200.0, H), "unveraendert ist keine Behauptung")
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 240.0, H), "ein Anstieg erst recht nicht")
        assertNull(
            ExpectationLedger.issue(t0, SEG, 200.0, 194.0, H),
            "6 mg/dl liegen im Messrauschen - daraus laesst sich nichts belegen",
        )
    }

    @Test
    fun `unbrauchbare Eingaben werden nicht eingereiht`() {
        assertNull(ExpectationLedger.issue(t0, SEG, null, 150.0, H))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, null, H))
        assertNull(ExpectationLedger.issue(t0, SEG, Double.NaN, 150.0, H))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, Double.NaN, H))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, 0), "ohne Horizont keine Faelligkeit")
    }

    /**
     * DIE SICHERHEITSUNTERGRENZE IST KEIN VERSPRECHEN (Tonis zweiter Befund).
     *
     * Die Mittelbahn ist eine Erwartung, die eintreten SOLL. Die Untergrenze
     * ist ein pessimistisches Risikoszenario, das gerade NICHT eintreten
     * soll. Sie faehrt als Kontext mit und geht in kein Urteil ein - sonst
     * liest der spaetere Verbraucher die Daten semantisch falsch.
     */
    @Test
    fun `die Sicherheitsuntergrenze faehrt als Kontext mit, nicht als Versprechen`() {
        val e = ExpectationLedger.issue(
            t0, SEG, anchorMgdl = 200.0, meanPredictedMgdl = 150.0, horizonMin = H,
            safetyLowerPredictedMgdl = 40.0, lambda = 1.0, discountMgdl = -110.8, bgiMgdl = -127.7,
            configGeneration = "cfg#1",
        )!!
        assertEquals(50.0, e.promisedDropMgdl, 1e-9, "das Versprechen ist die MITTELBAHN")
        assertEquals(40.0, e.safetyLowerPredictedMgdl!!, 1e-9)

        // Realer BG 180: die Mittelbahn (150) ist deutlich verfehlt, die
        // Untergrenze (40) hat sich nicht realisiert. Nur das ERSTE zaehlt.
        val (out, _) = ExpectationLedger.settle(listOf(e), e.dueTs, listOf(probe(e.dueTs, 180.0)), true)
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(30.0, out[0].meanErrorMgdl!!, 1e-9, "gemessen gegen die Mittelbahn")
        assertEquals(140.0, out[0].distanceFromSafetyLowerMgdl!!, 1e-9, "reine Diagnose")
    }

    // ---- Abrechnen: die ZEITLICHE ZUORDNUNG ------------------------------

    @Test
    fun `vor der Faelligkeit wird nichts abgerechnet`() {
        val e = eintrag()
        val (out, offen) = ExpectationLedger.settle(
            listOf(e), t0 + 10 * 60_000L, listOf(probe(t0 + 10 * 60_000L, 190.0)), true,
        )
        assertTrue(out.isEmpty())
        assertEquals(listOf(e), offen)
    }

    /** DER ANLASSFALL: versprochen 150, gemessen 205 zur Faelligkeit. */
    @Test
    fun `eine ausgebliebene Senkung wird als MISSED verbucht`() {
        val e = eintrag()
        val (out, offen) = ExpectationLedger.settle(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)), true)
        assertEquals(1, out.size)
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(55.0, out[0].meanErrorMgdl!!, 1e-9)
        assertEquals(e.dueTs, out[0].actualTs)
        assertTrue(offen.isEmpty())
    }

    @Test
    fun `eine eingetroffene Senkung wird als MET verbucht`() {
        val e = eintrag()
        val (out, _) = ExpectationLedger.settle(listOf(e), e.dueTs, listOf(probe(e.dueTs, 148.0)), true)
        assertEquals(ExpectationLedger.Verdict.MET, out[0].verdict)
    }

    /** Knappes Verfehlen ist kein Nachweis - die Toleranz sitzt auf der
     *  Seite des Modells. */
    @Test
    fun `ein knappes Verfehlen gilt noch als eingetroffen`() {
        val e = eintrag()
        val knapp = ExpectationLedger.settle(listOf(e), e.dueTs, listOf(probe(e.dueTs, 153.0)), true).first
        assertEquals(ExpectationLedger.Verdict.MET, knapp[0].verdict)
        val deutlich = ExpectationLedger.settle(listOf(e), e.dueTs, listOf(probe(e.dueTs, 158.0)), true).first
        assertEquals(ExpectationLedger.Verdict.MISSED, deutlich[0].verdict)
    }

    /**
     * TONIS ERSTER BEFUND, und der Vorgaengertest schrieb genau das falsche
     * Verhalten fest ("mehrere faellige Eintraege werden gemeinsam
     * abgerechnet").
     *
     * Nach einer CGM-Luecke werden mehrere Faelligkeiten ueberfaellig. Sie
     * alle gegen den EINEN spaeten Messwert zu pruefen, erfindet einen
     * Nachweis: derselbe Wert wuerde drei verschiedene Prognosen widerlegen,
     * von denen er nur zu einer gehoert. Jede Faelligkeit braucht einen
     * Messwert in IHRER Naehe - sonst ist sie nicht bewertbar.
     */
    @Test
    fun `nach einer Luecke wird kein spaeterer Wert rueckwirkend verwendet`() {
        val a = eintrag(source = t0)                       // faellig t0+30
        val b = eintrag(source = t0 + 5 * 60_000L)         // faellig t0+35
        val c = eintrag(source = t0 + 10 * 60_000L)        // faellig t0+40
        // Einziger Messwert: 20 Minuten NACH der letzten Faelligkeit.
        val spaet = probe(t0 + 60 * 60_000L, 205.0)
        val (out, _) = ExpectationLedger.settle(listOf(a, b, c), t0 + 60 * 60_000L, listOf(spaet), true)
        assertEquals(3, out.size)
        assertTrue(
            out.all { it.verdict == ExpectationLedger.Verdict.UNVERIFIABLE },
            "keine einzige darf am spaeten Wert abgerechnet werden: ${out.map { it.verdict }}",
        )
        assertTrue(out.all { it.actualMgdl == null })
    }

    /** Die Gegenprobe: mit passenden Messwerten wird jede Faelligkeit an
     *  IHREM eigenen Punkt abgerechnet. */
    @Test
    fun `jede Faelligkeit wird an ihrem eigenen Messwert abgerechnet`() {
        val a = eintrag(source = t0)                  // faellig t0+30 -> 205 (MISSED)
        val b = eintrag(source = t0 + 5 * 60_000L)    // faellig t0+35 -> 145 (MET)
        val samples = listOf(probe(a.dueTs, 205.0), probe(b.dueTs, 145.0))
        val (out, _) = ExpectationLedger.settle(listOf(a, b), b.dueTs, samples, true)
        val nachFaelligkeit = out.associateBy { it.entry.dueTs }
        assertEquals(ExpectationLedger.Verdict.MISSED, nachFaelligkeit[a.dueTs]!!.verdict)
        assertEquals(ExpectationLedger.Verdict.MET, nachFaelligkeit[b.dueTs]!!.verdict)
    }

    /** Ein Messwert knapp daneben zaehlt noch, einer weit daneben nicht. */
    @Test
    fun `die Zuordnung hat eine enge Toleranz`() {
        val e = eintrag()
        val nah = ExpectationLedger.settle(
            listOf(e), e.dueTs + 5 * 60_000L, listOf(probe(e.dueTs + 60_000L, 205.0)), true,
        ).first
        assertEquals(ExpectationLedger.Verdict.MISSED, nah[0].verdict, "eine Minute daneben ist noch zuordenbar")
        val fern = ExpectationLedger.settle(
            listOf(e), e.dueTs + 10 * 60_000L, listOf(probe(e.dueTs + 8 * 60_000L, 205.0)), true,
        ).first
        assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, fern[0].verdict, "acht Minuten sind es nicht")
    }

    /** Ohne Signalgesundheit ist nichts bewertbar, auch mit Messwert. */
    @Test
    fun `ohne gesundes Signal ist die Prognose nicht bewertbar`() {
        val e = eintrag()
        val (out, _) = ExpectationLedger.settle(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)), false)
        assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict)
        assertNull(out[0].meanErrorMgdl)
    }

    // ---- Die Strecke: DAUER statt Anzahl ---------------------------------

    private fun ergebnis(due: Long, v: ExpectationLedger.Verdict, seg: Long = SEG) =
        ExpectationLedger.Outcome(
            ExpectationLedger.Entry(due - H * 60_000L, due, seg, 200.0, 150.0),
            v, if (v == ExpectationLedger.Verdict.UNVERIFIABLE) null else due,
            if (v == ExpectationLedger.Verdict.UNVERIFIABLE) null else 205.0,
        )

    /**
     * TONIS DRITTER BEFUND: bei einer Prognose je Minute beschreiben 30
     * aufeinanderfolgende MISSED DASSELBE Plateau. Gemessen wird deshalb die
     * zeitliche Ausdehnung, nicht die Anzahl.
     */
    @Test
    fun `gemessen wird die Dauer der Strecke, nicht die Anzahl`() {
        val m = ExpectationLedger.Verdict.MISSED
        // Zehn Prognosen im Minutentakt = neun Minuten Strecke, nicht "zehn".
        val zehn = (0..9).map { ergebnis(t0 + it * 60_000L, m) }
        assertEquals(9, ExpectationLedger.missedStreakMin(zehn, SEG))
        // Dieselbe Anzahl ueber eine Stunde verteilt ist ein staerkerer Beleg.
        val verteilt = (0..9).map { ergebnis(t0 + it * 6 * 60_000L, m) }
        assertEquals(54, ExpectationLedger.missedStreakMin(verteilt, SEG))
    }

    /** Ein einzelnes Eintreffen beendet die Strecke - der Nachweis beginnt
     *  von vorn. */
    @Test
    fun `ein Eintreffen beendet die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val t = ExpectationLedger.Verdict.MET
        val reihe = listOf(
            ergebnis(t0, m), ergebnis(t0 + 60_000, m),
            ergebnis(t0 + 120_000, t),
            ergebnis(t0 + 180_000, m), ergebnis(t0 + 240_000, m),
        )
        assertEquals(1, ExpectationLedger.missedStreakMin(reihe, SEG), "nur die beiden juengsten zaehlen")
        assertEquals(0, ExpectationLedger.missedStreakMin(listOf(ergebnis(t0, t)), SEG))
        assertEquals(0, ExpectationLedger.missedStreakMin(emptyList(), SEG))
    }

    /**
     * EIN SEGMENTBRUCH BEENDET DIE STRECKE. Ueber eine Signalluecke hinweg
     * gibt es keinen zusammenhaengenden Nachweis - sonst wuerde eine
     * Ausbleib-Strecke von vor der Luecke eine spaetere Dosierentscheidung
     * mittragen, die sie nicht belegt.
     */
    @Test
    fun `ein Segmentbruch beendet die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val reihe = listOf(
            ergebnis(t0, m, seg = 1L), ergebnis(t0 + 60_000, m, seg = 1L),
            ergebnis(t0 + 300_000, m, seg = 2L), ergebnis(t0 + 360_000, m, seg = 2L),
        )
        assertEquals(
            1, ExpectationLedger.missedStreakMin(reihe, currentSegmentId = 2L),
            "nur das aktuelle Segment traegt den Nachweis",
        )
        assertEquals(
            0, ExpectationLedger.missedStreakMin(reihe, currentSegmentId = 3L),
            "nach einem weiteren Bruch beginnt er ganz neu",
        )
    }

    /** UNVERIFIABLE zaehlt weder mit noch bricht es - solange das Segment
     *  dasselbe bleibt. */
    @Test
    fun `nicht bewertbare Zyklen unterbrechen die Strecke nicht`() {
        val m = ExpectationLedger.Verdict.MISSED
        val u = ExpectationLedger.Verdict.UNVERIFIABLE
        val reihe = listOf(ergebnis(t0, m), ergebnis(t0 + 60_000, u), ergebnis(t0 + 120_000, m))
        assertEquals(2, ExpectationLedger.missedStreakMin(reihe, SEG))
        assertEquals(
            0, ExpectationLedger.missedStreakMin(listOf(ergebnis(t0, u), ergebnis(t0 + 60_000, u)), SEG),
            "aus lauter Unbeobachtetem entsteht kein Nachweis",
        )
    }
}
