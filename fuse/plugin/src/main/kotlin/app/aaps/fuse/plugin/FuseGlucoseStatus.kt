package app.aaps.fuse.plugin

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusSMB

/**
 * Der app-weite Glukosestatus — NICHT FUSEs Regelsignal.
 *
 * Wichtig zu wissen, warum es diese Klasse ueberhaupt gibt:
 * `GlucoseStatusProviderImpl` leitet die app-weite Eigenschaft
 * `glucoseStatusData` direkt an `activePlugin.activeAPS.getGlucoseStatusData()`
 * weiter. Sobald FUSE das aktive APS ist, haengen also Overview, Wear,
 * Automations und die xDrip-Bruecke an DIESER Antwort. Ein `null` waere kein
 * "FUSE benutzt das nicht", sondern ein stiller Ausfall in halb AAPS.
 *
 * Deshalb rechnet FUSE den Status ehrlich, aber bewusst schlicht — und auf
 * `GV.value`, dem Wert, den der Nutzer auch sieht. FUSEs eigene Entscheidung
 * laeuft ueber `q1`/`rSigned` aus [FuseSignalSource] und beruehrt diese Zahlen
 * nicht.
 */
object FuseGlucoseStatus {

    const val MAX_AGE_MS = 7 * 60_000L
    private const val DELTA_MS = 5 * 60_000L
    private const val SHORT_MS = 15 * 60_000L
    private const val LONG_MS = 45 * 60_000L

    /**
     * @param readings absteigend ODER aufsteigend — wird hier sortiert.
     *                 `getBgReadingsDataTableCopy()` liefert NEUESTE ZUERST.
     */
    fun of(readings: List<GV>, nowMs: Long, allowOldData: Boolean): GlucoseStatus? {
        val sorted = readings.filter { it.value > 39.0 }.sortedByDescending { it.timestamp }
        val newest = sorted.firstOrNull() ?: return null
        if (!allowOldData && newest.timestamp < nowMs - MAX_AGE_MS) return null

        fun avgSlopePerMinute(windowMs: Long): Double? {
            val inWindow = sorted.filter { it.timestamp >= newest.timestamp - windowMs && it.timestamp < newest.timestamp }
            if (inWindow.isEmpty()) return null
            // Mittlere Steigung gegen den juengsten Punkt - robuster als der
            // Vergleich mit genau EINEM alten Wert, und ohne Fremdcode.
            val slopes = inWindow.mapNotNull { old ->
                val dtMin = (newest.timestamp - old.timestamp) / 60_000.0
                if (dtMin <= 0.0) null else (newest.value - old.value) / dtMin
            }
            return if (slopes.isEmpty()) null else slopes.average()
        }

        // AAPS-Konvention: Delta ist mg/dl je 5 Minuten.
        val delta = avgSlopePerMinute(DELTA_MS)?.times(5.0) ?: 0.0
        val shortAvg = avgSlopePerMinute(SHORT_MS)?.times(5.0) ?: delta
        val longAvg = avgSlopePerMinute(LONG_MS)?.times(5.0) ?: shortAvg

        return GlucoseStatusSMB(
            glucose = newest.value,
            noise = 0.0,
            delta = delta,
            shortAvgDelta = shortAvg,
            longAvgDelta = longAvg,
            date = newest.timestamp,
        )
    }
}
