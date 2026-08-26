package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import org.json.JSONObject

/**
 * DAS TIEF-GEDAECHTNIS UEBER EINEN NEUSTART RETTEN.
 *
 * DER VORFALL (Toni, 15.08. 18:15, produktiv gemessen): das Rebound-Totband
 * sperrte ab 17:42 jeden Zyklus (SAFETY_HOLD, q1 fiel bis 70,2). Um 17:56 lief
 * ein Flash - danach stand `lastLowTs` wieder auf 0, `reboundWindow` kippte auf
 * false, und ab 17:58 gab FUSE wieder SMBs ab, obwohl das juengste Tief elf
 * Minuten alt war und das Fenster regulaer bis 18:35 gelaufen waere.
 *
 * Der Zustand war ausdruecklich als "fail-open, dokumentiert" vermerkt. Das ist
 * fuer einen SCHUTZ die falsche Richtung: der Verlust oeffnet den SMB-Kanal
 * genau in der Lage, fuer die das Totband gebaut wurde - im Anstieg nach einem
 * Tief, wo die Erholung das ZIEL der Gegenmassnahme ist und keine Stoerung.
 *
 * Rekonstruierbar ist er ohne neue Persistenz: `signal.q1` steht in jeder
 * Trail-Zeile. Dieselbe Quelle, aus der schon die Markerdrucke zurueckgeholt
 * werden (s. `FusePlugin.warmGraphRingOnce`).
 */
internal object FuseLowMemory {

    /**
     * Juengster Zeitpunkt mit `q1 < REBOUND_LOW_MGDL` aus den Trail-Zeilen,
     * oder 0, wenn keiner im noch wirksamen Fenster liegt.
     *
     * Zeilen ohne `q1` (aeltere Exporte, Abbruchzyklen) werden uebersprungen -
     * eine fehlende Zahl ist kein Tief. Aelteres als [reboundWindowMin] wird
     * verworfen: es wuerde das Fenster nicht mehr oeffnen, und ein
     * Zeitstempel, der nichts mehr bewirkt, hat im Zustand nichts zu suchen.
     *
     * DIE DAUER IST EIN PARAMETER, KEINE KONSTANTE (26.08.). Stuende hier
     * weiter [FuseController.REBOUND_WINDOW_MIN], kappte ein Neustart ein
     * laenger eingestelltes Fenster still auf 45 Minuten - der Schutz waere
     * ausgerechnet nach einem Flash am kuerzesten, also genau dort, wo der
     * Vorfall vom 15.08. ihn schon einmal verloren hat.
     *
     * @param reboundWindowMin Dauer des Fensters [min]. Werte kleiner gleich 0
     *   werden auf [FuseController.REBOUND_WINDOW_MIN] gehoben - ein
     *   fehlerhafter Aufruf darf das Gedaechtnis nicht ausschalten.
     */
    fun lastLowTsFromTrail(lines: Sequence<String>, nowMs: Long, reboundWindowMin: Int): Long {
        val fenster = if (reboundWindowMin > 0) reboundWindowMin else FuseController.REBOUND_WINDOW_MIN
        val cutoff = nowMs - fenster * 60_000L
        var neuestes = 0L
        for (line in lines) {
            val j = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val ts = j.optLong("sourceTs", 0L)
            if (ts <= cutoff || ts <= neuestes) continue
            // Nicht in die Zukunft: eine Zeile mit verstellter Uhr wuerde das
            // Fenster sonst weit ueber seine Laufzeit hinaus offen halten.
            if (ts > nowMs) continue
            val sig = j.optJSONObject("signal") ?: continue
            if (sig.isNull("q1")) continue
            val q1 = sig.optDouble("q1", Double.NaN)
            if (!q1.isFinite() || q1 >= FuseController.REBOUND_LOW_MGDL) continue
            neuestes = ts
        }
        return neuestes
    }
}
