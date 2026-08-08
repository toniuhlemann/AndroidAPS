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

    /** R93-F4: ein fremder Snapshot laesst Buchung, Rest und Hold unberuehrt —
     *  er aktualisiert aber die Provenienz, weil er die Abwesenheit BESTAETIGT.
     *  Instanzgleichheit ist damit kein gueltiges Kriterium mehr. */
    private fun assertUnaffected(before: LedgerState, after: LedgerState, tag: String = "") {
        val a = before.entries.getValue(id)
        val b = after.entries.getValue(id)
        assertEquals(a.accounting, b.accounting, tag)
        assertEquals(a.accountedAmountU, b.accountedAmountU, tag)
        assertEquals(before.transportCommitmentU, after.transportCommitmentU, 1e-12, tag)
        assertEquals(before.holdActuation, after.holdActuation, tag)
        assertEquals(a.errors, b.errors, tag)
        assertEquals(a.delivery, b.delivery, tag)
    }


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

        val snapshot = IobAccountingSnapshot("hash1", "cursor1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30)), sourceEpochId = "epoch-test")
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
        val foreign = IobAccountingSnapshot("hash2", "cursor2", t0, 2L, listOf(AccountedTreatment(temporaryId = 100L, pumpId = null, amountU = 0.30)), sourceEpochId = "epoch-test")
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(foreign), cfg)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(after).accounting)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
        // Und er ist auch kein Konflikt: ein fremder Bolus ist der Normalfall,
        // kein Grund, die Aktuation zu sperren (R81-F2 - diese Zeile fehlte,
        // weshalb der Testlauf den Fehler nicht bemerkt hat).
        assertFalse(after.holdActuation)
        assertUnaffected(s, after)
    }

    @Test
    fun `ohne gebundene Identitaet gibt es keine Buchung`() {
        val s = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val snapshot = IobAccountingSnapshot("h", "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30)), sourceEpochId = "epoch-test")
        assertUnaffected(s, LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(snapshot), cfg))
    }

    // ---- Accounting-Achse ------------------------------------------------

    @Test
    fun `KC2-46 eine fremde IOB-Revision laesst die Buchung stehen`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val foreign = IobAccountingSnapshot("h2", "c2", t0 + 60_000L, 2L, listOf(AccountedTreatment(null, 999L, 1.20)), sourceEpochId = "epoch-test")
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
        val snapshot = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30)), sourceEpochId = "epoch-test")
        val once = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(snapshot), cfg)
        assertEquals(0.0, once.transportCommitmentU, 1e-12)
        assertEquals("h1", entry(once).firstAccountedSnapshotHash)
        assertEquals(0, entry(once).corrections)

        // Ein zweiter Snapshot mit demselben Datensatz aendert die BUCHUNG nicht
        // mehr - wohl aber die Aussage darueber, gegen WELCHE Sicht zuletzt
        // abgeglichen wurde (R91-F5: Erstnachweis und aktuelle Vollsicht sind
        // getrennte Felder).
        // Ein spaeterer Snapshot traegt eine NEUERE Ordnung. Gleiche Ordnung mit
        // anderem Inhalt waere seit R93-F2 ein Widerspruch - und war als
        // Testkonstruktion von Anfang an unmoeglich.
        val twice = LedgerReducer.reduce(
            once,
            LedgerEvent.IobSnapshotObserved(
                snapshot.copy(treatmentSnapshotHash = "h2", calculatorGeneration = 2L, calculatedAt = t0 + 60_000L)
            ),
            cfg,
        )
        assertEquals(0.0, twice.transportCommitmentU, 1e-12)
        assertEquals(0.30, entry(twice).accountedAmountU)
        assertEquals(0, entry(twice).corrections)
        assertEquals("h1", entry(twice).firstAccountedSnapshotHash)
        assertEquals("h2", entry(twice).lastReconciledViewHash)
    }

    @Test
    fun `KC2-41 gebucht heisst nicht geliefert`() {
        // Medtrum legt VOR dem Abschluss einen vorlaeufigen Datensatz mit
        // temporaryId und der ANGEFORDERTEN Menge an.
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, temporaryId = 99L, pumpId = null, pumpType = "MEDTRUM", pumpSerialHash = "h", treatmentTimestamp = t0),
                LedgerEvent.IobSnapshotObserved(IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(99L, null, 0.30)), sourceEpochId = "epoch-test")),
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
        val snapshot = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.20)), sourceEpochId = "epoch-test")
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

    @Test
    fun `R81-F3 der Rueckzugsbeleg gilt NUR fuer den Uebergang aus der Queue-Annahme`() {
        // vor der Annahme gibt es nichts zurueckzuziehen
        val tooEarly = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            LedgerEvent.QueueWithdrawnProven(id, "removeAll"),
        )
        assertEquals(0.30, tooEarly.transportCommitmentU, 1e-12)
        assertTrue(tooEarly.holdActuation)
        assertFalse(entry(tooEarly).withdrawnProven)

        // ein leerer Beleg ist kein Beleg
        val empty = run(
            proposed(0.30),
            LedgerEvent.QueueAccepted(id),
            LedgerEvent.QueueWithdrawnProven(id, "   "),
        )
        assertEquals(0.30, empty.transportCommitmentU, 1e-12)
        assertTrue(empty.holdActuation)
    }

    @Test
    fun `R81-F3 und R83-F2 nach Rueckzug oder Reject stellt jede Fortsetzung die Buchung wieder her`() {
        val withdrawn = run(
            proposed(0.30),
            LedgerEvent.QueueAccepted(id),
            LedgerEvent.QueueWithdrawnProven(id, "cancelAllBoluses removed cmd#7"),
        )
        val rejected = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            LedgerEvent.QueueRejected(id, QueueRejectReason.BOLUS_IN_QUEUE),
        )
        for ((name, base) in listOf("withdrawn" to withdrawn, "rejected" to rejected)) {
            assertEquals(0.0, base.transportCommitmentU, 1e-12, name)

            // (1) erneute Annahme: setzte vorher nur den Hold, die Buchung blieb
            // bei 0 U - sachlich falsch, weil ein Widerspruch kein Beweis mehr
            // ist, dass nichts floss.
            val reAccepted = LedgerReducer.reduce(base, LedgerEvent.QueueAccepted(id), cfg)
            assertTrue(reAccepted.holdActuation, name)
            assertTrue(reAccepted.transportCommitmentU > 0.0, "$name: reAccept -> ${reAccepted.transportCommitmentU}")
            assertFalse(entry(reAccepted).closed, name)
            assertTrue(entry(reAccepted).contradicted, name)

            // (2) jede erstmals beobachtete spaetere Mengenstufe - auch eine,
            // die KEIN Pumpenkommando ist
            for (stage in listOf(AmountStage.LOOP_CONSTRAINED, AmountStage.QUEUE_CONSTRAINED, AmountStage.PUMP_COMMAND)) {
                if (entry(base).amounts.stage(stage) != null) continue
                val later = LedgerReducer.reduce(base, amount(stage, 0.25), cfg)
                assertTrue(later.holdActuation, "$name/$stage")
                assertTrue(later.transportCommitmentU > 0.0, "$name/$stage -> ${later.transportCommitmentU}")
                assertFalse(entry(later).closed, "$name/$stage")
            }

            // (3) ExecutionResult(0) ohne Nachweis bleibt gebucht ...
            val zero = LedgerReducer.reduce(base, LedgerEvent.ExecutionResult(id, true, false, 0.0), cfg)
            assertTrue(zero.transportCommitmentU > 0.0, name)
            assertEquals(DeliveryState.UNKNOWN_ASSUMED, entry(zero).delivery, name)
            // ... und wird erst durch den Nachweis zu CONFIRMED_ZERO
            val proven = LedgerReducer.reduce(zero, LedgerEvent.DeliveryProven(id, 0.0, "pump history"), cfg)
            assertEquals(DeliveryState.CONFIRMED_ZERO, entry(proven).delivery, name)
            assertEquals(0.0, proven.transportCommitmentU, 1e-12, name)

            // (4) oder durch den passenden IOB-Snapshot
            val bound = LedgerReducer.reduceAll(
                base,
                listOf(
                    LedgerEvent.QueueAccepted(id),
                    LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                    LedgerEvent.IobSnapshotObserved(
                        IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30)), sourceEpochId = "epoch-test")
                    ),
                ),
                cfg,
            )
            assertEquals(AccountingState.IOB_ACCOUNTED, entry(bound).accounting, name)
            assertEquals(0.0, bound.transportCommitmentU, 1e-12, name)
        }
    }

    @Test
    fun `R85-F1 auch der Widerspruchspfad durchlaeuft die Kettenpruefung`() {
        val withdrawn = run(
            proposed(0.30),
            LedgerEvent.QueueAccepted(id),
            LedgerEvent.QueueWithdrawnProven(id, "cancelAllBoluses removed cmd#7"),
        )
        val rejected = run(
            proposed(0.30),
            amount(AmountStage.RT_PUBLISHED, 0.30),
            LedgerEvent.QueueRejected(id, QueueRejectReason.BOLUS_IN_QUEUE),
        )
        for ((name, base) in listOf("withdrawn" to withdrawn, "rejected" to rejected)) {
            // Stufe GROESSER als die vorherige: beide Fehler muessen sichtbar
            // sein, nicht nur der Widerspruch.
            val bigger = LedgerReducer.reduce(base, amount(AmountStage.PUMP_COMMAND, 0.50), cfg)
            val e = entry(bigger)
            assertTrue(e.errors.contains(LedgerError.PHASE_VIOLATION), name)
            assertTrue(e.errors.contains(LedgerError.CONSTRAINT_CHAIN_INVALID), "$name: Kettenpruefung fehlt")
            assertTrue(bigger.holdActuation, name)
            assertEquals(0.50, bigger.transportCommitmentU, 1e-12, name)

            // Stufe KLEINER als die zuletzt bekannte: die Kette ist in Ordnung,
            // aber die Buchung darf nicht sinken.
            val smaller = LedgerReducer.reduce(base, amount(AmountStage.PUMP_COMMAND, 0.10), cfg)
            assertTrue(entry(smaller).errors.contains(LedgerError.PHASE_VIOLATION), name)
            assertFalse(entry(smaller).errors.contains(LedgerError.CONSTRAINT_CHAIN_INVALID), name)
            assertEquals(0.30, smaller.transportCommitmentU, 1e-12, "$name: Buchung gesunken")

            // Ein spaeterer Nachweis schlaegt die Untergrenze - er ist die
            // staerkere Aussage.
            val proven = LedgerReducer.reduce(smaller, LedgerEvent.DeliveryProven(id, 0.10, "pump history"), cfg)
            assertEquals(0.10, proven.transportCommitmentU, 1e-12, name)
        }
    }

    @Test
    fun `R87-F1 ein passender Snapshot erreicht auch eine durch Reject oder Rueckzug befreite Zeile`() {
        val bind = LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)
        val snapshot = IobAccountingSnapshot(
            "h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.30))
        , sourceEpochId = "epoch-test")
        val foreign = IobAccountingSnapshot(
            "h2", "c2", t0, 2L, listOf(AccountedTreatment(null, 999L, 1.20))
        , sourceEpochId = "epoch-test")
        // Die beiden Befreiungen haben VERSCHIEDENE gueltige Vorgeschichten:
        // ein Reject gilt nur VOR der Queue-Annahme (R79-F1), ein belegter
        // Rueckzug nur DANACH (R81-F3). Ein gemeinsamer Praefix waere in einem
        // der beiden Faelle selbst schon ein Widerspruch.
        val freeing = listOf(
            Triple(
                "reject",
                listOf<LedgerEvent>(proposed(0.30), amount(AmountStage.RT_PUBLISHED, 0.30)),
                LedgerEvent.QueueRejected(id, QueueRejectReason.BOLUS_IN_QUEUE) as LedgerEvent,
            ),
            Triple(
                "withdrawal",
                listOf<LedgerEvent>(proposed(0.30), LedgerEvent.QueueAccepted(id)),
                LedgerEvent.QueueWithdrawnProven(id, "cancelAllBoluses removed cmd#7") as LedgerEvent,
            ),
        )
        for ((name, prefix, free) in freeing) {
            // (1) Identitaet VOR der Schuldbefreiung
            val before = LedgerReducer.reduceAll(LedgerState(), prefix + listOf(bind, free), cfg)
            // (2) Identitaet ERST danach
            val after = LedgerReducer.reduceAll(LedgerState(), prefix + listOf(free, bind), cfg)

            for ((order, base) in listOf("id-vorher" to before, "id-nachher" to after)) {
                val tag = "$name/$order"
                assertEquals(0.0, base.transportCommitmentU, 1e-12, tag)

                // (3) fremder Snapshot laesst die befreite Zeile unberuehrt
                assertUnaffected(base, LedgerReducer.reduce(base, LedgerEvent.IobSnapshotObserved(foreign), cfg), tag)

                // (4) passender Snapshot: gebucht, Commitment 0, Widerspruch sichtbar
                val s = LedgerReducer.reduce(base, LedgerEvent.IobSnapshotObserved(snapshot), cfg)
                assertEquals(AccountingState.IOB_ACCOUNTED, entry(s).accounting, tag)
                assertEquals(0.0, s.transportCommitmentU, 1e-12, tag)
                assertTrue(s.holdActuation, "$tag: Widerspruch nicht sichtbar")
                assertTrue(entry(s).contradicted, tag)
                assertTrue(entry(s).errors.contains(LedgerError.PHASE_VIOLATION), tag)
            }
        }
    }

    @Test
    fun `R87-F1 eine bestaetigte Nullabgabe wird von einem fremden Snapshot nicht wieder geoeffnet`() {
        val base = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.ExecutionResult(id, true, false, 0.0),
                LedgerEvent.DeliveryProven(id, 0.0, "pump history"),
            ),
            cfg,
        )
        assertEquals(DeliveryState.CONFIRMED_ZERO, entry(base).delivery)
        assertEquals(0.0, base.transportCommitmentU, 1e-12)

        // fremder Datensatz -> keine Aenderung
        val foreign = IobAccountingSnapshot("h2", "c2", t0, 2L, listOf(AccountedTreatment(null, 999L, 1.20)), sourceEpochId = "epoch-test")
        assertUnaffected(base, LedgerReducer.reduce(base, LedgerEvent.IobSnapshotObserved(foreign), cfg))

        // (5) ein EXAKT gegenteiliger Nachweis ist kein normaler Widerspruch,
        // sondern ein unmoeglicher Zustand: zwei Nachweise schliessen sich aus.
        val contrary = IobAccountingSnapshot("h3", "c3", t0, 3L, listOf(AccountedTreatment(null, 4711L, 0.30)), sourceEpochId = "epoch-test")
        val s = LedgerReducer.reduce(base, LedgerEvent.IobSnapshotObserved(contrary), cfg)
        assertTrue(entry(s).errors.contains(LedgerError.IMPOSSIBLE_STATE_CONFLICT))
        assertTrue(s.holdActuation)
        // die Menge steckt im IOB, bindet also nicht zusaetzlich als Transport
        assertEquals(0.0, s.transportCommitmentU, 1e-12)

        // ein Datensatz mit Menge 0 widerspricht dagegen nicht - und bucht auch
        // nichts: im IOB steckt nichts Positives (R91-F1).
        val consistent = IobAccountingSnapshot("h4", "c4", t0, 4L, listOf(AccountedTreatment(null, 4711L, 0.0)), sourceEpochId = "epoch-test")
        val ok = LedgerReducer.reduce(base, LedgerEvent.IobSnapshotObserved(consistent), cfg)
        assertFalse(entry(ok).errors.contains(LedgerError.IMPOSSIBLE_STATE_CONFLICT))
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(ok).accounting)
        assertEquals(0.0, entry(ok).accountedAmountU)
        assertEquals(0.0, ok.transportCommitmentU, 1e-12)
    }

    // ---- R89-F1: Accounting ist eine Mengenbilanz -------------------------

    @Test
    fun `R89-F1 eine Teilmenge im IOB loescht nur diesen Teil der Haftung`() {
        val bound = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        fun snapshotWith(amount: Double, hash: String) = LedgerEvent.IobSnapshotObserved(
            IobAccountingSnapshot(hash, "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, amount)), sourceEpochId = "epoch-test")
        )

        // gross 0,30 / gebucht 0,10 -> Rest 0,20 (vorher: alles weg)
        val partial = LedgerReducer.reduce(bound, snapshotWith(0.10, "h1"), cfg)
        assertEquals(AccountingState.IOB_ACCOUNTED, entry(partial).accounting)
        assertEquals(0.10, entry(partial).accountedAmountU)
        assertEquals(0.20, partial.transportCommitmentU, 1e-12)
        assertFalse(entry(partial).closed)

        // gross 0,30 / gebucht 0,30 -> Rest 0
        val full = LedgerReducer.reduce(bound, snapshotWith(0.30, "h2"), cfg)
        assertEquals(0.0, full.transportCommitmentU, 1e-12)
        assertTrue(entry(full).closed)

        // gebucht MEHR als die Haftung -> Rest 0, nie negativ
        val more = LedgerReducer.reduce(bound, snapshotWith(0.50, "h3"), cfg)
        assertEquals(0.0, more.transportCommitmentU, 1e-12)

        // ein Datensatz ueber 0 U schliesst eine positive Verpflichtung NICHT.
        // Er wird aber REGISTRIERT (accountedAmountU = 0) - das ist die
        // Voraussetzung dafuer, dass eine Korrektur auf 0 spaeter greift.
        val zero = LedgerReducer.reduce(bound, snapshotWith(0.0, "h4"), cfg)
        assertEquals(0.30, zero.transportCommitmentU, 1e-12)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(zero).accounting)
        assertEquals(0.0, entry(zero).accountedAmountU)
        assertFalse(entry(zero).closed)
    }

    @Test
    fun `R89-F1 eine spaetere Mengenkorrektur verschiebt den Rest in beide Richtungen`() {
        val partial = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, 99L, null, "MEDTRUM", "h", t0),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h1", "c", t0, 1L, listOf(AccountedTreatment(99L, null, 0.10)), sourceEpochId = "epoch-test")
                ),
            ),
            cfg,
        )
        assertEquals(0.20, partial.transportCommitmentU, 1e-12)

        // Korrektur nach oben schliesst den Rest ...
        val up = LedgerReducer.reduce(partial, LedgerEvent.DbAmountObserved(id, 0.30), cfg)
        assertEquals(0.0, up.transportCommitmentU, 1e-12)
        // ... und eine Korrektur nach unten oeffnet ihn wieder
        val down = LedgerReducer.reduce(up, LedgerEvent.DbAmountObserved(id, 0.10), cfg)
        assertEquals(0.20, down.transportCommitmentU, 1e-12)
        assertFalse(entry(down).closed)

        // auch ein spaeterer Snapshot mit korrigierter Menge wirkt
        val revised = LedgerReducer.reduce(
            up,
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("h2", "c", t0, 2L, listOf(AccountedTreatment(99L, null, 0.05)), sourceEpochId = "epoch-test")
            ),
            cfg,
        )
        assertEquals(0.25, revised.transportCommitmentU, 1e-12)
        // der ERSTE beweisende Snapshot bleibt die Provenienz
        assertEquals("h1", entry(revised).firstAccountedSnapshotHash)
    }

    @Test
    fun `R89-F1 ein Nachweis bestimmt die Haftung, die Buchung nur den gebuchten Teil`() {
        val base = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        // DeliveryProven 0,10 senkt die Haftung auf 0,10 ...
        val proven = LedgerReducer.reduce(base, LedgerEvent.DeliveryProven(id, 0.10, "pump history"), cfg)
        assertEquals(0.10, proven.transportCommitmentU, 1e-12)
        // ... und die passende Buchung schliesst sie
        val accounted = LedgerReducer.reduce(
            proven,
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("h1", "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.10)), sourceEpochId = "epoch-test")
            ),
            cfg,
        )
        assertEquals(0.0, accounted.transportCommitmentU, 1e-12)
    }

    @Test
    fun `R89-F1 ein Neustart zwischen Teilbuchung und Korrektur verliert den Rest nicht`() {
        val partial = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h1", "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.10)), sourceEpochId = "epoch-test")
                ),
            ),
            cfg,
        )
        val restarted = LedgerReducer.reduce(partial, LedgerEvent.RestartObserved(t0 + 60_000L), cfg)
        assertEquals(0.20, restarted.transportCommitmentU, 1e-12)
        assertFalse(entry(restarted).closed)
    }

    @Test
    fun `R89-F2 ein positiver Betrag unter einer halben Pumpenstufe ist kein rundungsbedingtes Null`() {
        val base = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.ExecutionResult(id, true, false, 0.0),
                LedgerEvent.DeliveryProven(id, 0.0, "pump history"),
            ),
            cfg,
        )
        assertEquals(DeliveryState.CONFIRMED_ZERO, entry(base).delivery)
        // 0,02 U rundet auf 0 Pumpenstufen - fuer die logische Aussage
        // "Nullnachweis gegen positiven Fakt" zaehlt aber Positivitaet.
        assertEquals(0L, LedgerRules.canonicalTicks(0.02, cfg.bolusStepU))
        val s = LedgerReducer.reduce(
            base,
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("h9", "c", t0, 9L, listOf(AccountedTreatment(null, 4711L, 0.02)), sourceEpochId = "epoch-test")
            ),
            cfg,
        )
        assertTrue(entry(s).errors.contains(LedgerError.IMPOSSIBLE_STATE_CONFLICT))
        assertTrue(s.holdActuation)
    }

    // ---- R91: Reconciliation der Vollsicht --------------------------------

    /** Ein Snapshot ist die AKTUELLE VOLLSICHT, nicht eine Liste positiver
     *  Treffer. Diese Faelle pruefen die Gegenrichtungen. */
    private fun boundAndAccounted(accounted: Double): LedgerState = LedgerReducer.reduceAll(
        LedgerState(),
        throughPump(0.30) + listOf(
            LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("h1", "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, accounted)), sourceEpochId = "epoch-test")
            ),
        ),
        cfg,
    )

    @Test
    fun `R91-F1 eine Korrektur des Fakts auf 0 nimmt die Buchung zurueck`() {
        for (accounted in listOf(0.10, 0.30)) {
            val base = boundAndAccounted(accounted)
            assertEquals(0.30 - accounted, base.transportCommitmentU, 1e-12, "accounted=$accounted")

            val corrected = LedgerReducer.reduce(
                base,
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h2", "c", t0, 2L, listOf(AccountedTreatment(null, 4711L, 0.0)), sourceEpochId = "epoch-test")
                ),
                cfg,
            )
            assertEquals(0.30, corrected.transportCommitmentU, 1e-12, "accounted=$accounted")
            assertEquals(0.0, entry(corrected).accountedAmountU, "accounted=$accounted")
            assertEquals(AccountingState.NOT_ACCOUNTED, entry(corrected).accounting, "accounted=$accounted")
            assertFalse(entry(corrected).closed, "accounted=$accounted")
        }
    }

    @Test
    fun `R91-F1 ein verschwundener Fakt loescht die Buchung und wird sichtbar`() {
        for (accounted in listOf(0.10, 0.30)) {
            val base = boundAndAccounted(accounted)
            // Vollsicht OHNE unseren Datensatz - der Tail wuerde die Menge sonst
            // auf beiden Seiten verlieren.
            val gone = LedgerReducer.reduce(
                base,
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h2", "c", t0, 2L, listOf(AccountedTreatment(null, 999L, 1.20)), sourceEpochId = "epoch-test")
                ),
                cfg,
            )
            assertEquals(0.30, gone.transportCommitmentU, 1e-12, "accounted=$accounted")
            assertEquals(0.0, entry(gone).accountedAmountU, "accounted=$accounted")
            assertTrue(entry(gone).errors.contains(LedgerError.MISSING_ACCOUNTED_TREATMENT), "accounted=$accounted")
            assertTrue(gone.holdActuation, "accounted=$accounted")

            // Neustart dazwischen aendert daran nichts
            val viaRestart = LedgerReducer.reduceAll(
                base,
                listOf(
                    LedgerEvent.RestartObserved(t0 + 60_000L),
                    LedgerEvent.IobSnapshotObserved(
                        IobAccountingSnapshot("h3", "c", t0, 3L, listOf(AccountedTreatment(null, 999L, 1.20)), sourceEpochId = "epoch-test")
                    ),
                ),
                cfg,
            )
            assertEquals(0.30, viaRestart.transportCommitmentU, 1e-12, "accounted=$accounted")

            // Taucht der Fakt stabil wieder auf, wird neu gebucht - der
            // historische Fehler wird aber NICHT still geloescht.
            val back = LedgerReducer.reduce(
                gone,
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h4", "c", t0, 4L, listOf(AccountedTreatment(null, 4711L, accounted)), sourceEpochId = "epoch-test")
                ),
                cfg,
            )
            assertEquals(0.30 - accounted, back.transportCommitmentU, 1e-12, "accounted=$accounted")
            assertTrue(entry(back).errors.contains(LedgerError.MISSING_ACCOUNTED_TREATMENT), "accounted=$accounted")
            assertTrue(back.holdActuation, "accounted=$accounted")
        }
    }

    @Test
    fun `R91-F2 zwei passende Fakten sind reihenfolgeunabhaengig ein Fehler`() {
        val bound = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, 7L, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val a = AccountedTreatment(7L, null, 0.10)
        val b = AccountedTreatment(null, 4711L, 0.30)
        val results = listOf(listOf(a, b), listOf(b, a)).map { facts ->
            LedgerReducer.reduce(
                bound,
                LedgerEvent.IobSnapshotObserved(IobAccountingSnapshot("h1", "c", t0, 1L, facts, sourceEpochId = "epoch-test")),
                cfg,
            )
        }
        for (r in results) {
            assertTrue(entry(r).errors.contains(LedgerError.AMBIGUOUS_TREATMENT_IDENTITY))
            assertTrue(r.holdActuation)
            assertEquals(AccountingState.NOT_ACCOUNTED, entry(r).accounting)
            assertEquals(0.30, r.transportCommitmentU, 1e-12)
        }
        // beide Reihenfolgen ergeben denselben Zustand - nicht 0,20 gegen 0,00
        assertEquals(results[0].transportCommitmentU, results[1].transportCommitmentU)
        assertEquals(entry(results[0]).accountedAmountU, entry(results[1]).accountedAmountU)
    }

    @Test
    fun `R91-F3 Epsilon gilt auch fuer Rest und Abschluss, nicht nur fuer Vergleiche`() {
        val base = LedgerReducer.reduceAll(
            LedgerState(),
            listOf<LedgerEvent>(
                LedgerEvent.Proposed(id, 0.3000000004, t0, t0 - 600_000L),
                LedgerEvent.AmountObserved(id, AmountStage.PUMP_COMMAND, 0.3000000004),
                LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("h1", "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.3000000000)), sourceEpochId = "epoch-test")
                ),
            ),
            cfg,
        )
        // Differenz 4e-10 liegt unter amountEps 1e-9 -> kanonisch 0
        assertEquals(0.0, base.transportCommitmentU, 0.0)
        assertTrue(entry(base).closed)
        assertEquals(cfg.amountEpsU, entry(base).amountEpsU)
    }

    @Test
    fun `R91-F4 mehr gebucht als gehaftet ist sichtbar, aber nicht sperrend`() {
        val over = boundAndAccounted(0.50)
        assertTrue(entry(over).overAccounted)
        assertTrue(entry(over).errors.contains(LedgerError.OVERACCOUNTED_CONSERVATIVE))
        assertEquals(0.0, over.transportCommitmentU, 1e-12)
        // konservativ - also kein Hold
        assertFalse(over.holdActuation)
        assertFalse(entry(over).failClosed)
    }

    // ---- R93: gepinnte Policy und Snapshot-Ordnung ------------------------

    @Test
    fun `R93-F1 nach dem Vorschlag gilt ausschliesslich die gepinnte Policy`() {
        val policyA = LedgerConfig(bolusStepU = 0.05, amountEpsU = 1e-9)
        val policyB = LedgerConfig(bolusStepU = 0.01, amountEpsU = 0.10)
        val events = throughPump(0.30) + listOf(
            LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0),
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("h1", "c", t0, 1L, listOf(AccountedTreatment(null, 4711L, 0.05)), sourceEpochId = "epoch-test")
            ),
            LedgerEvent.ExecutionResult(id, true, false, 0.25),
        )
        // Alles unter A
        val allA = LedgerReducer.reduceAll(LedgerState(), events, policyA)
        // Erst A (nur der Vorschlag), dann ALLES uebrige unter B
        val mixed = LedgerReducer.reduceAll(
            LedgerReducer.reduce(LedgerState(), events.first(), policyA),
            events.drop(1),
            policyB,
        )
        assertEquals(allA.entries.getValue(id), mixed.entries.getValue(id))
        assertEquals(allA.transportCommitmentU, mixed.transportCommitmentU, 0.0)
        // Unter B waere 0,05 kein positiver Fakt gewesen (eps 0,10) und die
        // Lieferklassifikation haette mit 0,01er-Stufen gerechnet.
        assertEquals(AccountingState.IOB_ACCOUNTED, entry(mixed).accounting)
        assertEquals(1e-9, entry(mixed).amountEpsU)
        assertEquals(0.05, entry(mixed).bolusStepU)
    }

    @Test
    fun `R93-F1 eine ungueltige Policy wird beim Vorschlag abgewiesen`() {
        for (bad in listOf(
            LedgerConfig(bolusStepU = 0.05, amountEpsU = Double.NaN),
            LedgerConfig(bolusStepU = 0.05, amountEpsU = -1.0),
            LedgerConfig(bolusStepU = 0.0, amountEpsU = 1e-9),
        )) {
            val s = LedgerReducer.reduce(LedgerState(), proposed(0.30), bad)
            assertTrue(s.entries.isEmpty(), bad.toString())
            assertTrue(s.holdActuation, bad.toString())
        }
    }

    @Test
    fun `R93-F2 ein aelterer Snapshot rollt einen neueren Zustand nicht zurueck`() {
        val bound = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val s2 = LedgerReducer.reduce(
            bound,
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("v2", "c", t0 + 200_000L, 2L, listOf(AccountedTreatment(null, 4711L, 0.30)), sourceEpochId = "epoch-test")
            ),
            cfg,
        )
        assertEquals(0.0, s2.transportCommitmentU, 1e-12)

        // verspaeteter aelterer Snapshot mit kleinerer Menge
        val late = LedgerReducer.reduce(
            s2,
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("v1", "c", t0 + 100_000L, 1L, listOf(AccountedTreatment(null, 4711L, 0.10)), sourceEpochId = "epoch-test")
            ),
            cfg,
        )
        assertEquals(0.0, late.transportCommitmentU, 1e-12)
        assertEquals(0.30, entry(late).accountedAmountU)
        assertTrue(late.errors.any { it.error == LedgerError.STALE_SNAPSHOT_IGNORED })
        // stale ist normal in einem nebenlaeufigen System - kein Hold
        assertFalse(late.holdActuation)

        // und der gefaehrlichere Fall: ein verspaeteter LEERER Snapshot
        val lateEmpty = LedgerReducer.reduce(
            s2,
            LedgerEvent.IobSnapshotObserved(IobAccountingSnapshot("v0", "c", t0, 1L, emptyList(), sourceEpochId = "epoch-test")),
            cfg,
        )
        assertEquals(0.0, lateEmpty.transportCommitmentU, 1e-12)
        assertFalse(entry(lateEmpty).errors.contains(LedgerError.MISSING_ACCOUNTED_TREATMENT))
        assertFalse(lateEmpty.holdActuation)
    }

    @Test
    fun `R93-F2 gleiche Ordnung mit anderem Inhalt ist ein Widerspruch`() {
        val base = LedgerReducer.reduce(
            LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg),
            LedgerEvent.IobSnapshotObserved(IobAccountingSnapshot("va", "c", t0, 1L, emptyList(), sourceEpochId = "epoch-test")),
            cfg,
        )
        val conflicting = LedgerReducer.reduce(
            base,
            LedgerEvent.IobSnapshotObserved(IobAccountingSnapshot("vb", "c", t0, 1L, emptyList(), sourceEpochId = "epoch-test")),
            cfg,
        )
        assertTrue(conflicting.errors.any { it.error == LedgerError.SNAPSHOT_ORDER_CONFLICT })
        assertTrue(conflicting.holdActuation)
    }

    @Test
    fun `R93-F2 eine neue Epoch wird rebasiert, nicht verglichen`() {
        val base = LedgerReducer.reduce(
            LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg),
            LedgerEvent.IobSnapshotObserved(
                IobAccountingSnapshot("va", "c", t0 + 500_000L, 9L, emptyList(), sourceEpochId = "epoch-1")
            ),
            cfg,
        )
        // Nach dem Neustart beginnt die Zaehlung neu - kleinere Zahlen, aber
        // NICHT stale: ueber Epochgrenzen wird nicht verglichen.
        // R95-F2: der Wechsel muss ANGEKUENDIGT sein, sonst waere die Epoch ein
        // freier Reset-Knopf fuer die Monotonie.
        val rebased = LedgerReducer.reduceAll(
            base,
            listOf(
                LedgerEvent.SnapshotSourceRestarted("epoch-1", "epoch-2", "calculator restarted"),
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("vb", "c", t0, 1L, emptyList(), sourceEpochId = "epoch-2")
                ),
            ),
            cfg,
        )
        assertTrue(rebased.errors.any { it.error == LedgerError.SNAPSHOT_EPOCH_REBASED })
        assertFalse(rebased.errors.any { it.error == LedgerError.STALE_SNAPSHOT_IGNORED })
        assertEquals("epoch-2", rebased.lastSnapshotOrder!!.sourceEpochId)
    }

    @Test
    fun `R93-F4 auch die bestaetigte Abwesenheit aktualisiert die Provenienz`() {
        val bound = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, null, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        assertNull(entry(bound).lastReconciledViewHash)
        val absent = LedgerReducer.reduce(
            bound,
            LedgerEvent.IobSnapshotObserved(IobAccountingSnapshot("v1", "c", t0, 1L, emptyList(), sourceEpochId = "epoch-test")),
            cfg,
        )
        assertEquals("v1", entry(absent).lastReconciledViewHash)
        assertEquals(t0, entry(absent).lastReconciledAtTs)
        // ohne inhaltliche Folge: Buchung und Rest unveraendert
        assertEquals(0.30, absent.transportCommitmentU, 1e-12)
        assertFalse(absent.holdActuation)
    }

    @Test
    fun `R93-F5 ein dauerhafter Fehler waechst im Zaehler, nicht in der Liste`() {
        var s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, 7L, 4711L, "VIRTUAL", "h", t0)),
            cfg,
        )
        val ambiguous = listOf(AccountedTreatment(7L, null, 0.10), AccountedTreatment(null, 4711L, 0.30))
        for (i in 1..10_000) {
            s = LedgerReducer.reduce(
                s,
                LedgerEvent.IobSnapshotObserved(
                    IobAccountingSnapshot("view-$i", "c", t0 + i * 60_000L, i.toLong(), ambiguous, sourceEpochId = "epoch-test")
                ),
                cfg,
            )
        }
        // EIN Eintrag, nicht 10.000
        assertEquals(1, s.errors.size)
        val rec = s.errors.single()
        assertEquals(LedgerError.AMBIGUOUS_TREATMENT_IDENTITY, rec.error)
        assertEquals(10_000, rec.occurrences)
        assertTrue(rec.firstDetail.contains("view-1"))
        assertTrue(rec.lastDetail.contains("view-10000"))
        assertTrue(s.holdActuation)
    }

    @Test
    fun `R95-F1 die Quittung loest den GLOBALEN Hold, nicht nur das Zeilenflag`() {
        val held = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.ProposalIdLost(id, "DetailedBolusInfo")),
            cfg,
        )
        assertTrue(held.holdActuation)
        val gen = held.holdGeneration

        fun ackWith(by: String, reason: String, g: Long, errs: Set<LedgerError>) =
            LedgerReducer.reduce(held, LedgerEvent.HoldAcknowledged(id, by, reason, g, errs), cfg)

        // ungueltige Quittungen aendern nichts - und erzeugen selbst KEINEN
        // dauerhaften globalen Fehler, sonst waere der Weg zurueck endgueltig zu
        val invalid = listOf(
            "leer" to ackWith("", "weil", gen, setOf(LedgerError.PROPOSAL_ID_LOST)),
            "ohne Keys" to ackWith("toni", "weil", gen, emptySet()),
            "veraltete Generation" to ackWith("toni", "weil", gen - 1, setOf(LedgerError.PROPOSAL_ID_LOST)),
            "nicht quittierbar" to ackWith("toni", "weil", gen, setOf(LedgerError.IMPOSSIBLE_STATE_CONFLICT)),
            "nichts aktiv" to ackWith("toni", "weil", gen, setOf(LedgerError.OVERDELIVERY_ANOMALY)),
        )
        for ((name, s) in invalid) {
            assertTrue(s.holdActuation, "$name haette nicht freigeben duerfen")
            assertTrue(s.errors.none { it.active && it.error == LedgerError.HOLD_ACKNOWLEDGED }, name)
        }

        // die gueltige Quittung
        val ack = ackWith("toni", "transienter Adapterfehler geprueft", gen, setOf(LedgerError.PROPOSAL_ID_LOST))
        assertFalse(ack.holdActuation, "der GLOBALE Hold muss fallen")
        assertFalse(entry(ack).failClosed)
        // Historie bleibt vollstaendig, nur nicht mehr aktiv
        val resolved = ack.errors.single { it.error == LedgerError.PROPOSAL_ID_LOST }
        assertFalse(resolved.active)
        assertEquals("toni", resolved.resolvedBy)
        assertEquals(1, resolved.occurrences)
        assertTrue(entry(ack).errors.contains(LedgerError.PROPOSAL_ID_LOST))
        // und die Menge bleibt gebucht
        assertEquals(0.30, ack.transportCommitmentU, 1e-12)

        // Ein ERNEUTES Auftreten macht den Fehler wieder aktiv - eine
        // Unterschrift gilt fuer das Gesehene, nicht fuer die Zukunft.
        val again = LedgerReducer.reduce(ack, LedgerEvent.ProposalIdLost(id, "erneut"), cfg)
        assertTrue(again.holdActuation)
        assertEquals(2, again.errors.single { it.error == LedgerError.PROPOSAL_ID_LOST }.occurrences)
    }

    @Test
    fun `R95-F1 eine Teilquittung laesst den Hold bestehen, solange ein Fehler aktiv ist`() {
        val held = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.ProposalIdLost(id, "station"),
                LedgerEvent.QueueRejected(id, QueueRejectReason.OTHER),   // PHASE_VIOLATION
            ),
            cfg,
        )
        assertTrue(held.holdActuation)
        assertEquals(2, held.activeHoldErrors.size)

        val partial = LedgerReducer.reduce(
            held,
            LedgerEvent.HoldAcknowledged(id, "toni", "nur den einen", held.holdGeneration, setOf(LedgerError.PROPOSAL_ID_LOST)),
            cfg,
        )
        assertTrue(partial.holdActuation, "der zweite Fehler ist noch aktiv")
        assertTrue(entry(partial).failClosed)
        assertEquals(1, partial.activeHoldErrors.size)

        val full = LedgerReducer.reduce(
            partial,
            LedgerEvent.HoldAcknowledged(id, "toni", "und den zweiten", partial.holdGeneration, setOf(LedgerError.PHASE_VIOLATION)),
            cfg,
        )
        assertFalse(full.holdActuation)
        assertFalse(entry(full).failClosed)
    }

    @Test
    fun `R95-F2 ein Epochwechsel gilt nur angekuendigt und nie zweimal`() {
        fun snap(epoch: String, gen: Long, at: Long) = LedgerEvent.IobSnapshotObserved(
            IobAccountingSnapshot("v-$epoch-$gen", "c", at, gen, emptyList(), sourceEpochId = epoch)
        )
        val base = LedgerReducer.reduce(
            LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg),
            snap("epoch-A", 100L, t0 + 100_000L),
            cfg,
        )
        assertEquals("epoch-A", base.lastSnapshotOrder!!.sourceEpochId)

        // UNANGEKUENDIGT -> abgewiesen. Sonst waere die Epoch der Reset-Knopf
        // fuer die Monotonie: A(100) -> B(1) -> A(50) waere dreimal durchgegangen.
        val sneaky = LedgerReducer.reduce(base, snap("epoch-B", 1L, t0), cfg)
        assertTrue(sneaky.errors.any { it.error == LedgerError.SNAPSHOT_ORDER_CONFLICT })
        assertEquals("epoch-A", sneaky.lastSnapshotOrder!!.sourceEpochId)

        // angekuendigt -> akzeptiert
        val announced = LedgerReducer.reduceAll(
            base,
            listOf(
                LedgerEvent.SnapshotSourceRestarted("epoch-A", "epoch-B", "calculator pid 4711 started"),
                snap("epoch-B", 1L, t0),
            ),
            cfg,
        )
        assertEquals("epoch-B", announced.lastSnapshotOrder!!.sourceEpochId)

        // Rueckkehr auf eine SCHON BENUTZTE Epoch ist kein Rebase
        val reused = LedgerReducer.reduce(
            announced,
            LedgerEvent.SnapshotSourceRestarted("epoch-B", "epoch-A", "replay alter Daten"),
            cfg,
        )
        assertTrue(reused.errors.any { it.error == LedgerError.SNAPSHOT_ORDER_CONFLICT })
        assertTrue(reused.holdActuation)

        // leere und default-Epoch ebenfalls nicht
        for (bad in listOf("", "default")) {
            val r = LedgerReducer.reduce(
                announced, LedgerEvent.SnapshotSourceRestarted("epoch-B", bad, "x"), cfg
            )
            assertTrue(r.errors.any { it.error == LedgerError.SNAPSHOT_ORDER_CONFLICT }, "epoch='$bad'")
        }
    }

    @Test
    fun `R95-F4 eine unbrauchbare Snapshot-Ordnung wird abgewiesen, nicht interpretiert`() {
        val base = LedgerReducer.reduceAll(LedgerState(), throughPump(0.30), cfg)
        val bad = listOf(
            IobAccountingSnapshot("v", "c", t0, 1L, emptyList(), sourceEpochId = ""),
            IobAccountingSnapshot("v", "c", t0, 1L, emptyList(), sourceEpochId = "default"),
            IobAccountingSnapshot("v", "c", 0L, 1L, emptyList(), sourceEpochId = "epoch-A"),
            IobAccountingSnapshot("v", "c", t0, -1L, emptyList(), sourceEpochId = "epoch-A"),
        )
        for (b in bad) {
            val s = LedgerReducer.reduce(base, LedgerEvent.IobSnapshotObserved(b), cfg)
            assertTrue(s.errors.any { it.error == LedgerError.SNAPSHOT_ORDER_CONFLICT }, b.toString())
            assertNull(s.lastSnapshotOrder, b.toString())
        }
    }

    // ---- G3 (Codex-Adjudication bae885f1): Null-Beweis vs. Terminalmeldung -

    /**
     * DER G3-Repro: ein frueher Null-Beweis liess eine spaetere POSITIVE
     * Liefermeldung ins Leere laufen - `grossLiabilityU` gab dem Nachweis
     * unbedingten Vorrang, die Haftung blieb 0 und die Zeile geschlossen.
     * Zwei Beweise, die sich widersprechen, sind aber kein Fortschritt,
     * sondern ein unmoeglicher Zustand: Hold + konservatives Maximum.
     */
    @Test
    fun `G3 eine positive Terminalmeldung nach bewiesener Null ist ein Widerspruch`() {
        val proven = run(proposed(0.30), LedgerEvent.DeliveryProven(id, 0.0, "pump history"))
        assertEquals(DeliveryState.CONFIRMED_ZERO, entry(proven).delivery)
        assertEquals(0.0, proven.transportCommitmentU, 1e-12)

        val s = LedgerReducer.reduce(proven, LedgerEvent.ExecutionResult(id, true, true, 0.30), cfg)
        val e = entry(s)
        assertTrue(e.contradicted, "Widerspruch nicht markiert")
        assertTrue(e.errors.contains(LedgerError.IMPOSSIBLE_STATE_CONFLICT), "kein Fehlereintrag: ${e.errors}")
        assertTrue(s.holdActuation, "der Widerspruch sperrt nicht")
        assertTrue(e.grossLiabilityU >= 0.30, "grossLiability ${e.grossLiabilityU} < 0.30")
        assertEquals(0.30, s.transportCommitmentU, 1e-12)
        assertFalse(e.closed, "die Zeile gilt trotz Widerspruch als geschlossen")
    }

    /** Dieselbe Aussage in der anderen Reihenfolge (Ordnungsinvarianz): der
     *  Null-Beweis trifft NACH der positiven Meldung ein. Auch dann darf die
     *  bestaetigte Null die Haftung nicht loeschen. */
    @Test
    fun `G3 auch der spaetere Null-Beweis loescht eine positive Meldung nicht`() {
        val s = run(
            proposed(0.30),
            LedgerEvent.ExecutionResult(id, true, true, 0.30),
            LedgerEvent.DeliveryProven(id, 0.0, "pump history"),
        )
        val e = entry(s)
        assertTrue(e.contradicted)
        assertTrue(e.errors.contains(LedgerError.IMPOSSIBLE_STATE_CONFLICT), "kein Fehlereintrag: ${e.errors}")
        assertTrue(s.holdActuation)
        assertTrue(e.grossLiabilityU >= 0.30, "grossLiability ${e.grossLiabilityU} < 0.30")
        assertEquals(0.30, s.transportCommitmentU, 1e-12)
    }

    /** Der Widerspruch ist NICHT quittierbar: er braucht eine Reparatur,
     *  keine Unterschrift (fail-closed, s. FAIL_CLOSED_ERRORS). */
    @Test
    fun `G3 der Widerspruch ist fail-closed und nicht quittierbar`() {
        assertTrue(LedgerError.IMPOSSIBLE_STATE_CONFLICT in LedgerState.FAIL_CLOSED_ERRORS)
        assertFalse(LedgerError.IMPOSSIBLE_STATE_CONFLICT in LedgerState.RECOVERABLE_ERRORS)
    }

    /** GEGENPROBE: der Null-Beweis OHNE spaetere positive Gegenmeldung bleibt
     *  genau das, was er war - CONFIRMED_ZERO mit Commitment 0. Der Fix darf
     *  den belegten Normalfall nicht mit anhalten. */
    @Test
    fun `G3 ein unwidersprochener Null-Beweis bleibt eine bestaetigte Null`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(
                LedgerEvent.ExecutionResult(id, true, false, 0.0),
                LedgerEvent.DeliveryProven(id, 0.0, "pump history"),
            ),
            cfg,
        )
        val e = entry(s)
        assertEquals(DeliveryState.CONFIRMED_ZERO, e.delivery)
        assertFalse(e.contradicted)
        assertFalse(e.errors.contains(LedgerError.IMPOSSIBLE_STATE_CONFLICT))
        assertFalse(s.holdActuation)
        assertEquals(0.0, e.grossLiabilityU, 1e-12)
        assertEquals(0.0, s.transportCommitmentU, 1e-12)
        assertTrue(e.closed)
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
        val broken = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(null, 4711L, Double.NaN)), sourceEpochId = "epoch-test")
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
        val snapshot = IobAccountingSnapshot("h1", "c1", t0, 1L, listOf(AccountedTreatment(7L, 9L, 0.30)), sourceEpochId = "epoch-test")
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(snapshot), cfg)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(after).accounting)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
        assertTrue(after.holdActuation)
        assertTrue(entry(after).errors.contains(LedgerError.IDENTITY_CONFLICT))
    }

    @Test
    fun `R79-F3 und R81-F2 Vertraeglichkeit unterscheidet Treffer, Nichttreffer und Konflikt`() {
        val id78 = PumpTreatmentIdentity(id, 7L, 8L, "MEDTRUM", "h", t0)
        fun t(temp: Long?, pump: Long?) = AccountedTreatment(temp, pump, 0.30)
        assertEquals(IdentityMatch.MATCH, id78.compatibility(t(7L, 8L)))
        assertEquals(IdentityMatch.MATCH, id78.compatibility(t(7L, null)))
        assertEquals(IdentityMatch.MATCH, id78.compatibility(t(null, 8L)))
        assertEquals(IdentityMatch.CONFLICT, id78.compatibility(t(7L, 9L)))
        assertEquals(IdentityMatch.CONFLICT, id78.compatibility(t(6L, 8L)))
        assertEquals(IdentityMatch.NO_MATCH, id78.compatibility(t(null, null)))
        // R81-F2: OHNE gemeinsamen Anker ist es ein FREMDER Datensatz, kein
        // Konflikt. Ein normaler IOB-Snapshot enthaelt fremde Boli.
        assertEquals(IdentityMatch.NO_MATCH, id78.compatibility(t(6L, 9L)))
        assertEquals(IdentityMatch.NO_MATCH, id78.compatibility(t(100L, null)))
        val tempOnly = PumpTreatmentIdentity(id, 7L, null, "MEDTRUM", "h", t0)
        assertEquals(IdentityMatch.NO_MATCH, tempOnly.compatibility(t(null, 9L)))
        assertEquals(IdentityMatch.NO_MATCH, tempOnly.compatibility(t(100L, null)))
        assertEquals(IdentityMatch.MATCH, tempOnly.compatibility(t(7L, 9L)))
    }

    @Test
    fun `R83-F3 die Geraeteprovenienz entscheidet mit`() {
        val id7 = PumpTreatmentIdentity(id, 7L, null, "MEDTRUM", "serialA", t0)
        // gleiche temporaryId, ANDERE Pumpe -> kein Treffer, sondern Widerspruch
        assertEquals(
            IdentityMatch.CONFLICT,
            id7.compatibility(AccountedTreatment(7L, null, 0.30, pumpType = "MEDTRUM", pumpSerialHash = "serialB"))
        )
        assertEquals(
            IdentityMatch.CONFLICT,
            id7.compatibility(AccountedTreatment(7L, null, 0.30, pumpType = "VIRTUAL", pumpSerialHash = "serialA"))
        )
        // passende Provenienz -> Treffer
        assertEquals(
            IdentityMatch.MATCH,
            id7.compatibility(AccountedTreatment(7L, null, 0.30, pumpType = "MEDTRUM", pumpSerialHash = "serialA"))
        )
        // keine Angabe ist keine Aussage - und kein Konflikt
        assertEquals(IdentityMatch.MATCH, id7.compatibility(AccountedTreatment(7L, null, 0.30)))

        // und derselbe Fall am Reducer: die Zeile wird NICHT ausgebucht
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, 7L, null, "MEDTRUM", "serialA", t0)),
            cfg,
        )
        val foreignPump = IobAccountingSnapshot(
            "h1", "c1", t0, 1L,
            listOf(AccountedTreatment(7L, null, 0.30, "MEDTRUM", "serialB"))
        , sourceEpochId = "epoch-test")
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(foreignPump), cfg)
        assertEquals(AccountingState.NOT_ACCOUNTED, entry(after).accounting)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
        assertTrue(after.holdActuation)
    }

    @Test
    fun `R81-F2 ein Snapshot voller fremder Boli setzt keine Zeile fail-closed`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, 7L, null, "MEDTRUM", "h", t0)),
            cfg,
        )
        val onlyForeign = IobAccountingSnapshot(
            "h1", "c1", t0, 1L,
            listOf(
                AccountedTreatment(100L, null, 1.20),
                AccountedTreatment(null, 555L, 0.30),
                AccountedTreatment(101L, 556L, 2.50),
            )
        , sourceEpochId = "epoch-test")
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(onlyForeign), cfg)
        assertUnaffected(s, after)
        assertFalse(after.holdActuation)
        assertEquals(0.30, after.transportCommitmentU, 1e-12)
    }

    @Test
    fun `R81-F2 fremde Boli vor und nach dem echten Treffer stoeren die Buchung nicht`() {
        val s = LedgerReducer.reduceAll(
            LedgerState(),
            throughPump(0.30) + listOf(LedgerEvent.PumpIdentityBound(id, 7L, null, "MEDTRUM", "h", t0)),
            cfg,
        )
        val mixed = IobAccountingSnapshot(
            "h1", "c1", t0, 1L,
            listOf(
                AccountedTreatment(100L, null, 1.20),   // fremd, VOR dem Treffer
                AccountedTreatment(7L, null, 0.30),     // unser Datensatz
                AccountedTreatment(101L, null, 2.50),   // fremd, danach
            )
        , sourceEpochId = "epoch-test")
        val after = LedgerReducer.reduce(s, LedgerEvent.IobSnapshotObserved(mixed), cfg)
        assertEquals(AccountingState.IOB_ACCOUNTED, entry(after).accounting)
        assertEquals(0.0, after.transportCommitmentU, 1e-12)
        assertFalse(after.holdActuation)
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

    /** Deterministischer LCG — der frueher benutzte Ausdruck
     *  `(i*31 + seed*17) % size` erzeugte trotz 300 Seeds nur 14 verschiedene
     *  Ordnungen (R81-F7). Der Seed steht im Fehlertext, damit jeder Fall
     *  reproduzierbar ist. */
    private fun shuffledOrder(size: Int, seed: Int): List<Int> {
        var s = (seed * 2_654_435_761L + 12_345L) and 0xFFFFFFFFL
        fun next(): Long {
            s = (s * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L) ushr 1
            return s
        }
        val idx = (0 until size).toMutableList()
        for (i in size - 1 downTo 1) {
            val j = (next() % (i + 1)).toInt()
            val tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp
        }
        return idx
    }

    @Test
    fun `R81-F7 der Reihenfolgengenerator erzeugt wirklich verschiedene Ordnungen`() {
        val seen = (0 until 300).map { shuffledOrder(14, it) }.toSet()
        assertTrue(seen.size > 250, "nur ${seen.size} verschiedene Ordnungen")
    }

    @Test
    fun `R81-F7 jeder Zweier- und Dreieruebergang der kritischen Ereignisse haelt die Invariante`() {
        val critical = listOf<LedgerEvent>(
            LedgerEvent.QueueAccepted(id),
            LedgerEvent.QueueRejected(id, QueueRejectReason.OTHER),
            LedgerEvent.QueueWithdrawnProven(id, "e"),
            amount(AmountStage.PUMP_COMMAND, 0.30),
            LedgerEvent.ExecutionResult(id, true, false, 0.30),
            LedgerEvent.DeliveryProven(id, 0.30, "e"),
        )
        var checked = 0
        for (a in critical) for (b in critical) for (c in critical) {
            val s = LedgerReducer.reduceAll(LedgerState(), listOf(proposed(0.30), a, b, c), cfg)
            val e = entry(s)
            val commitment = s.transportCommitmentU
            assertTrue(commitment.isFinite(), "$a|$b|$c -> $commitment")
            // Die Kernaussage: nichts faellt auf 0, solange ein Lieferzeichen
            // ODER ein Fortsetzungsindiz ohne IOB-Nachweis existiert (R83-F2 —
            // anyDeliverySignal allein war dafuer zu schwach).
            if ((e.anyDeliverySignal || e.contradicted) && e.accounting == AccountingState.NOT_ACCOUNTED &&
                e.delivery != DeliveryState.CONFIRMED_ZERO
            ) assertTrue(commitment > 0.0, "$a|$b|$c -> $commitment")
            // Und: eine Befreiung gibt es nur ueber den einen erlaubten Weg.
            if (commitment == 0.0 && e.accounting == AccountingState.NOT_ACCOUNTED)
                assertTrue(e.debtReleaseEffective, "$a|$b|$c befreit ohne Grund")
            checked++
        }
        assertEquals(216, checked)
    }

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
        for (seed in 0 until 300) {
            val order = shuffledOrder(vocabulary.size, seed)
            val events = listOf<LedgerEvent>(proposed(0.30)) + order.map { vocabulary[it] }
            val s = LedgerReducer.reduceAll(LedgerState(), events, cfg)
            val c = s.transportCommitmentU
            assertTrue(c.isFinite(), "seed=$seed commitment=$c")
            assertTrue(c >= 0.0, "seed=$seed commitment=$c")
            // Sobald ein Lieferzeichen ODER ein Fortsetzungsindiz existiert,
            // darf nichts mehr auf 0 fallen, solange die Menge nicht im IOB
            // nachgewiesen ist.
            val e = entry(s)
            if ((e.anyDeliverySignal || e.contradicted) && e.accounting == AccountingState.NOT_ACCOUNTED &&
                e.delivery != DeliveryState.CONFIRMED_ZERO
            ) assertTrue(c > 0.0, "seed=$seed: Fortsetzung, aber Buchung $c")
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
