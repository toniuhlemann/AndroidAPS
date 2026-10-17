package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Fork (IOB-Action viewer): the export files are a contract with the companion viewer. These tests pin
 * the parts that decide how the bytes look: absent keys, org.json number form, the SMB stamps.
 * All values are synthetic.
 */
class IobActionExportTest {

    @TempDir lateinit var dir: File

    @Test fun putOptLeavesOutNullAndNonFiniteLikeOrgJson() {
        val o = buildJsonObject {
            putOpt("missing", null)
            putOpt("nan", Double.NaN)
            putOpt("inf", Double.POSITIVE_INFINITY)
            putOpt("double", 1.5)
            putOpt("int", 2)
            putOpt("long", 3L)
            putOpt("text", "x")
            putOpt("flag", true)
        }

        assertThat(o.keys).containsExactly("double", "int", "long", "text", "flag").inOrder()
    }

    @Test fun theOrgJsonRebuildWritesWholeDoublesLikeTheFork() {
        // kotlinx writes 1.0, org.json (what the 3.4 fork used) writes 1. The sink rebuilds with org.json.
        val o = buildJsonObject {
            putOpt("ratio", 1.0)
            putOpt("units", 0.25)
            put("inner", buildJsonObject { putOpt("target", 100.0) })
        }

        // The key order is the platform's business (Android keeps it, the JVM test org.json may not),
        // the number form is what this pins.
        val written = JSONObject(o.toString()).toString()
        assertThat(written).contains("\"ratio\":1")
        assertThat(written).doesNotContain("\"ratio\":1.0")
        assertThat(written).contains("\"units\":0.25")
        assertThat(written).contains("\"target\":100")
        assertThat(written).doesNotContain("\"target\":100.0")
    }

    @Test fun smbHistoryStampsOnlySmbsOfThisCycle() {
        val now = 10_000_000_000L
        val first = IobActionExportSink.Factors(acce = 1.1, bg = 1.2, pp = 1.3, dura = 1.4, final = 1.5)
        val smbs = listOf(
            IobActionExportSink.SmbIn(now - 60 * 60_000L, 0.2),   // seen first now, but an hour old: driver unknown
            IobActionExportSink.SmbIn(now - 2 * 60_000L, 0.3)     // delivered in this cycle
        )

        val arr = IobActionSmbHistory.buildAndPersist(dir, now, smbs, first)

        assertThat(arr.length()).isEqualTo(2)
        val old = arr.getJSONObject(0)
        assertThat(old.getLong("ts")).isEqualTo(now - 60 * 60_000L)
        assertThat(old.has("acce")).isFalse()
        val recent = arr.getJSONObject(1)
        assertThat(recent.getDouble("acce")).isEqualTo(1.1)
        assertThat(recent.getDouble("final")).isEqualTo(1.5)

        // Next cycle, other factors: the known SMB keeps the stamp of the cycle that decided it.
        val later = IobActionExportSink.Factors(acce = 2.1, bg = 2.2, pp = 2.3, dura = 2.4, final = 2.5)
        val arr2 = IobActionSmbHistory.buildAndPersist(dir, now + 60_000L, smbs, later)

        assertThat(arr2.getJSONObject(1).getDouble("acce")).isEqualTo(1.1)
        assertThat(arr2.getJSONObject(0).has("acce")).isFalse()
    }

    @Test fun smbHistoryDropsSmbsOutsideTheWindow() {
        val now = 10_000_000_000L
        val factors = IobActionExportSink.Factors(null, null, null, null, null)
        val smbs = listOf(IobActionExportSink.SmbIn(now - 7 * 3600_000L, 0.1), IobActionExportSink.SmbIn(now - 60_000L, 0.1))

        val arr = IobActionSmbHistory.buildAndPersist(dir, now, smbs, factors)

        assertThat(arr.length()).isEqualTo(1)
        assertThat(arr.getJSONObject(0).getLong("ts")).isEqualTo(now - 60_000L)
    }

    @Test fun atomicWriteLeavesNoTempFileAndBumpsTheMtime() {
        val before = System.currentTimeMillis()

        IobActionFiles.writeAtomically(dir, "iobaction_core.json", """{"ts":1}""", bumpMtime = true)

        val target = File(dir, "iobaction_core.json")
        assertThat(target.readText()).isEqualTo("""{"ts":1}""")
        assertThat(File(dir, "iobaction_core.json.tmp").exists()).isFalse()
        // Filesystems round the mtime to seconds; a fresh one is not older than a second before the write.
        assertThat(target.lastModified()).isAtLeast(before - 2000L)
    }
}
