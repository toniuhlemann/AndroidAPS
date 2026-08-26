package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER RUHE-AUSGANG AUS PHASE A - und vor allem die Kante, die ihn vom
 * Vollbatchpfad trennt.
 *
 * Die Vorgaengerfassung gab `releases: Boolean` heraus. Damit erreichte eine
 * bestaetigte RUHE denselben `liftUpfront`-Pfad wie die bestaetigte schnelle
 * Erholung, stempelte dort einen `MEAL_UPFRONT`-Grant, und [MarkerFloor] hob
 * ihn nach dem `finalVerify` auf die volle autorisierte Menge an. Gemessen am
 * Abendfall des 25.08.2026: 3,60 U bei BG 78, acht mg/dl ueber dem Guard-Boden,
 * in einem Zyklus mit `insulinReq <= 0`.
 *
 * Die Tests hier pruefen deshalb nicht nur, WANN freigegeben wird, sondern dass
 * die drei Faelle typisiert getrennt bleiben.
 */
class UpfrontRecoveryTest {

    private val marker = 1_700_000_000_000L

    private fun keineGefahr() = UpfrontRecovery.Hazards(
        descentRisk = false, lowThreat = false, zeroLatch = false, rebound = false,
        signalUnhealthy = false, technical = false, ledgerHold = false,
    )

    private val regelVersion = 31

    private fun ruhig(
        zyklen: Int = 3,
        version: Int = regelVersion,
    ) = UpfrontRecovery.Params.of(
        calmCycles = zyklen, minUkf = 0.05, minGuardDistanceMgdl = 5.0,
        calmTreatment = UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
        ruleSetVersion = version,
    )

    private fun bewerte(
        params: UpfrontRecovery.Params = ruhig(),
        prior: UpfrontRecovery.Track = UpfrontRecovery.Track.EMPTY,
        deferredOpen: Boolean = true,
        inPhaseA: Boolean = true,
        markerIdentity: Long = marker,
        hazards: UpfrontRecovery.Hazards = keineGefahr(),
        risingConfirmed: Boolean = false,
        ukf: Double? = 0.10,
        q1Falling: Boolean = false,
        guardDistance: Double? = 8.0,
        sourceTs: Long,
        nowTs: Long = sourceTs,
    ) = UpfrontRecovery.evaluate(
        params, prior, deferredOpen, inPhaseA, markerIdentity, hazards, risingConfirmed,
        ukf, q1Falling, guardDistance, sourceTs, nowTs,
    )

    /** Baut einen echten Streak ueber `n` lueckenlose Zyklen auf. */
    private fun streakUeber(
        n: Int,
        params: UpfrontRecovery.Params = ruhig(),
        markerIdentity: Long = marker,
        start: Long = 1_000_000L,
    ): UpfrontRecovery.Decision {
        var d: UpfrontRecovery.Decision =
            bewerte(params = params, markerIdentity = markerIdentity, sourceTs = start)
        for (i in 1 until n) {
            d = bewerte(
                params = params, prior = d.track, markerIdentity = markerIdentity,
                sourceTs = start + i * 60_000L,
            )
        }
        return d
    }

    // ---- DIE ABSOLUTE GEFAHR ------------------------------------------

    @Test
    fun `aktuelle Gefahr schlaegt alles und loescht den Zaehler`() {
        val reif = streakUeber(3)
        assertInstanceOf(UpfrontRecovery.Decision.CalmRecovered::class.java, reif)

        val gefahr = bewerte(
            prior = reif.track,
            hazards = keineGefahr().let {
                UpfrontRecovery.Hazards(
                    descentRisk = true, lowThreat = false, zeroLatch = false, rebound = false,
                    signalUnhealthy = false, technical = false, ledgerHold = false,
                )
            },
            sourceTs = 1_180_000L,
        )
        val b = assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, gefahr)
        assertEquals(UpfrontRecovery.Denial.CURRENT_HAZARD, b.denial)
        assertEquals(0, b.track.streak, "eine unterbrochene Ruhe war keine - der Zaehler faellt auf 0")
        assertTrue(b.hazards.contains("descentRisk"))
    }

    @Test
    fun `jede der sieben Gefahren blockiert einzeln`() {
        val faelle = listOf(
            "descentRisk" to UpfrontRecovery.Hazards(true, false, false, false, false, false, false),
            "lowThreat" to UpfrontRecovery.Hazards(false, true, false, false, false, false, false),
            "zeroLatch" to UpfrontRecovery.Hazards(false, false, true, false, false, false, false),
            "rebound" to UpfrontRecovery.Hazards(false, false, false, true, false, false, false),
            "signal" to UpfrontRecovery.Hazards(false, false, false, false, true, false, false),
            "technical" to UpfrontRecovery.Hazards(false, false, false, false, false, true, false),
            "ledgerHold" to UpfrontRecovery.Hazards(false, false, false, false, false, false, true),
        )
        faelle.forEach { (name, h) ->
            val d = bewerte(prior = streakUeber(3).track, hazards = h, sourceTs = 2_000_000L)
            val b = assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d, name)
            assertEquals(UpfrontRecovery.Denial.CURRENT_HAZARD, b.denial, name)
            assertTrue(b.hazards.contains(name), "$name muss im Export stehen: ${b.hazards}")
        }
    }

    // ---- DIE VORBEDINGUNGEN -------------------------------------------

    @Test
    fun `ohne offenen Aufschub stellt sich die Frage nicht`() {
        val d = bewerte(deferredOpen = false, sourceTs = 1_000_000L)
        assertEquals(UpfrontRecovery.Denial.NOTHING_DEFERRED,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d).denial)
    }

    @Test
    fun `ausserhalb Phase A gibt es keinen Ruhe-Ausgang`() {
        val d = bewerte(inPhaseA = false, sourceTs = 1_000_000L)
        assertEquals(UpfrontRecovery.Denial.NOT_PHASE_A,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d).denial)
    }

    @Test
    fun `ohne Markeridentitaet gibt es keine Autorisierung`() {
        val d = bewerte(markerIdentity = 0L, sourceTs = 1_000_000L)
        assertEquals(UpfrontRecovery.Denial.NO_AUTHORITY,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d).denial)
    }

    // ---- WEG 1 BLEIBT UNVERAENDERT ------------------------------------

    @Test
    fun `bestaetigte schnelle Erholung gibt den Vollbatch frei - auch ohne Ruheparameter`() {
        val d = bewerte(params = UpfrontRecovery.Params.OFF, risingConfirmed = true,
                        sourceTs = 1_000_000L)
        assertInstanceOf(UpfrontRecovery.Decision.FullBatchEligible::class.java, d)
        assertEquals(UpfrontRecovery.TrackMode.RISING, d.track.mode)
    }

    @Test
    fun `ohne Ruheparameter gibt es NIE den ruhigen Pfad`() {
        var d: UpfrontRecovery.Decision = bewerte(params = UpfrontRecovery.Params.OFF,
                                                  sourceTs = 1_000_000L)
        repeat(10) { i ->
            d = bewerte(params = UpfrontRecovery.Params.OFF, prior = d.track,
                        sourceTs = 1_000_000L + (i + 1) * 60_000L)
            assertFalse(d is UpfrontRecovery.Decision.CalmRecovered,
                        "Params.OFF darf niemals CALM_RECOVERED ergeben")
        }
        assertEquals(UpfrontRecovery.Denial.DISABLED,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d).denial)
    }

    // ---- WEG 2: DIE BESTAETIGTE RUHE ----------------------------------

    @Test
    fun `ein einzelner ruhiger Zyklus genuegt nicht`() {
        val d = bewerte(sourceTs = 1_000_000L)
        val b = assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d)
        assertEquals(UpfrontRecovery.Denial.CALM_STREAK_SHORT, b.denial)
        assertEquals(1, b.track.streak, "der Zaehler laeuft trotzdem mit")
        assertEquals(3, b.requiredCycles)
    }

    @Test
    fun `nach der geforderten Zahl lueckenloser Zyklen ist die Ruhe bestaetigt`() {
        val d = streakUeber(3)
        val c = assertInstanceOf(UpfrontRecovery.Decision.CalmRecovered::class.java, d)
        assertEquals(3, c.calmStreak)
        assertEquals(UpfrontRecovery.CalmTreatment.DEMAND_LIMITED, c.treatment)
        assertEquals(8.0, c.guardDistanceMgdl, 1e-9)
        assertEquals(UpfrontRecovery.TrackMode.CALM, c.track.mode)
    }

    @Test
    fun `noch fallende Rate blockiert und nullt den Zaehler`() {
        val zwei = streakUeber(2)
        assertEquals(2, zwei.track.streak)
        val d = bewerte(prior = zwei.track, ukf = -0.01, sourceTs = 1_120_000L)
        val b = assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d)
        assertEquals(UpfrontRecovery.Denial.STILL_FALLING, b.denial)
        assertEquals(0, b.track.streak)
    }

    @Test
    fun `weiter fallendes q1 blockiert`() {
        val d = bewerte(prior = streakUeber(2).track, q1Falling = true, sourceTs = 1_120_000L)
        assertEquals(UpfrontRecovery.Denial.Q1_FALLING,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d).denial)
    }

    @Test
    fun `zu geringer Abstand zum Guard-Boden blockiert`() {
        val d = bewerte(prior = streakUeber(2).track, guardDistance = 4.9, sourceTs = 1_120_000L)
        assertEquals(UpfrontRecovery.Denial.GUARD_DISTANCE,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, d).denial)
    }

    @Test
    fun `nicht endliche Eingaben blockieren, statt durchzurutschen`() {
        assertEquals(UpfrontRecovery.Denial.STILL_FALLING,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java,
                                      bewerte(ukf = Double.NaN, sourceTs = 1L)).denial)
        assertEquals(UpfrontRecovery.Denial.STILL_FALLING,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java,
                                      bewerte(ukf = null, sourceTs = 1L)).denial)
        assertEquals(UpfrontRecovery.Denial.GUARD_DISTANCE,
                     assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java,
                                      bewerte(guardDistance = Double.NaN, sourceTs = 1L)).denial)
    }

    // ---- DIE DREI ANSCHLUSSBEDINGUNGEN --------------------------------
    //
    // Jede davon ist eine Mutationsprobe: wer sie weglaesst, setzt einen
    // Zaehler fort, der zu einer anderen Lage gehoerte - und ein
    // fortgesetzter Zaehler gibt frueher frei.

    @Test
    fun `ein Markerwechsel bricht den Ruhezaehler ab`() {
        val zwei = streakUeber(2)
        assertEquals(2, zwei.track.streak)

        val andererMarker = bewerte(
            prior = zwei.track, markerIdentity = marker + 5_000L, sourceTs = 1_120_000L,
        )
        val b = assertInstanceOf(UpfrontRecovery.Decision.Blocked::class.java, andererMarker)
        assertEquals(UpfrontRecovery.Denial.CALM_STREAK_SHORT, b.denial)
        assertEquals(1, b.track.streak,
                     "die naechste Mahlzeit darf die Ruhe der vorigen nicht erben")
        assertEquals(marker + 5_000L, b.track.markerIdentity)
    }

    @Test
    fun `eine Zykluspause bricht den Ruhezaehler ab`() {
        val zwei = streakUeber(2)
        val nachPause = bewerte(
            prior = zwei.track,
            sourceTs = 1_060_000L + UpfrontRecovery.LUECKENLOS_MAX_MS + 1_000L,
        )
        assertEquals(1, nachPause.track.streak,
                     "ueber eine Luecke hinweg gibt es keine bestaetigte Ruhe")
    }

    @Test
    fun `derselbe Signalpunkt zweimal erhoeht den Zaehler nicht`() {
        // GENAU DER NEUSTARTFALL: nach einem Prozessneustart rechnet der
        // neue Prozess gegen dieselbe Signalepoche. Zwei Rechenversuche auf
        // EINEM Messpunkt sind ein Punkt, nicht zwei Ruhezyklen.
        val zwei = streakUeber(2, start = 1_000_000L)
        assertEquals(2, zwei.track.streak)
        val wiederholung = bewerte(prior = zwei.track, sourceTs = 1_060_000L, nowTs = 1_080_000L)
        assertEquals(1, wiederholung.track.streak,
                     "derselbe sourceTs darf nicht als weiterer Ruhezyklus zaehlen")
    }

    @Test
    fun `eine Beobachtungsluecke bricht den Zaehler auch bei benachbarten Messpunkten ab`() {
        // DIE ZWEITE, EIGENSTAENDIGE BEDINGUNG. Sie ist NICHT durch den
        // Signalanschluss abgedeckt, und die Mutationsprobe hat das gezeigt:
        // in allen anderen Tests folgte `nowTs` dem `sourceTs`, also prueften
        // sie unbemerkt nur eine der beiden Bedingungen.
        //
        // DER FALL: das Geraet schlief zehn Minuten (Doze, Pumpenwartezeit,
        // haengender Zyklus) und rechnet danach auf einem Messpunkt, der nur
        // eine Minute nach dem letzten verarbeiteten liegt - ein Rueckstand,
        // kein Fortschritt. Die Messpunkte sind benachbart, die BEOBACHTUNG
        // aber nicht: zehn Minuten Lage sind ungesehen vergangen. Ein Streak
        // behauptet "durchgehend ruhig beobachtet"; dieser Anspruch ist
        // gebrochen, auch wenn die Reihe der Punkte lueckenlos aussieht.
        val zwei = streakUeber(2, start = 1_000_000L)
        assertEquals(2, zwei.track.streak)
        assertEquals(1_060_000L, zwei.track.lastAcceptedSourceTs)
        assertEquals(1_060_000L, zwei.track.lastEvaluationTs)

        val nachSchlaf = bewerte(
            prior = zwei.track,
            sourceTs = 1_120_000L,                                     // +60 s: anschliessend
            nowTs = 1_060_000L + 10 * 60_000L,                         // +10 min: nicht beobachtet
        )
        assertEquals(1, nachSchlaf.track.streak,
                     "eine Beobachtungsluecke ist keine bestaetigte Ruhe, " +
                         "auch wenn die Messpunkte benachbart sind")
    }

    @Test
    fun `ein rueckwaerts laufender Signalpunkt zaehlt nicht`() {
        val zwei = streakUeber(2, start = 1_000_000L)
        val zurueck = bewerte(prior = zwei.track, sourceTs = 1_030_000L, nowTs = 1_090_000L)
        assertEquals(1, zurueck.track.streak)
    }

    // ---- DER PERSISTENZVERTRAG (fail-closed) ---------------------------

    @Test
    fun `ein unvollstaendig geladener Zaehler wird verworfen, nicht geglaubt`() {
        // Streak ohne Identitaeten - genau das, was ein halber Codec liefert.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = 0L, streak = 5, lastAcceptedSourceTs = 0L,
            lastEvaluationTs = 0L, mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "x",
        ))
        // Identitaeten ohne Streak.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 0, lastAcceptedSourceTs = 1L,
            lastEvaluationTs = 1L, mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "x",
        ))
        // Streak ohne Modus.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 2, lastAcceptedSourceTs = 1L,
            lastEvaluationTs = 1L, mode = UpfrontRecovery.TrackMode.NONE, fingerprint = "x",
        ))
        // Streak ohne Fingerprint - die sechste Identitaet fehlt.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 2, lastAcceptedSourceTs = 1L,
            lastEvaluationTs = 1L, mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "",
        ))
        // Davongelaufener Zaehler.
        assertSame(UpfrontRecovery.Track.EMPTY, UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = UpfrontRecovery.Track.MAX_STREAK + 1,
            lastAcceptedSourceTs = 1L, lastEvaluationTs = 1L,
            mode = UpfrontRecovery.TrackMode.CALM, fingerprint = "x",
        ))
        // Und der vollstaendige Fall kommt durch.
        val gut = UpfrontRecovery.Track.ofPersisted(
            markerIdentity = marker, streak = 2, lastAcceptedSourceTs = 1_000L,
            lastEvaluationTs = 1_000L, mode = UpfrontRecovery.TrackMode.CALM,
            fingerprint = ruhig().fingerprint,
        )
        assertEquals(2, gut.streak)
        assertTrue(gut.consistent)
    }

    @Test
    fun `ein inkonsistenter Zaehler wird auch beim Erben verworfen`() {
        val kaputt = UpfrontRecovery.Track(
            markerIdentity = 0L, streak = 9, lastAcceptedSourceTs = 0L,
            lastEvaluationTs = 0L, mode = UpfrontRecovery.TrackMode.CALM,
            fingerprint = ruhig().fingerprint,
        )
        assertFalse(kaputt.consistent)
        val d = bewerte(prior = kaputt, sourceTs = 1_000_000L)
        assertEquals(1, d.track.streak, "ein inkonsistenter Stand faengt bei 1 an, nicht bei 10")
    }

    // ---- DIE PARAMETERPRUEFUNG ----------------------------------------

    @Test
    fun `unbrauchbare Parameter ergeben OFF statt einer erfundenen Kalibrierung`() {
        val schlecht = listOf(
            Triple(0, 0.05, 5.0), Triple(21, 0.05, 5.0),
            Triple(3, Double.NaN, 5.0), Triple(3, 2.0, 5.0), Triple(3, -2.0, 5.0),
            Triple(3, 0.05, -1.0), Triple(3, 0.05, 101.0), Triple(3, 0.05, Double.NaN),
        )
        schlecht.forEach { (n, u, g) ->
            val p = UpfrontRecovery.Params.of(
                n, u, g, UpfrontRecovery.CalmTreatment.DEMAND_LIMITED, regelVersion,
            )
            assertSame(UpfrontRecovery.Params.OFF, p, "($n, $u, $g) haette OFF ergeben muessen")
        }
        assertNotEquals(UpfrontRecovery.Params.OFF, ruhig(3))
    }

    @Test
    fun `die Behandlungswahl wandert unveraendert in die Entscheidung`() {
        val verschieben = UpfrontRecovery.Params.of(
            2, 0.05, 5.0, UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED, regelVersion,
        )
        val d = streakUeber(2, params = verschieben)
        val c = assertInstanceOf(UpfrontRecovery.Decision.CalmRecovered::class.java, d)
        assertEquals(UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED, c.treatment)
    }

    // ---- DIE EXPORTKENNUNG --------------------------------------------

    @Test
    fun `die drei Modi tragen stabile Kennungen`() {
        assertEquals("BLOCKED", bewerte(deferredOpen = false, sourceTs = 1L).modeName)
        assertEquals("FULL_BATCH_ELIGIBLE",
                     bewerte(risingConfirmed = true, sourceTs = 1L).modeName)
        assertEquals("CALM_RECOVERED", streakUeber(3).modeName)
    }
}
