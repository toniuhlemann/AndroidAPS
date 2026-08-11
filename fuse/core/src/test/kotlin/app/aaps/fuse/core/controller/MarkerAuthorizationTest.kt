package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER MARKER AUTORISIERT INSULIN BEI GEMESSENEM TIEF.
 *
 * Tonis Entscheidung vom 11.08., ausdruecklich und nach Rueckfrage. Sie macht
 * den Mahlzeiten-Knopf zu einer insulin-autorisierenden Handlung und ist damit
 * die folgenreichste Einstellung in FUSE - deshalb steht hier jede Zusicherung
 * einzeln, auch die selbstverstaendlich wirkenden.
 *
 * Freigegeben wird AUSSCHLIESSLICH der markerfinanzierte Anteil. Das ist keine
 * zweite Regel, sondern strukturell: bei SAFETY_HOLD und GUARD_FLOOR ist die
 * Basisdosis 0, also ist alles, was danach herauskommt, der Lift aus der
 * Marker-Huelle.
 */
class MarkerAuthorizationTest {

    private val step = 0.05

    private fun state(iobTh: Double = 8.0, maxIob: Double = 8.0) = FuseController.State(
        health = Health.READY, safetyHold = true, phase = Phase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 55.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = step, maxSmbU = 0.3, pumpBusy = false,
    )

    /** Eine Lage im TIEF: Sicherheitsbahn deutlich unter dem Guard-Boden. */
    private fun planImTief(
        markerAuthorized: Boolean,
        markerActive: Boolean = true,
        spentU: Double = 0.0,
        envelopeU: Double = 1.2,
    ) = PrimeRelease.plan(
        PrimeRelease.Input(
            enabled = true,
            mealMarkerActive = markerActive,
            armedTsMs = 1_000_000L,
            windowStartTsMs = 0L,
            nowMs = 1_000_000L + 3 * 60_000L,
            envelopeU = envelopeU,
            spentU = spentU,
            safetyMinLowerMgdl = 62.0,      // BG im Tief, weit unter dem Boden
            guardFloorMgdl = 70.0,
            isfMgdlPerU = 55.0,
            pumpIncrementU = step,
            markerAuthorized = markerAuthorized,
        )
    )

    private fun blockiert(block: FuseController.Block) = FuseController.Decision(
        smbU = 0.0, tbr = FuseController.TbrAction.ZERO_TEMP, block = block,
        insulinReqU = 0.0, predAtReleaseMgdl = 90.0, minLowerMgdl = 62.0,
        bindingLimit = block.name,
    )

    // ---- Die Gegenproben ---------------------------------------------------

    /** LOW OHNE MARKER -> 0 U. Der Tiefschutz bleibt absolut. */
    @Test
    fun `LOW ohne Marker gibt nichts frei`() {
        val p = planImTief(markerAuthorized = false, markerActive = false)
        assertTrue(!p.active, "ohne Marker darf kein Plan entstehen: ${p.reason}")
        val d = PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, state())
        assertEquals(0.0, d.smbU, "keine Freigabe ohne Marker")
    }

    /**
     * LOW MIT MARKER, aber Einstellung AUS -> weiterhin 0 U. Der Schalter muss
     * wirken, sonst waere der Default egal.
     */
    @Test
    fun `LOW mit Marker aber ausgeschalteter Einstellung gibt nichts frei`() {
        val p = planImTief(markerAuthorized = false)
        assertTrue(!p.active, "die Freigangsprobe muss sperren: ${p.reason}")
        assertEquals(
            0.0,
            PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, state()).smbU,
        )
    }

    /** LOW MIT MARKER und Einstellung AN -> begrenzter markerExtra-Anteil. */
    @Test
    fun `LOW mit Marker gibt einen begrenzten Anteil frei`() {
        val p = planImTief(markerAuthorized = true)
        assertTrue(p.active, "der Plan muss stehen: ${p.reason}")
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorized = true,
        )
        assertTrue(d.smbU > 0.0, "der markerfinanzierte Anteil muss durchkommen")
        assertTrue(d.smbU <= p.floorU + 1e-9, "und nicht mehr als der Plan vorsieht")
        assertEquals("primeRelease", d.bindingLimit, "es ist der Marker-Kanal, kein anderer")
    }

    /** Dasselbe fuer den GUARD_FLOOR - das zweite der beiden Tore. */
    @Test
    fun `LOW mit Marker hebt auch den Guard-Floor-Block`() {
        val p = planImTief(markerAuthorized = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorized = true,
        )
        assertTrue(d.smbU > 0.0)
    }

    /**
     * KEINE FREIGABE EINER NORMALEN KORREKTURDOSIS. Der Lift hebt auf den
     * Prime-Floor, nicht auf den rechnerischen Bedarf - und der Floor kommt
     * ausschliesslich aus der Marker-Huelle.
     */
    @Test
    fun `nur der Marker-Anteil kommt durch, nicht der Korrekturbedarf`() {
        val p = planImTief(markerAuthorized = true)
        // Eine Basisentscheidung mit GROSSEM Bedarf, aber im Tief blockiert.
        val basis = blockiert(FuseController.Block.SAFETY_HOLD).copy(insulinReqU = 3.0)
        val d = PrimeRelease.lift(basis, p, state(), markerAuthorized = true)
        assertTrue(d.smbU <= p.floorU + 1e-9, "der Bedarf von 3,0 U darf nicht durchschlagen")
        assertTrue(d.smbU < 0.5, "die Menge kommt aus der Huelle, nicht aus insulinReq: ${d.smbU}")
    }

    /** DAS BUDGET WIRD VERBRAUCHT: eine ausgeschoepfte Huelle gibt nichts mehr. */
    @Test
    fun `eine verbrauchte Huelle gibt auch mit Marker nichts frei`() {
        val p = planImTief(markerAuthorized = true, spentU = 1.2, envelopeU = 1.2)
        assertTrue(!p.active, "verbrauchte Huelle: ${p.reason}")
        assertEquals(
            0.0,
            PrimeRelease.lift(
                blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
                markerAuthorized = true,
            ).smbU,
        )
    }

    /**
     * TECHNISCHE SPERREN BLEIBEN HART. Sie stehen bewusst NICHT in der
     * erweiterten Liste - kein Marker der Welt hebt einen Ledger-Hold, eine
     * belegte Pumpe oder eine fehlende Eingabe auf.
     */
    @Test
    fun `technische Sperren sind nicht uebersteuerbar`() {
        val p = planImTief(markerAuthorized = true)
        for (b in listOf(
            FuseController.Block.LEDGER_HOLD,
            FuseController.Block.PUMP_BUSY,
            FuseController.Block.HEALTH_NOT_READY,
            FuseController.Block.NO_INPUT,
            FuseController.Block.TAIL,
            FuseController.Block.MAX_IOB_REACHED,
            FuseController.Block.IOB_TH_REACHED,
        )) {
            val d = PrimeRelease.lift(blockiert(b), p, state(), markerAuthorized = true)
            assertEquals(0.0, d.smbU, "$b darf NICHT uebersteuerbar sein")
        }
    }

    /**
     * MARKERABLAUF -> LOW SPERRT WIEDER. Ohne aktiven Marker gibt es keinen
     * Plan, und damit auch keinen Weg an den Bloecken vorbei.
     */
    @Test
    fun `nach Markerablauf sperrt das Tief wieder`() {
        val p = planImTief(markerAuthorized = true, markerActive = false)
        assertTrue(!p.active)
        assertEquals(
            0.0,
            PrimeRelease.lift(
                blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
                markerAuthorized = true,
            ).smbU,
            "ohne laufenden Marker ist die Autorisierung erloschen",
        )
    }

    /**
     * DER SCHWANZ-HEADROOM KAPPT DEN AUTORISIERTEN LIFT NICHT MEHR.
     *
     * Diese Stelle liegt VOR allem anderen, und deshalb hilft kein Boden
     * weiter unten: der Lift ERZEUGT die Autorisierungsgrenze. Kappt der
     * Schwanz ihn hier auf 0, gibt es weiter unten nichts mehr zu schuetzen.
     * Gemessen am 11.08. ist der Schwanz-Headroom bei BG 62 genau das: <= 0.
     */
    @Test
    fun `der Schwanz-Headroom kappt den autorisierten Anteil nicht`() {
        val p = planImTief(markerAuthorized = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorized = true,
            tailHeadroomU = -5.0,          // der Schwanz sagt: gar nichts
        )
        assertTrue(d.smbU > 0.0, "eine Modellannahme darf den autorisierten Anteil nicht nullen")
        assertEquals(d.smbU, d.markerAuthorizedU, 1e-9)
    }

    /** OHNE Autorisierung kappt derselbe Headroom wie bisher. */
    @Test
    fun `ohne Autorisierung kappt der Schwanz-Headroom weiterhin`() {
        val p = planImTief(markerAuthorized = false, markerActive = true)
        // Ein Plan, der ohne die Autorisierung steht: hoher BG, kein Tief.
        val offen = PrimeRelease.plan(
            PrimeRelease.Input(
                enabled = true, mealMarkerActive = true,
                armedTsMs = 1_000_000L, windowStartTsMs = 0L,
                nowMs = 1_000_000L + 3 * 60_000L,
                envelopeU = 1.2, spentU = 0.0,
                safetyMinLowerMgdl = 160.0, guardFloorMgdl = 70.0,
                isfMgdlPerU = 55.0, pumpIncrementU = step,
                markerAuthorized = false,
            )
        )
        assertTrue(offen.active, "der Aufbau braucht einen stehenden Plan: ${offen.reason}")
        assertTrue(!p.active, "und im Tief steht ohne Autorisierung keiner")
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.NONE), offen, state(),
            tailHeadroomU = -5.0,
        )
        assertEquals(0.0, d.smbU, 1e-9, "ohne Autorisierung bleibt der Schwanz bindend")
    }

    // ---- DIE GRENZE ENTSTEHT AUCH OHNE ANHEBUNG ---------------------------

    /**
     * RANDFALL 1 (Toni 11.08.): die Basisdosis ist bereits GROESSER als der
     * Markerboden.
     *
     * Der Lift gibt dann unveraendert zurueck - richtig, er soll nicht
     * senken. Er stempelte aber auch nicht, und damit war `authCapU` null.
     * Verwarf das finale Veto danach die groessere Basisdosis, blieben 0 U
     * statt der autorisierten 0,20 U: der Markerdruck verlor seine Wirkung
     * gerade dadurch, dass FUSE ohnehin dosieren wollte.
     *
     * Die Grenze sagt, WIEVIEL der Knopfdruck deckt - nicht, ob dieser
     * Aufruf die Menge erhoeht hat.
     */
    @Test
    fun `die Autorisierungsgrenze entsteht auch wenn die Basis groesser ist`() {
        val p = planImTief(markerAuthorized = true)
        // Basis deutlich ueber dem Plan-Boden.
        val basis = blockiert(FuseController.Block.NONE).copy(smbU = p.floorU + 0.15)
        val d = PrimeRelease.lift(basis, p, state(), markerAuthorized = true)

        assertEquals(basis.smbU, d.smbU, 1e-9, "der Lift darf die groessere Basis nicht senken")
        assertTrue(
            d.markerAuthorizedU > 0.0,
            "die Grenze muss trotzdem entstehen - sonst ist sie beim naechsten Veto weg",
        )
        assertTrue(
            d.markerAuthorizedU <= p.floorU + 1e-9,
            "und sie ist der Markerboden, nicht die Basis: ${d.markerAuthorizedU}",
        )
    }

    /** OHNE Autorisierung entsteht auch auf diesem Weg keine Grenze. */
    @Test
    fun `ohne Autorisierung entsteht auch bei groesserer Basis keine Grenze`() {
        val p = planImTief(markerAuthorized = true)
        val basis = blockiert(FuseController.Block.NONE).copy(smbU = p.floorU + 0.15)
        assertEquals(0.0, PrimeRelease.lift(basis, p, state()).markerAuthorizedU, 1e-9)
    }

    // ---- DIE TRANSPORT-KOPPLUNG -------------------------------------------

    /**
     * DER TEST, AN DEM TONIS AUFLAGE ZU PENDING_MODEL_TOO_SHORT HAENGT.
     *
     * Die Freigabe bei diesem Ablehnungsgrund ist NUR zulaessig, solange die
     * offene Transportmenge - publiziertes, im IOB noch nicht sichtbares
     * Insulin - von BEIDEN Spielraeumen abgezogen wird. Sonst wird sie ein
     * zweites Mal finanziert: der Grund sagt ja gerade, dass ihre Bahnwirkung
     * unbekannt ist.
     *
     * KEINE SIGNATUR KANN DAS HALTEN, und deshalb steht es hier. Der Runner
     * reicht dieselbe Zahl an Politik und Lift - das verhindert VERSCHIEDENE
     * Zahlen, nicht einen fehlenden Abzug. Ein Boolean `transportAccounted`
     * war noch schwaecher: "endlich" gilt auch fuer 0,0.
     *
     * DER AUFBAU ist so gewaehlt, dass der Abzug ALLEIN entscheidet:
     *   capIob 0,50, iobTH = maxIOB = 0,60  ->  Spielraum 0,10 = zwei Schritte
     *   Transportmenge 0,05                 ->  Spielraum 0,05 = ein Schritt
     *   Transportmenge 0,10                 ->  Spielraum 0,00 = nichts
     * Die MITTLERE Zeile ist die wichtige: sie zeigt, dass der Abzug der Menge
     * nach wirkt und nicht als An-/Aus-Schalter. Ein Test mit nur den beiden
     * Endpunkten waere auch mit einem groben `if (transport > 0) caps = 0` gruen.
     *
     * MUTATIONSPROBE, nachgemessen: entfernt man `- transportCommitmentU` aus
     * dem iobTH-Term ODER aus dem maxIOB-Term in PrimeRelease.lift, liefert
     * dieser Test 0,05 U statt 0,0 und wird rot. Beide einzeln geprueft.
     */
    @Test
    fun `die Transportmenge kappt den autorisierten Anteil ueber BEIDE Spielraeume`() {
        val p = planImTief(markerAuthorized = true)
        val eng = FuseController.State(
            health = Health.READY, safetyHold = true, phase = Phase.REARMING,
            netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
            iobThU = 0.6, maxIobU = 0.6, targetMgdl = 100.0, isfMgdlPerU = 55.0,
            smbRatioCorrection = 0.15, smbRatioRise = 0.35,
            rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = step, maxSmbU = 0.3, pumpBusy = false,
        )

        // ZUERST der Gegenbeleg: OHNE Transportmenge reicht der Spielraum. Ohne
        // ihn koennte der Test auch dann gruen sein, wenn er aus einem ganz
        // anderen Grund nichts freigibt.
        val ohne = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, eng,
            markerAuthorized = true, transportCommitmentU = 0.0,
        )
        assertEquals(
            2 * step, ohne.smbU, 1e-9,
            "der Aufbau muss ohne Transportmenge zwei Schritte hergeben",
        )

        // DIE MITTLERE STUFE: der Abzug wirkt der MENGE NACH.
        assertEquals(
            step,
            PrimeRelease.lift(
                blockiert(FuseController.Block.SAFETY_HOLD), p, eng,
                markerAuthorized = true, transportCommitmentU = 0.05,
            ).smbU, 1e-9,
            "die halbe Transportmenge muss den halben Spielraum kosten",
        )

        // UND JETZT mit unterwegs befindlichem Insulin: nichts mehr.
        val mit = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, eng,
            markerAuthorized = true, transportCommitmentU = 0.10,
        )
        assertEquals(
            0.0, mit.smbU, 1e-9,
            "unterwegs befindliches Insulin darf nicht ein zweites Mal finanziert werden",
        )
        assertEquals(
            0.0, mit.markerAuthorizedU, 1e-9,
            "und es entsteht auch keine Autorisierungsgrenze, die ein Boden spaeter hebt",
        )
    }

    /**
     * BEIDE Spielraeume EINZELN. Der Test oben laesst iobTH und maxIOB gleich
     * gross - dort deckt eine Mutation beide auf einmal ab. Hier bindet
     * jeweils nur EINER, damit ein entfernter Abzug im ANDEREN nicht
     * unbemerkt bleibt.
     */
    @Test
    fun `die Transportmenge kappt auch wenn nur eine der beiden Grenzen bindet`() {
        val p = planImTief(markerAuthorized = true)
        fun eng(iobTh: Double, maxIob: Double) = FuseController.State(
            health = Health.READY, safetyHold = true, phase = Phase.REARMING,
            netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
            iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 55.0,
            smbRatioCorrection = 0.15, smbRatioRise = 0.35,
            rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = step, maxSmbU = 0.3, pumpBusy = false,
        )
        // capIob 0,50; die jeweils ANDERE Grenze ist weit offen.
        for ((name, st) in listOf(
            "nur iobTH bindet" to eng(iobTh = 0.6, maxIob = 8.0),
            "nur maxIOB bindet" to eng(iobTh = 8.0, maxIob = 0.6),
        )) {
            assertEquals(
                2 * step,
                PrimeRelease.lift(
                    blockiert(FuseController.Block.SAFETY_HOLD), p, st,
                    markerAuthorized = true, transportCommitmentU = 0.0,
                ).smbU, 1e-9, "$name: ohne Transportmenge zwei Schritte",
            )
            assertEquals(
                0.0,
                PrimeRelease.lift(
                    blockiert(FuseController.Block.SAFETY_HOLD), p, st,
                    markerAuthorized = true, transportCommitmentU = 0.10,
                ).smbU, 1e-9, "$name: mit Transportmenge nichts",
            )
        }
    }

    // ---- OHNE BAHN (predictorfreier Markerpfad) ---------------------------

    /**
     * DIE DOSIERLOGIK DES PREDICTORFREIEN PFADES, in derselben
     * Zusammensetzung, die `markerFallbackCycle` baut: plan(null) + lift ohne
     * Schwanz-Headroom. Wird die Bahn verworfen, ist das hier die GANZE
     * Rechnung - es gibt keine Kandidatensuche, keinen Guard, keinen Schwanz.
     */
    @Test
    fun `ohne Bahn entsteht mit Autorisierung eine Menge`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorized = true))
        assertTrue(p.active, "der Plan muss ohne Bahn stehen koennen: ${p.reason}")
        assertEquals("PRIME", p.reason)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorized = true,
            tailHeadroomU = null,          // ohne Bahn gibt es keinen
        )
        assertTrue(d.smbU > 0.0, "ohne Bahn und mit Autorisierung muss etwas herauskommen")
        assertEquals(d.smbU, d.markerAuthorizedU, 1e-9, "und alles davon ist autorisiert")
    }

    /**
     * OHNE AUTORISIERUNG IST DIE FEHLENDE BAHN EIN NEIN, und zwar unter
     * eigenem Namen. NO_TRAJECTORY heisst "es gab nichts zu pruefen",
     * NOT_FINITE hiesse "die Zahl war kaputt" - zwei verschiedene Lagen, die
     * unter einem Grund im Export nicht mehr aufloesbar waeren. Genau
     * deshalb ist das Feld nullbar und nicht NaN.
     */
    @Test
    fun `ohne Bahn und ohne Autorisierung sperrt der Plan`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorized = false))
        assertTrue(!p.active)
        assertEquals("NO_TRAJECTORY", p.reason)
    }

    /** Eine KAPUTTE Bahn bleibt NOT_FINITE - die beiden duerfen nicht
     *  zusammenfallen, sonst hat die Nullbarkeit nichts gebracht. */
    @Test
    fun `eine nicht endliche Bahn bleibt NOT_FINITE`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorized = true).copy(safetyMinLowerMgdl = Double.NaN))
        assertTrue(!p.active)
        assertEquals("NOT_FINITE", p.reason)
    }

    private fun ohneBahn(markerAuthorized: Boolean) = PrimeRelease.Input(
        enabled = true, mealMarkerActive = true,
        armedTsMs = 1_000_000L, windowStartTsMs = 0L,
        nowMs = 1_000_000L + 3 * 60_000L,
        envelopeU = 1.2, spentU = 0.0,
        safetyMinLowerMgdl = null,
        guardFloorMgdl = 70.0, isfMgdlPerU = 55.0, pumpIncrementU = step,
        markerAuthorized = markerAuthorized,
    )

    /**
     * DIE HARTEN MENGENDECKEL BLEIBEN. maxIOB und iobTH kappen den Lift auch
     * im autorisierten Fall - sie sind keine Tiefschutz-Tore.
     */
    @Test
    fun `maxIOB kappt auch den autorisierten Anteil`() {
        val p = planImTief(markerAuthorized = true)
        val eng = state(iobTh = 0.5, maxIob = 0.5)   // capIob 0,5 -> Spielraum 0
        val d = PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, eng, markerAuthorized = true)
        assertEquals(0.0, d.smbU, "ein erschoepftes IOB-Budget bleibt bindend")
    }

    /**
     * Und das SCHUETZENDE Zero-Temp bleibt. Es wird nicht "LOW abgeschaltet" -
     * die Basalabsenkung laeuft parallel weiter, waehrend der Marker-Anteil
     * hinausgeht. Beides zugleich ist kein Widerspruch: das eine ist die
     * erklaerte Mahlzeit, das andere der Schutz gegen ihr Ausbleiben.
     */
    @Test
    fun `das schuetzende Zero-Temp bleibt neben der Freigabe bestehen`() {
        val p = planImTief(markerAuthorized = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorized = true,
        )
        assertTrue(d.smbU > 0.0)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr, "die Basalabsenkung darf nicht verlorengehen")
    }
}
