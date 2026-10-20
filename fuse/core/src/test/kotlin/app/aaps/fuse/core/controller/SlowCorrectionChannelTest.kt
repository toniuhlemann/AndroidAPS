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

    private val an = Settings(enabled = true)
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
        // Faktor 5 auf 0,5 U/h: Zusatz bis 2,0 U/h, Stufen 1,0 / 1,5 / 2,0 / 2,5 U/h. Ein frischer Block traegt
        // 2 x Restbedarf je Stunde (wie autoISF: Basal + 2 x insulinReq).
        val fuenf = Settings(enabled = true, factor = 5.0)
        fun bei(bedarf: Double?, smb: Double = 0.0) = lauf(30, fuenf) { input(it, bedarf = bedarf, smb = if (it == 30) smb else 0.0) }
        assertEquals(1.0, bei(0.4).rateUPerH, 1e-9)          // 0,8 U/h Zusatz -> Stufe 1/4
        assertEquals(1, bei(0.4).stage)
        assertEquals(1.5, bei(0.6).rateUPerH, 1e-9)          // 1,2 U/h -> Stufe 2/4
        assertEquals(2.5, bei(2.0).rateUPerH, 1e-9)          // 4,0 U/h -> Obergrenze
        assertEquals("4/4 bedarf", bei(2.0).detail)
        // Der SMB dieses Zyklus deckt einen Teil: Restbedarf 0,3 U -> 0,6 U/h -> Stufe 1/4.
        assertEquals(1.0, bei(0.6, smb = 0.3).rateUPerH, 1e-9)
        // Traegt der Restbedarf keine Stufe, kommt keine TBR.
        assertEquals(Reason.NO_DEMAND, bei(0.1).reason)
        assertEquals(Reason.NO_DEMAND, bei(-0.5).reason)
        // Ohne berechenbaren Bedarf startet nichts.
        assertEquals(Reason.NO_DEMAND_ESTIMATE, bei(null).reason)
    }

    @Test fun `ein laufender Block sinkt abgestuft mit dem Bedarf und steigt erst bei der Erneuerung`() {
        val fuenf = Settings(enabled = true, factor = 5.0)
        val mem = lauf(30, fuenf).memory
        fun laufend(rate: Double, bedarf: Double?, rest: Int = 20, geplant: Double = 0.0) =
            SlowCorrectionChannel.step(fuenf, input(40, aktiv = true, laeuft = rate, rest = rest, bedarf = bedarf, geplant = geplant), mem)
        // Stufe 4/4 (2,5 U/h), 20 min Rest: offen 0,667 U. Deckt der Plan den Bedarf genau, bleibt der Block.
        assertEquals("block", laufend(2.5, 0.0).detail)
        // Fehlen 0,2 U im Bedarf, traegt die Restzeit nur noch Stufe 3/4.
        val tiefer = laufend(2.5, -0.2)
        assertEquals(2.0, tiefer.rateUPerH, 1e-9)
        assertEquals("3/4 bedarf", tiefer.detail)
        // Ist fast nichts mehr noetig, endet der Block (Profilbasal).
        assertEquals(Reason.NO_DEMAND, laufend(2.5, -0.6).reason)
        // Knapp ueber der Deckung (eine halbe Stufe Spiel) bleibt er stehen - kein Pendeln.
        assertEquals("block", laufend(1.5, -0.05).detail)
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

    @Test fun `der geplante Rest steht als Einzelgaben in der Prognose`() {
        // 1,0 U/h auf 0,5 U/h, vor 10 min angefordert: 20 min Rest in 5-min-Gaben zu je 0,5 x 5/60 U.
        val gaben = SlowCorrectionChannel.plannedChunks(1.0, start, 30, start + 10 * minute, 0.5)
        assertEquals(4, gaben.size)
        assertEquals(start + 10 * minute, gaben.first().first)
        assertEquals(0.5 * 20 / 60.0, gaben.sumOf { it.second }, 1e-9)
        // Noch nicht begonnen: ab der Anforderung, der ganze Block.
        assertEquals(0.25, SlowCorrectionChannel.plannedChunks(1.0, start, 30, start - minute, 0.5).sumOf { it.second }, 1e-9)
        // Abgelaufen oder ohne Zusatz: nichts.
        assertTrue(SlowCorrectionChannel.plannedChunks(1.0, start, 30, start + 30 * minute, 0.5).isEmpty())
        assertTrue(SlowCorrectionChannel.plannedChunks(0.5, start, 30, start, 0.5).isEmpty())
    }

    @Test fun `die TBR sinkt Stufe um Stufe Richtung Profilbasal`() {
        // Faktor 3 auf 0,5 U/h: Zusatz 1,0 U/h. Stufen 1,5 / 1,25 / 1,0 / 0,75 U/h - ein ganzer Block traegt
        // 0,5 / 0,375 / 0,25 / 0,125 U.
        val drei = Settings(enabled = true, factor = 3.0)
        fun bei(spielraum: Double) = lauf(30, drei) { input(it, iob = 8.0 - spielraum) }
        assertEquals(1.5, bei(0.6).rateUPerH, 1e-9)
        assertEquals(1.25, bei(0.4).rateUPerH, 1e-9)
        assertEquals("3/4 maxIob", bei(0.4).detail)
        assertEquals(1.0, bei(0.3).rateUPerH, 1e-9)
        assertEquals(0.75, bei(0.13).rateUPerH, 1e-9)
        assertEquals(Reason.MAX_IOB, bei(0.1).reason)
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
