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
 * DOKUMENTIERTER Eingriffe sechs von 256 Proben uebrig. Hier ist die Wahrheit
 * bekannt, weil die Reihe konstruiert ist.
 *
 * Funktionale Abnahme und Dosissicherheit bleiben getrennt (Toni 28.08.):
 * diese Tests zeigen, dass die Logik tut, was sie behauptet - nicht, dass eine
 * frueher freigegebene Dosis klinisch sicher gewesen waere.
 */
class GlucoseStabilityTest {

    private val t0 = 1_700_000_000_000L
    private val p = GlucoseStability.Params()

    private fun reihe(vararg roh: Double, abstandMin: Int = 1, epoch: Long = 1L) =
        MeasuredGlucose(
            points = roh.mapIndexed { i, v -> GlucosePoint(t0 + i * abstandMin * 60_000L, v, v) },
            segmentStartTs = t0,
            signalEpochTs = epoch,
        )

    private fun MeasuredGlucose.jetzt() = points.last().sourceTs

    // ---- Die beiden Pflichtgegenproben ------------------------------------

    /**
     * GEGENPROBE 1 (Toni 28.08.): `95 -> 94 -> 95` darf nicht allein wegen
     * EINES negativen Paares als anhaltender Abfall gelten.
     *
     * Entwurf 1 verbot jedem Abschnitt ab 2 Minuten eine Rate unter -0,1. Der
     * Schritt 95 -> 94 ergibt ueber zwei Minuten -0,49 und haette bis 09:37
     * gesperrt - LAENGER als der Zustand vorher.
     */
    @Test
    fun `95 auf 94 auf 95 gilt nicht als Abfall`() {
        val s = reihe(95.0, 94.0, 95.0, 95.0, 94.0, 95.0, abstandMin = 2)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict,
                     "Netto 0, Breite 1 mg/dl - das ist kein Abfall: $r")
    }

    /**
     * GEGENPROBE 2 (Toni 28.08.), die Gegenrichtung: Anstieg, dann FRISCHER
     * deutlicher Abfall.
     *
     * Entwurf 2 uebersprang Abschnitte unter 5 Minuten ganz und meldete hier
     * STABLE - der schlechteste BERUECKSICHTIGTE Vergleich war nur 110 -> 108
     * = -2, waehrend die letzten zwei Minuten 116 -> 108 = -8 fielen. Ein
     * frisch begonnener Abfall blieb unsichtbar; das war ein falscher
     * Gegenwartsnachweis, kein bloss konservativer.
     *
     * Beide Entwuerfe scheiterten am selben Fehler: die Intervallaenge als
     * SCHALTER statt als MASSSTAB. Die Toleranz waechst jetzt mit der Dauer,
     * und KEIN Abschnitt wird uebersprungen.
     */
    @Test
    fun `Anstieg mit frischem Abfall ist nicht stabil`() {
        val s = reihe(100.0, 102.0, 104.0, 106.0, 108.0, 110.0, 112.0, 114.0, 116.0, 112.0, 108.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict,
                     "die letzten zwei Minuten fielen 8 mg/dl: $r")
        assertTrue(r.worstDropSpanMin <= 3.0,
                   "und zwar auf einem KURZEN Abschnitt: ${r.worstDropSpanMin} min")
    }

    /** Die Toleranz waechst - und genau das trennt die beiden Gegenproben. */
    @Test
    fun `dieselbe Hoehe ist ueber zwei Minuten ein Abfall und ueber zehn keiner`() {
        assertTrue(GlucoseStability.exceedsTolerance(-3.0, 2.0, p), "-3 in 2 min reisst")
        assertTrue(!GlucoseStability.exceedsTolerance(-3.0, 10.0, p), "-3 in 10 min nicht")
    }

    // ---- Der gemessene Fall -----------------------------------------------

    /** Die Rohwerte des Fruehstuecks vom 28.08., 09:21 bis 09:32. */
    @Test
    fun `der gemessene Fruehstuecksverlauf ist stabil`() {
        val s = reihe(96.0, 96.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 94.0, 95.0, 95.0, 96.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict, "$r")
        assertTrue(r.evaluatedPairs > 0, "und es wurden Paare beurteilt: $r")
    }

    @Test
    fun `der Fruehstuecksverlauf traegt sobald das Fenster gefuellt ist`() {
        val voll = listOf(96.0, 96.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 94.0, 95.0, 95.0, 96.0)
        val erste = (6..voll.size).firstOrNull { n ->
            val s = reihe(*voll.take(n).toDoubleArray())
            GlucoseStability.evaluate(s, s.jetzt(), p).verdict == GlucoseStability.Verdict.STABLE
        }
        // Sieben Punkte spannen sechs Minuten und erfuellen die 60-Prozent-
        // Fuellung des Zehn-Minuten-Fensters. Gemessen, nicht gewaehlt.
        assertEquals(7, erste, "kein Warten von zehn Minuten nach dem Marker")
    }

    // ---- Echter Abfall ----------------------------------------------------

    @Test
    fun `ein echter Abfall wird erkannt`() {
        val s = reihe(73.0, 72.0, 71.0, 70.0, 69.0, 68.0, 67.0, 66.0, 65.0, 64.0)
        assertEquals(GlucoseStability.Verdict.FALLING,
                     GlucoseStability.evaluate(s, s.jetzt(), p).verdict)
    }

    @Test
    fun `auch ein langsamer Abfall wird erkannt`() {
        val s = reihe(120.0, 119.5, 119.0, 118.5, 118.0, 117.5, 117.0, 116.5, 116.0, 115.5)
        assertEquals(GlucoseStability.Verdict.FALLING,
                     GlucoseStability.evaluate(s, s.jetzt(), p).verdict)
    }

    // ---- V-Verlauf durch MEHRERE Fensterpositionen ------------------------

    @Test
    fun `der V-Verlauf wird erkannt solange der Abwaertsschenkel im Fenster liegt`() {
        val voll = (0..9).map { 110.0 - it } + (1..10).map { 100.0 + it }
        fun urteil(bis: Int): GlucoseStability.Verdict {
            val s = reihe(*voll.take(bis + 1).toDoubleArray())
            return GlucoseStability.evaluate(s, s.jetzt(), p).verdict
        }
        assertEquals(GlucoseStability.Verdict.FALLING, urteil(9), "am Tiefpunkt")
        assertEquals(GlucoseStability.Verdict.FALLING, urteil(13), "kurz nach der Wende")
        // GRENZE, kein Defekt: liegt der Schenkel ausserhalb des Fensters,
        // beschreibt die Logik die Gegenwart - und die steigt.
        assertEquals(GlucoseStability.Verdict.STABLE, urteil(19), "Schenkel herausgefallen")
    }

    // ---- UNDETERMINED ist kein stilles STABLE -----------------------------

    @Test
    fun `zu wenige Punkte sind unbestimmbar`() {
        val s = reihe(95.0, 95.0, 95.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict)
        assertEquals(GlucoseStability.Reason.TOO_FEW_POINTS, r.reason)
    }

    @Test
    fun `ein zu kurz gefuelltes Fenster ist unbestimmbar`() {
        val s = MeasuredGlucose(
            points = (0..7).map { GlucosePoint(t0 + it * 15_000L, 95.0, 95.0) },
            segmentStartTs = t0, signalEpochTs = 1L,
        )
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Reason.SPAN_TOO_SHORT, r.reason)
    }

    /**
     * TONIS LUECKE (a): sechs konstante Punkte bei Minute 0,1,2,3,9,10.
     * Anzahl und Spanne stimmen - ueber die sechs Minuten dazwischen sagt die
     * Reihe trotzdem nichts.
     */
    @Test
    fun `ein Loch im Fenster ist unbestimmbar`() {
        val min = listOf(0L, 1L, 2L, 3L, 9L, 10L)
        val s = MeasuredGlucose(
            points = min.map { GlucosePoint(t0 + it * 60_000L, 95.0, 95.0) },
            segmentStartTs = t0, signalEpochTs = 1L,
        )
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict, "$r")
        assertEquals(GlucoseStability.Reason.GAP_IN_WINDOW, r.reason)
    }

    /** TONIS LUECKE (b): ein Parametersatz, unter dem kein Paar beurteilbar
     *  waere, darf kein STABLE liefern. */
    @Test
    fun `ein unbrauchbarer Parametersatz ist unbestimmbar`() {
        val s = reihe(*DoubleArray(11) { 95.0 })
        for (kaputt in listOf(
            p.copy(windowMin = 0),
            p.copy(minPoints = 1),
            p.copy(noiseAllowanceMgdl = -1.0),
            p.copy(driftMgdlPerMin = Double.NaN),
            p.copy(maxGapMin = 0.0),
        )) {
            val r = GlucoseStability.evaluate(s, s.jetzt(), kaputt)
            assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict, "$kaputt -> $r")
        }
    }

    @Test
    fun `unbrauchbare Zahlen sind unbestimmbar`() {
        val s = reihe(95.0, 95.0, Double.NaN, 95.0, 95.0, 95.0, 95.0, 95.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict)
        assertEquals(GlucoseStability.Reason.INVALID_INPUT, r.reason)
    }

    @Test
    fun `rueckwaerts laufende Zeitstempel sind unbestimmbar`() {
        val s = MeasuredGlucose(
            points = listOf(0L, 1L, 2L, 2L, 4L, 5L, 6L, 7L)
                .map { GlucosePoint(t0 + it * 60_000L, 95.0, 95.0) },
            segmentStartTs = t0, signalEpochTs = 1L,
        )
        assertEquals(GlucoseStability.Reason.INVALID_INPUT,
                     GlucoseStability.evaluate(s, t0 + 7 * 60_000L, p).reason)
    }

    /** VERALTET IST NICHT RUHIG. */
    @Test
    fun `eine veraltete Reihe ist unbestimmbar`() {
        val s = reihe(*DoubleArray(11) { 95.0 })
        val r = GlucoseStability.evaluate(s, s.jetzt() + 10 * 60_000L, p)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict)
        assertEquals(GlucoseStability.Reason.STALE, r.reason)
    }

    @Test
    fun `ein Segmentwechsel macht das Urteil unbestimmbar`() {
        val s = reihe(*DoubleArray(11) { 95.0 }, epoch = 42L)
        assertEquals(GlucoseStability.Reason.SEGMENT_CHANGED,
                     GlucoseStability.evaluate(s, s.jetzt(), p, priorEpochTs = 41L).reason)
    }

    // ---- Tonis Nachbesserungen (28.08.) -----------------------------------

    /**
     * DEFEKT 1: ein Punkt HINTER `nowTs` machte die Altersprüfung negativ und
     * wurde danach vom Fensterfilter entfernt - die verbliebene Reihe war vier
     * Minuten alt und wurde trotzdem beurteilt.
     *
     * Jetzt gilt die Frische fuer die Punkte, die WIRKLICH benutzt werden.
     */
    @Test
    fun `ein Zukunftspunkt darf die Frischepruefung nicht unterlaufen`() {
        val min = listOf(0L, 1L, 2L, 3L, 4L, 6L, 11L)
        val s = MeasuredGlucose(
            points = min.map { GlucosePoint(t0 + it * 60_000L, 95.0, 95.0) },
            segmentStartTs = t0, signalEpochTs = 1L,
        )
        val r = GlucoseStability.evaluate(s, t0 + 10 * 60_000L, p)
        assertEquals(GlucoseStability.Verdict.UNDETERMINED, r.verdict,
                     "der juengste BENUTZTE Punkt ist vier Minuten alt: $r")
    }

    /** Ein Punkt weit in der Zukunft ist ein Uhrenproblem, kein Messwert. */
    @Test
    fun `ein weit zukuenftiger Punkt ist ungueltig`() {
        val s = MeasuredGlucose(
            points = (0..6).map { GlucosePoint(t0 + it * 60_000L, 95.0, 95.0) } +
                GlucosePoint(t0 + 20 * 60_000L, 95.0, 95.0),
            segmentStartTs = t0, signalEpochTs = 1L,
        )
        val r = GlucoseStability.evaluate(s, t0 + 6 * 60_000L, p)
        assertEquals(GlucoseStability.Reason.INVALID_INPUT, r.reason, "$r")
    }

    /** DEFEKT 1b: unendliche Schranken schalten sich selbst ab. */
    @Test
    fun `unendliche Schranken sind unbrauchbar`() {
        val s = reihe(*DoubleArray(11) { 95.0 })
        for (kaputt in listOf(
            p.copy(maxGapMin = Double.POSITIVE_INFINITY),
            p.copy(maxAgeMin = Double.POSITIVE_INFINITY),
        )) {
            assertEquals(GlucoseStability.Verdict.UNDETERMINED,
                         GlucoseStability.evaluate(s, s.jetzt(), kaputt).verdict,
                         "$kaputt darf keine Schranke ausschalten")
        }
    }

    /**
     * DEFEKT 2: im stabilen Fall beschrieben Rueckgang, Dauer und Zeitstempel
     * kein gemeinsames Paar - der Rueckgang kam aus einem zweiten Scan,
     * Dauer und Zeitstempel blieben 0. Herauskam "-2 mg/dl ueber 0 Minuten".
     */
    @Test
    fun `auch im stabilen Fall beschreibt die Diagnose EIN Paar`() {
        val s = reihe(96.0, 96.0, 95.0, 95.0, 95.0, 95.0, 95.0, 95.0, 94.0, 95.0, 95.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict, "$r")
        assertTrue(r.worstDropMgdl < 0.0, "es gab einen Rueckgang: $r")
        assertTrue(r.worstDropSpanMin > 0.0, "und er hat eine Dauer: $r")
        assertTrue(r.worstDropEndsTs > 0L, "und einen Ablauf: $r")
        // Und die erlaubte Menge steht daneben, damit nachvollziehbar ist,
        // WARUM der Grenzfall bestanden hat.
        assertTrue(r.worstDropAllowedMgdl >= -r.worstDropMgdl,
                   "erlaubt ${r.worstDropAllowedMgdl} gegen gefallen ${r.worstDropMgdl}")
    }

    // ---- Staerkstes Paar ist NICHT die Gegenwart ---------------------------

    /**
     * TONIS GEGENFALL (28.08.): ein starker ALTER Abfall verdeckt einen
     * frischen.
     *
     * Das staerkste Paar ist 120 -> 100 (-20 ueber 2 min) und liegt frueh,
     * also meldet `bindingEndsAtNewest` ALT. Gleichzeitig laufen mehrere
     * verletzende Abschnitte bis zum juengsten Punkt. Wer aus "staerkstes
     * Paar ist alt" auf "jetzt faellt nichts" schliesst, uebersieht sie.
     */
    @Test
    fun `ein starker alter Abfall verdeckt keinen frischen`() {
        val s = reihe(120.0, 120.0, 110.0, 100.0, 100.0, 110.0, 112.0, 114.0, 116.0, 114.0, 111.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict)
        assertTrue(!r.bindingEndsAtNewest, "das staerkste Paar liegt frueh: $r")
        assertTrue(r.freshDropExists,
                   "aber es faellt sehr wohl gerade etwas - genau das darf nicht verdeckt werden")
    }

    /** Und die Gegenrichtung: ein Paar am juengsten Punkt beweist keinen
     *  fortdauernden Abfall, wenn der Endwert seit Minuten steht. */
    @Test
    fun `ein flaches Ende trotz alter Stufe meldet keinen frischen Abfall`() {
        val s = reihe(110.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict, "die Stufe reisst noch: $r")
        assertTrue(r.freshDropExists, "sie laeuft bis zum juengsten Punkt - ehrlich gemeldet")
    }

    // ---- Die Historienberechnung SELBST ------------------------------------

    /** Bisher wurde nur die UEBERNAHME von confirmedCycles geprueft, nicht
     *  seine Berechnung (Tonis Befund 28.08.). */
    @Test
    fun `eine durchgehend ruhige Reihe bestaetigt mehrere Zyklen`() {
        val s = reihe(*DoubleArray(14) { 95.0 })
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict)
        assertTrue(r.confirmedCycles >= 3,
                   "die Reihe belegt mehrere ruhige Zyklen: ${r.confirmedCycles}")
    }

    /** Ein Abfall in der Vergangenheit beendet die Zaehlung dort. */
    @Test
    fun `ein historischer Abfall unterbricht die Bestaetigung`() {
        // Erst ein Sturz, dann acht ruhige Minuten.
        val s = reihe(120.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0,
                      110.0, 110.0, 110.0, 110.0, 110.0, 110.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict, "$r")
        val ohneSturz = GlucoseStability.evaluate(reihe(*DoubleArray(14) { 95.0 }),
                                                  s.jetzt(), p).confirmedCycles
        assertTrue(r.confirmedCycles < ohneSturz,
                   "der Sturz muss die Zaehlung begrenzen: ${r.confirmedCycles} gegen $ohneSturz")
    }

    /**
     * TONIS GEGENFALL 2 (28.08.): die Rueckschau prueft dieselben Bedingungen
     * wie die Gegenwart - auch die Luecke.
     *
     * Konstante Werte bei Minute -1,3,4,...,10. Das aktuelle Fenster ist
     * sauber; die historischen enthalten eine Vierminutenluecke und sind
     * vollstaendig bewertet UNDETERMINED. Die alte, abgespeckte
     * Historienpruefung zaehlte sie trotzdem mit.
     */
    @Test
    fun `eine Luecke in der Historie beendet die Bestaetigung`() {
        val min = listOf(-1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
        val s = MeasuredGlucose(
            points = min.map { GlucosePoint(t0 + it * 60_000L, 95.0, 95.0) },
            segmentStartTs = t0 - 60_000L, signalEpochTs = 1L,
        )
        val r = GlucoseStability.evaluate(s, t0 + 10 * 60_000L, p)
        assertEquals(GlucoseStability.Verdict.STABLE, r.verdict, "das aktuelle Fenster ist sauber: $r")
        assertEquals(1, r.confirmedCycles,
                     "die Vierminutenluecke macht jedes fruehere Fenster unbestimmbar: $r")
    }

    // ---- Kein Freigabe-Countdown ------------------------------------------

    /**
     * `worstDropEndsTs` beschreibt NUR das ausgewaehlte Paar. Andere fallende
     * Paare koennen weiter sperren - der Viewer darf daraus keine Restzeit bis
     * zur Freigabe ableiten (Tonis Hinweis 28.08.).
     */
    @Test
    fun `das Ablaufdatum gilt nur fuer das ausgewaehlte Paar`() {
        val s = reihe(120.0, 118.0, 116.0, 114.0, 112.0, 110.0, 108.0, 106.0, 104.0, 102.0)
        val r = GlucoseStability.evaluate(s, s.jetzt(), p)
        assertEquals(GlucoseStability.Verdict.FALLING, r.verdict)
        assertTrue(r.worstDropEndsTs > 0L, "der Ablauf des Paares ist benannt")
        // Nach diesem Zeitpunkt faellt der Verlauf immer noch - das Paar ist
        // nicht die Sperre, nur ihr staerkster Vertreter.
        val spaeter = reihe(110.0, 108.0, 106.0, 104.0, 102.0, 100.0, 98.0, 96.0)
        assertEquals(GlucoseStability.Verdict.FALLING,
                     GlucoseStability.evaluate(spaeter, spaeter.jetzt(), p).verdict,
                     "ein Countdown auf worstDropEndsTs waere falsch")
    }
}
