package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * DER LIVENESS-KANAL (Bauvertrag Toni + Codex, 22.08. nachts).
 *
 * DER GEMESSENE ANLASS: Fruehstueck und Abendessen am 22.08. - dieselbe
 * Signatur. Fruehe Versorgung ok (~3 U/30 min), danach der Deadlock: die
 * Mittelbahn meldet 1,8-2,1 U Bedarf bei BG 217-233 und r +3,2-3,5, aber die
 * KOHLENHYDRATFREIE Unterkante (bis -1 mg/dl zertifiziert) und der
 * DIA-Schwanz nullen bzw. rationieren jede Abgabe (Tail-Saegezahn: 0,05-0,30
 * je Zyklus, dann TAIL-Null). In 93 von 93 Deadlock-Zyklen des Tages lag die
 * zertifizierte Unterkante um median +97 mg/dl unter dem real eingetretenen
 * 120-min-Minimum. 90 der 116 Minuten ueber 180 waren blockierte Minuten mit
 * ERKANNTEM Bedarf.
 *
 * DIE ARCHITEKTUR-ANTWORT (Tonis autoISF-Referenz): Sicherheit fuer diesen
 * kleinen Zusatzkanal MENGENBASIERT statt bahnbasiert. Der Kanal macht den
 * BEREITS ERKANNTEN Mittelbahn-Bedarf dosierbar, den Guard und Tail heute
 * wegwerfen - er erfindet keinen eigenen Bedarf:
 *
 *     normalU = heutiger Pfad inklusive Guard und Tail (UNVERAENDERT)
 *     liveU   = Mittelbahn-Kandidat vor Guard/Tail
 *               -> smbRatio, maxSMB, EIGENER Kanaldeckel, globales iobTH,
 *                  maxIOB, Transporthaftung, Pumpenraster
 *     finalU  = max(normalU, liveU)   - NIEMALS Addition
 *
 * Guard- und Tail-Werte bleiben im Export sichtbar, bestimmen aber
 * ausschliesslich den normalen Pfad: im Kanal ist der Tail WEDER Veto NOCH
 * Mengenkappe - sonst reproduziert der Kanal exakt den Saegezahn.
 *
 * P0 (Codex): der Kanaldeckel ist ein EIGENER Prozentsatz
 * (LivenessIobCapPercent), NICHT das globale iobTH - das begrenzt auch
 * Korrektur, Prime und Fundament, und eine globale Senkung wuerde
 * ausgerechnet die fruehe Mahlzeitenversorgung aushungern. Ist das globale
 * iobTH niedriger, gewinnt selbstverstaendlich die strengere Grenze.
 *
 * ABSOLUT BLEIBEN (beide Pfade): gemessenes Fallen (30er-Risiko, gepinnter
 * Marker-Horizont, Latch), gemessenes Tief, Rebound, Signalfehler,
 * Ledger-Hold, Transport- und Pumpengates. Exit bei bestaetigter
 * Abwaertswende (P2) und bei jeder manuellen Intervention; danach gilt eine
 * RESTARTFESTE Wiederbewaffnungs-Sperre.
 */
object LivenessChannel {

    /** Bestaetigte Rueckgaenge des schnellen Drives, ab denen der Kanal
     *  aussteigt - dieselbe Persistenzzaehlung wie ADAPTIVE-DOWN P2. */
    /** Zyklen in Folge, die die Druckbedingung erfuellen muessen. */
    const val ARM_STREAK = 3

    /** Untergrenze der "nicht fallenden Messbahn" [mg/dl/min]. */
    const val UKF_FLOOR_MGDL_PER_MIN = -0.10

    /** Druckbedingung der Aktivierung. Die BG-Schwelle ist KEINE Konstante
     *  mehr, sondern `FuseDoubleKey.LivenessBgMinMgdl` (Toni 22.08.: hart
     *  codierte 160 waeren eine unnoetige Sackgasse - der Replay soll
     *  niedrigere Schwellen vergleichen koennen, ohne Architekturumbau). */
    const val R_MIN_MGDL_PER_MIN = 1.0

    /**
     * Der Kandidat des Kanals: DERSELBE Bedarf, den der Regler rechnet
     * ([FuseController]: `insulinReq = (releaseMean - target) / isf` - die
     * IOB-Wirkung steckt bereits in der Bahn, KEIN zweites "- iob"),
     * ratio- und maxSMB-gedeckelt. Kein Fantasiebedarf.
     */
    fun candidateU(
        releaseMeanMgdl: Double,
        targetMgdl: Double,
        isfMgdlPerU: Double,
        smbRatio: Double,
        maxSmbU: Double,
    ): Double {
        if (!releaseMeanMgdl.isFinite() || !targetMgdl.isFinite()) return 0.0
        if (!isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0) return 0.0
        if (!smbRatio.isFinite() || smbRatio <= 0.0) return 0.0
        if (!maxSmbU.isFinite() || maxSmbU <= 0.0) return 0.0
        val reqU = (releaseMeanMgdl - targetMgdl) / isfMgdlPerU
        if (reqU <= 0.0) return 0.0
        return min(smbRatio * reqU, maxSmbU)
    }

    /** Ergebnis der Deckelrechnung: Rest und der Name der bindenden Grenze. */
    data class Headroom(val headroomU: Double, val binding: String)

    /**
     * P0-Deckelvertrag: `min(globales iobTH, eigener Kanaldeckel, maxIOB)
     * minus capIob minus Transporthaftung`. Die strengste Grenze gewinnt und
     * wird BENANNT - der Export muss zeigen, wer gebunden hat.
     */
    fun headroomU(
        globalIobThU: Double,
        livenessCapU: Double,
        maxIobU: Double,
        capIobU: Double,
        transportU: Double,
    ): Headroom {
        val grenzen = listOf(
            "globalIobTh" to globalIobThU,
            "livenessCap" to livenessCapU,
            "maxIob" to maxIobU,
        ).filter { it.second.isFinite() }
        if (grenzen.isEmpty()) return Headroom(0.0, "invalid")
        val (name, grenze) = grenzen.minByOrNull { it.second }!!
        val belegt = (if (capIobU.isFinite()) max(0.0, capIobU) else return Headroom(0.0, "invalid")) +
            (if (transportU.isFinite()) max(0.0, transportU) else return Headroom(0.0, "invalid"))
        return Headroom(max(0.0, grenze - belegt), name)
    }

    /** Pumpenraster: reine QUANTISIERUNG nach unten, KEIN Carry - der Rest
     *  verfaellt in diesem Zyklus (kein Nachhol-Burst, Vertrag). */
    fun quantize(u: Double, stepU: Double): Double {
        if (!u.isFinite() || !stepU.isFinite() || stepU <= 0.0 || u < stepU) return 0.0
        return kotlin.math.floor(u / stepU + 1e-9) * stepU
    }

    /**
     * finalU = max(normalU, liveU) - NIEMALS Addition. Der Beitrag des
     * Kanals ist die Differenz; ist der Normalpfad wieder offen
     * (normal >= live), liefert der Kanal automatisch nichts.
     */
    fun finalU(normalU: Double, liveU: Double): Double = max(normalU, liveU)
}
