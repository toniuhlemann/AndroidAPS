package app.aaps.fuse.core.signal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Causal 1-minute UKF ("Q1") — forward-only leading edge for the live loop value.
 *
 * PROVENANCE. Verbatim port of the audited viewer reference (Ukf1Minute.kt, forward pass,
 * qScale = 1.0) that produced the ukfQ1 arm of the P-1.2 holdout: 240/240 pre-registered grid
 * cells confirmed persistent rises earlier on the BGI-corrected pairing, and ukfQ1 had the best
 * detection/carry balance of all arms (R55/R57, formal candidate lock R58). Per R59-B1 the
 * candidate identity is the CALIBRATED input BEFORE the EMA — in this fork that is exactly
 * [app.aaps.core.data.model.GV.raw] (extraBgEstimate after slope/offset), NOT GV.value and NOT
 * the uncalibrated sensor value carried in GV.noise.
 *
 * DELIBERATE OMISSIONS. No RTS backward pass and no acceleration replay: RTS revises history
 * after later data arrives and must never feed a live decision (R59-B1.3). Only the value that
 * was knowable at the newest timestamp leaves this object.
 *
 * The state model is linear ([glucose, rate]); the UKF prediction/update is algebraically
 * identical to the compact 2x2 Kalman form below. Constants mirror the frozen reference —
 * changing ANY of them makes this a NEW, unlocked candidate.
 */
object UkfQ1 {

    const val WINDOW_SAMPLES = 180
    const val Q_SCALE = 1.0                    // "Q1" — locked candidate, not configurable

    private const val R_INIT = 25.0
    private const val R_MIN = 16.0
    private const val R_MAX = 225.0
    private const val R_EFF_MAX = 400.0
    private const val MAX_GLUCOSE_VARIANCE = 400.0
    private const val MAX_RATE_VARIANCE = 4.0
    private const val MINOR_GAP_MIN = 7.0
    private const val MAJOR_GAP_MIN = 60.0
    private const val RATE_DECAY_MIN = 30.0
    private const val CHI_SQUARED_THRESHOLD = 15.13
    private const val OUTLIER_ABSOLUTE = 65.0
    private const val ADAPT_MIN_INNOVATIONS = 60
    private const val INNOVATION_WINDOW = 90
    private const val ADAPT_TRIM_FRACTION = 0.20
    private const val K_UP = 0.18
    private const val K_DN = 0.12
    private const val UP_CAP_UP = 1.20
    private const val UP_CAP_DN = 1.00
    private const val DN_CAP_UP = 1.00
    private const val DN_CAP_DN = 0.90
    private const val ETA = 0.25

    data class Point(val tsMs: Long, val value: Double)

    data class Result(
        val glucose: Double,
        val ratePerMin: Double,
        val outlier: Boolean,
        val learnedR: Double,
    )

    private data class Matrix2(var a: Double, var b: Double, var c: Double, var d: Double)

    /** Leading-edge state at the newest timestamp, or null when the series is unusable. */
    fun leadingEdge(points: List<Point>): Result? {
        if (points.isEmpty()) return null
        val ordered = points
            .asSequence()
            .filter { it.tsMs > 0L && it.value.isFinite() }
            .sortedBy { it.tsMs }
            .distinctBy { it.tsMs }
            .toList()
            .takeLast(WINDOW_SAMPLES)
        if (ordered.isEmpty()) return null

        // Only the continuous segment containing the newest reading contributes to its state.
        var segmentStart = 0
        for (i in 1 until ordered.size) {
            val dt = (ordered[i].tsMs - ordered[i - 1].tsMs) / 60_000.0
            if (dt > MAJOR_GAP_MIN || dt < 0.5 || ordered[i - 1].value == 38.0) {
                segmentStart = i
            }
        }
        val segment = ordered.subList(segmentStart, ordered.size)
        if (segment.size < 2) {
            return Result(max(segment.last().value, 39.0), 0.0, false, R_INIT)
        }

        var glucose = segment.first().value
        val initDt = (segment[1].tsMs - segment[0].tsMs) / 60_000.0
        var rate = if (initDt in 0.5..7.0) {
            ((segment[1].value - segment[0].value) / initDt).coerceIn(-4.0, 4.0)
        } else 0.0
        var covariance = Matrix2(16.0, 0.0, 0.0, 1.0)
        var learnedR = R_INIT
        val recentSigns = ArrayDeque<Int>(3)
        val normalizedInnovations = ArrayDeque<Double>(INNOVATION_WINDOW)
        val rawInnovationVar = ArrayDeque<Double>(INNOVATION_WINDOW)
        val predVarHistory = ArrayDeque<Double>(INNOVATION_WINDOW)
        var lastOutlier = false

        for (i in 1 until segment.size) {
            val dt = (segment[i].tsMs - segment[i - 1].tsMs) / 60_000.0
            if (dt > MINOR_GAP_MIN) rate *= rateDamp(dt)
            covariance.a = covariance.a.coerceIn(0.1, MAX_GLUCOSE_VARIANCE)
            covariance.d = covariance.d.coerceIn(0.001, MAX_RATE_VARIANCE)

            val base = predict(glucose, rate, covariance, Q_SCALE, 0.35 * Q_SCALE, dt)
            val z = segment[i].value
            val innovation = z - base.first.first
            val rawVariance = base.second.a + learnedR
            val norm = innovation / sqrt(rawVariance)
            val sign = when {
                norm > 0.0 -> 1
                norm < 0.0 -> -1
                else -> 0
            }
            pushFront(recentSigns, if (abs(norm) > 2.0) sign else 0, 3)
            val qInflate = sign != 0 && recentSigns.count { it == sign } >= 2
            val absNorm = abs(norm)
            val rScale = 1.0 + max(0.0, absNorm - 2.0)
            val effectiveR = min(learnedR * rScale, min(learnedR + 100.0, R_EFF_MAX))
            val qInflation = if (qInflate) absNorm.coerceIn(1.0, 3.0) else 1.0
            val predicted = if (qInflation > 1.0) {
                predict(glucose, rate, covariance, Q_SCALE * min(qInflation, 2.0), 0.35 * Q_SCALE * qInflation, dt)
            } else base

            val innovationVariance = predicted.second.a + effectiveR
            val mahalSquared = innovation * innovation / innovationVariance
            pushFront(predVarHistory, predicted.second.a, INNOVATION_WINDOW)

            val updated = update(predicted.first.first, predicted.first.second, predicted.second, z, effectiveR)
            glucose = updated.first.first
            rate = updated.first.second.coerceIn(-4.0, 4.0)
            covariance = updated.second

            pushFront(normalizedInnovations, innovation * innovation / innovationVariance, INNOVATION_WINDOW)
            pushFront(rawInnovationVar, innovation * innovation, INNOVATION_WINDOW)
            if (!qInflate && absNorm <= 3.0) {
                learnedR = adaptR(learnedR, normalizedInnovations, rawInnovationVar, predVarHistory)
            }
            lastOutlier = mahalSquared > CHI_SQUARED_THRESHOLD || abs(innovation) > OUTLIER_ABSOLUTE
        }

        return Result(
            glucose = max(glucose, 39.0),
            ratePerMin = rate,
            outlier = lastOutlier,
            learnedR = learnedR,
        )
    }

    private fun rateDamp(dtMin: Double): Double = exp(-dtMin / RATE_DECAY_MIN)

    private fun predict(
        glucose: Double,
        rate: Double,
        p: Matrix2,
        qGlucose: Double,
        qRate: Double,
        dt: Double,
    ): Pair<Pair<Double, Double>, Matrix2> {
        val damp = rateDamp(dt)
        val nextGlucose = glucose + rate * dt
        val nextRate = rate * damp
        // F P F^T for F = [[1, dt], [0, damp]].
        val fp00 = p.a + dt * p.c
        val fp01 = p.b + dt * p.d
        val fp10 = damp * p.c
        val fp11 = damp * p.d
        val qTimeScale = dt / 5.0
        val predicted = Matrix2(
            a = fp00 + fp01 * dt + qGlucose * qTimeScale,
            b = fp01 * damp,
            c = fp10 + fp11 * dt,
            d = fp11 * damp + qRate * qTimeScale,
        )
        predicted.a = max(predicted.a, 0.1)
        predicted.d = max(predicted.d, 0.001)
        return Pair(Pair(nextGlucose, nextRate), predicted)
    }

    private fun update(
        predictedGlucose: Double,
        predictedRate: Double,
        p: Matrix2,
        measurement: Double,
        measurementVariance: Double,
    ): Pair<Pair<Double, Double>, Matrix2> {
        val pzz = p.a + measurementVariance
        if (pzz < 1e-6) return Pair(Pair(predictedGlucose, predictedRate), p)
        val kGlucose = p.a / pzz
        val kRate = p.c / pzz
        val innovation = measurement - predictedGlucose
        val glucose = predictedGlucose + kGlucose * innovation
        val rate = predictedRate + kRate * innovation
        val updated = Matrix2(
            a = max(p.a - kGlucose * pzz * kGlucose, 0.1),
            b = p.b - kGlucose * pzz * kRate,
            c = p.c - kRate * pzz * kGlucose,
            d = max(p.d - kRate * pzz * kRate, 0.001),
        )
        return Pair(Pair(glucose, rate), updated)
    }

    private fun adaptR(
        current: Double,
        normalized: Collection<Double>,
        raw: Collection<Double>,
        predicted: Collection<Double>,
    ): Double {
        if (normalized.size < ADAPT_MIN_INNOVATIONS || predicted.isEmpty()) return current
        val n = normalized.size
        val rawMean = trimmedMean(raw.take(n))
        val predictedMean = trimmedMean(predicted.take(n))
        val estimated = (rawMean - predictedMean).coerceIn(R_MIN, R_MAX)
        val goingUp = estimated > current
        val step = current + (if (goingUp) K_UP else K_DN) * (estimated - current)
        val lower = current * if (goingUp) DN_CAP_UP else DN_CAP_DN
        val upper = current * if (goingUp) UP_CAP_UP else UP_CAP_DN
        val clamped = step.coerceIn(lower, upper).coerceIn(R_MIN, R_MAX)
        return (1.0 - ETA) * current + ETA * clamped
    }

    private fun trimmedMean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val trim = min((sorted.size * ADAPT_TRIM_FRACTION).toInt(), (sorted.size - 1) / 2)
        val core = if (trim > 0) sorted.subList(trim, sorted.size - trim) else sorted
        return if (core.isEmpty()) 0.0 else core.average()
    }

    private fun <T> pushFront(queue: ArrayDeque<T>, value: T, maxSize: Int) {
        queue.addFirst(value)
        while (queue.size > maxSize) queue.removeLast()
    }
}
