package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.signal.PairSlopeBand
import app.aaps.fuse.core.signal.SignalWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuseScreenModelTest {

    private val now = 1_700_000_090_000L

    private fun ledger(hold: Boolean, fehler: Map<String, Int> = mapOf("IDENTITY_CONFLICT" to 47)) =
        FuseScreenModel.LedgerInfo(
            hold = hold, holdReason = if (hold) "LEDGER_GLOBAL_HOLD" else null, holdGeneration = if (hold) 47L else 0L,
            activeErrors = if (hold) fehler else emptyMap(),
            openEntries = 6, grossLiabilityU = 0.85, lastRepairTs = null,
        )

    /**
     * DER SCHIRM MUSS DEM KOPF WIDERSPRECHEN, WENN DER LEDGER HAELT.
     *
     * Am 10.08.2026 stand oben `Health READY` und zwanzig Zeilen tiefer
     * `Kandidat LEDGER_HOLD - erlaubt 0.00 U`. Health ist der Zustand des
     * BEOBACHTERS und war korrekt - nur liest niemand einen Schirm so. Wer
     * oben READY sieht, sucht den Fehler nicht weiter unten.
     *
     * Geprueft wird deshalb nicht "irgendwo steht Hold", sondern dass die
     * Meldung VOR dem Beobachterblock steht: die Reihenfolge IST hier die
     * Aussage.
     */
    @Test
    fun `ein gehaltener Ledger meldet sich vor dem Beobachter`() {
        val t = FuseScreenModel.render(outcome(), null, now, null, ledger(hold = true))
        assertTrue(t.contains("HOLD - FUSE GIBT NICHTS AB")) { "der Hold muss beim Namen genannt werden" }
        assertTrue(t.contains("IDENTITY_CONFLICT x47")) { "und mit Ursache, sonst weiss niemand wohin" }
        assertTrue(t.contains("Reparatur")) { "ein Ausweg gehoert dazu" }

        val holdPos = t.indexOf("LEDGER")
        val healthPos = t.indexOf("Health")
        assertTrue(holdPos in 0 until healthPos) {
            "die Sperre muss VOR 'Health READY' stehen - sonst widerspricht der Kopf ihr weiterhin"
        }
    }

    /** Ohne Hold bleibt die Zeile stehen, nur leise - "keine Zeile" waere von
     *  "noch nie geschaut" nicht zu unterscheiden. */
    @Test
    fun `ohne Hold meldet der Ledger sich leise`() {
        val t = FuseScreenModel.render(outcome(), null, now, null, ledger(hold = false))
        assertTrue(t.contains("Ledger")) { "auch der freie Zustand ist eine Aussage" }
        assertFalse(t.contains("GIBT NICHTS AB"))
    }

    /**
     * EIN HOLD OHNE BENANNTEN FEHLER BLEIBT SICHTBAR.
     *
     * `holdActuation` ist wahr, sobald IRGENDEINE Zeile `failClosed` ist -
     * dafuer braucht es keinen globalen Fehlereintrag. Die Ursachenzeile waere
     * dann leer, und eine Anzeige, die nur bei bekannter Ursache warnt, waere
     * genau in dem Fall stumm, in dem am wenigsten klar ist, was los ist.
     */
    @Test
    fun `ein Hold ohne benannte Ursache meldet sich trotzdem`() {
        val t = FuseScreenModel.render(
            outcome(), null, now, null,
            FuseScreenModel.LedgerInfo(
                hold = true, holdReason = "LEDGER_STATE_HOLD", holdGeneration = 3L, activeErrors = emptyMap(),
                openEntries = 0, grossLiabilityU = 0.0, lastRepairTs = null,
            ),
        )
        assertTrue(t.contains("HOLD - FUSE GIBT NICHTS AB")) {
            "ohne Ursache ist die Warnung noetiger, nicht entbehrlicher"
        }
        assertTrue(t.contains("keine benannt")) { "und die fehlende Ursache wird als solche gesagt" }
    }

    /** Ohne Angabe gar keine Zeile - lieber nichts als eine erfundene
     *  Freimeldung. */
    @Test
    fun `ohne Ledger-Angabe wird nichts behauptet`() {
        val t = FuseScreenModel.render(outcome(), null, now)
        assertFalse(t.contains("GIBT NICHTS AB"))
        assertFalse(t.contains("Ledger  "))
    }

    private fun signal(rSigned: Double? = 0.8, bound: SignalWindow.Bound = SignalWindow.Bound.NONE) =
        FuseSignalSource.Signal(
            sourceTs = 1_700_000_000_000L, rawBg = 132.0, q1 = 130.0, rSigned = rSigned,
            ukfRatePerMin = 1.1, ukfLearnedR = 2.2, rawSlopePerMin = 1.4, activityAtAnchor = 0.01, isfAtAnchor = 90.0,
            adjusted = emptyList(), activity = ActivityValidity.VALID,
            samplesUsed = 19, rawSeriesSize = 200, gapBeforeMin = 1.0, stepFromLastMgdl = -1.0, stepRateActualMgdlPerMin = -1.0, postGapIndex = 18, q1Outlier = false,
            boundedBy = bound, windowFromTs = 1_699_988_120_000L,
        )

    private fun outcome(
        smbU: Double = 0.15,
        block: FuseController.Block = FuseController.Block.NONE,
        abort: String? = null,
        signal: FuseSignalSource.Signal? = signal(),
        tail: TailLiability.Report? = null,
        denial: String? = null,
        episodeId: Long = 0L,
        revoked: Boolean = false,
    ) = FuseCycleRunner.Outcome(
        decision = FuseController.Decision(
            smbU, FuseController.TbrAction.KEEP_CURRENT, block, 1.4, 180.0, 95.0, "smbRatio",
            tail, if (tail != null) 0.05 else 0.0,
        ),
        tbr = null, prediction = null, sourceTs = signal?.sourceTs, computeTs = 1_700_000_030_000L,
        health = Health.READY, gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "VirtualPumpPlugin"),
        reason = "KEEP", alarm = false, bgMgdl = 130.0, targetMgdl = 97.0, targetSource = "TT",
        signal = signal, band = PairSlopeBand.Estimate(0.8, 0.4, 153),
        discount = app.aaps.fuse.core.predictor.DriveDiscount.apply(0.8, 0.4, 0.01, 85.0, 1.0),
        onset = app.aaps.fuse.core.controller.OnsetChannel.Result(false, false, null, 1.5, "R_CONFIRMED"),
        prime = app.aaps.fuse.core.controller.PrimeRelease.Plan(false, 0.0, 1.2, "NO_MARKER"),
        candidate = null, candidateGap = null, computeDurationMs = 7L, mealStats = null, policy = null,
        state = null, step = null, sensorEpoch = null, calibrationEpoch = null,
        isfMgdlPerU = 85.0, iobU = 1.2, abortReason = abort,
        evidenceEpisodeId = episodeId, evidenceEpisodeDenial = denial, evidenceCreditRevoked = revoked,
    )

    /** Ohne Zyklus GENAU eine Zeile — kein Geruest aus Nullen, das wie ein
     *  Ergebnis aussieht. */
    @Test
    fun `ohne Lauf steht genau eine Zeile auf dem Schirm`() {
        val t = FuseScreenModel.render(null, null, now)
        assertEquals(1, t.trim().lines().size)
        assertFalse(t.contains("0.00"))
    }

    @Test
    fun `ein Abbruchzyklus zeigt den Grund und erfindet keine Zahlen`() {
        val t = FuseScreenModel.render(outcome(abort = "signal: no raw glucose values", signal = null), null, now)
        assertTrue(t.contains("ABBRUCH"))
        assertTrue(t.contains("no raw glucose values"))
        // Kein Signal -> "-" statt einer Zahl.
        assertTrue(t.contains("Signal          -"))
    }

    /**
     * DER WICHTIGSTE FALL: berechnet und angefordert muessen GETRENNT stehen.
     * Sonst liest man eine Menge, die der Pumpen-Riegel laengst genullt hat, als
     * abgegeben.
     */
    @Test
    fun `berechnet und angefordert stehen getrennt`() {
        val t = FuseScreenModel.render(outcome(smbU = 0.15), null, now)
        assertTrue(t.contains("berechnet"))
        assertTrue(t.contains("angefordert"))
        assertTrue(t.contains("0.15 U SMB"))
    }

    @Test
    fun `nicht berechenbares r wird benannt, nicht als Null gezeigt`() {
        val t = FuseScreenModel.render(outcome(signal = signal(rSigned = null)), null, now)
        assertTrue(t.contains("nicht berechenbar"))
        assertFalse(t.contains("r               0.000"))
    }

    /** Die Fenstergrenze erscheint NUR, wenn sie gegriffen hat — sonst waere sie
     *  Rauschen in jeder Zeile. */
    @Test
    fun `die Fenstergrenze erscheint nur wenn sie gegriffen hat`() {
        assertFalse(FuseScreenModel.render(outcome(), null, now).contains("Fenster ab"))
        val t = FuseScreenModel.render(outcome(signal = signal(bound = SignalWindow.Bound.CALIBRATION_START)), null, now)
        assertTrue(t.contains("Fenster ab      CALIBRATION_START"))
    }

    @Test
    fun `der Schwanzbericht zeigt seinen Vermerk mit`() {
        val tail = TailLiability.evaluate(TailLiability.Input(120.0, 0.5, 85.0, 70.0, 0.0))
        val t = FuseScreenModel.render(outcome(tail = tail), null, now)
        assertTrue(t.contains("Schwanz"))
        // Seit dem Sektions-Umbau (c750169) wird der Vermerk uebersetzt statt
        // roh gezeigt: "INCOMPLETE(1of3,noLedger,noCandidate)" -> Modellzeile
        // plus Haken. Der Test bindet den ANZEIGE-Vertrag, nicht den Rohtext
        // (REG-05 des Re-Audits: genau dieser Vertrag war nicht mitgezogen).
        assertTrue(t.contains("PARTIAL 1/3"))
        assertTrue(t.contains("IOB ✓  Transport ✗  Kandidat ✗"))
        // Der Rohtext selbst gehoert NICHT mehr in die Anzeige.
        assertFalse(t.contains(TailLiability.COMPLETENESS_STAGE1))
    }

    /**
     * C4 (Codex-Adjudication bae885f1): mit Transportmenge und beschlossener
     * Menge ist der Schwanz 3/3 - und die Anzeige muss das AUTOMATISCH zeigen,
     * ohne dass jemand eine zweite Wahrheit ueber den Modellstand pflegt.
     */
    @Test
    fun `ein vollstaendiger Schwanzbericht zeigt drei von drei und drei Haken`() {
        val tail = TailLiability.evaluate(
            TailLiability.Input(
                120.0, 0.5, 85.0, 70.0, 0.0,
                transport = TailLiability.Dose(0.20, 0.12),
                candidate = TailLiability.Dose(0.10, 0.07),
            )
        )
        val t = FuseScreenModel.render(outcome(tail = tail), null, now)
        assertTrue(t.contains("VOLLSTAENDIG 3/3")) { t }
        assertTrue(t.contains("IOB ✓  Transport ✓  Kandidat ✓")) { t }
        // Die drei Terme stehen einzeln da - eine Summe ohne Herkunft waere
        // genau die Zahl, die spaeter niemand mehr auseinandernehmen kann.
        assertTrue(t.contains("Transport @ H")) { t }
        assertTrue(t.contains("Dosis @ H")) { t }
    }

    /** Eine obere Schranke (kein Einheitskern -> volle Menge gerechnet) ist
     *  KEIN gesicherter Haken. */
    @Test
    fun `eine obere Schranke wird nicht als gesicherter Haken gezeigt`() {
        val tail = TailLiability.evaluate(
            TailLiability.Input(
                120.0, 0.5, 85.0, 70.0, 0.0,
                transport = TailLiability.Dose(0.20, null),
                candidate = TailLiability.Dose(0.10, 0.07),
            )
        )
        val t = FuseScreenModel.render(outcome(tail = tail), null, now)
        assertTrue(t.contains("IOB ✓  Transport ~  Kandidat ✓")) { t }
        assertTrue(t.contains("obere Schranke")) { t }
    }

    /** Ein unbrauchbarer Schwanzbericht darf nicht wie ein Ergebnis aussehen. */
    @Test
    fun `ein unbrauchbarer Schwanzbericht wird als solcher gezeigt`() {
        val kaputt = TailLiability.evaluate(TailLiability.Input(120.0, 0.5, 0.0, 70.0, 0.0))
        val t = FuseScreenModel.render(outcome(tail = kaputt), null, now)
        assertTrue(t.contains("unbrauchbar"))
    }

    @Test
    fun `Block und Phase sind getrennte Zeilen`() {
        val t = FuseScreenModel.render(outcome(block = FuseController.Block.TAIL), null, now)
        assertTrue(t.contains("Block           TAIL"))
        // Phase kommt vom Observer und ist hier unbekannt - also "-", nicht der
        // Blockname unter falschem Etikett.
        assertTrue(t.contains("Phase           -"))
    }

    @Test
    fun `das Ziel nennt seine Herkunft`() {
        assertTrue(FuseScreenModel.render(outcome(), null, now).contains("97 (TT)"))
    }

    @Test
    fun `das Alter des Laufs wird gezeigt`() {
        assertTrue(FuseScreenModel.render(outcome(), null, now).contains("vor 1 min"))
    }
    // ---- Marker- und Episodenzustand auf dem Schirm ------------------------

    /**
     * DER ABBRUCHZYKLUS MUSS DEN GRUND TRAGEN.
     *
     * Nach einem Neustart bricht der Zyklus mangels Signalhistorie erst
     * einmal ab - und genau dann ist `MARKER_EVENT_NOT_DURABLE` am
     * wahrscheinlichsten. Stuende dort nur "ABBRUCH", erfuehre der Nutzer
     * nicht, dass er zweimal druecken muss.
     */
    @Test
    fun `auch ein Abbruch zeigt den Episodenzustand samt Handlungsanweisung`() {
        val t = FuseScreenModel.render(
            outcome(abort = "signal: no raw glucose values", signal = null, denial = "MARKER_EVENT_NOT_DURABLE"),
            null, now,
        )
        assertTrue(t.contains("ABBRUCH"))
        assertTrue(t.contains("MARKER_EVENT_NOT_DURABLE")) { "der Grund muss dastehen" }
        assertTrue(t.contains("erneut druecken")) { "und was zu tun ist" }
    }

    /**
     * EIN ALTER MARKER DARF DIE ZEILE NICHT VERSCHLUCKEN.
     *
     * Die Markerzeile verschwindet nach Fenster plus zwei Stunden - der
     * Ausstieg lag im selben Block wie der Episodenzustand und nahm ihn mit.
     * Am 12.08. stand genau das auf dem Testgeraet: Marker von gestern,
     * Episode abgelehnt, Begruendung unsichtbar.
     */
    @Test
    fun `ein 830 Minuten alter Marker verschluckt den Episodenzustand nicht`() {
        val alt = FuseScreenModel.MarkerInfo(armedTs = now - 830 * 60_000L, windowMin = 90, envelopeU = 3.0)
        val t = FuseScreenModel.render(outcome(denial = "MARKER_STALE"), null, now, alt)
        assertTrue(t.contains("MARKER_STALE")) { "die Ablehnung muss sichtbar bleiben: $t" }
    }

    /** Gegen einen Uhrenruecksprung hilft erneutes Druecken NICHT - der Rat
     *  waere schlicht falsch. */
    @Test
    fun `ein Uhrenruecksprung bekommt die andere Anweisung`() {
        val t = FuseScreenModel.render(outcome(denial = "MARKER_CLOCK_ROLLBACK"), null, now)
        assertTrue(t.contains("Uhr ist rueckwaerts gesprungen"))
        assertFalse(t.contains("erneut druecken")) { "dieser Rat waere wirkungslos" }
    }

    /** Der Widerruf ist keine beendete Episode - und der Schirm muss beides
     *  auseinanderhalten. */
    @Test
    fun `ein widerrufener Kredit meldet sich bei laufender Episode`() {
        val t = FuseScreenModel.render(outcome(episodeId = now - 30 * 60_000L, revoked = true), null, now)
        assertTrue(t.contains("WIDERRUFEN"))
        assertTrue(t.contains("laufen weiter")) { "Episode und Bezahlung bleiben" }
    }

    /** Ohne Marker und ohne Episode bleibt der Schirm still - eine Zeile
     *  "alles in Ordnung" waere Geruest. */
    @Test
    fun `ohne Marker steht keine Evidenzzeile`() {
        val t = FuseScreenModel.render(outcome(), null, now)
        assertFalse(t.contains("Evidenz-"))
    }
}
