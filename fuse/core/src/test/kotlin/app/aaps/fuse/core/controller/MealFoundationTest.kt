package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE BAUVERTRAEGE DES MAHLZEITENFUNDAMENTS (Toni 18.08.).
 *
 * Gerechnet wird durchgehend mit seinem Replay-Kandidaten:
 *
 *     Budget 3,00 U | Phase A 75 % bis T+15 | Phase B 25 % bis T+60
 *     Pumpenschritt 0,05 U  ->  15 Schritte in 45 min, etwa einer je 3 min
 *
 * Das sind Replay-Hypothesen, keine Therapiewerte - die Tests halten die
 * MECHANIK fest, nicht die Zahlen.
 */
class MealFoundationTest {

    private val t0 = 1_700_000_000_000L
    private val BUDGET = 3.0
    private val A_SHARE = 0.75
    private val A_BIS = 15
    private val B_BIS = 60
    private val STEP = 0.05

    /** Phase-B-Budget: 25 % von 3,00 U. */
    private val B_BUDGET = BUDGET * (1.0 - A_SHARE)

    private fun plan(
        minuten: Double,
        geflossenU: Double,
        ausPhaseBU: Double = 0.0,
        step: Double = STEP,
    ) = MealFoundation.plan(
        markerTs = t0,
        nowTs = t0 + (minuten * 60_000).toLong(),
        totalBudgetU = BUDGET,
        phaseAShare = A_SHARE,
        phaseAUntilMin = A_BIS,
        phaseBUntilMin = B_BIS,
        deliveredFromBudgetU = geflossenU,
        deliveredFromPhaseBU = ausPhaseBU,
        bolusStepU = step,
    )

    // ---- Das Fenster -----------------------------------------------------

    @Test
    fun `vor der Uebergabe gibt das Fundament nichts frei`() {
        val p = plan(minuten = 10.0, geflossenU = 2.25)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.BEFORE_WINDOW, p.binding)
        assertEquals(B_BUDGET, p.remainingInWindowU, 1e-9, "aber das Budget steht bereit")
    }

    /**
     * NACH FENSTERENDE VERFAELLT DER REST - Tonis Auflage.
     *
     * Kein Nachliefern Stunden spaeter: was bis T+60 nicht gebraucht wurde,
     * war offenbar nicht noetig. Ein spaeter Nachschlag traefe eine Absorption,
     * die es nicht mehr gibt.
     */
    @Test
    fun `nach Fensterende verfaellt der Rest`() {
        val p = plan(minuten = 61.0, geflossenU = 2.25)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.AFTER_WINDOW, p.binding)
        assertTrue(p.remainingInWindowU > 0.0, "der Verfall ist sichtbar, nicht stillschweigend")
    }

    // ---- Die Verteilung --------------------------------------------------

    /**
     * DAS SOLL WAECHST LINEAR ueber das Fenster.
     *
     * Bei T+30 ist ein Drittel der 45 Minuten vorbei - also ein Drittel von
     * 0,75 U, das sind 0,25 U.
     */
    @Test
    fun `das Soll waechst linear ueber das Fenster`() {
        assertEquals(0.0, plan(15.0, 2.25).plannedTotalU, 1e-9, "bei T+15 noch nichts")
        assertEquals(0.25, plan(30.0, 2.25).plannedTotalU, 1e-9, "bei T+30 ein Drittel")
        assertEquals(B_BUDGET, plan(60.0, 2.25).plannedTotalU, 1e-9, "bei T+60 alles")
    }

    /** Ein Rueckstand von mindestens einem Pumpenschritt wird freigegeben. */
    @Test
    fun `bei Rueckstand kommt ein Pumpenschritt`() {
        // T+18: Soll = 0,75 * 3/45 = 0,05 U - genau ein Schritt.
        val p = plan(minuten = 18.0, geflossenU = 2.25)
        assertEquals(STEP, p.dueU, 1e-9)
    }

    /** Unter einem Schritt wird nichts freigegeben - der Plan ist erfuellt. */
    @Test
    fun `ohne vollen Schritt bleibt es beim Plan`() {
        // T+16: Soll = 0,75 * 1/45 = 0,0167 U - weniger als ein Schritt.
        val p = plan(minuten = 16.0, geflossenU = 2.25)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.ON_SCHEDULE, p.binding)
    }

    /**
     * KEIN AUFHOL-BURST - Tonis Auflage, und der Kern der ganzen Bauform.
     *
     * Ein Rueckstand von mehreren Schritten entsteht, wenn Zyklen ausfielen
     * oder Schritte abgelehnt wurden. Ihn in einem Zug nachzuholen waere genau
     * die IOB-Spitze, die das Fundament vermeiden soll - dann waere die
     * Verteilung sinnlos geworden.
     */
    @Test
    fun `ein grosser Rueckstand kommt trotzdem nur als EIN Schritt`() {
        // T+50: Soll = 0,75 * 35/45 = 0,583 U. Geflossen ist nur Phase A -
        // der Rueckstand betraegt also fast zwoelf Schritte.
        val p = plan(minuten = 50.0, geflossenU = 2.25)
        assertEquals(STEP, p.dueU, 1e-9, "genau EIN Schritt, nicht der ganze Rueckstand")
        assertEquals(MealFoundation.Binding.ONE_STEP_PER_CYCLE, p.binding)
        assertTrue(p.plannedTotalU > 0.5, "obwohl das Soll deutlich hoeher liegt: ${p.plannedTotalU}")
    }

    /** Und der Rueckstand LAEUFT NACH: der naechste Zyklus gibt wieder einen
     *  Schritt, solange das Fenster offen ist. */
    @Test
    fun `der Rueckstand laeuft im Fenster nach`() {
        var geflossen = 2.25
        var ausB = 0.0
        var schritte = 0
        // Von T+18 bis T+60 in Minutenschritten - wie im echten Zyklus. BEIDE
        // Zahlen wachsen mit: der Gesamtverbrauch und der von Phase B.
        for (m in 18..60) {
            val p = plan(minuten = m.toDouble(), geflossenU = geflossen, ausPhaseBU = ausB)
            if (p.dueU > 0.0) {
                geflossen += p.dueU
                ausB += p.dueU
                schritte++
            }
        }
        assertEquals(
            B_BUDGET, geflossen - 2.25, 1e-9,
            "ueber das Fenster wird Phase B vollstaendig ausgeliefert",
        )
        assertEquals(15, schritte, "in 15 Schritten von 0,05 U")
    }

    // ---- Das gemeinsame Budget ------------------------------------------

    /**
     * PHASE A UND B ZUSAMMEN NIEMALS UEBER DAS BUDGET - Tonis Auflage und
     * Spezifikation 3.1.
     *
     * Das Fundament eroeffnet keinen zweiten Topf. Hat Phase A mehr verbraucht
     * als vorgesehen, schrumpft Phase B entsprechend - nicht umgekehrt.
     */
    @Test
    fun `ein ausgeschoepftes Budget sperrt das Fundament`() {
        val p = plan(minuten = 40.0, geflossenU = BUDGET)
        assertEquals(0.0, p.dueU, 1e-9)
        assertEquals(MealFoundation.Binding.BUDGET_EXHAUSTED, p.binding)
        assertEquals(0.0, p.remainingInWindowU, 1e-9)
    }

    /**
     * HAT PHASE A MEHR GENOMMEN, BLEIBT FUER B WENIGER.
     *
     * Der Fall ist real: die Huelle darf im Fruehfenster bis zum Deckel
     * freigeben, und wenn sie 2,60 U statt 2,25 U verbraucht hat, sind das
     * 0,35 U weniger fuer das Fundament. Andernfalls waere das Gesamtbudget
     * ueberschritten.
     */
    @Test
    fun `mehr Verbrauch in Phase A verkleinert Phase B`() {
        val p = plan(minuten = 60.0, geflossenU = 2.60)
        assertEquals(
            BUDGET - 2.60, p.remainingInWindowU, 1e-9,
            "nur noch der Rest bis zum gemeinsamen Budget",
        )
    }

    /**
     * DAS OFFENE BUDGET IST DAS VON PHASE B, nicht das des Gesamtbudgets.
     *
     * Bei unverbrauchtem Phase-A-Budget waeren `totalBudget - geflossen` volle
     * 3,00 U - Phase B darf davon aber nur ihren Anteil sehen. Die Begrenzung
     * wirkt zwar auch ueber das Soll, aber `remainingInWindowU` geht in den
     * Export und in die Liveness-Pruefung: eine dort gemeldete Restmenge, die
     * das Fundament nie ausliefern darf, waere eine falsche Aussage ueber die
     * verbleibende Versorgung.
     *
     * Eine Mutationsprobe, die diese Grenze durch die Gesamtgrenze ersetzt,
     * blieb ohne diesen Test gruen.
     */
    @Test
    fun `das offene Budget ist auf den Phase-B-Anteil begrenzt`() {
        val p = plan(minuten = 30.0, geflossenU = 0.0, ausPhaseBU = 0.0)
        assertEquals(
            B_BUDGET, p.remainingInWindowU, 1e-9,
            "0,75 U - nicht die 3,00 U des Gesamtbudgets",
        )
    }

    /** Die Summe kann das Budget in keinem Verlauf ueberschreiten. */
    @Test
    fun `die Summe bleibt in jedem Verlauf unter dem Budget`() {
        for (startA in listOf(0.0, 1.0, 2.25, 2.9, 3.0)) {
            var geflossen = startA
            var ausB = 0.0
            for (m in 15..70) {
                val p = plan(minuten = m.toDouble(), geflossenU = geflossen, ausPhaseBU = ausB)
                geflossen += p.dueU
                ausB += p.dueU
            }
            assertTrue(
                geflossen <= BUDGET + 1e-9,
                "Start $startA endete bei $geflossen - das Budget ist $BUDGET",
            )
        }
    }

    // ---- Fail-closed -----------------------------------------------------

    /**
     * JEDE UNBRAUCHBARE EINGABE ERGIBT NICHTS.
     *
     * Ein Fundament, das auf NaN oder einer unsinnigen Fensterreihenfolge
     * dosiert, waere gefaehrlicher als eines, das schweigt. Kein Default, der
     * "wahrscheinlich passt".
     */
    @Test
    fun `unbrauchbare Eingaben ergeben keinen Vorschlag`() {
        val faelle = listOf(
            "kein Marker" to MealFoundation.plan(0L, t0, BUDGET, A_SHARE, A_BIS, B_BIS, 0.0, 0.0, STEP),
            "jetzt vor Marker" to MealFoundation.plan(t0, t0 - 1000L, BUDGET, A_SHARE, A_BIS, B_BIS, 0.0, 0.0, STEP),
            "Budget NaN" to MealFoundation.plan(t0, t0, Double.NaN, A_SHARE, A_BIS, B_BIS, 0.0, 0.0, STEP),
            "Budget 0" to MealFoundation.plan(t0, t0, 0.0, A_SHARE, A_BIS, B_BIS, 0.0, 0.0, STEP),
            "Anteil ueber 1" to MealFoundation.plan(t0, t0, BUDGET, 1.5, A_BIS, B_BIS, 0.0, 0.0, STEP),
            "Anteil NaN" to MealFoundation.plan(t0, t0, BUDGET, Double.NaN, A_BIS, B_BIS, 0.0, 0.0, STEP),
            "Fenster verdreht" to MealFoundation.plan(t0, t0, BUDGET, A_SHARE, 60, 15, 0.0, 0.0, STEP),
            "Schritt 0" to MealFoundation.plan(t0, t0, BUDGET, A_SHARE, A_BIS, B_BIS, 0.0, 0.0, 0.0),
            "Schritt NaN" to MealFoundation.plan(t0, t0, BUDGET, A_SHARE, A_BIS, B_BIS, 0.0, 0.0, Double.NaN),
            "geflossen negativ" to MealFoundation.plan(t0, t0, BUDGET, A_SHARE, A_BIS, B_BIS, -1.0, 0.0, STEP),
            "Phase-B negativ" to MealFoundation.plan(t0, t0, BUDGET, A_SHARE, A_BIS, B_BIS, 0.0, -1.0, STEP),
        )
        for ((name, p) in faelle) {
            assertEquals(0.0, p.dueU, 1e-9, name)
            assertEquals(MealFoundation.Binding.UNUSABLE_INPUT, p.binding, name)
        }
    }

    /**
     * DER HEUTIGE STAND BLEIBT ERREICHBAR: Anteil 1,0 heisst kein Fundament.
     *
     * Das ist der Default und der Vergleichsfall im Replay (100/0). Bliebe
     * hier ein Rest uebrig, waere das Einschalten des Schalters allein schon
     * eine Verhaltensaenderung.
     */
    @Test
    fun `bei Anteil eins gibt es keine Phase B`() {
        for (m in listOf(0.0, 15.0, 30.0, 60.0, 90.0)) {
            val p = MealFoundation.plan(
                t0, t0 + (m * 60_000).toLong(), BUDGET,
                phaseAShare = 1.0, phaseAUntilMin = A_BIS, phaseBUntilMin = B_BIS,
                deliveredFromBudgetU = 3.0, deliveredFromPhaseBU = 0.0, bolusStepU = STEP,
            )
            assertEquals(0.0, p.dueU, 1e-9, "bei T+$m")
        }
    }

    /** Und ein Verlauf ohne jeden Verbrauch liefert am Ende genau das
     *  Phase-B-Budget - nicht mehr, nicht weniger. */
    @Test
    fun `ohne Phase-A-Verbrauch bleibt Phase B trotzdem bei seinem Anteil`() {
        var geflossen = 0.0
        var ausB = 0.0
        for (m in 15..60) {
            val d = plan(m.toDouble(), geflossen, ausB).dueU
            geflossen += d
            ausB += d
        }
        assertEquals(
            B_BUDGET, geflossen, 1e-9,
            "das Fundament nimmt sich NICHT das ungenutzte Phase-A-Budget",
        )
        assertNull(plan(60.0, geflossen).binding.takeIf { it == MealFoundation.Binding.UNUSABLE_INPUT })
    }
}
