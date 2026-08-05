package app.aaps.fuse.core.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KC2-06..10, 17..24, 32..35, 39..47, 52, 54..59, 63, 64.
 *
 * Der Reducer ist die Stelle, an der die Medtrum-Falle wehtut: `success=true,
 * enacted=false, bolusDelivered=0,25` ist eine VOLLE Abgabe. Wer `enacted`
 * allein liest, bucht sie aus und gibt sie im naechsten Zyklus erneut frei.
 */
class LedgerReducerTest {

    private val cfg = LedgerConfig(bolusStepU = 0.05)
    private val t0 = 1_700_000_000_000L
    private val id = "p1"

    private fun proposed(u: Double = 0.30, pid: String = id) =
        LedgerEvent.Proposed(pid, u, decisionTs = t0, latestBolusTimestamp = t0 - 600_000L)

    private fun amount(stage: AmountStage, u: Double, pid: String = id) =
        LedgerEvent.AmountObserved(pid, stage, u)

    private fun run(vararg events: LedgerEvent): LedgerState =
        LedgerReducer.reduceAll(LedgerState(), events.toList(), cfg)

    private fun entry(s: LedgerState, pid: String = id) = s.entries.getValue(pid)

    /** Die ganze Kette bis zum Pumpenkommando, ohne Terminalereignis. */
    private fun throughPump(u: Double = 0.30, pid: String = id) = listOf(
        proposed(u, pid),
        amount(AmountStage.RT_PUBLISHED, u, pid),
        amount(AmountStage.LOOP_CONSTRAINED, u, pid),
        amount(AmountStage.QUEUE_CONSTRAINED, u, pid),
        LedgerEvent.QueueAccepted(pid),
        amount(AmountStage.PUMP_COMMAND, u, pid),
    )

    // ---- Commitment und Budget -------------------------------------------

    @Test
    fun `KC2-07 ein erst vorgeschlagener SMB zaehlt sofort voll in die Schuld`() {
        val s = run(proposed(0.30))
        assertEquals(0.30, s.transportCommitmentU, 1e-12)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(s).accounting)
    }

    @Test
    fun `KC2-06 mehrere SMBs summieren sich, sie umgehen das Budget nicht`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.20, "a") + throughPump(0.15, "b"),
            cfg,
        )
        assertEquals(0.35, s.transportCommitmentU, 1e-12)
    }

    @Test
    fun `KC2-08 und KC2-23 kein Zeitablauf loest die Buchung - nur der IOB-Snapshot tut das`() {
        // Es gibt im Reducer bewusst KEIN Zeitereignis. Auch die vollstaendige
        // Kette inkl. bestaetigter Abgabe laesst die Menge gebucht, solange sie
        // nicht nachweislich im IOB steckt.
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.ExecutionResult(id, success = true, enacted = false, bolusDeliveredU = 0.30)),
            cfg,
        )
        assertEquals(DeliveryState.REPORTED_FULL, entry(s).delivery)
        assertEquals(0.30, s.transportCommitmentU, 1e-12)
    }

    @Test
    fun `KC2-09 ein Retry erzeugt keinen zweiten Eintrag`() {
        val once = run(proposed(0.30))
        val twice = LedgerReducer.reduce(once, proposed(0.30), cfg)
        assertSame(once, twice)
        assertEquals(1, twice.entries.size)
        assertEquals(0.30, twice.transportCommitmentU, 1e-12)
    }

    @Test
    fun `dieselbe Id mit anderem Inhalt haelt alles an`() {
        val s = LedgerReducer.reduce(run(proposed(0.30)), proposed(0.45), cfg)
        assertTrue(s.holdActuation)
        assertTrue(entry(s).errors.contains(LedgerError.DUPLICATE_PROPOSAL))
    }

    // ---- Mengenachse -----------------------------------------------------

    @Test
    fun `KC2-32 und KC2-44 alle Stufen bleiben sichtbar, pumpCommand ist die Referenz`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            listOf(
                proposed(0.30),
                amount(AmountStage.RT_PUBLISHED, 0.30),
                amount(AmountStage.LOOP_CONSTRAINED, 0.25),
                amount(AmountStage.QUEUE_CONSTRAINED, 0.20),
                LedgerEvent.QueueAccepted(id),
                amount(AmountStage.PUMP_COMMAND, 0.15),
            ),
            cfg,
        )
        val a = entry(s).amounts
        assertEquals(0.30, a.proposedU)
        assertEquals(0.25, a.loopConstrainedU)
        assertEquals(0.20, a.queueConstrainedU)
        assertEquals(0.15, a.pumpCommandU)
        assertEquals(0.15, a.latestKnownCommandU)
        assertEquals(0.15, s.transportCommitmentU, 1e-12)
        assertFalse(s.holdActuation)
    }

    @Test
    fun `KC2-18 ein auf null constrainter Loop-SMB erzeugt keine Phantomdosis`() {
        val s = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            amount(AmountStage.LOOP_CONSTRAINED, 0.0),
            LedgerEvent.QueueRejected(id, QueueRejectReason.CONSTRAINT_ZERO),
        )
        assertEquals(0.0, s.transportCommitmentU, 1e-12)
        assertTrue(entry(s).closed)
    }

    @Test
    fun `KC2-59 eine groessere Folgestufe ist ein Vertragsbruch`() {
        val s = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            amount(AmountStage.LOOP_CONSTRAINED, 0.40),
        )
        assertTrue(entry(s).failClosed)
        assertTrue(entry(s).errors.contains(LedgerError.CONSTRAINT_CHAIN_INVALID))
        assertTrue(s.holdActuation)
    }

    @Test
    fun `dieselbe Stufe zweimal mit anderer Menge haelt an`() {
        val s = run(
            proposed(0.30),
            amount(AmountStage.LOOP_CONSTRAINED, 0.25),
            amount(AmountStage.LOOP_CONSTRAINED, 0.20),
        )
        assertTrue(entry(s).errors.contains(LedgerError.CONFLICTING_STAGE_AMOUNT))
        // dieselbe Stufe zweimal mit GLEICHER Menge ist dagegen folgenlos
        val same = LedgerReducer.reduce(s, amount(AmountStage.LOOP_CONSTRAINED, 0.25), cfg)
        assertSame(s, same)
    }

    // ---- Queue -----------------------------------------------------------

    @Test
    fun `KC2-58 eine auf null constrainte Menge darf nie in die Queue`() {
        // Genau der Pfad, den CommandQueueImplementation.bolus() heute offen
        // laesst: nach applyBolusConstraints wird NICHT erneut auf > 0 geprueft,
        // und der Treiber scheitert danach an require(insulin > 0).
        assertTrue(LedgerRules.queueWouldRejectAsZero(0.0, cfg.bolusStepU))
        assertTrue(LedgerRules.queueWouldRejectAsZero(0.04, cfg.bolusStepU))
        assertFalse(LedgerRules.queueWouldRejectAsZero(0.05, cfg.bolusStepU))

        val rejected = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            amount(AmountStage.LOOP_CONSTRAINED, 0.30),
            amount(AmountStage.QUEUE_CONSTRAINED, 0.0),
            LedgerEvent.QueueRejected(id, QueueRejectReason.CONSTRAINT_ZERO),
        )
        assertEquals(QueueRejectReason.CONSTRAINT_ZERO, entry(rejected).queueReject)
        assertEquals(0.0, rejected.transportCommitmentU, 1e-12)
        assertFalse(rejected.holdActuation)

        // ein zweites Reject-Ereignis derselben Ursache aendert nichts
        assertSame(rejected, LedgerReducer.reduce(rejected, LedgerEvent.QueueRejected(id, QueueRejectReason.CONSTRAINT_ZERO), cfg))

        // wird sie trotzdem eingereiht, ist das ein Vertragsbruch
        val accepted = run(
            proposed(0.30),
            amount(AmountStage.QUEUE_CONSTRAINED, 0.0),
            LedgerEvent.QueueAccepted(id),
        )
        assertTrue(accepted.holdActuation)
        assertTrue(entry(accepted).errors.contains(LedgerError.PHASE_VIOLATION))
    }

    @Test
    fun `KC2-19 und KC2-20 ein abgelehnter Bolus loest die Schuld auf`() {
        for (reason in listOf(QueueRejectReason.BOLUS_IN_QUEUE, QueueRejectReason.GATE_BLOCKED, QueueRejectReason.OTHER)) {
            val s = run(
                proposed(0.30),
                amount(AmountStage.RT_PUBLISHED, 0.30),
                LedgerEvent.QueueRejected(id, reason),
            )
            assertEquals(0.0, s.transportCommitmentU, 1e-12, reason.name)
            assertTrue(entry(s).closed, reason.name)
            assertEquals(LedgerPhase.TERMINAL, entry(s).phase)
        }
    }

    @Test
    fun `KC2-35 ein manueller Bolus zwischen Snapshot und Queue verwirft den Kandidaten`() {
        val s = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            LedgerEvent.QueueRejected(id, QueueRejectReason.TREATMENT_CHANGED),
        )
        assertEquals(0.0, s.transportCommitmentU, 1e-12)
        // Der Zeitstempel, gegen den der vorhandene Queue-Guard vergleicht,
        // stammt aus dem CycleSnapshot - nicht aus dem Moment des Queue-Aufrufs.
        assertEquals(t0 - 600_000L, entry(s).latestBolusTimestampAtDecision)
    }

    // ---- Terminalregel ---------------------------------------------------

    @Test
    fun `KC2-17 und KC2-39 success ohne enacted mit voller Menge ist FULL`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.25) + listOf(
                LedgerEvent.ExecutionResult(id, success = true, enacted = false, bolusDeliveredU = 0.25, comment = "ok")
            ),
            cfg,
        )
        assertEquals(DeliveryState.REPORTED_FULL, entry(s).delivery)
        assertEquals(0.25, s.transportCommitmentU, 1e-12)
        assertFalse(s.holdActuation)
    }

    @Test
    fun `KC2-40 und KC2-22 eine Teilabgabe ist PARTIAL in Hoehe von bolusDelivered`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.ExecutionResult(id, success = true, enacted = false, bolusDeliveredU = 0.10)
            ),
            cfg,
        )
        assertEquals(DeliveryState.REPORTED_PARTIAL, entry(s).delivery)
        assertEquals(0.10, entry(s).amounts.reportedDeliveredU)
        // Der REST bleibt gebucht: dass er nicht floss, ist noch nicht bewiesen.
        assertEquals(0.30, s.transportCommitmentU, 1e-12)

        // Erst der Nachweis gibt ihn frei.
        val proven = LedgerReducer.reduce(s, LedgerEvent.DeliveryProven(id, 0.10, "pump history"), cfg)
        assertEquals(0.10, proven.transportCommitmentU, 1e-12)
        assertEquals(DeliveryState.REPORTED_PARTIAL, entry(proven).delivery)
    }

    @Test
    fun `KC2-56 genau eine Pumpenstufe weniger ist PARTIAL, niemals FULL`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.ExecutionResult(id, success = true, enacted = false, bolusDeliveredU = 0.25)
            ),
            cfg,
        )
        assertEquals(DeliveryState.REPORTED_PARTIAL, entry(s).delivery)
        // Zur Erinnerung, warum die Toleranz nicht bolusStep sein darf:
        // Medtrum meldet success bereits bei abs(diff) < bolusStep.
        assertEquals(6L, LedgerRules.canonicalTicks(0.30, cfg.bolusStepU))
        assertEquals(5L, LedgerRules.canonicalTicks(0.25, cfg.bolusStepU))
    }

    @Test
    fun `KC2-57 mehr geliefert als kommandiert ist eine Anomalie mit Hold`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.20) + listOf(
                LedgerEvent.ExecutionResult(id, success = true, enacted = true, bolusDeliveredU = 0.35)
            ),
            cfg,
        )
        assertEquals(DeliveryState.OVERDELIVERY_ANOMALY, entry(s).delivery)
        assertTrue(s.holdActuation)
        // konservativ gebucht wird die GROESSERE Menge
        assertEquals(0.35, s.transportCommitmentU, 1e-12)
    }

    @Test
    fun `KC2-21 und KC2-43 eine Nullmeldung ohne Nachweis bleibt unbekannt und gebucht`() {
        val failed = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.ExecutionResult(id, success = false, enacted = false, bolusDeliveredU = null)),
            cfg,
        )
        assertEquals(DeliveryState.UNKNOWN_ASSUMED, entry(failed).delivery)
        assertEquals(0.30, failed.transportCommitmentU, 1e-12)

        val zeroWithoutProof = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.ExecutionResult(id, success = true, enacted = false, bolusDeliveredU = 0.0)),
            cfg,
        )
        assertEquals(DeliveryState.UNKNOWN_ASSUMED, entry(zeroWithoutProof).delivery)
        assertEquals(0.30, zeroWithoutProof.transportCommitmentU, 1e-12)

        // NUR mit explizitem Nachweis wird daraus eine bestaetigte Null
        val proven = LedgerReducer.reduce(zeroWithoutProof, LedgerEvent.DeliveryProven(id, 0.0, "pump history"), cfg)
        assertEquals(DeliveryState.CONFIRMED_ZERO, entry(proven).delivery)
        assertEquals(0.0, proven.transportCommitmentU, 1e-12)
    }

    @Test
    fun `ein zweites, abweichendes Terminalergebnis haelt an und bucht konservativ`() {
        val first = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.ExecutionResult(id, success = true, enacted = true, bolusDeliveredU = 0.10)),
            cfg,
        )
        val second = LedgerReducer.reduce(first, LedgerEvent.ExecutionResult(id, true, true, 0.20), cfg)
        assertTrue(second.holdActuation)
        assertEquals(0.30, second.transportCommitmentU, 1e-12)
        // identische Wiederholung ist dagegen folgenlos
        assertSame(first, LedgerReducer.reduce(first, LedgerEvent.ExecutionResult(id, true, true, 0.10), cfg))
    }

    // ---- Identitaet ------------------------------------------------------

    @Test
    fun `KC2-54 VirtualPump bindet direkt ueber pumpId`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, temporaryId = null, pumpId = 4711L, pumpType = "VIRTUAL", pumpSerialHash = "h", treatmentTimestamp = t0)
            ),
            cfg,
        )
        assertNotNull(entry(s).identity)
        assertNull(entry(s).identity!!.temporaryId)
        assertEquals(4711L, entry(s).identity!!.pumpId)

        val snapshot = IobAccountingSnapshot("hash1", "cursor1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30)))
        val accounted = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(snapshot), cfg)
        assertEquals(AccountingState.IOB_ACCOUNTED, entry(accounted).accounting)
        assertEquals(0.0, accounted.transportCommitmentU, 1e-12)
    }

    @Test
    fun `KC2-55 Medtrum faltet temporaryId und spaetere pumpId auf dieselbe Zeile`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, temporaryId = 99L, pumpId = null, pumpType = "MEDTRUM", pumpSerialHash = "h", treatmentTimestamp = t0),
                LedgerEvent.PumpIdentityBound(id, temporaryId = null, pumpId = 12345L, pumpType = "MEDTRUM", pumpSerialHash = "h", treatmentTimestamp = t0),
            ),
            cfg,
        )
        assertEquals(1, s.entries.size)
        assertEquals(99L, entry(s).identity!!.temporaryId)
        assertEquals(12345L, entry(s).identity!!.pumpId)
        assertFalse(s.holdActuation)
    }

    @Test
    fun `widersprechende Identitaeten werden nicht geraten`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, 99L, null, "MEDTRUM", "h", t0),
                LedgerEvent.PumpIdentityBound(id, 77L, null, "MEDTRUM", "h", t0),
            ),
            cfg,
        )
        assertTrue(entry(s).errors.contains(LedgerError.IDENTITY_CONFLICT))
        assertTrue(s.holdActuation)
    }

    @Test
    fun `KC2-45 ein gleichzeitiger fremder Bolus derselben Menge wird nicht zugeordnet`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, temporaryId = 99L, pumpId = null, pumpType = "MEDTRUM", pumpSerialHash = "h", treatmentTimestamp = t0)
            ),
            cfg,
        )
        // gleiche Menge, gleiche Sekunde - aber eine ANDERE Identitaet
        val foreign = IobAccountingSnapshot("hash2", "cursor2", t0, 2L, listOf(AccountedTreatment(temporaryId = 100L, pumpId = null, amountU = 0.30)))
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(foreign), cfg)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(after).accounting)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
    }

    @Test
    fun `ohne gebundene Identitaet gibt es keine Buchung`() {
        val s = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val snapshot = IobAccountingSnapshot("h", "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30)))
        assertSame(s, LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(snapshot), cfg))
    }

    // ---- Accounting-Achse ------------------------------------------------

    @Test
    fun `KC2-46 eine fremde IOB-Revision laesst die Buchung stehen`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val foreign = IobAccountingSnapshot("h2", "c2", t0 + 60_000L, 2L, listOf(AccountedTreatment(null, 999L, 1.20)))
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(foreign), cfg)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
    }

    @Test
    fun `KC2-24 und KC2-47 der Uebergang ins IOB passiert genau einmal`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val snapshot = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30)))
        val once = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(snapshot), cfg)
        assertEquals(0.0, once.transportCommitmentU, 1e-12)
        assertEquals("h1", entry(once).accountedSnapshotHash)
        assertEquals(0, entry(once).corrections)

        // ein zweiter Snapshot mit demselben Datensatz aendert nichts mehr
        val twice = LedgerReducer.reduce(once, LedgerEvent.IobSnapshotObserved(snapshot.copy(treatmentSnapshotHash = "h2")), cfg)
        assertSame(once, twice)
        assertEquals(0.0, twice.transportCommitmentU, 1e-12)
    }

    @Test
    fun `KC2-41 gebucht heisst nicht geliefert`() {
        // Medtrum legt VOR dem Abschluss einen vorlaeufigen Datensatz mit
        // temporaryId und der ANGEFORDERTEN Menge an.
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, temporaryId = 99L, pumpId = null, pumpType = "MEDTRUM", pumpSerialHash = "h", treatmentTimestamp = t0),
                LedgerEvent.IobSnapshotObserved(IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(99L, null, 0.30)))),
            ),
            cfg,
        )
        assertEquals(AccountingState.IOB_ACCOUNTED, entry(s).accounting)
        assertEquals(DeliveryState.UNKNOWN, entry(s).delivery)   // NICHT abgeglichen
        assertEquals(0.0, s.transportCommitmentU, 1e-12)
    }

    @Test
    fun `KC2-42 eine spaetere Revision auf eine Teilmenge ist genau eine Korrektur`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, 99L, null, "MEDTRUM", "h", t0),
                LedgerEvent.DbAmountObserved(id, 0.30),
            ),
            cfg,
        )
        val revised = LedgerReducer.reduce(s, LedgerEvent.DbAmountObserved(id, 0.10), cfg)
        assertEquals(1, entry(revised).corrections)
        assertEquals(0.10, entry(revised).amounts.dbAccountedU)
        // dieselbe Revision noch einmal: keine zweite Korrektur
        assertSame(revised, LedgerReducer.reduce(revised, LedgerEvent.DbAmountObserved(id, 0.10), cfg))
        // und in keinem Fall eine neue Dosis
        assertEquals(1, revised.entries.size)
    }

    // ---- Absturz und Wiederanlauf ----------------------------------------

    @Test
    fun `KC2-10 und KC2-33 und KC2-34 ein offener Eintrag ueberlebt den Neustart konservativ`() {
        val afterPersist = run(proposed(0.30))
        val restarted = LedgerReducer.reduce(afterPersist, LedgerEvent.RestartObserved(t0 + 60_000L), cfg)
        assertEquals(DeliveryState.UNKNOWN_ASSUMED, entry(restarted).delivery)
        assertEquals(0.30, restarted.transportCommitmentU, 1e-12)
        assertFalse(entry(restarted).closed)
    }

    @Test
    fun `KC2-52 an jeder Phasengrenze abgebrochen entsteht keine zweite Dosis`() {
        val full = throughPump(0.30)
        for (cut in 1..full.size) {
            val s = LedgerReducer.reduceAll(LedgerState(), full.take(cut), cfg)
            val restarted = LedgerReducer.reduce(s, LedgerEvent.RestartObserved(t0 + 60_000L), cfg)
            assertEquals(
                0.30, restarted.transportCommitmentU, 1e-12,
                "Abbruch nach ${full[cut - 1]::class.simpleName} (cut=$cut)"
            )
            assertEquals(1, restarted.entries.size)
        }
    }

    @Test
    fun `ein geschlossener Eintrag wird durch den Neustart nicht wiederbelebt`() {
        val rejected = run(proposed(0.30), LedgerEvent.QueueRejected(id, QueueRejectReason.BOLUS_IN_QUEUE))
        val restarted = LedgerReducer.reduce(rejected, LedgerEvent.RestartObserved(t0), cfg)
        assertEquals(0.0, restarted.transportCommitmentU, 1e-12)
    }

    // ---- Identitaetstransport --------------------------------------------

    @Test
    fun `KC2-63 dieselbe proposalId traegt durch alle Stationen`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.ExecutionResult(id, true, false, 0.30),
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
            ),
            cfg,
        )
        assertEquals(1, s.entries.size)
        assertEquals(id, entry(s).proposalId)
        assertEquals(LedgerPhase.TERMINAL, entry(s).phase)
        assertTrue(s.errors.isEmpty())
    }

    @Test
    fun `KC2-64 eine verlorene proposalId sperrt die Aktuation`() {
        val lost = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.ProposalIdLost(id, "DetailedBolusInfo")),
            cfg,
        )
        assertTrue(lost.holdActuation)
        assertTrue(entry(lost).failClosed)
        // Die Menge bleibt gebucht - "ungetrackt" ist kein Freibrief.
        assertEquals(0.30, lost.transportCommitmentU, 1e-12)

        // Ein Ereignis zu einer voellig unbekannten Id ist derselbe Fall.
        val unknown = LedgerReducer.reduce(LedgerState(), LedgerEvent.QueueAccepted("ghost"), cfg)
        assertTrue(unknown.holdActuation)
        assertEquals(LedgerError.UNKNOWN_PROPOSAL, unknown.errors.single().error)
    }

    // ---- R79-F1: Reject vs. Lieferbeleg ----------------------------------

    @Test
    fun `R79-F1 ein Reject nach der Queue-Annahme ist ein Widerspruch, kein Beleg`() {
        val s = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            amount(AmountStage.QUEUE_CONSTRAINED, 0.30),
            LedgerEvent.QueueAccepted(id),
            LedgerEvent.QueueRejected(id, QueueRejectReason.OTHER),
        )
        // Die Menge bleibt gebucht - vorher fiel sie hier auf 0 U.
        assertEquals(0.30, s.transportCommitmentU, 1e-12)
        assertTrue(s.holdActuation)
        assertTrue(entry(s).errors.contains(LedgerError.PHASE_VIOLATION))
        assertNull(entry(s).queueReject)
        assertFalse(entry(s).closed)
    }

    @Test
    fun `R79-F1 ein Terminalereignis nach einem Reject holt die Buchung zurueck`() {
        val rejected = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            LedgerEvent.QueueRejected(id, QueueRejectReason.BOLUS_IN_QUEUE),
        )
        assertEquals(0.0, rejected.transportCommitmentU, 1e-12)

        val delivered = LedgerReducer.reduce(rejected, LedgerEvent.ExecutionResult(id, true, false, 0.30), cfg)
        assertEquals(0.30, delivered.transportCommitmentU, 1e-12)
        assertTrue(delivered.holdActuation)
        assertTrue(entry(delivered).errors.contains(LedgerError.PHASE_VIOLATION))
        assertFalse(entry(delivered).closed)
    }

    @Test
    fun `R79-F1 auch ein spaeterer Lieferbeleg holt die Buchung zurueck`() {
        val rejected = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            LedgerEvent.QueueRejected(id, QueueRejectReason.BOLUS_IN_QUEUE),
        )
        val proven = LedgerReducer.reduce(rejected, LedgerEvent.DeliveryProven(id, 0.20, "pump history"), cfg)
        assertEquals(0.20, proven.transportCommitmentU, 1e-12)
        assertTrue(proven.holdActuation)

        // und der IOB-Snapshot kann die Zeile danach ueberhaupt noch schliessen
        val bound = LedgerReducer.reduce(proven, LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0), cfg)
        val snapshot = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.20)))
        val accounted = LedgerReducer.reduce(bound, LedgerEvent.IobSnapshotObserved(snapshot), cfg)
        assertEquals(AccountingState.IOB_ACCOUNTED, entry(accounted).accounting)
        assertEquals(0.0, accounted.transportCommitmentU, 1e-12)
    }

    @Test
    fun `R79-F1 ein belegter Rueckzug vor der Ausfuehrung befreit, ein spaeterer nicht`() {
        // Beleg: removeAll() kann nur Kommandos entfernen, die noch in der
        // Queue liegen - das laufende steckt in performing.
        val withdrawn = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            LedgerEvent.QueueAccepted(id),
            LedgerEvent.QueueWithdrawnProven(id, "removeAll(SMB_BOLUS) before pickup"),
        )
        assertEquals(0.0, withdrawn.transportCommitmentU, 1e-12)
        assertTrue(entry(withdrawn).closed)
        assertFalse(withdrawn.holdActuation)
        assertSame(withdrawn, LedgerReducer.reduce(withdrawn, LedgerEvent.QueueWithdrawnProven(id, "again"), cfg))

        // nach einem Pumpenkommando ist derselbe Beleg wertlos
        val tooLate = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.QueueWithdrawnProven(id, "too late")),
            cfg,
        )
        assertEquals(0.30, tooLate.transportCommitmentU, 1e-12)
        assertTrue(tooLate.holdActuation)
    }

    // ---- R79-F2: ungueltige Mengen ---------------------------------------

    @Test
    fun `R79-F2 NaN und Infinity werden nie zum Ledger-Fakt`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5)) {
            val s = LedgerReducer.reduceAll(
                LedgerState(),
                throughPump(0.30) + listOf(LedgerEvent.ExecutionResult(id, true, true, bad)),
                cfg,
            )
            val c = s.transportCommitmentU
            assertTrue(c.isFinite(), "commitment nicht endlich bei $bad: $c")
            assertEquals(0.30, c, 1e-12, "bei $bad")
            assertTrue(s.holdActuation, "bei $bad")
            assertTrue(entry(s).errors.contains(LedgerError.NON_FINITE_AMOUNT), "bei $bad")
            assertEquals(DeliveryState.UNKNOWN_ASSUMED, entry(s).delivery, "bei $bad")
            assertNull(entry(s).amounts.reportedDeliveredU, "bei $bad")
        }
    }

    @Test
    fun `R79-F2 auch DB-Menge und Nachweis werden geprueft`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.5)) {
            val db = LedgerReducer.reduceAll(
                LedgerState(),
                throughPump(0.30) + listOf(LedgerEvent.DbAmountObserved(id, bad)),
                cfg,
            )
            assertTrue(db.transportCommitmentU.isFinite(), "db $bad")
            assertNull(entry(db).amounts.dbAccountedU, "db $bad")
            assertTrue(entry(db).errors.contains(LedgerError.NON_FINITE_AMOUNT), "db $bad")

            val proven = LedgerReducer.reduceAll(
                LedgerState(),
                throughPump(0.30) + listOf(LedgerEvent.DeliveryProven(id, bad, "x")),
                cfg,
            )
            assertTrue(proven.transportCommitmentU.isFinite(), "proven $bad")
            assertEquals(0.30, proven.transportCommitmentU, 1e-12, "proven $bad")
        }
    }

    @Test
    fun `R79-F2 auch ein kaputter Snapshot-Betrag bucht nicht aus`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val broken = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, Double.NaN)))
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(broken), cfg)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(after).accounting)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
        assertTrue(after.holdActuation)
    }

    // ---- R79-F3: Identitaetsvertraeglichkeit ------------------------------

    @Test
    fun `R79-F3 eine teilpassende, widerspruechliche Identitaet ist ein Konflikt, kein Treffer`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, temporaryId = 7L, pumpId = null, pumpType = "MEDTRUM", pumpSerialHash = "h", treatmentTimestamp = t0),
                LedgerEvent.PumpIdentityBound(id, temporaryId = null, pumpId = 8L, pumpType = "MEDTRUM", pumpSerialHash = "h", treatmentTimestamp = t0),
            ),
            cfg,
        )
        assertEquals(7L, entry(s).identity!!.temporaryId)
        assertEquals(8L, entry(s).identity!!.pumpId)

        // temporaryId passt, pumpId widerspricht
        val snapshot = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(7L, 9L, 0.30)))
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(snapshot), cfg)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(after).accounting)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
        assertTrue(after.holdActuation)
        assertTrue(entry(after).errors.contains(LedgerError.IDENTITY_CONFLICT))
    }

    @Test
    fun `R79-F3 Vertraeglichkeit unterscheidet Treffer, Nichttreffer und Konflikt`() {
        val id78 = PumpTreatmentIdentity(id, 7L, 8L, "MEDTRUM", "h", t0)
        assertEquals(IdentityMatch.MATCH, id78.compatibility(7L, 8L))
        assertEquals(IdentityMatch.MATCH, id78.compatibility(7L, null))
        assertEquals(IdentityMatch.MATCH, id78.compatibility(null, 8L))
        assertEquals(IdentityMatch.CONFLICT, id78.compatibility(7L, 9L))
        assertEquals(IdentityMatch.CONFLICT, id78.compatibility(6L, 8L))
        assertEquals(IdentityMatch.NO_MATCH, id78.compatibility(null, null))
        val tempOnly = PumpTreatmentIdentity(id, 7L, null, "MEDTRUM", "h", t0)
        assertEquals(IdentityMatch.NO_MATCH, tempOnly.compatibility(null, 9L))
    }

    @Test
    fun `R79-F3 eine verschobene Behandlungszeit beim Binden ist eine Korrektur, kein Konflikt`() {
        // SyncBolusWithTempIdTransaction ueberschreibt beim Binden der pumpId
        // timestamp UND amount des vorhandenen Datensatzes - die Verschiebung
        // ist AAPS-normal.
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, 7L, null, "MEDTRUM", "h", t0),
                LedgerEvent.PumpIdentityBound(id, null, 8L, "MEDTRUM", "h", t0 + 4_000L),
            ),
            cfg,
        )
        assertFalse(s.holdActuation)
        assertEquals(t0 + 4_000L, entry(s).identity!!.treatmentTimestamp)
        assertEquals(1, entry(s).corrections)
    }

    @Test
    fun `eine andere Pumpe oder Seriennummer bleibt ein Konflikt`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, 7L, null, "MEDTRUM", "h", t0),
                LedgerEvent.PumpIdentityBound(id, null, 8L, "MEDTRUM", "andere", t0),
            ),
            cfg,
        )
        assertTrue(entry(s).errors.contains(LedgerError.IDENTITY_CONFLICT))
    }

    // ---- Invariante ueber alle Reihenfolgen -------------------------------

    @Test
    fun `R79-F2 die Gesamtbuchung ist in JEDER Ereignisfolge endlich und nie kleiner als der Nachweisstand`() {
        val vocabulary = listOf<LedgerEvent>(
            amount(AmountStage.RT_PUBLISHED, 0.30),
            amount(AmountStage.LOOP_CONSTRAINED, 0.25),
            amount(AmountStage.QUEUE_CONSTRAINED, 0.25),
            LedgerEvent.QueueAccepted(id),
            LedgerEvent.QueueRejected(id, QueueRejectReason.OTHER),
            LedgerEvent.QueueWithdrawnProven(id, "e"),
            amount(AmountStage.PUMP_COMMAND, 0.25),
            LedgerEvent.ExecutionResult(id, true, false, 0.25),
            LedgerEvent.ExecutionResult(id, false, false, Double.NaN),
            LedgerEvent.DeliveryProven(id, 0.25, "e"),
            LedgerEvent.DbAmountObserved(id, Double.NaN),
            LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
            LedgerEvent.RestartObserved(t0),
            LedgerEvent.ProposalIdLost(id, "station"),
        )
        // deterministische Streuung ueber viele Reihenfolgen, ohne Zufall
        for (seed in 0 until 300) {
            val order = vocabulary.indices.sortedBy { (it * 31 + seed * 17) % vocabulary.size }
            val events = listOf<LedgerEvent>(proposed(0.30)) + order.map { vocabulary[it] }
            val s = LedgerReducer.reduceAll(LedgerState(), events, cfg)
            val c = s.transportCommitmentU
            assertTrue(c.isFinite(), "seed=$seed commitment=$c")
            assertTrue(c >= 0.0, "seed=$seed commitment=$c")
            // Sobald ein Lieferzeichen existiert, darf nichts mehr auf 0 fallen,
            // solange die Menge nicht im IOB nachgewiesen ist.
            val e = entry(s)
            if (e.anyDeliverySignal && e.accounting == AccountingState.NOT_ACCOUNTED &&
                e.delivery != DeliveryState.CONFIRMED_ZERO
            ) assertTrue(c > 0.0, "seed=$seed: Lieferzeichen, aber Buchung $c")
        }
    }

    // ---- Kanonische Mengen -----------------------------------------------

    @Test
    fun `canonicalTicks rundet halb aufwaerts und vertraegt Double-Ungenauigkeit`() {
        assertEquals(6L, LedgerRules.canonicalTicks(0.30000000000000004, 0.05))
        assertEquals(1L, LedgerRules.canonicalTicks(0.05, 0.05))
        assertEquals(0L, LedgerRules.canonicalTicks(0.0, 0.05))
        assertEquals(2L, LedgerRules.canonicalTicks(0.075, 0.05))   // halbe Stufe -> aufwaerts
        assertEquals(20L, LedgerRules.canonicalTicks(1.0, 0.05))
    }
}
