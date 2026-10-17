package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.BgiAdjustedSeries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE GANZE KETTE, nicht nur ihr letztes Glied (Tonis Punkt 3, 12.08.).
 *
 *     q1 + Aktivitaet/ISF -> BgiAdjustedSeries.adjust -> AdjustedInterval
 *                         -> EvidenceStock
 *
 * Die bisherigen Algebra-Tests begannen mit einer FERTIG bereinigten Reihe.
 * Sie pruefen damit den Vertrag des Bestands korrekt - aber nicht, ob die
 * Bereinigung ueberhaupt das richtige Delta liefert. Lieferte sie ein
 * falsches, verbuchte der Kern es vollkommen regelkonform, und kein Test
 * haette widersprochen.
 *
 * Hier wird deshalb aus ROHEN Groessen gerechnet: Glukose, Aktivitaet, ISF.
 */
class EvidenceChainTest {

    private val T0 = 1_700_000_000_000L
    private val ISF = 90.0
    private val CFG = EvidenceStock.Config()

    private fun probe(minute: Int, q1: Double, activity: Double) =
        BgiAdjustedSeries.Sample(T0 + minute * 60_000L, q1, activity, ISF)

    /**
     * Ein Zyklus der echten Kette: aus den Rohwerten die bereinigte Reihe, aus
     * ihr das Intervall, daraus den Bestand.
     *
     * @param fenster welcher Ausschnitt der Reihe diesem Zyklus vorliegt - so
     *   entsteht der WANDERNDE Fensteranfang.
     */
    private fun zyklus(
        proben: List<BgiAdjustedSeries.Sample>,
        fenster: IntRange,
        prev: EvidenceStock.State,
        committedU: Double = 0.0,
    ): EvidenceStock.Result {
        val ausschnitt = proben.slice(fenster)
        val bereinigt = BgiAdjustedSeries.adjust(ausschnitt)
        val letzte = ausschnitt.last()
        return EvidenceStock.step(
            prev,
            EvidenceStock.Input(
                nowMs = letzte.sourceTs,
                sourceTs = letzte.sourceTs,
                interval = BgiAdjustedSeries.AdjustedInterval.of(bereinigt, prev.lastAcceptedTs),
                driveLowerMgdlPerMin = 1.0,
                healthReady = true,
                measuredLow = false,
                creditRevoked = false,
                episodeId = 1L,
                episodeCommittedU = committedU,
                isfMgdlPerU = ISF,
                persistedStateKnown = true,
            ),
            CFG,
        )
    }

    /**
     * DER WANDERNDE FENSTERANFANG - der Fehler vom 12.08. in seiner ECHTEN
     * Gestalt (Tonis Punkt 1).
     *
     * Die konstante Verschiebung der ganzen Reihe beweist nur die
     * mathematische Baseline-Invarianz. Hier wandert das Fenster wirklich:
     * jeder Zyklus sieht einen anderen Ausschnitt, `adjust()` setzt
     * `cumulativeBgi` jedesmal am neuen Anfang auf 0, und die absoluten
     * `adjusted`-Werte desselben Zeitpunkts unterscheiden sich zwischen den
     * Zyklen. Der verbuchte Zufluss darf das nicht sehen.
     */
    @Test
    fun `ein wanderndes Fenster aendert den verbuchten Zufluss nicht`() {
        // Aktivitaet ungleich null, damit cumulativeBgi ueberhaupt waechst -
        // sonst waere der Fensteranfang folgenlos und der Test leer.
        val proben = (0..10).map { probe(it, 100.0 + 3.0 * it, activity = 0.02) }

        // GEGENPROBE ZUERST: die absoluten Werte desselben Punkts SIND
        // verschieden, je nachdem wo das Fenster beginnt.
        val ausA = BgiAdjustedSeries.adjust(proben.slice(0..5)).points.last().adjusted
        val ausB = BgiAdjustedSeries.adjust(proben.slice(2..5)).points.last().adjusted
        assertTrue(kotlin.math.abs(ausA - ausB) > 1.0) {
            "ohne wandernden Nullpunkt prueft dieser Test nichts: $ausA vs $ausB"
        }

        // Und jetzt die Kette ueber fuenf Zyklen mit gleitendem Fenster.
        var st = EvidenceStock.State(episodeId = 1L)
        var summe = 0.0
        for (i in 3..7) {
            val r = zyklus(proben, (i - 3)..i, st)
            summe += r.inflowMgdl
            st = r.state
        }

        // Von Minute 3 bis 7 steigt q1 um 12 mg/dl; die Aktivitaet ist
        // konstant, ihre Wirkung wird herausgerechnet. Der SUMMIERTE Zufluss
        // muss der bereinigte Anstieg ueber dieselbe Spanne sein.
        val ganze = BgiAdjustedSeries.adjust(proben)
        val erwartet = ganze.points.first { it.sourceTs == T0 + 7 * 60_000L }.adjusted -
            ganze.points.first { it.sourceTs == T0 + 3 * 60_000L }.adjusted
        assertEquals(erwartet, summe, 1e-9) {
            "der Zufluss haengt am wandernden Fensteranfang"
        }
    }

    /**
     * INSULINWIRKUNG OHNE STOERUNG - aus rohen Groessen.
     *
     * Der Zucker faellt allein durch Insulin. `dq1 = -Insulinwirkung`, die
     * Bereinigung addiert sie zurueck, das bereinigte Delta ist 0 - und der
     * Bestand bleibt leer, obwohl q1 deutlich sinkt.
     */
    @Test
    fun `fallender Zucker ohne Stoerung erzeugt keinen Bestand`() {
        // 0,02 U/min Aktivitaet bei ISF 90 = 1,8 mg/dl/min Abfall.
        val proben = (0..8).map { probe(it, 180.0 - 1.8 * it, activity = 0.02) }

        var st = EvidenceStock.State(episodeId = 1L)
        for (i in 1..8) {
            val r = zyklus(proben, 0..i, st)
            st = r.state
            assertEquals(0.0, r.inflowMgdl, 1e-9) { "Minute $i: reine Insulinwirkung ist keine Stoerung" }
        }
        assertEquals(0.0, st.stockMgdl, 1e-9)
    }

    /**
     * STOERUNG PLUS KOMPENSIERENDES INSULIN - aus rohen Groessen.
     *
     * q1 steht still, weil Mahlzeit und Insulin sich aufheben. Die bereinigte
     * Reihe steigt trotzdem, und zwar um genau die Stoerung: 1,8 mg/dl je
     * Minute. Das ist die Aussage, auf der der ganze Bestand beruht - hier
     * einmal ohne sie vorauszusetzen.
     */
    @Test
    fun `stillstehender Zucker bei laufendem Insulin liefert die Stoerung`() {
        val proben = (0..5).map { probe(it, 150.0, activity = 0.02) }

        var st = EvidenceStock.State(episodeId = 1L)
        var summe = 0.0
        for (i in 1..5) {
            val r = zyklus(proben, 0..i, st)
            summe += r.inflowMgdl
            st = r.state
        }
        // VIER Intervalle x 1,8 mg/dl/min, nicht fuenf: der erste Zyklus hat
        // keinen Anker und setzt nur die Basis. Das ist dieselbe Regel wie
        // "der erste Punkt liefert nur den Bezug" - hier faellt sie auf, weil
        // die Kette von rohen Werten aus rechnet.
        assertEquals(4 * 1.8, summe, 1e-9)
        // Und die verdeckte Stoerung ist da, obwohl q1 keine Minute lang
        // gestiegen ist.
        assertTrue(summe > 0.0) { "sonst waere die Bereinigung wirkungslos" }
    }

    /** Die Fabrik ist der einzige Weg - und sie verweigert, was sie nicht
     *  belegen kann. */
    @Test
    fun `ohne Anker in der Ausgabe entsteht kein Intervall`() {
        val bereinigt = BgiAdjustedSeries.adjust((3..6).map { probe(it, 100.0, 0.0) })
        assertNull(BgiAdjustedSeries.AdjustedInterval.of(bereinigt, T0))
        assertNull(BgiAdjustedSeries.AdjustedInterval.of(BgiAdjustedSeries.adjust(emptyList()), T0))
        // Anker IST der juengste Punkt: kein Zuwachs, kein Intervall.
        assertNull(BgiAdjustedSeries.AdjustedInterval.of(bereinigt, T0 + 6 * 60_000L))
    }
}
