package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.insulin.UnitInsulinSampler
import app.aaps.fuse.core.ledger.AccountedTreatment
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.plugin.ledger.OpenTransportItem
import app.aaps.fuse.plugin.ledger.TransportInclusion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C3-01 (P0, Codex Fix-Pass-5-Closure G.1/G.2/K) AN DER ECHTEN VERDRAHTUNG:
 * jeder offene Posten bekommt eigene Menge und eigenen Anker, und die
 * Resthaftung am Horizont ist die Summe der EINZELN gerechneten Restwirkungen.
 *
 * Gerechnet wird mit Codex' linearem 240-min-Testkernel, damit die Zahlen des
 * Audits nachvollziehbar bleiben.
 */
class PendingTransportMultiCommitmentTest {

    private val t0 = 1_700_000_000_000L
    private val supportMin = 240
    private val hMin = 60
    private val horizonTs = t0 + hMin * 60_000L

    /** Codex' Testkernel: Restwirkung faellt linear von 1 U auf 0 in 240 min,
     *  Aktivitaet konstant 1/240 U/min. */
    private fun kernel(deliveryTs: Long = t0): UnitInsulinKernel {
        val sampler = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= supportMin) InsulinSample(0.0, 0.0)
            else InsulinSample(doseU * (1.0 - offsetMin / supportMin.toDouble()), doseU / supportMin)
        }
        return (UnitInsulinKernelBuilder.build(
            sampler, deliveryTs, InsulinModelProvenance("TEST", 4.0, 60, "test"), "test"
        ) as KernelOutcome.Ok).kernel
    }

    /** Restwirkung, wie Codex sie fuer einen EINZELN modellierten Posten
     *  rechnet: Alter jetzt + Horizont. */
    private fun getrennteRestwirkung(amountU: Double, ageMin: Double): Double =
        amountU * maxOf(0.0, 1.0 - (ageMin + hMin) / supportMin)

    private fun item(id: String, u: Double, ageMin: Long) = OpenTransportItem(
        proposalId = id,
        commitmentU = u,
        grossLiabilityU = u,
        accountedAmountU = 0.0,
        bestKnownTs = t0 - ageMin * 60_000L,
        temporaryId = null,
        pumpId = null,
        settledZero = false,
    )

    private fun doses(vararg items: OpenTransportItem) =
        FuseCycleRunner.transportDoses(items.toList(), witness = null, computeTs = t0)

    // ---- Codex' Gegenprobe an der Verdrahtung ----------------------------

    /**
     * P1 = 0,10 U (12 min alt), P2 = 0,30 U (2 min alt), H = 60 min.
     *
     *   getrennt   = 0,2925 U
     *   aggregiert am aeltesten Anker = 0,2800 U   <- die alte Untererfassung
     */
    @Test
    fun `zwei Posten haften einzeln gerechnet mindestens so viel wie getrennt modelliert`() {
        val k = kernel()
        val d = doses(item("p1", 0.10, ageMin = 12), item("p2", 0.30, ageMin = 2))
        assertEquals(2, d.size) { "die Posten wurden zusammengefasst" }

        val summe = TailLiability.sumOf(d.map { FuseCycleRunner.tailTransportDose(it, k, horizonTs) })
        val getrennt = getrennteRestwirkung(0.10, 12.0) + getrennteRestwirkung(0.30, 2.0)
        val alteAggregation = getrennteRestwirkung(0.40, 12.0)

        assertEquals(0.2925, getrennt, 1e-9)
        assertEquals(0.2800, alteAggregation, 1e-9)
        assertTrue(summe.liabilityAtHU >= 0.2925 - 1e-9) {
            "Haftung ${summe.liabilityAtHU} unter der getrennten Modellierung 0,2925"
        }
        assertTrue(summe.liabilityAtHU > alteAggregation + 1e-9) {
            "die Oldest-Aggregation wird nicht ueberboten"
        }
        assertEquals(0.40, summe.amountU, 1e-12)
    }

    /**
     * PROPERTY ueber ein Raster aus Mengen und Altern: die tatsaechlich
     * gerechnete Haftung liegt NIE unter der Summe der getrennt modellierten
     * Einzelhaftungen - und nie unter der alten Oldest-Aggregation.
     */
    @Test
    fun `Property - nie weniger konservativ als jedes einzeln modellierte Paar`() {
        val k = kernel()
        val mengen = listOf(0.05, 0.10, 0.30, 0.90)
        val alter = listOf(0L, 1L, 2L, 5L, 12L, 25L, 30L)
        for (u1 in mengen) for (u2 in mengen) for (a1 in alter) for (a2 in alter) {
            val d = doses(item("p1", u1, a1), item("p2", u2, a2))
            val summe = TailLiability.sumOf(d.map { FuseCycleRunner.tailTransportDose(it, k, horizonTs) })
            val getrennt = getrennteRestwirkung(u1, a1.toDouble()) + getrennteRestwirkung(u2, a2.toDouble())
            val oldest = getrennteRestwirkung(u1 + u2, maxOf(a1, a2).toDouble())
            assertTrue(summe.liabilityAtHU >= getrennt - 1e-9) {
                "u=($u1,$u2) alter=($a1,$a2) -> ${summe.liabilityAtHU} < getrennt $getrennt"
            }
            assertTrue(summe.liabilityAtHU >= oldest - 1e-9) {
                "u=($u1,$u2) alter=($a1,$a2) -> ${summe.liabilityAtHU} < oldest $oldest"
            }
        }
    }

    // ---- Die Ankerwahl (G.1) ---------------------------------------------

    /**
     * ZWEI ANKER, ZWEI ZWECKE. Fuer die BAHN ist FRUEH konservativ (mehr
     * Wirkung faellt ins Bewertungsfenster, die Bahn sinkt); fuer die
     * RESTHAFTUNG am Horizont ist SPAET konservativ (weniger Wirkung ist
     * verbraucht, es bleibt mehr uebrig). Ein einziger Anker kann nicht beides
     * sein - deshalb traegt jeder Posten beide Enden seiner plausiblen
     * Lieferspanne.
     */
    @Test
    fun `jeder Posten traegt frueheste und spaeteste plausible Lieferzeit`() {
        val d = doses(item("p1", 0.10, ageMin = 12)).single()
        assertEquals(t0 - 12 * 60_000L, d.earliestTs) { "die Bahn braucht den FRUEHESTEN Anker" }
        assertEquals(t0, d.latestTs) { "die Resthaftung braucht den SPAETESTEN Anker" }
    }

    /** Die 30-min-Kappe bleibt: ein unbrauchbar alter Anker wuerde den
     *  Modelltraeger aus dem Bewertungsfenster schieben. */
    @Test
    fun `ein uralter Posten wird auf die Messgrenze gekappt`() {
        val d = doses(item("p1", 0.10, ageMin = 360)).single()
        assertEquals(t0 - FuseCycleRunner.TRANSPORT_ANCHOR_MAX_AGE_MIN * 60_000L, d.earliestTs)
        assertEquals(t0, d.latestTs)
    }

    /** Die Resthaftung nimmt den Anker mit der GROESSEREN Restwirkung - hier
     *  ist das der spaete. Gegenprobe: der fruehe allein waere kleiner. */
    @Test
    fun `die Resthaftung nimmt den konservativeren der beiden Anker`() {
        val k = kernel()
        val d = doses(item("p1", 0.30, ageMin = 20)).single()
        val gerechnet = FuseCycleRunner.tailTransportDose(d, k, horizonTs)
        val nurFrueh = k.iobAt(horizonTs - d.earliestTs + k.deliveryTs, 0.30)
        val nurSpaet = k.iobAt(horizonTs - d.latestTs + k.deliveryTs, 0.30)
        assertTrue(nurSpaet > nurFrueh) { "der Testkernel ist nicht fallend - Aufbau falsch" }
        assertEquals(maxOf(nurFrueh, nurSpaet), gerechnet.liabilityAtHU, 1e-12)
    }

    /** Ohne Einheitskern ist die Restwirkung UNBEKANNT, nicht 0: dann haftet
     *  die volle Menge und der Vermerk sagt `transportBounded`. */
    @Test
    fun `ohne Kern haftet die volle Menge und der Vermerk sagt es`() {
        val d = doses(item("p1", 0.10, 12), item("p2", 0.30, 2))
        val summe = TailLiability.sumOf(d.map { FuseCycleRunner.tailTransportDose(it, null, horizonTs) })
        assertEquals(0.40, summe.liabilityAtHU, 1e-12)
        assertTrue(TailLiability.completenessOf(summe, null).contains("transportBounded"))
    }

    /** Kein offener Posten heisst gerechnete Null - kein `bounded`. */
    @Test
    fun `ohne Posten ist die Transportgroesse eine gerechnete Null`() {
        val summe = TailLiability.sumOf(emptyList())
        assertEquals(0.0, summe.liabilityAtHU, 0.0)
        assertTrue(summe.modelled)
    }

    // ---- C3-02 an der Verdrahtung ----------------------------------------

    /**
     * Der Inclusion-Vertrag wirkt bis in die Bahn: eine bereits GEBUCHTE
     * Zeile, deren Fakt in der Snapshot-Lesung fehlt, bleibt eine synthetische
     * Dosis in voller Hoehe - statt aus beiden Sichten zu verschwinden.
     */
    @Test
    fun `ein gebuchter Posten ohne Snapshot-Nachweis bleibt eine synthetische Dosis`() {
        val gebucht = OpenTransportItem(
            proposalId = "p1", commitmentU = 0.0, grossLiabilityU = 0.30, accountedAmountU = 0.30,
            bestKnownTs = t0 - 60_000L, temporaryId = null, pumpId = 4711L, settledZero = false,
        )
        val ohneFakt = TransportInclusion.witnessOf(
            listOf(AccountedTreatment(null, 9999L, 0.30, "GENERIC_AAPS", "hash")),
            fromTs = t0 - 9 * 3600_000L, readAtTs = t0,
        )
        val mitFakt = TransportInclusion.witnessOf(
            listOf(AccountedTreatment(null, 4711L, 0.30, "GENERIC_AAPS", "hash")),
            fromTs = t0 - 9 * 3600_000L, readAtTs = t0,
        )
        assertEquals(
            0.30,
            FuseCycleRunner.transportDoses(listOf(gebucht), ohneFakt, t0).single().amountU, 1e-12,
        )
        assertTrue(FuseCycleRunner.transportDoses(listOf(gebucht), mitFakt, t0).isEmpty()) {
            "mit Nachweis darf keine synthetische Dosis mehr entstehen"
        }
    }

    /** Posten ohne Menge erzeugen keine Dosis - sonst stuende eine 0-U-Dosis
     *  in der Bahn und der Vermerk wuerde ohne Not `bounded`. */
    @Test
    fun `Nullposten erzeugen keine Dosis`() {
        assertTrue(doses(item("p1", 0.0, 5)).isEmpty())
    }
}
