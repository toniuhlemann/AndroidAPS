package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.fuse.core.controller.ExpectationAccount
import app.aaps.fuse.core.controller.FuseController
import org.json.JSONObject
import kotlin.math.min

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
    insulinReqFromAccount = f.bedarfVomKonto,
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

    /**
     * DER RINGPUNKT EINES LIVE-ZYKLUS - aus dem ENDGUELTIGEN Ausgang und der Menge, die AAPS bekam. Eine Funktion fuer den
     * Graph-Ring und fuer den Chip auf dem Hauptbildschirm ([FuseChipStand]): beide zeigen denselben Zyklus aus denselben
     * Werten. Ein paar Felder, keine Ein-/Ausgabe.
     */
    fun punkt(o: FuseCycleRunner.Outcome, publizierteSmbU: Double?): FuseOverviewSource.Point {
        val sig = o.signal
        return FuseOverviewSource.Point(
            timestamp = o.sourceTs ?: o.computeTs,
            driveMgdlPerMin = sig?.rSigned?.takeIf { it.isFinite() },
            fastDriveMgdlPerMin = sig?.let { (it.ukfRatePerMin + it.activityAtAnchor * it.isfAtAnchor).takeIf { v -> v.isFinite() } },
            guardMarginMgdl = FuseGraphMargin.guardMarginMgdl(o.decision.minLowerMgdl, o.policy?.guardFloorMgdl ?: 70.0),
            // Schwanz-Kante in mg/dl - Regeln s. FuseGraphMargin
            // (der unphysiologische Ausgang hat KEINEN ISF-Nenner,
            // sperrt aber; er gehoert auf den unteren Anschlag).
            tailMarginMgdl = o.decision.tail?.let { t ->
                FuseGraphMargin.tailMarginMgdl(t.headroomU, t.isfTailMgdlPerU, t.invalidReason)
            },
            // Konto: dieselben Regeln wie im Warmstart aus dem Trail (FuseGraphMargin).
            mealWindow = o.erwartungskonto?.let { it.gilt || it.rueckfall != null } == true,
            expectedMealDriveMgdlPerMin = o.erwartungskonto?.takeIf { it.gilt }
                ?.zyklus?.eval?.early?.expectedDriveMgdlPerMin?.takeIf { it.isFinite() },
            guardWithMealMarginMgdl = o.erwartungskonto?.fruehUntenMitMahlzeitMgdl
                ?.let { FuseGraphMargin.guardMarginMgdl(it, o.policy?.guardFloorMgdl ?: 70.0) },
            tailWithMealMarginMgdl = o.erwartungskonto?.fruehSchwanzMitMahlzeit?.let { t ->
                FuseGraphMargin.tailMarginMgdl(t.headroomU, t.isfTailMgdlPerU, t.invalidReason)
            },
            accountStatus = o.erwartungskonto?.let {
                FuseGraphMargin.accountStatus(it.gilt, it.rueckfall, it.zyklus?.eval?.status)
            },
        ).mitHauptgraph(felder(o, publizierteSmbU))
    }

    /** Die Hauptgraph-Felder eines Live-Zyklus - aus dem endgueltigen Ausgang und der Menge, die AAPS bekam. */
    fun felder(o: FuseCycleRunner.Outcome, publizierteSmbU: Double?): Felder {
        val smbU = publizierteSmbU?.takeIf { it.isFinite() && it > 0.0 }
        val reglerBedarf = o.decision.insulinReqU?.takeIf { it.isFinite() }
        val kontoBedarf = if (reglerBedarf == null) kontoBedarfU(o) else null
        return Felder(
            zustand = zustand(o.decision.block.name, o.decision.tbr.name, o.currentZeroTbrActive, o.slowChannel?.phase, o.smbState, o.smbStopReason),
            smbU = smbU,
            quelle = smbU?.let { quelle(o.exposureFinalSource) },
            kanal = o.slowChannel?.let { kanal(it) },
            bindung = o.decision.bindingLimit,
            bedarfU = reglerBedarf ?: kontoBedarf,
            bedarfVomKonto = kontoBedarf != null,
        )
    }

    /**
     * DER BEDARF DES KONTOS in diesem Zyklus [U] - dieselbe Groesse, die der Regler in einem Kontozyklus ohne Stopp als
     * Bedarf ausweist (`FuseController.decide`, `kontoReq`): die lieferbare Kontomenge ueber dieselben beiden
     * Kernfunktionen wie dort und im Trail (`deliverableU`), hoechstens die Grenze der gemeinsamen Bilanz der
     * Rechner-Mahlzeit. `null` ohne geltendes Konto - im Rueckfall gibt es keinen Kontobedarf.
     *
     * Er steht im Ringpunkt nur dort, wo der Regler KEINEN Bedarf gerechnet hat (Toni 15.11.2026: "Bedarf vom Konto
     * zeigen"): stoppt der Normalweg am Guard oder am Schwanz, kommt die Portion ueber die Marker-Freigabe aus dem
     * Konto, und Chip wie Halte-Pille stuenden genau in der Phase leer, in der das Konto liefert.
     */
    fun kontoBedarfU(o: FuseCycleRunner.Outcome): Double? {
        val eval = o.erwartungskonto?.takeIf { it.gilt }?.zyklus?.eval ?: return null
        val roh = FuseController.accountDemandU(ExpectationAccount.deliverableU(eval)) ?: return null
        val grenze = o.mealBalance?.smbCapU?.takeIf { it.isFinite() }
        return if (grenze == null) roh else min(roh, grenze)
    }

    /**
     * Derselbe Kontobedarf aus einer Trail-Zeile (Warmstart): `expectationAccount.deliverableU` ist dieselbe Zahl, die
     * [kontoBedarfU] live rechnet, `mealBalance.smbCapU` dieselbe Grenze. Das Feld steht nur in Zeilen mit einer
     * Auswertung des Kontos; ein JSON-null darin heisst "nicht berechenbar" und zaehlt wie im Regler als 0. Aeltere
     * Zeilen ohne das Feld liefern keinen Kontobedarf.
     */
    fun kontoBedarfU(j: JSONObject): Double? {
        val konto = j.optJSONObject("expectationAccount")?.takeIf { it.optBoolean("applies", false) } ?: return null
        if (!konto.has("deliverableU")) return null
        val roh = konto.zahl("deliverableU") ?: 0.0
        val grenze = j.optJSONObject("mealBalance")?.zahl("smbCapU")
        return if (grenze == null) roh else min(roh, grenze)
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
        val reglerBedarf = dec?.zahl("insulinReqU")
        val kontoBedarf = if (reglerBedarf == null) kontoBedarfU(j) else null
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
            bedarfU = reglerBedarf ?: kontoBedarf,
            bedarfVomKonto = kontoBedarf != null,
        )
    }

    class Felder(
        val zustand: FuseOverviewSource.CycleState,
        val smbU: Double?,
        val quelle: FuseOverviewSource.SmbSource?,
        val kanal: FuseOverviewSource.ChannelState?,
        val bindung: String?,
        /** Der Bedarf des Reglers; hat der keinen gerechnet, der des Kontos ([kontoBedarfU]). */
        val bedarfU: Double?,
        /** [bedarfU] ist der Bedarf des Kontos, nicht der des Reglers. */
        val bedarfVomKonto: Boolean,
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
