package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE PFLICHTPROBEN ZUM WIEDEREINSTIEG (Tonis Listen vom 25.08. abends,
 * einschliesslich der Nachbesserung zur SEGMENTIDENTITAET).
 *
 * Der Wiedereinstieg lockert die Theil-Sen-Reife von 5x8 auf 4x3 - aber
 * nur nach einer echten CGM-Funkluecke in einem ETABLIERTEN Messregime.
 * Etabliert heisst: der Abschnitt zwischen der letzten Regimegrenze und
 * dem Punkt vor der Luecke war selbst streng 5x8-reif.
 *
 * Jede Probe benennt den Fall aus Tonis Listen, damit ein spaeterer
 * Review die Abdeckung nachzaehlen kann.
 */
class SignalRejoinTest {

    private val t0 = 1_700_000_000_000L
    private val GAP = GapPolicy.PRODUCTION
    private val BREAK = GAP.rSegmentBreakMs                    // 180 s
    private val AN = RejoinPolicy.enabled()
    private val TAKT = listOf(58_000L, 61_000L, 59_000L, 60_000L, 62_000L, 59_000L)

    /** Reihe im ECHTEN Geraetetakt (58-62 s), optional mit einer Luecke. */
    private fun reihe(vorLuecke: Int, lueckeMs: Long, nachLuecke: Int): List<Long> {
        val out = ArrayList<Long>()
        var t = t0
        repeat(vorLuecke) { out.add(t); t += TAKT[it % TAKT.size] }
        if (lueckeMs > 0L) t = out.last() + lueckeMs
        repeat(nachLuecke) { out.add(t); t += TAKT[it % TAKT.size] }
        return out
    }

    private fun waehle(
        ts: List<Long>,
        segmentStart: Long,
        regime: SignalRejoin.Regime = SignalRejoin.Regime.NONE,
        now: Long = ts.last(),
        policy: RejoinPolicy = AN,
        base: MaturityPolicy = MaturityPolicy.PRODUCTION,
    ) = SignalRejoin.select(policy, base, ts, segmentStart, regime, now, GAP)

    /** Regime mit Segmentidentitaet; ohne Angabe faellt der erste
     *  Segmentpunkt mit der Grenze zusammen (so wie am Geraet, wo die
     *  Reihe an der Grenze beschnitten wird). */
    private fun regime(b: SignalWindow.Bound, ts: Long, segStart: Long = ts) =
        SignalRejoin.Regime.of(b, ts, segStart)

    // ---- NUTZENFAELLE ---------------------------------------------------

    /** PFLICHTFALL "Nutzen": echte Funkluecke in einem etablierten Regime. */
    @Test
    fun `nach echter funkluecke wird gelockert`() {
        val ts = reihe(8, 200_000L, 4)          // 8 Punkte reichen fuer 5x8
        val w = waehle(ts, ts[8])
        assertTrue(w.active, "die Funkluecke muss lockern")
        assertEquals(SignalRejoin.Cause.GAP, w.cause)
        assertEquals(200_000L, w.gapMs)
        assertTrue(w.preGapStrictReady, "vor der Luecke lag ein streng gereiftes Signal")
        assertEquals(4, w.maturity.minPoints)
        assertEquals(3, w.maturity.minSlopes)
    }

    /**
     * UND DER NUTZEN IST ECHT: dieselben Punkte im Geraetetakt sind unter
     * 5x8 blind und unter dem Wiedereinstieg reif. Ohne diese Gegenprobe
     * koennte der Test gruen sein, waehrend sich am Schaetzer nichts
     * aendert.
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

    // ---- SEGMENTIDENTITAET (Tonis Nachbesserung) ------------------------

    /**
     * PFLICHTFALL 1: Kalibrierung UND gleichzeitige Luecke -> kein Rejoin.
     *
     * Die Reihe beginnt an der Kalibrierung; gleich danach faellt der Funk
     * aus. Vor der Luecke steht genau ein Punkt - das ist keine bekannte
     * Kurve, sondern der Anfang einer neuen.
     */
    @Test
    fun `kalibrierung mit gleichzeitiger luecke lockert nicht`() {
        val ts = reihe(1, 200_000L, 5)
        val w = waehle(ts, ts[1], regime = regime(SignalWindow.Bound.CALIBRATION_START, ts[0]))
        assertFalse(w.active)
        assertEquals(SignalRejoin.Cause.PRE_GAP_NOT_MATURE, w.cause,
                     "ein einziger Punkt vor der Luecke belegt keine Kurve")
        assertFalse(w.preGapStrictReady)
    }

    /** Und wenn die Grenze den Segmentbeginn SELBST erklaert, heisst sie so. */
    @Test
    fun `eine grenze am segmentbeginn wird beim namen genannt`() {
        val ts = reihe(0, 0L, 6)
        for ((b, c) in listOf(
            SignalWindow.Bound.CALIBRATION_START to SignalRejoin.Cause.CALIBRATION,
            SignalWindow.Bound.SENSOR_CHANGE to SignalRejoin.Cause.SENSOR_CHANGE,
            SignalWindow.Bound.INPUT_STEP to SignalRejoin.Cause.INPUT_STEP,
        )) {
            val w = waehle(ts, ts[0], regime = regime(b, ts[0]))
            assertFalse(w.active)
            assertEquals(c, w.cause)
        }
        // Ohne Grenze ist derselbe Fall ein Kaltstart.
        assertEquals(SignalRejoin.Cause.COLD_START, waehle(ts, ts[0]).cause)
    }

    /**
     * PFLICHTFALL 2 - DER KERN DER NACHBESSERUNG: Kalibrierung, danach
     * vollstaendige 5x8-Reife, SPAETER eine Funkluecke -> Rejoin.
     *
     * Tonis Beispiel: 12:00 Kalibrierung, 12:05-12:06 neues Signal reif,
     * 13:00 kurze Funkluecke, 13:04 Rejoin erlaubt. Der erste Wurf haette
     * hier bis etwa 15:00 gesperrt, weil die Grenze noch im
     * 180-min-Puffer lag - das verwechselte die historische Fenstergrenze
     * mit der Ursache des aktuellen Segmentbruchs.
     */
    @Test
    fun `kalibrierung, dann volle reife, dann spaetere luecke lockert wieder`() {
        // 60 Punkte nach der Kalibrierung (eine Stunde), dann 200 s Luecke.
        val ts = reihe(60, 200_000L, 5)
        val kalibrierung = ts[0]
        val w = waehle(ts, ts[60], regime = regime(SignalWindow.Bound.CALIBRATION_START, kalibrierung))
        assertTrue(w.active, "das neue Regime war laengst etabliert")
        assertEquals(SignalRejoin.Cause.GAP, w.cause)
        assertTrue(w.preGapStrictReady)
        // Die Grenze steht trotzdem im Ergebnis - sie ist nicht verschwunden,
        // sie erklaert nur diesen Segmentbeginn nicht.
        assertEquals(SignalWindow.Bound.CALIBRATION_START, w.regime.bound)
        assertEquals(kalibrierung, w.regime.boundaryTs)
        // Und die Identitaet zeigt, WARUM sie nicht erklaert: ihr erster
        // Segmentpunkt ist ein anderer als der aktuelle Segmentbeginn.
        assertEquals(kalibrierung, w.regime.segmentStartTs)
        assertFalse(w.regime.explains(ts[60]))
    }

    /**
     * DIE IDENTITAET TRAEGT AUS SICH SELBST - ohne dass der Aufrufer die
     * Reihe beschnitten haben muesste.
     *
     * Der erste Wurf entschied an der POSITION in der Liste (`i == 0`) und
     * funktionierte nur, weil die Produktreihe vorher an der Grenze
     * beschnitten wurde. Eine Mutation, die den Grenzzeitpunkt auf 0
     * setzte, blieb deshalb gruen. Hier ist die Reihe ausdruecklich NICHT
     * beschnitten: die Kalibrierung faellt mit der Luecke zusammen und
     * traegt acht Punkte Vorgeschichte, die streng reif waeren. Nur die
     * Identitaet verhindert den Rejoin.
     */
    @Test
    fun `eine grenze auf dem segmentbeginn sperrt auch in unbeschnittener reihe`() {
        val ts = reihe(8, 200_000L, 4)
        val segment = ts[8]
        // Ohne Grenze: gelockert, denn acht Punkte davor sind streng reif.
        assertTrue(waehle(ts, segment).active)
        // Mit einer Grenze, deren erster Segmentpunkt GENAU dieser
        // Segmentbeginn ist: gesperrt, obwohl ein Vorgaenger existiert.
        val w = waehle(ts, segment,
                       regime = regime(SignalWindow.Bound.CALIBRATION_START, segment, segment))
        assertFalse(w.active, "die Grenze erklaert diesen Segmentbeginn")
        assertEquals(SignalRejoin.Cause.CALIBRATION, w.cause)
        // Dieselbe Grenze, aber mit einem ANDEREN ersten Segmentpunkt,
        // erklaert ihn nicht - dann ist es wieder eine gewoehnliche Luecke.
        val w2 = waehle(ts, segment,
                        regime = regime(SignalWindow.Bound.CALIBRATION_START, ts[0], ts[0]))
        assertTrue(w2.active)
        assertEquals(SignalRejoin.Cause.GAP, w2.cause)
    }

    /**
     * UNMOEGLICHE KOMBINATIONEN GIBT ES NICHT. Ein Regime ohne
     * Segmentidentitaet waere die GEFAEHRLICHE Richtung: `explains` faende
     * nie eine Uebereinstimmung, die Grenze sperrte also NIE - eine
     * kaputte Verdrahtung saehe aus wie "kein Regimewechsel".
     */
    @Test
    fun `die factory weist unmoegliche regime ab`() {
        // NONE traegt keine Zeitstempel.
        assertThrows(IllegalArgumentException::class.java) {
            SignalRejoin.Regime.of(SignalWindow.Bound.NONE, t0, t0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalRejoin.Regime.of(SignalWindow.Bound.NONE, 0L, t0)
        }
        // Eine Grenze ohne Zeitpunkt oder ohne Segmentidentitaet.
        assertThrows(IllegalArgumentException::class.java) {
            SignalRejoin.Regime.of(SignalWindow.Bound.CALIBRATION_START, 0L, t0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalRejoin.Regime.of(SignalWindow.Bound.SENSOR_CHANGE, t0, 0L)
        }
        // Ein erster Segmentpunkt VOR der Grenze kann kein Punkt dieses
        // Regimes sein.
        assertThrows(IllegalArgumentException::class.java) {
            SignalRejoin.Regime.of(SignalWindow.Bound.INPUT_STEP, t0, t0 - 1)
        }
        // Die zulaessigen Faelle.
        assertSame(SignalRejoin.Regime.NONE,
                   SignalRejoin.Regime.of(SignalWindow.Bound.NONE, 0L, 0L))
        val r = SignalRejoin.Regime.of(SignalWindow.Bound.CALIBRATION_START, t0, t0 + 60_000L)
        assertEquals(t0, r.boundaryTs)
        assertEquals(t0 + 60_000L, r.segmentStartTs)
        assertTrue(r.explains(t0 + 60_000L))
        assertFalse(r.explains(t0))
        // Und NONE erklaert nie etwas - auch nicht den Zeitpunkt 0.
        assertFalse(SignalRejoin.Regime.NONE.explains(0L))
    }

    /**
     * PFLICHTFALL 3: Kaltstart, wenige Werte, Funkluecke -> kein Rejoin.
     *
     * Genau die Luecke, die Toni benannt hat: zwei neue Werte, dann
     * Funkverlust, dann 4x3-Reife haetten den Wiedereinstieg geoeffnet,
     * obwohl vor der Luecke nie ein streng gereiftes Signal existierte.
     */
    @Test
    fun `kaltstart mit wenigen werten und dann luecke lockert nicht`() {
        for (n in 1..5) {
            val ts = reihe(n, 200_000L, 5)
            val w = waehle(ts, ts[n])
            assertFalse(w.active, "$n Punkte vor der Luecke duerfen nicht lockern")
            assertEquals(SignalRejoin.Cause.PRE_GAP_NOT_MATURE, w.cause, "bei $n Punkten")
            assertFalse(w.preGapStrictReady)
        }
        // Ab sechs Punkten im Geraetetakt traegt 5x8 - und erst dann lockert er.
        val sechs = reihe(6, 200_000L, 5)
        assertTrue(waehle(sechs, sechs[6]).active, "sechs Punkte sind streng reif")
    }

    /**
     * PFLICHTFALL 4: Eingangssprung, danach gereifte neue Reihe, dann
     * Funkverlust -> Rejoin. Dieselbe Regel wie fuer die Kalibrierung.
     */
    @Test
    fun `input-step, dann volle reife, dann luecke lockert wieder`() {
        val ts = reihe(30, 200_000L, 5)
        val w = waehle(ts, ts[30], regime = regime(SignalWindow.Bound.INPUT_STEP, ts[0]))
        assertTrue(w.active, "die neue Reihe war etabliert")
        assertEquals(SignalRejoin.Cause.GAP, w.cause)
        assertTrue(w.preGapStrictReady)
    }

    /**
     * DIE REGIMEGRENZE SCHNEIDET DIE VORGESCHICHTE AB. Punkte VOR der
     * Grenze belegen nichts ueber das neue Regime - auch wenn sie
     * lueckenlos anschliessen.
     */
    @Test
    fun `punkte vor der regimegrenze zaehlen nicht als etablierung`() {
        // 20 Punkte, Grenze kurz vor der Luecke: nur 2 Punkte danach.
        val ts = reihe(20, 200_000L, 5)
        val spaeteGrenze = ts[18]
        val w = waehle(ts, ts[20], regime = regime(SignalWindow.Bound.SENSOR_CHANGE, spaeteGrenze))
        assertFalse(w.active, "nach der Grenze standen nur zwei Punkte")
        assertEquals(SignalRejoin.Cause.PRE_GAP_NOT_MATURE, w.cause)
        // Ohne die Grenze waere derselbe Verlauf reif - der Unterschied ist
        // allein die Grenze.
        assertTrue(waehle(ts, ts[20]).active)
    }

    /** Eine FRUEHERE Luecke trennt ebenso wie eine Regimegrenze. */
    @Test
    fun `eine fruehere luecke trennt die vorgeschichte ebenso`() {
        // 10 Punkte, Luecke, 2 Punkte, Luecke - vor der zweiten Luecke
        // stehen nur zwei Punkte des neuen Abschnitts.
        val a = reihe(10, 200_000L, 2)
        var t = a.last() + 200_000L
        val ts = a + (0 until 5).map { i -> (t + (0 until i).sumOf { TAKT[it % TAKT.size] }) }
        val w = waehle(ts, ts[12])
        assertFalse(w.active, "der Abschnitt zwischen den beiden Luecken traegt nicht")
        assertEquals(SignalRejoin.Cause.PRE_GAP_NOT_MATURE, w.cause)
    }

    // ---- DIE UEBRIGEN VERWEIGERUNGSFAELLE -------------------------------

    /**
     * PFLICHTFALL "Schleifenpause 24.08. 18:49". Der Regler stand
     * (Bolus), das CGM lief weiter - die MESSREIHE hat dann gar keine
     * Luecke, auch wenn zwischen zwei Reglerzyklen 181 s liegen.
     *
     * Genau so sah es am Geraet aus: gapBeforeMin 1,01 min, 18 Samples,
     * r sofort +2,15.
     */
    @Test
    fun `schleifenpause lockert nicht - die reihe hat keine luecke`() {
        val ts = reihe(18, 0L, 0)                    // durchgehende Reihe
        val jetzt = ts.last() + 181_000L             // Regler kommt spaet wieder
        val w = waehle(ts, jetzt - 18 * 60_000L, now = jetzt)
        assertFalse(w.active, "ohne Luecke IN DER REIHE gibt es keinen Wiedereinstieg")
        assertEquals(SignalRejoin.Cause.NO_BREAK, w.cause)
        val punkte = ts.mapIndexed { i, t -> BgiAdjustedSeries.AdjustedPoint(t, 100.0 + i) }
        assertNotNull(BgiAdjustedSeries.theilSen(punkte, jetzt))
    }

    /**
     * DIE BEIDEN DECKEL SIND VERSCHIEDENE DINGE. Mit den Vorgabewerten
     * sind beide 10 min - ein vertauschtes Feldpaar waere damit unsichtbar.
     */
    @Test
    fun `lueckendauer und altersfenster sind nicht dasselbe`() {
        val p = RejoinPolicy.enabled(maxGapMs = 6 * 60_000L, maxAgeMs = 9 * 60_000L)
        assertTrue(p.enabled)
        val lang = reihe(8, 8 * 60_000L, 4)
        assertEquals(SignalRejoin.Cause.GAP_TOO_LONG,
                     waehle(lang, lang[8], policy = p, now = lang[8] + 60_000L).cause,
                     "8 min Luecke muss am GAP-Deckel scheitern, nicht am Alter")
        val kurz = reihe(8, 4 * 60_000L, 4)
        assertEquals(SignalRejoin.Cause.TOO_OLD,
                     waehle(kurz, kurz[8], policy = p, now = kurz[8] + 10 * 60_000L).cause,
                     "4 min Luecke muss am ALTERS-Deckel scheitern, nicht an der Dauer")
        assertTrue(waehle(kurz, kurz[8], policy = p, now = kurz[8] + 60_000L).active)
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
        val gerade = reihe(8, RejoinPolicy.DEFAULT_MAX_GAP_MS, 4)
        assertTrue(waehle(gerade, gerade[8]).active)
    }

    /** Und das Altersfenster: irgendwann ist die Kadenz das Problem. */
    @Test
    fun `zu altes segment lockert nicht mehr`() {
        val ts = reihe(8, 200_000L, 4)
        val segment = ts[8]
        val w = waehle(ts, segment, now = segment + RejoinPolicy.DEFAULT_MAX_AGE_MS + 1)
        assertFalse(w.active)
        assertEquals(SignalRejoin.Cause.TOO_OLD, w.cause)
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
     * Element, also kein robuster Theil-Sen mehr.
     */
    @Test
    fun `unter 4x3 gibt es keinen wiedereinstieg`() {
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(3, 1)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(4, 2)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(3, 3)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(2, 1)).enabled)
        assertTrue(RejoinPolicy.enabled(MaturityPolicy.of(4, 3)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(6, 12)).enabled)
        assertFalse(RejoinPolicy.enabled(MaturityPolicy.of(4, 3, t0)).enabled)
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
}
