package app.aaps.fuse.plugin.export

import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.util.Sha
import app.aaps.fuse.plugin.FuseCycleRunner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Der Zyklus-Datensatz, den R89 zur Installationsvoraussetzung macht.
 *
 * Seit v7 (Audit R95, Fix 3) ist der Commitment-Ledger verdrahtet: R89
 * §360-361 verlangt Ledgerrevision und die Mengenbilanz
 * (gross/accounted/residual), und beides kommt jetzt aus der ECHTEN
 * Ledger-Sicht des Zyklus statt als benannte Luecke. `r89Complete` haengt
 * an der tatsaechlich uebergebenen Sicht: fehlt sie (alter Aufrufer, Fehler
 * im Adapter), stehen die GAP_NO_LEDGER-Luecken wieder da - ein Datensatz,
 * der vollstaendig AUSSIEHT, wuerde sonst als Freigabe gelesen.
 *
 * Reine Erzeugung: kein Dateizugriff, kein Android. Das Schreiben liegt in
 * [FuseStateExporter], damit der Inhalt ohne Geraet pruefbar bleibt.
 */
object FuseStateJson {

    const val VERSION = 1

    /**
     * Version des REGELWERKS. Handgepflegte Konstante — also genau die Sorte
     * Zahl, die stimmt, bis jemand das Hochzaehlen vergisst. Deshalb steht in
     * JEDEM Datensatz `ruleSetVersionIsManual: true`: eine Auswertung darf
     * einen unveraenderten Wert NICHT als Beweis lesen, dass sich die Regeln
     * nicht geaendert haben.
     */
    // v8 (09.08.): Fix-Pass 5 - prior-freie Sicherheitsbahn, Bremsbahn in
    // allen Mit-Dosis-Pruefungen, Transportmenge in Bahn+Schwanz (3of3),
    // erklaerte Absorption als Bedarf, Puls-Zaehler, SMB+TBR gemeinsam.
    // v6 (08.08. mittags): Marker entwaffnet Rebound-Bremse (Gas-vor-Bremse
    // nur fuer erklaertes Wissen) + Mess-Flag reboundSuppressedByMarker.
    // v7 (08.08. abends, Audit R95 Fix 3): Commitment-Ledger verdrahtet -
    // ledger-Block gefuellt (revision/transportCommitment/hold/openEntries/
    // holdGeneration/aktive Fehler), Transportmenge geht von den Headrooms
    // der Kandidatensuche ab, Hold nullt nach dem Lift, Episodenbudgets
    // restartfest, Huellen-Belastung auf gate-wirksame Menge umgestellt.
    const val RULE_SET_VERSION = 8

    /** Gruende fuer fehlende Felder. Benannt statt weggelassen. */
    const val GAP_NO_LEDGER = "LEDGER_NOT_WIRED"
    const val GAP_POLICY_NOT_READ = "POLICY_NOT_READ_THIS_CYCLE"
    const val GAP_HASH_NOT_FINITE = "HASH_INPUT_NOT_FINITE"
    const val GAP_METRICS_LAG = "EXPORT_METRICS_LAG_BY_ONE"

    /** Messwerte des VORIGEN Schreibvorgangs. Sie koennen nicht im eigenen
     *  Datensatz stehen — die Dauer des Schreibens ist erst danach bekannt. */
    data class PrevWrite(val writeMs: Long, val bytes: Int)

    /** Woher der laufende Build stammt. `committed = false` heisst: es lag
     *  Unversioniertes im Baum — der Hash allein identifiziert den Stand dann
     *  NICHT, und genau das muss im Datensatz stehen. */
    data class Build(val versionName: String, val head: String, val committed: Boolean)

    /** Die Ledger-Sicht NACH den Buchungen des Zyklus. [revision] ist die
     *  monotone Aenderungszaehlung des Adapters (R89 §360). */
    data class LedgerSnapshot(val revision: Long, val state: app.aaps.fuse.core.ledger.LedgerState)

    /**
     * Was das PUBLIKATIONSGATE mit diesem Zyklus gemacht hat (B0c).
     *
     * Der Grund einer Zurueckhaltung stand bisher ausschliesslich als
     * angehaengter Text im `rt.reason`; der Trail exportiert nur die vier
     * Aktuatorfelder, also war er dort unsichtbar. Ein Zyklus, in dem eine
     * gerechnete Menge NICHT hinausging, sah im Trail aus wie einer, der
     * keine gerechnet hat - und die Unterscheidung ist genau das, was eine
     * Auswertung braucht.
     *
     * Die Werte kommen als DATEN aus [app.aaps.fuse.plugin.ledger.LedgerPublicationGate.Outcome],
     * nicht aus einer nachtraeglichen Zerlegung des Grundtextes.
     *
     * @param allowed hat das RT das Gate unveraendert verlassen? Ohne units
     *   trivialerweise true - [reason] unterscheidet die Faelle.
     * @param reason `null`, wenn nichts entfernt wurde.
     * @param treatmentViewPresent hatte der Zyklus eine Behandlungs-Vollsicht?
     *   Vom Zyklus selbst, nicht vom Gate abgeleitet.
     */
    data class PublicationGate(val allowed: Boolean, val reason: String?, val treatmentViewPresent: Boolean)

    fun record(
        cycleId: String,
        outcome: FuseCycleRunner.Outcome,
        rt: RT,
        policy: FuseCycleRunner.Config?,
        build: Build?,
        buildStartNs: Long,
        prev: PrevWrite?,
        // VOR nowNs, damit bestehende Aufrufe mit Trailing-Lambda den neuen
        // Parameter per Default ueberspringen koennen.
        ledger: LedgerSnapshot? = null,
        publicationGate: PublicationGate? = null,
        nowNs: () -> Long,
    ): JSONObject {
        val gaps = JSONArray()
        fun gap(field: String, reason: String) = gaps.put(JSONObject().put("field", field).put("reason", reason))

        val o = JSONObject()
        o.put("v", VERSION)
        o.put("cycleId", cycleId)
        o.put("computeTs", outcome.computeTs)
            .put("computeDurationMs", outcome.computeDurationMs ?: JSONObject.NULL)
            .put("mealStats", outcome.mealStats?.let { m ->
                JSONObject().put("sinceMin", m.sinceMin).put("totalU", fin(m.totalU))
                    .put("first30U", fin(m.first30U)).put("first60U", fin(m.first60U))
            } ?: JSONObject.NULL)
        putOrGap(o, "sourceTs", outcome.sourceTs, gaps, "NO_SIGNAL_THIS_CYCLE")
        o.put("abortReason", outcome.abortReason ?: JSONObject.NULL)

        // ---- Entscheidung + die VIER Aktuatorfelder (R89) -------------------
        val d = outcome.decision
        o.put(
            "decision", JSONObject()
                .put("smbU", d.smbU)
                .put("tbr", d.tbr.name)
                .put("block", d.block.name)
                .put("bindingLimit", d.bindingLimit)
                .put("insulinReqU", fin(d.insulinReqU))
                .put("predAtReleaseMgdl", fin(d.predAtReleaseMgdl))
                .put("minLowerMgdl", fin(d.minLowerMgdl))
                .put("minMeanMgdl", fin(outcome.prediction?.minMeanBg))
                // Hat die SCHNELLE Bahn gebremst? Ohne dieses Feld ist im
                // Nachhinein nicht unterscheidbar, ob eine Zurueckhaltung aus
                // dem traegen Antrieb kam oder aus der Bremse.
                .put("restraintBound", d.restraintBound)
                // FEHLTE bis 08.08. - der Schirm zeigte die Schwanz-Kosten,
                // der Trail nicht (18 bindende Zyklen der Nacht alle "0").
                .put("tailCostU", fin(d.tailCostU))
                .put("reason", outcome.reason)
                .put("alarm", outcome.alarm)
        )
        // Genau die vier Felder, ueber die AAPS aktuiert. null heisst hier
        // AUSDRUECKLICH "nichts angefordert" und nicht "unbekannt".
        o.put(
            "rt", JSONObject()
                .put("rate", fin(rt.rate))
                .put("duration", rt.duration ?: JSONObject.NULL)
                .put("units", fin(rt.units))
                .put("deliverAt", rt.deliverAt ?: JSONObject.NULL)
        )

        // ---- Gate ----------------------------------------------------------
        // `allowed`, nicht `mayActuate` — letzteres ist eine lokale Variable im
        // RT-Bauer. Und `pumpClass` ist bei fehlender Pumpe der Sentinel "none",
        // kein Klassenname; wer danach sucht, sucht vergeblich.
        //
        // `realPump` ist der Unterschied, auf den es bei der Auswertung
        // ankommt: eine erlaubte VirtualPump und eine erlaubte Medtrum sind
        // beide `allowed`, aber nur bei einer davon war echtes Insulin im
        // Spiel. Ohne dieses Feld liesse sich das im Nachhinein nur noch am
        // Namen des Verdikts ablesen — und Namen aendern sich.
        o.put(
            "gate", JSONObject()
                .put("verdict", outcome.gate.verdict.name)
                .put("allowed", outcome.gate.allowed)
                .put("realPump", outcome.gate.realPump)
                .put("pumpClass", outcome.gate.pumpDescription)
                .put("reason", outcome.gate.reason)
        )

        // ---- Publikationsgate (B0c) ----------------------------------------
        // NICHT dasselbe wie `gate`: jenes ist der harte Pumpenriegel,
        // dieses die Ledger-Freigabe des Zyklus. Beide koennen unabhaengig
        // voneinander eine Menge zurueckhalten, und im Trail muss unterscheidbar
        // bleiben, welches es war.
        if (publicationGate == null) gap("publicationGate", "NOT_REPORTED")
        else o.put(
            "publicationGate", JSONObject()
                .put("allowed", publicationGate.allowed)
                .put("reason", publicationGate.reason ?: JSONObject.NULL)
                .put("treatmentViewPresent", publicationGate.treatmentViewPresent)
        )

        // ---- Signal --------------------------------------------------------
        val s = outcome.signal
        if (s == null) gap("signal", "NO_SIGNAL_THIS_CYCLE")
        else o.put(
            "signal", JSONObject()
                .put("q1", fin(s.q1))
                .put("rawBg", fin(s.rawBg))
                .put("rSigned", fin(s.rSigned))
                // DREI Ratenmaasse nebeneinander - ein zweites Thermometer,
                // kein zweiter Regler. Nur rSigned wirkt; die anderen beiden
                // machen messbar, wieviel Vorsprung ein kuerzeres Fenster hat.
                .put("ukfRatePerMin", fin(s.ukfRatePerMin))
                .put("rawSlopePerMin", fin(s.rawSlopePerMin))
                .put("activityAtAnchor", fin(s.activityAtAnchor))
                .put("isfAtAnchor", fin(s.isfAtAnchor))
                .put("ukfLearnedR", fin(s.ukfLearnedR))
                // 4.0 = der Clamp aus UkfQ1.kt:136 (nacktes Literal DORT; hier
                // nicht referenzierbar, ohne die gelockte Datei anzufassen -
                // die Fork-Kopie ist byteidentisch, eine einseitige Aenderung
                // ergaebe zwei stumm divergierende Filter).
                .put("ukfRateSaturated", kotlin.math.abs(s.ukfRatePerMin) >= 4.0 - 1e-9)
                // Der Antrieb der Bremsbahn, fertig BGI-bereinigt - exakt die
                // am 06.08. korrigierte Groesse, jetzt nachrechenbar.
                .put("fastDriveAdjusted", fin(s.ukfRatePerMin + s.activityAtAnchor * s.isfAtAnchor))
                .put("samplesUsed", s.samplesUsed)
                .put("rawSeriesSize", s.rawSeriesSize)
                .put("q1Outlier", s.q1Outlier)
                .put("boundedBy", s.boundedBy.name)
                .put("windowFromTs", s.windowFromTs)
        )

        val b = outcome.band
        if (b == null) gap("drive", "NO_BAND_THIS_CYCLE")
        else o.put(
            "drive", JSONObject()
                .put("mean", fin(b.mean))
                .put("lower", fin(b.lower))
                .put("spread", fin(b.spread))
                .put("pairCount", b.pairCount)
                .put("methodId", policy?.let { app.aaps.fuse.core.signal.PairSlopeBand.methodId(it.driveLowerQuantilePct) } ?: JSONObject.NULL)
                .put("candidate", outcome.candidate?.let { c ->
                    JSONObject()
                        .put("smbU", fin(c.smbU))
                        .put("reject", c.reject?.name ?: JSONObject.NULL)
                        .put("bindingLimit", c.bindingLimit)
                        .put("meanWithCandidate", c.meanWithCandidateMgdl?.let { fin(it) } ?: JSONObject.NULL)
                        .put("minLowerWithCandidate", c.minLowerWithCandidateMgdl?.let { fin(it) } ?: JSONObject.NULL)
                        .put("effectPerU", c.effectPerUAtReleaseMgdl?.let { fin(it) } ?: JSONObject.NULL)
                        .put("evaluated", c.candidatesEvaluated)
                } ?: JSONObject.NULL)
                .put("candidateGap", outcome.candidateGap ?: JSONObject.NULL)
                .put("prime", outcome.prime?.let { pr ->
                    JSONObject()
                        .put("active", pr.active)
                        .put("floorU", fin(pr.floorU))
                        .put("remainingU", fin(pr.remainingU))
                        .put("reason", pr.reason)
                } ?: JSONObject.NULL)
                .put("onset", outcome.onset?.let { o ->
                    JSONObject()
                        .put("active", o.active)
                        .put("mealMarker", o.mealMarker)
                        .put("driveMgdlPerMin", o.driveMgdlPerMin?.let { fin(it) } ?: JSONObject.NULL)
                        .put("remainingU", fin(o.remainingU))
                        .put("reason", o.reason)
                } ?: JSONObject.NULL)
                .put("discount", outcome.discount?.let { d ->
                    JSONObject()
                        .put("lambda", fin(d.lambda))
                        .put("bolusActivityUPerMin", fin(d.bolusActivityUPerMin))
                        .put("isfMgdlPerU", fin(d.isfMgdlPerU))
                        .put("termMgdlPerMin", fin(d.termMgdlPerMin))
                        .put("lowerBefore", fin(d.lowerBeforeMgdlPerMin))
                        .put("lowerAfter", fin(d.lowerAfterMgdlPerMin))
                } ?: JSONObject.NULL)
        )

        // ---- Observer ------------------------------------------------------
        // ALLES, was eine spaetere Nachrechnung von PERSISTENCE und TURN
        // braucht. Bewusst der ZUSTAND und nicht das Ergebnis: die Rohreihe
        // liegt ohnehin in der Datenbank, und welche Minuten FUSE gesehen hat,
        // steht als computeTs/sourceTs in jeder Zeile dieses Trails. Damit ist
        // die Bewertungsregel nachtraeglich aenderbar, statt in der
        // Zustandsmaschine festzustehen.
        //
        // Der EINGEFRORENE Peak einer Episode wird NICHT hier gebildet - er
        // ergibt sich aus livePeak ueber die Zeilen zwischen Confirm und
        // Phasenende. Ihn im Kern einzufrieren hiesse, eine Regel zu locken,
        // die noch nie an Daten geprueft wurde.
        val st = outcome.step
        if (st == null) gap("observer", "NO_STEP_THIS_CYCLE")
        else {
            val obs = JSONObject()
                .put("accepted", st.accepted)
                .put("phase", st.phase.name)
                .put("healthReasons", JSONArray(st.healthReasons.map { it.name }))
                .put("safetyReasons", JSONArray(st.safetyReasons.map { it.name }))
                .put("candidateId", st.candidateId ?: JSONObject.NULL)
                .put("eventId", st.eventId ?: JSONObject.NULL)
                .put("livePeakTs", st.livePeak?.sourceTs ?: JSONObject.NULL)
                .put("livePeakValue", fin(st.livePeak?.value))
                .put("quietAccumMin", fin(st.quietAccumMin))
                .put("confirmCount", st.confirmCount)
                .put("carryDurMin", fin(st.carryDurMin))
                .put("resetCauses", JSONArray(st.resetCauses.map { it.name }))
                .put("sensorEpoch", outcome.sensorEpoch ?: JSONObject.NULL)
                .put("calibrationEpoch", outcome.calibrationEpoch ?: JSONObject.NULL)
            // Der Uebergang ist der ANKER: triggerSourceTs beim RISE_CONFIRMED
            // ist der Zeitpunkt, gegen den eine Episode spaeter bewertet wird.
            st.transition?.let { tr ->
                obs.put(
                    "transition", JSONObject()
                        .put("type", tr.type.name)
                        .put("from", tr.from.name)
                        .put("to", tr.to.name)
                        .put("reasons", JSONArray(tr.reasons.toList()))
                        .put("triggerSourceTs", tr.triggerSourceTs)
                        .put("triggerComputeTs", tr.triggerComputeTs)
                        .put("candidateId", tr.candidateId ?: JSONObject.NULL)
                        .put("eventId", tr.eventId ?: JSONObject.NULL)
                )
            }
            o.put("observer", obs)
        }

        // ---- Zustand -------------------------------------------------------
        o.put(
            "state", JSONObject()
                .put("health", outcome.health?.name ?: JSONObject.NULL)
                .put("iobU", fin(outcome.iobU))
                .put("targetMgdl", fin(outcome.targetMgdl))
                .put("targetSource", outcome.targetSource ?: JSONObject.NULL)
                .put("isfMgdlPerU", fin(outcome.isfMgdlPerU))
                .put("iobThU", fin(outcome.state?.iobThU))
                .put("maxIobU", fin(outcome.state?.maxIobU))
                // Der WIRKSAME Anteil, nicht beide Rohwerte: welche Zahl gegolten hat,
                // haengt an der Phase, und im Nachhinein soll niemand die falsche
                // von zweien lesen. Die Rohwerte stehen ohnehin unter policy.values.
                .put("reboundWindow", outcome.state?.reboundWindow ?: JSONObject.NULL)
                .put("reboundSuppressedByMarker", outcome.state?.reboundSuppressedByMarker ?: JSONObject.NULL)
                .put("mealWindow", outcome.state?.mealWindow ?: JSONObject.NULL)
                .put("smbRatioEffective", fin(outcome.state?.effectiveSmbRatio))
                .put("context", outcome.decision.context?.name ?: JSONObject.NULL)
        )

        // ---- Schwanz -------------------------------------------------------
        val t = d.tail
        if (t == null) o.put("tail", JSONObject.NULL)
        else o.put(
            "tail", JSONObject()
                .put("usable", t.usable)
                .put("budgetU", fin(t.budgetU))
                // ACHTUNG SEMANTIKWECHSEL (C3/C4, 09.08.): existingU ist ab
                // jetzt die GESAMTE Haftung am Horizont (IOB + Transport +
                // beschlossene Menge), nicht mehr nur das sichtbare IOB. Die
                // drei Anteile stehen einzeln daneben - eine Auswertung, die
                // alte und neue Exporte mischt, muss das wissen.
                .put("existingU", fin(t.existingU))
                .put("existingIobAtHU", fin(t.existingIobAtHU))
                .put("transportLiabilityU", fin(t.transportLiabilityU))
                .put("candidateLiabilityU", fin(t.candidateLiabilityU))
                .put("headroomU", fin(t.headroomU))
                .put("costU", fin(d.tailCostU))
                .put("completeness", t.completeness)
                .put("lowerBgAtHSource", t.lowerBgAtHSource)
                // Die beiden Faktoren des Budgets - ohne sie ist eine Sperre
                // nicht in "Bahn zu tief" gegen "ISF-Nenner zu hoch"
                // zerlegbar (Kontroll-Audit 09.08.).
                .put("lowerBgAtHMgdl", fin(t.lowerBgAtHMgdl))
                .put("isfTailMgdlPerU", fin(t.isfTailMgdlPerU))
                .put("negativeLiabilityClamped", t.negativeLiabilityClamped)
                .put("invalidReason", t.invalidReason ?: JSONObject.NULL)
        )

        // ---- Insulinmodell: WELCHE Kurve diese Zahlen erzeugt hat -----------
        // DIA und peak sind Profil-Eigenschaften und koennen sich zwischen zwei
        // Zeilen des Trails aendern, ohne dass sonst irgendetwas es anzeigt.
        // Ohne diesen Block ist nicht entscheidbar, ob zwei Zyklen vergleichbar
        // sind (Kontroll-Audit 09.08.).
        val im = outcome.insulinModel
        if (im == null) o.put("insulinModel", JSONObject.NULL)
        else o.put(
            "insulinModel", JSONObject()
                .put("insulinType", im.insulinType)
                .put("diaHours", fin(im.diaHours))
                .put("peakMin", im.peakMin)
                .put("codeProvenance", im.codeProvenance)
        )

        // ---- Ledger (R89 §360-361, verdrahtet seit v7) ----------------------
        if (ledger == null) {
            // Kein Ersatzwert: fehlt die Sicht, stehen die Luecken wieder da.
            o.put("ledger", JSONObject.NULL)
            gap("ledger.revision", GAP_NO_LEDGER)
            gap("ledger.grossLiabilityU", GAP_NO_LEDGER)
            gap("ledger.accountedU", GAP_NO_LEDGER)
            gap("ledger.residualU", GAP_NO_LEDGER)
        } else {
            val ls = ledger.state
            val open = ls.openEntries
            o.put(
                "ledger", JSONObject()
                    .put("revision", ledger.revision)
                    .put("transportCommitmentU", fin(ls.transportCommitmentU))
                    .put("hold", ls.holdActuation)
                    .put("holdGeneration", ls.holdGeneration)
                    // Die R89-Mengenbilanz ueber die OFFENEN Zeilen:
                    // gross - accounted = residual (= transportCommitment,
                    // geschlossene Zeilen tragen 0).
                    .put("grossLiabilityU", fin(open.sumOf { it.grossLiabilityU }))
                    .put("accountedU", fin(open.sumOf { it.accountedAmountU ?: 0.0 }))
                    .put("residualU", fin(ls.transportCommitmentU))
                    // PHANTOMHAFTUNG (09.08.): Zeilen, die nach DIA plus
                    // Spanne nie abgeglichen waren und deshalb als wirkungslos
                    // abgeschrieben wurden. Ihre Menge haftet nicht mehr - der
                    // GRUND bleibt ein Befund ueber die Abgleichung, und ohne
                    // diese Zahl faellt er niemandem auf.
                    .put("unresolvedBeyondAction", ls.entries.values.count { it.expiredBeyondAction })
                    .put("openEntries", JSONArray(open.map { e ->
                        JSONObject()
                            .put("proposalId", e.proposalId)
                            .put("phase", e.phase.name)
                            .put("accounting", e.accounting.name)
                            .put("delivery", e.delivery.name)
                            .put("commitmentU", fin(e.commitmentU))
                    }))
                    .put("activeErrors", JSONArray(ls.errors.filter { it.active }.map { r ->
                        JSONObject()
                            .put("proposalId", r.proposalId ?: JSONObject.NULL)
                            .put("error", r.error.name)
                            .put("occurrences", r.occurrences)
                            .put("lastDetail", r.lastDetail)
                    }))
            )
        }

        // ---- Politik -------------------------------------------------------
        val pol = JSONObject()
            .put("ruleSetVersion", RULE_SET_VERSION)
            // s. KDoc von RULE_SET_VERSION — ohne dieses Feld liest eine
            // Auswertung einen unveraenderten Hash als Beweis fuer
            // Unveraendertheit.
            .put("ruleSetVersionIsManual", true)
        if (policy == null) {
            pol.put("source", "none").put("hash", JSONObject.NULL)
            gap("policy.hash", GAP_POLICY_NOT_READ)
        } else {
            pol.put("source", "cycle")
            pol.put("values", policyValues(policy))
            val h = hashOf(policy)
            if (h == null) {
                pol.put("hash", JSONObject.NULL)
                gap("policy.hash", GAP_HASH_NOT_FINITE)
            } else pol.put("hash", h)
        }
        o.put("policy", pol)

        // ---- Build ---------------------------------------------------------
        // R89 verlangt Policy- UND Build-Hash. Ohne den zweiten laesst sich ein
        // Geraetelauf nicht auf einen Commit zurueckfuehren - und genau das ist
        // die Frage, die man nach einer auffaelligen Nacht als erstes stellt.
        if (build == null) gap("build", "BUILD_INFO_MISSING")
        else o.put(
            "build", JSONObject()
                .put("versionName", build.versionName)
                .put("head", build.head)
                .put("committed", build.committed)
        )

        // ---- Exportmetrik --------------------------------------------------
        val ex = JSONObject().put("buildMs", (nowNs() - buildStartNs) / 1_000_000)
        if (prev == null) {
            ex.put("prevWriteMs", JSONObject.NULL).put("prevBytes", JSONObject.NULL)
            gap("export.prevWriteMs", GAP_METRICS_LAG)
        } else ex.put("prevWriteMs", prev.writeMs).put("prevBytes", prev.bytes)
        o.put("export", ex)

        o.put("gaps", gaps)
        // Der Kopf sagt in EINEM Feld, ob dieser Datensatz die R89-Bedingung
        // erfuellt - und das haengt an der TATSAECHLICH uebergebenen
        // Ledger-Sicht, nicht an der Codeversion.
        o.put("r89Complete", ledger != null)
        return o
    }

    /**
     * Die Stellgroessen, die den Zyklus bestimmt haben — als Klartext neben dem
     * Hash, damit ein Unterschied nicht nur erkennbar, sondern lesbar ist.
     *
     * Jeder Double geht durch [fin]: `org.json` WIRFT bei NaN/Infinity
     * ("Forbidden numeric value"). Ein Wurf hier laege im runCatching des
     * Exports und liesse den ganzen Datensatz verschwinden — ausgerechnet den,
     * der die kaputte Einstellung dokumentiert.
     */
    fun policyValues(p: FuseCycleRunner.Config): JSONObject = JSONObject()
        .put("smbRatioCorrection", fin(p.smbRatio))
        .put("smbRatioRise", fin(p.smbRatioRise))
        // Fix-Pass 4 Nr. 17: die geteilte maxIOB-Preference gehoert in den
        // Fingerprint - sie ist therapieaktiv (Constraint-Kette + iobTH-Basis).
        .put("sharedMaxIobU", fin(p.sharedMaxIobU))
        .put("maxSmbU", fin(p.maxSmbU))
        .put("guardFloorMgdl", fin(p.guardFloorMgdl))
        .put("iobThPercent", p.iobThPercent)
        .put("releaseHorizonMin", p.releaseHorizonMin)
        .put("liabilityHorizonMin", p.liabilityHorizonMin)
        .put("driveTauMin", p.driveTauMin)
        .put("absorptionCreditWindowMin", p.absorptionCreditWindowMin)
        .put("markerBoostMaxMin", p.markerBoostMaxMin)
        .put("nightStartMin", p.nightStartMin)
        .put("nightEndMin", p.nightEndMin)
        .put("nightDeadbandMgdl", p.nightDeadbandMgdl)
        .put("nightDeadbandEnabled", p.nightDeadbandEnabled)
        .put("reboundDeadbandMgdl", p.reboundDeadbandMgdl)
        .put("reboundDeadbandEnabled", p.reboundDeadbandEnabled)
        .put("driveLowerQuantilePct", p.driveLowerQuantilePct)
        .put("tailGuardEnabled", p.tailGuardEnabled)
        .put("tailFloorMgdl", fin(p.tailFloorMgdl))
        .put("tailRecoveryU", fin(p.tailRecoveryU))
        .put("fastRestraintEnabled", p.fastRestraintEnabled)
        .put("riseRampLowR", fin(p.riseRampLowR))
        .put("riseRampHighR", fin(p.riseRampHighR))
        .put("bolusShareLambda", fin(p.bolusShareLambda))
        .put("onsetChannelEnabled", p.onsetChannelEnabled)
        .put("onsetEnvelopeU", fin(p.onsetEnvelopeU))
        .put("primeReleaseEnabled", p.primeReleaseEnabled)
        .put("primeEnvelopeU", fin(p.primeEnvelopeU))
        .put("primeEnvelopeSmallU", fin(p.primeEnvelopeSmallU))
        .put("primeEnvelopeLargeU", fin(p.primeEnvelopeLargeU))

    /**
     * `null` bei nicht-endlichen Eingaben. [Sha.lossless] WIRFT bei NaN/Inf,
     * und der Wurf laege innerhalb des `runCatching` des Exports — der Hash
     * waere danach dauerhaft still weg. Lieber kein Hash und ein benannter
     * Grund als ein Ersatzwert.
     */
    fun hashOf(p: FuseCycleRunner.Config): String? {
        val doubles = listOf(
            p.smbRatio, p.smbRatioRise, p.maxSmbU, p.guardFloorMgdl, p.tailFloorMgdl, p.tailRecoveryU,
            // Rampe + Abschlag: fehlten bis v1 - zwei Laeufe mit verschiedenen
            // Rampen bekamen denselben Hash (Audit 07.08.). Version 1->2.
            p.riseRampLowR, p.riseRampHighR, p.bolusShareLambda, p.onsetEnvelopeU, p.primeEnvelopeU,
            p.primeEnvelopeSmallU, p.primeEnvelopeLargeU,
        )
        if (doubles.any { !it.isFinite() }) return null
        val parts = listOf("fuse-policy-v$RULE_SET_VERSION") +
            doubles.map { Sha.lossless(it) } +
            listOf(
                p.iobThPercent, p.releaseHorizonMin, p.liabilityHorizonMin,
                p.driveTauMin, p.driveLowerQuantilePct, p.tailGuardEnabled, p.fastRestraintEnabled, p.onsetChannelEnabled, p.primeReleaseEnabled,
            ).map { it.toString() }
        return Sha.of(parts.joinToString("|"))
    }

    private fun fin(d: Double?): Any = if (d != null && d.isFinite()) d else JSONObject.NULL

    private fun putOrGap(o: JSONObject, key: String, v: Long?, gaps: JSONArray, reason: String) {
        if (v == null) {
            o.put(key, JSONObject.NULL)
            gaps.put(JSONObject().put("field", key).put("reason", reason))
        } else o.put(key, v)
    }
}
