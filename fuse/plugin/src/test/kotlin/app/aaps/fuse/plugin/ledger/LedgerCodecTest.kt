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

    // ---- Neue persistierte Felder -----------------------------------------

    /** lastAcceptedSourceTs (Fix 5, Re-Audit 6.5): Round-Trip plus
     *  Altdatei-Toleranz (fehlendes Feld liest sich als 0 - "noch kein
     *  Punkt akzeptiert"). */
    @Test
    fun `lastAcceptedSourceTs ueberlebt den Round-Trip und fehlt tolerant`() {
        val ep = EpisodeBudgets().apply { lastAcceptedSourceTs = t0 }
        val decoded = LedgerCodec.decode(JSONObject(LedgerCodec.encode(LedgerState(), ep, 0L).toString()))
        assertEquals(t0, decoded.episodes.lastAcceptedSourceTs)

        val alt = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        alt.getJSONObject("episodes").remove("lastAcceptedSourceTs")
        assertEquals(0L, LedgerCodec.decode(JSONObject(alt.toString())).episodes.lastAcceptedSourceTs)
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

        val alt = LedgerCodec.encode(LedgerState(), EpisodeBudgets(), 0L)
        alt.remove("retiredBoundIds")
        assertTrue(LedgerCodec.decode(JSONObject(alt.toString())).retiredBoundIds.isEmpty())
    }
}
