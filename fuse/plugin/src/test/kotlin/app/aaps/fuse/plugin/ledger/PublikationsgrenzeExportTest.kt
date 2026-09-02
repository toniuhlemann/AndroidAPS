package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.controller.MealFoundation
import app.aaps.fuse.plugin.FuseCycleRunner
import app.aaps.fuse.plugin.FuseFinalDelivery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DIE LETZTE GRENZE MIT DEM ECHTEN GATE.
 *
 * `FuseFinalDeliveryTest` prueft den Vertrag an gesetzten Werten. Hier
 * kommt das Gate-Ergebnis aus dem ECHTEN `LedgerPublicationGate` - genau
 * dem, das im Plugin laeuft. Damit haengt der Nachweis nicht an meiner
 * Annahme darueber, wann das Gate streicht.
 *
 * WAS DIESER TEST NICHT IST: ein Durchlauf von `FusePlugin.invoke()`.
 * Dafuer gibt es keinen Testrahmen. Die Verdrahtung im Plugin ist eine
 * einzige Aufrufstelle ohne Logik daneben und per Codepruefung
 * abgesichert - das steht hier ausdruecklich, damit niemand mehr
 * hineinliest, als bewiesen ist.
 *
 * Alle Werte sind synthetisch.
 */
class PublikationsgrenzeExportTest {

    private val t0 = 1_700_000_000_000L
    private val bucht = LedgerPublicationGate.Commitment.Proposal("p1")

    private fun rtMitSmb(units: Double = 0.30) = RT(
        algorithm = APSResult.Algorithm.FUSE,
        timestamp = t0,
        reason = StringBuilder("FUSE test"),
        rate = 0.0,
        duration = 30,
        units = units,
        deliverAt = t0,
    )

    private fun adapter(dir: File) = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }

    /** Ein Pfad, in den der Store nicht schreiben kann - das Gate streicht dann. */
    private fun unschreibbar(parent: File): File {
        val blockiert = File(parent, "datei-statt-verzeichnis")
        blockiert.writeText("x")
        return File(blockiert, "unter")
    }

    /**
     * DER FALL, UM DEN ES GEHT. Der Runner hat eine positive Menge
     * gesehen; das echte Gate streicht sie. Was AAPS bekommt, ist nichts -
     * und genau das muss im Endstand stehen.
     */
    @Test
    fun `das echte Gate streicht - Endstand ist null mit seinem Grund`(@TempDir dir: File) {
        val a = adapter(dir)
        val rt = rtMitSmb()
        val out = LedgerPublicationGate.publish(
            rt, a, unschreibbar(dir), bucht,
            published = InterventionStamp.Published(smbU = null, tbrChanged = false),
            events = { a.onPublished("p1", 0.30, t0, 0L, 0.05) },
        )
        assertNull(out.rt.units) { "Ausgangslage: das echte Gate hat die Zeile entfernt" }

        val endstand = FuseFinalDelivery.bestimme(
            // Der Runner sah eine positive Menge - er kennt das Gate nicht.
            runnerActuatedU = 0.30,
            runnerFinalBlock = null,
            publishedUnits = out.rt.units,
            gateAllowed = out.allowed,
            angeforderteZeileVorhanden = rt.units != null,
            gateReason = out.reason,
        )
        assertEquals(0.0, endstand.actuatedU, 1e-12) {
            "AAPS bekommt keinen SMB - der Endstand darf keine Menge behaupten"
        }
        assertNotNull(endstand.finalBlock)
        assertTrue(endstand.finalBlock!!.startsWith(FuseFinalDelivery.PUBLICATION_BLOCK)) {
            endstand.finalBlock!!
        }
    }

    /** Der freigegebene Gegenfall: dasselbe Gate laesst durch. */
    @Test
    fun `das echte Gate laesst durch - Endstand ist die uebergebene Menge`(@TempDir dir: File) {
        val a = adapter(dir)
        val rt = rtMitSmb()
        val out = LedgerPublicationGate.publish(
            rt, a, dir, bucht,
            published = InterventionStamp.Published(smbU = null, tbrChanged = false),
            events = { a.onPublished("p1", 0.30, t0, 0L, 0.05) },
        )
        assertEquals(0.30, out.rt.units!!, 1e-12) { "Ausgangslage: die Zeile ging hinaus" }

        val endstand = FuseFinalDelivery.bestimme(
            runnerActuatedU = 0.30,
            runnerFinalBlock = null,
            publishedUnits = out.rt.units,
            gateAllowed = out.allowed,
            angeforderteZeileVorhanden = rt.units != null,
            gateReason = out.reason,
        )
        assertEquals(0.30, endstand.actuatedU, 1e-12)
        assertNull(endstand.finalBlock) { "kein Riegel, der gegriffen haette" }
    }

    /**
     * PFLICHTFALL GATE-VERWURF MIT ANSCHLIESSENDER ANZEIGE.
     *
     * Der Runner bildet die Huellenzahlen VOR dem Publikations-Gate. Streicht
     * das Gate die Zeile, gibt `resolveReservation` die Belastung wieder frei -
     * das Plugin ruft es (FusePlugin ~1486) VOR dem Export. Die eingefrorene
     * Fassung des Runners meldete danach eine Menge als verbraucht, die nie
     * hinausging, und daneben stand die richtige, gesunkene Episodensumme.
     *
     * Hier laeuft die ECHTE Kette: echtes Gate -> echte Rueckdrehung im
     * Ledger -> dieselbe Produktionsfunktion, die auch der Runner benutzt.
     * Alle Werte sind synthetisch.
     */
    @Test
    fun `nach dem Gate-Verwurf sinkt der Huellenverbrauch wieder`(@TempDir dir: File) {
        val a = adapter(dir)
        // Eine bewaffnete Autorisierung ueber 4 U, davon 0,50 U schon
        // geflossen; dieser Zyklus bucht 0,30 U in Phase B dazu.
        a.episodes.foundation = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = 4.0, phaseAShare = 0.75,
            phaseAUpfrontShare = 0.0, primeWindowMin = 15, wallCeilingMin = 45,
            phaseBUntilMin = 60, pressObservedInThisProcess = true,
            primeDeclinedByUser = false, markerAuthorized = true,
        )
        a.episodes.deliveredPhaseAU = 0.50
        a.episodes.deliveredSinceHandoverU = 0.30
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = t0, amountU = 0.30, prime = false, onset = false,
            // Ohne Mahlzeitenzeile: dieser Test misst die Huelle, nicht die
            // Serienbuchhaltung - und `resolveReservation` steigt sonst aus,
            // wenn es die genannte Zeile nicht findet.
            mealTs = 0L, correctionTs = 0L,
            foundationPhase = MealFoundation.Phase.PHASE_B,
        )

        val vorher = FuseCycleRunner.envelopeUseOf(a.episodes, livePrimeEnvelopeU = 4.0)
        assertEquals(0.80, vorher.spentU, 1e-9) { "Ausgangslage: der Runner-Stand" }

        // ---- DAS ECHTE GATE STREICHT ------------------------------------
        val rt = rtMitSmb()
        val out = LedgerPublicationGate.publish(
            rt, a, unschreibbar(dir), bucht,
            published = InterventionStamp.Published(smbU = null, tbrChanged = false),
            events = { a.onPublished("p1", 0.30, t0, 0L, 0.05) },
        )
        assertNull(out.rt.units) { "Ausgangslage: das Gate hat die Zeile entfernt" }

        // ---- WAS DAS PLUGIN DANACH TUT ----------------------------------
        a.resolveReservation(t0, out.rt.units ?: 0.0, proposalId = "p1")

        val nachher = FuseCycleRunner.envelopeUseOf(a.episodes, livePrimeEnvelopeU = 4.0)
        assertEquals(0.50, nachher.spentU, 1e-9) {
            "die gestrichenen 0,30 U duerfen nicht als verbraucht stehenbleiben"
        }
        assertEquals(3.50, nachher.freeU, 1e-9)
        assertEquals(4.0, nachher.envelopeU, 1e-9)
        assertTrue(nachher.spentU < vorher.spentU) {
            "ohne Neurechnung nach dem Gate zeigte die Zeile den Vorstand"
        }
    }
}
