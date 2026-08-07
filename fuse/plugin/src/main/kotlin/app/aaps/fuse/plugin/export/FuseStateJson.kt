package app.aaps.fuse.plugin.export

import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.util.Sha
import app.aaps.fuse.plugin.FuseCycleRunner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Der Zyklus-Datensatz, den R89 zur Installationsvoraussetzung macht.
 *
 * NICHT ERFUELLT, und das steht in jedem Datensatz statt in einer Fussnote:
 * R89 §360-361 verlangt Ledgerrevision und die Mengenbilanz
 * (gross/accounted/residual). Der Commitment-Ledger ist gebaut und getestet,
 * hat aber KEINE Aufrufstelle im Livepfad — jede dieser Zahlen waere heute
 * erfunden. Sie stehen deshalb als `null` unter einem maschinenlesbaren
 * `gaps`-Block, und `header.r89Complete` ist `false`. Ein Datensatz, der
 * vollstaendig AUSSIEHT, wuerde sonst als Freigabe gelesen.
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
    const val RULE_SET_VERSION = 4

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

    fun record(
        cycleId: String,
        outcome: FuseCycleRunner.Outcome,
        rt: RT,
        policy: FuseCycleRunner.Config?,
        build: Build?,
        buildStartNs: Long,
        prev: PrevWrite?,
        nowNs: () -> Long,
    ): JSONObject {
        val gaps = JSONArray()
        fun gap(field: String, reason: String) = gaps.put(JSONObject().put("field", field).put("reason", reason))

        val o = JSONObject()
        o.put("v", VERSION)
        o.put("cycleId", cycleId)
        o.put("computeTs", outcome.computeTs)
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
        o.put(
            "gate", JSONObject()
                .put("verdict", outcome.gate.verdict.name)
                .put("allowed", outcome.gate.allowed)
                .put("pumpClass", outcome.gate.pumpDescription)
                .put("reason", outcome.gate.reason)
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
                .put("existingU", fin(t.existingU))
                .put("headroomU", fin(t.headroomU))
                .put("costU", fin(d.tailCostU))
                .put("completeness", t.completeness)
                .put("lowerBgAtHSource", t.lowerBgAtHSource)
                .put("invalidReason", t.invalidReason ?: JSONObject.NULL)
        )

        // ---- Ledger: existiert nicht, und das ist die Aussage ---------------
        o.put("ledger", JSONObject.NULL)
        gap("ledger.revision", GAP_NO_LEDGER)
        gap("ledger.grossLiabilityU", GAP_NO_LEDGER)
        gap("ledger.accountedU", GAP_NO_LEDGER)
        gap("ledger.residualU", GAP_NO_LEDGER)

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
        // erfuellt. Solange der Ledger nicht verdrahtet ist: nein.
        o.put("r89Complete", false)
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
        .put("maxSmbU", fin(p.maxSmbU))
        .put("guardFloorMgdl", fin(p.guardFloorMgdl))
        .put("iobThPercent", p.iobThPercent)
        .put("releaseHorizonMin", p.releaseHorizonMin)
        .put("liabilityHorizonMin", p.liabilityHorizonMin)
        .put("driveTauMin", p.driveTauMin)
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
