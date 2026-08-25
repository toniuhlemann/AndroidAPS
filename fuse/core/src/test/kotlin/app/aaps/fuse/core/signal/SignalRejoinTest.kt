package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE PFLICHTPROBEN ZUM WIEDEREINSTIEG (Tonis Liste vom 25.08. abends).
 *
 * Der Wiedereinstieg lockert die Theil-Sen-Reife von 5x8 auf 4x3 - aber
 * NUR nach einer eindeutig identifizierten echten CGM-Funkluecke. Jede
 * andere Ursache eines Segmentbeginns beschreibt ein neues Messregime,
 * und dort waere eine fruehe Steigung eine Behauptung ueber Daten, die
 * es nicht gibt.
 *
 * Jede Probe hier benennt den Fall aus Tonis Liste, damit ein spaeterer
 * Review die Abdeckung nachzaehlen kann.
 */
class SignalRejoinTest {

    private val t0 = 1_700_000_000_000L
    private val BREAK = GapPolicy.PRODUCTION.rSegmentBreakMs   // 180 s
    private val AN = RejoinPolicy.enabled()

    /** Reihe im ECHTEN Geraetetakt (58-62 s), optional mit einer Luecke. */
    private fun reihe(vorLuecke: Int, lueckeMs: Long, nachLuecke: Int): List<Long> {
        val takt = listOf(58_000L, 61_000L, 59_000L, 60_000L, 62_000L, 59_000L)
        val out = ArrayList<Long>()
        var t = t0
        repeat(vorLuecke) { out.add(t); t += takt[it % takt.size] }
        if (lueckeMs > 0L) t = out.last() + lueckeMs
        repeat(nachLuecke) { out.add(t); t += takt[it % takt.size] }
        return out
    }

    private fun waehle(
        ts: List<Long>,
        segmentStart: Long,
        bound: SignalWindow.Bound = SignalWindow.Bound.NONE,
        now: Long = ts.last(),
        policy: RejoinPolicy = AN,
        base: MaturityPolicy = MaturityPolicy.PRODUCTION,
    ) = SignalRejoin.select(policy, base, ts, segmentStart, bound, now, BREAK)

    // ---- NUTZENFAELLE ---------------------------------------------------

    /** PFLICHTFALL "Nutzen": echte Funkluecke ueber der Bruchgrenze. */
    @Test
    fun `nach echter funkluecke wird gelockert`() {
        // 8 Punkte, 200 s Luecke, dann 4 Punkte.
        val ts = reihe(8, 200_000L, 4)
        val segment = ts[8]
        val w = waehle(ts, segment)
        assertTrue(w.active, "die Funkluecke muss lockern")
        assertEquals(SignalRejoin.Cause.GAP, w.cause)
        assertEquals(200_000L, w.gapMs)
        assertEquals(4, w.maturity.minPoints)
        assertEquals(3, w.maturity.minSlopes)
    }

    /**
     * UND DER NUTZEN IST ECHT: dieselben vier Punkte im Geraetetakt sind
     * unter 5x8 blind und unter dem Wiedereinstieg reif. Ohne diese
     * Gegenprobe koennte der Test gruen sein, waehrend sich am Schaetzer
     * nichts aendert.
     */
    @Test
    fun `der gelockerte schaetzer liefert dort, wo der strenge noch blind ist`() {
        val ts = reihe(8, 200_000L, 6)
        val segment = ts[8]
        val punkte = ts.filter { it >= segment }
            .mapIndexed { i, t -> BgiAdjustedSeries.AdjustedPoint(t, 100.0 + i) }

        // VIER Punkte reichen im ECHTEN Takt NOCH NICHT - und das ist kein
        // Mangel des Wiedereinstiegs, sondern die 120-s-Paarschranke: die
        // Zyklen liegen 58-62 s auseinander, drei Abstaende ergeben 178 s
        // (ein Paar) und der zweite Nachbar nur 119 s. Macht zwei Paare,
        // noetig sind drei. Wer die idealisierte Minutentabelle fuer eine
        // Messung haelt, verspricht hier eine Minute zu viel.
        val vier = punkte.take(4)
        val w4 = waehle(ts, segment, now = vier.last().sourceTs)
        assertTrue(w4.active, "der Wiedereinstieg gilt, er reicht nur noch nicht")
        assertNull(BgiAdjustedSeries.theilSen(vier, vier.last().sourceTs, w4.maturity))

        // FUENF Punkte: vier zulaessige Paare. Streng blind (braucht 8),
        // gelockert reif (braucht 3). Das ist der gemessene Gewinn.
        val fuenf = punkte.take(5)
        val jetzt5 = fuenf.last().sourceTs
        assertNull(BgiAdjustedSeries.theilSen(fuenf, jetzt5),
                   "die Produktion muss hier noch blind sein")
        val w = waehle(ts, segment, now = jetzt5)
        assertTrue(w.active)
        assertNotNull(BgiAdjustedSeries.theilSen(fuenf, jetzt5, w.maturity),
                      "genau das ist der Gewinn des Wiedereinstiegs")

        // Und ab der gemeinsamen Reife sind beide identisch - die Lockerung
        // aendert nur das OB, nicht das WORAUS.
        val sechs = punkte.take(6)
        val jetzt6 = sechs.last().sourceTs
        assertNotNull(BgiAdjustedSeries.theilSen(sechs, jetzt6))
        assertEquals(
            BgiAdjustedSeries.theilSen(sechs, jetzt6),
            BgiAdjustedSeries.theilSen(sechs, jetzt6, w.maturity),
        )
    }

    // ---- DIE VERWEIGERUNGSFAELLE ---------------------------------------

    /** PFLICHTFALL "Kaltstart": kein Punkt vor dem Segmentbeginn. */
    @Test
    fun `kaltstart lockert nicht`() {
        val ts = reihe(0, 0L, 5)
        val w = waehle(ts, ts.first())
        assertFalse(w.active, "ohne Vorgeschichte gibt es nichts fortzusetzen")
        assertEquals(SignalRejoin.Cause.COLD_START, w.cause)
        assertSame(MaturityPolicy.PRODUCTION, w.maturity)
    }

    /** PFLICHTFALL "Sensorwechsel". */
    @Test
    fun `sensorwechsel lockert nicht`() {
        val ts = reihe(8, 200_000L, 4)
        val w = waehle(ts, ts[8], bound = SignalWindow.Bound.SENSOR_CHANGE)
        assertFalse(w.active, "neuer Sensor heisst neue Kennlinie")
        assertEquals(SignalRejoin.Cause.SENSOR_CHANGE, w.cause)
    }

    /** PFLICHTFALL "Kalibrierung". */
    @Test
    fun `kalibrierung lockert nicht`() {
        val ts = reihe(8, 200_000L, 4)
        val w = waehle(ts, ts[8], bound = SignalWindow.Bound.CALIBRATION_START)
        assertFalse(w.active)
        assertEquals(SignalRejoin.Cause.CALIBRATION, w.cause)
    }

    /** PFLICHTFALL "Input-Step". */
    @Test
    fun `eingangssprung lockert nicht`() {
        val ts = reihe(8, 200_000L, 4)
        val w = waehle(ts, ts[8], bound = SignalWindow.Bound.INPUT_STEP)
        assertFalse(w.active)
        assertEquals(SignalRejoin.Cause.INPUT_STEP, w.cause)
    }

    /**
     * PFLICHTFALL "Schleifenpause 24.08. 18:49". Der Regler stand
     * (Bolus), das CGM lief weiter - die MESSREIHE hat dann gar keine
     * Luecke, auch wenn zwischen zwei Reglerzyklen 181 s liegen.
     *
     * Genau so sah es am Geraet aus: gapBeforeMin 1,01 min, 18 Samples,
     * r sofort +2,15. Der Wiedereinstieg darf hier nicht greifen - und er
     * kann es auch gar nicht, weil er die REIHE prueft und nicht die
     * Zykluskadenz.
     */
    @Test
    fun `schleifenpause lockert nicht - die reihe hat keine luecke`() {
        val ts = reihe(18, 0L, 0)                    // durchgehende Reihe
        val jetzt = ts.last() + 181_000L             // Regler kommt spaet wieder
        // Der Segmentbeginn ist die rollende Fensterkante, kein Punkt.
        val w = waehle(ts, jetzt - 18 * 60_000L, now = jetzt)
        assertFalse(w.active, "ohne Luecke IN DER REIHE gibt es keinen Wiedereinstieg")
        assertEquals(SignalRejoin.Cause.NO_BREAK, w.cause)
        // Und die strenge Reife traegt hier ohnehin: 18 Punkte.
        val punkte = ts.mapIndexed { i, t -> BgiAdjustedSeries.AdjustedPoint(t, 100.0 + i) }
        assertNotNull(BgiAdjustedSeries.theilSen(punkte, jetzt))
    }

    /** PFLICHTFALL "Luecke ueber Maximaldauer". */
    @Test
    fun `zu lange luecke lockert nicht`() {
        val zuLang = RejoinPolicy.DEFAULT_MAX_GAP_MS + 60_000L
        val ts = reihe(8, zuLang, 4)
        val w = waehle(ts, ts[8])
        assertFalse(w.active, "ueber 10 min ist zu viel Kurve unbeobachtet vergangen")
        assertEquals(SignalRejoin.Cause.GAP_TOO_LONG, w.cause)
        assertEquals(zuLang, w.gapMs)
        // Die Kante selbst ist noch zulaessig.
        val gerade = reihe(8, RejoinPolicy.DEFAULT_MAX_GAP_MS, 4)
        assertTrue(waehle(gerade, gerade[8]).active)
    }

    /** Und das Altersfenster: irgendwann ist die Kadenz das Problem. */
    @Test
    fun `zu altes segment lockert nicht mehr`() {
        val ts = reihe(8, 200_000L, 4)
        val segment = ts[8]
        val spaet = segment + RejoinPolicy.DEFAULT_MAX_AGE_MS + 1
        val w = waehle(ts, segment, now = spaet)
        assertFalse(w.active)
        assertEquals(SignalRejoin.Cause.TOO_OLD, w.cause)
        // Genau auf der Kante gilt sie noch.
        assertTrue(waehle(ts, segment, now = segment + RejoinPolicy.DEFAULT_MAX_AGE_MS).active)
    }

    /** Eine Unterbrechung UNTER der Bruchgrenze ist gar kein Segmentbruch. */
    @Test
    fun `luecke unter der bruchgrenze ist kein wiedereinstieg`() {
        val ts = reihe(8, BREAK, 4)     // genau auf der Grenze = kein Bruch
        val w = waehle(ts, ts[8])
        assertFalse(w.active)
        assertEquals(SignalRejoin.Cause.NO_BREAK, w.cause)
    }

    @Test
    fun `ausgeschaltet bleibt alles bei der produktion`() {
        val ts = reihe(8, 200_000L, 4)
        val w = waehle(ts, ts[8], policy = RejoinPolicy.OFF)
        assertFalse(w.active)
        assertEquals(SignalRejoin.Cause.DISABLED, w.cause)
        assertSame(MaturityPolicy.PRODUCTION, w.maturity)
    }

    // ---- DER BODEN ------------------------------------------------------

    /**
     * 3x1 waere eine EINZIGE Paarsteigung - ein "Median" ueber ein
     * Element, also kein robuster Theil-Sen mehr. Die Politik weist das
     * ab, statt still eine schwaechere Statistik zu erlauben.
     */
    @Test
    fun `unter 4x3 gibt es keinen wiedereinstieg`() {
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(3, 1)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(4, 2)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(3, 3)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(2, 1)).enabled)
        // Der Boden selbst ist zulaessig.
        assertTrue(RejoinPolicy.enabled(MaturityPolicy.of(4, 3)).enabled)
        // Und eine "Lockerung", die strenger als die Produktion waere,
        // gehoerte in die Produktionskonstanten und nicht hierher.
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(6, 12)).enabled)
        // Ein Vorlauf-Fenster ist ein Replay-Werkzeug und hat im Produkt
        // nichts zu suchen.
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(4, 3, t0)).enabled)
        // Unbrauchbare Deckel ebenso.
        assertFalse(RejoinPolicy.enabled(maxGapMs = 0L).enabled)
        assertFalse(RejoinPolicy.enabled(maxGapMs = RejoinPolicy.MAX_GAP_CEILING_MS + 1).enabled)
        assertFalse(RejoinPolicy.enabled(maxAgeMs = 0L).enabled)
        assertFalse(RejoinPolicy.enabled(maxAgeMs = RejoinPolicy.MAX_AGE_CEILING_MS + 1).enabled)
    }

    /**
     * DER WIEDEREINSTIEG VERSCHAERFT NIE. Laeuft ein Reife-Replay mit
     * einer noch lockereren Basis, bleibt sie - sonst wuerde der Produkt-
     * Rejoin ausgerechnet die Messung verstellen, die ihn begruendet hat.
     */
    @Test
    fun `eine lockerere basis wird nicht verschaerft`() {
        val ts = reihe(8, 200_000L, 4)
        val basis = MaturityPolicy.of(3, 1)
        val w = waehle(ts, ts[8], base = basis)
        assertSame(basis, w.maturity)
        assertFalse(w.active, "nichts geaendert heisst nicht aktiv")
        assertEquals(SignalRejoin.Cause.GAP, w.cause)
    }

    /**
     * DIE REIHENFOLGE DER GRUENDE ist bedeutungstragend: liegt ZUGLEICH
     * eine Luecke und ein Sensorwechsel vor, gewinnt der Sensorwechsel.
     * Andersherum waere die Regel durch eine zufaellig gleichzeitige
     * Funkluecke aushebelbar.
     */
    @Test
    fun `bei luecke UND regimewechsel gewinnt der regimewechsel`() {
        val ts = reihe(8, 200_000L, 4)
        for (b in listOf(SignalWindow.Bound.SENSOR_CHANGE,
                         SignalWindow.Bound.CALIBRATION_START,
                         SignalWindow.Bound.INPUT_STEP)) {
            val w = waehle(ts, ts[8], bound = b)
            assertFalse(w.active, "$b darf nicht durch eine Luecke ausgehebelt werden")
        }
    }
}
