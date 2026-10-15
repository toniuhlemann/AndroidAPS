package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Das Konto mit frueher Versorgung (E-034 v2): Buchung, Anfangsportion, weitere Portionen. Synthetische Werte. */
class ExpectationAccountEarlyTest {

    private val t0 = 1_700_000_000_000L
    private val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) }

    /** Synthetisch: gleichmaessig 1 U je 30 min bis 120 min. */
    private val werte = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)).values()
    private val params = requireNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3))
    private val einstellung = requireNotNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.0, 2.0, 10))
    private val alle = EarlySupply.Switches(true, true, true, true)

    private fun konto(stabil: Boolean = true) = ExpectationAccount.armEarly(
        authId = t0, windowEndTs = t0 + 120 * 60_000L, params = params, settings = einstellung, switches = alle,
        startStable = stabil, nowTs = t0, bgMgdl = 100.0, isfMgdlPerU = 50.0, unitAction = wirkung,
    )

    private fun eingaben(iobU: Double = 0.0) = ExpectationAccount.EarlyInputs(
        iobU = iobU, freeIobU = iobU, existingActionU = { t -> iobU * wirkung(t) }, unitAction = wirkung,
    )

    private fun schritt(
        s: ExpectationAccount.State, min: Int, bg: Double = 100.0, iobU: Double = 0.0, pause: Boolean = false,
        early: ExpectationAccount.EarlyInputs? = eingaben(iobU),
    ) = ExpectationAccount.step(
        s,
        ExpectationAccount.Inputs(
            nowTs = t0 + min * 60_000L, ageMin = min.toDouble(), bgMgdl = bg, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            iobTotalU = iobU, activityUPerMin = 0.0, paused = pause, stableNow = true, early = early,
        ),
    )

    @Test
    fun `am Marker wird das Gesamtbudget gebucht, nicht die Huelle - ohne v1-Ausnahme`() {
        val s = konto()
        val kurve = requireNotNull(ExpectationAccount.Curve.of(werte))
        assertEquals(EarlySupply.budgetU(kurve, 120, 1.0, 8.0, wirkung), s.a0U, 1e-12)
        assertEquals(1.0, s.phi0, 0.0)
        assertNotNull(s.early)
        // Nach Tief: der eigene Skalierer.
        assertEquals(EarlySupply.budgetU(kurve, 120, 0.5, 8.0, wirkung), konto(stabil = false).a0U, 1e-12)
    }

    @Test
    fun `die Anfangsportion ist hoechstens alpha mal B und hoechstens die Anforderung`() {
        val (s, e) = schritt(konto(), 0)
        val f = requireNotNull(e.early)
        assertTrue(f.initial)
        assertEquals(0.5 * s.a0U, e.restEffU, 1e-12)
        assertEquals(minOf(f.demandU, 0.5 * s.a0U), ExpectationAccount.deliverableU(e), 1e-12)
        // Vorhandenes freies Insulin senkt die Anforderung - und damit die Anfangsportion.
        val (_, mitIob) = schritt(konto(), 0, iobU = 3.0)
        assertTrue(mitIob.early!!.demandU < f.demandU)
    }

    @Test
    fun `nach der Anfangsportion gelten maximale Portion und Menge je Zeitraum - ohne Ansparen`() {
        var (s, e) = schritt(konto(), 0)
        s = ExpectationAccount.book(s, e, e.restEffU).first
        assertTrue(s.initialDone)
        // Zwei weitere Portionen zu je 1,0 U fuellen den Zeitraum (2,0 U je 10 min).
        for (m in 1..2) {
            val (s2, e2) = schritt(s, m)
            assertFalse(e2.early!!.initial)
            assertEquals(1.0, e2.restEffU, 1e-12)
            s = ExpectationAccount.book(s2, e2, 1.0).first
        }
        val (s3, e3) = schritt(s, 3)
        assertEquals(0.0, e3.restEffU, 0.0)
        assertEquals(0.0, e3.early!!.periodLeftU, 0.0)
        // Lange Pause: danach wieder hoechstens EINE Portion, nichts aus den verpassten Zyklen.
        var t = s3
        for (m in 4..9) t = schritt(t, m, pause = true).first
        val (_, e4) = schritt(t, 11)
        assertEquals(1.0, e4.restEffU, 1e-12)
        val (_, e5) = schritt(t, 12)
        assertTrue(e5.restEffU <= 1.0 + 1e-12)
    }

    @Test
    fun `die Anfangsportion verfaellt nach ihrem Fenster, auch ungeliefert`() {
        var s = konto()
        for (m in 0 until EarlySupply.INITIAL_WINDOW_MIN.toInt()) s = schritt(s, m, pause = true).first
        val (danach, e) = schritt(s, EarlySupply.INITIAL_WINDOW_MIN.toInt())
        assertTrue(danach.initialDone)
        assertFalse(e.early!!.initial)
        assertTrue(e.restEffU <= 1.0 + 1e-12)
    }

    @Test
    fun `Budget wird nie ueberschritten, und d_mess zaehlt hoechstens, was der Normalpfad freigab`() {
        var s = konto()
        var summe = 0.0
        for (m in 0..119) {
            val (s2, e) = schritt(s, m, bg = 160.0)
            // Der Normalpfad gab vom gemessenen Anteil nur die Haelfte frei.
            val gemessen = 0.5 * ExpectationAccount.measuredShareU(e)
            val (s3, split) = ExpectationAccount.book(s2, e, gemessen + ExpectationAccount.expectationShareU(e), gemessen)
            assertEquals(0.0, split.violationU, 0.0)
            assertTrue(split.dMessU <= gemessen + 1e-12)
            summe += split.dErwU
            s = s3
        }
        assertTrue(summe <= s.a0U + 1e-9)
        assertEquals(summe, s.bookedU, 1e-9)
    }

    /** Schritt 2 (Toni): die beobachtete Fortsetzung der laufenden Mahlzeit - nur fuer weitere Portionen. */
    @Test
    fun `die Fortsetzung zaehlt nur fuer weitere Portionen, nie nach einem Tief und nie nach dem Fenster`() {
        val f = EarlySupply.Continuation(0.05, 60.0)
        fun mit(s: ExpectationAccount.State, min: Int, bg: Double) = ExpectationAccount.step(
            s,
            ExpectationAccount.Inputs(
                nowTs = t0 + min * 60_000L, ageMin = min.toDouble(), bgMgdl = bg, targetMgdl = 100.0, isfMgdlPerU = 50.0,
                iobTotalU = 0.0, activityUPerMin = 0.0, paused = false, stableNow = true, early = eingaben(),
                continuation = f,
            ),
        )
        // In der Anfangsportion zaehlt sie nicht.
        val (_, anfang) = mit(konto(), 0, 100.0)
        assertTrue(anfang.early!!.initial)
        assertEquals(0.0, anfang.early!!.continuationUPerMin, 0.0)
        assertEquals(schritt(konto(), 0).second.early!!.demandU, anfang.early!!.demandU, 0.0)
        // Die Mahlzeit laeuft (BG steigt), nach Minute 90: ohne Fortsetzung keine Anforderung, mit ihr schon.
        var s = konto()
        for (m in 0..99) s = schritt(s, m, bg = 100.0 + m).first
        assertFalse(s.frozen)
        val (_, ohne) = schritt(s, 100, bg = 200.0)
        assertEquals(0.0, ohne.early!!.demandU, 0.0)
        assertEquals(0.0, ExpectationAccount.expectationShareU(ohne), 0.0)
        val (_, laeuft) = mit(s, 100, 200.0)
        assertTrue(laeuft.early!!.demandU > 0.0) { "keine Anforderung trotz laufender Mahlzeit" }
        assertEquals(0.05, laeuft.early!!.continuationUPerMin, 0.0)
        assertTrue(ExpectationAccount.expectationShareU(laeuft) > 0.0)
        // Nach einem gemessenen Tief in dieser Mahlzeit nicht.
        val (_, nachTief) = mit(s.copy(earlyAfterLow = true), 100, 200.0)
        assertEquals(0.0, nachTief.early!!.demandU, 0.0)
        assertEquals(0.0, nachTief.early!!.continuationUPerMin, 0.0)
        // Nach dem Fenster der Vollmacht nicht.
        var t = s
        for (m in 100..119) t = schritt(t, m, bg = 200.0).first
        val (_, danach) = mit(t, 120, 200.0)
        assertTrue(danach.expired)
        assertEquals(0.0, danach.early!!.demandU, 0.0)
        assertEquals(0.0, danach.restEffU, 0.0)
    }

    /** Toni: die Stabilitaetsdauer darf 0 sein - dann gilt die Freigabe im ersten stabilen Zyklus. */
    @Test
    fun `Stabilitaetsdauer 0 gibt im ersten stabilen Zyklus frei`() {
        assertNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 0, 0.3))
        assertTrue(ExpectationAccount.Stability.stable(t0, t0, 0))
        assertFalse(ExpectationAccount.Stability.stable(null, t0, 0))
        assertFalse(ExpectationAccount.Stability.stable(t0, t0, 1))
    }

    @Test
    fun `ohne Insulinmodell gibt es keinen Erwartungsanteil`() {
        val (_, e) = schritt(konto(), 0, early = null)
        assertEquals(0.0, e.restEffU, 0.0)
        assertEquals("NO_MODEL", e.status)
    }

    @Test
    fun `bleibt die Mahlzeit aus, friert die Ruecknahme bei +30 die fruehe Versorgung ein`() {
        var s = konto()
        for (m in 0..30) s = schritt(s, m).first
        assertTrue(s.frozen)
        val (_, e) = schritt(s, 31)
        assertEquals(0.0, e.restEffU, 0.0)
        assertEquals("FROZEN", e.status)
    }

    @Test
    fun `G3 und G4 bekommen vor +20 keine Anrechnung, danach die bestaetigte`() {
        val (_, frueh) = schritt(konto(), 5)
        assertEquals(0.0, frueh.early!!.guardScale, 0.0)
        // BG steigt: die Beobachtung bestaetigt die Mahlzeit.
        var s = konto()
        for (m in 0..25) s = schritt(s, m, bg = 100.0 + 4.0 * m).first
        val (_, spaet) = schritt(s, 26, bg = 204.0)
        assertTrue(spaet.early!!.guardScale > 0.0)
        assertTrue(spaet.early!!.guardScale <= 1.0)
    }

    @Test
    fun `die Fallgrenze der Strecke wird geprueft und am Marker festgehalten`() {
        assertEquals(null, ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3, stabilityFallMgdlPerMin = 10.5))
        assertEquals(null, ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3, stabilityFallMgdlPerMin = -0.5))
        // Toni: ein deutlicher Abfall muss mindestens bis 8 (1-Minuten-Delta) einstellbar sein.
        assertEquals(8.0, requireNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3, stabilityFallMgdlPerMin = 8.0)).stabilityFallMgdlPerMin, 0.0)
        val p = requireNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3, stabilityFallMgdlPerMin = 1.0))
        val s = ExpectationAccount.armEarly(t0, t0 + 120 * 60_000L, p, einstellung, alle, true, t0, 100.0, 50.0, wirkung)
        assertEquals(1.0, s.stabilityFallMgdlPerMin, 0.0)
        // Standard = die bisherige Kanalkonstante, als Rueckgang.
        assertEquals(-LivenessChannel.UKF_FLOOR_MGDL_PER_MIN, konto().stabilityFallMgdlPerMin, 0.0)
    }

    private fun ts(min: Int) = t0 + min * 60_000L

    /** Ein Zyklus mit den Groessen der Tief-Regel: gemessenes Tief, Neustart, Strecke, Bestaetigung nach Tief. */
    private fun zyklus(
        s: ExpectationAccount.State, min: Int, bg: Double = 120.0, tief: Boolean = false, neustart: Boolean = false,
        stabil: Boolean = true, bestaetigt: Long = 0L, early: ExpectationAccount.EarlyInputs? = eingaben(),
    ) = ExpectationAccount.step(
        s,
        ExpectationAccount.Inputs(
            nowTs = ts(min), ageMin = min.toDouble(), bgMgdl = bg, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            iobTotalU = early?.iobU ?: 0.0, activityUPerMin = 0.0, paused = false, stableNow = stabil,
            measuredLow = tief || neustart, lowLocksEarlySupply = tief, confirmedAfterLowTs = bestaetigt, early = early,
        ),
    )

    @Test
    fun `ein gemessenes Tief nach der Freigabe sperrt die fruehe Versorgung, ein Neustart nicht`() {
        var s = schritt(konto(), 0).first
        assertTrue(s.released)
        // Neustart: Freigabe weg, Versorgung bleibt - die naechste Strecke gibt sie zurueck.
        val nachNeustart = zyklus(s, 5, neustart = true).first
        assertFalse(nachNeustart.earlyLowLocked)
        val (wieder, eW) = schritt(nachNeustart, 6)
        assertTrue(wieder.released)
        assertTrue(eW.restEffU > 0.0)
        // Gemessenes Tief: gesperrt - auch nach neuer Strecke und steigendem Zucker (Rescue) kein Erwartungsanteil.
        s = zyklus(s, 5, tief = true).first
        assertTrue(s.earlyLowLocked && s.earlyAfterLow && s.initialDone)
        assertEquals(ts(5), s.earlyLowLockSinceTs)
        for (m in 6..40) {
            val (s2, e) = schritt(s, m, bg = 100.0 + 3.0 * (m - 5))
            assertEquals(0.0, e.restEffU, 0.0, "Minute $m")
            assertEquals(0.0, e.early!!.guardScale, 0.0)
            assertEquals("LOCKED_BY_LOW", e.status)
            s = s2
        }
        assertTrue(s.released) { "Probe zeigt nichts: die Strecke gibt die Freigabe zurueck, nur die Sperre haelt" }
        // Konsolidierung 2.0: das Konto v1 (ohne die Regel) ist entfallen - auch ein Konto ohne Budget sperrt.
        val ohneBudget = ExpectationAccount.armWithoutExpectation(t0, t0 + 120 * 60_000L, true, t0, 100.0, 50.0)
        assertTrue(zyklus(schritt(ohneBudget, 0).first, 5, tief = true).first.earlyLowLocked)
    }

    /** Bank N2, W3: das Tief kam, waehrend die Freigabe noch auf die Strecke wartete - es sperrte bisher nichts. */
    @Test
    fun `ein Tief vor der Freigabe sperrt ebenso - kein Anstieg allein gibt sie frei`() {
        var s = zyklus(konto(stabil = false), 0, bg = 95.0, stabil = false).first
        assertFalse(s.released)
        s = zyklus(s, 5, bg = 60.0, tief = true, stabil = false).first
        assertTrue(s.earlyLowLocked && s.initialDone)
        for (m in 6..60) {
            val (s2, e) = zyklus(s, m, bg = 60.0 + 2.0 * (m - 5))
            assertEquals(0.0, e.restEffU, 0.0, "Minute $m")
            assertEquals("LOCKED_BY_LOW", e.status)
            s = s2
        }
        assertTrue(s.released) { "Probe zeigt nichts: ohne Sperre floesse der Rescue-Anstieg als Mahlzeit" }
    }

    @Test
    fun `die Bestaetigung hebt die Sperre auf - erst nach dem Tief, ohne neues Budget und ohne Anfangsportion`() {
        var s = zyklus(konto(stabil = false), 0, bg = 95.0, stabil = false).first
        val budget = s.a0U
        s = zyklus(s, 5, bg = 62.0, tief = true, stabil = false).first
        // Bestaetigt waehrend des Tiefs: gesperrt, solange es misst ...
        for (m in 6..8) {
            val (s2, e) = zyklus(s, m, bg = 60.0, tief = true, stabil = false, bestaetigt = ts(6))
            assertEquals(0.0, e.restEffU, 0.0, "Minute $m")
            assertTrue(s2.earlyLowLocked)
            s = s2
        }
        // ... und bis die Strecke erfuellt ist.
        s = zyklus(s, 9, bg = 95.0, stabil = false, bestaetigt = ts(6)).first
        assertTrue(s.earlyLowLocked)
        val (frei, e) = zyklus(s, 10, bg = 130.0, bestaetigt = ts(6))
        assertFalse(frei.earlyLowLocked)
        assertTrue(e.restEffU > 0.0)
        assertEquals("ACTIVE", e.status)
        assertFalse(e.early!!.initial)
        assertTrue(e.restEffU <= einstellung.maxPortionU + 1e-12)
        assertEquals(budget, frei.a0U, 0.0)
        // Nach einem Tief keine Anrechnung in Guard und Schwanz (G3/G4).
        assertEquals(0.0, e.early!!.guardScale, 0.0)
    }

    @Test
    fun `eine Bestaetigung vor dem Sperrbeginn zaehlt nicht - ein neues Tief sperrt erneut`() {
        var s = zyklus(konto(stabil = false), 0, bg = 95.0, stabil = false).first
        s = zyklus(s, 5, bg = 62.0, tief = true, stabil = false).first
        s = zyklus(s, 10, bestaetigt = ts(6)).first
        assertFalse(s.earlyLowLocked)
        s = zyklus(s, 20, bg = 60.0, tief = true, bestaetigt = ts(6)).first
        assertTrue(s.earlyLowLocked)
        assertEquals(ts(20), s.earlyLowLockSinceTs)
        // Die alte Bestaetigung liegt vor dem neuen Sperrbeginn.
        for (m in 21..30) s = zyklus(s, m, bestaetigt = ts(6)).first.also { assertTrue(it.earlyLowLocked, "Minute $m") }
        assertFalse(zyklus(s, 31, bestaetigt = ts(30)).first.earlyLowLocked)
    }

    @Test
    fun `nach einem Tief rechnet die Anforderung alles vorhandene Insulin an`() {
        val mitIob = ExpectationAccount.EarlyInputs(
            iobU = 2.0, freeIobU = 0.5, existingActionU = { t -> 2.0 * wirkung(t) }, unitAction = wirkung,
        )
        var s = zyklus(konto(stabil = false), 0, bg = 95.0, stabil = false).first
        s = zyklus(s, 5, bg = 62.0, tief = true, stabil = false).first
        val (_, e) = zyklus(s, 10, bestaetigt = ts(6), early = mitIob)
        val kurve = requireNotNull(ExpectationAccount.Curve.of(werte))
        val alles = EarlySupply.demandU(kurve, 120, 10.0, e.early!!.scaleEff, 2.0, 2.0, mitIob.existingActionU, wirkung)
        val nurFrei = EarlySupply.demandU(kurve, 120, 10.0, e.early!!.scaleEff, 2.0, 0.5, mitIob.existingActionU, wirkung)
        assertEquals(alles, e.early!!.demandU, 1e-12)
        assertTrue(alles < nurFrei) { "Probe zeigt nichts: alles = $alles, nur frei = $nurFrei" }
    }

    /** Toni: Freigabe und Skalierer sind zwei Fragen - ein Fallen bei hohem Zucker ist kein Start nach Tief. */
    @Test
    fun `Freigabe und Skalierer sind getrennt - nicht stabil heisst nicht nach Tief`() {
        val kurve = requireNotNull(ExpectationAccount.Curve.of(werte))
        fun arm(stabil: Boolean, nachTief: Boolean?) = if (nachTief == null) ExpectationAccount.armEarly(
            t0, t0 + 120 * 60_000L, params, einstellung, alle, stabil, t0, 100.0, 50.0, wirkung,
        ) else ExpectationAccount.armEarly(
            t0, t0 + 120 * 60_000L, params, einstellung, alle, stabil, t0, 100.0, 50.0, wirkung, afterLow = nachTief,
        )
        // Nicht freigegeben (z. B. deutliches Fallen), aber nicht nach Tief: stabiler Skalierer, Freigabe wartet.
        val fallend = arm(stabil = false, nachTief = false)
        assertFalse(fallend.released)
        assertEquals(einstellung.scaleStable, fallend.phi0, 0.0)
        assertEquals(EarlySupply.budgetU(kurve, 120, einstellung.scaleStable, 8.0, wirkung), fallend.a0U, 1e-12)
        // Unter der Grenze: Skalierer nach Tief.
        val tief = arm(stabil = false, nachTief = true)
        assertEquals(einstellung.scaleAfterLow, tief.phi0, 0.0)
        // Ohne Angabe wie bisher: nicht stabil = nach Tief.
        assertEquals(einstellung.scaleAfterLow, arm(stabil = false, nachTief = null).phi0, 0.0)
        assertEquals(einstellung.scaleStable, arm(stabil = true, nachTief = null).phi0, 0.0)
    }

    // Konsolidierung 2.0: `die v1-Ausnahme gilt in v2 nie` ist mit der v1-Ausnahme entfallen.

    // ---- KI-221: der Storno nimmt auch Portion und Anfangsportion zurueck ----------------------------------------

    /** Die Anfangsportion ging nachweislich nie hinaus: sie war nie gegeben und ist wieder offen. */
    @Test
    fun `eine ganz nie gesendete Anfangsportion ist wieder offen`() {
        val (s0, e0) = schritt(konto(), 0)
        val (gebucht, split) = ExpectationAccount.book(s0, e0, e0.restEffU)
        assertTrue(gebucht.initialDone)
        val storno = ExpectationAccount.cancel(
            gebucht, erwU = split.dErwU, bookedAtTs = e0.atTs, initialNeverSent = true,
        )
        assertFalse(storno.initialDone)
        assertEquals(0.0, storno.bookedU, 1e-12)
        val (_, e1) = schritt(storno, 1)
        assertTrue(e1.early!!.initial)
        assertEquals(e0.restEffU, e1.restEffU, 1e-12)
        // Teilweise gesendet (ohne die Angabe): gegeben - einmalig, wie bei einer kleineren Anforderung.
        assertTrue(ExpectationAccount.cancel(gebucht, erwU = split.dErwU / 2, bookedAtTs = e0.atTs).initialDone)
    }

    /** Das Tief hat die Marke gesetzt; ein spaeter Beweis darf die Anfangsportion danach nie wieder oeffnen (Hypo). */
    @Test
    fun `nach einem gemessenen Tief bleibt die Anfangsportion zu, auch beim Storno`() {
        val (s0, e0) = schritt(konto(), 0)
        val gebucht = ExpectationAccount.book(s0, e0, e0.restEffU).first
        val nachTief = zyklus(gebucht, 3, tief = true).first
        assertTrue(nachTief.earlyAfterLow && nachTief.initialDone)
        val storno = ExpectationAccount.cancel(
            nachTief, erwU = e0.restEffU, bookedAtTs = e0.atTs, initialNeverSent = true,
        )
        assertTrue(storno.initialDone)
        val (_, e) = schritt(storno, 4)
        assertFalse(e.early!!.initial)
        assertEquals(0.0, e.restEffU, 0.0)
        // Auch die Bestaetigung nach dem Tief bringt keine Anfangsportion zurueck.
        val bestaetigt = zyklus(storno, 30, bestaetigt = ts(25)).first
        assertTrue(bestaetigt.initialDone)
    }

    /** Ein Beweis nach dem Ende des Anfangsfensters: es bleibt bei Portionen. */
    @Test
    fun `nach dem Anfangsfenster bleibt es bei Portionen, auch nach dem Storno`() {
        val letzte = EarlySupply.INITIAL_WINDOW_MIN.toInt() - 1
        var s = konto()
        for (m in 0 until letzte) s = schritt(s, m, pause = true).first
        val (sL, eL) = schritt(s, letzte)
        assertTrue(eL.early!!.initial)
        val gebucht = ExpectationAccount.book(sL, eL, eL.restEffU).first
        val storno = ExpectationAccount.cancel(
            gebucht, erwU = eL.restEffU, bookedAtTs = eL.atTs, initialNeverSent = true,
        )
        val (danach, e) = schritt(storno, EarlySupply.INITIAL_WINDOW_MIN.toInt())
        assertTrue(danach.initialDone)
        assertFalse(e.early!!.initial)
        assertTrue(e.restEffU <= 1.0 + 1e-12)
    }

    /** Der Zeitraumrest kommt aus dem tatsaechlich Gelieferten - eine nie gesendete Portion belegt ihn nicht. */
    @Test
    fun `eine nie gesendete Portion verlaesst das Zeitraumbuch`() {
        val (s0, e0) = schritt(konto(), 0)
        val s = ExpectationAccount.book(s0, e0, e0.restEffU).first
        val (s1, e1) = schritt(s, 1)
        val gebucht = ExpectationAccount.book(s1, e1, 1.0).first
        assertEquals(1.0, gebucht.portions.sumOf { it.amountU }, 1e-12)
        // Ganz nie gesendet: die Portion ist weg, der Zeitraum wieder voll offen; die Anfangsportion bleibt gegeben.
        val storno = ExpectationAccount.cancel(gebucht, erwU = 1.0, bookedAtTs = e1.atTs)
        assertTrue(storno.portions.isEmpty())
        assertTrue(storno.initialDone)
        assertEquals(2.0, schritt(storno, 2).second.early!!.periodLeftU, 1e-12)
        // Teilweise gesendet: nur der nie gesendete Teil geht.
        val teil = ExpectationAccount.cancel(gebucht, erwU = 0.4, bookedAtTs = e1.atTs)
        assertEquals(0.6, teil.portions.single().amountU, 1e-12)
        assertEquals(1.4, schritt(teil, 2).second.early!!.periodLeftU, 1e-12)
        // Ohne Buchungszeit bleibt das Buch wie bisher.
        assertEquals(1.0, ExpectationAccount.cancel(gebucht, erwU = 1.0).portions.sumOf { it.amountU }, 1e-12)
    }
}
