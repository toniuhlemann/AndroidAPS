package app.aaps.fuse.plugin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.signal.SignalWindow

/**
 * Was auf dem FUSE-Schirm steht — als reiner Text, ohne Android.
 *
 * Warum es diesen Schirm ueberhaupt braucht: solange FUSE nichts anfordert
 * (der Normalfall), ist `isChangeRequested` false, und der Loop-Reiter zeigt
 * "nochangerequested". Die FUSE-Begruendung ist damit im Alltag UNSICHTBAR.
 * Gleichzeitig verschwinden die OpenAPS-Reiter, sobald FUSE aktiv ist.
 *
 * DREI EHRLICHE ZEILEN statt einer. "abgegeben" waere fuer das RT falsch — das
 * RT ist eine ANFORDERUNG; ob etwas ausgefuehrt wird, entscheidet der Loop
 * danach:
 *
 *   berechnet    was der Regler entschieden hat        (decision.smbU)
 *   angefordert  was nach dem Pumpen-Riegel uebrig ist (apsResult.smb)
 *   Gate         warum, falls die beiden auseinandergehen
 *
 * Die zweite Zahl wird NICHT aus der Riegel-Bedingung nachgebaut, sondern aus
 * `lastAPSResult` gelesen — sonst gaebe es zwei Wahrheiten ueber dasselbe.
 */
object FuseScreenModel {

    /**
     * @param apsResult das zuletzt gebaute Ergebnis. Rate und Dauer duerfen
     *   NUR ueber `isTempBasalRequested` gelesen werden: ohne Anforderung
     *   stehen dort die Sentinels -1.0 / -1, und die roh anzuzeigen waere eine
     *   erfundene TBR.
     */
    fun render(outcome: FuseCycleRunner.Outcome?, apsResult: APSResult?, nowMs: Long): String {
        val b = StringBuilder()
        if (outcome == null) {
            // Genau EINE Zeile. Kein Geruest mit Nullen, das wie ein Ergebnis
            // aussieht.
            return "FUSE hat in diesem Prozess noch nicht gerechnet."
        }

        val alterMin = (nowMs - outcome.computeTs) / 60_000
        row(b, "Lauf", "vor $alterMin min")
        row(b, "Gate", outcome.gate.reason)
        outcome.abortReason?.let { row(b, "ABBRUCH", it) }
        b.append('\n')

        // ---- Zustand -------------------------------------------------------
        // Phase und Block sind ZWEI Groessen: die Phase kommt vom Observer, der
        // Block vom Regler. Sie unter einem Namen zu fuehren war der Fehler in
        // der alten RT-Beschriftung.
        row(b, "Phase", outcome.step?.phase?.name ?: "-")
        row(b, "Health", outcome.health?.name ?: "-")
        outcome.step?.healthReasons?.takeIf { it.isNotEmpty() }?.let { row(b, "  Gruende", it.joinToString(",") { r -> r.name }) }
        outcome.step?.safetyReasons?.takeIf { it.isNotEmpty() }?.let { row(b, "  Safety", it.joinToString(",") { r -> r.name }) }
        outcome.step?.eventId?.let { row(b, "Ereignis", it) }
        outcome.step?.transition?.let { row(b, "Uebergang", "${it.type.name} ${it.from.name}->${it.to.name}") }
        b.append('\n')

        // ---- Signal --------------------------------------------------------
        val s = outcome.signal
        if (s == null) row(b, "Signal", "-") else {
            row(b, "q1 / roh", "${f1(s.q1)} / ${f1(s.rawBg)} mg/dl" + if (s.q1Outlier) "  AUSREISSER" else "")
            // rSigned NUR von hier. Aus der Bahn rekonstruiert waere es bereits
            // einen Zerfallsschritt gealtert und damit eine falsche Zahl.
            row(b, "r (18min)", s.rSigned?.let { "${f3(it)} mg/dl/min" } ?: "nicht berechenbar")
            // Danebengestellt, damit die Traegheit des 18-min-Medians am Onset
            // sichtbar ist statt erklaert werden zu muessen.
            row(b, "  UKF-Rate", f3(s.ukfRatePerMin))
            row(b, "  roh 5min", s.rawSlopePerMin?.let { f3(it) } ?: "-")
            row(b, "Punkte", "${s.samplesUsed} von ${s.rawSeriesSize}")
            if (s.boundedBy != SignalWindow.Bound.NONE) row(b, "Fenster ab", s.boundedBy.name)
        }
        outcome.band?.let {
            row(b, "Antrieb", "${f3(it.mean)} / ${f3(it.lower)} (Spreizung ${f3(it.spread)}, ${it.pairCount} Paare)")
        }
        // Die vier Mechanismen mit Namen und Grund - der Schirm soll die
        // Kette zeigen, nicht nur das Ergebnis.
        outcome.discount?.let {
            row(
                b, "Abschlag",
                if (it.termMgdlPerMin > 0.0) "-${f3(it.termMgdlPerMin)} auf die Guardbahn (Bolus-Deckung, Lambda ${f1(it.lambda)})"
                else "0 (keine Bolus-Aktivitaet)"
            )
        }
        outcome.onset?.let { o ->
            row(b, "Onset-Kanal", onsetText(o))
            if (o.mealMarker) row(b, "Marker", "AKTIV")
        }
        outcome.prime?.let { pr -> primeText(pr)?.let { row(b, "Freigabe", it) } }
        b.append('\n')

        // ---- Bahn ----------------------------------------------------------
        val d = outcome.decision
        row(b, "Ziel", outcome.targetMgdl?.let { "${f0(it)} (${outcome.targetSource})" } ?: "-")
        row(b, "predBG", d.predAtReleaseMgdl?.let { f0(it) + subFloor(it) } ?: "-")
        row(b, "minLower", d.minLowerMgdl?.let { f0(it) + subFloor(it) } ?: "-")
        row(b, "minMean", outcome.prediction?.minMeanBg?.let { f0(it) + subFloor(it) } ?: "-")
        b.append('\n')

        // ---- Menge ---------------------------------------------------------
        // "Kontext" ist die LAGEBESCHREIBUNG des Beobachters - sie steuert
        // nichts. Der wirksame SMB-Anteil kommt aus der Rampe; seine
        // Zusammensetzung steht in der SMB-Anteil-Zeile, damit "Kontext
        // CORRECTION, Anteil 0,35" kein Raetsel mehr ist.
        row(b, "Kontext", (d.context?.name ?: "-") + "  (Lage, steuert nicht)")
        row(b, "Block", d.block.name)
        row(b, "Grenze", d.bindingLimit + if (d.restraintBound) "  (gebremst)" else "")
        row(b, "insulinReq", f2(d.insulinReqU) + " U")
        row(b, "IOB", outcome.iobU?.let { f2(it) + " U" } ?: "-")
        outcome.state?.let {
            row(b, "iobTH / maxIOB", "${f2(it.iobThU)} / ${f2(it.maxIobU)} U")
            val rampPct = it.rSignedMgdlPerMin?.let { r ->
                (((r - it.riseRampLowRPerMin) / (it.riseRampHighRPerMin - it.riseRampLowRPerMin))
                    .coerceIn(0.0, 1.0) * 100).toInt()
            }
            row(
                b, "SMB-Anteil",
                f2(it.effectiveSmbRatio) +
                    "  = Basis ${f2(it.smbRatioCorrection)} + Rampe ${rampPct?.toString() ?: "-"} %% Richtung ${f2(it.smbRatioRise)}"
                        .replace("%%", "%")
            )
        }
        d.tail?.let {
            row(
                b, "Schwanz",
                if (it.usable) "${f2(it.headroomU)} U frei (Budget ${f2(it.budgetU)}, IOB@H ${f2(it.existingU)})"
                else "unbrauchbar: ${it.invalidReason}"
            )
            row(b, "  Vermerk", it.completeness)
            if (d.tailCostU > 0.0) row(b, "  Kosten", f2(d.tailCostU) + " U")
        }
        b.append('\n')

        // ---- Was daraus wurde ----------------------------------------------
        row(b, "berechnet", f2(d.smbU) + " U SMB, TBR " + d.tbr.name)
        if (apsResult == null) row(b, "angefordert", "-") else {
            row(b, "angefordert", f2(apsResult.smb) + " U SMB")
            row(
                b, "  TBR",
                if (apsResult.isTempBasalRequested) "${f2(apsResult.rate)} U/h fuer ${apsResult.duration} min"
                else "keine Anforderung"
            )
        }
        return b.toString()
    }

    /** Bahnwerte unter dem physiologischen Boden sind keine Blutzuckerwerte
     *  mehr, sondern "tief unter dem Boden" - die Sperre ist richtig, die
     *  Zahl aber nur noch ein Mass fuer den Ueberschuss. */
    private fun subFloor(v: Double) = if (v < 20.0) "  (unter Boden - sperrt)" else ""

    private fun onsetText(o: app.aaps.fuse.core.controller.OnsetChannel.Result): String = when (o.reason) {
        "OPEN"            -> "OFFEN - Antrieb ${f2(o.driveMgdlPerMin ?: Double.NaN)}, Rest-Huelle ${f2(o.remainingU)} U"
        "OPEN_MARKER"     -> "OFFEN (Marker) - Antrieb ${f2(o.driveMgdlPerMin ?: Double.NaN)}, Rest ${f2(o.remainingU)} U"
        "R_CONFIRMED"     -> "zu - r bestaetigt, Rampe traegt"
        "UKF_BELOW_THR"   -> "zu - kein echter Anstieg"
        "TOO_FEW_SAMPLES" -> "zu - sammelt Werte"
        "GAP"             -> "zu - Messluecke"
        "OUTLIER"         -> "zu - Ausreisser"
        "ENVELOPE_SPENT"  -> "zu - Huelle verbraucht"
        "DISABLED"        -> "aus"
        else              -> "zu - ${o.reason}"
    }

    private fun primeText(p: app.aaps.fuse.core.controller.PrimeRelease.Plan): String? = when (p.reason) {
        "NO_MARKER", "DISABLED" -> null // ohne Marker keine Zeile - kein Geruest
        "PRIME"                 -> "laeuft - ${f2(p.floorU)} U diese Minute, Rest ${f2(p.remainingU)} U"
        "WINDOW_OVER"           -> "zu - 15-min-Fenster vorbei"
        "CLEARANCE"             -> "zu - Bahn zu nah am Boden"
        "ENVELOPE_SPENT"        -> "zu - Huelle verbraucht"
        else                    -> "zu - ${p.reason}"
    }

    private fun row(b: StringBuilder, label: String, value: String) {
        b.append(label.padEnd(16)).append(value).append('\n')
    }

    private fun f0(d: Double) = fmt(d, 0)
    private fun f1(d: Double) = fmt(d, 1)
    private fun f2(d: Double) = fmt(d, 2)
    private fun f3(d: Double) = fmt(d, 3)
    /**
     * `%.2f` auf `Double.MAX_VALUE` ergibt ueber 300 Ziffern und sprengt den
     * Schirm — genau so ist der fehlende IOB-Deckel beim ersten Geraetelauf
     * aufgefallen. Die Grenze ist bewusst niedrig: keine Groesse in diesem
     * Regler hat je einen sinnvollen Wert ueber 10.000, und "unbegrenzt" ist
     * eine ehrlichere Anzeige als eine Zahl, die niemand liest.
     */
    private const val ABSURD = 10_000.0

    private fun fmt(d: Double, n: Int) = when {
        !d.isFinite()          -> "?"
        d >= ABSURD            -> "unbegrenzt"
        d <= -ABSURD           -> "-unbegrenzt"
        else                   -> String.format(java.util.Locale.ROOT, "%.${n}f", d)
    }
}
