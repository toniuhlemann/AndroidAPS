package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.PartialTbrOwnership.Phase
import app.aaps.fuse.core.controller.PartialTbrOwnership.Reason
import app.aaps.fuse.core.controller.PartialTbrOwnership.View
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER LEBENSZYKLUS DER EIGENEN TEIL-TBR.
 *
 * Der wichtigste Test ist [E2E die geforderte Zyklusfolge] - er faehrt
 * die Folge Anforderung, ausbleibende Sicht, verspaetetes Auftauchen,
 * Latch-Freigabe, fehlgeschlagener Abbruch, autoritative Bestaetigung
 * am Stueck durch. Die Einzeltests darunter halten die Kanten fest.
 *
 * Zwei Fehlerrichtungen, und sie sind NICHT gleich schlimm: eine FREMDE
 * Absenkung faelschlich beenden = ungefragt Insulin erhoehen. Eine
 * EIGENE faelschlich halten = laenger weniger geben, bei gesperrtem SMB.
 * Jede Zweifelsentscheidung faellt deshalb auf "halten, nicht anfassen".
 */
class PartialTbrOwnershipTest {

    private val schritt = 0.05
    private val t0 = 1_700_000_000_000L
    private val cfg = TbrPolicy.Config(basalStepUPerH = schritt)
    private val profil = 0.60

    private fun min(n: Int) = t0 + n * 60_000L

    private fun anforderung(rate: Double = 0.30, ts: Long = t0, dauer: Int = 30) =
        PartialTbrOwnership.Own(rate, ts, dauer, Phase.REQUESTED, ts)

    private fun laufend(rate: Double, restMin: Int, typ: TbrPolicy.SourceType = TbrPolicy.SourceType.TEMP_BASAL) =
        TbrPolicy.Current(rate, restMin, typ)

    private fun auth(c: TbrPolicy.Current?) = View.Authoritative(c)

    private fun schritt(
        own: PartialTbrOwnership.Own?,
        view: View,
        nowTs: Long,
        wunschRate: Double? = null,
        wantEnd: Boolean = false,
    ) = PartialTbrOwnership.advance(own, view, nowTs, schritt, wunschRate, wantEnd)

    // =====================================================================
    // DIE GEFORDERTE ZYKLUSFOLGE, AM STUECK
    // =====================================================================

    @Test
    fun `E2E die geforderte Zyklusfolge`() {
        // (1) Teil-TBR angefordert, noch nicht sichtbar.
        var s = schritt(null, auth(null), t0, wunschRate = 0.30)
        assertTrue(s.allowSet) { "ohne Nachweis ist der erste Versuch zulaessig" }
        // Erst das TATSAECHLICH gesendete Kommando legt den Nachweis an.
        var own = PartialTbrOwnership.registerSet(s.own, 0.30, 30, t0, schritt)
        assertEquals(Phase.REQUESTED, own.phase)
        assertEquals(1, own.setAttempts)
        s = schritt(own, auth(null), t0, wunschRate = 0.30)
        assertTrue(s.smbBlocked) { "ab der Anforderung ist der schnelle Kanal zu" }
        assertFalse(s.sendCancel)
        assertFalse(s.allowSet) { "und kein zweites Kommando fuer dieselbe Rate" }

        // (2) Naechster Snapshot weiterhin ohne TBR - Besitz bleibt REQUESTED,
        //     und die Frist beginnt NICHT neu.
        s = schritt(own, auth(null), min(1), wunschRate = 0.30)
        assertEquals(Reason.SET_SUPPRESSED_DUPLICATE, s.reason)
        assertEquals(Phase.REQUESTED, s.own!!.phase) { "NICHT geloescht - sie kann verspaetet auftauchen" }
        assertEquals(t0, s.own!!.setAtTs) { "kein stiller Neubeginn der Frist" }
        assertFalse(s.allowSet)
        assertTrue(s.smbBlocked)
        own = s.own!!

        // (3) Teil-TBR erscheint verzoegert -> RUNNING.
        //     2 min nach der Anforderung, die Pumpe hat bei Minute 2 mit
        //     30 min gestartet: Rest 30 statt erwarteter 28.
        s = schritt(own, auth(laufend(0.30, 30)), min(2), wunschRate = 0.30)
        assertEquals(Reason.CONFIRMED_RUNNING, s.reason)
        assertEquals(Phase.RUNNING, s.own!!.phase)
        assertTrue(s.smbBlocked)
        own = s.own!!

        // (4) Latch loest -> Abbruch und ENDING, SMB gesperrt.
        s = schritt(own, auth(laufend(0.30, 27)), min(5), wantEnd = true)
        assertEquals(Reason.END_REQUESTED, s.reason)
        assertEquals(Phase.ENDING, s.own!!.phase)
        assertTrue(s.sendCancel) { "das Kommando geht raus" }
        assertFalse(s.allowSet) { "und daneben kein Setzen" }
        assertTrue(s.smbBlocked) { "der Abbruch HEBT die Rate - kein SMB daneben" }
        own = PartialTbrOwnership.registerCancel(s.own, min(5))!!
        assertEquals(1, own.endAttempts)

        // (5) Snapshot zeigt sie weiter -> ENDING, Wiederholung NUR nach Backoff.
        s = schritt(own, auth(laufend(0.30, 26)), min(6), wantEnd = true)
        assertEquals(Reason.END_BACKOFF_WAIT, s.reason)
        assertFalse(s.sendCancel) { "kein Kommando je Zyklus - das war der Spam-Befund" }
        assertTrue(s.smbBlocked)
        s = schritt(own, auth(laufend(0.30, 25)), min(7), wantEnd = true)
        assertEquals(Reason.END_BACKOFF_WAIT, s.reason)
        // erst nach END_BACKOFF_MIN wieder
        s = schritt(own, auth(laufend(0.30, 22)), min(8), wantEnd = true)
        assertEquals(Reason.END_RETRY, s.reason)
        assertTrue(s.sendCancel)
        own = PartialTbrOwnership.registerCancel(s.own, min(8))!!
        assertEquals(2, own.endAttempts)

        // (6) Autoritativ keine TBR -> geloescht, ERST JETZT ist der SMB offen.
        s = schritt(own, auth(null), min(9), wantEnd = true)
        assertEquals(Reason.CLEARED_CONFIRMED, s.reason)
        assertNull(s.own)
        assertFalse(s.smbBlocked) { "das ist der einzige Weg, auf dem der SMB wieder aufgeht" }
        assertFalse(s.sendCancel)
    }

    // =====================================================================
    // DIE RACE, DIE DAS AUSGELOEST HAT
    // =====================================================================

    @Test
    fun `die Race der ersten Fassung ist geschlossen`() {
        // Zyklus N: angefordert. N+1: nicht sichtbar UND der Riegel loest.
        // Die alte Fassung loeschte hier den Nachweis; danach galt die
        // verspaetet uebernommene Rate als fremd.
        val gebucht = PartialTbrOwnership.registerSet(null, 0.30, 30, t0, schritt)
        var s = schritt(gebucht, auth(null), min(1), wantEnd = true)
        assertNotNull(s.own) { "NICHT geloescht" }
        assertEquals(Reason.WAITING_CONFIRM, s.reason)
        assertTrue(s.smbBlocked)

        // N+2: die Pumpe uebernimmt verspaetet - sie wird als UNSERE erkannt
        // und abgebrochen, nicht als fremde stehen gelassen.
        s = schritt(s.own, auth(laufend(0.30, 30)), min(2), wantEnd = true)
        assertEquals(Reason.END_REQUESTED, s.reason)
        assertTrue(s.sendCancel)
    }

    // =====================================================================
    // UNBRAUCHBARE SICHT
    // =====================================================================

    @Test
    fun `eine unbrauchbare Sicht loescht NIEMALS und bricht NIEMALS ab`() {
        for (phase in Phase.entries) {
            val own = anforderung().copy(phase = phase, phaseSinceTs = t0, endAttempts = 0)
            val s = schritt(own, View.Unknown, min(10), wantEnd = true)
            assertEquals(Reason.VIEW_UNKNOWN_HELD, s.reason, phase.name)
            assertEquals(own, s.own, "$phase: unveraendert gehalten")
            assertTrue(s.smbBlocked, phase.name)
            assertFalse(s.sendCancel, "$phase: ohne Sicht wird nichts angefasst")
        }
    }

    @Test
    fun `eine unbrauchbare Sicht laesst auch eine abgelaufene Frist nicht verfallen`() {
        val s = schritt(anforderung(), View.Unknown, min(60))
        assertNotNull(s.own) { "ohne Sicht gibt es keinen Beweis, dass sie NICHT laeuft" }
        assertTrue(s.smbBlocked)
    }

    // =====================================================================
    // BESTAETIGUNGSFRIST
    // =====================================================================

    @Test
    fun `nach Ablauf der Frist wird BENANNT verworfen, nicht still`() {
        assertEquals(5, PartialTbrOwnership.CONFIRM_WINDOW_MIN)
        val nochDrin = schritt(anforderung(), auth(null), min(5))
        assertEquals(Reason.WAITING_CONFIRM, nochDrin.reason)
        val drueber = schritt(anforderung(), auth(null), min(6))
        assertEquals(Reason.CONFIRM_TIMEOUT, drueber.reason)
        assertNull(drueber.own)
        assertFalse(drueber.sendCancel) { "und dabei wird NIE etwas Fremdes abgebrochen" }
    }

    @Test
    fun `verspaetetes Auftauchen INNERHALB der Frist gilt als unsere`() {
        // Die Pumpe startet erst bei Minute 4 mit voller Dauer.
        val s = schritt(anforderung(), auth(laufend(0.30, 30)), min(4))
        assertEquals(Reason.CONFIRMED_RUNNING, s.reason)
        assertEquals(Phase.RUNNING, s.own!!.phase)
    }

    // =====================================================================
    // FREMDE TBR
    // =====================================================================

    @Test
    fun `eine fremde Absenkung mit GLEICHER Rate aber unpassender Zeit wird nie angefasst`() {
        // Bestaetigt laufende eigene Rate, dann taucht eine andere TBR mit
        // derselben Rate auf, deren Restlaufzeit nicht passt.
        val laufendEigen = anforderung().copy(phase = Phase.RUNNING, phaseSinceTs = t0)
        val s = schritt(laufendEigen, auth(laufend(0.30, 5)), min(10), wantEnd = true)
        assertEquals(Reason.CLEARED_CONFIRMED, s.reason) { "unsere ist weg" }
        assertNull(s.own)
        assertFalse(s.sendCancel) { "und die fremde wird NICHT abgebrochen" }
    }

    @Test
    fun `ein FAKE_EXTENDED ist nie unsere Teilrate`() {
        assertFalse(PartialTbrOwnership.matches(
            anforderung(), laufend(0.30, 20, TbrPolicy.SourceType.FAKE_EXTENDED), min(10), schritt))
    }

    @Test
    fun `die Toleranz ist unten eng und oben nur so weit wie die Wartezeit`() {
        assertEquals(3, PartialTbrOwnership.REMAINING_TOLERANCE_MIN)
        val own = anforderung()
        // 10 min gewartet, erwarteter Rest 20. Unten bis 17, oben bis 25
        // (Wartezeit 10, gedeckelt auf CONFIRM_WINDOW 5).
        assertTrue(PartialTbrOwnership.matches(own, laufend(0.30, 20), min(10), schritt))
        assertTrue(PartialTbrOwnership.matches(own, laufend(0.30, 17), min(10), schritt))
        assertFalse(PartialTbrOwnership.matches(own, laufend(0.30, 16), min(10), schritt))
        assertTrue(PartialTbrOwnership.matches(own, laufend(0.30, 25), min(10), schritt))
        assertFalse(PartialTbrOwnership.matches(own, laufend(0.30, 26), min(10), schritt))
        // Nach EINER Minute ist das Band oben viel enger - die Pumpe kann
        // nicht mehr verspaetet sein, als wir gewartet haben.
        assertTrue(PartialTbrOwnership.matches(own, laufend(0.30, 30), min(1), schritt))
        assertFalse(PartialTbrOwnership.matches(own, laufend(0.30, 31), min(1), schritt))
    }

    @Test
    fun `eine abweichende Rate ist nie unsere - ein halber Pumpenschritt schon`() {
        assertFalse(PartialTbrOwnership.matches(anforderung(0.30), laufend(0.20, 20), min(10), schritt))
        assertTrue(PartialTbrOwnership.matches(anforderung(0.30), laufend(0.31, 20), min(10), schritt))
    }

    // =====================================================================
    // BACKOFF UND DECKEL
    // =====================================================================

    @Test
    fun `nach dem Versuchsdeckel geht kein Kommando mehr raus - der SMB bleibt trotzdem zu`() {
        assertEquals(3, PartialTbrOwnership.END_MAX_ATTEMPTS)
        // Bei Minute 20 sind von 30 noch 10 uebrig - sie laeuft also noch.
        val own = anforderung().copy(phase = Phase.ENDING, phaseSinceTs = t0, endAttempts = 3, lastEndRequestTs = min(10))
        val s = schritt(own, auth(laufend(0.30, 10)), min(20))
        assertEquals(Reason.END_GIVEN_UP, s.reason)
        assertFalse(s.sendCancel)
        assertTrue(s.smbBlocked) { "aufgeben heisst nicht freigeben - sie laeuft ja noch" }
    }

    @Test
    fun `der Backoff-Abstand ist benannt und wird eingehalten`() {
        assertEquals(3, PartialTbrOwnership.END_BACKOFF_MIN)
        val own = anforderung().copy(phase = Phase.ENDING, phaseSinceTs = t0, endAttempts = 1, lastEndRequestTs = min(10))
        // Rest = 30 minus verstrichene Minuten, sonst passt sie gar nicht.
        assertFalse(schritt(own, auth(laufend(0.30, 18)), min(12)).sendCancel) { "2 min" }
        assertTrue(schritt(own, auth(laufend(0.30, 17)), min(13)).sendCancel) { "3 min" }
    }

    // =====================================================================
    // ERNEUERUNG UND NEUSTART
    // =====================================================================

    @Test
    fun `eine Erneuerung derselben laufenden Rate stuft NICHT auf REQUESTED zurueck`() {
        val laufendEigen = anforderung().copy(phase = Phase.RUNNING, phaseSinceTs = t0)
        val s = schritt(laufendEigen, auth(laufend(0.30, 12)), min(18), wunschRate = 0.30)
        assertEquals(Phase.RUNNING, s.own!!.phase)
        assertTrue(s.allowSet) { "eine bestaetigte Rate darf erneuert werden" }
        val erneuert = PartialTbrOwnership.registerSet(s.own, 0.30, 30, min(18), schritt)
        assertEquals(Phase.RUNNING, erneuert.phase) { "sonst faellt jede Erneuerung in die Frist zurueck" }
        assertEquals(min(18), erneuert.setAtTs) { "aber die Uhr laeuft neu" }
    }

    @Test
    fun `nach einem Neustart traegt jeder Zustand weiter`() {
        for (phase in Phase.entries) {
            // Ein Prozessstart aendert nichts am Datensatz - genau deshalb
            // ist er persistent und nicht prozesslokal wie die Riegelzaehler.
            val own = PartialTbrOwnership.Own(0.30, t0, 30, phase, t0)
            assertTrue(own.valid, phase.name)
            val s = schritt(own, auth(laufend(0.30, 20)), min(10))
            assertTrue(s.smbBlocked, "$phase: der SMB bleibt zu, bis autoritativ bestaetigt ist")
            assertNotNull(s.own, phase.name)
        }
    }

    @Test
    fun `ein unbrauchbarer Nachweis gilt als keiner`() {
        for ((was, own) in listOf(
            "Rate 0" to anforderung(rate = 0.0),
            "Rate NaN" to anforderung(rate = Double.NaN),
            "kein Zeitstempel" to anforderung(ts = 0L),
            "Dauer 0" to anforderung(dauer = 0),
        )) {
            assertFalse(own.valid, was)
            assertEquals(Reason.NONE, schritt(own, auth(laufend(0.30, 20)), min(10)).reason, was)
        }
    }

    // =====================================================================
    // DIE TABELLE FUEHRT NUR AUS
    // =====================================================================

    private fun keepMit(current: TbrPolicy.Current?, end: Boolean, held: Boolean, busy: Boolean = false) =
        TbrPolicy.decide(
            TbrPolicy.Intent.KEEP, current, profil, cfg,
            pumpBusy = busy, endOwnPartial = end, ownPartialHeld = held,
        )

    @Test
    fun `ein faelliger Abbruch wird als Rate 0 mit Dauer 0 ausgefuehrt`() {
        val d = keepMit(laufend(0.30, 20), end = true, held = true)
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome) { "Abbruch, zurueck aufs Profilbasal" }
        assertEquals(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON, d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause)
    }

    @Test
    fun `ein gehaltener Nachweis ohne faelliges Kommando sperrt den SMB trotzdem`() {
        val d = keepMit(laufend(0.30, 20), end = false, held = true)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome) { "Backoff-Pause: kein Kommando" }
        assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause) { "aber der Kanal bleibt zu" }
        assertEquals("KEEP_OWN_PARTIAL_HELD", d.reason)
    }

    @Test
    fun `ohne Nachweis bleibt eine fremde Absenkung unangetastet und der SMB frei`() {
        val d = keepMit(laufend(0.30, 20), end = false, held = false)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals("KEEP", d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.NONE, d.smbBlockCause)
    }

    @Test
    fun `eine arbeitende Pumpe unterdrueckt das Kommando, nicht die Sperre`() {
        val d = keepMit(laufend(0.30, 20), end = true, held = true, busy = true)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals(TbrPolicy.SmbBlockCause.PUMP_BUSY, d.smbBlockCause)
        assertTrue(d.reason.startsWith("PUMP_BUSY|"), d.reason)
        assertTrue(d.reason.contains(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON), d.reason)
    }

    @Test
    fun `Null und positive TBR behalten ihre eigenen Wege`() {
        assertEquals(TbrPolicy.KEEP_CANCEL_STALE_ZERO_REASON,
            keepMit(laufend(0.0, 20), end = false, held = false).reason)
        assertEquals("KEEP_CANCEL_POSITIVE",
            keepMit(laufend(1.20, 20), end = false, held = false).reason)
    }

    @Test
    fun `PARTIAL nach ZERO ersetzt im SELBEN Zyklus - Schalter aus unter aktivem Latch`() {
        val d = TbrPolicy.decide(TbrPolicy.Intent.SAFETY_ZERO, laufend(0.30, 20), profil, cfg)
        assertEquals(TbrPolicy.Outcome.Request(0.0, cfg.defaultDurationMin), d.outcome)
        assertEquals("SAFETY_ZERO_REPLACE", d.reason) { "kein Auslaufen der Teilrate" }
        assertEquals(TbrPolicy.SmbBlockCause.SAFETY_ZERO, d.smbBlockCause)
    }

    // ---- DERSELBE AUSGANG UNTER NO_POSITIVE ------------------------------
    //
    // Vom E2E-Kommandostromtest gefunden: der Besitzausgang lag zuerst NUR
    // im KEEP-Pfad. Nach einer Nullphase steht aber sehr oft
    // NO_NEW_POSITIVE oder CANCEL_TO_SCHEDULED an - und dort fiel eine
    // laufende EIGENE Absenkung durch, lief bis zum Ablauf weiter und der
    // SMB war offen. Also derselbe Fehler ueber einen anderen Intent.

    private fun noPositiveMit(current: TbrPolicy.Current?, end: Boolean, held: Boolean) =
        TbrPolicy.decide(
            TbrPolicy.Intent.NO_POSITIVE, current, profil, cfg,
            endOwnPartial = end, ownPartialHeld = held,
        )

    @Test
    fun `unter NO_POSITIVE wird die eigene Teilrate genauso beendet`() {
        val d = noPositiveMit(laufend(0.30, 20), end = true, held = true)
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome)
        assertEquals(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON, d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause)
    }

    @Test
    fun `unter NO_POSITIVE sperrt ein gehaltener Nachweis den SMB - auch ohne Kommando`() {
        for ((was, cur) in listOf(
            "laufende eigene Absenkung" to laufend(0.30, 20),
            "gar keine sichtbare TBR" to null,
        )) {
            val d = noPositiveMit(cur, end = false, held = true)
            assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome, was)
            assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause) {
                "$was: sonst laeuft unsere Absenkung weiter UND der schnelle Kanal ist offen"
            }
            assertEquals("NO_POSITIVE_OWN_PARTIAL_HELD", d.reason, was)
        }
    }

    @Test
    fun `unter NO_POSITIVE bleibt eine FREMDE Absenkung unangetastet`() {
        val d = noPositiveMit(laufend(0.30, 20), end = false, held = false)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals("NO_POSITIVE_KEEP_NON_POSITIVE", d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.NONE, d.smbBlockCause) { "C7b bleibt" }
    }

    @Test
    fun `unter NO_POSITIVE wird eine positive TBR weiterhin zuerst abgebrochen`() {
        val d = noPositiveMit(laufend(1.20, 20), end = false, held = true)
        assertEquals("NO_POSITIVE_CANCEL", d.reason) {
            "der Besitzpfad darf den positiven Abbruch nicht verdecken"
        }
    }

    @Test
    fun `die Blockursachen sind vollstaendig aufgezaehlt`() {
        assertEquals(9, TbrPolicy.SmbBlockCause.entries.size)
        assertTrue(TbrPolicy.SmbBlockCause.entries.contains(TbrPolicy.SmbBlockCause.PARTIAL_ENDING))
    }

    @Test
    fun `die Gruende des Lebenszyklus sind vollstaendig aufgezaehlt`() {
        // 17 seit der Setzberechtigung: die sechs SET_-Gruende kamen mit
        // der zweiten Race dazu (Duplikat, Senken, Halten, Retry, Backoff,
        // Deckel). Ein neuer Grund muss bewusst aufgenommen werden.
        assertEquals(17, Reason.entries.size)
    }
}
