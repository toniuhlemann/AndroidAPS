package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.FuseOverviewSource.AccountStatus
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipTile
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.FuseOverviewSource.RatioMode
import app.aaps.core.interfaces.overview.FuseOverviewSource.SmbSource
import app.aaps.fuse.core.controller.ExpectationAccount
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER FUSE-CHIP AUF DEM HAUPTBILDSCHIRM (Toni 15.11.2026): die Werte eines Zyklus fuer die neun Kacheln und die
 * gespeicherte Auswahl. Alle Werte sind ausgedacht.
 */
class FuseChipStandTest {

    private val jetzt = 1_800_000_300_000L

    private fun state(
        r: Double? = 1.75,
        mahlzeitFenster: Boolean = true,
        rebound: Boolean = false,
    ) = FuseController.State(
        health = Health.READY,
        safetyHold = false,
        phase = Phase.ARMED,
        netIobU = 0.4,
        bolusIobU = 0.6,
        basalIobU = -0.2,
        iobThU = 2.0,
        maxIobU = 4.0,
        targetMgdl = 100.0,
        isfMgdlPerU = 50.0,
        smbRatioCorrection = 0.2,
        smbRatioRise = 0.4,
        rSignedMgdlPerMin = r,
        riseRampLowRPerMin = 1.0,
        riseRampHighRPerMin = 2.5,
        pumpIncrementU = 0.05,
        maxSmbU = 0.3,
        pumpBusy = false,
        reboundWindow = rebound,
        mealWindow = mahlzeitFenster,
    )

    private fun outcome(
        bindung: String = "smbRatio",
        block: FuseController.Block = FuseController.Block.NONE,
        state: FuseController.State? = state(),
    ) = FuseCycleRunner.Outcome(
        tbrChanged = false,
        decision = FuseController.Decision(
            smbU = 0.2,
            tbr = FuseController.TbrAction.KEEP_CURRENT,
            block = block,
            insulinReqU = 0.8,
            predAtReleaseMgdl = 130.0,
            minLowerMgdl = 90.0,
            bindingLimit = bindung,
        ),
        tbr = null,
        prediction = null,
        sourceTs = jetzt - 60_000L,
        computeTs = jetzt - 30_000L,
        health = Health.READY,
        gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "VirtualPumpPlugin"),
        reason = "KEEP",
        alarm = false,
        bgMgdl = 140.0,
        targetMgdl = 100.0,
        targetSource = "profile",
        signal = null,
        band = null,
        discount = null,
        onset = null,
        prime = null,
        candidate = null,
        candidateGap = null,
        computeDurationMs = 5L,
        mealStats = null,
        policy = null,
        state = state,
        step = null,
        sensorEpoch = null,
        calibrationEpoch = null,
        isfMgdlPerU = 50.0,
        iobU = 0.4,
        abortReason = null,
        evidenceEpisodeCapMin = 360,
        smbState = "FREE",
    )

    private fun punkt(
        zustand: CycleState? = CycleState.FREE,
        smbU: Double? = null,
        quelle: SmbSource? = null,
        kontoStatus: AccountStatus? = null,
        erwartet: Double? = null,
        kanal: FuseOverviewSource.ChannelState? = null,
        /** Der Bedarf des Reglers; `null` = in diesem Zyklus nicht gerechnet (Stopp am Guard oder am Schwanz). */
        bedarfU: Double? = 0.8,
    ) = FuseOverviewSource.Point(
        timestamp = jetzt - 60_000L,
        driveMgdlPerMin = 1.2,
        fastDriveMgdlPerMin = 1.0,
        guardMarginMgdl = 18.0,
        tailMarginMgdl = 25.0,
        expectedMealDriveMgdlPerMin = erwartet,
        guardWithMealMarginMgdl = 42.0,
        tailWithMealMarginMgdl = 30.0,
        accountStatus = kontoStatus,
        cycleState = zustand,
        smbU = smbU,
        smbSource = quelle,
        channel = kanal,
        bindingLimit = "smbRatio",
        insulinReqU = bedarfU,
    )

    private fun konto(budgetU: Double = 4.0, gebuchtU: Double = 0.8, skalierer: Double = 1.4) = ExpectationAccount.State(
        authId = jetzt - 600_000L,
        windowEndTs = jetzt + 85 * 60_000L,
        startStable = true,
        phi0 = skalierer,
        a0U = budgetU,
        bookedU = gebuchtU,
        released = true,
        frozen = false,
        releaseChecked = true,
        disturbanceU = 0.5,
        disturbanceValid = true,
        lastObsTs = jetzt - 60_000L,
        lastBgMgdl = 140.0,
        isfAtMarkerMgdlPerU = 50.0,
        horizonMin = 120,
        releaseThresholdU = 0.3,
        curveValues = emptyList(),
    )

    /** Die Auswertung des Kontos: lieferbar ist min(Anforderung, gemessener Anteil + Rest dieses Zyklus). */
    private fun auswertung(anforderungU: Double = 0.7, gemessenU: Double = 0.4, restZyklusU: Double = 0.3) = ExpectationAccount.Evaluation(
        rMessU = gemessenU, phiZU = 0.3, rRohU = anforderungU, requestU = anforderungU, restEffU = restZyklusU,
        expired = false, paused = false, released = true, frozen = false, status = "ACTIVE",
    )

    private fun kontoExport(
        gilt: Boolean = true,
        rueckfall: String? = null,
        zustand: ExpectationAccount.State? = konto(),
        eval: ExpectationAccount.Evaluation = auswertung(),
    ) = FuseCycleRunner.ErwartungskontoExport(
        gilt = gilt,
        kontextAbweichung = false,
        verworfenBeimLaden = false,
        zyklus = zustand?.let {
            FuseCycleRunner.ErwartungskontoZyklus(state = it, eval = eval, armiertInDiesemZyklus = false, stabil = true, pausiert = false)
        },
        buchung = null,
        bodenU = 0.0,
        angehobenU = 0.0,
        blockNormalpfad = null,
        grundNormalpfad = null,
        grundEnde = null,
        stabilSeitTs = null,
        rueckfall = rueckfall,
    )

    private fun bilanz(g: Double? = 0.7) = FuseCycleRunner.MealBalanceExport(
        markerTs = jetzt - 600_000L,
        minutesSinceMarker = 10.0,
        balanceU = g,
        smbCapU = g,
        smbBeforeCapU = null,
        announcedActive = true,
        immediateRestU = 1.0,
        eCarbsG = null,
        eCarbsRestU = null,
        confirmation = null,
        weight = null,
        inheritedFrom = emptyList(),
        inheritedECarbsG = null,
        inheritedRestU = null,
        inheritedCountedU = null,
        episodeIobU = 1.5,
        observedU = 0.5,
        withdrawalWantedUPerH = null,
        withdrawalRateUPerH = null,
        withdrawalLimitU = 0.0,
        overcoverageU = 0.0,
        withdrawalReason = "NOT_OVERCOVERED",
        withdrawalBlock = null,
        phase = "NONE",
        acted = false,
        command = null,
    )

    private fun mit(o: FuseCycleRunner.Outcome, konto: FuseCycleRunner.ErwartungskontoExport? = null, bilanz: FuseCycleRunner.MealBalanceExport? = null) =
        o.copy(bloecke = FuseCycleRunner.Bloecke(erwartungskonto = konto, mealBalance = bilanz))

    // ---- Rampe -------------------------------------------------------------------------------------------------

    @Test
    fun `die Rampe steht in Prozent zwischen ihren Kanten und nie ausserhalb`() {
        assertEquals(50, FuseChipStand.rampenProzent(1.75, 1.0, 2.5))
        assertEquals(0, FuseChipStand.rampenProzent(0.2, 1.0, 2.5))
        assertEquals(0, FuseChipStand.rampenProzent(-1.0, 1.0, 2.5))
        assertEquals(100, FuseChipStand.rampenProzent(4.0, 1.0, 2.5))
        // Ein glatter Anteil wird gerundet, nicht abgeschnitten: 8 Prozent sind binaer oft knapp darunter.
        assertEquals(8, FuseChipStand.rampenProzent(1.0 + 0.08 * 1.5, 1.0, 2.5))
    }

    @Test
    fun `ohne r oder ohne gueltige Rampe gibt es keine Prozentzahl`() {
        assertNull(FuseChipStand.rampenProzent(null, 1.0, 2.5))
        assertNull(FuseChipStand.rampenProzent(Double.NaN, 1.0, 2.5))
        assertNull(FuseChipStand.rampenProzent(1.5, 2.5, 2.5))
        assertNull(FuseChipStand.rampenProzent(1.5, 2.5, 1.0))
        assertNull(FuseChipStand.rampenProzent(1.5, Double.NaN, 2.5))
    }

    // ---- Auswahl -----------------------------------------------------------------------------------------------

    @Test
    fun `die Vorgabe des Schluessels ist die Vorgabe der Schnittstelle`() {
        assertEquals(FuseOverviewSource.ChipLayout.DEFAULT_PINS, FuseChipStand.pinsAusText(FuseStringNonKey.ChipPins.defaultValue))
        assertEquals(FuseOverviewSource.ChipLayout().expanded, FuseBooleanNonKey.ChipExpanded.defaultValue)
        assertEquals(FuseStringNonKey.ChipPins.defaultValue, FuseChipStand.pinsAlsText(FuseOverviewSource.ChipLayout.DEFAULT_PINS))
    }

    @Test
    fun `die Auswahl haelt die Reihenfolge und wirft Unbekanntes, Doppeltes und Ueberzaehliges weg`() {
        assertEquals(listOf(ChipTile.MEAL, ChipTile.DRIVE), FuseChipStand.pinsAusText("MEAL, DRIVE"))
        assertEquals(listOf(ChipTile.GUARD), FuseChipStand.pinsAusText("GUARD,EINE_ANDERE,GUARD"))
        assertEquals(
            listOf(ChipTile.DRIVE, ChipTile.DEMAND, ChipTile.RATIO, ChipTile.SMB),
            FuseChipStand.pinsAusText("DRIVE,DEMAND,RATIO,SMB,LIMIT,CHANNEL"),
        )
        assertEquals(FuseOverviewSource.ChipLayout.MAX_PINS, FuseChipStand.pinsAusText(ChipTile.entries.joinToString(",") { it.name }).size)
    }

    @Test
    fun `eine leere Auswahl bleibt leer und wird nicht zur Vorgabe`() {
        assertEquals(emptyList<ChipTile>(), FuseChipStand.pinsAusText(""))
        assertEquals("", FuseChipStand.pinsAlsText(emptyList()))
    }

    @Test
    fun `gespeichert wird, was auch gelesen wird`() {
        for (auswahl in listOf(listOf(ChipTile.MEAL), listOf(ChipTile.TAIL, ChipTile.LIMIT, ChipTile.CHANNEL, ChipTile.SMB), ChipTile.entries)) {
            val text = FuseChipStand.pinsAlsText(auswahl)
            assertEquals(auswahl.take(FuseOverviewSource.ChipLayout.MAX_PINS), FuseChipStand.pinsAusText(text), text)
        }
    }

    // ---- Grenze ------------------------------------------------------------------------------------------------

    @Test
    fun `frei ist nur, wenn der Anteil oder das Konto die Menge formt`() {
        assertNull(FuseChipStand.grenze(outcome("smbRatio")))
        assertNull(FuseChipStand.grenze(outcome("liveness:smbRatio")))
        assertNull(FuseChipStand.grenze(outcome("expectationDemand")))
        assertNull(FuseChipStand.grenze(outcome("expectationAccount")))
        // Ein Vermerk hinter dem Namen haelt nichts zurueck.
        assertNull(FuseChipStand.grenze(outcome("smbRatio|subStep")))
        assertNull(FuseChipStand.grenze(outcome("expectationAccount|initialPastDescentRisk")))
        // Ohne Bedarf ist nichts da, das zurueckgehalten wuerde.
        assertNull(FuseChipStand.grenze(outcome("insulinReq<=0", FuseController.Block.NO_DEMAND).copy(smbState = "NO_DEMAND")))
    }

    @Test
    fun `eine Mengengrenze, der Schwanz und ein Totband stehen mit Namen da, auch wenn der SMB frei ist`() {
        assertEquals("maxSmb", FuseChipStand.grenze(outcome("maxSmb")))
        assertEquals("iobThHeadroom", FuseChipStand.grenze(outcome("iobThHeadroom")))
        assertEquals("tailHeadroom=0.12", FuseChipStand.grenze(outcome("tailHeadroom=0.12")))
        assertEquals("demandRatioCap", FuseChipStand.grenze(outcome("demandRatioCap")))
        assertEquals("nightDeadband", FuseChipStand.grenze(outcome("nightDeadband", FuseController.Block.NO_DEMAND).copy(smbState = "NO_DEMAND")))
        assertEquals(
            FuseController.CORRECTION_DEADBAND_LIMIT,
            FuseChipStand.grenze(outcome(FuseController.CORRECTION_DEADBAND_LIMIT, FuseController.Block.NO_DEMAND).copy(smbState = "NO_DEMAND")),
        )
    }

    @Test
    fun `ein Stopp nennt seine Grenze - das Urteil des Low-Tors dahinter ist nur ein Vermerk`() {
        val guard = outcome("guardFloor=70.0|IMMINENT", FuseController.Block.GUARD_FLOOR).copy(smbState = "STOP", smbStopReason = "GUARD")
        assertEquals("guardFloor=70.0", FuseChipStand.grenze(guard))
        val kandidat = outcome("candidate:GUARD_FLOOR", FuseController.Block.CANDIDATE).copy(smbState = "STOP", smbStopReason = "GUARD")
        assertEquals("candidate:GUARD_FLOOR", FuseChipStand.grenze(kandidat))
    }

    /** Ein bedarfsloser Zyklus an einer vollen Mengengrenze: der Status sagt STOP, die Grenze nur "kein Bedarf". */
    @Test
    fun `ein Stopp ohne eigenen Namen nimmt den typisierten Grund`() {
        val voll = outcome("insulinReq<=0", FuseController.Block.NO_DEMAND).copy(smbState = "STOP", smbStopReason = "MAX_IOB")
        assertEquals("maxIobHeadroom", FuseChipStand.grenze(voll))
        assertEquals("correctionExposureLimit", FuseChipStand.grenze(voll.copy(smbStopReason = "EXPOSURE")))
        assertEquals("iobThHeadroom", FuseChipStand.grenze(voll.copy(smbStopReason = "IOB_TH")))
        assertEquals("STOP", FuseChipStand.grenze(voll.copy(smbStopReason = null)))
    }

    @Test
    fun `ein spaeteres Tor zaehlt vor der Grundentscheidung, das letzte zuerst`() {
        assertEquals("finalShortened:GUARD_FLOOR", FuseChipStand.grenze(outcome("smbRatio|finalShortened:GUARD_FLOOR")))
        assertEquals("REVERSAL_CONFIRMED", FuseChipStand.grenze(outcome("smbRatio|REVERSAL_CONFIRMED")))
        assertEquals("expectationDescentRisk", FuseChipStand.grenze(outcome("expectationAccount|expectationDescentRisk")))
        assertEquals("deferredPrime", FuseChipStand.grenze(outcome("maxSmb|subStep|deferredPrime")))
    }

    @Test
    fun `der letzte Riegel gewinnt, wenn gewollt war und nichts hinausging`() {
        assertEquals("PUMP_GATE", FuseChipStand.grenze(outcome("smbRatio").copy(smbFinalBlock = "PUMP_GATE")))
        assertEquals("SAFETY_ZERO", FuseChipStand.grenze(outcome("maxSmb").copy(smbFinalBlock = "SAFETY_ZERO")))
        // "NONE" und leer sind kein Riegel.
        assertNull(FuseChipStand.grenze(outcome("smbRatio").copy(smbFinalBlock = "NONE")))
        assertNull(FuseChipStand.grenze(outcome("smbRatio").copy(smbFinalBlock = " ")))
    }

    /** Das Netz: "frei" hiesse, nichts haelt zurueck - ging weniger hinaus als gewollt, darf es nicht dastehen. */
    @Test
    fun `frei steht nie da, wenn weniger hinausging als gewollt`() {
        val gekuerzt = outcome("smbRatio|einNeuerZusatz").copy(smbRequestedU = 0.5)
        assertEquals("smbRatio|einNeuerZusatz", FuseChipStand.grenze(gekuerzt, gegebenU = 0.2))
        assertEquals("smbRatio|einNeuerZusatz", FuseChipStand.grenze(gekuerzt, gegebenU = null))
        // Dieselbe Menge hinaus wie gewollt: frei. Eine Rundung im letzten Pumpenschritt zaehlt nicht als Kuerzung.
        assertNull(FuseChipStand.grenze(gekuerzt, gegebenU = 0.5))
        assertNull(FuseChipStand.grenze(gekuerzt, gegebenU = 0.5 - FuseChipStand.GEKUERZT_EPS_U / 2))
    }

    @Test
    fun `ein nicht auswertbarer Zyklus nennt seine Grenze, sonst den Block`() {
        val abbruch = outcome("drive not estimable", FuseController.Block.NO_INPUT).copy(smbState = "UNKNOWN")
        assertEquals("drive not estimable", FuseChipStand.grenze(abbruch))
        assertEquals("NO_INPUT", FuseChipStand.grenze(outcome("", FuseController.Block.NO_INPUT).copy(smbState = null)))
    }

    // ---- Der Stand eines Zyklus --------------------------------------------------------------------------------

    @Test
    fun `der Stand liest den Zyklus ab und rechnet nichts neu`() {
        val chip = FuseChipStand.aus(outcome().copy(exposureGateHeadroomU = 2.3), punkt(erwartet = 0.9), letzterSmb = null)

        // Die Zeit ist die der Rechnung, nicht die des Messwerts: daran wird der Chip grau.
        assertEquals(jetzt - 30_000L, chip.timestamp)
        assertEquals(CycleState.FREE, chip.cycleState)
        assertEquals(1.2, chip.driveMgdlPerMin)
        assertEquals(0.9, chip.expectedMealDriveMgdlPerMin)
        assertEquals(0.8, chip.insulinReqU)
        assertEquals(state().effectiveSmbRatio, chip.smbRatio!!, 1e-12)
        assertEquals(RatioMode.RAMP, chip.ratioMode)
        assertEquals(50, chip.rampPercent)
        assertNull(chip.limit)
        assertEquals(2.3, chip.roomU)
        assertEquals(18.0, chip.guardMarginMgdl)
        assertEquals(42.0, chip.guardWithMealMarginMgdl)
        assertEquals(25.0, chip.tailMarginMgdl)
        assertEquals(30.0, chip.tailWithMealMarginMgdl)
        assertNull(chip.channel)
        assertNull(chip.meal)
    }

    // ---- Bedarf ------------------------------------------------------------------------------------------------

    /**
     * Stoppt der Normalweg am Guard, rechnet der Regler keinen Bedarf - die Portion kommt ueber die Marker-Freigabe aus
     * dem Konto. Dann zeigt die Kachel den Bedarf des Kontos (Toni 15.11.2026), und sagt, woher er kommt.
     */
    @Test
    fun `ohne Bedarf des Reglers zeigt die Kachel den des Kontos`() {
        val chip = FuseChipStand.aus(mit(outcome(), kontoExport()), punkt(kontoStatus = AccountStatus.AKTIV, bedarfU = null), null)

        // lieferbar = min(Anforderung, gemessener Anteil + Rest dieses Zyklus)
        assertEquals(0.7, chip.insulinReqU!!, 1e-12)
        assertTrue(chip.insulinReqFromAccount)
    }

    @Test
    fun `der Bedarf des Reglers geht vor dem des Kontos`() {
        val chip = FuseChipStand.aus(mit(outcome(), kontoExport()), punkt(kontoStatus = AccountStatus.AKTIV, bedarfU = 0.8), null)

        assertEquals(0.8, chip.insulinReqU)
        assertFalse(chip.insulinReqFromAccount)
        // Auch ein gerechneter Bedarf von null ist ein Bedarf des Reglers.
        val nullBedarf = FuseChipStand.aus(mit(outcome(), kontoExport()), punkt(kontoStatus = AccountStatus.AKTIV, bedarfU = 0.0), null)
        assertEquals(0.0, nullBedarf.insulinReqU)
        assertFalse(nullBedarf.insulinReqFromAccount)
    }

    /** Ohne geltendes Konto gibt es keinen Kontobedarf - weder im Rueckfall noch ausserhalb einer Mahlzeit. */
    @Test
    fun `ohne Bedarf des Reglers und ohne geltendes Konto bleibt die Kachel leer`() {
        val rueckfall = mit(outcome(), kontoExport(gilt = false, rueckfall = "ACCOUNT_LOST"))
        val imRueckfall = FuseChipStand.aus(rueckfall, punkt(kontoStatus = AccountStatus.OHNE_KONTO, bedarfU = null), null)
        assertNull(imRueckfall.insulinReqU)
        assertFalse(imRueckfall.insulinReqFromAccount)

        val ohneMahlzeit = FuseChipStand.aus(outcome(), punkt(bedarfU = null), null)
        assertNull(ohneMahlzeit.insulinReqU)
        assertFalse(ohneMahlzeit.insulinReqFromAccount)
    }

    /** Der Kontobedarf ist die lieferbare Menge des Kontos - dieselbe, die der Regler als Kontobedarf bekommt. */
    @Test
    fun `der Kontobedarf ist die lieferbare Menge, nicht berechenbar zaehlt als null`() {
        fun bedarf(eval: ExpectationAccount.Evaluation) = FuseChipStand.kontoBedarfU(mit(outcome(), kontoExport(eval = eval)))

        // Die Anforderung begrenzt.
        assertEquals(0.5, bedarf(auswertung(anforderungU = 0.5, gemessenU = 0.4, restZyklusU = 0.3))!!, 1e-12)
        // Gemessener Anteil plus Rest dieses Zyklus begrenzen.
        assertEquals(0.6, bedarf(auswertung(anforderungU = 2.0, gemessenU = 0.4, restZyklusU = 0.2))!!, 1e-12)
        // Ein negativer gemessener Anteil zaehlt nicht mit.
        assertEquals(0.2, bedarf(auswertung(anforderungU = 2.0, gemessenU = -1.0, restZyklusU = 0.2))!!, 1e-12)
        // Nicht berechenbar zaehlt als 0, wie im Regler.
        assertEquals(0.0, bedarf(auswertung(anforderungU = Double.NaN))!!, 0.0)
        // Ohne geltendes Konto gibt es keinen.
        assertNull(FuseChipStand.kontoBedarfU(outcome()))
        assertNull(FuseChipStand.kontoBedarfU(mit(outcome(), kontoExport(gilt = false, rueckfall = "NO_CURVE"))))
    }

    /** Wie im Regler: bei einer Rechner-Mahlzeit verlangt kein Weg mehr, als die gemeinsame Bilanz traegt. */
    @Test
    fun `die Grenze der Bilanz deckelt den Kontobedarf`() {
        val o = mit(outcome(), kontoExport(), bilanz(g = 0.5))
        assertEquals(0.5, FuseChipStand.kontoBedarfU(o)!!, 1e-12)
        // Eine weitere Grenze aendert nichts, eine nicht endliche zaehlt nicht.
        assertEquals(0.7, FuseChipStand.kontoBedarfU(mit(outcome(), kontoExport(), bilanz(g = 3.0)))!!, 1e-12)
        assertEquals(0.7, FuseChipStand.kontoBedarfU(mit(outcome(), kontoExport(), bilanz(g = null)))!!, 1e-12)
    }

    @Test
    fun `der Anteil nennt seine Regel - Rampe, Korrektur oder Rebound`() {
        assertEquals(RatioMode.RAMP, FuseChipStand.aus(outcome(state = state()), punkt(), null).ratioMode)
        val korrektur = FuseChipStand.aus(outcome(state = state(mahlzeitFenster = false)), punkt(), null)
        assertEquals(RatioMode.CORRECTION, korrektur.ratioMode)
        assertEquals(0.2, korrektur.smbRatio!!, 1e-12)
        // Nach einem Tief gilt der Deckel, auch im Mahlzeitfenster.
        val rebound = FuseChipStand.aus(outcome(state = state(rebound = true)), punkt(), null)
        assertEquals(RatioMode.REBOUND, rebound.ratioMode)
        assertEquals(0.2, rebound.smbRatio!!, 1e-12)
    }

    @Test
    fun `ein Abbruchzyklus hat weder Anteil noch Rampe und heisst unbekannt`() {
        val chip = FuseChipStand.aus(outcome(state = null), punkt(zustand = null), null)

        assertEquals(CycleState.UNKNOWN, chip.cycleState)
        assertNull(chip.smbRatio)
        assertNull(chip.ratioMode)
        assertNull(chip.rampPercent)
    }

    @Test
    fun `das Konto ohne Erwartung zeigt keinen erwarteten Wert`() {
        assertNull(FuseChipStand.aus(outcome(), punkt(erwartet = 0.0), null).expectedMealDriveMgdlPerMin)
        assertNull(FuseChipStand.aus(outcome(), punkt(erwartet = null), null).expectedMealDriveMgdlPerMin)
    }

    @Test
    fun `der SMB des Zyklus ist auch der juengste, sonst zaehlt der aus dem Ring`() {
        val frueher = FuseOverviewSource.ChipSmb(jetzt - 12 * 60_000L, 0.3, SmbSource.NORMAL)
        val mit = FuseChipStand.aus(outcome(), punkt(smbU = 0.2, quelle = SmbSource.EXPECTATION), frueher)
        assertEquals(FuseOverviewSource.ChipSmb(jetzt - 60_000L, 0.2, SmbSource.EXPECTATION), mit.smb)
        assertEquals(mit.smb, mit.lastSmb)

        val ohne = FuseChipStand.aus(outcome(), punkt(), frueher)
        assertNull(ohne.smb)
        assertEquals(frueher, ohne.lastSmb)
        assertNull(FuseChipStand.aus(outcome(), punkt(), null).lastSmb)
    }

    @Test
    fun `gewollt steht nur da, wenn weniger hinausging`() {
        val o = outcome().copy(smbRequestedU = 0.45)
        assertEquals(0.45, FuseChipStand.aus(o, punkt(smbU = 0.2, quelle = SmbSource.NORMAL), null).smbWantedU)
        assertEquals(0.45, FuseChipStand.aus(o, punkt(), null).smbWantedU)
        assertNull(FuseChipStand.aus(o, punkt(smbU = 0.45, quelle = SmbSource.NORMAL), null).smbWantedU)
        assertNull(FuseChipStand.aus(outcome().copy(smbRequestedU = 0.0), punkt(), null).smbWantedU)
        assertNull(FuseChipStand.aus(outcome().copy(smbRequestedU = null), punkt(), null).smbWantedU)
    }

    @Test
    fun `ueber der Grenze ist der Platz null und nicht negativ, ohne Angabe fehlt er`() {
        assertEquals(0.0, FuseChipStand.aus(outcome().copy(exposureGateHeadroomU = -0.4), punkt(), null).roomU)
        assertNull(FuseChipStand.aus(outcome(), punkt(), null).roomU)
    }

    @Test
    fun `der Kanal kommt aus dem Ringpunkt`() {
        val kanal = FuseOverviewSource.ChannelState(phase = "RUNNING", rateUPerH = 1.6, extraUPerH = 0.8, deliveredU = 0.2)
        val chip = FuseChipStand.aus(outcome(), punkt(zustand = CycleState.CHANNEL, kanal = kanal), null)

        assertEquals(CycleState.CHANNEL, chip.cycleState)
        assertEquals(kanal, chip.channel)
        assertEquals(2.0, chip.channel!!.factor!!, 1e-9)
    }

    // ---- Mahlzeit ----------------------------------------------------------------------------------------------

    @Test
    fun `das Konto zeigt Rest, Fensterende und den gebuchten Skalierer`() {
        val mahlzeit = FuseChipStand.aus(mit(outcome(), kontoExport()), punkt(kontoStatus = AccountStatus.AKTIV), null).meal

        assertNotNull(mahlzeit)
        assertEquals(AccountStatus.AKTIV, mahlzeit!!.status)
        assertEquals(3.2, mahlzeit.restU!!, 1e-9)
        assertFalse(mahlzeit.withoutBudget)
        assertEquals(jetzt + 85 * 60_000L, mahlzeit.windowEndTs)
        assertEquals(1.4, mahlzeit.scale)
        assertFalse(mahlzeit.calculator)
        assertNull(mahlzeit.balanceU)
    }

    /**
     * Der Ausgang traegt den Kontostand VOR der Buchung des Zyklus. Der gespeicherte Stand derselben Autorisierung traegt
     * sie schon - so steht ein SMB aus dem Konto nie neben einem Rest, von dem er noch nicht abgezogen ist.
     */
    @Test
    fun `der Rest ist der nach der Buchung - aus dem gespeicherten Konto derselben Autorisierung`() {
        val o = mit(outcome(), kontoExport())
        val status = punkt(kontoStatus = AccountStatus.AKTIV)
        val danach = konto(gebuchtU = 1.4)

        val mahlzeit = FuseChipStand.aus(o, status, null, kontoDanach = danach).meal!!
        assertEquals(2.6, mahlzeit.restU!!, 1e-9)
        // Fenster und Skalierer sind am Marker festgehalten - sie bleiben die des Zyklus.
        assertEquals(jetzt + 85 * 60_000L, mahlzeit.windowEndTs)
        assertEquals(1.4, mahlzeit.scale)

        // Ein Konto einer anderen Autorisierung (ein neuer Druck dazwischen) zaehlt nicht: dann gilt der Stand des Zyklus.
        val anderes = konto(gebuchtU = 3.9).copy(authId = jetzt - 1_000L)
        assertEquals(3.2, FuseChipStand.aus(o, status, null, kontoDanach = anderes).meal!!.restU!!, 1e-9)
        assertEquals(3.2, FuseChipStand.aus(o, status, null, kontoDanach = null).meal!!.restU!!, 1e-9)
    }

    /** Gilt im Zyklus kein Konto, macht auch ein gespeichertes keinen Rest daraus. */
    @Test
    fun `ohne geltendes Konto gibt es keinen Rest, auch wenn eins gespeichert ist`() {
        val rueckfall = mit(outcome(), kontoExport(gilt = false, rueckfall = "ACCOUNT_LOST"))

        val mahlzeit = FuseChipStand.aus(rueckfall, punkt(kontoStatus = AccountStatus.OHNE_KONTO), null, kontoDanach = konto()).meal!!

        assertNull(mahlzeit.restU)
        assertEquals(0L, mahlzeit.windowEndTs)
        assertNull(FuseChipStand.aus(outcome(), punkt(), null, kontoDanach = konto()).meal)
    }

    @Test
    fun `nur Marker ist ein Konto ohne Budget und ohne Skalierer`() {
        val nurMarker = kontoExport(zustand = konto(budgetU = 0.0, gebuchtU = 0.0))
        val mahlzeit = FuseChipStand.aus(mit(outcome(), nurMarker), punkt(kontoStatus = AccountStatus.AKTIV), null).meal!!

        assertTrue(mahlzeit.withoutBudget)
        assertEquals(0.0, mahlzeit.restU)
        assertNull(mahlzeit.scale)
    }

    /** Im Rueckfall gilt kein Konto: sein Stand darf nicht wie ein laufendes Budget aussehen. */
    @Test
    fun `im Rueckfall ohne Konto gibt es weder Rest noch Fenster`() {
        val rueckfall = kontoExport(gilt = false, rueckfall = "ACCOUNT_LOST")
        val mahlzeit = FuseChipStand.aus(mit(outcome(), rueckfall), punkt(kontoStatus = AccountStatus.OHNE_KONTO), null).meal!!

        assertEquals(AccountStatus.OHNE_KONTO, mahlzeit.status)
        assertNull(mahlzeit.restU)
        assertFalse(mahlzeit.withoutBudget)
        assertEquals(0L, mahlzeit.windowEndTs)
        assertNull(mahlzeit.scale)
    }

    @Test
    fun `eine Rechner-Mahlzeit zeigt die Bilanz G und keinen Skalierer`() {
        val mahlzeit = FuseChipStand.aus(mit(outcome(), kontoExport(), bilanz(g = -0.4)), punkt(kontoStatus = AccountStatus.AKTIV), null).meal!!

        assertTrue(mahlzeit.calculator)
        assertEquals(-0.4, mahlzeit.balanceU)
        assertNull(mahlzeit.scale)
        // Das Konto laeuft daneben weiter: Rest und Fenster bleiben ablesbar.
        assertEquals(3.2, mahlzeit.restU!!, 1e-9)
        assertEquals(jetzt + 85 * 60_000L, mahlzeit.windowEndTs)
    }

    @Test
    fun `die Bilanz allein reicht fuer die Kachel, eine nicht berechenbare bleibt leer`() {
        val nurBilanz = FuseChipStand.aus(mit(outcome(), bilanz = bilanz(g = 0.7)), punkt(), null).meal!!
        assertTrue(nurBilanz.calculator)
        assertEquals(0.7, nurBilanz.balanceU)
        assertNull(nurBilanz.status)
        assertNull(nurBilanz.restU)

        val ohneWert = FuseChipStand.aus(mit(outcome(), bilanz = bilanz(g = null)), punkt(), null).meal!!
        assertTrue(ohneWert.calculator)
        assertNull(ohneWert.balanceU)
    }

    /** Die Bilanz ohne Marker ist nur noch eine auslaufende Absenkung - keine Mahlzeit mehr. */
    @Test
    fun `ohne Konto und ohne Mahlzeit der Bilanz gibt es keine Kachelwerte`() {
        assertNull(FuseChipStand.aus(outcome(), punkt(), null).meal)
        assertNull(FuseChipStand.aus(mit(outcome(), bilanz = bilanz().copy(markerTs = 0L)), punkt(), null).meal)
    }
}
