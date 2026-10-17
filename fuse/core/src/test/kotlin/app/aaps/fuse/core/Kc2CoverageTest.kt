package app.aaps.fuse.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Buchfuehrung ueber KC2-01..KC2-65.
 *
 * Zweck: kein stiller Ausfall. Ein Testblock, aus dem einzelne Nummern
 * kommentarlos fehlen, sieht spaeter aus wie vollstaendige Abdeckung. Hier
 * traegt JEDE Nummer entweder einen Fundort oder einen benannten Grund,
 * warum sie heute nicht gebaut werden darf.
 *
 * Diese Datei ist der einzige Ort, an dem "noch nicht" stehen darf.
 */
class Kc2CoverageTest {

    private enum class Status { COVERED, BLOCKED }

    private data class Entry(val status: Status, val where: String)

    private fun covered(where: String) = Entry(Status.COVERED, where)
    private fun blocked(why: String) = Entry(Status.BLOCKED, why)

    private val coverage: Map<Int, Entry> = mapOf(
        1 to covered("TbrPolicyTest"),
        2 to covered("TbrPolicyTest"),
        3 to covered("TbrPolicyTest"),
        4 to covered("CandidateSearchTest"),
        5 to covered("UnitInsulinKernelTest + CandidateSearchTest"),
        6 to covered("CandidateSearchTest + LedgerReducerTest"),
        7 to covered("LedgerReducerTest"),
        8 to covered("LedgerReducerTest"),
        9 to covered("LedgerReducerTest"),
        10 to covered("LedgerReducerTest"),
        11 to covered("TbrPolicyTest"),
        12 to covered("TbrPolicyTest"),
        13 to covered("TbrPolicyTest"),
        14 to blocked("Aktuationsreihenfolge im Plugin - VPUMP_ALPHA ist NO-GO (R77)"),
        15 to covered("FusePumpGateTest + FuseRtBuilderTest (R74-F1)"),
        16 to covered("TbrPolicyTest (IobThreshold)"),
        17 to covered("LedgerReducerTest"),
        18 to covered("LedgerReducerTest"),
        19 to covered("LedgerReducerTest"),
        20 to covered("LedgerReducerTest"),
        21 to covered("LedgerReducerTest"),
        22 to covered("LedgerReducerTest"),
        23 to covered("LedgerReducerTest"),
        24 to covered("LedgerReducerTest"),
        25 to covered("CandidateSearchTest"),
        26 to covered("TbrPolicyTest"),
        27 to covered("TbrPolicyTest"),
        28 to covered("TbrPolicyTest"),
        29 to covered("TbrPolicyTest"),
        30 to covered("TbrPolicyTest"),
        31 to covered("TbrPolicyTest"),
        32 to covered("LedgerReducerTest"),
        33 to covered("LedgerReducerTest (Reducer-Anteil; Persistenzadapter fehlt noch)"),
        34 to covered("LedgerReducerTest (Reducer-Anteil; Persistenzadapter fehlt noch)"),
        35 to covered("LedgerReducerTest"),
        36 to covered("CandidateSearchTest"),
        37 to covered("CandidateSearchTest + UnitInsulinKernelTest"),
        38 to blocked("Ereignis-Budgetimpuls gehoert zur Release-Budget-Policy - NO-GO bis KC2-53"),
        39 to covered("LedgerReducerTest"),
        40 to covered("LedgerReducerTest"),
        41 to covered("LedgerReducerTest"),
        42 to covered("LedgerReducerTest"),
        43 to covered("LedgerReducerTest"),
        44 to covered("LedgerReducerTest"),
        45 to covered("LedgerReducerTest"),
        46 to covered("LedgerReducerTest"),
        47 to covered("LedgerReducerTest"),
        48 to covered("CandidateSearchTest"),
        49 to covered("ProfileSlotsTest"),
        50 to covered("TbrPolicyTest"),
        51 to covered("TbrPolicyTest"),
        52 to covered("LedgerReducerTest"),
        53 to blocked("Replay-Messung mit realistischem Basalprofil - eigener Schritt vor der Budgetentscheidung"),
        54 to covered("LedgerReducerTest"),
        55 to covered("LedgerReducerTest"),
        56 to covered("LedgerReducerTest"),
        57 to covered("LedgerReducerTest"),
        58 to covered("LedgerReducerTest"),
        59 to covered("LedgerReducerTest"),
        60 to covered("UnitInsulinKernelTest + AapsUnitInsulinSamplerTest"),
        61 to covered("AapsUnitInsulinSamplerTest (gegen die ECHTE Pluginklasse)"),
        62 to blocked("braucht den lastKnownBolusTime-Diff in Loop/Queue - AAPS-Hook-Diff ist NO-GO; " +
            "die pure Vorstufe (Snapshot-Zeitstempel im Ledger, TREATMENT_CHANGED-Reject) liegt in KC2-35"),
        63 to covered("LedgerReducerTest (Ledger-Anteil); der Transport ueber RT/Clone/DetailedBolusInfo/Queue " +
            "braucht den AAPS-Diff und bleibt gesperrt"),
        64 to covered("LedgerReducerTest"),
        65 to covered("TbrPolicyTest"),
    )

    @Test
    fun `jede der 65 Gegenproben ist entweder gebaut oder mit Grund gesperrt`() {
        for (n in 1..65) {
            val e = coverage[n]
            assertTrue(e != null, "KC2-${"%02d".format(n)} fehlt in der Buchfuehrung")
            assertTrue(e!!.where.isNotBlank(), "KC2-${"%02d".format(n)} ohne Fundort oder Grund")
        }
        assertEquals(65, coverage.size)
    }

    @Test
    fun `die gesperrten Nummern sind genau die vier aus der R77-Freigabematrix`() {
        val blocked = coverage.filterValues { it.status == Status.BLOCKED }.keys.sorted()
        assertEquals(listOf(14, 38, 53, 62), blocked)
    }
}
