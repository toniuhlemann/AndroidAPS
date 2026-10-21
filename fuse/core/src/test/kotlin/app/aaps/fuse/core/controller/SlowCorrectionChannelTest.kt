package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.SlowCorrectionChannel.Input
import app.aaps.fuse.core.controller.SlowCorrectionChannel.Memory
import app.aaps.fuse.core.controller.SlowCorrectionChannel.Reason
import app.aaps.fuse.core.controller.SlowCorrectionChannel.Settings
import app.aaps.fuse.core.controller.SlowCorrectionChannel.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Toni 20.10.2026: der langsame Korrekturkanal - Einstieg und Stopp nur ueber Messung, maxIOB immer. */
class SlowCorrectionChannelTest {

    // Die Faelle mit Wartezeit setzen sie ausdruecklich: ab Werk startet der Kanal seit 21.10. sofort (0 min).
    private val an = Settings(enabled = true, entryMin = 30)
    private val sofort = Settings(enabled = true, entryMin = 0)
    private val minute = 60_000L
    private val start = 1_000_000_000_000L

    private fun input(
        min: Int,
        q1: Double? = 220.0,
        q1Vorher: Double? = 215.0,
        trend: Double? = 0.2,
        iob: Double = 2.0,
        maxIob: Double = 8.0,
        basal: Double = 0.5,
        hardStop: String? = null,
        smb: Double = 0.0,
        aktiv: Boolean = false,
        teilbasal: Boolean = false,
        laeuft: Double? = null,
        rest: Int? = null,
        // Ein grosser Bedarf: die Stufe setzt dann allein die Obergrenze (bzw. maxIOB).
        bedarf: Double? = 5.0,
        geplant: Double = 0.0,
    ) = Input(
        nowTs = start + min * minute, q1Mgdl = q1, q1EntryAgoMgdl = q1Vorher, trendMgdlPerMin = trend,
        iobU = iob, maxIobU = maxIob, scheduledBasalUPerH = basal, basalStepUPerH = 0.05,
        hardStop = hardStop, smbThisCycleU = smb, active = aktiv, otherOwnBasalActive = teilbasal,
        runningRateUPerH = laeuft, runningRemainingMin = rest, demandU = bedarf, plannedRemainderU = geplant,
    )

    /** Spielt [bis] Minuten durch und liefert den letzten Schritt. */
    private fun lauf(bis: Int, settings: Settings = an, eingabe: (Int) -> Input = { input(it) }): Step {
        var mem = Memory()
        var letzter: Step? = null
        for (m in 0..bis) {
            letzter = SlowCorrectionChannel.step(settings, eingabe(m), mem)
            mem = letzter.memory
        }
        return letzter!!
    }

    @Test fun `ausgeschaltet tut der Kanal nie etwas`() {
        val s = lauf(120, Settings(enabled = false))
        assertFalse(s.want)
        assertEquals(Reason.OFF, s.reason)
    }

    @Test fun `eingestiegen wird erst nach der Einstiegszeit ueber der Schwelle`() {
        assertEquals(Reason.WAIT_ENTRY, lauf(29).reason)
        val s = lauf(30)
        assertTrue(s.want)
        assertEquals(Reason.START, s.reason)
        // Faktor 2 auf 0,5 U/h Profilbasal: insgesamt 1,0 U/h.
        assertEquals(1.0, s.rateUPerH, 1e-9)
        assertEquals(0.5, s.extraUPerH(0.5), 1e-9)
    }

    @Test fun `mit Einstiegszeit 0 startet der Kanal im ersten Zyklus ueber der Schwelle`() {
        // Toni 21.10.: nicht erst lange warten - ueber der Schwelle, nicht fallend, mit Bedarf: sofort.
        val s = SlowCorrectionChannel.step(sofort, input(0, q1Vorher = null), Memory())
        assertTrue(s.want)
        assertEquals(Reason.START, s.reason)
        assertEquals(1.0, s.rateUPerH, 1e-9)
        // Faellt der Trend gerade deutlich, wartet er auch ohne Einstiegszeit.
        assertEquals(Reason.WAIT_ENTRY, SlowCorrectionChannel.step(sofort, input(0, trend = -0.7), Memory()).reason)
        // Unter der Schwelle (aber ueber dem Stopp) startet nichts.
        assertEquals(Reason.WAIT_ENTRY, SlowCorrectionChannel.step(sofort, input(0, q1 = 175.0), Memory()).reason)
        // Ohne Bedarf auch nicht.
        assertEquals(Reason.NO_DEMAND, SlowCorrectionChannel.step(sofort, input(0, bedarf = 0.0), Memory()).reason)
    }

    @Test fun `Schwelle ab 100 und Einstiegszeit ab 0 sind gueltig, darunter nicht`() {
        assertNull(Settings(enabled = true, thresholdMgdl = 100.0).violation())
        assertNotNull(Settings(enabled = true, thresholdMgdl = 99.0).violation())
        assertNull(Settings(enabled = true, entryMin = 0).violation())
        assertNotNull(Settings(enabled = true, entryMin = -1).violation())
        assertNull(Settings(enabled = true, entryMin = 90).violation())
        assertNotNull(Settings(enabled = true, entryMin = 91).violation())
        assertEquals(0, Settings(enabled = true).entryMin)
        // Schwelle 100: gestoppt wird 20 darunter.
        val tief = Settings(enabled = true, thresholdMgdl = 100.0, entryMin = 0)
        assertEquals(Reason.START, SlowCorrectionChannel.step(tief, input(0, q1 = 120.0), Memory()).reason)
        assertEquals(Reason.STOP_BELOW, SlowCorrectionChannel.step(tief, input(0, q1 = 79.0), Memory()).reason)
    }

    @Test fun `die Stufen sind fein - kleiner Bedarf gibt eine kleine TBR, der Faktor ist nur der Deckel`() {
        assertEquals(4, SlowCorrectionChannel.stagesFor(2.0))
        assertEquals(12, SlowCorrectionChannel.stagesFor(4.0))
        assertEquals(16, SlowCorrectionChannel.stagesFor(5.0))
        assertEquals(6, SlowCorrectionChannel.stagesFor(2.3))
        assertEquals(1, SlowCorrectionChannel.stagesFor(1.2))
        // Faktor 4 auf 0,8 U/h: Stufen zu 0,2 U/h bis 3,2 U/h. Bis 21.10. war die erste Stufe schon 0,6 U/h Zusatz -
        // ein Bedarf von 0,1 U loeste nichts aus.
        val vier = Settings(enabled = true, factor = 4.0, entryMin = 0)
        fun bei(bedarf: Double) = SlowCorrectionChannel.step(vier, input(0, basal = 0.8, bedarf = bedarf), Memory())
        assertEquals(1.0, bei(0.1).rateUPerH, 1e-9)      // 0,2 U/h Zusatz -> Stufe 1
        assertEquals(1, bei(0.1).stage)
        assertEquals("1/12 bedarf", bei(0.1).detail)
        assertEquals(1.8, bei(0.5).rateUPerH, 1e-9)      // 1,0 U/h -> Stufe 5
        assertEquals(3.2, bei(5.0).rateUPerH, 1e-9)      // Deckel: 4 x 0,8
        assertEquals(Reason.NO_DEMAND, bei(0.04).reason) // 0,08 U/h traegt keine Stufe
        // Faktor 1,2: eine einzige Stufe, kleiner als 0,25 x Profilbasal.
        val klein = Settings(enabled = true, factor = 1.2, entryMin = 0)
        assertEquals(0.95, SlowCorrectionChannel.step(klein, input(0, basal = 0.8), Memory()).rateUPerH, 1e-9)
    }

    @Test fun `ein gefallener BG ueber die Einstiegszeit startet nichts`() {
        // 30 min vorher 240, jetzt 220: mehr als 5 mg/dl gefallen.
        assertEquals(Reason.WAIT_ENTRY, lauf(40) { input(it, q1Vorher = 240.0) }.reason)
        // Ohne bekannten Wert von damals auch nicht.
        assertEquals(Reason.WAIT_ENTRY, lauf(40) { input(it, q1Vorher = null) }.reason)
        // Und nicht, solange der Trend gerade deutlich faellt.
        assertEquals(Reason.WAIT_ENTRY, lauf(40) { input(it, trend = -0.7) }.reason)
    }

    @Test fun `unter der Schwelle beginnt die Einstiegszeit neu`() {
        val s = lauf(45) { input(it, q1 = if (it == 20) 175.0 else 220.0) }
        // Ab Minute 21 wieder hoch - erst 24 Minuten, also noch kein Einstieg.
        assertEquals(Reason.STOP_BELOW, lauf(20) { input(it, q1 = if (it == 20) 155.0 else 220.0) }.reason)
        assertEquals(Reason.WAIT_ENTRY, s.reason)
        assertEquals(24.0, s.highForMin, 1e-9)
    }

    @Test fun `eine Zyklusluecke laesst die Hochphase neu beginnen`() {
        var mem = Memory()
        for (m in 0..25) mem = SlowCorrectionChannel.step(an, input(m), mem).memory
        // 10 min ohne Zyklus, dann wieder: die Zeit vor der Luecke zaehlt nicht.
        val nachLuecke = SlowCorrectionChannel.step(an, input(36), mem)
        assertEquals(Reason.WAIT_ENTRY, nachLuecke.reason)
        assertEquals(0.0, nachLuecke.highForMin, 1e-9)
    }

    @Test fun `ein laufender Kanal stoppt bei Fall ueber zwei Zyklen, nicht bei einem Wert`() {
        var mem = lauf(30).memory
        val einer = SlowCorrectionChannel.step(an, input(31, trend = -1.5, aktiv = true), mem)
        assertTrue(einer.want)
        mem = einer.memory
        val zwei = SlowCorrectionChannel.step(an, input(32, trend = -1.5, aktiv = true), mem)
        assertFalse(zwei.want)
        assertEquals(Reason.STOP_FALLING, zwei.reason)
    }

    @Test fun `ein laufender Kanal stoppt unter Schwelle minus 20 und laeuft darueber weiter`() {
        val mem = lauf(30).memory
        assertTrue(SlowCorrectionChannel.step(an, input(31, q1 = 165.0, aktiv = true), mem).want)
        val unten = SlowCorrectionChannel.step(an, input(31, q1 = 159.0, aktiv = true), mem)
        assertFalse(unten.want)
        assertEquals(Reason.STOP_BELOW, unten.reason)
    }

    @Test fun `harte Sperren beenden den Kanal und benennen den Grund`() {
        val mem = lauf(30).memory
        val s = SlowCorrectionChannel.step(an, input(31, hardStop = "MEASURED_LOW", aktiv = true), mem)
        assertFalse(s.want)
        assertEquals(Reason.HARD_STOP, s.reason)
        assertEquals("MEASURED_LOW", s.detail)
    }

    @Test fun `ein SMB stoppt den Kanal nicht - beide laufen nebeneinander`() {
        // Einstieg im selben Zyklus wie ein SMB: der Block kommt trotzdem.
        val mitSmb = lauf(30) { input(it, smb = if (it == 30) 0.3 else 0.0) }
        assertTrue(mitSmb.want)
        assertEquals(Reason.START, mitSmb.reason)
        assertEquals(1.0, mitSmb.rateUPerH, 1e-9)
        // Ein laufender Block bleibt neben einem SMB stehen, solange beides in maxIOB passt.
        val mem = lauf(30).memory
        val laeuft = SlowCorrectionChannel.step(an, input(31, smb = 0.5, aktiv = true, laeuft = 1.0, rest = 20), mem)
        assertTrue(laeuft.want)
        assertEquals(1.0, laeuft.rateUPerH, 1e-9)
        assertEquals("block", laeuft.detail)
    }

    @Test fun `an der maxIOB-Grenze weicht der Kanal dem SMB`() {
        val mem = lauf(30).memory
        // IOB 7,6 + SMB 0,3 + Rest 0,167 > 8,0: der laufende Block passt nicht mehr daneben. Fuer einen frischen Block
        // bleiben 0,1 U - das traegt nur noch die Stufe 1/4 (0,6 U/h, 0,05 U je Block).
        val enger = SlowCorrectionChannel.step(an, input(40, iob = 7.6, smb = 0.3, aktiv = true, laeuft = 1.0, rest = 20), mem)
        assertTrue(enger.want)
        assertEquals(0.6, enger.rateUPerH, 1e-9)
        assertEquals("1/4 maxIob", enger.detail)
        // Fuellt der SMB den Spielraum ganz, endet der Kanal.
        val voll = SlowCorrectionChannel.step(an, input(40, iob = 7.5, smb = 0.5, aktiv = true, laeuft = 1.0, rest = 20), mem)
        assertFalse(voll.want)
        assertEquals(Reason.MAX_IOB, voll.reason)
        // Ein neuer Block wird nach dem SMB bemessen: IOB 7,6 + SMB 0,2 laesst 0,2 U - Stufe 3/4 (0,175 U je Block).
        val neu = lauf(30) { input(it, iob = 7.6, smb = if (it == 30) 0.2 else 0.0) }
        assertEquals(0.85, neu.rateUPerH, 1e-9)
        assertEquals("3/4 maxIob", neu.detail)
    }

    @Test fun `die Rate richtet sich nach dem Bedarf - der Faktor ist nur die Obergrenze`() {
        // Faktor 5 auf 0,5 U/h: Zusatz bis 2,0 U/h in 16 Stufen zu 0,125 U/h (auf das 0,05er Raster abgerundet). Ein
        // frischer Block traegt 2 x Restbedarf je Stunde (wie autoISF: Basal + 2 x insulinReq).
        val fuenf = Settings(enabled = true, factor = 5.0)
        fun bei(bedarf: Double?, smb: Double = 0.0) = lauf(30, fuenf) { input(it, bedarf = bedarf, smb = if (it == 30) smb else 0.0) }
        assertEquals(1.25, bei(0.4).rateUPerH, 1e-9)         // 0,8 U/h Zusatz -> Stufe 6 (0,75 U/h)
        assertEquals(6, bei(0.4).stage)
        assertEquals(1.6, bei(0.6).rateUPerH, 1e-9)          // 1,2 U/h -> Stufe 9 (1,625 -> 1,60)
        assertEquals(2.5, bei(2.0).rateUPerH, 1e-9)          // 4,0 U/h -> Obergrenze
        assertEquals("16/16 bedarf", bei(2.0).detail)
        // Der SMB dieses Zyklus deckt einen Teil: Restbedarf 0,3 U -> 0,6 U/h -> Stufe 5 (1,125 -> 1,10).
        assertEquals(1.1, bei(0.6, smb = 0.3).rateUPerH, 1e-9)
        // Auch ein kleiner Bedarf traegt eine kleine Stufe; erst unter der kleinsten kommt keine TBR.
        assertEquals(0.6, bei(0.1).rateUPerH, 1e-9)
        assertEquals(Reason.NO_DEMAND, bei(0.02).reason)
        assertEquals(Reason.NO_DEMAND, bei(-0.5).reason)
        // Ohne berechenbaren Bedarf startet nichts.
        assertEquals(Reason.NO_DEMAND_ESTIMATE, bei(null).reason)
    }

    @Test fun `ein laufender Block sinkt abgestuft mit dem Bedarf und steigt erst bei der Erneuerung`() {
        val fuenf = Settings(enabled = true, factor = 5.0)
        val mem = lauf(30, fuenf).memory
        fun laufend(rate: Double, bedarf: Double?, rest: Int = 20, geplant: Double = 0.0) =
            SlowCorrectionChannel.step(fuenf, input(40, aktiv = true, laeuft = rate, rest = rest, bedarf = bedarf, geplant = geplant), mem)
        // Stufe 16 (2,5 U/h), 20 min Rest: offen 0,667 U. Deckt der Plan den Bedarf genau, bleibt der Block.
        assertEquals("block", laufend(2.5, 0.0).detail)
        // Fehlen 0,2 U im Bedarf, traegt die Restzeit (mit einer Stufe Spiel) nur noch Stufe 12.
        val tiefer = laufend(2.5, -0.2)
        assertEquals(2.0, tiefer.rateUPerH, 1e-9)
        assertEquals("12/16 bedarf", tiefer.detail)
        // Fehlt fast alles, sinkt er auf eine kleine Stufe ...
        val klein = laufend(2.5, -0.6)
        assertEquals(0.75, klein.rateUPerH, 1e-9)
        assertEquals("2/16 bedarf", klein.detail)
        // ... und ist nichts mehr noetig, endet der Block (Profilbasal).
        assertEquals(Reason.NO_DEMAND, laufend(2.5, -0.7).reason)
        // Das Spiel beim Senken pinnt ein eigener Test (Verhaltensaenderung 21.10.).
        // Mehr Bedarf hebt mitten im Block NICHT an ...
        val mehr = laufend(1.5, 1.0)
        assertEquals(1.5, mehr.rateUPerH, 1e-9)
        assertEquals("block", mehr.detail)
        // ... erst die Erneuerung: frischer Block fuer Restbedarf plus geplanten Rest.
        val erneuert = laufend(1.5, 1.0, rest = 5, geplant = 1.0 * 5 / 60.0)
        assertEquals(2.5, erneuert.rateUPerH, 1e-9)
        // Ohne Bedarfsschaetzung laeuft ein Block aus und wird nicht erneuert.
        assertEquals("block ohne Bedarf", laufend(1.5, null).detail)
        assertEquals(Reason.NO_DEMAND_ESTIMATE, laufend(1.5, null, rest = 5).reason)
    }

    /**
     * EIGENE VERHALTENSAENDERUNG 21.10. (Toni: gesondert dokumentieren): ein laufender Block sinkt erst, wenn der
     * Bedarf ueber die Restzeit eine GANZE (feine) Stufe unter seiner Stufe liegt. Vorher war es eine halbe Stufe der
     * groben Teilung. Gilt nur fuer den Bedarf - an maxIOB sinkt er sofort.
     */
    @Test fun `Verhaltensaenderung 21_10 - beim Senken eine ganze Stufe Spiel`() {
        assertEquals(1.0, SlowCorrectionChannel.LOWER_SLACK_STAGES, 0.0)
        // Faktor 5 auf 0,5 U/h, Block auf Stufe 8 (1,5 U/h = 1,0 U/h Zusatz), 20 min Rest: offen 0,333 U.
        val fuenf = Settings(enabled = true, factor = 5.0)
        val mem = lauf(30, fuenf).memory
        fun laufend(bedarf: Double) =
            SlowCorrectionChannel.step(fuenf, input(40, aktiv = true, laeuft = 1.5, rest = 20, bedarf = bedarf), mem)
        // Fehlen 0,03 U: der Bedarf traegt 0,91 U/h, weniger als eine Stufe (0,125 U/h) unter der Deckung - der Block
        // bleibt. Mit dem alten Spiel (eine halbe Stufe, 0,0625 U/h) waere er hier schon gesunken.
        assertEquals("block", laufend(-0.03).detail)
        // Fehlen 0,05 U (0,85 U/h), liegt er mehr als eine Stufe darunter: eine Stufe tiefer.
        assertEquals("7/16 bedarf", laufend(-0.05).detail)
        // An maxIOB gibt es kein Spiel: passt der Rest nicht mehr, sinkt er trotz gedecktem Bedarf.
        val eng = SlowCorrectionChannel.step(fuenf, input(40, iob = 7.8, aktiv = true, laeuft = 1.5, rest = 20, bedarf = 5.0), mem)
        assertTrue(eng.rateUPerH < 1.5) { "maxIOB senkt sofort: ${eng.detail}" }
    }

    private fun block(rate: Double, abMin: Int) = SlowCorrectionChannel.Block(rate, start + abMin * minute, 30)

    private fun plan(vararg bloecke: SlowCorrectionChannel.Block, jetztMin: Int) =
        SlowCorrectionChannel.plannedEnvelope(bloecke.toList(), start + jetztMin * minute, 0.5)

    @Test fun `der geplante Rest steht als Einzelgaben in der Prognose`() {
        // 1,0 U/h auf 0,5 U/h, vor 10 min angefordert: 20 min Rest in 5-min-Gaben zu je 0,5 x 5/60 U.
        val gaben = plan(block(1.0, 0), jetztMin = 10)
        assertEquals(4, gaben.size)
        assertEquals(start + 10 * minute, gaben.first().first)
        assertEquals(0.5 * 20 / 60.0, gaben.sumOf { it.second }, 1e-9)
        // Noch nicht begonnen: ab der Anforderung, der ganze Block.
        assertEquals(0.25, plan(block(1.0, 0), jetztMin = -1).sumOf { it.second }, 1e-9)
        // Abgelaufen oder ohne Zusatz: nichts.
        assertTrue(plan(block(1.0, 0), jetztMin = 30).isEmpty())
        assertTrue(plan(block(0.5, 0), jetztMin = 0).isEmpty())
    }

    @Test fun `eine unbestaetigte Absenkung mindert den geplanten Rest nicht - nie beide zusammen`() {
        // Laeuft 1,0 U/h seit Minute 0 (Zusatz 0,5), in Minute 10 auf 0,75 U/h (Zusatz 0,25) abgesenkt angefordert,
        // jetzt Minute 12 und die Pumpe zeigt die Absenkung noch nicht. Je Abschnitt zaehlt der groessere Zusatz:
        // bis Minute 30 der alte, danach bis Minute 40 der neue.
        val alt = block(1.0, 0)
        val absenkung = block(0.75, 10)
        val huelle = plan(alt, absenkung, jetztMin = 12).sumOf { it.second }
        val altRest = 0.5 * 18 / 60.0
        val neuRest = 0.25 * 28 / 60.0
        assertEquals(0.5 * 18 / 60.0 + 0.25 * 10 / 60.0, huelle, 1e-9)
        assertTrue(huelle >= altRest - 1e-9) { "die alte, hoehere TBR kann weiterlaufen" }
        assertTrue(huelle < altRest + neuRest - 1e-9) { "nie beide Bloecke zusammen" }
        // Bestaetigt die Pumpe die Absenkung, fuehrt der Lebenszyklus nur noch den neuen Block - dann sinkt der Rest.
        assertEquals(neuRest, plan(absenkung, jetztMin = 12).sumOf { it.second }, 1e-9)
        // Eine unbestaetigte ANHEBUNG zaehlt dagegen sofort: je Abschnitt der hoehere neue Zusatz.
        val anhebung = block(1.0, 10)
        assertEquals(0.5 * 28 / 60.0, plan(block(0.75, 0), anhebung, jetztMin = 12).sumOf { it.second }, 1e-9)
    }

    @Test fun `die TBR sinkt Stufe um Stufe Richtung Profilbasal`() {
        // Faktor 3 auf 0,5 U/h: Zusatz bis 1,0 U/h in 8 Stufen. Raten 1,5 / 1,35 / 1,25 / 1,1 / 1,0 / 0,85 / 0,75 /
        // 0,6 U/h - ein ganzer Block traegt 0,5 / 0,425 / 0,375 / 0,3 / 0,25 / 0,175 / 0,125 / 0,05 U.
        val drei = Settings(enabled = true, factor = 3.0)
        fun bei(spielraum: Double) = lauf(30, drei) { input(it, iob = 8.0 - spielraum) }
        assertEquals(1.5, bei(0.6).rateUPerH, 1e-9)
        assertEquals(1.25, bei(0.4).rateUPerH, 1e-9)
        assertEquals("6/8 maxIob", bei(0.4).detail)
        assertEquals(1.1, bei(0.3).rateUPerH, 1e-9)
        assertEquals(0.75, bei(0.13).rateUPerH, 1e-9)
        assertEquals(0.6, bei(0.06).rateUPerH, 1e-9)
        assertEquals(Reason.MAX_IOB, bei(0.04).reason)
    }

    @Test fun `waehrend einer Teilbasal-Stufe wartet der Kanal`() {
        assertEquals(Reason.PARTIAL_ACTIVE, lauf(40) { input(it, teilbasal = true) }.reason)
    }

    @Test fun `maxIOB waehlt die hoechste Stufe, die ein ganzer Block noch hat`() {
        // Spielraum 0,2 U: der volle Zusatz (0,25 U je Block) passt nicht, die Stufe 3/4 (0,175 U) schon.
        val s = lauf(30) { input(it, iob = 7.8, maxIob = 8.0) }
        assertTrue(s.want)
        assertEquals(0.85, s.rateUPerH, 1e-9)
        assertEquals("3/4 maxIob", s.detail)
        // Ohne Spielraum fuer einen Basalschritt: kein Kanal.
        val voll = lauf(30) { input(it, iob = 7.99, maxIob = 8.0) }
        assertFalse(voll.want)
        assertEquals(Reason.MAX_IOB, voll.reason)
    }

    @Test fun `Faktor 5 ist erlaubt, darueber ist die Einstellung ungueltig`() {
        val fuenf = lauf(30, Settings(enabled = true, factor = 5.0))
        assertEquals(2.5, fuenf.rateUPerH, 1e-9)
        val zuviel = lauf(30, Settings(enabled = true, factor = 5.5))
        assertFalse(zuviel.want)
        assertEquals(Reason.INVALID_SETTINGS, zuviel.reason)
        assertNotNull(Settings(enabled = true, factor = 1.0).violation())
        assertNull(Settings(enabled = true, factor = 5.0).violation())
    }

    @Test fun `die Rate liegt auf dem Basalraster der Pumpe`() {
        // 0,55 U/h x 1,5 = 0,825 -> abgerundet 0,80
        val s = lauf(30, Settings(enabled = true, factor = 1.5)) { input(it, basal = 0.55) }
        assertEquals(0.80, s.rateUPerH, 1e-9)
    }

    @Test fun `ein laufender Block bleibt nahe maxIOB stehen, solange sein Rest passt`() {
        val mem = lauf(30).memory
        // Laeuft 1,0 U/h mit 20 min Rest: offen sind 0,5 U/h x 20 min = 0,167 U. IOB 7,8 + 0,167 <= 8,0 - der Block
        // bleibt, obwohl ein FRISCHER Block hier nur noch die Stufe 3/4 bekaeme.
        val bleibt = SlowCorrectionChannel.step(an, input(40, iob = 7.8, aktiv = true, laeuft = 1.0, rest = 20), mem)
        assertTrue(bleibt.want)
        assertEquals(1.0, bleibt.rateUPerH, 1e-9)
        assertEquals("block", bleibt.detail)
        // Passt der Rest nicht mehr (z. B. nach einem Handbolus), sinkt die TBR: Spielraum 0,05 U traegt noch die
        // Stufe 1/4 ...
        val enger = SlowCorrectionChannel.step(an, input(40, iob = 7.95, aktiv = true, laeuft = 1.0, rest = 20), mem)
        assertTrue(enger.want)
        assertEquals(0.6, enger.rateUPerH, 1e-9)
        assertEquals("1/4 maxIob", enger.detail)
        // ... und ohne Spielraum fuer einen Basalschritt endet der Kanal.
        val voll = SlowCorrectionChannel.step(an, input(40, iob = 7.99, aktiv = true, laeuft = 1.0, rest = 20), mem)
        assertFalse(voll.want)
        assertEquals(Reason.MAX_IOB, voll.reason)
        // Kurz vor dem Ende gilt wieder die Rechnung des frischen Blocks.
        val erneuern = SlowCorrectionChannel.step(an, input(40, iob = 7.8, aktiv = true, laeuft = 1.0, rest = 5), mem)
        assertEquals(0.85, erneuern.rateUPerH, 1e-9)
        // Liegt der laufende Block ueber der eingestellten Rate (Faktor gesenkt), wird nicht gehalten.
        val gesenkt = SlowCorrectionChannel.step(
            Settings(enabled = true, factor = 1.5), input(40, aktiv = true, laeuft = 1.0, rest = 20), mem,
        )
        assertEquals(0.75, gesenkt.rateUPerH, 1e-9)
        // Ein harter Stopp schlaegt auch den laufenden Block.
        assertFalse(SlowCorrectionChannel.step(an, input(40, hardStop = "DESCENT_RISK", aktiv = true, laeuft = 1.0, rest = 20), mem).want)
    }

    @Test fun `die Buchung trennt abgegeben, reserviert und verfallen`() {
        val gesetzt = start + 30 * minute
        var l = SlowCorrectionChannel.Ledger()
        // Zyklus 30: Einstieg, die Pumpe fuehrt noch nichts.
        l = SlowCorrectionChannel.book(l, gesetzt, null, 0L, null, 0.5, newEpisode = true)
        // Zyklus 31: die Pumpe fuehrt den Block (1,0 U/h, 29 min Rest) - 1 min abgegeben, 29 min reserviert.
        l = SlowCorrectionChannel.book(l, gesetzt + minute, 1.0, gesetzt, 29, 0.5, newEpisode = false)
        assertEquals(0.5 / 60.0, l.deliveredU, 1e-9)
        assertEquals(0.5 * 29 / 60.0, l.reservedU, 1e-9)
        // Bis Minute 41 weiter.
        for (m in 32..41) l = SlowCorrectionChannel.book(l, start + m * minute, 1.0, gesetzt, 30 - (m - 30), 0.5, false)
        assertEquals(0.5 * 11 / 60.0, l.deliveredU, 1e-9)
        assertEquals(0.0, l.cancelledU, 1e-9)
        // Minute 42: abgebrochen (das Endkommando ging in Minute 41 hinaus) - die 19 min Rest verfallen.
        l = SlowCorrectionChannel.book(l, start + 42 * minute, null, 0L, null, 0.5, false)
        assertEquals(0.5 * 11 / 60.0, l.deliveredU, 1e-9)
        assertEquals(0.5 * 19 / 60.0, l.cancelledU, 1e-9)
        assertEquals(0.0, l.reservedU, 1e-9)
        // Eine Erneuerung loest den Block ab: nichts verfaellt, die Abgabe laeuft ohne Luecke weiter.
        var e = SlowCorrectionChannel.book(SlowCorrectionChannel.Ledger(), start, null, 0L, null, 0.5, newEpisode = true)
        for (m in 1..25) e = SlowCorrectionChannel.book(e, start + m * minute, 1.0, start, 30 - m, 0.5, false)
        // Minute 26: die Pumpe fuehrt den neuen Block (in Minute 25 angefordert, 29 min Rest).
        e = SlowCorrectionChannel.book(e, start + 26 * minute, 1.0, start + 25 * minute, 29, 0.5, false)
        assertEquals(0.0, e.cancelledU, 1e-9)
        assertEquals(0.5 * 26 / 60.0, e.deliveredU, 1e-9)
        assertEquals(0.5 * 29 / 60.0, e.reservedU, 1e-9)
        // Ein neuer Einstieg beginnt die Summen neu.
        l = SlowCorrectionChannel.book(l, start + 60 * minute, null, 0L, null, 0.5, newEpisode = true)
        assertEquals(0.0, l.deliveredU, 1e-9)
        assertEquals(0.0, l.cancelledU, 1e-9)
    }

    @Test fun `ein ausgelaufener Block gibt seinen Rest noch ab, eine Luecke bucht nichts`() {
        // Minute 0: angefordert, die Pumpe fuehrt noch nichts.
        var l = SlowCorrectionChannel.book(SlowCorrectionChannel.Ledger(), start, null, 0L, null, 0.5, newEpisode = true)
        for (m in 1..29) l = SlowCorrectionChannel.book(l, start + m * minute, 1.0, start, 30 - m, 0.5, false)
        // Minute 30: ausgelaufen - der letzte Rest (1 min) ging noch hinaus. Abgegeben: der ganze Block, 0,25 U.
        l = SlowCorrectionChannel.book(l, start + 30 * minute, null, 0L, null, 0.5, false)
        assertEquals(0.25, l.deliveredU, 1e-9)
        assertEquals(0.0, l.cancelledU, 1e-9)
        // Eine Luecke von 20 min mit verschwundenem Block: nichts gebucht.
        var g = SlowCorrectionChannel.Ledger()
        g = SlowCorrectionChannel.book(g, start + minute, 1.0, start, 29, 0.5, false)
        val vorher = g.deliveredU
        g = SlowCorrectionChannel.book(g, start + 21 * minute, null, 0L, null, 0.5, false)
        assertEquals(vorher, g.deliveredU, 1e-9)
        assertEquals(0.0, g.cancelledU, 1e-9)
    }

    @Test fun `erneuert wird erst kurz vor dem Ende oder bei anderer Rate`() {
        assertTrue(SlowCorrectionChannel.needsCommand(1.0, null, null, 0.05))
        assertFalse(SlowCorrectionChannel.needsCommand(1.0, 1.0, 12, 0.05))
        assertTrue(SlowCorrectionChannel.needsCommand(1.0, 1.0, 5, 0.05))
        assertTrue(SlowCorrectionChannel.needsCommand(0.9, 1.0, 20, 0.05))
    }
}
