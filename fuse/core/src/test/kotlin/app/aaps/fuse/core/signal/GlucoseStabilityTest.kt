package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KONTROLLIERTE VERLAEUFE STATT KONFUNDIERTER HISTORIE.
 *
 * Eine Breitenmessung ueber fuenf aufgezeichnete Tage konnte die Frage nicht
 * beantworten: dort haengt der weitere Verlauf an Kohlenhydraten, Aktivitaet
 * und liegendem Insulin, und im Mahlzeitenkontext blieben nach Ausschluss
 * dokumentierter Eingriffe genau SECHS Proben uebrig. Hier ist die Wahrheit
 * dagegen bekannt, weil die Reihe konstruiert ist.
 *
 * Was diese Tests leisten: sie zeigen, dass die Logik tut, was sie behauptet.
 * Was sie NICHT leisten: einen Nachweis, dass eine frueher freigegebene Dosis
 * klinisch sicher gewesen waere. Funktionale Abnahme und Dosissicherheit
 * bleiben getrennt (Toni 28.08.).
 */
class GlucoseStabilityTest {

    private val t0 = 1_700_000_000_000L
    private val p = GlucoseStability.Params()

    /** Punkte im Minutenraster; q1 = roh, wo der Filter keine Rolle spielt. */
    private fun reihe(vararg roh: Double, abstandMin: Int = 1, epoch: Long = 1L) =
        MeasuredGlucose(
            points = roh.mapIndexed { i, v ->
                GlucosePoint(t0 + i * abstandMin * 60_000L, v, v)
            },
            segmentStartTs = t0,
            signalEpochTs = epoch,
        )

    private fun MeasuredGlucose.jetzt() = points.last().sourceTs

    // ---- Tonis Pflichtgegenprobe ------------------------------------------

    /**
     * DIE PFLICHTGEGENPROBE (Toni 28.08.): `95 -> 94 -> 95` im
     * Zweiminutenraster darf nicht allein wegen EINES negativen Paares als
     * anhaltender relevanter Abfall gelten.
     *
     * Der erste Entwurf verbot jedem Teilintervall ab 2 Minuten eine Rate
     * unter -0,1 mg/dl/min. Der Schritt 95 -> 94 ergibt ueber zwei Minuten
     * -0,5 - er waere durchgefallen, obwohl der Verlauf netto unveraendert
     * ist und die Schwankungsbreite einen einzigen Quantisierungsschritt
     * betraegt.
     */
    @Test
    fun `95 auf 94 auf 95 gilt nicht als Abfall`() {
        val s = reihe(95.0, 94.0, 95.0, 95.0, 94.0, 95.0, abstandMin = 2)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict,
                     "Netto 0, Breite 1 mg/dl - das ist kein Abfall: $r")
        assertTrue(r.worstDropMgdl >= -1.0, "hoechstens ein Quantisierungsschritt: ${r.worstDropMgdl}")
    }

    // ---- Der gemessene Fall -----------------------------------------------

    /**
     * DIE ROHWERTE DES FRUEHSTUECKS vom 28.08., 09:21 bis 09:32, wie
     * aufgezeichnet. Marker war 09:21:56, offen waren 4,00 U.
     *
     * Der Zweck dieses Tests ist NICHT "die Freigabe war richtig", sondern:
     * ab wann nennt die Logik diese Lage stabil? Die Antwort gehoert in den
     * Bericht, nicht in eine Schwelle, die auf diesen Tag zugeschnitten ist.
     */
    @Test
    fun `der gemessene Fruehstuecksverlauf ist stabil`() {
        val s = reihe(96.0, 96.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 94.0, 95.0, 95.0, 96.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict, "$r")
        // Der groesste Rueckgang ist ein einziger Schritt ueber mehrere Minuten.
        assertTrue(r.worstDropMgdl >= -2.0, "worstDrop ${r.worstDropMgdl}")
    }

    /** AB WELCHEM PUNKT traegt der Nachweis? Die Frage, die der Bericht
     *  beantworten muss - hier als Zusicherung, damit sie nicht verrutscht. */
    @Test
    fun `der Fruehstuecksverlauf traegt sobald das Fenster gefuellt ist`() {
        val voll = listOf(96.0, 96.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 94.0, 95.0, 95.0, 96.0)
        val erste = (6..voll.size).firstOrNull { n ->
            val s = reihe(*voll.take(n).toDoubleArray())
            GlucoseStability.evaluate(s, s.jetzt(), p).verdict == GlucoseStability.Verdict.STABLE
        }
        // SIEBEN, nicht sechs: sechs Punkte spannen im Minutenraster nur fuenf
        // Minuten und scheitern an der 60-Prozent-Fuellung des
        // Zehn-Minuten-Fensters (SPAN_TOO_SHORT). Der Wert ist gemessen, nicht
        // gewaehlt - er faellt mit windowMin und der Kadenz.
        assertEquals(7, erste, "sobald das Fenster hinreichend gefuellt ist, traegt es - nicht erst nach zehn Minuten")
    }

    // ---- Echter Abfall ----------------------------------------------------

    @Test
    fun `ein echter Abfall wird erkannt`() {
        // -1 mg/dl je Minute, wie am 26.08. zwischen 15:15 und 15:33.
        val s = reihe(73.0, 72.0, 71.0, 70.0, 69.0, 68.0, 67.0, 66.0, 65.0, 64.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict, "$r")
        assertEquals(GlucoseStability.Reason.DROP_EXCEEDS, r.reason)
    }

    @Test
    fun `auch ein langsamer Abfall wird erkannt`() {
        // -0,5 mg/dl je Minute - haelt die Toleranz von 3 mg/dl nicht.
        val s = reihe(120.0, 119.5, 119.0, 118.5, 118.0, 117.5, 117.0, 116.5, 116.0, 115.5)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict, "$r")
    }

    // ---- V-Verlauf durch MEHRERE Fensterpositionen ------------------------

    /**
     * DER V-VERLAUF, DURCH DAS FENSTER GESCHOBEN (Tonis Auflage 28.08.):
     * vor der Wende, mittendrin, danach. Ein Fenster, das den Abwaertsschenkel
     * nicht mehr enthaelt, prueft dessen Erkennung nicht - deshalb steht hier
     * ausdruecklich, WANN der Schenkel wieder herausfaellt.
     */
    @Test
    fun `der V-Verlauf wird erkannt solange der Abwaertsschenkel im Fenster liegt`() {
        // 10 min runter (-1/min), dann 10 min hoch (+1/min).
        val voll = (0..9).map { 110.0 - it } + (1..10).map { 100.0 + it }
        data class Fall(val bisIndex: Int, val lage: String)
        val faelle = listOf(
            Fall(9, "am Tiefpunkt - Schenkel voll im Fenster"),
            Fall(13, "kurz nach der Wende - Schenkel noch drin"),
            Fall(19, "spaet - Schenkel aus dem Fenster gefallen"),
        )
        val urteile = faelle.map { f ->
            val fenster = voll.take(f.bisIndex + 1)
            val s = reihe(*fenster.toDoubleArray())
            f.lage to GlucoseStability.evaluate(s, s.jetzt(), p).verdict
        }
        assertEquals(GlucoseStability.Verdict.FALLING, urteile[0].second, urteile[0].first)
        assertEquals(GlucoseStability.Verdict.FALLING, urteile[1].second, urteile[1].first)
        // UND DAS IST KEIN DEFEKT, SONDERN DIE GRENZE: liegt der Schenkel
        // ausserhalb des Fensters, beschreibt die Logik die Gegenwart - und
        // die steigt. Wer den alten Abstieg weiter beruecksichtigt haben will,
        // braucht ein laengeres Fenster, nicht eine andere Regel.
        assertEquals(GlucoseStability.Verdict.STABLE, urteile[2].second, urteile[2].first)
    }

    // ---- Fehlende Daten sind KEINE Entwarnung -----------------------------

    @Test
    fun `zu wenige Punkte sind unbestimmbar und nicht stabil`() {
        val s = reihe(95.0, 95.0, 95.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict)
        assertEquals(GlucoseStability.Reason.TOO_FEW_POINTS, r.reason)
    }

    @Test
    fun `ein zu kurz gefuelltes Fenster ist unbestimmbar`() {
        // Acht Punkte, aber alle innerhalb von zwei Minuten.
        val s = MeasuredGlucose(
            points = (0..7).map { GlucosePoint(t0 + it * 15_000L, 95.0, 95.0) },
            segmentStartTs = t0, signalEpochTs = 1L,
        )
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict)
        assertEquals(GlucoseStability.Reason.SPAN_TOO_SHORT, r.reason)
    }

    @Test
    fun `ein Segmentwechsel macht das Urteil unbestimmbar`() {
        val s = reihe(95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, epoch = 42L)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p, priorEpochTs = 41L)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict)
        assertEquals(GlucoseStability.Reason.SEGMENT_CHANGED, r.reason,
                     "ein geerbtes Urteil ueber einen Bruch hinweg waere erfunden")
    }

    // ---- Keine verdeckte Sperre -------------------------------------------

    /**
     * WANN ENDET DER EINFLUSS DES TRAGENDEN PUNKTES? Ohne diese Angabe
     * entstuende genau die verdeckte Zehn-Minuten-Sperre, vor der Toni
     * gewarnt hat: der Aufrufer koennte nicht sagen, warum er noch wartet.
     */
    @Test
    fun `der tragende Rueckgang nennt sein Ablaufdatum`() {
        val s = reihe(110.0, 109.0, 108.0, 107.0, 106.0, 105.0, 104.0, 103.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict)
        assertTrue(r.worstDropEndsTs > 0L, "der Ablauf muss benannt sein")
        assertTrue(r.worstDropSpanMin >= p.minSubSpanMin,
                   "und die Dauer dazu: ${r.worstDropSpanMin}")
        // Der tragende Punkt ist der aelteste; sein Einfluss endet ein
        // Fenster nach ihm.
        assertEquals(s.points.first().sourceTs + p.windowMin * 60_000L, r.worstDropEndsTs)
    }

    /** Kurze Abschnitte werden gar nicht erst beurteilt - dort dominiert die
     *  Quantisierung. Genau diese Wahl rettet die Pflichtgegenprobe. */
    @Test
    fun `Abschnitte unter der Mindestdauer zaehlen nicht`() {
        // Ein Sprung um 5 mg/dl innerhalb einer Minute, danach flach.
        val s = reihe(100.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        // Ueber >= 5 min gemessen betraegt der Rueckgang trotzdem 5 mg/dl
        // (100 am Anfang gegen 95 spaeter) - er wird also NICHT uebersehen.
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict,
                     "ein echter Sprung bleibt sichtbar, sobald er ein langes Intervall spannt: $r")
    }
}
