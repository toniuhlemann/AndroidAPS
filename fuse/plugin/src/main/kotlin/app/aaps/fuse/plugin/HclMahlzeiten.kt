package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * HCL-KOMPATIBILITAET, TEIL 1 (Toni 25.10.2026): die Mahlzeiten aus dem AAPS-Bolusrechner.
 *
 * Der gemeinsame Bolus-Kern meldet nach der Pumpenantwort EINE Rechnung mit KH und der tatsaechlich abgegebenen
 * Menge ([FuseOverviewSource.fuseWizardBolus]). AAPS speichert keine Verbindung zwischen Rechnung und Bolus, und die
 * Rechnung wird auch dann gespeichert, wenn die Pumpe scheitert - deshalb meldet der Bolus-Kern beides zusammen.
 * Hier wird jede Rechnung genau einmal festgehalten (Schluessel: der Zeitpunkt der Rechnung) und bleibt ueber einen
 * Neustart erhalten.
 *
 * Teil 1 DOSIERT NICHT: die Zahlen gehen in den Trail fuer den Viewer. FUSE liest dafuer auch keine KH-Tabelle
 * (`NoCobDependencyTest`) - die Zahlen kommen fertig vom Bolusrechner. Stufe 2 (Schalter, ab Werk aus) liest von einer
 * Rechnung nur das KH-Insulin - fuer den Skalierer der Anrechnung bei Guard und Schwanz, nie als Menge.
 */
internal class HclMahlzeiten(private val preferences: Preferences) {

    private val sperre = Any()

    /** Haelt die Rechnung fest. @return ob sie neu war - eine wiederholte Meldung derselben Rechnung zaehlt nicht. */
    fun aufnehmen(b: FuseOverviewSource.WizardBolus, jetzt: Long): Boolean = synchronized(sperre) {
        if (!gueltig(b)) return false
        val alle = laden().filter { jetzt - it.mealTs <= AUFBEWAHRUNG_MS }.toMutableList()
        if (alle.any { it.calcTs == b.calcTs }) return false
        alle += b
        preferences.put(FuseStringNonKey.HclMeals, JSONArray(alle.sortedBy { it.calcTs }.takeLast(MAX_ANZAHL).map(::zuJson)).toString())
        true
    }

    /** Die Rechnungen, deren Mahlzeit in [fensterMs] zurueck bis [VORAUS_MS] voraus liegt - fuer den Trail. */
    fun juengste(jetzt: Long, fensterMs: Long = TRAIL_FENSTER_MS): List<FuseOverviewSource.WizardBolus> = synchronized(sperre) {
        laden().filter { it.mealTs in (jetzt - fensterMs)..(jetzt + VORAUS_MS) }.sortedBy { it.calcTs }
    }

    /** HCL Stufe 2: die Rechnung [calcTs] - fuer den Skalierer des Kontos am Marker. `null` = nicht (mehr) gespeichert. */
    fun rechnung(calcTs: Long): FuseOverviewSource.WizardBolus? = synchronized(sperre) {
        if (calcTs <= 0L) null else laden().firstOrNull { it.calcTs == calcTs }
    }

    /** HCL Stufe 2: was eine gemeldete Rechnung am Marker bewirkt (FusePlugin.hclMarkerUnterSperre, [uebernahme]). */
    sealed interface Uebernahme {
        /** Schalter aus oder kein abgegebener Bolus: nur aufgezeichnet. */
        data object Keine : Uebernahme

        /** Die Essenszeit liegt zu weit zurueck ([RUECKDATIERUNG_MS]): kein Marker. */
        data object ZuAlt : Uebernahme

        /** Der laufende Marker gehoert schon zu einer Rechnung: nur aufgezeichnet - weniger Anrechnung, nie mehr. */
        data object SchonHcl : Uebernahme

        /** Mit dem laufenden Marker zusammenfuehren: seine weiteren Portionen entfallen, keine Anrechnung. */
        data object Zusammenfuehren : Uebernahme

        /** Ein neuer Marker "Nur Marker" zu diesem Zeitpunkt, mit der Rechnung als Kennung. */
        data class NeuerMarker(val ts: Long) : Uebernahme
    }

    private fun laden(): List<FuseOverviewSource.WizardBolus> {
        val text = preferences.get(FuseStringNonKey.HclMeals)
        if (text.isBlank()) return emptyList()
        val a = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::vonJson) }
    }

    companion object {

        /** So lange bleibt eine Rechnung gespeichert [ms]. */
        const val AUFBEWAHRUNG_MS = 24 * 3600_000L

        /** So lange steht sie in jedem Trail-Datensatz [ms] - lang genug fuer die ganze Mahlzeit. */
        const val TRAIL_FENSTER_MS = 8 * 3600_000L

        /** Eine Mahlzeit darf so weit in der Zukunft liegen (KH-Zeit mit Versatz) [ms]. */
        const val VORAUS_MS = 2 * 3600_000L

        /** HCL Stufe 2: so weit darf der Marker auf den Essenszeitpunkt zurueckgelegt werden [ms]. */
        const val RUECKDATIERUNG_MS = 30 * 60_000L

        /**
         * HCL Stufe 2: der Markerzeitpunkt einer Rechnung - der Essenszeitpunkt, damit die Kurve mit der Mahlzeit
         * beginnt und nicht mit dem Ende der Abgabe. Liegt er in der Zukunft (KH-Zeit voraus), steht der Marker jetzt:
         * die Mahlzeit zeigt sich dann spaeter als erwartet, und angerechnet wird nur, was sich zeigt (weniger, nie
         * mehr). Liegt er mehr als [RUECKDATIERUNG_MS] zurueck, ist die Mahlzeit schon weit gelaufen - `null`, kein
         * Marker: eine spaet beginnende Kurve rechnete Guard und Schwanz mehr kommende Mahlzeit an, als noch kommt.
         */
        fun markerZeit(mealTs: Long, jetzt: Long): Long? = when {
            mealTs >= jetzt -> jetzt
            jetzt - mealTs <= RUECKDATIERUNG_MS -> mealTs
            else -> null
        }

        /**
         * HCL Stufe 2: die Entscheidung fuer eine gemeldete Rechnung. Nur ein wirklich abgegebener Bolus zaehlt - ein
         * abgebrochener mit seiner Teilmenge, einer ohne Abgabe gar nicht (kein Nachholauftrag, Codex). Laeuft zum
         * Essenszeitpunkt oder jetzt ein Marker, gehoert die Rechnung zu ihm.
         */
        fun uebernahme(
            schalterAn: Boolean,
            deliveredU: Double,
            mealTs: Long,
            jetzt: Long,
            markerAktiv: (Long) -> Boolean,
            laufenderIstHcl: Boolean,
        ): Uebernahme {
            if (!schalterAn || !(deliveredU > 0.0)) return Uebernahme.Keine
            val anker = markerZeit(mealTs, jetzt) ?: return Uebernahme.ZuAlt
            if (markerAktiv(jetzt) || markerAktiv(anker))
                return if (laufenderIstHcl) Uebernahme.SchonHcl else Uebernahme.Zusammenfuehren
            return Uebernahme.NeuerMarker(anker)
        }

        private const val MAX_ANZAHL = 20

        private fun ok(x: Double) = x.isFinite()

        fun gueltig(b: FuseOverviewSource.WizardBolus): Boolean =
            b.calcTs > 0L && b.mealTs > 0L &&
                ok(b.carbsG) && b.carbsG > 0.0 &&
                ok(b.icGPerU) && b.icGPerU > 0.0 &&
                ok(b.carbsInsulinU) && b.carbsInsulinU >= 0.0 &&
                ok(b.requestedU) && b.requestedU >= 0.0 &&
                ok(b.deliveredU) && b.deliveredU >= 0.0 &&
                ok(b.calcTotalU) && ok(b.directCorrectionU) && ok(b.isfMgdlPerU)

        /** Der Trail-Block einer Rechnung - dieselben Namen wie in [FuseOverviewSource.WizardBolus]. */
        fun zuJson(b: FuseOverviewSource.WizardBolus): JSONObject {
            fun opt(x: Double?): Any = x?.takeIf { it.isFinite() } ?: JSONObject.NULL
            return JSONObject()
                .put("calcTs", b.calcTs)
                .put("mealTs", b.mealTs)
                .put("carbsG", b.carbsG)
                .put("icGPerU", b.icGPerU)
                .put("isfMgdlPerU", b.isfMgdlPerU)
                .put("carbsInsulinU", b.carbsInsulinU)
                .put("glucoseInsulinU", opt(b.glucoseInsulinU))
                .put("trendInsulinU", opt(b.trendInsulinU))
                .put("cobInsulinU", opt(b.cobInsulinU))
                .put("iobU", opt(b.iobU))
                .put("superbolusU", opt(b.superbolusU))
                .put("directCorrectionU", b.directCorrectionU)
                .put("percentage", b.percentage)
                .put("calcTotalU", b.calcTotalU)
                .put("requestedU", b.requestedU)
                .put("deliveredU", b.deliveredU)
                .put("success", b.success)
        }

        fun vonJson(o: JSONObject): FuseOverviewSource.WizardBolus? = runCatching {
            fun opt(k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.getDouble(k)
            FuseOverviewSource.WizardBolus(
                calcTs = o.getLong("calcTs"),
                mealTs = o.getLong("mealTs"),
                carbsG = o.getDouble("carbsG"),
                icGPerU = o.getDouble("icGPerU"),
                isfMgdlPerU = o.getDouble("isfMgdlPerU"),
                carbsInsulinU = o.getDouble("carbsInsulinU"),
                glucoseInsulinU = opt("glucoseInsulinU"),
                trendInsulinU = opt("trendInsulinU"),
                cobInsulinU = opt("cobInsulinU"),
                iobU = opt("iobU"),
                superbolusU = opt("superbolusU"),
                directCorrectionU = o.getDouble("directCorrectionU"),
                percentage = o.getInt("percentage"),
                calcTotalU = o.getDouble("calcTotalU"),
                requestedU = o.getDouble("requestedU"),
                deliveredU = o.getDouble("deliveredU"),
                success = o.getBoolean("success"),
            )
        }.getOrNull()?.takeIf(::gueltig)
    }
}
