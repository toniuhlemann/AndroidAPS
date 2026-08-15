package app.aaps.fuse.plugin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.NightWindow
import app.aaps.fuse.core.controller.PrimeRelease
import app.aaps.fuse.core.observer.Health
import java.util.Locale

/**
 * Die kompakte Betriebssicht des FUSE-Reiters, ohne Android-Abhaengigkeit.
 *
 * [FuseScreenModel] bleibt die vollstaendige technische Belegschicht. Diese
 * Klasse beantwortet die Fragen, die oben auf dem Schirm in wenigen Sekunden
 * erkennbar sein muessen - NACH dem Umbau vom 15.08. (Toni: "zu viele
 * IOB-Werte uebereinander") sind das:
 *
 *   laeuft FUSE - was tut es gerade und warum - was sagen Mahlzeit,
 *   Evidenz-Episode und Totbaender - wieviel Spielraum bleibt.
 *
 * Die fuenf IOB-Einzelzeilen sind zu EINER Zeile verdichtet; Kappen-IOB
 * wohnt nur noch in den technischen Details, Transport erscheint NUR, wenn
 * tatsaechlich etwas offen ist (Null-Info verstecken, Alarm betonen - die
 * Widget-Layoutregel). NEU oben: die Evidenz-Episode (Phase, Alter, verbucht,
 * Bestand, Kredit) und der Totband-Status inklusive Entwaffnung durch
 * Kredit/Marker - exakt ueber [NightWindow.effectiveDeadbandMgdl] berechnet,
 * damit Anzeige und Regler nie auseinanderlaufen.
 *
 * Die Werte werden nicht nachgerechnet, sondern aus demselben Outcome,
 * APSResult, Marker- und Ledgerzustand gelesen wie die Detailansicht.
 */
object FuseDashboardModel {

    data class ProfileInfo(val scheduledBasalUPerH: Double?)

    data class View(
        val status: String,
        val statusDetail: String,
        /** Eine Zeile: r, Modus, effektiver SMB-Anteil. */
        val signal: String,
        /** Eine Zeile: SMB berechnet -> angefordert, TBR. */
        val action: String,
        /** Menschenlesbarer Grund; das rohe Token steht in Klammern. */
        val decisionReason: String,
        /** Leise Fusszeile (Gate + Ledger) - oder die laute Sperrliste. */
        val gate: String,
        val marker: String,
        /** Evidenz-Episode: Phase, Alter/Deckel, verbucht, Bestand, Kredit. */
        val evidence: String,
        /** Totband-/Prime-/Onset-Status; null = nichts aktiv -> Zeile weg. */
        val windows: String?,
        /** IOB verdichtet: netto (Bolus/Basal in Klammern). */
        val iobLine: String,
        /** Nur bei offener Transportmenge - sonst null -> Zeile weg. */
        val transport: String?,
        /** Verfuegbarer Spielraum + beide Grenzen in einer Zeile. */
        val limits: String,
        val profile: String,
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
            signal = "Störung r -  |  Modus -  |  SMB-Anteil -",
            action = "Keine aktuelle Entscheidung",
            decisionReason = "-",
            gate = "Pumpengate und Ledger noch nicht bewertet",
            marker = markerText(null, nowMs, marker),
            evidence = "noch nicht bewertet",
            windows = null,
            iobLine = "IOB -",
            transport = null,
            limits = "-",
            profile = profileText(null, profile),
        )

        val alterMin = ((nowMs - outcome.computeTs).coerceAtLeast(0L) / 60_000L).toInt()
        val status = when {
            ledger?.hold == true              -> "HOLD"
            !outcome.gate.allowed             -> "PUMPENGATE GESPERRT"
            outcome.abortReason != null       -> "ABBRUCH"
            outcome.health == Health.WARMUP   -> "AUFWAERMEN"
            outcome.health == Health.DEGRADED -> "EINGESCHRAENKT"
            else                              -> "READY"
        }
        val phase = outcome.step?.phase?.name ?: outcome.state?.phase?.name ?: "-"
        val health = outcome.health?.name ?: "-"
        val statusDetail = "vor $alterMin min  |  Phase $phase  |  Health $health"

        val berechnet = outcome.decision.smbU.coerceAtLeast(0.0)
        val angefordert = apsResult?.smb?.coerceAtLeast(0.0)
        val smb = if (angefordert == null) "SMB ${u(berechnet)}"
        else "SMB ${u(berechnet)} -> ${u(angefordert)}"
        val tbr = when {
            apsResult?.isTempBasalRequested == true -> "TBR ${f2(apsResult.rate)} U/h ${apsResult.duration} min"
            outcome.tbr != null                     -> "TBR ${f2(outcome.tbr.rateUPerH)} U/h berechnet"
            else                                    -> "keine neue TBR"
        }
        val action = "$smb  |  $tbr"

        val d = outcome.decision
        val decisionReason = when {
            outcome.abortReason != null -> "Abbruch: ${outcome.abortReason}"
            ledger?.hold == true        -> "Ledger-Hold: ${ledger.holdReason ?: "unbekannt"}"
            d.block != FuseController.Block.NONE -> "${blockLabel(d.block)}  (${d.bindingLimit})"
            berechnet > 0.0             -> "freigegeben  |  Grenze ${limitLabel(d.bindingLimit)}"
            else                        -> "kein zusaetzlicher Bedarf  (${d.bindingLimit})"
        }

        val hard = mutableListOf<String>()
        if (!outcome.gate.allowed) hard += "Pumpengate ${outcome.gate.reason}"
        if (ledger?.hold == true) hard += "Ledger ${ledger.holdReason ?: "HOLD"}"
        if (outcome.abortReason != null) hard += "Eingabe/Zyklus ${outcome.abortReason}"
        if (d.block == FuseController.Block.PUMP_BUSY) hard += "Pumpe belegt"
        val gate = if (hard.isEmpty())
            "Gate ${outcome.gate.reason}  |  Ledger ${if (ledger == null) "?" else "frei"}"
        else hard.joinToString("  |  ")

        return View(
            status = status,
            statusDetail = statusDetail,
            signal = signalText(outcome),
            action = action,
            decisionReason = decisionReason,
            gate = gate,
            marker = markerText(outcome, nowMs, marker),
            evidence = evidenceText(outcome),
            windows = windowsText(outcome, nowMs, marker),
            iobLine = iobLine(outcome),
            transport = ledger?.transportCommitmentU?.takeIf { it > 0.0 }
                ?.let { "Transport offen ${u(it)} - wird vom Spielraum abgezogen" },
            limits = limitsText(outcome, ledger),
            profile = profileText(outcome, profile),
        )
    }

    private fun signalText(outcome: FuseCycleRunner.Outcome): String {
        val state = outcome.state
        val r = outcome.signal?.rSigned ?: state?.rSignedMgdlPerMin
        val rText = r?.takeIf { it.isFinite() }?.let { signed2(it) } ?: "-"
        if (state == null) return "Störung r $rText  |  Modus -  |  SMB-Anteil -"
        val mode = when {
            state.reboundWindow -> "Rebound"
            state.mealWindow    -> "Anstieg"
            else                -> "Korrektur"
        }
        return "Störung r $rText mg/dl/min  |  $mode  |  SMB-Anteil ${f2(state.effectiveSmbRatio)}"
    }

    /**
     * Die Evidenz-Episode - das Herz der Mahlzeitenregeln, bislang nur in
     * den technischen Details (und dort nur in den Stoerfaellen). Phase,
     * Alter gegen den 360-Deckel, bezahlte Menge, Bestand und der Kredit
     * DIESES Zyklus in zwei kompakten Zeilen.
     */
    private fun evidenceText(outcome: FuseCycleRunner.Outcome): String {
        if (outcome.evidenceEpisodeId <= 0L) {
            val grund = outcome.evidenceEpisodeDenial?.let { denialLabel(it) }
            return if (grund == null) "keine Episode" else "keine Episode  |  $grund"
        }
        val alter = outcome.evidenceEpisodeMin?.let { "$it/${outcome.evidenceEpisodeCapMin} min" } ?: "Alter ?"
        val kopf = "Episode ${phaseLabel(outcome.evidencePhase)}  |  $alter  |  verbucht ${u(outcome.evidenceCommittedU)}"
        val bestand = outcome.evidenceStockMgdl?.let { "Bestand ${f0(it)} mg/dl" } ?: "Bestand ?"
        val kredit = when {
            outcome.evidenceCreditRevoked -> "Kredit WIDERRUFEN"
            (outcome.evidenceCreditMgdlPerMin ?: 0.0) > 0.0 ->
                "Kredit +${f2(outcome.evidenceCreditMgdlPerMin!!)}/min"
            else -> "kein Kredit" + (outcome.evidenceReason?.let { " ($it)" } ?: "")
        }
        return "$kopf\n$bestand  |  $kredit"
    }

    /**
     * Totband-, Prime- und Onset-Status. Der Totband-Zustand wird ueber
     * DIESELBE Funktion berechnet wie im Regler ([NightWindow]), mit
     * demselben Kreditsignal (`creditMgdlPerMin > 0`) - eine zweite,
     * "aehnliche" Formel in der Anzeige war der Fehlerweg des 15.08.
     * (Verdrahtung fehlte, Anzeige haette "entwaffnet" gelogen).
     * null = nichts davon aktiv -> die Zeile verschwindet ganz.
     */
    private fun windowsText(outcome: FuseCycleRunner.Outcome, nowMs: Long, marker: FuseScreenModel.MarkerInfo?): String? {
        val state = outcome.state
        val teile = mutableListOf<String>()
        if (state != null && (state.nightWindow || state.reboundWindow)) {
            val creditActive = (outcome.evidenceCreditMgdlPerMin ?: 0.0) > 0.0
            val effektiv = NightWindow.effectiveDeadbandMgdl(
                reboundWindow = state.reboundWindow,
                reboundDeadbandMgdl = state.reboundDeadbandMgdl,
                isNight = state.nightWindow,
                nightDeadbandMgdl = state.nightDeadbandMgdl,
                markerBoost = state.markerBoost,
                evidenceCreditActive = creditActive,
            )
            val fenster = listOfNotNull(
                "Nacht".takeIf { state.nightWindow },
                "Rebound".takeIf { state.reboundWindow },
            ).joinToString("+")
            teile += if (effektiv > 0.0)
                "$fenster-Totband scharf bis ${f0(state.targetMgdl + effektiv)}"
            else
                "$fenster-Totband entwaffnet (${if (creditActive) "Kredit" else "Marker"})"
        }
        val markerAktiv = marker != null && marker.armedTs > 0L &&
            (nowMs - marker.armedTs) / 60_000L < marker.windowMin
        if (markerAktiv) outcome.prime?.let { p ->
            teile += "Prime ${u(p.remainingU.coerceAtLeast(0.0))} offen"
        }
        outcome.onset?.takeIf { it.active }?.let { o ->
            teile += "Onset offen ${u(o.remainingU.coerceAtLeast(0.0))}"
        }
        return teile.takeIf { it.isNotEmpty() }?.joinToString("  |  ")
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
        val release = "${elapsed.coerceAtMost(PrimeRelease.WINDOW_MIN)}/${PrimeRelease.WINDOW_MIN} min Freigabe"
        return "AKTIV seit $elapsed/${marker.windowMin} min  |  $amount\n$release"
    }

    private fun iobLine(outcome: FuseCycleRunner.Outcome): String {
        val state = outcome.state
        val netto = outcome.iobU?.let(::u) ?: "-"
        val teile = listOfNotNull(
            state?.bolusIobU?.let { "Bolus ${f2(it)}" },
            state?.basalIobU?.let { "Basal ${f2(it)}" },
        )
        return if (teile.isEmpty()) "IOB $netto" else "IOB $netto  (${teile.joinToString("  |  ")})"
    }

    private fun limitsText(outcome: FuseCycleRunner.Outcome, ledger: FuseScreenModel.LedgerInfo?): String {
        val state = outcome.state
        val iobTh = state?.iobThU ?: outcome.iobThU
        val maxIob = state?.maxIobU ?: outcome.maxIobU
        val capIob = state?.capIobU
        val transport = ledger?.transportCommitmentU ?: 0.0
        val fastRest = capIob?.let { cap -> iobTh?.minus(cap)?.minus(transport) }
        val maxRest = capIob?.let { cap -> maxIob?.minus(cap)?.minus(transport) }
        val headroom = when {
            fastRest != null && maxRest != null && kotlin.math.abs(fastRest - maxRest) < 0.005 ->
                "Verfuegbar ${u(minOf(fastRest, maxRest))} (beide Grenzen)"
            fastRest != null && maxRest != null -> "Verfuegbar iobTH ${u(fastRest)}  |  maxIOB ${u(maxRest)}"
            fastRest != null -> "Verfuegbar iobTH ${u(fastRest)}"
            maxRest != null -> "Verfuegbar maxIOB ${u(maxRest)}"
            else -> "Verfuegbar -"
        }
        val grenzen = "iobTH ${iobTh?.let(::f2) ?: "-"}  |  maxIOB ${maxIob?.let(::f2) ?: "-"} U"
        return "$headroom\n$grenzen"
    }

    private fun profileText(outcome: FuseCycleRunner.Outcome?, profile: ProfileInfo?): String {
        val target = outcome?.targetMgdl?.let { "Ziel ${f0(it)} mg/dl (${outcome.targetSource ?: "?"})" } ?: "Ziel -"
        val isf = outcome?.isfMgdlPerU?.let { "ISF ${f0(it)} mg/dl/U" } ?: "ISF -"
        val basal = profile?.scheduledBasalUPerH?.let { "Basal ${f2(it)} U/h" } ?: "Basal -"
        return "$target  |  $isf  |  $basal"
    }

    private fun blockLabel(block: FuseController.Block): String = when (block) {
        FuseController.Block.GUARD_FLOOR      -> "Guard-Boden"
        FuseController.Block.NO_DEMAND        -> "kein Bedarf"
        FuseController.Block.TAIL             -> "Schwanzhaftung"
        FuseController.Block.HEALTH_NOT_READY -> "Signal nicht bereit"
        FuseController.Block.SAFETY_HOLD      -> "Sicherheits-Hold"
        FuseController.Block.PUMP_BUSY        -> "Pumpe belegt"
        FuseController.Block.HORIZON_MISSING  -> "keine Prognose"
        FuseController.Block.MAX_IOB_REACHED  -> "maxIOB erreicht"
        FuseController.Block.IOB_TH_REACHED   -> "iobTH erreicht"
        else                                  -> block.name
    }

    private fun limitLabel(bindingLimit: String): String = when {
        bindingLimit.startsWith("nightDeadband")   -> "Nacht-Totband"
        bindingLimit.startsWith("reboundDeadband") -> "Rebound-Totband"
        bindingLimit.startsWith("smbRatio")        -> "SMB-Anteil ($bindingLimit)"
        bindingLimit.startsWith("iobThHeadroom")   -> "iobTH-Spielraum"
        bindingLimit.startsWith("maxIobHeadroom")  -> "maxIOB-Spielraum"
        bindingLimit.startsWith("maxSmb")          -> "max. Einzel-SMB"
        bindingLimit.startsWith("tailHeadroom")    -> "Schwanz-Spielraum"
        bindingLimit.startsWith("onsetEnvelope")   -> "Onset-Huelle"
        else                                       -> bindingLimit
    }

    private fun phaseLabel(phase: String?): String = when (phase) {
        "ACTIVE"       -> "AKTIV"
        "PENDING_SEAL" -> "VERSIEGELT GLEICH"
        "DORMANT"      -> "RUHT"
        "SUSPENDED"    -> "AUSGESETZT"
        "EXPIRED"      -> "ABGELAUFEN"
        "UNKNOWN"      -> "UNKLAR"
        null, "", "NONE" -> "?"
        else           -> phase
    }

    private fun denialLabel(denial: String): String = when (denial) {
        "NO_MARKER"                -> "kein Marker"
        "MARKER_STALE"             -> "Marker zu alt"
        "MARKER_ALREADY_CONSUMED"  -> "Marker bereits verbraucht"
        "MARKER_IN_FUTURE"         -> "Marker-Zeit in der Zukunft"
        "MARKER_CLOCK_ROLLBACK"    -> "Uhrenruecksprung"
        "MARKER_EVENT_NOT_DURABLE" -> "Druck nicht beobachtet (Neustart?)"
        else                       -> denial
    }

    private fun f0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun signed2(v: Double): String = String.format(Locale.US, "%+.2f", v)
    private fun u(v: Double): String = "${f2(v)} U"
}
