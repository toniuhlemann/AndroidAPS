package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuseControllerTest {

    private fun pred(bgAt30: Double, minLower: Double = 120.0): PredictorResult {
        val pts = (1..60).map {
            TrajectoryPoint(it, it * 60_000L, if (it == 30) bgAt30 else bgAt30, minLower, 0.0, 0.0, 0.0)
        }
        return PredictorResult(
            points = pts, predictionAnchorTs = 0L, bgAtAnchor = bgAt30,
            // Minima einschliesslich Anker, wie beim echten TrajectoryCore (R85-F5)
            minMeanBg = bgAt30, minLowerBg = minOf(bgAt30, minLower), timeToMinLowerMin = 30,
            bgAtHorizonMean = bgAt30, bgAtHorizonLower = minLower,
            lineageKind = "ACTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
            modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private fun state(
        health: Health = Health.READY,
        hold: Boolean = false,
        netIob: Double = 1.0,
        bolusIob: Double = 1.0,
        iobTh: Double = 4.0,
        maxIob: Double = 8.0,
        busy: Boolean = false,
        smbRatio: Double = 0.5,
        maxSmb: Double = 0.75,
    ) = FuseController.State(
        health = health, safetyHold = hold, phase = Phase.REARMING,
        netIobU = netIob, bolusIobU = bolusIob, basalIobU = 0.0,
        iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        smbRatioCorrection = smbRatio, smbRatioRise = smbRatio, pumpIncrementU = 0.05, maxSmbU = maxSmb, pumpBusy = busy,
    )

    // ---- Mahlzeit oder Korrektur -----------------------------------------

    /** Die Phasenzuordnung ist die einzige Stelle, an der FUSE zwischen
     *  Mahlzeit und Korrektur unterscheidet — bei autoISF brauchte es dafuer
     *  eine gesetzte TT. */
    @Test
    fun `die Phase entscheidet ueber Mahlzeit oder Korrektur`() {
        for (p in listOf(Phase.CANDIDATE, Phase.RISE_ACTIVE, Phase.CARRY))
            assertEquals(FuseController.Context.RISE, FuseController.contextOf(p)) { "$p" }
        // TURN gehoert bewusst zur Korrektur: Peak ueberschritten, das meiste
        // bereits gegebene Insulin ist noch nicht angekommen.
        for (p in listOf(Phase.REARMING, Phase.ARMED, Phase.TURN))
            assertEquals(FuseController.Context.CORRECTION, FuseController.contextOf(p)) { "$p" }
    }

    @Test
    fun `im Anstieg wird mehr freigegeben als in der Korrektur`() {
        fun s(phase: Phase) = FuseController.State(
            health = Health.READY, safetyHold = false, phase = phase,
            netIobU = 0.0, bolusIobU = 0.0, basalIobU = 0.0,
            iobThU = 8.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            smbRatioCorrection = 0.15, smbRatioRise = 0.35,
            pumpIncrementU = 0.05, maxSmbU = 2.0, pumpBusy = false,
        )
        val korr = FuseController.decide(s(Phase.ARMED), pred(300.0))
        val rise = FuseController.decide(s(Phase.RISE_ACTIVE), pred(300.0))
        assertTrue(rise.smbU > korr.smbU) { "Anstieg ${rise.smbU} muss ueber Korrektur ${korr.smbU} liegen" }
        assertEquals(FuseController.Context.RISE, rise.context)
        assertEquals(FuseController.Context.CORRECTION, korr.context)
        // insulinReq = (300-100)/50 = 4.0 -> 0.15 bzw. 0.35 davon
        assertEquals(0.60, korr.smbU, 1e-9)
        assertEquals(1.40, rise.smbU, 1e-9)
    }

    // ---- Zustand vor Zahlen ----------------------------------------------

    @Test
    fun `health nicht READY erzeugt keine Dosis`() {
        val d = FuseController.decide(state(health = Health.DEGRADED), pred(250.0))
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.Block.HEALTH_NOT_READY, d.block)
    }

    @Test
    fun `LOW-Hold sperrt SMB und setzt Zero-Temp`() {
        val d = FuseController.decide(state(hold = true), pred(250.0))
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
    }

    @Test
    fun `Pump-busy erzeugt keine neue Anforderung`() {
        val d = FuseController.decide(state(busy = true), pred(250.0))
        assertEquals(FuseController.Block.PUMP_BUSY, d.block)
        assertEquals(FuseController.TbrAction.KEEP_CURRENT, d.tbr)
    }

    @Test
    fun `fehlende Trajektorie erzeugt keine Dosis`() {
        val d = FuseController.decide(state(), null)
        assertEquals(FuseController.Block.HORIZON_MISSING, d.block)
    }

    // ---- Guard -----------------------------------------------------------

    /** Das MINIMUM der pessimistischen Bahn entscheidet, nicht ihr Endwert. */
    @Test
    fun `Zwischentief unter dem Guard sperrt trotz hohem Bedarf`() {
        val d = FuseController.decide(state(), pred(bgAt30 = 250.0, minLower = 65.0))
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.Block.GUARD_FLOOR, d.block)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
    }

    @Test
    fun `knapp ueber dem Guard wird dosiert`() {
        val d = FuseController.decide(state(), pred(bgAt30 = 250.0, minLower = 70.0))
        assertTrue(d.smbU > 0.0)
    }

    // ---- Bedarf ----------------------------------------------------------

    // VERTRAGSAENDERUNG: bis dahin erwartete dieser Test ZERO_TEMP. Das
    // widersprach TbrPolicys eigener Tabelle ("kein Bedarf" -> NO_POSITIVE) und
    // haette bei jedem Zyklus mit predBG <= Ziel das Profilbasal 30 Minuten
    // gestoppt. Der Sicherheitsfall laeuft ueber den Guard, nicht hierueber.
    @Test
    fun `kein Bedarf fordert nichts Positives mehr an - aber kein Zero-Temp`() {
        val d = FuseController.decide(state(), pred(bgAt30 = 90.0))
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.Block.NO_DEMAND, d.block)
        assertEquals(FuseController.TbrAction.NO_NEW_POSITIVE, d.tbr)
    }

    /** Gegenprobe zur Aenderung darueber: die gefaehrliche Lage muss weiterhin
     *  eine echte Null ergeben — sonst haette der Fix den Schutz mit entfernt. */
    @Test
    fun `unsichere Bahn ergibt weiterhin Zero-Temp`() {
        val d = FuseController.decide(state(), pred(bgAt30 = 90.0, minLower = 60.0))
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.Block.GUARD_FLOOR, d.block)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
    }

    /** Kein zweiter IOB-Abzug: insulinReq folgt allein aus predBG und Ziel,
     *  weil die IOB-Wirkung bereits in der Bahn steckt. */
    @Test
    fun `insulinReq zieht IOB nicht ein zweites Mal ab`() {
        val a = FuseController.decide(state(netIob = 0.5, bolusIob = 0.5), pred(200.0))
        val b = FuseController.decide(state(netIob = 3.0, bolusIob = 3.0), pred(200.0))
        assertEquals(a.insulinReqU, b.insulinReqU, 1e-12)   // (200-100)/50 = 2.0
        assertEquals(2.0, a.insulinReqU, 1e-12)
    }

    // ---- Kanalgrenze und Deckel ------------------------------------------

    @Test
    fun `oberhalb iobTH gibt es keinen SMB, aber es bleibt unter maxIOB`() {
        val d = FuseController.decide(state(netIob = 4.5, bolusIob = 4.5, iobTh = 4.0, maxIob = 8.0), pred(250.0))
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.Block.IOB_TH_REACHED, d.block)
    }

    @Test
    fun `maxIOB ist absolut und wird vor iobTH geprueft`() {
        val d = FuseController.decide(state(netIob = 8.0, bolusIob = 8.0, iobTh = 20.0, maxIob = 8.0), pred(250.0))
        assertEquals(FuseController.Block.MAX_IOB_REACHED, d.block)
    }

    /** capIob = max(net, bolus): zurueckgehaltenes Basal darf kein zusaetzliches
     *  schnelles Budget erzeugen. */
    @Test
    fun `negatives Basal-IOB vergroessert das SMB-Budget nicht`() {
        val s = state(netIob = 0.5, bolusIob = 3.9, iobTh = 4.0)
        val d = FuseController.decide(s, pred(300.0))
        assertEquals(3.9, s.capIobU, 1e-12)
        assertTrue(d.smbU <= 0.1 + 1e-9) { "Budget aus capIob, nicht aus net" }
    }

    @Test
    fun `die greifende Grenze wird benannt`() {
        val d = FuseController.decide(state(maxSmb = 0.1), pred(400.0))
        assertEquals("maxSmb", d.bindingLimit)
        assertEquals(0.1, d.smbU, 1e-9)
    }

    // ---- Rundung ---------------------------------------------------------

    @Test
    fun `Rundung erfolgt ausschliesslich abwaerts`() {
        // insulinReq = (240-100)/50 = 2.8; * 0.5 = 1.4 -> maxSmb 0.75 bindet
        val d = FuseController.decide(state(maxSmb = 0.74), pred(240.0))
        assertEquals(0.70, d.smbU, 1e-9)   // floor(0.74/0.05)*0.05
    }

    /**
     * WAECHTER gegen einen Fehler, der lange unbemerkt blieb: ohne Epsilon
     * verliert `floor` an exakten Vielfachen einen ganzen Pumpenschritt, weil
     * 0,15 als Double knapp UNTER 0,15 liegt. Bei diesen Dosen sind das 17 bis
     * 100 % der Menge — systematisch zu wenig.
     */
    @Test
    fun `exakte Vielfache des Pumpenschritts verlieren keinen Schritt`() {
        // insulinReq = (250-100)/50 = 3.0; x 0.05 = 0.15 -> exakt 3 Schritte
        val d = FuseController.decide(state(smbRatio = 0.05, maxSmb = 2.0, netIob = 0.0, bolusIob = 0.0), pred(250.0))
        assertEquals(0.15, d.smbU, 1e-9)
        // (500-100)/50 = 8.0; x 0.15 = 1.20 -> exakt 24 Schritte
        val e = FuseController.decide(state(smbRatio = 0.15, maxSmb = 5.0, netIob = 0.0, bolusIob = 0.0, iobTh = 20.0, maxIob = 20.0), pred(500.0))
        assertEquals(1.20, e.smbU, 1e-9)
    }

    @Test
    fun `unter dem Pumpeninkrement wird nicht aufgerundet`() {
        val d = FuseController.decide(state(maxSmb = 0.04), pred(250.0))
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.Block.BELOW_PUMP_INCREMENT, d.block)
    }

    // ---- TBR konkret -----------------------------------------------------

    @Test
    fun `ZERO_TEMP wird zu Rate 0 ueber die volle Dauer`() {
        val r = FuseController.tbrRequest(FuseController.TbrAction.ZERO_TEMP, 0.8, 3.0, 30)!!
        assertEquals(0.0, r.rateUPerH)
        assertEquals(30, r.durationMin)
    }

    @Test
    fun `CANCEL_TO_SCHEDULED setzt das Profilbasal absolut und respektiert maxBasal`() {
        assertEquals(0.8, FuseController.tbrRequest(FuseController.TbrAction.CANCEL_TO_SCHEDULED, 0.8, 3.0)!!.rateUPerH)
        assertEquals(3.0, FuseController.tbrRequest(FuseController.TbrAction.CANCEL_TO_SCHEDULED, 9.0, 3.0)!!.rateUPerH)
    }

    /** null heisst "nichts anfordern" — ausdruecklich NICHT Rate 0. Eine
     *  laufende Absenkung darf weiterlaufen, sie wirkt in die sichere Richtung. */
    @Test
    fun `KEEP_CURRENT und NO_NEW_POSITIVE fordern nichts an`() {
        assertEquals(null, FuseController.tbrRequest(FuseController.TbrAction.KEEP_CURRENT, 0.8, 3.0))
        assertEquals(null, FuseController.tbrRequest(FuseController.TbrAction.NO_NEW_POSITIVE, 0.8, 3.0))
    }

    // VERTRAGSAENDERUNG (zweite Haelfte): frueher hiess dieser Test "Guard UND
    // fehlender Bedarf fuehren beide zu einer echten Zero-Temp" und pruefte
    // beide Faelle gemeinsam. Genau das war der Fehler — er zementierte die
    // Vermengung, die TbrPolicy ausdruecklich trennt. Jetzt wird der
    // UNTERSCHIED geprueft, nicht die Gleichheit.
    @Test
    fun `nur die unsichere Bahn ergibt eine echte Null - fehlender Bedarf nicht`() {
        val guard = FuseController.decide(state(), pred(250.0, minLower = 60.0))
        assertEquals(0.0, FuseController.tbrRequest(guard.tbr, 0.8, 3.0)!!.rateUPerH)

        val noDemand = FuseController.decide(state(), pred(90.0))
        // null heisst: nichts anfordern. Eine laufende Absenkung laeuft weiter,
        // das Profilbasal wird NICHT gestoppt.
        assertEquals(null, FuseController.tbrRequest(noDemand.tbr, 0.8, 3.0))
    }
}
