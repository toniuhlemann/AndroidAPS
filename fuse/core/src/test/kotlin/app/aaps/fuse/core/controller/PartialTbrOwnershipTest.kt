package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.PartialTbrOwnership.Ending
import app.aaps.fuse.core.controller.PartialTbrOwnership.Identity
import app.aaps.fuse.core.controller.PartialTbrOwnership.Reason
import app.aaps.fuse.core.controller.PartialTbrOwnership.State
import app.aaps.fuse.core.controller.PartialTbrOwnership.View
import app.aaps.fuse.core.controller.PartialTbrOwnership.Wirkung
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BESITZ UND ENDE DER EIGENEN TEIL-TBR.
 *
 * Zwei Fehlerrichtungen, und sie sind NICHT gleich schlimm: eine FREMDE
 * Absenkung faelschlich beenden = ungefragt Insulin erhoehen. Eine
 * EIGENE faelschlich halten = laenger weniger geben, bei gesperrtem SMB.
 * Jede Zweifelsentscheidung faellt deshalb auf "halten, nicht anfassen".
 */
class PartialTbrOwnershipTest {

    private val schritt = 0.05
    private val profil = 0.60
    private val t0 = 1_700_000_000_000L
    private val cfg = TbrPolicy.Config(basalStepUPerH = schritt)

    private fun min(n: Int) = t0 + n * 60_000L
    private fun id(rate: Double = 0.30, ts: Long = t0, dauer: Int = 30) = Identity(rate, ts, dauer)
    private fun laufend(rate: Double, restMin: Int, typ: TbrPolicy.SourceType = TbrPolicy.SourceType.TEMP_BASAL) =
        TbrPolicy.Current(rate, restMin, typ)

    private fun auth(c: TbrPolicy.Current?) = View.Authoritative(c)

    private fun schritt(
        state: State,
        view: View,
        nowTs: Long,
        wunschRate: Double? = null,
        wantEnd: Boolean = false,
    ) = PartialTbrOwnership.advance(state, view, nowTs, schritt, wunschRate, wantEnd)

    // =====================================================================
    // P0-1: BESTAETIGTE RATE UND OFFENE ANFORDERUNG SIND ZWEI DINGE
    // =====================================================================

    /**
     * DER GEMELDETE P0, Pflichtfaelle 1 und 2.
     *
     * 0,85 laeuft bestaetigt, der Guard will 1,00, die Pumpe zeigt weiter
     * 0,85. Die Vorfassung ueberschrieb den Datensatz mit 1,00, fand beim
     * Latch-Ende nichts Passendes und loeschte den Besitz - waehrend
     * unsere 0,85 weiterlief und der SMB wieder aufging.
     */
    @Test
    fun `eine bestaetigte Rate ueberlebt eine neue Anforderung`() {
        val s = State(
            confirmedRunning = id(0.85, t0, 30),
            pendingRequest = id(1.00, min(10), 30),
            pendingAttempts = 1,
        )
        val r = schritt(s, auth(laufend(0.85, 20)), min(10))
        // Der Grund benennt die OFFENE Anforderung - sie hat Vorrang in der
        // Auskunft. Entscheidend ist die Substanz darunter: die bestaetigte
        // 0,85 bleibt erhalten und der Kanal bleibt zu.
        assertEquals(Reason.WAITING_CONFIRM, r.reason)
        assertNotNull(r.state.confirmedRunning) { "0,85 bleibt als UNSERE laufende Rate erkannt" }
        assertEquals(0.85, r.state.confirmedRunning!!.rateUPerH, 1e-12)
        assertNotNull(r.state.pendingRequest) { "und 1,00 bleibt offen" }
        assertTrue(r.smbBlocked)

        // Latch-Ende in genau dieser Lage: GENAU EIN Abbruch, SMB zu.
        val e = schritt(s, auth(laufend(0.85, 20)), min(10), wantEnd = true)
        assertEquals(Reason.END_REQUESTED, e.reason)
        assertTrue(e.sendCancel) { "genau hier fehlte der Abbruch" }
        assertTrue(e.smbBlocked)
    }

    @Test
    fun `erst ohne alte UND neue Rate wird geloescht und der SMB geoeffnet`() {
        val s = State(
            confirmedRunning = id(0.85, t0, 30),
            pendingRequest = id(1.00, min(10), 30),
            pendingAttempts = 1,
            ending = Ending(sinceTs = min(10), attempts = 1, lastRequestTs = min(10)),
        )
        val r = schritt(s, auth(null), min(16), wantEnd = true)
        assertEquals(Reason.CLEARED_CONFIRMED, r.reason)
        assertTrue(r.state.leer)
        assertFalse(r.smbBlocked)
    }

    @Test
    fun `eine Erneuerung darf den Besitz nicht verlieren`() {
        // Erneuerung bei Minute 18 gebucht; die Pumpe meldet noch die ALTE
        // TBR (Rest 12 von der urspruenglichen 30er).
        val vorher = State(confirmedRunning = id(0.30, t0, 30))
        val nachher = PartialTbrOwnership.buche(vorher, Wirkung.SET_PARTIAL, 0.30, 30, min(18), schritt)
        assertNotNull(nachher.confirmedRunning) { "die bestaetigte Rate bleibt stehen" }
        assertNotNull(nachher.pendingRequest)
        val r = schritt(nachher, auth(laufend(0.30, 12)), min(18))
        assertTrue(r.smbBlocked) { "der Besitz darf hier NICHT verschwinden" }
        assertFalse(r.state.leer)
        assertNotNull(r.state.confirmedRunning) { "die alte Kennung traegt weiter" }
        assertEquals(Reason.WAITING_CONFIRM, r.reason) { "die Erneuerung ist noch nicht sichtbar" }
    }

    @Test
    fun `eine fehlgeschlagene Ratenaenderung laeuft in den Retry - trotz bestaetigter alter Rate`() {
        val s = State(
            confirmedRunning = id(0.85, t0, 30),
            pendingRequest = id(1.00, min(2), 30),
            pendingAttempts = 1,
        )
        val r = schritt(s, auth(laufend(0.85, 22)), min(10), wunschRate = 1.00)
        assertEquals(Reason.SET_RETRY, r.reason) { "der Retry-Pfad darf nicht uebersprungen werden" }
        assertTrue(r.allowSet)
        assertNotNull(r.state.confirmedRunning) { "und die alte Rate bleibt dabei erkannt" }
    }

    @Test
    fun `ohne neuen Wunsch verfaellt die Anforderung benannt - die laufende Rate bleibt`() {
        val s = State(confirmedRunning = id(0.85, t0, 30), pendingRequest = id(1.00, min(2), 30), pendingAttempts = 1)
        val r = schritt(s, auth(laufend(0.85, 22)), min(10))
        assertEquals(Reason.CONFIRM_TIMEOUT, r.reason)
        assertNull(r.state.pendingRequest)
        assertNotNull(r.state.confirmedRunning)
        assertTrue(r.smbBlocked) { "es laeuft ja noch etwas von uns" }
    }

    @Test
    fun `ein Neustart zwischen den Uebergaengen liefert dasselbe`() {
        // Der Zustand ist ein reiner Datensatz; ein Prozessstart aendert
        // nichts an ihm. Genau deshalb ist er persistent.
        listOf(
            State(pendingRequest = id(0.30, t0, 30), pendingAttempts = 1),
            State(confirmedRunning = id(0.30, t0, 30)),
            State(confirmedRunning = id(0.85, t0, 30), pendingRequest = id(1.00, min(2), 30), pendingAttempts = 1),
            State(confirmedRunning = id(0.30, t0, 30), ending = Ending(min(5), 1, min(5))),
        ).forEach { s ->
            val sicht = auth(laufend(s.confirmedRunning?.rateUPerH ?: 0.30, 20))
            assertEquals(schritt(s, sicht, min(10)), schritt(s.copy(), sicht, min(10)), s.toString())
        }
    }

    // =====================================================================
    // P0-2: WAS AAPS AUS DEM KOMMANDO MACHT
    // =====================================================================

    /**
     * PFLICHTTEST A - bis zur LoopPlugin-Semantik.
     *
     * `LoopPlugin.applyAPSRequest`: `abs(rate - baseBasalRate) < basalStep`
     * ruft `cancelTempBasal`. Die Guard-Suche ist am Profilbasal gedeckelt
     * und liefert genau das haeufig.
     */
    @Test
    fun `Profilbasal ist ein ABBRUCH, keine positive TBR`() {
        fun w(rate: Double, dauer: Int = 30, aus: Boolean = true) =
            PartialTbrOwnership.klassifiziere(rate, dauer, profil, schritt, aus)
        assertEquals(Wirkung.CANCEL_TO_PROFILE, w(0.60)) { "exakt Profilbasal" }
        assertEquals(Wirkung.CANCEL_TO_PROFILE, w(0.58)) { "innerhalb eines Basalschritts" }
        assertEquals(Wirkung.CANCEL_TO_PROFILE, w(0.0, 0)) { "der ausdrueckliche Abbruch" }
        assertEquals(Wirkung.SET_PARTIAL, w(0.30))
        // Die Grenze ist die von LoopPlugin: ein GANZER Schritt, strikt.
        assertEquals(Wirkung.SET_PARTIAL, w(profil - schritt))
        assertEquals(Wirkung.CANCEL_TO_PROFILE, w(profil - schritt / 2.0))
    }

    @Test
    fun `ein Abbruch wird nie als erwartete Teilrate gespeichert`() {
        val s = State(confirmedRunning = id(0.30, t0, 30))
        val nach = PartialTbrOwnership.buche(s, Wirkung.CANCEL_TO_PROFILE, 0.60, 30, min(5), schritt)
        assertNull(nach.pendingRequest) { "eine Kennung fuer etwas, das nie laeuft" }
        assertEquals(1, nach.ending?.attempts) { "sondern ein gezaehlter Abbruchversuch" }
    }

    /** PFLICHTTEST B: geschlossenes Aktuationstor. */
    @Test
    fun `ein vom Aktuationstor verworfener Wunsch bucht nichts`() {
        assertEquals(
            Wirkung.NO_REQUEST,
            PartialTbrOwnership.klassifiziere(0.30, 30, profil, schritt, ausgegeben = false),
        )
        val s = State(pendingRequest = id(0.30, t0, 30), pendingAttempts = 1)
        assertEquals(s, PartialTbrOwnership.buche(s, Wirkung.NO_REQUEST, 0.30, 30, min(5), schritt)) {
            "weder setAtTs noch Zaehler duerfen sich bewegen"
        }
    }

    @Test
    fun `unbrauchbare Kommandowerte sind kein Versuch`() {
        for ((was, w) in listOf(
            "keine Rate" to PartialTbrOwnership.klassifiziere(null, 30, profil, schritt, true),
            "keine Dauer" to PartialTbrOwnership.klassifiziere(0.30, null, profil, schritt, true),
            "NaN" to PartialTbrOwnership.klassifiziere(Double.NaN, 30, profil, schritt, true),
            "negativ" to PartialTbrOwnership.klassifiziere(-0.1, 30, profil, schritt, true),
            "kein Profil" to PartialTbrOwnership.klassifiziere(0.30, 30, Double.NaN, schritt, true),
        )) assertEquals(Wirkung.NO_REQUEST, w, was)
    }

    // =====================================================================
    // PFLICHTTEST D: DER SETZVERSUCHSDECKEL, EXAKT
    // =====================================================================

    @Test
    fun `es gibt GENAU SET_MAX_ATTEMPTS Setzversuche, danach bleibt der Zustand und der SMB zu`() {
        assertEquals(3, PartialTbrOwnership.SET_MAX_ATTEMPTS)
        var s = State()
        var versuche = 0
        // Die Pumpe uebernimmt NIE. Ein Zyklus je Minute, 60 Minuten.
        for (m in 0 until 60) {
            val r = schritt(s, auth(null), min(m), wunschRate = 0.30)
            s = if (r.allowSet) {
                versuche++
                PartialTbrOwnership.buche(r.state, Wirkung.SET_PARTIAL, 0.30, 30, min(m), schritt)
            } else r.state
        }
        assertEquals(PartialTbrOwnership.SET_MAX_ATTEMPTS, versuche) { "exakt, nicht hoechstens" }
        assertFalse(s.leer) { "der Zustand bleibt vorhanden" }
        assertTrue(s.smbBlocked) { "und der SMB bleibt zu" }
        assertEquals(Reason.SET_GIVEN_UP, schritt(s, auth(null), min(60), wunschRate = 0.30).reason)
    }

    @Test
    fun `die Bestaetigungsfrist ist der Takt der Setzversuche - ein eigener Backoff waere tot`() {
        var s = PartialTbrOwnership.buche(State(), Wirkung.SET_PARTIAL, 0.30, 30, t0, schritt)
        val zeiten = mutableListOf<Int>()
        for (m in 1 until 40) {
            val r = schritt(s, auth(null), min(m), wunschRate = 0.30)
            s = if (r.allowSet) {
                zeiten += m
                PartialTbrOwnership.buche(r.state, Wirkung.SET_PARTIAL, 0.30, 30, min(m), schritt)
            } else r.state
        }
        assertTrue(zeiten.zipWithNext().all { (a, b) -> b - a >= PartialTbrOwnership.CONFIRM_WINDOW_MIN }) {
            "Versuche: $zeiten"
        }
    }

    // =====================================================================
    // ABBRUCH: BACKOFF UND DECKEL
    // =====================================================================

    @Test
    fun `der Abbruch haelt den Backoff ein und hoert nach dem Deckel auf`() {
        assertEquals(3, PartialTbrOwnership.END_BACKOFF_MIN)
        assertEquals(3, PartialTbrOwnership.END_MAX_ATTEMPTS)
        var s = State(confirmedRunning = id(0.30, t0, 30))
        val gesendet = mutableListOf<Int>()
        for (m in 0 until 25) {
            val r = schritt(s, auth(laufend(0.30, 30 - m)), min(m), wantEnd = true)
            s = r.state
            assertTrue(r.smbBlocked) { "Minute $m: solange etwas laeuft, bleibt der SMB zu" }
            if (r.sendCancel) {
                gesendet += m
                s = PartialTbrOwnership.buche(s, Wirkung.CANCEL_TO_PROFILE, 0.0, 0, min(m), schritt)
            }
        }
        assertEquals(PartialTbrOwnership.END_MAX_ATTEMPTS, gesendet.size) { "gesendet: $gesendet" }
        assertTrue(gesendet.zipWithNext().all { (a, b) -> b - a >= PartialTbrOwnership.END_BACKOFF_MIN }) {
            "gesendet: $gesendet"
        }
    }

    // =====================================================================
    // UNBRAUCHBARE SICHT
    // =====================================================================

    @Test
    fun `eine unbrauchbare Sicht loescht NIEMALS und bricht NIEMALS ab`() {
        listOf(
            State(pendingRequest = id(), pendingAttempts = 1),
            State(confirmedRunning = id()),
            State(confirmedRunning = id(0.85), pendingRequest = id(1.00, min(2)), pendingAttempts = 1),
            State(confirmedRunning = id(), ending = Ending(t0, 1, t0)),
        ).forEach { s ->
            val r = schritt(s, View.Unknown, min(60), wantEnd = true, wunschRate = 0.30)
            assertEquals(Reason.VIEW_UNKNOWN_HELD, r.reason, s.toString())
            assertEquals(s, r.state, "unveraendert gehalten: $s")
            assertTrue(r.smbBlocked, s.toString())
            assertFalse(r.sendCancel, "ohne Sicht wird nichts angefasst: $s")
            assertFalse(r.allowSet, s.toString())
        }
    }

    // =====================================================================
    // FREMDE TBR
    // =====================================================================

    @Test
    fun `eine fremde Absenkung mit gleicher Rate aber unpassender Zeit wird nie angefasst`() {
        val s = State(confirmedRunning = id(0.30, t0, 30))
        val r = schritt(s, auth(laufend(0.30, 5)), min(10), wantEnd = true)
        assertEquals(Reason.CLEARED_CONFIRMED, r.reason) { "unsere ist weg" }
        assertFalse(r.sendCancel) { "und die fremde wird NICHT abgebrochen" }
        assertTrue(r.state.leer)
    }

    @Test
    fun `ein FAKE_EXTENDED ist nie unsere Teilrate`() {
        assertFalse(
            PartialTbrOwnership.matches(
                id(), laufend(0.30, 20, TbrPolicy.SourceType.FAKE_EXTENDED), min(10), schritt
            )
        )
    }

    @Test
    fun `die Toleranz ist unten eng und oben nur so weit wie die Wartezeit`() {
        assertEquals(3, PartialTbrOwnership.REMAINING_TOLERANCE_MIN)
        val i = id()
        assertTrue(PartialTbrOwnership.matches(i, laufend(0.30, 20), min(10), schritt))
        assertTrue(PartialTbrOwnership.matches(i, laufend(0.30, 17), min(10), schritt))
        assertFalse(PartialTbrOwnership.matches(i, laufend(0.30, 16), min(10), schritt))
        assertTrue(PartialTbrOwnership.matches(i, laufend(0.30, 25), min(10), schritt))
        assertFalse(PartialTbrOwnership.matches(i, laufend(0.30, 26), min(10), schritt))
        assertTrue(PartialTbrOwnership.matches(i, laufend(0.30, 30), min(1), schritt))
        assertFalse(PartialTbrOwnership.matches(i, laufend(0.30, 31), min(1), schritt))
    }

    @Test
    fun `eine abweichende Rate ist nie unsere - ein halber Pumpenschritt schon`() {
        assertFalse(PartialTbrOwnership.matches(id(0.30), laufend(0.20, 20), min(10), schritt))
        assertTrue(PartialTbrOwnership.matches(id(0.30), laufend(0.31, 20), min(10), schritt))
    }

    // =====================================================================
    // SETZREGELN BEI OFFENER ANFORDERUNG
    // =====================================================================

    @Test
    fun `bei offener Anforderung gilt - gleiche Rate warten, niedrigere sofort, hoehere nicht`() {
        val s = State(pendingRequest = id(0.30, t0, 30), pendingAttempts = 1)
        assertEquals(Reason.SET_SUPPRESSED_DUPLICATE, schritt(s, auth(null), min(2), wunschRate = 0.30).reason)
        assertFalse(schritt(s, auth(null), min(2), wunschRate = 0.30).allowSet)
        // SICHERER GEHT SOFORT - aber nur, wenn wirklich etwas LAEUFT,
        // das gesenkt werden koennte. Ohne bestaetigte Rate hat ein
        // niedrigerer Wunsch keinen Sicherheitswert und wuerde den
        // Versuchsdeckel aushebeln, sobald die Guard-Rate wandert.
        assertFalse(schritt(s, auth(null), min(2), wunschRate = 0.20).allowSet) {
            "ohne laufende Rate ist auch das nur ein weiterer Versuch"
        }
        // Bestaetigt laeuft 0,30 (Rest passt zur Kennung), offen ist 0,45.
        // Ein Wunsch von 0,20 senkt die tatsaechlich laufende Rate.
        val mitLaufender = State(
            confirmedRunning = id(0.30, t0, 30),
            pendingRequest = id(0.45, min(1), 30),
            pendingAttempts = 1,
        )
        val gesenkt = schritt(mitLaufender, auth(laufend(0.30, 28)), min(2), wunschRate = 0.20)
        assertTrue(gesenkt.allowSet) { "mit laufender Rate geht sicherer sofort" }
        assertEquals(Reason.SET_LOWERED, gesenkt.reason)
        assertFalse(schritt(s, auth(null), min(2), wunschRate = 0.45).allowSet) { "hoeher erst nach Klaerung" }
        assertEquals(Reason.SET_HELD_HIGHER, schritt(s, auth(null), min(2), wunschRate = 0.45).reason)
    }

    @Test
    fun `eine bestaetigte Rate darf erneuert, gesenkt und angehoben werden`() {
        val s = State(confirmedRunning = id(0.30, t0, 30))
        listOf(0.20, 0.30, 0.45).forEach { w ->
            val r = schritt(s, auth(laufend(0.30, 20)), min(10), wunschRate = w)
            assertTrue(r.allowSet) { "Wunsch $w" }
            assertEquals(Reason.CONFIRMED_RUNNING, r.reason)
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
        assertEquals(TbrPolicy.Outcome.Request(0.0, 0), d.outcome)
        assertEquals(TbrPolicy.KEEP_END_OWN_PARTIAL_REASON, d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause)
    }

    @Test
    fun `ein gehaltener Nachweis ohne faelliges Kommando sperrt den SMB trotzdem`() {
        val d = keepMit(laufend(0.30, 20), end = false, held = true)
        assertEquals(TbrPolicy.Outcome.NoRequest, d.outcome)
        assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause)
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
    }

    @Test
    fun `Null und positive TBR behalten ihre eigenen Wege`() {
        assertEquals(TbrPolicy.KEEP_CANCEL_STALE_ZERO_REASON, keepMit(laufend(0.0, 20), false, false).reason)
        assertEquals("KEEP_CANCEL_POSITIVE", keepMit(laufend(1.20, 20), false, false).reason)
    }

    @Test
    fun `PARTIAL nach ZERO ersetzt im SELBEN Zyklus - Schalter aus unter aktivem Latch`() {
        val d = TbrPolicy.decide(TbrPolicy.Intent.SAFETY_ZERO, laufend(0.30, 20), profil, cfg)
        assertEquals(TbrPolicy.Outcome.Request(0.0, cfg.defaultDurationMin), d.outcome)
        assertEquals("SAFETY_ZERO_REPLACE", d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.SAFETY_ZERO, d.smbBlockCause)
    }

    // ---- DERSELBE AUSGANG UNTER NO_POSITIVE ------------------------------
    //
    // Vom E2E-Ausgabetest gefunden: der Besitzausgang lag zuerst NUR im
    // KEEP-Pfad. Nach einer Nullphase steht aber sehr oft NO_NEW_POSITIVE
    // oder CANCEL_TO_SCHEDULED an - und dann fiel eine laufende EIGENE
    // Absenkung durch, lief bis zum Ablauf weiter und der SMB war offen.

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
            assertEquals(TbrPolicy.SmbBlockCause.PARTIAL_ENDING, d.smbBlockCause, was)
            assertEquals("NO_POSITIVE_OWN_PARTIAL_HELD", d.reason, was)
        }
    }

    @Test
    fun `unter NO_POSITIVE bleibt eine FREMDE Absenkung unangetastet`() {
        val d = noPositiveMit(laufend(0.30, 20), end = false, held = false)
        assertEquals("NO_POSITIVE_KEEP_NON_POSITIVE", d.reason)
        assertEquals(TbrPolicy.SmbBlockCause.NONE, d.smbBlockCause) { "C7b bleibt" }
    }

    @Test
    fun `unter NO_POSITIVE wird eine positive TBR weiterhin zuerst abgebrochen`() {
        assertEquals("NO_POSITIVE_CANCEL", noPositiveMit(laufend(1.20, 20), end = false, held = true).reason)
    }

    // =====================================================================
    // INVENTARE
    // =====================================================================

    @Test
    fun `die Blockursachen sind vollstaendig aufgezaehlt`() {
        assertEquals(9, TbrPolicy.SmbBlockCause.entries.size)
        assertTrue(TbrPolicy.SmbBlockCause.entries.contains(TbrPolicy.SmbBlockCause.PARTIAL_ENDING))
    }

    @Test
    fun `die Gruende und Wirkungen sind vollstaendig aufgezaehlt`() {
        assertEquals(15, Reason.entries.size)
        assertEquals(3, Wirkung.entries.size)
    }
}
