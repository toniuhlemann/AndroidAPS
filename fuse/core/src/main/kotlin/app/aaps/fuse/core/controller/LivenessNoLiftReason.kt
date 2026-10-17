package app.aaps.fuse.core.controller

/**
 * WARUM DER BEWAFFNETE LIVENESS-KANAL NICHTS HEBT - dosierneutral
 * aufgeschluesselt (Toni 14.09.).
 *
 * Der bisherige Denial `NO_HEADROOM` fasste drei verschiedene Lagen
 * zusammen, und der Viewer las ihn als "Deckel voll" - auch dann, wenn es
 * schlicht keinen Bedarf gab. Der Denial bleibt unveraendert (Viewer- und
 * Trail-Kompatibilitaet); dieser Grund steht daneben.
 */
object LivenessNoLiftReason {

    enum class Reason {
        /** Die Prognose traegt keinen Bedarf ueber dem Ziel. */
        NO_NEED,

        /** Bedarf und Deckelraum sind da, der Kandidat liegt unter der Pumpenstufe. */
        BELOW_PUMP_STEP,

        /** Ein Deckel (Kontextgrenze, iobTH, maxIOB, maxSMB/Ratio) laesst nichts mehr zu. */
        CAP_EXHAUSTED,

        /** Der Kanal traegt etwas, der Normalpfad aber schon mindestens so viel. */
        NORMAL_COVERS,
    }

    /**
     * @return null, wenn der Kanal tatsaechlich hebt.
     */
    fun of(needU: Double, candidateU: Double, headroomU: Double, liveU: Double, normalU: Double): Reason? {
        if (liveU > normalU + 1e-9) return null
        if (liveU > 0.0) return Reason.NORMAL_COVERS
        if (!(needU > 0.0)) return Reason.NO_NEED
        if (!(candidateU > 0.0) || headroomU < candidateU - 1e-9) return Reason.CAP_EXHAUSTED
        return Reason.BELOW_PUMP_STEP
    }
}
