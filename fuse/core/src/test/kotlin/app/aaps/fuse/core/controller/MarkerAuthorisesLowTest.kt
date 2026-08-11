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
class MarkerAuthorisesLowTest {

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
        markerAuthorisesLow: Boolean,
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
            markerAuthorisesLow = markerAuthorisesLow,
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
        val p = planImTief(markerAuthorisesLow = false, markerActive = false)
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
        val p = planImTief(markerAuthorisesLow = false)
        assertTrue(!p.active, "die Freigangsprobe muss sperren: ${p.reason}")
        assertEquals(
            0.0,
            PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, state()).smbU,
        )
    }

    /** LOW MIT MARKER und Einstellung AN -> begrenzter markerExtra-Anteil. */
    @Test
    fun `LOW mit Marker gibt einen begrenzten Anteil frei`() {
        val p = planImTief(markerAuthorisesLow = true)
        assertTrue(p.active, "der Plan muss stehen: ${p.reason}")
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorisesLow = true,
        )
        assertTrue(d.smbU > 0.0, "der markerfinanzierte Anteil muss durchkommen")
        assertTrue(d.smbU <= p.floorU + 1e-9, "und nicht mehr als der Plan vorsieht")
        assertEquals("primeRelease", d.bindingLimit, "es ist der Marker-Kanal, kein anderer")
    }

    /** Dasselbe fuer den GUARD_FLOOR - das zweite der beiden Tore. */
    @Test
    fun `LOW mit Marker hebt auch den Guard-Floor-Block`() {
        val p = planImTief(markerAuthorisesLow = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorisesLow = true,
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
        val p = planImTief(markerAuthorisesLow = true)
        // Eine Basisentscheidung mit GROSSEM Bedarf, aber im Tief blockiert.
        val basis = blockiert(FuseController.Block.SAFETY_HOLD).copy(insulinReqU = 3.0)
        val d = PrimeRelease.lift(basis, p, state(), markerAuthorisesLow = true)
        assertTrue(d.smbU <= p.floorU + 1e-9, "der Bedarf von 3,0 U darf nicht durchschlagen")
        assertTrue(d.smbU < 0.5, "die Menge kommt aus der Huelle, nicht aus insulinReq: ${d.smbU}")
    }

    /** DAS BUDGET WIRD VERBRAUCHT: eine ausgeschoepfte Huelle gibt nichts mehr. */
    @Test
    fun `eine verbrauchte Huelle gibt auch mit Marker nichts frei`() {
        val p = planImTief(markerAuthorisesLow = true, spentU = 1.2, envelopeU = 1.2)
        assertTrue(!p.active, "verbrauchte Huelle: ${p.reason}")
        assertEquals(
            0.0,
            PrimeRelease.lift(
                blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
                markerAuthorisesLow = true,
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
        val p = planImTief(markerAuthorisesLow = true)
        for (b in listOf(
            FuseController.Block.LEDGER_HOLD,
            FuseController.Block.PUMP_BUSY,
            FuseController.Block.HEALTH_NOT_READY,
            FuseController.Block.NO_INPUT,
            FuseController.Block.TAIL,
            FuseController.Block.MAX_IOB_REACHED,
            FuseController.Block.IOB_TH_REACHED,
        )) {
            val d = PrimeRelease.lift(blockiert(b), p, state(), markerAuthorisesLow = true)
            assertEquals(0.0, d.smbU, "$b darf NICHT uebersteuerbar sein")
        }
    }

    /**
     * MARKERABLAUF -> LOW SPERRT WIEDER. Ohne aktiven Marker gibt es keinen
     * Plan, und damit auch keinen Weg an den Bloecken vorbei.
     */
    @Test
    fun `nach Markerablauf sperrt das Tief wieder`() {
        val p = planImTief(markerAuthorisesLow = true, markerActive = false)
        assertTrue(!p.active)
        assertEquals(
            0.0,
            PrimeRelease.lift(
                blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
                markerAuthorisesLow = true,
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
        val p = planImTief(markerAuthorisesLow = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorisesLow = true,
            tailHeadroomU = -5.0,          // der Schwanz sagt: gar nichts
        )
        assertTrue(d.smbU > 0.0, "eine Modellannahme darf den autorisierten Anteil nicht nullen")
        assertEquals(d.smbU, d.markerLowAuthorizedU, 1e-9)
    }

    /** OHNE Autorisierung kappt derselbe Headroom wie bisher. */
    @Test
    fun `ohne Autorisierung kappt der Schwanz-Headroom weiterhin`() {
        val p = planImTief(markerAuthorisesLow = false, markerActive = true)
        // Ein Plan, der ohne die Autorisierung steht: hoher BG, kein Tief.
        val offen = PrimeRelease.plan(
            PrimeRelease.Input(
                enabled = true, mealMarkerActive = true,
                armedTsMs = 1_000_000L, windowStartTsMs = 0L,
                nowMs = 1_000_000L + 3 * 60_000L,
                envelopeU = 1.2, spentU = 0.0,
                safetyMinLowerMgdl = 160.0, guardFloorMgdl = 70.0,
                isfMgdlPerU = 55.0, pumpIncrementU = step,
                markerAuthorisesLow = false,
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

    // ---- OHNE BAHN (predictorfreier Markerpfad) ---------------------------

    /**
     * DIE DOSIERLOGIK DES PREDICTORFREIEN PFADES, in derselben
     * Zusammensetzung, die `markerFallbackCycle` baut: plan(null) + lift ohne
     * Schwanz-Headroom. Wird die Bahn verworfen, ist das hier die GANZE
     * Rechnung - es gibt keine Kandidatensuche, keinen Guard, keinen Schwanz.
     */
    @Test
    fun `ohne Bahn entsteht mit Autorisierung eine Menge`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorisesLow = true))
        assertTrue(p.active, "der Plan muss ohne Bahn stehen koennen: ${p.reason}")
        assertEquals("PRIME", p.reason)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorisesLow = true,
            tailHeadroomU = null,          // ohne Bahn gibt es keinen
        )
        assertTrue(d.smbU > 0.0, "ohne Bahn und mit Autorisierung muss etwas herauskommen")
        assertEquals(d.smbU, d.markerLowAuthorizedU, 1e-9, "und alles davon ist autorisiert")
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
        val p = PrimeRelease.plan(ohneBahn(markerAuthorisesLow = false))
        assertTrue(!p.active)
        assertEquals("NO_TRAJECTORY", p.reason)
    }

    /** Eine KAPUTTE Bahn bleibt NOT_FINITE - die beiden duerfen nicht
     *  zusammenfallen, sonst hat die Nullbarkeit nichts gebracht. */
    @Test
    fun `eine nicht endliche Bahn bleibt NOT_FINITE`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorisesLow = true).copy(safetyMinLowerMgdl = Double.NaN))
        assertTrue(!p.active)
        assertEquals("NOT_FINITE", p.reason)
    }

    private fun ohneBahn(markerAuthorisesLow: Boolean) = PrimeRelease.Input(
        enabled = true, mealMarkerActive = true,
        armedTsMs = 1_000_000L, windowStartTsMs = 0L,
        nowMs = 1_000_000L + 3 * 60_000L,
        envelopeU = 1.2, spentU = 0.0,
        safetyMinLowerMgdl = null,
        guardFloorMgdl = 70.0, isfMgdlPerU = 55.0, pumpIncrementU = step,
        markerAuthorisesLow = markerAuthorisesLow,
    )

    /**
     * DIE HARTEN MENGENDECKEL BLEIBEN. maxIOB und iobTH kappen den Lift auch
     * im autorisierten Fall - sie sind keine Tiefschutz-Tore.
     */
    @Test
    fun `maxIOB kappt auch den autorisierten Anteil`() {
        val p = planImTief(markerAuthorisesLow = true)
        val eng = state(iobTh = 0.5, maxIob = 0.5)   // capIob 0,5 -> Spielraum 0
        val d = PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, eng, markerAuthorisesLow = true)
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
        val p = planImTief(markerAuthorisesLow = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorisesLow = true,
        )
        assertTrue(d.smbU > 0.0)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr, "die Basalabsenkung darf nicht verlorengehen")
    }
}
