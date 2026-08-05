package app.aaps.fuse.core.profile

import app.aaps.fuse.core.predictor.IsfSlot

/**
 * Aufgeloeste Profilgroessen ueber den Horizont (K2-C v0.3 §8, R76-F9).
 *
 * Warum aufgeloest und nicht "Uhrzeitblock":
 * `Profile.getBasal(ts)` wendet Prozentsatz und Timeshift des UEBERGEBENEN
 * Profilobjekts an (verifiziert in `ProfileSealed.kt:259`) — es wechselt aber
 * nicht zu einem anderen Effective Profile, wenn der laufende Profile Switch
 * im Horizont endet. Der Android-Adapter loest deshalb je Stuetzstelle zuerst
 * `profileFunction.getProfile(ts)` auf und ruft DANN `getBasal(ts)` darauf.
 *
 * Der pure Kern sieht nur noch fertige Slots — und der Test dafuer (KC2-49)
 * laeuft ohne AAPS.
 *
 * Eine LUECKE ergibt hier nie einen Ersatzwert. Ein hochgerechneter
 * Momentanwert waere eine Behauptung ueber ein Profil, das niemand gelesen hat.
 */
data class BasalSlot(val startTsInclusive: Long, val endTsExclusive: Long, val rateUPerH: Double) {

    init {
        require(endTsExclusive > startTsInclusive) { "empty basal slot" }
        require(rateUPerH.isFinite() && rateUPerH >= 0.0) { "basal rate invalid: $rateUPerH" }
    }
}

object ProfileSlots {

    /**
     * Verdichtet je Stuetzstelle aufgeloeste Basalraten zu minimalen Slots.
     * Gleiche Nachbarn werden zusammengefasst — keine Profilabfrage je Minute
     * im spaeteren Rechenpfad.
     */
    fun compressBasal(ts: LongArray, rateUPerH: DoubleArray, endExclusive: Long): List<BasalSlot> {
        require(ts.size == rateUPerH.size) { "basal columns differ in length" }
        if (ts.isEmpty()) return emptyList()
        require(endExclusive > ts.last()) { "endExclusive must lie behind the last support point" }
        val out = ArrayList<BasalSlot>()
        var start = ts[0]
        var cur = rateUPerH[0]
        for (i in 1 until ts.size) {
            if (rateUPerH[i] != cur) {
                out.add(BasalSlot(start, ts[i], cur))
                start = ts[i]
                cur = rateUPerH[i]
            }
        }
        out.add(BasalSlot(start, endExclusive, cur))
        return out
    }

    /** Rate zu [tsMs], oder null wenn kein Slot ihn deckt. */
    fun basalAt(slots: List<BasalSlot>, tsMs: Long): Double? =
        slots.firstOrNull { tsMs >= it.startTsInclusive && tsMs < it.endTsExclusive }?.rateUPerH

    /** ISF zu [tsMs], oder null wenn kein Slot ihn deckt. */
    fun isfAt(slots: List<IsfSlot>, tsMs: Long): Double? =
        slots.firstOrNull { tsMs >= it.startTsInclusive && tsMs < it.endTsExclusive }?.isfMgdlPerU

    /**
     * Basalintegral ueber [fromTs, toTs) in Einheiten — die Rueckholkapazitaet,
     * gegen die das Release-Budget spaeter bemessen wird (K2-C v0.3 §9).
     *
     * Die BUDGETPOLICY (Fensterlaenge, k_risk, Ereignisimpuls) ist bis KC2-53
     * ausdruecklich NICHT entschieden. Was hier steht, ist nur das Messmittel:
     * das Integral folgt dem aufgeloesten Profil, es wird nicht aus einem
     * Momentanwert hochgerechnet.
     *
     * Rueckgabe null = Luecke in der Abdeckung -> der Aufrufer faellt
     * fail-closed, er rechnet NICHT mit einem Teilintegral weiter.
     */
    fun basalIntegralU(slots: List<BasalSlot>, fromTs: Long, toTs: Long): Double? {
        if (toTs <= fromTs) return 0.0
        val sorted = slots.sortedBy { it.startTsInclusive }
        var cursor = fromTs
        var acc = 0.0
        for (s in sorted) {
            // Reihenfolge ist tragend: erst das Ende des Abfragebereichs, dann
            // die Lueckenpruefung. Andersherum meldete eine Luecke HINTER [toTs]
            // faelschlich ein unvollstaendiges Integral.
            if (cursor >= toTs) break
            if (s.endTsExclusive <= cursor) continue
            if (s.startTsInclusive > cursor) return null       // Luecke
            val end = minOf(s.endTsExclusive, toTs)
            acc += s.rateUPerH * (end - cursor) / 3_600_000.0
            cursor = end
        }
        return if (cursor >= toTs) acc else null
    }

    /** Deckt die Slotfolge [fromTs, toTs) vollstaendig ab? */
    fun basalCovers(slots: List<BasalSlot>, fromTs: Long, toTs: Long): Boolean =
        basalIntegralU(slots, fromTs, toTs) != null
}
