package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * DIE REIFEBEDINGUNG ALS INJIZIERTE POLITIK (Bauauftrag Toni 25.08.
 * abends, dosierneutraler Reife-Replay).
 *
 * Gemessen war: nach einer echten CGM-Luecke bleiben ~5-6 Minuten blind,
 * und diese Zeit haengt NICHT von der Lueckenlaenge ab - sie ist die
 * Theil-Sen-Reifebedingung. Diese Klasse macht sie zur Variante, ohne
 * die gelockten Produktionskonstanten anzufassen.
 */
class MaturityPolicyTest {

    private val t0 = 1_700_000_000_000L

    /** n Punkte im Minutentakt auf einer sauberen Rampe von +1/min. */
    private fun punkte(n: Int) = (0 until n).map {
        BgiAdjustedSeries.AdjustedPoint(t0 + it * 60_000L, 100.0 + it)
    }

    @Test
    fun `die produktion ist unveraendert und kommt aus den gelockten konstanten`() {
        assertEquals(5, MaturityPolicy.PRODUCTION.minPoints)
        assertEquals(8, MaturityPolicy.PRODUCTION.minSlopes)
        assertEquals(BgiAdjustedSeries.MIN_POINTS, MaturityPolicy.PRODUCTION.minPoints)
        assertEquals(BgiAdjustedSeries.MIN_SLOPES, MaturityPolicy.PRODUCTION.minSlopes)
        // Die Produktion ist EIN Objekt - kein Aufruf erzeugt eine zweite
        // Instanz, an der jemand vorbeischreiben koennte.
        assertSame(MaturityPolicy.PRODUCTION, MaturityPolicy.of(-1, -1))
        assertSame(MaturityPolicy.PRODUCTION, MaturityPolicy.parse(null))
    }

    /**
     * DIE TABELLE, aus der Tonis vier Varianten stammen: n Punkte ergeben
     * bei 1-min-Kadenz (n-1)(n-2)/2 Paare mit dt >= 2 min. Sie steht hier
     * als PRUEFUNG, nicht als Kommentar - eine Verschiebung der
     * Paarschranke wuerde sie sofort rot faerben.
     */
    /**
     * DIE KADENZ IST NICHT EXAKT EINE MINUTE.
     *
     * Gemessen am Geraet (Zyklenfolge auf die Sekunde): die Abstaende
     * schwanken zwischen 58 und 62 s. Drei solche Punkte spannen 117 s und
     * VERFEHLEN die 120-s-Paarschranke um drei Sekunden. Vor diesem
     * Kandidaten kostete das jede Variante bis zu einen Punkt mehr als die
     * Tabelle in [MaturityPolicy].
     *
     * CANDIDATE (cand/blind-anstieg): a pair that misses 120 s by
     * at most [BgiAdjustedSeries.PAIR_DT_JITTER_MS] counts, but only when the
     * strict pairs are too few. The real cadence now needs the points of the
     * table - and a series the strict rule already accepts keeps its list.
     */
    @Test
    fun `bei 59-sekunden-kadenz zaehlen knapp verfehlte paare nur wenn strenge fehlen`() {
        fun echt(n: Int) = (0 until n).map {
            // Die gemessene Folge des Geraets, auf die Sekunde.
            val versatz = listOf(0, 58, 117, 176, 237, 296, 355)[it]
            BgiAdjustedSeries.AdjustedPoint(t0 + versatz * 1000L, 100.0 + it)
        }
        val p31 = MaturityPolicy.of(3, 1)
        // Bei exakt 60 s reichen drei Punkte ...
        assertNotNull(BgiAdjustedSeries.theilSen(punkte(3), punkte(3).last().sourceTs, p31))
        // ... und jetzt auch bei der echten Kadenz: the 117-s pair counts (it was null before the candidate).
        assertNotNull(
            BgiAdjustedSeries.theilSen(echt(3), echt(3).last().sourceTs, p31),
            "117 s is only clock jitter away from 120 s",
        )
        // 4x3: four real points carry one strict pair (176 s) and two jitter pairs (117 s, 118 s) - three pairs,
        // the same as at exact 60 s (before the candidate: null, it needed a fifth point).
        val p43 = MaturityPolicy.of(4, 3)
        val vier = BgiAdjustedSeries.pairSlopes(echt(4), echt(4).last().sourceTs, maturity = p43)
        assertNotNull(vier)
        assertEquals(3, vier!!.size)
        // The production 5x8 does not move with this cadence: five points stay blind (4 strict + 2 jitter < 8) ...
        assertNull(BgiAdjustedSeries.theilSen(echt(5), echt(5).last().sourceTs))
        // ... and six points are mature on their 8 strict pairs alone - the 117-s and 118-s pairs stay out.
        val sechs = BgiAdjustedSeries.pairSlopes(echt(6), echt(6).last().sourceTs)
        assertEquals(8, sechs!!.size)
    }

    /**
     * Pairs more than [BgiAdjustedSeries.PAIR_DT_JITTER_MS] short of 120 s never count - also not when the
     * strict pairs are too few. Neighbour pairs (about 60 s) are far away from that.
     */
    @Test
    fun `paare unter der zittergrenze zaehlen nie`() {
        val p31 = MaturityPolicy.of(3, 1)
        fun drei(spanS: Long) = listOf(0L, spanS / 2, spanS).mapIndexed { i, s ->
            BgiAdjustedSeries.AdjustedPoint(t0 + s * 1000L, 100.0 + i)
        }
        // 115 s is the edge and counts; 114 s does not.
        assertNotNull(BgiAdjustedSeries.theilSen(drei(115), drei(115).last().sourceTs, p31))
        assertNull(BgiAdjustedSeries.theilSen(drei(114), drei(114).last().sourceTs, p31))
        assertEquals(
            SignalReadiness.Reason.TOO_FEW_SLOPES,
            BgiAdjustedSeries.readiness(drei(114), drei(114).last().sourceTs, maturity = p31).reason,
        )
    }

    /**
     * THE TWO PROMISES OF THE CANDIDATE, on many random series in the real cadence (55-65 s, with short and long
     * gaps):
     *  1. bit-identical: whenever the old strict rule gives a list, the new rule gives the SAME list;
     *  2. one counting rule: [BgiAdjustedSeries.readiness] says READY exactly when [BgiAdjustedSeries.pairSlopes]
     *     gives a list, and its slope count is [BgiAdjustedSeries.usablePairCount].
     */
    @Test
    fun `strenge reife bleibt bitgleich und der reifestand zaehlt wie der schaetzer`() {
        /** The rule as it was before this candidate - copied here as the reference, on purpose. */
        fun alteRegel(points: List<BgiAdjustedSeries.AdjustedPoint>, nowTs: Long, m: MaturityPolicy): List<Double>? {
            val window = points.filter { nowTs - it.sourceTs <= BgiAdjustedSeries.WINDOW_MS && it.sourceTs <= nowTs }
            if (window.size < m.minPointsAt(nowTs)) return null
            val slopes = ArrayList<Double>()
            for (i in window.indices) for (j in i + 1 until window.size) {
                val dtMs = window[j].sourceTs - window[i].sourceTs
                if (dtMs >= BgiAdjustedSeries.PAIR_DT_MIN_MS) {
                    slopes.add((window[j].adjusted - window[i].adjusted) / (dtMs / 60_000.0))
                }
            }
            if (slopes.size < m.minSlopesAt(nowTs)) return null
            slopes.sort()
            return slopes
        }

        val zufall = Random(4711)
        val politiken = listOf(MaturityPolicy.PRODUCTION, MaturityPolicy.of(4, 3), MaturityPolicy.of(3, 1))
        var gleich = 0
        var neuReif = 0
        repeat(400) { lauf ->
            var t = t0
            val reihe = ArrayList<BgiAdjustedSeries.AdjustedPoint>()
            repeat(3 + zufall.nextInt(20)) { i ->
                reihe.add(BgiAdjustedSeries.AdjustedPoint(t, 100.0 + i * 1.7 + zufall.nextDouble() * 4.0))
                t += when (zufall.nextInt(12)) {
                    0    -> 120_000L + zufall.nextInt(120_000)   // a short CGM gap
                    else -> 55_000L + zufall.nextInt(10_001)     // the real cadence
                }
            }
            val jetzt = reihe.last().sourceTs
            for (m in politiken) {
                val alt = alteRegel(reihe, jetzt, m)
                val neu = BgiAdjustedSeries.pairSlopes(reihe, jetzt, maturity = m)
                if (alt != null) {
                    assertEquals(alt, neu, "run $lauf ${m.tag()}: a strictly mature series must keep its list")
                    gleich++
                } else if (neu != null) neuReif++
                val r = BgiAdjustedSeries.readiness(reihe, jetzt, maturity = m)
                assertEquals(neu != null, r.ready, "run $lauf ${m.tag()}: readiness and estimator must agree")
                val window = reihe.filter { jetzt - it.sourceTs <= BgiAdjustedSeries.WINDOW_MS }
                assertEquals(
                    BgiAdjustedSeries.usablePairCount(window.map { it.sourceTs }, m.minSlopesAt(jetzt)), r.slopes,
                    "run $lauf ${m.tag()}",
                )
                if (neu != null) assertEquals(r.slopes, neu.size, "run $lauf ${m.tag()}")
            }
        }
        // Both kinds of case really happened - otherwise the test would prove nothing.
        assertTrue(gleich > 100, "strictly mature cases: $gleich")
        assertTrue(neuReif > 10, "cases mature only by jitter pairs: $neuReif")
    }

    @Test
    fun `die vier varianten entsprechen 6 5 4 und 3 punkten bei 1-min-kadenz`() {
        val erwartet = listOf(
            MaturityPolicy.PRODUCTION to 6,          // 5x8  - effektiv 6 Punkte
            MaturityPolicy.of(5, 6) to 5,
            MaturityPolicy.of(4, 3) to 4,
            MaturityPolicy.of(3, 1) to 3,
        )
        for ((politik, n) in erwartet) {
            assertEquals(n, politik.effectivePointsAt1Min(),
                         "${politik.tag()} sollte $n Punkte brauchen")
            // Und die Rechnung stimmt mit dem Schaetzer ueberein: bei n-1
            // Punkten gibt es noch kein r, bei n schon.
            assertNull(
                BgiAdjustedSeries.theilSen(punkte(n - 1), punkte(n - 1).last().sourceTs, politik),
                "${politik.tag()}: ${n - 1} Punkte duerfen KEIN r ergeben",
            )
            assertNotNull(
                BgiAdjustedSeries.theilSen(punkte(n), punkte(n).last().sourceTs, politik),
                "${politik.tag()}: $n Punkte muessen ein r ergeben",
            )
        }
    }

    /**
     * DIE EIGENSCHAFT, AUF DER DIE GANZE AUSWERTUNG RUHT: die Politik
     * entscheidet nur, OB ein Wert herauskommt - nicht, aus welchen
     * Punkten. Sobald beide reif sind, ist der Median IDENTISCH.
     *
     * Ohne diese Eigenschaft waere jede Differenz im Replay mehrdeutig:
     * man koennte nicht unterscheiden, ob die aggressive Variante frueher
     * dosiert oder ANDERS rechnet.
     */
    @Test
    fun `ab gemeinsamer reife liefern alle politiken denselben wert`() {
        for (n in 6..14) {
            val p = punkte(n)
            val ref = BgiAdjustedSeries.theilSen(p, p.last().sourceTs, MaturityPolicy.PRODUCTION)
            assertNotNull(ref, "$n Punkte: die Produktion muss reif sein")
            for (tag in listOf("5x6", "4x3", "3x1")) {
                val v = BgiAdjustedSeries.theilSen(p, p.last().sourceTs, MaturityPolicy.parse(tag))
                assertEquals(ref, v, "bei $n Punkten muss $tag bitgleich zur Produktion sein")
            }
        }
    }

    /**
     * DIE PFLICHTPROBE (Toni): keine Politik leckt zwischen Laeufen. Zwei
     * Schaetzungen mit verschiedenen Politiken laufen ABWECHSELND auf
     * DERSELBEN Punktliste im selben Prozess, in beiden Reihenfolgen.
     *
     * Bei 4 Punkten ist die Produktion blind und 4x3 reif - waere die
     * Politik irgendwo geteilt, kippte genau hier eine der beiden Seiten.
     */
    @Test
    fun `zwei politiken stoeren sich im selben prozess nicht`() {
        val vier = punkte(4)
        val jetzt = vier.last().sourceTs
        val streng = MaturityPolicy.PRODUCTION
        val locker = MaturityPolicy.of(4, 3)
        repeat(20) {
            assertNull(BgiAdjustedSeries.theilSen(vier, jetzt, streng),
                       "die strenge Politik muss bei 4 Punkten blind bleiben (Durchgang $it)")
            assertNotNull(BgiAdjustedSeries.theilSen(vier, jetzt, locker),
                          "die lockere Politik muss bei 4 Punkten liefern (Durchgang $it)")
        }
        // Umgekehrte Reihenfolge - ein geteilter Zustand koennte sich
        // sonst hinter der Aufrufreihenfolge verstecken.
        repeat(20) {
            assertNotNull(BgiAdjustedSeries.theilSen(vier, jetzt, locker))
            assertNull(BgiAdjustedSeries.theilSen(vier, jetzt, streng))
        }
        // Und dasselbe fuer das eigentliche Tor: PairSlopeBand.estimate.
        // Sein `null` erzeugt im Runner den Abbruch "drive not estimable".
        repeat(20) {
            assertNull(PairSlopeBand.estimate(vier, jetzt, 50, maturity = streng))
            assertNotNull(PairSlopeBand.estimate(vier, jetzt, 50, maturity = locker))
        }
        // Nach alldem ist die Produktion unveraendert - der Vorgabewert
        // gilt weiterhin fuer jeden Aufruf ohne Argument.
        assertNull(BgiAdjustedSeries.theilSen(vier, jetzt))
        assertNull(PairSlopeBand.estimate(vier, jetzt, 50))
    }

    /**
     * DER REIFESTAND folgt der Politik mit - sonst zeigte die Anzeige
     * "5/6S" waehrend der Regler auf 8 wartet. Genau diesen Fehler macht
     * die alte `samplesUsed`-Zeile.
     */
    @Test
    fun `der reifestand nennt die schranken der jeweiligen politik`() {
        val f = punkte(5)
        val streng = BgiAdjustedSeries.readiness(f, f.last().sourceTs)
        assertEquals(SignalReadiness.Reason.TOO_FEW_SLOPES, streng.reason)
        assertEquals(8, streng.slopesRequired)
        assertTrue(streng.shortText()!!.contains("6/8S"))

        val locker = BgiAdjustedSeries.readiness(
            f, f.last().sourceTs, maturity = MaturityPolicy.of(5, 6),
        )
        assertEquals(SignalReadiness.Reason.READY, locker.reason)
        assertEquals(6, locker.slopesRequired)
        assertNull(locker.shortText(), "reif heisst: keine Zeile")

        // Und bei 4 Punkten bindet unter 4x3 nichts mehr, unter der
        // Produktion die PUNKT-Schranke.
        val vier = punkte(4)
        assertEquals(
            SignalReadiness.Reason.TOO_FEW_POINTS,
            BgiAdjustedSeries.readiness(vier, vier.last().sourceTs).reason,
        )
        assertEquals(
            SignalReadiness.Reason.READY,
            BgiAdjustedSeries.readiness(
                vier, vier.last().sourceTs, maturity = MaturityPolicy.of(4, 3),
            ).reason,
        )
    }

    @Test
    fun `unbrauchbare werte ergeben die produktion`() {
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.of(1, 1))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.of(3, 0))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.of(0, 0))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.of(-3, 5))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.of(MaturityPolicy.MAX_POINTS + 1, 5))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.of(5, MaturityPolicy.MAX_SLOPES + 1))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.parse("bloedsinn"))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.parse("5"))
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.parse("5x8x3"))
        // Die Raender selbst sind gueltig.
        assertEquals(2, MaturityPolicy.of(2, 1).minPoints)
        assertEquals(1, MaturityPolicy.of(2, 1).minSlopes)
        // Wertsemantik: zwei gleiche Politiken sind gleich, damit ein
        // Vergleich im Trail nicht auf Identitaet hereinfaellt.
        assertEquals(MaturityPolicy.of(4, 3), MaturityPolicy.parse("4x3"))
        assertEquals(MaturityPolicy.of(4, 3).hashCode(), MaturityPolicy.parse("4x3").hashCode())
        assertEquals("4x3", MaturityPolicy.of(4, 3).tag())
    }

    /**
     * DIE VORLAUFSPERRE - gegen ein GEMESSENES Replay-Artefakt.
     *
     * Ein Replay startet kalt: in den ersten Minuten seines Fensters ist
     * die Referenz blind, weil noch keine Reihe da ist, nicht weil das
     * Geraet blind war. In einem Replay-Fall dosierte eine aggressive Variante
     * genau dort mehrfach - rund die Haelfte der gemessenen "Mehrmenge" - und
     * verschob ueber Ledger und Deckel auch spaetere Zyklen.
     */
    @Test
    fun `vor dem vorlauf gilt die produktion, danach die variante`() {
        val abTs = t0 + 20 * 60_000L
        val p = MaturityPolicy.of(3, 1, abTs)
        assertEquals(5, p.minPointsAt(abTs - 1), "eine ms vorher: Produktion")
        assertEquals(8, p.minSlopesAt(abTs - 1))
        assertEquals(3, p.minPointsAt(abTs), "ab der Kante: die Variante")
        assertEquals(1, p.minSlopesAt(abTs))

        // Und das wirkt bis in den Schaetzer: dieselben vier Punkte sind
        // vor der Kante blind und dahinter reif.
        fun vier(ab: Long) = (0 until 4).map {
            BgiAdjustedSeries.AdjustedPoint(ab + it * 60_000L, 100.0 + it)
        }
        val frueh = vier(t0)
        assertNull(BgiAdjustedSeries.theilSen(frueh, frueh.last().sourceTs, p),
                   "im Vorlauf muss auch 3x1 blind bleiben")
        val spaet = vier(abTs)
        assertNotNull(BgiAdjustedSeries.theilSen(spaet, spaet.last().sourceTs, p),
                      "nach dem Vorlauf muss 3x1 liefern")
        // Die Produktion ist von der Kante voellig unberuehrt.
        assertNull(BgiAdjustedSeries.theilSen(spaet, spaet.last().sourceTs))
        // Ohne Kante (0L) gilt die Variante immer - so laeuft ein Test,
        // der die Politik direkt prueft.
        assertEquals(3, MaturityPolicy.of(3, 1).minPointsAt(0L))
        // Eine negative Kante ist unbrauchbar und ergibt die Produktion.
        assertEquals(MaturityPolicy.PRODUCTION, MaturityPolicy.of(3, 1, -1L))
        // Die Kante gehoert zur Identitaet: zwei Politiken mit gleichen
        // Schranken, aber verschiedener Kante sind NICHT dieselbe.
        assertTrue(MaturityPolicy.of(3, 1, abTs) != MaturityPolicy.of(3, 1))
    }

    /**
     * DIE GRENZPROBE 3x1 ist bewusst aggressiv - aber sie darf nicht in
     * den Zustand "Median ohne Beleg" kippen. Ein Paar ist das Minimum,
     * und zwei Punkte im Minutentakt ergeben KEINES (dt < 2 min).
     */
    @Test
    fun `drei-punkte-probe braucht wirklich drei punkte`() {
        val p31 = MaturityPolicy.of(3, 1)
        assertNull(BgiAdjustedSeries.theilSen(punkte(2), punkte(2).last().sourceTs, p31),
                   "zwei 1-min-Werte ergeben kein Paar mit dt >= 2 min - mathematisch, nicht politisch")
        val drei = punkte(3)
        val r = BgiAdjustedSeries.theilSen(drei, drei.last().sourceTs, p31)
        assertNotNull(r)
        // Genau ein Paar: (t0, t0+2min) -> Steigung 1.0/min.
        assertEquals(1.0, r!!, 1e-9)
    }
}
