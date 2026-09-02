package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE LETZTE GRENZE: was AAPS wirklich bekommt.
 *
 * Der Runner bildet seine Zahl VOR dem Publikations-Gate. Wird die Zeile
 * dort entfernt, uebergibt FUSE gar keinen SMB - und ohne diesen Vertrag
 * stand im Export weiter eine positive Menge, im Widget daneben "FREI".
 *
 * `FusePlugin.invoke()` hat keinen Testrahmen; deshalb ist die
 * Entscheidung eine reine Funktion, und das Plugin enthaelt nur den
 * Aufruf. Alle Werte hier sind synthetisch.
 */
class FuseFinalDeliveryTest {

    private fun bestimme(
        runner: Double? = 0.50,
        runnerBlock: String? = null,
        published: Double? = 0.50,
        allowed: Boolean = true,
        zeileDa: Boolean = true,
        grund: String? = "SEAL_FAILED",
    ) = FuseFinalDelivery.bestimme(runner, runnerBlock, published, allowed, zeileDa, grund)

    @Test
    fun `das Publikations-Gate streicht die Menge - final ist null mit seinem Grund`() {
        val e = bestimme(published = null, allowed = false)
        assertEquals(0.0, e.actuatedU, 1e-12) { "AAPS bekommt nichts" }
        assertTrue(e.finalBlock!!.startsWith(FuseFinalDelivery.PUBLICATION_BLOCK)) { e.finalBlock!! }
        assertTrue(e.finalBlock!!.contains("SEAL_FAILED")) { "der Grund des Gates gehoert dazu" }
    }

    @Test
    fun `der Runner-Wert darf den Endstand nicht ueberstimmen`() {
        // GENAU DIE LUECKE: der Runner sah 0,50, das Gate strich die Zeile.
        val e = bestimme(runner = 0.50, published = null, allowed = false)
        assertEquals(0.0, e.actuatedU, 1e-12) {
            "sonst zeigte der Export eine Menge, die nie uebergeben wurde"
        }
    }

    @Test
    fun `laesst das Gate durch, gilt die uebergebene Zeile`() {
        val e = bestimme(published = 0.35, allowed = true)
        assertEquals(0.35, e.actuatedU, 1e-12)
        assertNull(e.finalBlock)
    }

    @Test
    fun `ein Riegel vor dem Gate bleibt der finale, wenn nichts hinausgeht`() {
        val e = bestimme(runner = 0.0, runnerBlock = "PARTIAL_RECOVERY",
                         published = null, allowed = true, zeileDa = false)
        assertEquals(0.0, e.actuatedU, 1e-12)
        assertEquals("PARTIAL_RECOVERY", e.finalBlock)
    }

    @Test
    fun `ging etwas hinaus, gibt es keinen Sperrgrund`() {
        val e = bestimme(runner = 0.20, runnerBlock = "PARTIAL_RECOVERY",
                         published = 0.20, allowed = true)
        assertEquals(0.20, e.actuatedU, 1e-12)
        assertNull(e.finalBlock) { "ein Riegel, der nicht griff, ist keiner" }
    }

    @Test
    fun `ohne angeforderte Zeile ist ein geschlossenes Gate kein Streichen`() {
        // Das Gate war zu, aber es gab gar nichts zu streichen - dann ist
        // sein Grund nicht der finale.
        val e = bestimme(runner = 0.0, runnerBlock = null, published = null,
                         allowed = false, zeileDa = false)
        assertEquals(0.0, e.actuatedU, 1e-12)
        assertNull(e.finalBlock)
    }
}
