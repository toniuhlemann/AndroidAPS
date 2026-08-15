package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S0-Telemetrie des Reglers (Gruppe B).
 *
 * Wieder keine Regeln, sondern ZUSAMMENHAENGE: dass die Telemetrie dieselbe
 * Groesse beschreibt wie die Entscheidung, und dass sie ueberhaupt an jeder
 * Rueckgabestelle ankommt. Der zweite Punkt ist der wichtigere - ein
 * Telemetriefeld, das an einem von acht Ausgaengen fehlt, sieht im Export nicht
 * wie eine Luecke aus, sondern wie ein Messwert (0, `false`, leere Liste).
 */
class DecisionTelemetryTest {

    /** Bahn mit einer FALLENDEN unteren Kante, damit `timeToFloor` etwas zu
     *  finden hat. `lowerAt(i)` bestimmt die Sicherheitsbahn Punkt fuer Punkt. */
    private fun pred(
        bg: Double = 160.0,
        anchor: Double = bg,
        lowerAt: (Int) -> Double,
    ): PredictorResult {
        val pts = (1..60).map { TrajectoryPoint(it, it * 60_000L, bg, lowerAt(it), 0.0, 0.0, 0.0) }
        // Minimum UND Index genau wie `TrajectoryCore`: der Lauf startet AM
        // ANKER (Index 0) und uebernimmt nur bei STRIKTER Unterschreitung. Eine
        // Nachbildung, die bei Minute 1 anfaengt, meldet auf einer steigenden
        // Bahn faelschlich Index 1 - und pruefte dann eine Bahn, die es nicht
        // gibt.
        var minLower = anchor
        var minAt = 0
        for (i in 1..60) if (lowerAt(i) < minLower) { minLower = lowerAt(i); minAt = i }
        return PredictorResult(
            points = pts, predictionAnchorTs = 0L, bgAtAnchor = anchor,
            minMeanBg = bg, minLowerBg = minLower,
            timeToMinLowerMin = minAt,
            bgAtHorizonMean = bg, bgAtHorizonLower = lowerAt(60),
            lineageKind = "ACTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
            modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private fun flat(bg: Double = 160.0, lower: Double = 120.0) = pred(bg) { lower }

    private fun state(
        iobTh: Double = 4.0,
        maxIob: Double = 8.0,
        netIob: Double = 1.0,
        maxSmb: Double = 0.75,
        smbRatio: Double = 0.5,
    ) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = netIob, bolusIobU = netIob, basalIobU = 0.0,
        iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        smbRatioCorrection = smbRatio, smbRatioRise = smbRatio,
        rSignedMgdlPerMin = null, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = maxSmb, pumpBusy = false,
    )

    // ---- K2: die vollstaendige Kappenliste --------------------------------

    /**
     * DER FALL, DER K2 AUSGELOEST HAT - und er ist auf diesem Geraet der
     * Normalzustand, nicht der Randfall.
     *
     * `iobThU = percent/100 * maxIobU`; bei `IobThPercent = 100` sind beide
     * Spielraeume BITGENAU gleich. `minByOrNull` nennt dann die erste der
     * Liste, also `iobThHeadroom` - und ein Privileg, das iobTH als erweiterbar
     * liest, wuerde damit den harten maxIOB erweitern, ohne dass es irgendwo
     * sichtbar waere.
     *
     * Der Test friert deshalb NICHT ein, welche Kappe genannt wird (das ist
     * heute Listenreihenfolge), sondern dass die Liste BEIDE als aktiv fuehrt.
     */
    @Test
    fun `bei gleichem iobTH und maxIOB fuehrt die Kappenliste beide als aktiv`() {
        // iobTh == maxIob: genau die Live-Einstellung IobThPercent = 100.
        val d = FuseController.decide(state(iobTh = 2.0, maxIob = 2.0, netIob = 1.9), flat(), evidenceCreditActive = false)

        val byName = d.caps.associateBy { it.name }
        val iobTh = byName.getValue("iobThHeadroom")
        val maxIob = byName.getValue("maxIobHeadroom")
        assertEquals(iobTh.valueU, maxIob.valueU, 0.0, "die beiden Spielraeume sind hier identisch")
        assertTrue(iobTh.active && maxIob.active, "beide muessen als aktiv gefuehrt sein: ${d.caps}")

        // Und genau hier taeuscht `bindingLimit`: er nennt nur eine von beiden.
        assertEquals("iobThHeadroom", d.bindingLimit)
    }

    /** Die Liste ist VOLLSTAENDIG, nicht nur die bindende plus Nachbarn. */
    @Test
    fun `die Kappenliste enthaelt jede geprueften Grenze`() {
        val d = FuseController.decide(state(), flat(), evidenceCreditActive = false)
        val names = d.caps.map { it.name }.toSet()
        assertEquals(setOf("smbRatio", "iobThHeadroom", "maxIobHeadroom", "maxSmb"), names)
        // Die kleinste ist immer aktiv - sonst waere die Markierung sinnlos.
        val min = d.caps.minOf { it.valueU }
        assertTrue(d.caps.single { it.valueU == min }.active)
    }

    /**
     * AKTIV HEISST GLEICHE TICKZAHL, nicht "innerhalb eines Pumpenschritts".
     *
     * Die erste Fassung prueft `v <= min + inc` und verfehlte damit ihre eigene
     * Begruendung: 1,00 und 1,05 U sind bei 0,05er Schritt 20 gegen 21 Ticks,
     * also VERSCHIEDENE lieferbare Mengen. Die zweite Kappe hat nichts begrenzt
     * und waere trotzdem als mitbindend gefuehrt worden - genau die Klasse von
     * stillem Fehler, gegen die die Liste ueberhaupt gebaut wurde.
     */
    @Test
    fun `aktiv heisst gleiche Tickzahl und nicht ein Schritt Abstand`() {
        val caps = FuseController.capsOf(
            0.05,
            "a" to 1.00,   // 20 Ticks - die kleinste
            "b" to 1.04,   // 20 Ticks: liefert dieselbe Menge
            "c" to 1.05,   // 21 Ticks: GENAU ein Schritt mehr, liefert mehr
            "d" to 1.06,   // 21 Ticks
        )
        assertTrue(caps.single { it.name == "a" }.active)
        assertTrue(caps.single { it.name == "b" }.active, "gleiche Tickzahl")
        assertFalse(caps.single { it.name == "c" }.active, "ein ganzer Schritt ist ein Unterschied")
        assertFalse(caps.single { it.name == "d" }.active)
    }

    /**
     * Die Tickrechnung der Kappen ist DIESELBE wie die der Dosis - samt
     * Epsilon.
     *
     * Ohne das Epsilon verliert `floor` an exakten Vielfachen einen ganzen
     * Schritt (0,15 U sind als Double 0,1499999999999999944…). Eine
     * Kappeneinstufung mit anderer Rundung als die Freigabe wuerde die Grenzen
     * nach einer Arithmetik einteilen, die nicht entschieden hat.
     */
    @Test
    fun `die Kappen rastern wie die Dosis - auch an exakten Vielfachen`() {
        for (mult in 1..40) {
            val exact = mult * 0.05
            assertEquals(
                mult.toLong(), FuseController.ticksOf(exact, 0.05),
                "exaktes Vielfaches $mult verliert einen Schritt"
            )
        }
        // Und die Einstufung folgt daraus: 0,15 und 0,15 sind gleich, 0,15 und
        // 0,20 nicht - trotz nur eines Schritts Abstand.
        val caps = FuseController.capsOf(0.05, "x" to 0.15, "y" to 0.15, "z" to 0.20)
        assertTrue(caps.single { it.name == "x" }.active)
        assertTrue(caps.single { it.name == "y" }.active)
        assertFalse(caps.single { it.name == "z" }.active)
    }

    /** Ohne brauchbaren Pumpenschritt bleibt nur exakte Gleichheit - eine
     *  ehrliche Entartung statt einer erfundenen Toleranz. */
    @Test
    fun `ohne Pumpenschritt gilt exakte Gleichheit`() {
        val caps = FuseController.capsOf(0.0, "a" to 1.0, "b" to 1.0, "c" to 1.0000001)
        assertTrue(caps.single { it.name == "a" }.active)
        assertTrue(caps.single { it.name == "b" }.active)
        assertFalse(caps.single { it.name == "c" }.active)
    }

    /**
     * Auch der ERSCHOEPFTE Fall berichtet beide IOB-Grenzen.
     *
     * Die Testreihenfolge im Regler (maxIOB vor iobTH) ist richtig herum und
     * bleibt so - aber ohne die Liste waere im Trail nicht zu sehen, dass die
     * jeweils andere genauso erschoepft war.
     */
    @Test
    fun `der erschoepfte IOB-Fall berichtet beide Grenzen`() {
        val d = FuseController.decide(state(iobTh = 1.0, maxIob = 1.0, netIob = 2.0), flat(), evidenceCreditActive = false)
        assertEquals(FuseController.Block.MAX_IOB_REACHED, d.block)
        assertEquals(setOf("maxIobHeadroom", "iobThHeadroom"), d.caps.map { it.name }.toSet())
        assertTrue(d.caps.all { it.valueU < 0.0 })
    }

    // ---- Die gebremste Bahn, aufgeteilt -----------------------------------

    /**
     * Die Bremse kann zwei verschiedene Dinge tun, und bis S0 war beides ein
     * Bit. Hier senkt sie NUR die Sicherheitsbahn (Guard), nicht den Bedarf.
     */
    @Test
    fun `eine Bremse die nur den Guard senkt setzt nur das Guard-Bit`() {
        val main = flat(bg = 160.0, lower = 120.0)
        // Gleicher Mittelwert (Bedarf unveraendert), tiefere untere Kante.
        val brake = flat(bg = 160.0, lower = 95.0)
        val d = FuseController.decide(state(), main, restraint = brake, evidenceCreditActive = false)

        assertTrue(d.restraintBoundGuard)
        assertFalse(d.restraintBoundDemand)
        assertTrue(d.restraintBound, "das abgeleitete Bit muss weiter stimmen")
        // Und das AUSMASS ist ablesbar, nicht nur das Vorhandensein.
        assertEquals(120.0, d.minLowerMainMgdl)
        assertEquals(95.0, d.minLowerMgdl)
    }

    /** Umgekehrt: dieselbe untere Kante, aber ein niedrigerer Mittelwert. */
    @Test
    fun `eine Bremse die nur den Bedarf senkt setzt nur das Bedarfs-Bit`() {
        val main = flat(bg = 200.0, lower = 120.0)
        val brake = flat(bg = 150.0, lower = 120.0)
        val d = FuseController.decide(state(), main, restraint = brake, evidenceCreditActive = false)

        assertFalse(d.restraintBoundGuard)
        assertTrue(d.restraintBoundDemand)
        assertEquals(120.0, d.minLowerMainMgdl)
        assertEquals(120.0, d.minLowerMgdl)
    }

    /** Ohne Bremse ist beides falsch - und `minLowerMain` trotzdem gesetzt. */
    @Test
    fun `ohne Bremse bleiben beide Bits falsch`() {
        val d = FuseController.decide(state(), flat(), evidenceCreditActive = false)
        assertFalse(d.restraintBoundGuard)
        assertFalse(d.restraintBoundDemand)
        assertFalse(d.restraintBound)
        assertEquals(120.0, d.minLowerMainMgdl)
    }

    // ---- I16: WIE TIEF und WIE BALD ---------------------------------------

    /**
     * Der Guard ist ein Schwellentest - eine Bahn bei 69 war von einer bei
     * -382 nicht zu unterscheiden. Die beiden Zahlen machen den Unterschied
     * sichtbar, und sie muessen zur ENTSCHIEDENEN Bahn passen: das Defizit ist
     * exakt `guardFloor - minLowerMgdl`.
     */
    @Test
    fun `das Bodendefizit passt zur entschiedenen Bahn`() {
        val limits = FuseController.Limits(guardFloorMgdl = 80.0)
        // Faellt ab Minute 30 unter 80.
        val p = pred(bg = 160.0, anchor = 160.0) { i -> if (i < 30) 100.0 else 61.0 }
        val d = FuseController.decide(state(), p, limits, evidenceCreditActive = false)

        assertEquals(FuseController.Block.GUARD_FLOOR, d.block)
        assertEquals(61.0, d.minLowerMgdl)
        assertEquals(19.0, d.floorDeficitMgdl, 1e-12)
        assertEquals(30, d.timeToFloorMin)
    }

    /** Haelt die Bahn den Boden, ist das Defizit 0 und die Zeit `null` -
     *  "nie im Fenster" und ausdruecklich nicht "sofort". */
    @Test
    fun `eine sichere Bahn meldet kein Defizit und keine Zeit`() {
        val d = FuseController.decide(state(), flat(), FuseController.Limits(guardFloorMgdl = 80.0), evidenceCreditActive = false)
        assertEquals(0.0, d.floorDeficitMgdl)
        assertNull(d.timeToFloorMin)
    }

    /** Der Boden wird ueber BEIDE Bahnen geprueft, so wie der Guard selbst. */
    @Test
    fun `auch die Bremsbahn kann den Boden reissen`() {
        val limits = FuseController.Limits(guardFloorMgdl = 80.0)
        val main = flat(bg = 160.0, lower = 120.0)
        val brake = pred(bg = 160.0, anchor = 160.0) { i -> if (i < 10) 120.0 else 70.0 }
        val d = FuseController.decide(state(), main, limits, restraint = brake, evidenceCreditActive = false)

        assertEquals(10.0, d.floorDeficitMgdl, 1e-12)
        assertEquals(10, d.timeToFloorMin)
    }

    // ---- I16: der Zeitindex gehoert zur ENTSCHIEDENEN Bahn ----------------

    /**
     * DER LIVE-WIDERSPRUCH VOM 10.08., als Test festgehalten.
     *
     * Im Trail stand `minLower = 71,17` bei Anker ~90,61 und Zeitindex 0.
     * Beide Zahlen waren richtig - die HAUPTbahn hatte ihr Minimum wirklich am
     * Anker, die 71,17 kamen aus der BREMSbahn -, aber nebeneinander gelesen
     * beschrieben sie eine unmoegliche Bahn.
     *
     * Hier derselbe Aufbau: Hauptbahn steigt (Minimum am Anker), Bremsbahn
     * faellt spaeter tiefer. Der kombinierte Index muss die Bremse zeigen.
     */
    @Test
    fun `der kombinierte Zeitindex zeigt die Bahn die das Minimum haelt`() {
        val main = pred(bg = 160.0, anchor = 90.61) { 120.0 }            // steigt: Min am Anker
        val brake = pred(bg = 160.0, anchor = 90.61) { i -> if (i < 40) 120.0 else 71.17 }
        val d = FuseController.decide(state(), main, restraint = brake, evidenceCreditActive = false)

        // Die Hauptbahn sagt weiterhin "Anker" - und das stimmt fuer SIE.
        assertEquals(0, main.timeToMinSafetyLowerMin)
        // Entschieden wurde aber gegen 71,17, und der gehoert Minute 40.
        assertEquals(71.17, d.minLowerMgdl)
        assertEquals(40, d.timeToMinCombinedMin)
    }

    /**
     * Der kombinierte Index und das kombinierte Minimum muessen ZUSAMMEN
     * passen - sonst ist der Widerspruch nur verschoben.
     */
    @Test
    fun `am kombinierten Index steht wirklich das kombinierte Minimum`() {
        val main = pred(bg = 160.0, anchor = 150.0) { i -> if (i < 20) 130.0 else 108.0 }
        val brake = pred(bg = 160.0, anchor = 150.0) { i -> if (i < 50) 140.0 else 99.0 }
        val d = FuseController.decide(state(), main, restraint = brake, evidenceCreditActive = false)

        val at = d.timeToMinCombinedMin!!
        val amIndex = minOf(
            main.points.single { it.offsetMin == at }.safetyLowerBg,
            brake.points.single { it.offsetMin == at }.safetyLowerBg,
        )
        assertEquals(d.minLowerMgdl, amIndex)
    }

    /** Ohne Bremse ist der kombinierte Index der der Hauptbahn - und 0 heisst
     *  weiterhin "der Anker ist das Minimum". */
    @Test
    fun `ohne Bremse folgt der kombinierte Index der Hauptbahn`() {
        val steigend = pred(bg = 160.0, anchor = 100.0) { 120.0 }
        assertEquals(0, FuseController.decide(state(), steigend, evidenceCreditActive = false).timeToMinCombinedMin)

        val fallend = pred(bg = 160.0, anchor = 150.0) { i -> if (i < 25) 140.0 else 110.0 }
        assertEquals(25, FuseController.decide(state(), fallend, evidenceCreditActive = false).timeToMinCombinedMin)
    }

    // ---- Der Punkt, an dem Telemetrie sonst still verschwindet -------------

    /**
     * JEDE Rueckgabestelle traegt die Telemetrie.
     *
     * Der Regler hat acht Ausgaenge. Ein Feld, das an einem davon fehlt, faellt
     * nicht auf: es sieht im Export aus wie "nicht gebremst" oder "kein
     * Defizit". Deshalb faehrt dieser Test die erreichbaren Bloecke ab und
     * prueft an jedem dieselbe, unabhaengig bekannte Zahl.
     */
    @Test
    fun `jeder Ausgang traegt dieselbe Telemetrie`() {
        val limits = FuseController.Limits(guardFloorMgdl = 80.0)
        val main = flat(bg = 200.0, lower = 120.0)
        val brake = flat(bg = 200.0, lower = 110.0)   // senkt nur den Guard

        val faelle = listOf(
            // Anker bewusst HOCH: `minSafetyLowerBg` schliesst den Anker ein,
            // ein Anker auf Hoehe der unteren Kante wuerde beide Bahnen auf
            // denselben Wert ziehen und das Bremsbit unbeabsichtigt loeschen.
            FuseController.Block.NO_DEMAND to FuseController.decide(
                state(), pred(bg = 90.0, anchor = 160.0) { 110.0 }, limits,
                restraint = pred(bg = 90.0, anchor = 160.0) { 100.0 }, evidenceCreditActive = false
            ),
            FuseController.Block.MAX_IOB_REACHED to FuseController.decide(
                state(iobTh = 1.0, maxIob = 1.0, netIob = 2.0), main, limits, restraint = brake, evidenceCreditActive = false
            ),
            FuseController.Block.IOB_TH_REACHED to FuseController.decide(
                state(iobTh = 1.0, maxIob = 8.0, netIob = 2.0), main, limits, restraint = brake, evidenceCreditActive = false
            ),
            FuseController.Block.NONE to FuseController.decide(state(), main, limits, restraint = brake, evidenceCreditActive = false),
        )

        for ((erwartet, d) in faelle) {
            assertEquals(erwartet, d.block, "falscher Ausgang getroffen")
            assertTrue(d.restraintBoundGuard, "$erwartet fuehrt das Bremsbit nicht mit")
            assertNotNull(d.minLowerMainMgdl, "$erwartet fuehrt minLowerMain nicht mit")
            assertEquals(0.0, d.floorDeficitMgdl, "$erwartet: keine dieser Bahnen reisst den Boden")
        }
    }

    // ---- Die Kappenliste darf ihre Stufe nicht ueberleben ----------------

    /**
     * DER WIDERSPRUCH, GEGEN DEN DIE LISTE UEBERHAUPT GEBAUT WURDE.
     *
     * `caps` ueberlebte jedes nachgelagerte `.copy()`, waehrend `smbU` und
     * `bindingLimit` neu geschrieben wurden. Ein Datensatz konnte damit eine
     * Menge zeigen, die GROESSER ist als die Kappe, die er als bindend
     * markiert. Schlimmer: `CandidateSearch` fuehrt dieselben NAMEN mit
     * ledgerkorrigierten WERTEN - gleiche Beschriftung, andere Zahl, kein
     * sichtbarer Unterschied.
     */
    @Test
    fun `die Kandidatenstufe ersetzt die Kappenliste der Basis`() {
        val base = FuseController.decide(state(), flat(bg = 300.0), evidenceCreditActive = false)
        assertEquals(FuseController.STAGE_BASE, base.capsStage)
        assertTrue(base.caps.isNotEmpty())

        val result = CandidateSearch.Result(
            smbU = base.smbU / 2.0, reject = null, bindingLimit = "iobThHeadroom",
            baselineMeanAtReleaseMgdl = 300.0, meanWithCandidateMgdl = 280.0,
            minLowerWithCandidateMgdl = 120.0, effectPerUAtReleaseMgdl = 40.0,
            candidatesEvaluated = 3,
            // DIESELBEN Namen, ANDERE Werte - der ledgerkorrigierte Satz.
            limits = listOf("iobThHeadroom" to 0.40, "maxIobHeadroom" to 0.90),
        )
        val gated = CandidateGate.apply(base, result, 0.05)

        assertEquals(FuseController.STAGE_CANDIDATE, gated.capsStage)
        assertEquals(setOf("iobThHeadroom", "maxIobHeadroom"), gated.caps.map { it.name }.toSet())
        // 0,40 statt der 3,0 aus der Basisliste: gleicher Name, andere Zahl -
        // das ist die Verwechslung, die ohne Stufenwechsel unbemerkt bliebe.
        assertEquals(0.40, gated.caps.single { it.name == "iobThHeadroom" }.valueU)
        assertEquals(3.0, base.caps.single { it.name == "iobThHeadroom" }.valueU)
        // Und die Kernaussage: keine markierte Kappe ist kleiner als die
        // gelieferte Menge.
        val bindend = gated.caps.filter { it.active }.minOf { it.valueU }
        assertTrue(gated.smbU <= bindend + 1e-9, "smbU=${gated.smbU} > bindende Kappe $bindend")
    }

    /** Wo die Grenzen nicht aufzaehlbar sind, steht die Stufe - und die Liste
     *  ist leer. Das ist eine Aussage, kein Fehlen. */
    @Test
    fun `eine leere Liste traegt trotzdem ihre Stufe`() {
        val d = FuseController.decide(state(), flat(), evidenceCreditActive = false)
            .copy(caps = emptyList(), capsStage = FuseController.STAGE_PRIME)
        assertTrue(d.caps.isEmpty())
        assertEquals(FuseController.STAGE_PRIME, d.capsStage)
    }

    // ---- NaN: lieber keine Markierung als eine falsche -------------------

    /**
     * Die beiden Reduktionen sind NICHT dieselbe: `minOfOrNull` propagiert NaN,
     * `minByOrNull` (Dosispfad) sortiert NaN ans Ende. Ohne Riegel waere die
     * Markierung exakt invertiert - die entscheidende Kappe inaktiv, die
     * unbrauchbare aktiv.
     */
    @Test
    fun `eine NaN-Kappe markiert nichts statt das Falsche`() {
        val caps = FuseController.capsOf(0.05, "a" to 1.0, "b" to Double.NaN)
        assertTrue(caps.none { it.active }, "keine Markierung ist richtig, eine falsche nicht: $caps")
        // Gegenprobe: der Dosispfad haette "a" gewaehlt.
        assertEquals("a", listOf("a" to 1.0, "b" to Double.NaN).minByOrNull { it.second }!!.first)
    }

    /** Unter null ist nichts lieferbar - dort trennt die Tickzahl Groessen,
     *  die alle dasselbe bedeuten. */
    @Test
    fun `bei erschoepftem Spielraum sind alle nichtpositiven Kappen aktiv`() {
        val caps = FuseController.capsOf(0.05, "maxIobHeadroom" to -0.1, "iobThHeadroom" to -3.1)
        assertTrue(caps.all { it.active }, "beide sind erschoepft, beide haben gebunden: $caps")
    }

    /** Der Divisor der Rasterung wird geprueft wie alles andere - sonst
     *  verliesse eine NaN-Dosis den Regler. */
    @Test
    fun `ein unbrauchbarer Pumpenschritt wird im State abgewiesen`() {
        for (bad in listOf(0.0, -0.05, Double.NaN)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                state().copy(pumpIncrementU = bad)
            }
            assertTrue(e.message!!.contains("Pumpenschritt"), e.message!!)
        }
    }
}
