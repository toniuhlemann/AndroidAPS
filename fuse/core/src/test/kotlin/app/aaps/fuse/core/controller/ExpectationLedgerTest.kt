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
    private val CFG = "cfg#1"
    private val REV = 100L

    private fun eintrag(
        source: Long = t0,
        seg: Long = SEG,
        anchor: Double = 200.0,
        mean: Double = 150.0,
        rev: Long = REV,
    ) = ExpectationLedger.issue(
        source, seg, anchor, mean, H, configGeneration = CFG, interventionRevision = rev,
    )!!

    private fun probe(ts: Long, mgdl: Double, seg: Long = SEG, healthy: Boolean = true) =
        ExpectationLedger.Sample(ts, mgdl, seg, healthy)

    private fun rechne(
        entries: List<ExpectationLedger.Entry>,
        now: Long,
        samples: List<ExpectationLedger.Sample>,
        rev: Long = REV,
    ) = ExpectationLedger.settle(entries, now, samples, rev)

    // ---- Einreihen ------------------------------------------------------

    /** NUR SENKUNGEN DER MITTELBAHN sind widerlegbar. */
    @Test
    fun `nur eine behauptete Senkung wird eingereiht`() {
        assertTrue(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, H, CFG, REV) != null)
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 200.0, H, CFG, REV), "unveraendert")
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 240.0, H, CFG, REV), "ein Anstieg")
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 194.0, H, CFG, REV), "6 mg/dl sind Rauschen")
    }

    @Test
    fun `unbrauchbare Eingaben werden nicht eingereiht`() {
        assertNull(ExpectationLedger.issue(t0, SEG, null, 150.0, H, CFG, REV))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, null, H, CFG, REV))
        assertNull(ExpectationLedger.issue(t0, SEG, Double.NaN, 150.0, H, CFG, REV))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, 0, CFG, REV), "ohne Horizont")
    }

    /** OHNE VERGLEICHBARKEITSKENNUNG KEIN EINTRAG. Eine Garantie, die man
     *  weglassen darf, ist keine - sonst steht spaeter ein Ergebnis in der
     *  Datei, das mit nichts vergleichbar ist. */
    @Test
    fun `ohne Konfigurationskennung wird nicht eingereiht`() {
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, H, "", REV))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, H, "   ", REV))
    }

    /**
     * DIE SICHERHEITSUNTERGRENZE IST KEIN VERSPRECHEN. Die Mittelbahn soll
     * eintreten, die Untergrenze gerade NICHT. Sie faehrt als Kontext mit
     * und geht in kein Urteil ein.
     */
    @Test
    fun `die Sicherheitsuntergrenze faehrt als Kontext mit, nicht als Versprechen`() {
        val e = ExpectationLedger.issue(
            t0, SEG, 200.0, 150.0, H, CFG, REV,
            safetyLowerPredictedMgdl = 40.0, lambda = 1.0, discountMgdl = -110.8, bgiMgdl = -127.7,
        )!!
        assertEquals(50.0, e.promisedDropMgdl, 1e-9, "das Versprechen ist die MITTELBAHN")
        val (out, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 180.0)))
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(30.0, out[0].meanErrorMgdl!!, 1e-9, "gemessen gegen die Mittelbahn")
        assertEquals(140.0, out[0].distanceFromSafetyLowerMgdl!!, 1e-9, "reine Diagnose")
    }

    // ---- Abrechnen: ZEITLICHE ZUORDNUNG ----------------------------------

    @Test
    fun `vor der Faelligkeit wird nichts abgerechnet`() {
        val e = eintrag()
        val (out, offen) = rechne(listOf(e), t0 + 10 * 60_000L, listOf(probe(t0 + 10 * 60_000L, 190.0)))
        assertTrue(out.isEmpty())
        assertEquals(listOf(e), offen)
    }

    @Test
    fun `eine ausgebliebene Senkung wird als MISSED verbucht`() {
        val e = eintrag()
        val (out, offen) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)))
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(55.0, out[0].meanErrorMgdl!!, 1e-9)
        assertTrue(out[0].isEvidence)
        assertTrue(offen.isEmpty())
    }

    @Test
    fun `eine eingetroffene Senkung wird als MET verbucht`() {
        val e = eintrag()
        val (out, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 148.0)))
        assertEquals(ExpectationLedger.Verdict.MET, out[0].verdict)
        assertTrue(!out[0].isEvidence)
    }

    @Test
    fun `ein knappes Verfehlen gilt noch als eingetroffen`() {
        val e = eintrag()
        assertEquals(
            ExpectationLedger.Verdict.MET,
            rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 153.0))).first[0].verdict,
        )
        assertEquals(
            ExpectationLedger.Verdict.MISSED,
            rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 158.0))).first[0].verdict,
        )
    }

    /** Nach einer Luecke darf kein spaeterer Wert rueckwirkend gelten. */
    @Test
    fun `nach einer Luecke wird kein spaeterer Wert rueckwirkend verwendet`() {
        val e = listOf(eintrag(t0), eintrag(t0 + 5 * 60_000L), eintrag(t0 + 10 * 60_000L))
        val spaet = probe(t0 + 60 * 60_000L, 205.0)
        val (out, _) = rechne(e, t0 + 60 * 60_000L, listOf(spaet))
        assertTrue(
            out.all { it.verdict == ExpectationLedger.Verdict.UNVERIFIABLE },
            "keine darf am spaeten Wert abgerechnet werden: ${out.map { it.verdict }}",
        )
    }

    /**
     * EIN MESSWERT WIRD HOECHSTENS EINMAL VERBRAUCHT (Tonis Befund).
     *
     * Bei 1-min-Prognosen und 150 s Toleranz kann derselbe Punkt fuer bis zu
     * fuenf benachbarte Faelligkeiten der naechste Treffer sein - und wuerde
     * fuenf voneinander unabhaengige Widerlegungen erzeugen, die es nicht
     * gibt. Genau EINE darf ihn bekommen, die mit dem kleinsten Abstand.
     */
    @Test
    fun `ein Messwert bedient hoechstens eine Faelligkeit`() {
        // Drei Faelligkeiten im Minutenabstand, EIN Messwert bei der mittleren.
        val a = eintrag(t0)
        val b = eintrag(t0 + 60_000L)
        val c = eintrag(t0 + 120_000L)
        val einer = probe(b.dueTs, 205.0)
        val (out, _) = rechne(listOf(a, b, c), c.dueTs, listOf(einer))
        val bewertet = out.filter { it.actualMgdl != null }
        assertEquals(1, bewertet.size, "nur EINE Faelligkeit darf den Wert bekommen")
        assertEquals(b.dueTs, bewertet[0].entry.dueTs, "und zwar die naechstgelegene")
        assertEquals(
            2, out.count { it.verdict == ExpectationLedger.Verdict.UNVERIFIABLE },
            "die beiden anderen bleiben unbewertet",
        )
    }

    /** Ein Messwert aus einem ANDEREN Segment zaehlt nicht - ueber einen
     *  Bruch hinweg ist er nicht vergleichbar. */
    @Test
    fun `ein Messwert aus fremdem Segment zaehlt nicht`() {
        val e = eintrag(seg = 1L)
        val (out, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0, seg = 2L)))
        assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict)
    }

    /**
     * DIE GESUNDHEIT GEHOERT AN DEN MESSWERT, nicht an den
     * Abrechnungszeitpunkt: geprueft wird ein historischer Punkt.
     */
    @Test
    fun `ein ungesunder Messwert zaehlt nicht`() {
        val e = eintrag()
        val (out, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0, healthy = false)))
        assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict)
        assertNull(out[0].meanErrorMgdl)
    }

    @Test
    fun `die Zuordnung hat eine enge Toleranz`() {
        val e = eintrag()
        assertEquals(
            ExpectationLedger.Verdict.MISSED,
            rechne(listOf(e), e.dueTs + 5 * 60_000L, listOf(probe(e.dueTs + 60_000L, 205.0))).first[0].verdict,
            "eine Minute daneben ist zuordenbar",
        )
        assertEquals(
            ExpectationLedger.Verdict.UNVERIFIABLE,
            rechne(listOf(e), e.dueTs + 10 * 60_000L, listOf(probe(e.dueTs + 8 * 60_000L, 205.0))).first[0].verdict,
            "acht Minuten sind es nicht",
        )
    }

    // ---- Eingriffe -------------------------------------------------------

    /**
     * EIN EINGRIFF MACHT DIE PROGNOSE UNVERGLEICHBAR (Tonis dritter Befund),
     * und zwar in BEIDE Richtungen gefaehrlich: ein manueller Bolus koennte
     * ein MET erzeugen und einen echten Nachweis loeschen; Kohlenhydrate
     * koennten ein MISSED erzeugen und spaeter lambda lockern, obwohl das
     * Modell recht hatte.
     */
    @Test
    fun `ein Eingriff zwischen Ausgabe und Faelligkeit macht das Urteil ungueltig`() {
        val e = eintrag(rev = 100L)
        // Der BG steht auf 205 - ohne Eingriff waere das ein klares MISSED.
        val (missed, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)), rev = 100L)
        assertEquals(ExpectationLedger.Verdict.MISSED, missed[0].verdict)

        // Mit Eingriff: kein Urteil, obwohl die Zahlen dieselben sind.
        val (eingriff, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)), rev = 101L)
        assertEquals(ExpectationLedger.Verdict.INTERVENED, eingriff[0].verdict)
        assertNull(eingriff[0].actualMgdl, "ein ungueltiges Urteil traegt keine Zahl")
        assertTrue(!eingriff[0].isEvidence)
    }

    /** Auch die andere Richtung: ein Eingriff darf kein MET erzeugen und
     *  damit einen Nachweis loeschen. */
    @Test
    fun `ein Eingriff erzeugt auch kein MET`() {
        val e = eintrag(rev = 100L)
        val (out, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 140.0)), rev = 101L)
        assertEquals(ExpectationLedger.Verdict.INTERVENED, out[0].verdict)
    }

    // ---- Die Strecke: BELEGTE Dauer --------------------------------------

    private fun ergebnis(due: Long, v: ExpectationLedger.Verdict, seg: Long = SEG) =
        ExpectationLedger.Outcome(
            ExpectationLedger.Entry(due - H * 60_000L, due, seg, 200.0, 150.0, CFG, REV),
            v,
            if (v == ExpectationLedger.Verdict.MISSED || v == ExpectationLedger.Verdict.MET) due else null,
            if (v == ExpectationLedger.Verdict.MISSED || v == ExpectationLedger.Verdict.MET) 205.0 else null,
        )

    /** Zehn Prognosen im Minutentakt sind neun Minuten Strecke, nicht "zehn
     *  Widerlegungen". */
    @Test
    fun `gemessen wird die Dauer der Strecke, nicht die Anzahl`() {
        val m = ExpectationLedger.Verdict.MISSED
        val zehn = (0..9).map { ergebnis(t0 + it * 60_000L, m) }
        assertEquals(9, ExpectationLedger.missedStreakMin(zehn, SEG))
    }

    /**
     * UNBEOBACHTETE ZEIT IST KEIN BELEG (Tonis zweiter Befund) - und der
     * Vorgaengertest schrieb genau das Gegenteil fest ("zehn Ereignisse ueber
     * eine Stunde sind ein staerkerer Beleg").
     *
     * Zwei MISSED mit 58 Minuten Luecke dazwischen ergaben dort eine
     * 60-Minuten-Strecke. Ohne belegte Zwischenzeit stimmt das nicht.
     */
    @Test
    fun `eine Luecke zwischen zwei Ausbleibern bricht die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val mitLuecke = listOf(ergebnis(t0, m), ergebnis(t0 + 58 * 60_000L, m))
        assertEquals(
            0, ExpectationLedger.missedStreakMin(mitLuecke, SEG),
            "58 unbeobachtete Minuten sind keine 58 Minuten Nachweis",
        )
        // Dieselben zwei Punkte, aber lueckenlos belegt: das zaehlt.
        val dicht = (0..58).map { ergebnis(t0 + it * 60_000L, m) }
        assertEquals(58, ExpectationLedger.missedStreakMin(dicht, SEG))
    }

    /** Ein Eintreffen beendet die Strecke - der Nachweis beginnt von vorn. */
    @Test
    fun `ein Eintreffen beendet die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val t = ExpectationLedger.Verdict.MET
        val reihe = listOf(
            ergebnis(t0, m), ergebnis(t0 + 60_000, m),
            ergebnis(t0 + 120_000, t),
            ergebnis(t0 + 180_000, m), ergebnis(t0 + 240_000, m),
        )
        assertEquals(1, ExpectationLedger.missedStreakMin(reihe, SEG), "nur die beiden juengsten")
        assertEquals(0, ExpectationLedger.missedStreakMin(listOf(ergebnis(t0, t)), SEG))
        assertEquals(0, ExpectationLedger.missedStreakMin(emptyList(), SEG))
    }

    /** Ein Segmentbruch beendet sie ebenfalls. */
    @Test
    fun `ein Segmentbruch beendet die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val reihe = listOf(
            ergebnis(t0, m, seg = 1L), ergebnis(t0 + 60_000, m, seg = 1L),
            ergebnis(t0 + 120_000, m, seg = 2L), ergebnis(t0 + 180_000, m, seg = 2L),
        )
        assertEquals(1, ExpectationLedger.missedStreakMin(reihe, currentSegmentId = 2L))
        assertEquals(0, ExpectationLedger.missedStreakMin(reihe, currentSegmentId = 3L))
    }

    /**
     * UNVERIFIABLE UND INTERVENED BRECHEN JETZT EBENFALLS - konservativ.
     * Beide bedeuten, dass der Nachweis an dieser Stelle nicht gefuehrt
     * wurde, und ein nicht gefuehrter Nachweis darf keine Strecke
     * ueberbruecken.
     */
    @Test
    fun `nicht gefuehrte Nachweise brechen die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        for (luecke in listOf(ExpectationLedger.Verdict.UNVERIFIABLE, ExpectationLedger.Verdict.INTERVENED)) {
            val reihe = listOf(ergebnis(t0, m), ergebnis(t0 + 60_000, luecke), ergebnis(t0 + 120_000, m))
            assertEquals(
                0, ExpectationLedger.missedStreakMin(reihe, SEG),
                "$luecke darf nicht ueberbrueckt werden",
            )
        }
    }
}
