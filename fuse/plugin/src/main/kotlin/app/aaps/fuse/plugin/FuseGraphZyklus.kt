package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource
import org.json.JSONObject

/**
 * DER ZYKLUS FUER DEN HAUPTGRAPHEN (Toni 21.10.2026) - eine Regel fuer den Live-Ring und den Warmstart aus dem Trail.
 *
 * Die BG-Punkte der Uebersicht tragen die Farbe dessen, was FUSE in diesem Zyklus durfte oder tat; die SMB ihre
 * Quelle; der langsame Korrekturkanal steht als Band. Dieselben Regeln wie im Viewer (FuseGraphState), damit beide
 * Schirme dasselbe zeigen. Reine Anzeige: nichts hiervon fliesst in eine Entscheidung.
 */
/** Der Ringpunkt mit den Hauptgraph-Feldern dieses Zyklus. */
internal fun FuseOverviewSource.Point.mitHauptgraph(f: FuseGraphZyklus.Felder): FuseOverviewSource.Point = copy(
    cycleState = f.zustand,
    smbU = f.smbU,
    smbSource = f.quelle,
    channel = f.kanal,
    bindingLimit = f.bindung,
    insulinReqU = f.bedarfU,
)

internal object FuseGraphZyklus {

    /**
     * Vorrang wie im Viewer: Schutz-Null (angefordert oder an der Pumpe laufend) vor Kanal vor Guard vor anderer
     * Sperre vor frei. Ohne Entscheidung oder ohne Eingang ist der Zustand unbekannt.
     */
    fun zustand(
        block: String?,
        tbr: String?,
        nullAktiv: Boolean?,
        kanalPhase: String?,
        smbState: String?,
        stopReason: String?,
    ): FuseOverviewSource.CycleState = when {
        block == null || block == "NO_INPUT"               -> FuseOverviewSource.CycleState.UNKNOWN
        tbr == "ZERO_TEMP" || nullAktiv == true            -> FuseOverviewSource.CycleState.ZERO
        kanalPhase == "PENDING" || kanalPhase == "RUNNING" -> FuseOverviewSource.CycleState.CHANNEL
        smbState == "STOP"                                 ->
            if (stopReason == "GUARD") FuseOverviewSource.CycleState.GUARD else FuseOverviewSource.CycleState.BLOCKED

        else                                               -> FuseOverviewSource.CycleState.FREE
    }

    /** `exposure.source` -> Quelle; NORMAL_SUBSTEP zaehlt zum Normalweg. */
    fun quelle(source: String?): FuseOverviewSource.SmbSource = when (source) {
        "EXPECTATION"              -> FuseOverviewSource.SmbSource.EXPECTATION
        "NORMAL", "NORMAL_SUBSTEP" -> FuseOverviewSource.SmbSource.NORMAL
        else                       -> FuseOverviewSource.SmbSource.OTHER
    }

    /** Der Kanal eines Live-Zyklus. */
    fun kanal(x: FuseCycleRunner.SlowChannelExport): FuseOverviewSource.ChannelState = FuseOverviewSource.ChannelState(
        phase = x.phase,
        reason = x.reason,
        detail = x.detail,
        rateUPerH = x.rateUPerH.takeIf { it.isFinite() },
        extraUPerH = x.extraUPerH.takeIf { it.isFinite() },
        deliveredU = x.deliveredU.takeIf { it.isFinite() },
    )

    /** Die Hauptgraph-Felder eines Live-Zyklus - aus dem endgueltigen Ausgang und der Menge, die AAPS bekam. */
    fun felder(o: FuseCycleRunner.Outcome, publizierteSmbU: Double?): Felder {
        val smbU = publizierteSmbU?.takeIf { it.isFinite() && it > 0.0 }
        return Felder(
            zustand = zustand(o.decision.block.name, o.decision.tbr.name, o.currentZeroTbrActive, o.slowChannel?.phase, o.smbState, o.smbStopReason),
            smbU = smbU,
            quelle = smbU?.let { quelle(o.exposureFinalSource) },
            kanal = o.slowChannel?.let { kanal(it) },
            bindung = o.decision.bindingLimit,
            bedarfU = o.decision.insulinReqU?.takeIf { it.isFinite() },
        )
    }

    /** Dieselben Felder aus einer Trail-Zeile (Warmstart). Android liefert fuer JSON-null "null" - daher isNull(). */
    fun felder(j: JSONObject): Felder {
        val dec = j.optJSONObject("decision")
        val smb = j.optJSONObject("smb")
        val sc = j.optJSONObject("slowChannel")
        val kanal = sc?.let {
            FuseOverviewSource.ChannelState(
                phase = it.text("phase") ?: "NONE",
                reason = it.text("reason"),
                detail = it.text("detail"),
                rateUPerH = it.zahl("rateUPerH"),
                extraUPerH = it.zahl("extraUPerH"),
                deliveredU = it.zahl("deliveredU"),
            )
        }
        val smbU = j.optJSONObject("rt")?.zahl("units")?.takeIf { it > 0.0 }
        return Felder(
            zustand = zustand(
                block = dec?.text("block"),
                tbr = dec?.text("tbr"),
                nullAktiv = j.optJSONObject("basalGap")?.let { if (it.isNull("currentZeroTbrActive")) null else it.opt("currentZeroTbrActive") as? Boolean },
                kanalPhase = kanal?.phase,
                smbState = smb?.text("state"),
                stopReason = smb?.text("stopReason"),
            ),
            smbU = smbU,
            quelle = smbU?.let { quelle(j.optJSONObject("exposure")?.text("source")) },
            kanal = kanal,
            bindung = dec?.text("bindingLimit"),
            bedarfU = dec?.zahl("insulinReqU"),
        )
    }

    class Felder(
        val zustand: FuseOverviewSource.CycleState,
        val smbU: Double?,
        val quelle: FuseOverviewSource.SmbSource?,
        val kanal: FuseOverviewSource.ChannelState?,
        val bindung: String?,
        val bedarfU: Double?,
    )

    /** `computeTs` einer Zeile, ohne sie zu parsen - zum Vorsieben beim Warmstart. */
    fun schnellerComputeTs(zeile: String): Long? {
        val i = zeile.indexOf("\"computeTs\":")
        if (i < 0) return null
        var k = i + 12
        while (k < zeile.length && zeile[k] == ' ') k++
        val start = k
        while (k < zeile.length && zeile[k].isDigit()) k++
        return if (k > start) zeile.substring(start, k).toLongOrNull() else null
    }

    private fun JSONObject.text(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.zahl(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }
}
