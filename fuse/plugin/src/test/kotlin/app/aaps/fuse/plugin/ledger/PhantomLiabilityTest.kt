package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * PHANTOMHAFTUNG (Kontroll-Audit 09.08.2026).
 *
 * Gemessener Anlass: drei Posten vom Vorabend, 16-20 h alt, banden am 09.08.
 * um 11:52 zusammen 0,35 U als Transport - 0,086 U davon im Schwanzbudget,
 * das Siebenfache des damals verbliebenen Headrooms. `prune` entfernte sie
 * nicht, weil es nur GESCHLOSSENE Zeilen kennt.
 *
 * Die Regel prueft AUSDRUECKLICH nicht "geliefert", sondern "kann nicht mehr
 * wirken" - der v0.1-Fehler (Freigabe nach 20 min, also mitten in der
 * Wirkzeit) darf dabei nicht zurueckkommen.
 */
class PhantomLiabilityTest {

    private val t0 = 1_700_000_000_000L
    private val dia = 9.0

    /** DIA 9 h + 2 h Spanne. */
    private val cutoffMs = (dia * 3600_000L).toLong() + 2L * 3600_000L

    private fun FuseLedgerAdapter.publishVs(id: String, u: Double, ts: Long) =
        onPublished(id, u, ts, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))

    private fun adapter(dir: File) = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }

    @Test
    fun `der gemessene Fall - 19 Stunden alte Posten haften nicht mehr`(@TempDir dir: File) {
        val a = adapter(dir)
        val alt = t0 - 19 * 3600_000L
        a.publishVs("abend-1", 0.15, alt)
        a.publishVs("abend-2", 0.10, alt + 60_000L)
        a.publishVs("abend-3", 0.10, alt + 120_000L)
        assertEquals(0.35, a.view().transportCommitmentU, 1e-9) { "Ausgangslage wie gemessen" }

        a.prune(t0, dia)

        assertEquals(0.0, a.view().transportCommitmentU, 1e-9) {
            "nach DIA+Spanne kann keine dieser Mengen mehr wirken"
        }
    }

    @Test
    fun `der v0_1-Fehler kommt nicht zurueck - innerhalb der Wirkzeit bleibt alles gebunden`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("frisch", 0.30, t0 - 20 * 60_000L)
        a.publishVs("mittel", 0.20, t0 - 4 * 3600_000L)
        // Knapp INNERHALB des Fensters: darf nichts verlieren.
        a.publishVs("kante", 0.10, t0 - cutoffMs + 60_000L)

        a.prune(t0, dia)

        assertEquals(0.60, a.view().transportCommitmentU, 1e-9) {
            "Zeitablauf innerhalb der Wirkzeit ist KEIN Freibrief"
        }
    }

    @Test
    fun `der Befund wird sichtbar, sperrt aber nicht`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("leiche", 0.20, t0 - 19 * 3600_000L)
        a.prune(t0, dia)

        // Der Posten haftet nicht mehr...
        assertEquals(0.0, a.view().transportCommitmentU, 1e-9)
        // ...aber der Grund bleibt zaehlbar, fuer Export und Diagnose.
        assertEquals(1, a.unresolvedBeyondActionCount()) {
            "ein nie abgeschlossener Abgleich muss ein Befund bleiben"
        }
        // Und er legt den Regler NICHT still.
        assertFalse(a.view().hold) { "eine 19 h alte Leiche darf nicht sperren" }
        assertTrue(a.openTransportItems().all { it.commitmentU == 0.0 })
    }

    @Test
    fun `zweimal pruefen aendert nichts - der Befund wird nicht vervielfacht`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("leiche", 0.20, t0 - 19 * 3600_000L)
        a.prune(t0, dia)
        val nachErstem = a.unresolvedBeyondActionCount()
        a.prune(t0 + 60_000L, dia)
        assertEquals(nachErstem, a.unresolvedBeyondActionCount())
        assertEquals(0.0, a.view().transportCommitmentU, 1e-9)
    }
}
