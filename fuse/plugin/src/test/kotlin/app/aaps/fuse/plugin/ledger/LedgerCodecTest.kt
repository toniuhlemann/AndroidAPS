package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.ledger.AccountedTreatment
import app.aaps.fuse.core.ledger.AmountStage
import app.aaps.fuse.core.ledger.DeliveryState
import app.aaps.fuse.core.ledger.IobAccountingSnapshot
import app.aaps.fuse.core.ledger.LedgerConfig
import app.aaps.fuse.core.ledger.LedgerError
import app.aaps.fuse.core.ledger.LedgerEvent
import app.aaps.fuse.core.ledger.LedgerReducer
import app.aaps.fuse.core.ledger.LedgerState
import app.aaps.fuse.core.ledger.QueueRejectReason
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * VERLUSTFREIHEIT des Codecs (Audit R95, Fix 3): `decode(encode(s)) == s` als
 * Datenklassen-Gleichheit - geprueft gegen Zustaende aus der KANONISCHEN
 * Ereignissequenz des Reducers (LedgerReducerTest, boundAndAccounted), nicht
 * gegen handgebaute Objekte: ein handgebauter Zustand koennte genau das Feld
 * auslassen, das der Reducer als naechstes braucht.
 *
 * Round-Trip laeuft durch die TEXTFORM (`toString` -> `JSONObject`), denn
 * genau die liegt auf der Platte.
 */
class LedgerCodecTest {

    private val cfg = LedgerConfig(bolusStepU = 0.05)
    private val t0 = 1_700_000_000_000L
    private val id = "fuse-1#7"

    /** Die ganze Kette bis zum Pumpenkommando - wie im Reducer-Test. */
    private fun throughPump(u: Double = 0.30) = listOf(
        LedgerEvent.Proposed(id, u, decisionTs = t0, latestBolusTimestamp = t0 - 600_000L),
        LedgerEvent.AmountObserved(id, AmountStage.RT_PUBLISHED, u),
        LedgerEvent.AmountObserved(id, AmountStage.LOOP_CONSTRAINED, u),
        LedgerEvent.AmountObserved(id, AmountStage.QUEUE_CONSTRAINED, u),
        LedgerEvent.QueueAccepted(id),
        LedgerEvent.AmountObserved(id, AmountStage.PUMP_COMMAND, u),
    )

    private fun roundTrip(s: LedgerState): LedgerState =
        LedgerCodec.decodeState(JSONObject(LedgerCodec.encodeState(s).toString()))

    @Test
    fun `leerer Zustand`() {
        val s = LedgerState()
        assertEquals(s, roundTrip(s))
    }

    /** Die kanonische Sequenz: gebunden und gebucht - inkl. Snapshot-Ordnung,
     *  Epochs, Identitaet und den je Zeile gepinnten Policies. */
    @Test
    fun `gebunden und gebucht - alle Felder ueberleben`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot(
                        "h1", "c", t0, 1L,
                        listOf(AccountedTreatment(null, 4711L, 0.30)),
                        sourceEpochId = "epoch-test",
                    )
                ),
            ),
            cfg,
        )
        val back = roundTrip(s)
        assertEquals(s, back)
        // Stichproben gegen "gleich, weil beide leer":
        assertEquals(0.0, back.transportCommitmentU, 1e-12)
        assertNotNull(back.entries.getValue(id).identity)
        assertEquals("epoch-test", back.lastSnapshotOrder?.sourceEpochId)
        assertEquals("h1", back.lastSnapshotViewHash)
        assertTrue("epoch-test" in back.seenEpochs)
        assertEquals(0.05, back.entries.getValue(id).bolusStepU, 0.0)
        assertEquals(1e-9, back.entries.getValue(id).amountEpsU, 0.0)
    }

    /** Teilbuchung: der offene Rest muss EXAKT ueberleben - er geht nach dem
     *  Neustart direkt in die Headrooms der Kandidatensuche. */
    @Test
    fun `Teilbuchung behaelt den offenen Rest exakt`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot(
                        "h1", "c", t0, 1L,
                        listOf(AccountedTreatment(null, 4711L, 0.10)),
                        sourceEpochId = "epoch-test",
                    )
                ),
            ),
            cfg,
        )
        assertEquals(0.20, s.transportCommitmentU, 1e-12)
        val back = roundTrip(s)
        assertEquals(s, back)
        assertEquals(0.20, back.transportCommitmentU, 1e-12)
    }

    /** Widerspruchspfad: contradicted + conservativeFloorU + Fehlerhistorie
     *  mit Hold-Generation. */
    @Test
    fun `Widerspruch mit conservativeFloor und Fehlerhistorie ueberlebt`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed(id, 0.30, t0, t0 - 600_000L),
                LedgerEvent.AmountObserved(id, AmountStage.RT_PUBLISHED, 0.30),
                LedgerEvent.QueueRejected(id, QueueRejectReason.GATE_BLOCKED),
                // Nach dem Reject laeuft die Kette weiter -> PHASE_VIOLATION,
                // contradicted, conservativeFloorU = letzte bekannte Menge.
                LedgerEvent.AmountObserved(id, AmountStage.PUMP_COMMAND, 0.30),
            ),
            cfg,
        )
        val e = s.entries.getValue(id)
        assertTrue(e.contradicted)
        assertEquals(0.30, e.conservativeFloorU!!, 1e-12)
        assertTrue(s.holdActuation)
        assertTrue(s.holdGeneration > 0L)

        val back = roundTrip(s)
        assertEquals(s, back)
        assertTrue(back.holdActuation)
    }

    /** Quittierte Fehler: active=false + resolved*-Felder muessen erhalten
     *  bleiben - sonst waere die Unterschrift nach dem Neustart weg und der
     *  Hold faelschlich wieder aktiv. */
    @Test
    fun `eine Quittung ueberlebt den Round-Trip`() {
        val before = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed(id, 0.30, t0, t0 - 600_000L),
                LedgerEvent.ProposalIdLost(id, "queue"),
            ),
            cfg,
        )
        assertTrue(before.holdActuation)
        val acked = LedgerReducer.reduce(
            before,
            LedgerEvent.HoldAcknowledged(
                id, "tester", "known transient", before.holdGeneration,
                setOf(LedgerError.PROPOSAL_ID_LOST),
            ),
            cfg,
        )
        org.junit.jupiter.api.Assertions.assertFalse(acked.holdActuation)

        val back = roundTrip(acked)
        assertEquals(acked, back)
        org.junit.jupiter.api.Assertions.assertFalse(back.holdActuation)
        val rec = back.errors.first { it.error == LedgerError.PROPOSAL_ID_LOST }
        assertEquals("tester", rec.resolvedBy)
        assertEquals("known transient", rec.resolvedReason)
    }

    /** Angekuendigter Epochwechsel + Neustart-Delivery: announcedEpochId und
     *  UNKNOWN_ASSUMED muessen ueberleben. */
    @Test
    fun `Epoch-Ankuendigung und Neustartzustand ueberleben`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h1", "c", t0, 1L, emptyList(), sourceEpochId = "epoch-a")
                ),
                LedgerEvent.SnapshotSourceRestarted("epoch-a", "epoch-b", "process restart"),
                LedgerEvent.RestartObserved(t0 + 60_000L),
            ),
            cfg,
        )
        assertEquals("epoch-b", s.announcedEpochId)
        assertEquals(DeliveryState.UNKNOWN_ASSUMED, s.entries.getValue(id).delivery)

        val back = roundTrip(s)
        assertEquals(s, back)
        assertEquals("epoch-b", back.announcedEpochId)
    }

    /** Das Gesamtobjekt: state + Episodenbudgets + Revision. */
    @Test
    fun `Gesamtdatei mit Episodenbudgets und Revision`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val ep = EpisodeBudgets().apply {
            primeSpentU = 0.45
            primeArmedTs = t0
            onsetSpentU = 0.10
            onsetQuietMin = 3
            mealArmedTs = t0
            mealDeliveries.addLast(t0 + 60_000L to 0.15)
            mealDeliveries.addLast(t0 + 120_000L to 0.30)
        }
        val decoded = LedgerCodec.decode(JSONObject(LedgerCodec.encode(state, ep, 42L).toString()))
        assertEquals(state, decoded.state)
        assertEquals(42L, decoded.revision)
        assertEquals(0.45, decoded.episodes.primeSpentU, 0.0)
        assertEquals(t0, decoded.episodes.primeArmedTs)
        assertEquals(0.10, decoded.episodes.onsetSpentU, 0.0)
        assertEquals(3, decoded.episodes.onsetQuietMin)
        assertEquals(t0, decoded.episodes.mealArmedTs)
        assertEquals(listOf(t0 + 60_000L to 0.15, t0 + 120_000L to 0.30), decoded.episodes.mealDeliveries.toList())
    }

    // ---- Semantische Decode-Validierung (Audit 2d273cb, REG-01d) ----------

    /** Negativer Verbrauch hiesse "Huelle groesser als konfiguriert" - genau
     *  der Korruptions-/Manipulationspfad, der werfen MUSS (Hold statt
     *  Uebernahme). */
    @Test
    fun `negatives primeSpentU wirft beim Decode`() {
        val o = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        o.getJSONObject("episodes").put("primeSpentU", -1.0)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    /** 99 U in einer Mengenstufe kann keine selbstgeschriebene Datei tragen
     *  (Hardlimits liegen weit darunter) - Befund, kein Buchungsstoff. */
    @Test
    fun `Menge 99 in der Mengenachse wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .getJSONObject("amounts").put("proposedU", 99.0)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    @Test
    fun `mealDeliveries mit 501 Eintraegen wirft beim Decode`() {
        val ep = EpisodeBudgets()
        repeat(501) { ep.mealDeliveries.addLast((t0 + it) to 0.1) }
        val o = LedgerCodec.encode(LedgerState(), ep, 0L)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    @Test
    fun `negative revision wirft beim Decode`() {
        val o = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L).put("revision", -1L)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    // ---- Ganzheitlicher State-Validator (Re-Audit c750169, REG-04/6.2) ----

    /** DER Re-Audit-Repro: zwei Eintraege derselben Id - vorher faltete
     *  associateBy still last-win, und ein spaeterer unbewiesener Eintrag
     *  konnte einen frueheren offenen ueberschreiben. Jetzt: Wurf (-> beim
     *  Laden invalid -> Hold statt Uebernahme). */
    @Test
    fun `doppelte proposalId wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        val entries = o.getJSONObject("state").getJSONArray("entries")
        entries.put(JSONObject(entries.getJSONObject(0).toString()))
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    @Test
    fun `leere proposalId wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0).put("proposalId", "")
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    /** CONFIRMED_ZERO ohne persistierten Nachweis (provenDeliveredU) kann
     *  keine eigene Datei tragen - der Reducer setzt den Wert nur in
     *  onDeliveryProven, und der schreibt IMMER die bewiesene Menge. */
    @Test
    fun `CONFIRMED_ZERO ohne Nachweis wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .put("delivery", DeliveryState.CONFIRMED_ZERO.name)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    /** Gegenprobe: ein ECHTER Nullnachweis (DeliveryProven 0.0) ist gueltig
     *  und ueberlebt den Round-Trip unveraendert. */
    @Test
    fun `CONFIRMED_ZERO mit Nachweis passiert unveraendert`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.DeliveryProven(id, 0.0, "pump history")),
            cfg,
        )
        assertEquals(DeliveryState.CONFIRMED_ZERO, s.entries.getValue(id).delivery)
        val back = roundTrip(s)
        assertEquals(s, back)
        assertEquals(0.0, back.entries.getValue(id).amounts.provenDeliveredU!!, 0.0)
    }

    /** Die gepinnten Policies muessen STRIKT positiv sein: mit 0 entarten
     *  Mengenvergleich bzw. Tick-Kanonisierung erst zur Laufzeit. */
    @Test
    fun `amountEpsU 0 wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0).put("amountEpsU", 0.0)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    @Test
    fun `bolusStepU 0 wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0).put("bolusStepU", 0.0)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    /** Eine Identitaet, die zu einer ANDEREN Zeile gehoert, ist ein kopierter
     *  Nachweis - kein Zustand, den dieser Code je schreibt. */
    @Test
    fun `fremde identity-proposalId wirft beim Decode`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val o = LedgerCodec.encode(s, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .getJSONObject("identity").put("proposalId", "fremd#1")
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    /** Nicht-monotone Mengenachse OHNE den zugehoerigen Befund: Fremdinhalt,
     *  wirft. MIT Befund (der Reducer persistiert die verletzte Kette als
     *  failClosed-Beweisstueck) ist sie gueltiger Zustand und ueberlebt. */
    @Test
    fun `Kettenverletzung nur mit Befund gueltig`() {
        // Ohne Befund: rtPublishedU groesser als proposedU hochgetampert.
        val clean = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(clean, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .getJSONObject("amounts").put("rtPublishedU", 0.40)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }

        // Mit Befund: der Reducer selbst hat die Verletzung erlebt und als
        // CONSTRAINT_CHAIN_INVALID an der Zeile festgehalten.
        val flagged = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed(id, 0.30, t0, t0 - 600_000L),
                LedgerEvent.AmountObserved(id, AmountStage.RT_PUBLISHED, 0.30),
                LedgerEvent.AmountObserved(id, AmountStage.LOOP_CONSTRAINED, 0.40),
            ),
            cfg,
        )
        assertTrue(LedgerError.CONSTRAINT_CHAIN_INVALID in flagged.entries.getValue(id).errors)
        assertEquals(flagged, roundTrip(flagged))
    }

    // ---- R4-02: Validator als Zustandsmaschine ----------------------------

    /** DER Codex-Repro (R4-02, Fault-Matrix): PUBLISHED + wirksamer
     *  queueReject + KEIN Liefersignal. Der Reducer kann das nie schreiben
     *  (onQueueRejected setzt IMMER phase=TERMINAL) - eine Datei, die es
     *  traegt, setzt ein offenes Commitment ohne Beweis auf 0
     *  (debtReleaseEffective). ROT vor dem Fix: der Decode liess sie durch. */
    @Test
    fun `PUBLISHED mit queueReject ohne Liefersignal wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed(id, 0.30, t0, t0 - 600_000L),
                LedgerEvent.AmountObserved(id, AmountStage.RT_PUBLISHED, 0.30),
            ),
            cfg,
        )
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .put("queueReject", QueueRejectReason.GATE_BLOCKED.name)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    /** Zweiter Codex-Repro: PUBLISHED + withdrawnProven. Ein Rueckzug
     *  existiert nur aus QUEUE_ACCEPTED heraus und endet TERMINAL. */
    @Test
    fun `PUBLISHED mit withdrawnProven ohne Liefersignal wirft beim Decode`() {
        val state = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed(id, 0.30, t0, t0 - 600_000L),
                LedgerEvent.AmountObserved(id, AmountStage.RT_PUBLISHED, 0.30),
            ),
            cfg,
        )
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .put("withdrawnProven", true)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }
    }

    /** Gegenprobe: der LEGITIME Reject (vor jeder Annahme, Reducer-erzeugt)
     *  bleibt gueltig, sein Debt-Release bleibt wirksam. */
    @Test
    fun `legitimer Reject vor der Annahme bleibt gueltig`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed(id, 0.30, t0, t0 - 600_000L),
                LedgerEvent.AmountObserved(id, AmountStage.RT_PUBLISHED, 0.30),
                LedgerEvent.QueueRejected(id, QueueRejectReason.CONSTRAINT_ZERO),
            ),
            cfg,
        )
        assertEquals(0.0, s.transportCommitmentU, 1e-12)
        val back = roundTrip(s)
        assertEquals(s, back)
        assertEquals(0.0, back.transportCommitmentU, 1e-12)
    }

    /** Gegenprobe: der LEGITIME Rueckzug (aus QUEUE_ACCEPTED, Reducer-erzeugt)
     *  bleibt gueltig - auch mit spaeterem Nullnachweis. */
    @Test
    fun `legitimer Rueckzug nach der Annahme bleibt gueltig`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                LedgerEvent.Proposed(id, 0.30, t0, t0 - 600_000L),
                LedgerEvent.AmountObserved(id, AmountStage.RT_PUBLISHED, 0.30),
                LedgerEvent.AmountObserved(id, AmountStage.QUEUE_CONSTRAINED, 0.30),
                LedgerEvent.QueueAccepted(id),
                LedgerEvent.QueueWithdrawnProven(id, "cancelAllBoluses vor Start"),
                LedgerEvent.DeliveryProven(id, 0.0, "pump history: nichts geflossen"),
            ),
            cfg,
        )
        assertEquals(0.0, s.transportCommitmentU, 1e-12)
        assertEquals(s, roundTrip(s))
    }

    /** Timestamps (R4-02 c): decisionTs 0 kann keine eigene Datei tragen
     *  (Wanduhrzeit der Entscheidung) - ab Schemaversion 2 strikt, als
     *  Version 1 weiter tolerant (Bestandsdateien bleiben ladbar). */
    @Test
    fun `decisionTs 0 wirft ab Schemaversion 2 und passiert als Version 1`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0).put("decisionTs", 0L)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }

        val v1 = JSONObject(o.toString()).put("v", 1)
        LedgerCodec.decode(v1) // Legacy-Toleranz: wirft nicht
    }

    /** Timestamps (R4-02 c): die gebundene Behandlungszeit liegt nie WEIT vor
     *  der Entscheidung - die Bindung verlangte timestamp >= decisionTs, und
     *  die pumpenbestaetigte Korrektur ist durch den Uhrenversatz begrenzt.
     *  Genau an der Toleranzgrenze bleibt gueltig, dahinter wirft es. */
    @Test
    fun `treatmentTimestamp weit vor decisionTs wirft ab Schemaversion 2`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val o = LedgerCodec.encode(s, EpisodeBudgets(), 1L)
        val tol = LedgerStateValidator.TREATMENT_BEFORE_DECISION_TOLERANCE_MS

        val boundary = JSONObject(o.toString())
        boundary.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .getJSONObject("identity").put("treatmentTimestamp", t0 - tol)
        LedgerCodec.decode(boundary) // exakt an der Grenze: gueltig

        val beyond = JSONObject(o.toString())
        beyond.getJSONObject("state").getJSONArray("entries").getJSONObject(0)
            .getJSONObject("identity").put("treatmentTimestamp", t0 - tol - 1L)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(beyond) }

        val v1 = JSONObject(beyond.toString()).put("v", 1)
        LedgerCodec.decode(v1) // Legacy-Toleranz: wirft nicht
    }

    /** R4-02 (d): failClosed ohne persistierten Befund ist Fremdinhalt - der
     *  Reducer schreibt den Latch NUR zusammen mit einem aktiven
     *  Fehlereintrag derselben Zeile. Ab Schemaversion 2 strikt. */
    @Test
    fun `failClosed ohne aktiven Fehlereintrag wirft ab Schemaversion 2`() {
        val state = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val o = LedgerCodec.encode(state, EpisodeBudgets(), 1L)
        o.getJSONObject("state").getJSONArray("entries").getJSONObject(0).put("failClosed", true)
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(o.toString())) }

        val v1 = JSONObject(o.toString()).put("v", 1)
        LedgerCodec.decode(v1) // Legacy-Toleranz: wirft nicht
    }

    // ---- Schemaversionierung (R4-02) --------------------------------------

    /** Neue Dateien tragen Version 2; Version 1 (Bestand) bleibt ladbar;
     *  eine UNBEKANNTE Zukunftsversion wirft weiterhin (Hold statt raten). */
    @Test
    fun `Schemaversion 3 wird geschrieben, aeltere bleiben lesbar, 4 wirft`() {
        val o = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        assertEquals(3, o.getInt("v"))

        // Alte Versionen bleiben LESBAR. Ob ihr Inhalt uebernommen werden darf,
        // entscheidet `migrationRequired` - Lesbarkeit und Uebernehmbarkeit
        // sind seit v3 zwei verschiedene Fragen.
        LedgerCodec.decode(JSONObject(o.toString()).put("v", 1))
        LedgerCodec.decode(JSONObject(o.toString()).put("v", 2))
        assertThrows(IllegalArgumentException::class.java) {
            LedgerCodec.decode(JSONObject(o.toString()).put("v", 4))
        }
    }

    /**
     * PUNKT 9: die MIGRATION ist konservativ und beweisbar.
     *
     * Zu fuellen ist genau `lastPositiveFactTs`, und die Regel folgt der
     * Beweislage der Altzeile:
     *
     *  - GEBUCHTE Zeile: einen positiven Fakt hat es gegeben, nur ist unbekannt
     *    wann. Der spaetestmoegliche Zeitpunkt ist jetzt - das verlaengert die
     *    Wirkfrist maximal und macht die Haftung nie kleiner.
     *  - UNGEBUCHTE Zeile: es gab nie einen positiven Fakt. `null` ist die
     *    WAHRE Aussage; die Frist laeuft ab `decisionTs`, wie ohne Migration.
     *
     * Beides ist eine reine Funktion des Altzustands - es wird nichts geraten.
     */
    @Test
    fun `die Migration fuellt nur, was die Altzeile belegt`() {
        val jetzt = t0 + 5 * 3600_000L

        // (1) Ungebuchte Zeile: bleibt null - es gab nie einen positiven Fakt.
        val offen = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(LedgerEvent.Proposed("p1", 0.30, decisionTs = t0, latestBolusTimestamp = t0)),
            cfg,
        )
        val m1 = LedgerCodec.migrateToCurrent(offen, jetzt)
        assertNull(m1.entries.getValue("p1").lastPositiveFactTs) {
            "ohne Buchung gab es nie einen positiven Fakt - null ist die wahre Aussage"
        }

        // (2) Gebuchte Zeile: bekommt den spaetestmoeglichen Zeitpunkt.
        val gebucht = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot(
                        "h1", "c", t0, 1L,
                        listOf(AccountedTreatment(null, 4711L, 0.30, treatmentTs = t0)),
                        sourceEpochId = "epoch-test",
                    )
                ),
            ),
            cfg,
        )
        // Feld kuenstlich leeren - so saehe die Zeile aus einer v2-Datei aus.
        val alsAlt = gebucht.copy(
            entries = gebucht.entries.mapValues { (_, e) -> e.copy(lastPositiveFactTs = null) }
        )
        val m2 = LedgerCodec.migrateToCurrent(alsAlt, jetzt)
        assertEquals(jetzt, m2.entries.getValue(id).lastPositiveFactTs) {
            "der Fakt existierte - unbekannt ist nur wann; spaetestmoeglich ist konservativ"
        }

        // (3) IDEMPOTENT: ein zweiter Lauf aendert nichts mehr.
        assertEquals(m2, LedgerCodec.migrateToCurrent(m2, jetzt + 3600_000L))
    }

    /**
     * DER P0-FALL (Codex-Re-Review 10.08.): der Fakt WAR da und ist wieder
     * verschwunden.
     *
     * Der Reducer nimmt die Buchung zurueck, wenn ein zuvor nachgewiesener
     * Fakt aus der Vollsicht faellt (R91-F1) - `accountedAmountU` steht dann
     * auf 0. Die HISTORISCHE Provenienz (`firstAccountedSnapshotHash`) bleibt
     * aber stehen, und genau sie ist der Beweis, dass es ihn gab.
     *
     * Der erste Migrationsanlauf las den aktuellen Stand und haette dieser
     * Zeile `null` gegeben - ihre Wirkfrist waere ab `decisionTs` gelaufen und
     * moeglicherweise zu frueh abgelaufen.
     */
    @Test
    fun `ein verschwundener Fakt zaehlt bei der Migration weiter`() {
        val jetzt = t0 + 5 * 3600_000L
        val gebucht = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot(
                        "h1", "c", t0, 1L,
                        listOf(AccountedTreatment(null, 4711L, 0.30, treatmentTs = t0)),
                        sourceEpochId = "epoch-test",
                    )
                ),
                // ... und im naechsten Zyklus ist er WEG.
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h2", "c", t0 + 60_000L, 2L, emptyList(), sourceEpochId = "epoch-test")
                ),
            ),
            cfg,
        )
        val e = gebucht.entries.getValue(id)
        assertEquals(0.0, e.accountedAmountU!!, 1e-12) { "Ausgangslage: die Buchung ist zurueckgenommen" }
        assertNotNull(e.firstAccountedSnapshotHash) { "aber die Provenienz steht" }

        // So saehe die Zeile aus einer v2-Datei aus.
        val alsAlt = gebucht.copy(
            entries = gebucht.entries.mapValues { (_, x) -> x.copy(lastPositiveFactTs = null) }
        )
        assertEquals(jetzt, LedgerCodec.migrateToCurrent(alsAlt, jetzt).entries.getValue(id).lastPositiveFactTs) {
            "der Fakt EXISTIERTE - die Migration muss konservativ datieren, nicht auf null lassen"
        }
    }

    /** Die Haftung darf durch die Migration NIE kleiner werden - das ist die
     *  Eigenschaft, wegen der es den Hold ueberhaupt gab. */
    @Test
    fun `die Migration senkt die offene Haftung nicht`() {
        val offen = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(LedgerEvent.Proposed("p1", 0.30, decisionTs = t0, latestBolusTimestamp = t0)),
            cfg,
        )
        val vorher = offen.transportCommitmentU
        val nachher = LedgerCodec.migrateToCurrent(offen, t0 + 3600_000L).transportCommitmentU
        assertTrue(nachher >= vorher) { "vorher=$vorher nachher=$nachher" }
    }

    /**
     * P0-B (Codex-Re-Review 09.08.): eine aeltere Generation MIT Zeilen ist
     * lesbar, aber nicht uebernehmbar.
     *
     * Unter v3 fehlt `lastPositiveFactTs`, und sein Fehlen sieht beim Lesen
     * genauso aus wie ein gueltiges `null` - waehrend beides das Gegenteil
     * bedeutet. Der erste Anlauf hatte diesen Schutz nur im Kommentar
     * behauptet; jetzt gibt es ihn.
     */
    @Test
    fun `eine aeltere Generation mit Zeilen verlangt eine Migration`() {
        val mitZeile = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(LedgerEvent.Proposed("p1", 0.30, decisionTs = t0, latestBolusTimestamp = t0)),
            LedgerConfig(bolusStepU = 0.05),
        )
        val v3 = LedgerCodec.encode(mitZeile, EpisodeBudgets(), 1L)

        assertNull(LedgerCodec.decode(JSONObject(v3.toString())).migrationRequired) {
            "die frisch geschriebene v3-Generation ist uebernehmbar"
        }
        assertNotNull(LedgerCodec.decode(JSONObject(v3.toString()).put("v", 2)).migrationRequired) {
            "dieselbe Generation als v2 gilt als migrationsbeduerftig"
        }

        val leerAlt = JSONObject(LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L).toString()).put("v", 2)
        assertNull(LedgerCodec.decode(leerAlt).migrationRequired) {
            "eine LEERE Altgeneration hat nichts zu verlieren und migriert still"
        }
    }

    /**
     * V3 IST NICHT TOLERANT (Codex-Re-Review 09.08., Finding 2).
     *
     * Der Migrations-Hold griff nur fuer `v < 3`. Fehlte ein Pflichtfeld in
     * einer Datei, die sich als v3 ausgibt, wurde es weiterhin still als
     * "kein Wert" gelesen - also derselbe Fehler wie beim Altbestand, nur ohne
     * dessen Entschuldigung.
     *
     * Der Encoder schreibt alle drei Felder IMMER, notfalls als
     * `JSONObject.NULL`. Ihr Fehlen ist deshalb kein legitimer Zustand,
     * sondern eine beschaedigte oder fremde Generation - und muss werfen,
     * damit der Adapter in den Recovery-Hold geht statt zu raten.
     */
    @Test
    fun `eine v3-Datei ohne Pflichtfelder wirft`() {
        val mitZeile = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(LedgerEvent.Proposed("p1", 0.30, decisionTs = t0, latestBolusTimestamp = t0)),
            LedgerConfig(bolusStepU = 0.05),
        )
        val voll = LedgerCodec.encode(mitZeile, EpisodeBudgets(), 1L)
        // Ausgangslage: vollstaendig laedt sie.
        LedgerCodec.decode(JSONObject(voll.toString()))

        // 1) lastPositiveFactTs aus der Zeile entfernen
        val ohneFakt = JSONObject(voll.toString())
        ohneFakt.getJSONObject("state").getJSONArray("entries").getJSONObject(0).remove("lastPositiveFactTs")
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(ohneFakt) }

        // 2) retiredBoundIds entfernen - die leere Menge waere hier die
        //    gefaehrliche Deutung: eine verbrauchte Identitaet duerfte wieder
        //    binden.
        val ohneRetired = JSONObject(voll.toString()).also { it.remove("retiredBoundIds") }
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(ohneRetired) }

        // 3) lastAcceptedSourceTs entfernen - 0L waere "noch kein Punkt
        //    akzeptiert" und entschaerft den Restart-Dedupe lautlos.
        val ohneCursor = JSONObject(voll.toString())
        ohneCursor.getJSONObject("episodes").remove("lastAcceptedSourceTs")
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(ohneCursor) }
    }

    /** Abstimmung mit der parallelen Sitzung: der Schreiber kappt
     *  mealDeliveries kuenftig auf 400 - die Validator-Grenze bleibt bei
     *  500. Eine 400er-Datei ist gueltig, 501 wirft (Test oben). */
    @Test
    fun `mealDeliveries mit 400 Eintraegen bleibt gueltig`() {
        val ep = EpisodeBudgets()
        repeat(400) { ep.mealDeliveries.addLast((t0 + it) to 0.1) }
        val decoded = LedgerCodec.decode(JSONObject(LedgerCodec.encode(LedgerState(), ep, 0L).toString()))
        assertEquals(400, decoded.episodes.mealDeliveries.size)
    }

    // ---- Neue persistierte Felder -----------------------------------------

    /**
     * lastAcceptedSourceTs: Round-Trip, und ab v3 PRAESENZPFLICHTIG.
     *
     * UMGEKEHRT gegenueber Fix 5 (Codex-Re-Review Finding 2). Die alte
     * Toleranz las ein fehlendes Feld als 0 - "noch kein Punkt akzeptiert" -
     * und entschaerfte damit den Restart-Dedupe lautlos. In einer v3-Datei
     * kann das Feld nicht fehlen, der Encoder schreibt es immer; sein Fehlen
     * ist Korruption. Fuer echten Altbestand greift der Migrations-Hold.
     */
    @Test
    fun `lastAcceptedSourceTs ueberlebt den Round-Trip und ist ab v3 pflicht`() {
        val ep = EpisodeBudgets().apply { lastAcceptedSourceTs = t0 }
        val decoded = LedgerCodec.decode(JSONObject(LedgerCodec.encode(LedgerState(), ep, 0L).toString()))
        assertEquals(t0, decoded.episodes.lastAcceptedSourceTs)

        val alt = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        alt.getJSONObject("episodes").remove("lastAcceptedSourceTs")
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(alt.toString())) }

        // Als v1 gelesen gilt die alte Toleranz weiter.
        assertEquals(0L, LedgerCodec.decode(JSONObject(alt.toString()).put("v", 1)).episodes.lastAcceptedSourceTs)
    }

    /** proposalPumpEpochs (Fix 3, Re-Audit 6.3): Round-Trip, Altdatei ohne
     *  Feld liest sich als "keine Pinnung", Duplikate werfen. */
    @Test
    fun `proposalPumpEpochs ueberleben den Round-Trip`() {
        val epochs = mapOf(
            "p1" to ProposalPumpEpoch("GENERIC_AAPS", "hash1"),
            "p2" to ProposalPumpEpoch("DANA_R", null),
        )
        val decoded = LedgerCodec.decode(
            JSONObject(LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L, emptyList(), epochs).toString())
        )
        assertEquals(epochs, decoded.pumpEpochs)

        val alt = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        alt.remove("proposalPumpEpochs")
        assertTrue(LedgerCodec.decode(JSONObject(alt.toString())).pumpEpochs.isEmpty())

        val dup = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L, emptyList(), mapOf("p1" to ProposalPumpEpoch("X", null)))
        val arr = dup.getJSONArray("proposalPumpEpochs")
        arr.put(JSONObject(arr.getJSONObject(0).toString()))
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(dup.toString())) }
    }

    /** markerRiseSeen (Fix-Pass 2 Nr. 4): Round-Trip plus Altdatei-Toleranz
     *  (fehlendes Feld liest sich als false - die konservative Richtung). */
    @Test
    fun `markerRiseSeen ueberlebt den Round-Trip und fehlt tolerant`() {
        val ep = EpisodeBudgets().apply { markerRiseSeen = true }
        val decoded = LedgerCodec.decode(JSONObject(LedgerCodec.encode(LedgerState(), ep, 0L).toString()))
        assertTrue(decoded.episodes.markerRiseSeen)

        val alt = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        alt.getJSONObject("episodes").remove("markerRiseSeen")
        assertFalse(LedgerCodec.decode(JSONObject(alt.toString())).episodes.markerRiseSeen)
    }

    /** retiredBoundIds (Fix 6): Round-Trip, Kappung auf die juengsten 300,
     *  Altdatei ohne Feld liest sich als leer. */
    @Test
    fun `retiredBoundIds ueberleben den Round-Trip gekappt auf 300`() {
        val retired = (1..350).map { RetiredBoundId(temporaryId = it.toLong(), pumpId = 1000L + it) }
        val decoded = LedgerCodec.decode(
            JSONObject(LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L, retired).toString())
        )
        assertEquals(300, decoded.retiredBoundIds.size)
        // Die JUENGSTEN 300 bleiben: 51..350.
        assertEquals(51L, decoded.retiredBoundIds.first().temporaryId)
        assertEquals(350L, decoded.retiredBoundIds.last().temporaryId)
        assertEquals(1350L, decoded.retiredBoundIds.last().pumpId)

        // UMGEKEHRT (Codex-Re-Review Finding 2). Vorher stand hier die
        // Altdatei-Toleranz "fehlendes Feld liest sich als leere Menge". Ab v3
        // ist das Feld praesenzpflichtig: die leere Menge ist hier die
        // GEFAEHRLICHE Deutung, weil eine verbrauchte Bindungsidentitaet damit
        // wieder binden duerfte. Fuer echten Altbestand greift stattdessen der
        // Migrations-Hold - der laesst die Datei gar nicht erst herein.
        val alt = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        alt.remove("retiredBoundIds")
        assertThrows(IllegalArgumentException::class.java) { LedgerCodec.decode(JSONObject(alt.toString())) }

        // Als v1 gelesen bleibt die alte Toleranz gueltig - dort ist das Feld
        // wirklich nie geschrieben worden.
        val alsV1 = JSONObject(alt.toString()).put("v", 1)
        assertTrue(LedgerCodec.decode(alsV1).retiredBoundIds.isEmpty())
    }
}
