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
        r: Double? = null,
    ) = FuseController.State(
        health = health, safetyHold = hold, phase = Phase.REARMING,
        netIobU = netIob, bolusIobU = bolusIob, basalIobU = 0.0,
        iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        smbRatioCorrection = smbRatio, smbRatioRise = smbRatio,
        rSignedMgdlPerMin = r, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = maxSmb, pumpBusy = busy,
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

    /**
     * DER BEFUND DES ERSTEN GERAETELAUFS, als Test festgehalten: ein flacher
     * Verlauf 100-105 hatte r ~ 0,65 und stand damit als RISE_ACTIVE da. Mit
     * einem binaeren Schalter an der Phase haette er den vollen
     * Mahlzeitenanteil bekommen — 0,9 U statt 0,35 U auf einem Drift.
     * Die Rampe muss dort praktisch beim Korrekturanteil bleiben.
     */
    @Test
    fun `ein flacher Drift bekommt fast den Korrekturanteil - egal welche Phase`() {
        fun s(phase: Phase, r: Double) = FuseController.State(
            health = Health.READY, safetyHold = false, phase = phase,
            netIobU = 0.0, bolusIobU = 0.0, basalIobU = 0.0,
            iobThU = 8.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            smbRatioCorrection = 0.20, smbRatioRise = 0.35,
            rSignedMgdlPerMin = r, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = 0.05, maxSmbU = 2.0, pumpBusy = false, mealWindow = true,
        )
        // r = 0,65 -> 10 % der Rampe -> 0,20 + 0,10*0,15 = 0,215
        assertEquals(0.215, s(Phase.RISE_ACTIVE, 0.65).effectiveSmbRatio, 1e-9)
        // Die PHASE aendert daran nichts mehr - das ist der Punkt.
        assertEquals(
            s(Phase.RISE_ACTIVE, 0.65).effectiveSmbRatio,
            s(Phase.REARMING, 0.65).effectiveSmbRatio, 1e-12
        )
    }

    @Test
    fun `ein echter Onset bekommt den vollen Anstiegsanteil`() {
        fun s(r: Double?) = FuseController.State(
            health = Health.READY, safetyHold = false, phase = Phase.RISE_ACTIVE,
            netIobU = 0.0, bolusIobU = 0.0, basalIobU = 0.0,
            iobThU = 8.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            smbRatioCorrection = 0.20, smbRatioRise = 0.35,
            rSignedMgdlPerMin = r, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = 0.05, maxSmbU = 2.0, pumpBusy = false, mealWindow = true,
        )
        assertEquals(0.35, s(3.0).effectiveSmbRatio, 1e-9)   // ueber der Rampe
        assertEquals(0.35, s(2.0).effectiveSmbRatio, 1e-9)   // genau oben
        assertEquals(0.275, s(1.25).effectiveSmbRatio, 1e-9) // Mitte
        assertEquals(0.20, s(0.5).effectiveSmbRatio, 1e-9)   // genau unten
        assertEquals(0.20, s(-1.0).effectiveSmbRatio, 1e-9)  // fallend
        // OHNE Evidenz gilt der Korrekturanteil - nicht der Anstiegswert.
        assertEquals(0.20, s(null).effectiveSmbRatio, 1e-9)
        assertEquals(0.20, s(Double.NaN).effectiveSmbRatio, 1e-9)
    }

    /** Die Phase bleibt als EPISODENZUSTAND erhalten und wird berichtet — sie
     *  steuert nur die Verstaerkung nicht mehr. */
    @Test
    fun `die Phase wird weiterhin als Kontext berichtet`() {
        assertEquals(FuseController.Context.RISE, FuseController.contextOf(Phase.RISE_ACTIVE))
        assertEquals(FuseController.Context.CORRECTION, FuseController.contextOf(Phase.TURN))
    }

    /**
     * Der Kontext muss an JEDEM Rueckgabepfad haengen, nicht nur am
     * Erfolgsfall — beim Blockieren ist "war das Mahlzeit oder Korrektur" die
     * erste Frage. Genau das hat der erste Geraetelauf als "Kontext -" gezeigt.
     */
    @Test
    fun `jeder Entscheidungspfad traegt den Kontext`() {
        val faelle = listOf(
            FuseController.decide(state(health = Health.DEGRADED), pred(250.0)),
            FuseController.decide(state(hold = true), pred(250.0)),
            FuseController.decide(state(busy = true), pred(250.0)),
            FuseController.decide(state(), null),
            FuseController.decide(state(), pred(250.0, minLower = 60.0)),
            FuseController.decide(state(), pred(90.0)),
            FuseController.decide(state(netIob = 8.0, bolusIob = 8.0), pred(250.0)),
            FuseController.decide(state(netIob = 4.5, bolusIob = 4.5), pred(250.0)),
            FuseController.decide(state(maxSmb = 0.04), pred(250.0)),
            FuseController.decide(state(), pred(250.0)),
        )
        for (d in faelle) assertEquals(FuseController.Context.CORRECTION, d.context) { "${d.block} ohne Kontext" }
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

    // ---- Die schnelle Bremsbahn ------------------------------------------

    /**
     * DIE INVARIANTE, die den ganzen Entwurf traegt: die schnelle Bahn darf
     * NIE mehr Insulin ergeben. Ueber Zufallslagen geprueft, nicht an einem
     * Beispiel — wenn diese Eigenschaft faellt, ist der Eingriff nicht mehr
     * einseitig und braucht eine ganz andere Begruendung.
     */
    @Test
    fun `die Bremsbahn kann eine Dosis niemals erhoehen`() {
        val rnd = kotlin.random.Random(20260806)
        repeat(500) {
            val langsam = pred(bgAt30 = rnd.nextDouble(60.0, 400.0), minLower = rnd.nextDouble(40.0, 300.0))
            val schnell = pred(bgAt30 = rnd.nextDouble(60.0, 400.0), minLower = rnd.nextDouble(40.0, 300.0))
            val st = state(netIob = 0.0, bolusIob = 0.0, maxSmb = 5.0, iobTh = 20.0, maxIob = 20.0)
            val ohne = FuseController.decide(st, langsam)
            val mit = FuseController.decide(st, langsam, restraint = schnell)
            assertTrue(mit.smbU <= ohne.smbU + 1e-12) {
                "Bremse hat erhoeht: ${ohne.smbU} -> ${mit.smbU}"
            }
        }
    }

    /**
     * DER FALL VOM 06.08., 14:49: rSigned sagte +5,84, die rohe Steigung
     * -3,73 — FUSE gab 0,15 U. Die Bremsbahn muss das verhindern.
     */
    @Test
    fun `an der Wende bremst die schnelle Bahn`() {
        val langsam = pred(bgAt30 = 300.0, minLower = 200.0)   // r noch hoch
        val schnell = pred(bgAt30 = 120.0, minLower = 55.0)    // faellt bereits
        val st = state(netIob = 4.5, bolusIob = 4.5, iobTh = 20.0, maxIob = 20.0)
        val ohne = FuseController.decide(st, langsam)
        val mit = FuseController.decide(st, langsam, restraint = schnell)
        assertTrue(ohne.smbU > 0.0) { "ohne Bremse haette dosiert werden muessen" }
        assertEquals(0.0, mit.smbU)
        assertEquals(FuseController.Block.GUARD_FLOOR, mit.block)
        assertTrue(mit.restraintBound)
    }

    /**
     * GEGENPROBE ZUM GEGENBEISPIEL: am ONSET ist die langsame Bahn die
     * pessimistischere. Die Bremse darf dort NICHT oeffnen — sie behebt den
     * Onset ausdruecklich nicht, und dieser Test haelt das fest, damit niemand
     * spaeter faelschlich annimmt, sie taete es.
     */
    @Test
    fun `am Onset oeffnet die Bremse nichts - sie behebt ihn nicht`() {
        val langsam = pred(bgAt30 = 60.0, minLower = 60.0)     // r noch negativ
        val schnell = pred(bgAt30 = 200.0, minLower = 150.0)   // steigt bereits
        val st = state()
        val d = FuseController.decide(st, langsam, restraint = schnell)
        assertEquals(FuseController.Block.GUARD_FLOOR, d.block)
        assertEquals(0.0, d.smbU)
        // Die schnelle Bahn war die harmlosere und wurde deshalb verworfen.
        assertEquals(false, d.restraintBound)
    }

    @Test
    fun `ohne Bremsbahn bleibt alles wie vorher`() {
        val p = pred(300.0)
        val st = state()
        assertEquals(FuseController.decide(st, p).smbU, FuseController.decide(st, p, restraint = null).smbU, 0.0)
        assertEquals(false, FuseController.decide(st, p).restraintBound)
    }

    /** Eine schnelle Bahn, die HARMLOSER ist, aendert nichts und wird auch
     *  nicht als bindend gemeldet. */
    @Test
    fun `eine harmlosere Bremsbahn bindet nicht`() {
        val langsam = pred(bgAt30 = 250.0, minLower = 100.0)
        val schnell = pred(bgAt30 = 400.0, minLower = 300.0)
        val st = state()
        val d = FuseController.decide(st, langsam, restraint = schnell)
        assertEquals(FuseController.decide(st, langsam).smbU, d.smbU, 1e-12)
        assertEquals(false, d.restraintBound)
    }

    // ---- C7 / K2 Punkt 8: der Prior darf keinen Schutz vortaeuschen --------

    /**
     * Wie [pred], aber mit einer PRIOR-GEHOBENEN unteren Bahn: `minLower` ist
     * die gehobene Anzeigebahn, `priorFree` die Sicherheitsbahn darunter.
     */
    private fun predWithPrior(bgAt30: Double, minLower: Double, priorFree: Double): PredictorResult {
        val pts = (1..60).map { TrajectoryPoint(it, it * 60_000L, bgAt30, minLower, 0.0, 0.0, 0.0, priorFree) }
        return PredictorResult(
            points = pts, predictionAnchorTs = 0L, bgAtAnchor = bgAt30,
            minMeanBg = bgAt30, minLowerBg = minOf(bgAt30, minLower), timeToMinLowerMin = 30,
            bgAtHorizonMean = bgAt30, bgAtHorizonLower = minLower,
            lineageKind = "ACTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
            modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
            minLowerBgPriorFree = priorFree, bgAtHorizonLowerPriorFree = priorFree,
        )
    }

    /**
     * DER OFFENE PUNKT AUS DEM BAHN-FIX (C7, Codex-Adjudication H2/H3): der
     * Regler bekam weiter die PRIOR-GEHOBENE Bahn. Autorisieren konnte sie
     * keine Dosis mehr - das finale Zeugnis im Runner ist prior-frei -, aber
     * sie konnte einen SCHUETZENDEN ZERO_TEMP UNTERDRUECKEN: prior-frei liegt
     * das Minimum bei 65 (Boden 70, also GUARD_FLOOR -> ZERO_TEMP),
     * prior-gehoben bei 95, und der Fall lief als normale Dosierlage aus.
     * Damit fiel der Basalstopp ersatzlos weg.
     */
    @Test
    fun `ein Marker-Prior darf einen schuetzenden Zero-Temp nicht unterdruecken`() {
        val gehoben = predWithPrior(bgAt30 = 150.0, minLower = 95.0, priorFree = 65.0)
        val d = FuseController.decide(state(), gehoben, FuseController.Limits(guardFloorMgdl = 70.0))
        assertEquals(FuseController.Block.GUARD_FLOOR, d.block)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
        assertEquals(0.0, d.smbU)
        // Berichtet wird die Bahn, gegen die entschieden wurde - nicht die
        // guenstiger aussehende Anzeigebahn.
        assertEquals(65.0, d.minLowerMgdl!!, 1e-9)
        // Gegenprobe: OHNE Prior-Hub war die Lage schon immer ZERO_TEMP.
        assertEquals(
            FuseController.Block.GUARD_FLOOR,
            FuseController.decide(state(), pred(bgAt30 = 150.0, minLower = 65.0), FuseController.Limits(guardFloorMgdl = 70.0)).block
        )
    }

    /** Derselbe Vertrag auf der BREMSBAHN: auch dort zaehlt die prior-freie
     *  Kante, und das Minimum laeuft ueber beide Bahnen. */
    @Test
    fun `auch die Bremsbahn wird prior-frei gegen den Boden geprueft`() {
        val langsam = pred(bgAt30 = 150.0, minLower = 120.0)
        val schnell = predWithPrior(bgAt30 = 150.0, minLower = 100.0, priorFree = 60.0)
        val d = FuseController.decide(state(), langsam, FuseController.Limits(guardFloorMgdl = 70.0), restraint = schnell)
        assertEquals(FuseController.Block.GUARD_FLOOR, d.block)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr)
        assertTrue(d.restraintBound)
    }

    /** EINSEITIGKEIT: ein Prior, der die Sicherheitsbahn nicht unter den Boden
     *  zieht, aendert nichts - der Eingriff kann nur haeufiger schuetzen, nie
     *  seltener. */
    @Test
    fun `ein Prior ueber dem Boden aendert die Entscheidung nicht`() {
        val gehoben = predWithPrior(bgAt30 = 200.0, minLower = 140.0, priorFree = 120.0)
        val ohne = FuseController.decide(state(), pred(bgAt30 = 200.0, minLower = 120.0))
        val mit = FuseController.decide(state(), gehoben)
        assertEquals(ohne.smbU, mit.smbU, 1e-12)
        assertEquals(FuseController.Block.NONE, mit.block)
    }
}
