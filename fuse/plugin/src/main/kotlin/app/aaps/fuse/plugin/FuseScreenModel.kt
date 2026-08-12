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
    /** Marker-Zustand fuer Timer-Zeile - kommt vom Fragment (Plugin-Prefs),
     *  nicht aus dem Outcome: der Timer soll auch zwischen Zyklen laufen. */
    data class MarkerInfo(val armedTs: Long, val windowMin: Int, val envelopeU: Double)

    /**
     * Der LEDGER-Zustand - eine EIGENE Groesse, nicht Teil von "Health".
     *
     * Health ist der Zustand des BEOBACHTERS. Der kann tadellos READY sein,
     * waehrend der Ledger die Aktuation haelt und FUSE nichts mehr abgibt -
     * genau so stand es am 10.08.2026 auf dem Schirm: `Health READY` oben,
     * `Kandidat LEDGER_HOLD - erlaubt 0.00 U` zwanzig Zeilen tiefer, und
     * dazwischen nichts, was die Kopfzeile widerlegt haette.
     *
     * Beides unter einer Ueberschrift zu fuehren waere aber genau der Fehler,
     * vor dem die alte RT-Beschriftung warnt (s. "Phase und Block sind ZWEI
     * Groessen"). Also eine zweite Zeile, und die schreit.
     */
    data class LedgerInfo(
        /**
         * Der ZUSAMMENGESETZTE Wert aus `LedgerView.hold`, nicht
         * `state.holdActuation` (Audit-Befund S1): gesperrt wird ueber vier
         * Quellen, und drei davon stoppten die Abgabe lautlos, waehrend hier
         * "frei" stand.
         */
        val hold: Boolean,
        /** Welche der vier Quellen - ohne sie ist ein Hold nicht
         *  einzuordnen, und drei von ihnen haben gar keine Fehlerliste. */
        val holdReason: String?,
        val holdGeneration: Long,
        val activeErrors: Map<String, Int>,
        val openEntries: Int,
        val grossLiabilityU: Double,
        /** Offene, noch nicht im IOB belegte Transportmenge. Sie wird bei
         *  den wirksamen iobTH-/maxIOB-Spielraeumen ebenfalls abgezogen. */
        val transportCommitmentU: Double = 0.0,
        val lastRepairTs: Long?,
    )

    fun render(
        outcome: FuseCycleRunner.Outcome?,
        apsResult: APSResult?,
        nowMs: Long,
        marker: MarkerInfo? = null,
        ledger: LedgerInfo? = null,
    ): String {
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

        // ---- LEDGER: ganz oben, weil er die Abgabe ganz zumachen kann -------
        //
        // Im Hold ist das die wichtigste Zeile des Schirms: FUSE rechnet
        // weiter, zeigt insulinReq, Bahn und Ratio - und gibt NICHTS ab. Ohne
        // diese Zeile liest sich der Schirm wie ein gesunder Regler, der
        // gerade nichts fuer noetig haelt.
        //
        // Ausserhalb des Holds bleibt sie trotzdem stehen, nur leise: "keine
        // Zeile" waere von "noch nie geschaut" nicht zu unterscheiden.
        ledger?.let { l ->
            if (l.hold) {
                row(b, "!! LEDGER", "HOLD - FUSE GIBT NICHTS AB")
                row(b, "  Quelle", l.holdReason ?: "unbekannt")
                val fehler = if (l.activeErrors.isEmpty()) "keine benannt"
                else l.activeErrors.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} x${it.value}" }
                row(b, "  Ursache", fehler)
                row(b, "  Generation", l.holdGeneration.toString())
                row(b, "  offen", "${l.openEntries} Zeilen, ${f2(l.grossLiabilityU)} U Haftung")
                row(b, "  Ausweg", "Einstellungen -> FUSE -> Reparatur")
            } else {
                row(b, "Ledger", "frei - ${l.openEntries} offen, ${f2(l.grossLiabilityU)} U")
            }
            l.lastRepairTs?.let { row(b, "  repariert", "vor ${(nowMs - it) / 60_000} min") }
        }
        sec(b, "Beobachter")

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
        sec(b, "Signal")

        // ---- Signal --------------------------------------------------------
        val s = outcome.signal
        if (s == null) row(b, "Signal", "-") else {
            row(b, "q1 / roh", "${f1(s.q1)} / ${f1(s.rawBg)} mg/dl" + if (s.q1Outlier) "  AUSREISSER" else "")
            // rSigned NUR von hier. Aus der Bahn rekonstruiert waere es bereits
            // einen Zerfallsschritt gealtert und damit eine falsche Zahl.
            row(b, "Stoerung r", s.rSigned?.let { "${f3(it)} mg/dl/min" } ?: "nicht berechenbar")
            // Danebengestellt, damit die Traegheit des 18-min-Medians am Onset
            // sichtbar ist statt erklaert werden zu muessen.
            row(b, "  UKF-Rate", f3(s.ukfRatePerMin))
            row(b, "  roh 5min", s.rawSlopePerMin?.let { f3(it) } ?: "-")
            // Tonis Dreiteilung 08.08.: Fenster, Vorrat und Paare sind drei
            // verschiedene Dinge - "19 von 190" verquickte sie.
            row(b, "Drive-Fenster", "${s.samplesUsed} Punkte / 18 min")
            row(b, "Rohhistorie", "${s.rawSeriesSize} Punkte (198 min)")
            if (s.boundedBy != SignalWindow.Bound.NONE) row(b, "Fenster ab", s.boundedBy.name)
        }
        outcome.band?.let {
            row(b, "Stoerung m/l", "${f3(it.mean)} / ${f3(it.lower)}")
            row(b, "Steigungspaare", "${it.pairCount} (Spreizung ${f3(it.spread)})")
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
        outcome.onset?.let { o -> row(b, "Onset-Kanal", onsetText(o)) }
        marker?.takeIf { it.armedTs > 0 }?.let { m ->
            val elapsed = ((nowMs - m.armedTs) / 60_000L).toInt()
            // Nach Fenster + 2 h Nachlauf verschwindet die Zeile - ein
            // gestriger Marker ist Historie (Trail), kein Zustand (Tab).
            if (elapsed > m.windowMin + 120) return@let
            val restMin = (m.windowMin - elapsed).coerceAtLeast(0)
            row(b, "Marker", if (restMin > 0) "AKTIV - Rest $restMin/${m.windowMin} min" else "abgelaufen")
            // DER FALL, DER SONST UNERKLAERLICH WAERE: nach einem Absturz
            // zwischen Knopfdruck und Ledger-Persist steht oben "AKTIV", und
            // es gibt trotzdem keine Episode und keinen Kredit. Der Nutzer
            // muss dann zweimal druecken (zuruecknehmen, neu armen) - das
            // sagen wir hier, statt ihn raten zu lassen.
        }
        // IMMER, und ausdruecklich AUSSERHALB des Markerblocks: der hat drei
        // Ausgaenge, an denen die Zeile sonst verlorenginge - kein Marker,
        // armedTs 0 nach Ruecknahme, und das `return@let` nach 210 Minuten.
        // Genau der letzte Fall stand am 12.08. auf dem Testgeraet: Marker von
        // gestern, Episode abgelehnt, und die Begruendung unsichtbar.
        evidenceZeilen(b, outcome)
        outcome.mealStats?.let { ms ->
            // T0-Anker (Toni 08.08.): Stand nach 30/60 min AB ESSENSBEGINN,
            // waechst bis zur Marke und friert dann ein - rollierende Fenster
            // waren nicht die gewuenschte Groesse.
            // "publiziert" statt implizit "geliefert" (Fix-Pass 4 Nr. 18):
            // gezaehlt wird die gate-wirksam publizierte Menge - die
            // Lieferbestaetigung ist Sache des Ledgers.
            row(b, "seit Mahlzeit", "${f2(ms.totalU)} U publiziert nach ${ms.sinceMin} min (T0+30: ${f2(ms.first30U)} / T0+60: ${f2(ms.first60U)})")
        }
        outcome.prime?.let { pr ->
            primeText(pr)?.let { txt ->
                val stand = marker?.takeIf { it.armedTs > 0 && it.envelopeU > 0 }
                    ?.let { m -> "  [${f2((m.envelopeU - pr.remainingU).coerceAtLeast(0.0))}/${f2(m.envelopeU)} U geliefert]" } ?: ""
                row(b, "Freigabe", txt + stand)
            }
        }
        if (outcome.candidate != null || outcome.candidateGap != null) sec(b, "Pruefungen")
        outcome.candidate?.let { c ->
            row(
                b, "Kandidat",
                if (c.reject == null) "bestaetigt bis ${f2(c.smbU)} U (${c.candidatesEvaluated} geprueft)"
                else "${c.reject!!.name} - erlaubt ${f2(c.smbU)} U"
            )
        }
        outcome.candidateGap?.let { row(b, "Kandidat", "Pruefer-Ausfall: $it (Basis galt)") }
        sec(b, "Bahn")

        // ---- Bahn ----------------------------------------------------------
        val d = outcome.decision
        row(b, "Ziel", outcome.targetMgdl?.let { "${f0(it)} (${outcome.targetSource})" } ?: "-")
        row(b, "predBG", d.predAtReleaseMgdl?.let { f0(it) + subFloor(it) } ?: "-")
        row(b, "minLower", d.minLowerMgdl?.let { f0(it) + subFloor(it) } ?: "-")
        row(b, "minMean", outcome.prediction?.minMeanBg?.let { f0(it) + subFloor(it) } ?: "-")
        sec(b, "Menge")

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
        // ABBRUCH-Fallback (Toni 08.08.: iobTH nie verstecken): ohne State
        // kommen die Basiswerte aus der Abort-Anreicherung, die Ratio-Zeile
        // zeigt ihre Konfiguration ohne wirksamen Anteil.
        if (outcome.state == null) {
            if (outcome.iobThU != null || outcome.maxIobU != null)
                row(b, "iobTH / maxIOB", "${outcome.iobThU?.let { f2(it) } ?: "-"} / ${outcome.maxIobU?.let { f2(it) } ?: "-"} U")
            outcome.policy?.let { p ->
                sec(b, "SMB-Ratio")
                row(b, "Rampe", "- % → ${f2(p.smbRatioRise)}")
                row(b, "Korrektur", f2(p.smbRatio))
                row(b, "effektiv", "-  (kein Zyklus)")
            }
        }
        outcome.state?.let {
            // Tonis IOB-Referenz-Regel sichtbar gemacht: net ist die
            // Prognose-Groesse, cap = max(net, Bolus) die Dosier-Referenz -
            // bei stark negativem Basal-Delta gehen sie weit auseinander.
            row(b, "", "Bolus ${f2(it.bolusIobU)} | Basal ${f2(it.basalIobU)} | cap ${f2(it.capIobU)}")
            row(b, "iobTH / maxIOB", "${f2(it.iobThU)} / ${f2(it.maxIobU)} U")
            val rampPct = it.rSignedMgdlPerMin?.let { r ->
                (((r - it.riseRampLowRPerMin) / (it.riseRampHighRPerMin - it.riseRampLowRPerMin))
                    .coerceIn(0.0, 1.0) * 100).toInt()
            }
            // Eigene Mini-Sektion statt Kombizeile (Tonis Layout 08.08.):
            // Rampe, wirksamer Deckel und Effektivwert je als Label-Wert-Paar.
            sec(b, "SMB-Ratio")
            row(b, "Rampe", "${rampPct?.toString() ?: "-"} % → ${f2(it.smbRatioRise)}")
            when {
                it.reboundWindow -> row(b, "Rebound-Deckel", f2(it.smbRatioCorrection))
                !it.mealWindow   -> row(b, "Korrektur", f2(it.smbRatioCorrection))
                else             -> row(b, "Anstiegsfenster", "Rampe aktiv")
            }
            row(b, "effektiv", f2(it.effectiveSmbRatio))
        }
        d.tail?.let {
            sec(b, "Schwanz")
            if (it.usable) {
                row(b, "frei", f2(it.headroomU) + " U")
                row(b, "Budget @ H", f2(it.budgetU) + " U")
                // C4: die Haftung hat DREI Terme. Sie stehen einzeln da, sobald
                // sie nicht null sind - eine Summe ohne Herkunft ist die Zahl,
                // die spaeter niemand mehr auseinandernimmt.
                row(b, "IOB @ H", f2(it.existingIobAtHU) + " U")
                if (it.transportLiabilityU > 0.0) row(b, "  Transport @ H", f2(it.transportLiabilityU) + " U")
                if (it.candidateLiabilityU > 0.0) row(b, "  Dosis @ H", f2(it.candidateLiabilityU) + " U")
            } else {
                row(b, "frei", "-  (unbrauchbar: ${it.invalidReason})")
            }
            if (d.tailCostU > 0.0) row(b, "Kosten", f2(d.tailCostU) + " U")
            row(b, "Modell", modellText(it.completeness))
            modellChecks(it.completeness)?.let { line -> row(b, "", line) }
        }
        sec(b, "Ergebnis")

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

    /** "INCOMPLETE(1of3,noLedger,noCandidate)" -> "PARTIAL 1/3",
     *  "COMPLETE(3of3)" -> "VOLLSTAENDIG 3/3"; unbekannte Formate unveraendert
     *  durchreichen (ehrlicher als raten).
     *
     *  Abgeleitet, nicht gepflegt: der Vermerk entsteht in [TailLiability] aus
     *  den tatsaechlich gerechneten Termen - die Anzeige kann deshalb nie etwas
     *  anderes behaupten als die Rechnung (C4c). */
    private fun modellText(completeness: String): String {
        val m = Regex("""\((\d)of(\d)""").find(completeness) ?: return completeness
        val wort = if (completeness.startsWith("COMPLETE")) "VOLLSTAENDIG" else "PARTIAL"
        return "$wort ${m.groupValues[1]}/${m.groupValues[2]}"
    }

    /** Haken-Zeile zum Modellstand: IOB traegt Stufe 1 immer; Transport-
     *  und Kandidaten-Anteil je nach Vermerk.
     *
     *  "~" ist KEIN Haken: der Term steht in der Gleichung, aber nur als obere
     *  Schranke (volle Menge statt gerechneter Restwirkung, weil der
     *  Einheitskern fehlte). Das ist konservativ - und es soll nicht wie eine
     *  Messung aussehen. */
    private fun modellChecks(completeness: String): String? {
        if ("of" !in completeness) return null
        val transport = when {
            "noLedger" in completeness         -> "✗"
            "transportBounded" in completeness -> "~"
            else                               -> "✓"
        }
        val kandidat = when {
            "noCandidate" in completeness      -> "✗"
            "candidateBounded" in completeness -> "~"
            else                               -> "✓"
        }
        val legende = if (transport == "~" || kandidat == "~") "   (~ = obere Schranke)" else ""
        return "IOB ✓  Transport $transport  Kandidat $kandidat$legende"
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

    /** Abschnitts-Kopf im autoISF-Stil (Tonis Lesbarkeits-Wunsch 08.08.). */
    private fun sec(b: StringBuilder, name: String) {
        b.append('\n').append("--- ").append(name).append(" ")
            .append("-".repeat((26 - name.length).coerceAtLeast(2))).append('\n')
    }

    private fun row(b: StringBuilder, label: String, value: String) {
        // DIE SPALTENBREITE IST EINE ZUSICHERUNG UEBER DIE BESCHRIFTUNGEN,
        // nicht ueber diese Zeile: `padEnd(16)` liefert bei genau 16 Zeichen
        // gar keinen Abstand, und am 12.08. stand deshalb
        // "Evidence-EpisodeNICHT eroeffnet" auf dem Geraet. Kein Test sah es -
        // alle suchten den WERT, und der stimmte.
        //
        // Hier eine Fallunterscheidung einzubauen waere die schlechtere
        // Loesung gewesen: sie haette die Spalte still verrutschen lassen und
        // waere zudem unpruefbar, solange keine Beschriftung so lang ist.
        // Stattdessen haelt FuseScreenModelLabelWidthTest die Laenge fest.
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

    /**
     * DER ZUSTAND DER EVIDENZ-EPISODE, immer wenn er etwas zu sagen hat.
     *
     * Drei Lagen, die ohne eigene Zeile nicht auseinanderzuhalten waeren:
     * keine Episode mit Grund, laufende Episode mit widerrufenem Kredit, und
     * der stumme Normalfall (dann steht hier nichts).
     */
    private fun evidenceZeilen(b: StringBuilder, outcome: FuseCycleRunner.Outcome) {
        outcome.evidenceEpisodeDenial?.let { grund ->
            row(b, "Evidenz-Episode", "NICHT eroeffnet - $grund, zusaetzlicher Kredit 0")
            // Die Handlungsanweisung haengt am GRUND: gegen einen nicht
            // durablen Druck hilft erneutes Druecken, gegen einen
            // Uhrenruecksprung nicht - dort waere der Rat schlicht falsch.
            when (grund) {
                "MARKER_EVENT_NOT_DURABLE" -> row(b, "", "Marker zuruecknehmen und erneut druecken")
                "MARKER_CLOCK_ROLLBACK"    -> row(b, "", "Uhr ist rueckwaerts gesprungen - erneutes Druecken hilft nicht")
                else                       -> {}
            }
        }
        if (outcome.evidenceCreditRevoked && outcome.evidenceEpisodeId > 0L)
            row(b, "Evidenz-Kredit", "WIDERRUFEN (Ruecknahme) - Episode und Bezahlung laufen weiter")
    }
}
