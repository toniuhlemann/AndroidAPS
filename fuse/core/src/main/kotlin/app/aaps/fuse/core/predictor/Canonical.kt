package app.aaps.fuse.core.predictor

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.round

/**
 * ZWEI Hashes mit verschiedenen Aufgaben (R71-B1) — sie duerfen nicht
 * zusammengelegt werden:
 *
 *  trajectoryContentHash  VERLUSTFREI. Bindet Herkunft und Punkte exakt. Eine
 *                         Rundung auf 6 Stellen koennte zwei verschiedene
 *                         Punktreihen auf denselben Hash abbilden — genau das,
 *                         was ein Provenienzhash verhindern soll.
 *  goldenOutputHash       GERUNDET. Plattformrobust fuer Golden-Tests, weil
 *                         bitgleiche Double-Ergebnisse nur bei identischer
 *                         Plattform-Mathematik garantiert sind.
 */
object Canonical {

    const val INPUT_CANONICALIZATION_ID = "fuse-k2p-input-v1-lossless"
    const val OUTPUT_CANONICALIZATION_ID = "fuse-k2p-output-v1-scale6"
    const val OUTPUT_SCALE = 6

    /** -0.0 und +0.0 sind fuer beide kanonischen Formen derselbe Wert. */
    private fun norm(d: Double): Double = if (d == 0.0) 0.0 else d

    /** Verlustfrei ueber die IEEE-754-Bits — kein Praezisionsverlust moeglich. */
    private fun lossless(d: Double): String {
        require(d.isFinite()) { "non-finite value must never reach the hash path" }
        return java.lang.Double.doubleToLongBits(norm(d)).toString()
    }

    private fun rounded(d: Double): String {
        if (!d.isFinite()) return "null"
        val f = Math.pow(10.0, OUTPUT_SCALE.toDouble())
        val r = round(norm(d) * f) / f
        return if (abs(r) < 1e-12) "0" else r.toBigDecimal().stripTrailingZeros().toPlainString()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun trajectoryContentHash(t: InsulinTrajectoryInput): String {
        val sb = StringBuilder()
        sb.append(INPUT_CANONICALIZATION_ID).append('|')
        // lineageKind ist Teil des GEHASHTEN Payloads, nicht nur ein Feld daneben:
        // sonst waere eine als Actual etikettierte Virtual-Reihe hashgleich.
        sb.append(t.lineage.lineageKind).append('|')
        sb.append(
            when (val l = t.lineage) {
                is InsulinLineage.ActualTreatment    -> "${l.sourceDeviceIdHash}:${l.treatmentSnapshotHash}"
                is InsulinLineage.VirtualController  -> "${l.vpumpSessionId}:${l.ledgerRevision}"
            }
        ).append('|')
        sb.append(t.lineage.modelHash).append('|')
        sb.append(t.model.insulinType).append(':').append(lossless(t.model.diaHours))
            .append(':').append(t.model.peakMin).append(':').append(t.model.codeProvenance).append('|')
        sb.append(t.iobCalculationHash).append('|')
        sb.append(t.arrayAsOfTs).append('|')
        for (p in t.points) {
            sb.append(p.timeMs).append(',')
                .append(lossless(p.iob)).append(',')
                .append(lossless(p.activity)).append(',')
                .append(lossless(p.basalIob)).append(';')
        }
        return sha256(sb.toString())
    }

    fun goldenOutputHash(r: PredictorResult): String {
        val sb = StringBuilder()
        sb.append(OUTPUT_CANONICALIZATION_ID).append('|')
        sb.append(r.baselineSemantics).append('|').append(r.lineageKind).append('|')
        sb.append(r.trajectoryContentHash).append('|')
        sb.append(rounded(r.minMeanBg)).append(',').append(rounded(r.minLowerBg)).append(',')
            .append(r.timeToMinLowerMin).append(',')
            .append(rounded(r.bgAtHorizonMean)).append(',').append(rounded(r.bgAtHorizonLower)).append('|')
        for (p in r.points) {
            sb.append(p.offsetMin).append(',')
                .append(rounded(p.meanBg)).append(',')
                .append(rounded(p.lowerBg)).append(',')
                .append(rounded(p.bgiRate)).append(';')
        }
        return sha256(sb.toString())
    }
}
