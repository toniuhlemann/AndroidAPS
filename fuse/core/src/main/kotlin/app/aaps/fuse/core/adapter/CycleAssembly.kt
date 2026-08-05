package app.aaps.fuse.core.adapter

import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.observer.ObserverInput
import app.aaps.fuse.core.predictor.IobPoint
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorReason

/**
 * Bruecke zwischen AAPS-Rohwerten und den reinen Kernen — aber weiterhin PUR:
 * keine Android-, AAPS- oder Dagger-Abhaengigkeit. Der Android-Adapter reicht
 * nur Primitive herein, damit derselbe Aufbau auf der JVM replaybar bleibt.
 *
 * Zweck: die Regeln, die AAPS-Eigenheiten in Kern-Eingaben uebersetzen, an EINER
 * Stelle und testbar zu halten — statt sie im Plugin zu verstreuen, wo sie
 * niemand nachrechnen kann.
 */
object CycleAssembly {

    /** Kausales LOCF: der juengste Aktivitaetspunkt, der NICHT in der Zukunft
     *  liegt und nicht aelter als [maxAgeMs] ist. Ein Punkt aus der Zukunft ist
     *  kein gueltiges LOCF (K1 §2.1), auch wenn er "besser passt". */
    fun activityValidity(
        activityModelTs: Long?,
        sourceTs: Long,
        availableAt: Long?,
        computeTs: Long,
        maxAgeMs: Long = 180_000L,
    ): ActivityValidity = when {
        activityModelTs == null                       -> ActivityValidity.MISSING
        activityModelTs > sourceTs                    -> ActivityValidity.FUTURE
        sourceTs - activityModelTs > maxAgeMs         -> ActivityValidity.STALE
        availableAt != null && availableAt > computeTs -> ActivityValidity.MISSING
        else                                          -> ActivityValidity.VALID
    }

    /**
     * Baut die K1-Eingabe. `q1` und `rSigned` kommen bereits berechnet herein —
     * der Aufbau entscheidet nichts ueber das Signal, er reicht es durch.
     */
    fun observerInput(
        sourceTs: Long,
        computeTs: Long,
        signalInputBg: Double?,
        q1: Double?,
        rSigned: Double?,
        sensorEpoch: Long,
        calibrationEpoch: Long,
        activity: ActivityValidity,
        profileIsfValid: Boolean,
        inputGap: Boolean,
    ) = ObserverInput(
        sourceTs = sourceTs,
        computeTs = computeTs,
        signalInputBg = signalInputBg,
        q1 = q1,
        rSigned = rSigned,
        sensorEpoch = sensorEpoch,
        calibrationEpoch = calibrationEpoch,
        activity = activity,
        profileIsfValid = profileIsfValid,
        inputGap = inputGap,
    )

    /**
     * Uebersetzt das AAPS-Zukunftsarray. Erwartet GENAU das Objekt, das
     * DetermineBasal im selben Zyklus verwendet hat — ein Nachrechnen waere
     * eine andere Momentaufnahme (Treatment-DB, laufende TBR, now koennen sich
     * geaendert haben).
     *
     * `activity` darf negativ sein: nach einer Zero-/Low-TBR ist
     * netBasalRate = rate - basalRate negativ, und bgi = -activity*isf ergibt
     * dann korrekt positives BGI.
     */
    fun iobPoints(times: LongArray, iob: DoubleArray, activity: DoubleArray, basalIob: DoubleArray): List<IobPoint> {
        require(times.size == iob.size && times.size == activity.size && times.size == basalIob.size) {
            "iob array columns differ in length"
        }
        return times.indices.map { IobPoint(times[it], iob[it], activity[it], basalIob[it]) }
    }

    /**
     * Verdichtet aufgeloeste ISF-Werte zu minimalen Slots. Der Aufrufer liefert
     * pro Stuetzstelle den DANN gueltigen ISF (inklusive Profilprozentsatz,
     * Timeshift und Ablauf eines Profile Switch); hier werden nur gleiche
     * Nachbarn zusammengefasst — keine DB-Abfrage je Minute.
     */
    fun compressIsfSlots(ts: LongArray, isf: DoubleArray, endExclusive: Long): List<IsfSlot> {
        require(ts.size == isf.size) { "isf columns differ in length" }
        if (ts.isEmpty()) return emptyList()
        val out = ArrayList<IsfSlot>()
        var start = ts[0]
        var cur = isf[0]
        for (i in 1 until ts.size) {
            if (isf[i] != cur) {
                out.add(IsfSlot(start, ts[i], cur))
                start = ts[i]
                cur = isf[i]
            }
        }
        out.add(IsfSlot(start, endExclusive, cur))
        return out
    }

    /** Deckt die Slotfolge den gesamten Abfragebereich ab? Eine Luecke ergibt
     *  keinen Ersatzwert, sondern eine Ablehnung im Kern. */
    fun isfCoverageGap(slots: List<IsfSlot>, fromTs: Long, toTs: Long): PredictorReason? {
        if (slots.isEmpty()) return PredictorReason.MISSING_ISF_SLOT
        val sorted = slots.sortedBy { it.startTsInclusive }
        if (sorted.first().startTsInclusive > fromTs) return PredictorReason.MISSING_ISF_SLOT
        var cursor = sorted.first().startTsInclusive
        for (s in sorted) {
            if (s.startTsInclusive > cursor) return PredictorReason.MISSING_ISF_SLOT
            if (s.endTsExclusive > cursor) cursor = s.endTsExclusive
        }
        return if (cursor <= toTs) PredictorReason.MISSING_ISF_SLOT else null
    }
}
