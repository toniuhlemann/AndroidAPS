package app.aaps.fuse.plugin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.PrimeRelease
import app.aaps.fuse.core.observer.Health
import java.util.Locale

/**
 * Die kompakte Betriebssicht des FUSE-Reiters, ohne Android-Abhaengigkeit.
 *
 * [FuseScreenModel] bleibt die vollstaendige technische Belegschicht. Diese
 * Klasse beantwortet dagegen die Fragen, die oben auf dem Schirm in wenigen
 * Sekunden erkennbar sein muessen: laeuft FUSE, was fordert es an, warum,
 * wie weit ist die Marker-Huelle verbraucht und welche harte Sperre gilt.
 *
 * Die Werte werden nicht nachgerechnet, sondern aus demselben Outcome,
 * APSResult, Marker- und Ledgerzustand gelesen wie die Detailansicht.
 */
object FuseDashboardModel {

    data class ProfileInfo(val scheduledBasalUPerH: Double?)

    data class View(
        val status: String,
        val statusDetail: String,
        val action: String,
        val decisionReason: String,
        val marker: String,
        val limits: String,
        val profile: String,
        val hardStops: String,
    )

    fun build(
        outcome: FuseCycleRunner.Outcome?,
        apsResult: APSResult?,
        nowMs: Long,
        marker: FuseScreenModel.MarkerInfo?,
        ledger: FuseScreenModel.LedgerInfo?,
        profile: ProfileInfo?,
    ): View {
        if (outcome == null) return View(
            status = "WARTET AUF ERSTEN ZYKLUS",
            statusDetail = "Noch kein FUSE-Ergebnis in diesem Prozess",
            action = "Keine aktuelle Entscheidung",
            decisionReason = "-",
            marker = markerText(null, nowMs, marker),
            limits = "IOB und Spielraeume noch unbekannt",
            profile = profileText(null, profile),
            hardStops = "Pumpengate und Ledger noch nicht bewertet",
        )

        val alterMin = ((nowMs - outcome.computeTs).coerceAtLeast(0L) / 60_000L).toInt()
        val status = when {
            ledger?.hold == true         -> "HOLD"
            !outcome.gate.allowed        -> "PUMPENGATE GESPERRT"
            outcome.abortReason != null  -> "ABBRUCH"
            outcome.health == Health.WARMUP -> "AUFWAERMEN"
            outcome.health == Health.DEGRADED -> "EINGESCHRAENKT"
            else                         -> "READY"
        }
        val phase = outcome.step?.phase?.name ?: outcome.state?.phase?.name ?: "-"
        val health = outcome.health?.name ?: "-"
        val statusDetail = "vor $alterMin min  |  Phase $phase  |  Health $health"

        val berechnet = outcome.decision.smbU.coerceAtLeast(0.0)
        val angefordert = apsResult?.smb?.coerceAtLeast(0.0)
        val smb = if (angefordert == null) "SMB ${u(berechnet)} berechnet"
        else "SMB ${u(berechnet)} berechnet / ${u(angefordert)} angefordert"
        val tbr = when {
            apsResult?.isTempBasalRequested == true -> "TBR ${f2(apsResult.rate)} U/h fuer ${apsResult.duration} min"
            outcome.tbr != null                     -> "TBR ${f2(outcome.tbr.rateUPerH)} U/h fuer ${outcome.tbr.durationMin} min berechnet"
            else                                    -> "keine neue TBR"
        }
        val action = "$smb  |  $tbr"

        val d = outcome.decision
        val decisionReason = when {
            outcome.abortReason != null -> outcome.abortReason
            ledger?.hold == true        -> "Ledger: ${ledger.holdReason ?: "unbekannter Hold"}"
            d.block != FuseController.Block.NONE -> "${d.block.name}  |  ${d.bindingLimit}"
            berechnet > 0.0             -> "Freigegeben  |  ${d.bindingLimit}"
            else                        -> "Kein zusaetzlicher Bedarf  |  ${d.bindingLimit}"
        }

        val hard = mutableListOf<String>()
        if (!outcome.gate.allowed) hard += "Pumpengate ${outcome.gate.reason}"
        if (ledger?.hold == true) hard += "Ledger ${ledger.holdReason ?: "HOLD"}"
        if (outcome.abortReason != null) hard += "Eingabe/Zyklus ${outcome.abortReason}"
        if (d.block == FuseController.Block.PUMP_BUSY) hard += "Pumpe belegt"
        val hardStops = if (hard.isEmpty())
            "Keine harte Systemsperre  |  Gate ${outcome.gate.reason}  |  Ledger ${if (ledger == null) "?" else "frei"}"
        else hard.joinToString("  |  ")

        return View(
            status = status,
            statusDetail = statusDetail,
            action = action,
            decisionReason = decisionReason,
            marker = markerText(outcome, nowMs, marker),
            limits = limitsText(outcome, ledger),
            profile = profileText(outcome, profile),
            hardStops = hardStops,
        )
    }

    private fun markerText(outcome: FuseCycleRunner.Outcome?, nowMs: Long, marker: FuseScreenModel.MarkerInfo?): String {
        if (marker == null) return "Markerzustand unbekannt"
        if (marker.armedTs <= 0L) return "Nicht aktiv  |  Huelle ${u(marker.envelopeU)} konfiguriert"

        val elapsed = ((nowMs - marker.armedTs).coerceAtLeast(0L) / 60_000L).toInt()
        if (elapsed >= marker.windowMin) return "Abgelaufen seit ${elapsed - marker.windowMin} min  |  Huelle ${u(marker.envelopeU)} konfiguriert"
        val rest = outcome?.prime?.remainingU?.coerceIn(0.0, marker.envelopeU)
        val published = rest?.let { (marker.envelopeU - it).coerceAtLeast(0.0) }
            ?: outcome?.mealStats?.totalU?.coerceAtLeast(0.0)
        val available = published?.let { (marker.envelopeU - it).coerceAtLeast(0.0) }
        val amount = if (published == null || available == null) "Huelle ${u(marker.envelopeU)}"
        else "Huelle ${u(marker.envelopeU)}  |  publiziert ${u(published)}  |  verfuegbar ${u(available)}"
        val context = "$elapsed/${marker.windowMin} min Kontext"
        val release = "${elapsed.coerceAtMost(PrimeRelease.WINDOW_MIN)}/${PrimeRelease.WINDOW_MIN} min Freigabe"
        val wall = "${elapsed.coerceAtMost(PrimeRelease.WALL_CEILING_MIN)}/${PrimeRelease.WALL_CEILING_MIN} min Wandzeit"
        return "AKTIV seit $elapsed min  |  $amount\n$release  |  $wall  |  $context"
    }

    private fun limitsText(outcome: FuseCycleRunner.Outcome, ledger: FuseScreenModel.LedgerInfo?): String {
        val state = outcome.state
        val iob = outcome.iobU
        val iobTh = state?.iobThU ?: outcome.iobThU
        val maxIob = state?.maxIobU ?: outcome.maxIobU
        val capIob = state?.capIobU
        val transport = ledger?.transportCommitmentU ?: 0.0
        val iobText = iob?.let { "IOB ${u(it)}" } ?: "IOB -"
        if (iobTh == null && maxIob == null) return "$iobText  |  Spielraeume unbekannt"
        if (capIob == null) return "$iobText  |  iobTH ${iobTh?.let(::u) ?: "-"}  |  maxIOB ${maxIob?.let(::u) ?: "-"}"
        val fastRest = iobTh?.minus(capIob)?.minus(transport)
        val maxRest = maxIob?.minus(capIob)?.minus(transport)
        return "$iobText  |  cap ${u(capIob)}  |  Transport ${u(transport)}\n" +
            "iobTH ${iobTh?.let(::u) ?: "-"} (Rest ${fastRest?.let(::u) ?: "-"})  |  " +
            "maxIOB ${maxIob?.let(::u) ?: "-"} (Rest ${maxRest?.let(::u) ?: "-"})"
    }

    private fun profileText(outcome: FuseCycleRunner.Outcome?, profile: ProfileInfo?): String {
        val target = outcome?.targetMgdl?.let { "Ziel ${f0(it)} mg/dl (${outcome.targetSource ?: "?"})" } ?: "Ziel -"
        val isf = outcome?.isfMgdlPerU?.let { "ISF ${f0(it)} mg/dl/U" } ?: "ISF -"
        val basal = profile?.scheduledBasalUPerH?.let { "Basal ${f2(it)} U/h" } ?: "Basal -"
        return "$target  |  $isf  |  $basal"
    }

    private fun f0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun u(v: Double): String = "${f2(v)} U"
}
