package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die gemeinsame Bilanz der Rechner-Mahlzeit und die Basal-Ruecknahme (GV-R) als reine Rechnung. Die Wirkung im Regelkreis
 * prueft `ECarbsHclClosedLoopTest` (64 Faelle), das hier sind die Vertraege der einzelnen Teile.
 */
class MealBalanceTest {

    private val t0 = 1_800_000_000_000L
    private val minMs = 60_000L
    private val kurve = ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 2.5, 3.0)!!

    private fun eingabe(
        minuten: Int,
        eCarbs: List<ECarbs.Part> = emptyList(),
        beobachtetU: Double? = 0.0,
        bg: Double = 150.0,
        r: Double = 0.0,
        iob: Double = 1.0,
        episodenIob: Double = 1.0,
        sofortU: Double = 4.0,
    ) = MealBalance.Input(
        nowTs = t0 + minuten * minMs, minutesSinceMarker = minuten.toDouble(), windowMin = 180.0,
        immediateCarbsU = sofortU, phi0 = 1.0, curve = kurve, observedU = beobachtetU,
        eCarbs = eCarbs, icGPerU = 10.0, bgMgdl = bg, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        driveMgdlPerMin = r, driveTauMin = 60.0, iobU = iob, transportU = 0.0, episodeIobU = episodenIob,
    )

    @Test
    fun `G ist BG ueber Ziel plus das Groessere aus Rest und Fortsetzung, minus IOB und Transport`() {
        // (150 - 100 + max(1 x 50, 0.5 x 60)) / 50 - (1 + 0.2) = (50 + 50) / 50 - 1.2 = 0.8
        assertEquals(0.8, MealBalance.balanceU(150.0, 100.0, 50.0, 1.0, 0.5, 60.0, 1.0, 0.2)!!, 1e-12)
        // Die Fortsetzung ist groesser: (150 - 100 + 2 x 60) / 50 - 1.2 = 3.4 - 1.2 = 2.2
        assertEquals(2.2, MealBalance.balanceU(150.0, 100.0, 50.0, 1.0, 2.0, 60.0, 1.0, 0.2)!!, 1e-12)
        // Ein Fallen zaehlt nicht als negative Fortsetzung.
        assertEquals(MealBalance.balanceU(150.0, 100.0, 50.0, 0.0, 0.0, 60.0, 1.0, 0.0), MealBalance.balanceU(150.0, 100.0, 50.0, 0.0, -3.0, 60.0, 1.0, 0.0))
        assertNull(MealBalance.balanceU(150.0, 100.0, 0.0, 1.0, 0.5, 60.0, 1.0, 0.0))
        assertNull(MealBalance.balanceU(Double.NaN, 100.0, 50.0, 1.0, 0.5, 60.0, 1.0, 0.0))
    }

    @Test
    fun `im Kontofenster zaehlt der Sofort-KH-Rest, danach nur noch solange Insulin der Mahlzeit wirkt`() {
        val start = ECarbs.CobAttribution()
        val imFenster = MealBalance.cycle(eingabe(60), start)
        assertTrue(imFenster.announcedActive)
        // Rest = 4 - 1 x Kurve(60) = 4 - 2 = 2 U.
        assertEquals(2.0, imFenster.immediateRestU, 1e-9)
        assertEquals(MealBalance.balanceU(150.0, 100.0, 50.0, 2.0, 0.0, 60.0, 1.0, 0.0)!!, imFenster.balanceU!!, 1e-12)

        val danach = MealBalance.cycle(eingabe(200), start)
        assertFalse(danach.announcedActive)
        assertEquals(MealBalance.balanceU(150.0, 100.0, 50.0, 0.0, 0.0, 60.0, 1.0, 0.0)!!, danach.balanceU!!, 1e-12)

        assertNull(MealBalance.cycle(eingabe(200, episodenIob = MealBalance.EPISODE_IOB_MIN_U), start).balanceU)
    }

    @Test
    fun `angekuendigte eCarbs zaehlen bis zum Ende der Aufnahme des letzten Teils, mal der Ruecknahme`() {
        val teile = ECarbs.parts(17.0, t0 + 60 * minMs, 180)
        val ende = 60 + 165 + ECarbs.PART_MIN + ECarbs.ABSORPTION_MIN.toInt()
        assertTrue(MealBalance.cycle(eingabe(ende - 1, teile), ECarbs.CobAttribution()).announcedActive)
        assertFalse(MealBalance.cycle(eingabe(ende, teile), ECarbs.CobAttribution()).announcedActive)
        // Vor dem eCarbs-Beginn ist nichts angekommen: kein eCarbs-Rest, w = 1.
        val vorher = MealBalance.cycle(eingabe(30, teile), ECarbs.CobAttribution())
        assertEquals(0.0, vorher.eCarbsRestU, 1e-12)
        assertEquals(1.0, vorher.weight, 1e-12)
        // Ein angekommener, nicht aufgenommener Teil zaehlt; nach dem Verfall nicht mehr.
        val angekommen = MealBalance.cycle(eingabe(61, teile), ECarbs.CobAttribution())
        assertTrue(angekommen.eCarbsRestU > 0.0)
        val ohneMehr = listOf(teile.first())
        val verfallen = MealBalance.cycle(eingabe(60 + ECarbs.REST_LAPSE_MIN.toInt(), ohneMehr), ECarbs.CobAttribution())
        assertEquals(0.0, verfallen.eCarbsRestU, 1e-12)
    }

    @Test
    fun `ohne gueltige Beobachtung wird nichts neu zugeordnet`() {
        val teile = ECarbs.parts(17.0, t0 + 60 * minMs, 180)
        val stand = ECarbs.CobAttribution(absorbedU = 0.1, maxExcessU = 0.3, lastArrivedU = 0.2)
        assertEquals(stand, MealBalance.cycle(eingabe(80, teile, beobachtetU = null), stand).attribution)
        assertEquals(stand.step(5.0 - kurve.at(80.0), ECarbs.arrivedU(teile, 10.0, t0 + 80 * minMs)), MealBalance.cycle(eingabe(80, teile, beobachtetU = 5.0), stand).attribution)
    }

    /**
     * UEBERLAPPENDE RECHNER-MAHLZEITEN (Codex 05.11.): der neue Marker fuehrt die eCarbs der laufenden Episode mit Herkunft
     * und verbuchter Aufnahme weiter. Im Wechselzyklus ist ihr Rest genau der davor - ohne Uebernahme waere er weg, mit
     * leerer Zuordnung begaenne er neu.
     */
    @Test
    fun `ein neuer Marker uebernimmt laufende eCarbs - ihr Rest geht weder verloren noch beginnt er neu`() {
        val e1 = ECarbs.parts(40.0, t0, 120)
        // Die erste Mahlzeit: die Beobachtung liegt ueber der Sofort-KH-Erwartung, Aufnahme wird verbucht.
        var a = ECarbs.CobAttribution()
        for (m in 0..70) a = MealBalance.cycle(eingabe(m, e1, beobachtetU = kurve.at(m.toDouble()) + 0.02 * m), a).attribution
        assertTrue(a.absorbedU > 0.5) { "Vorbedingung: verbuchte Aufnahme ${a.absorbedU}" }
        // Der Wechsel in Minute 71: kein Teil kommt an, keiner verfaellt - der Rest muss genau bleiben.
        val jetzt = t0 + 71 * minMs
        val vorher = MealBalance.Episode(
            t0, ExpectationAccount.Observation(2.0, true, jetzt - minMs, 150.0), a, eCarbs = listOf(MealBalance.ECarbsRef(7L, t0)),
        )
        val restVorher = a.remainingWithLapseU(e1, 10.0, jetzt)
        assertTrue(MealBalance.inherits(vorher, jetzt, jetzt))
        val neu = MealBalance.inherit(vorher, jetzt, ExpectationAccount.Observation(0.0, true, jetzt, 150.0))
        assertEquals(jetzt, neu.authId)
        assertEquals(vorher.eCarbs, neu.eCarbs)
        assertEquals(a.absorbedU, neu.attribution.absorbedU, 0.0)
        assertEquals(a.lastArrivedU, neu.attribution.lastArrivedU, 0.0)
        assertNull(neu.attribution.maxExcessU) { "die Hochwassermarke gehoert zur alten Beobachtung" }
        val r = MealBalance.cycle(
            eingabe(71, beobachtetU = 0.0).copy(minutesSinceMarker = 0.0, earlierECarbs = listOf(MealBalance.EarlierECarbs(t0, e1))),
            neu.attribution,
        )
        assertEquals(t0, r.earlier.single().originAuthId)
        assertEquals(40.0, r.earlier.single().eCarbsG, 0.0)
        assertEquals(restVorher, r.earlier.single().restU, 1e-12)
        // Die erste Beobachtung des neuen Markers setzt nur die Marke - nichts wird neu verbucht.
        assertEquals(a.absorbedU, r.attribution.absorbedU, 0.0)
        // Gegenprobe: mit leerer Zuordnung zaehlten schon aufgenommene Teile ein zweites Mal.
        assertTrue(ECarbs.CobAttribution().remainingWithLapseU(e1, 10.0, jetzt) > restVorher + 0.1)
        // Der Rest zaehlt in G, mal der Ruecknahme w der frueheren Mahlzeit (ohne Antrieb: G steigt um genau diese Menge).
        val ohne = MealBalance.cycle(eingabe(71, beobachtetU = 0.0).copy(minutesSinceMarker = 0.0), neu.attribution)
        assertEquals(r.earlier.single().weight * restVorher, r.balanceU!! - ohne.balanceU!!, 1e-9)
    }

    @Test
    fun `uebernommen wird nur von einer aelteren, nicht beendeten, eben gerechneten Episode mit eCarbs`() {
        val jetzt = t0 + 100 * minMs
        val e = MealBalance.Episode(
            t0, ExpectationAccount.Observation(1.0, true, jetzt - minMs, 150.0), eCarbs = listOf(MealBalance.ECarbsRef(7L, t0)),
        )
        assertTrue(MealBalance.inherits(e, jetzt, jetzt))
        assertFalse(MealBalance.inherits(e.copy(done = true), jetzt, jetzt))
        assertFalse(MealBalance.inherits(e, t0, jetzt))
        assertFalse(MealBalance.inherits(e.copy(eCarbs = emptyList()), jetzt, jetzt))
        // Laenger nicht gerechnet als die Beobachtung luecken darf: der neue Marker gilt allein.
        val grenzeMs = (ExpectationAccount.MAX_OBSERVATION_GAP_MIN * 60_000.0).toLong()
        assertTrue(MealBalance.inherits(e.copy(observation = e.observation.copy(lastObsTs = jetzt - grenzeMs)), jetzt, jetzt))
        assertFalse(MealBalance.inherits(e.copy(observation = e.observation.copy(lastObsTs = jetzt - grenzeMs - 1)), jetzt, jetzt))
    }

    /**
     * Jede Mahlzeit hat ihre eigene Bestaetigung und Ruecknahme: die verbuchte Aufnahme fuellt zuerst die aeltere, je
     * hoechstens ihre angekommene Menge. Eine bestaetigte fruehere stuetzt so keine ausbleibende neue.
     */
    @Test
    fun `die aeltere Mahlzeit bekommt die Aufnahme zuerst, die neue hat ihre eigene Ruecknahme`() {
        val m2 = t0 + 60 * minMs
        val e1 = ECarbs.parts(20.0, t0, 60)
        val e2 = ECarbs.parts(20.0, m2, 60)
        val frueher = listOf(MealBalance.EarlierECarbs(t0, e1))
        fun zyklus(aufgenommenU: Double) = MealBalance.cycle(
            eingabe(100, e2, beobachtetU = null).copy(minutesSinceMarker = 40.0, earlierECarbs = frueher),
            ECarbs.CobAttribution(absorbedU = aufgenommenU, maxExcessU = 0.0, lastArrivedU = 3.5),
        )
        // Die fruehere Mahlzeit ist ganz aufgenommen, fuer die neue ist nichts verbucht.
        val r = zyklus(2.0)
        assertEquals(1.0, r.earlier.single().confirmation, 1e-12)
        assertEquals(1.0, r.earlier.single().weight, 1e-12)
        assertEquals(0.0, r.confirmation, 1e-12)
        assertEquals(0.0, r.weight, 1e-12)
        // Gegenprobe: ein gemeinsamer Topf truege die Bestaetigung der frueheren Mahlzeit auf die neue.
        val jetzt = t0 + 100 * minMs
        val gemeinsam = (e1 + e2).sortedBy { it.ts }
        assertTrue(ECarbs.CobAttribution(absorbedU = 2.0).confirmation(ECarbs.arrivedEffectU(gemeinsam, 10.0, jetzt), 100.0) > 0.5)
        // Was ueber die angekommene Menge der frueheren hinausgeht, gehoert der neuen.
        val mehr = zyklus(2.6)
        assertEquals(1.0, mehr.earlier.single().confirmation, 1e-12)
        assertEquals(0.6 / ECarbs.arrivedEffectU(e2, 10.0, jetzt), mehr.confirmation, 1e-9)
    }

    @Test
    fun `die beobachtete Mahlzeitwirkung laeuft mit der Rechnung des Kontos weiter`() {
        val o = ExpectationAccount.Observation(0.5, true, t0, 120.0)
        // 5 min, BG +10, Aktivitaet 0,01 U/min, ISF 50, ISF am Marker 40: D += (10 + 0,01 x 50 x 5) / 40.
        val n = ExpectationAccount.observe(o, t0 + 5 * minMs, 130.0, 0.01, 50.0, 40.0)
        assertEquals(0.5 + 12.5 / 40.0, n.disturbanceU, 1e-12)
        assertTrue(n.valid)
        assertEquals(t0 + 5 * minMs, n.lastObsTs)
        assertEquals(130.0, n.lastBgMgdl, 0.0)
        // Kein Zeitfortschritt: unveraendert. Eine Luecke ueber die Grenze macht sie ungueltig, und so bleibt sie.
        assertEquals(o, ExpectationAccount.observe(o, t0, 130.0, 0.01, 50.0, 40.0))
        val luecke = ExpectationAccount.observe(o, t0 + 11 * minMs, 130.0, 0.01, 50.0, 40.0)
        assertFalse(luecke.valid)
        assertEquals(0.5, luecke.disturbanceU, 0.0)
        assertFalse(ExpectationAccount.observe(luecke, t0 + 12 * minMs, 131.0, 0.01, 50.0, 40.0).valid)
    }

    @Test
    fun `ein laufender Befehl haelt die Grenze, solange sein restlicher Entzug die Ueberdeckung nicht uebersteigt`() {
        // Basal 0,45 U/h, laufend 0,05 U/h, noch 15 min: restlicher Entzug 0,4 x 15 / 60 = 0,1 U.
        assertTrue(MealBalance.holdsLimit(0.05, 15.0, 0.45, 0.1))
        assertFalse(MealBalance.holdsLimit(0.05, 15.0, 0.45, 0.09))
        // Abgelaufen oder ueber dem Basal: kein Entzug mehr.
        assertTrue(MealBalance.holdsLimit(0.05, 0.0, 0.45, 0.0))
        assertTrue(MealBalance.holdsLimit(0.6, 20.0, 0.45, 0.0))
    }

    @Test
    fun `der SMB-Weg bekommt G minus Kanalplan`() {
        assertEquals(0.3, MealBalance.smbCapU(0.5, 0.2)!!, 1e-12)
        assertEquals(0.5, MealBalance.smbCapU(0.5, -1.0)!!, 1e-12)
        assertNull(MealBalance.smbCapU(null, 0.2))
        // Nicht berechenbar (ein Wert nicht endlich): keine Grenze aus G - die Rechnung gilt ohne Bilanz, nie eine NaN-Menge.
        assertNull(MealBalance.smbCapU(0.5, Double.NaN))
        assertNull(MealBalance.smbCapU(Double.NaN, 0.2))
        assertNull(MealBalance.smbCapU(Double.POSITIVE_INFINITY, 0.2))
    }

    /** Flache Bahn, Antrieb 0. */
    private fun bahn(mean: Double, lower: Double): PredictorResult {
        val pts = (1..120).map { TrajectoryPoint(it, it * minMs, mean, lower, 0.0, 0.0, 0.0) }
        return PredictorResult(
            points = pts, predictionAnchorTs = 0L, bgAtAnchor = mean,
            minMeanBg = mean, minLowerBg = minOf(mean, lower), timeToMinLowerMin = 30,
            bgAtHorizonMean = mean, bgAtHorizonLower = lower,
            lineageKind = "ACTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
            modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private fun zustand() = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = 1.0, bolusIobU = 1.0, basalIobU = 0.0,
        iobThU = 4.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        smbRatioCorrection = 0.5, smbRatioRise = 0.5,
        rSignedMgdlPerMin = null, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 2.0, pumpBusy = false,
        reboundWindow = false, nightWindow = false, nightDeadbandMgdl = 0.0,
    )

    @Test
    fun `die Grenze aus G senkt den gemeinsamen Bedarf auf beiden Wegen und hebt ihn nie`() {
        val p = bahn(160.0, 140.0)
        fun entscheide(konto: Double?, grenze: Double?) = FuseController.decide(
            zustand(), p, limits = FuseController.Limits(releaseHorizonMin = 45),
            evidenceCreditActive = false, evidenceMayOverrideRebound = false, lowThreat = LowThreatGate.Verdict.NONE,
            expectationDemandU = konto, balanceCapU = grenze,
        )
        val heute = entscheide(null, null)
        // Grenze ueber dem Bedarf: nichts aendert sich. Nicht berechenbar zaehlt als keine Grenze.
        assertEquals(heute, entscheide(null, 5.0))
        assertEquals(heute, entscheide(null, Double.NaN))
        // Grenze 0,4 U: Normalweg 0,4 x Ratio 0,5 = 0,2 U; der Kontoweg wird ebenso begrenzt.
        val d = entscheide(null, 0.4)
        assertEquals(0.4, d.insulinReqU!!, 1e-9)
        assertEquals(0.2, d.smbU, 1e-9)
        assertEquals(0.4, entscheide(1.5, 0.4).smbU, 1e-9)
        // Grenze <= 0: kein Bedarf, keine Menge.
        val keine = entscheide(1.5, -0.3)
        assertEquals(FuseController.Block.NO_DEMAND, keine.block)
        assertEquals(0.0, keine.smbU, 0.0)
    }

    private fun wunsch(
        g: Double?,
        r: Double = 0.0,
        kanal: Boolean = false,
        schutzNull: Boolean = false,
        vorher: Double = 0.0,
    ) = MealBalance.withdrawal(
        MealBalance.WithdrawalInput(
            balanceU = g, driveMgdlPerMin = r, rampLowMgdlPerMin = 1.25, channelActive = kanal, protectiveZeroActive = schutzNull,
            scheduledBasalUPerH = 0.45, basalStepUPerH = 0.05, withdrawnBeforeExecutionU = vorher,
        ),
    )

    @Test
    fun `keine Ruecknahme ohne Ueberdeckung, waehrend des Anstiegs, bei laufendem Kanal oder Schutz-Null`() {
        assertEquals(MealBalance.NoWithdrawal.INACTIVE, wunsch(null).reason)
        assertEquals(MealBalance.NoWithdrawal.NOT_OVERCOVERED, wunsch(0.0).reason)
        assertEquals(MealBalance.NoWithdrawal.RISING, wunsch(-0.3, r = 1.25).reason)
        assertEquals(MealBalance.NoWithdrawal.CHANNEL, wunsch(-0.3, kanal = true).reason)
        assertEquals(MealBalance.NoWithdrawal.PROTECTIVE_ZERO, wunsch(-0.3, schutzNull = true).reason)
        assertEquals(MealBalance.NoWithdrawal.RISING, wunsch(-0.3, r = Double.NaN).reason)
    }

    @Test
    fun `ein Befehl nimmt nie mehr als die Ueberdeckung und geht nie unter einen Pumpenschritt`() {
        // Ein voller Befehl waere 0,45 U/h x 30 min = 0,225 U; die Untergrenze 0,05 U/h nimmt hoechstens 0,2 U.
        val voll = wunsch(-0.5)
        assertEquals(0.05, voll.rateUPerH!!, 1e-12)
        assertEquals(0.225, voll.limitU, 1e-12)
        // Kleine Ueberdeckung: Teilabsenkung, aufgerundet - 0,1 U ueber 30 min sind 0,2 U/h weniger, also 0,25 U/h.
        val teil = wunsch(-0.1)
        assertEquals(0.25, teil.rateUPerH!!, 1e-12)
        assertTrue((0.45 - teil.rateUPerH!!) * MealBalance.COMMAND_MIN / 60.0 <= teil.limitU + 1e-12)
        // Unter einem Pumpenschritt ueber die Befehlsdauer: keine Ruecknahme.
        assertEquals(MealBalance.NoWithdrawal.TOO_SMALL, wunsch(-0.02).reason)
        // Was ein laufender Befehl bis zur Ausfuehrung noch nimmt, geht von der Grenze ab.
        assertEquals(0.1 - 0.04, wunsch(-0.1, vorher = 0.04).limitU, 1e-12)
        // Ueber ein ganzes Raster: der zurueckgehaltene Teil eines Befehls bleibt unter seiner Grenze.
        for (i in 1..200) {
            val w = wunsch(-i * 0.005)
            w.rateUPerH?.let { rate -> assertTrue((0.45 - rate) * MealBalance.COMMAND_MIN / 60.0 <= w.limitU + 1e-12) { "G=${-i * 0.005}" } }
            w.rateUPerH?.let { rate -> assertTrue(rate >= 0.05 - 1e-12) }
        }
        assertNotNull(wunsch(-0.03).rateUPerH)
    }
}
