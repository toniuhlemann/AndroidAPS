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
    const val RULE_SET_VERSION = 1

    /** Gruende fuer fehlende Felder. Benannt statt weggelassen. */
    const val GAP_NO_LEDGER = "LEDGER_NOT_WIRED"
    const val GAP_POLICY_NOT_READ = "POLICY_NOT_READ_THIS_CYCLE"
    const val GAP_HASH_NOT_FINITE = "HASH_INPUT_NOT_FINITE"
    const val GAP_METRICS_LAG = "EXPORT_METRICS_LAG_BY_ONE"

    /** Messwerte des VORIGEN Schreibvorgangs. Sie koennen nicht im eigenen
     *  Datensatz stehen — die Dauer des Schreibens ist erst danach bekannt. */
    data class PrevWrite(val writeMs: Long, val bytes: Int)

    fun record(
        cycleId: String,
        outcome: FuseCycleRunner.Outcome,
        rt: RT,
        policy: FuseCycleRunner.Config?,
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
        )

        // ---- Zustand -------------------------------------------------------
        o.put(
            "state", JSONObject()
                .put("health", outcome.health?.name ?: JSONObject.NULL)
                .put("iobU", fin(outcome.iobU))
                .put("targetMgdl", fin(outcome.targetMgdl))
                .put("targetSource", outcome.targetSource ?: JSONObject.NULL)
                .put("isfMgdlPerU", fin(outcome.isfMgdlPerU))
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
        .put("smbRatio", fin(p.smbRatio))
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

    /**
     * `null` bei nicht-endlichen Eingaben. [Sha.lossless] WIRFT bei NaN/Inf,
     * und der Wurf laege innerhalb des `runCatching` des Exports — der Hash
     * waere danach dauerhaft still weg. Lieber kein Hash und ein benannter
     * Grund als ein Ersatzwert.
     */
    fun hashOf(p: FuseCycleRunner.Config): String? {
        val doubles = listOf(p.smbRatio, p.maxSmbU, p.guardFloorMgdl, p.tailFloorMgdl, p.tailRecoveryU)
        if (doubles.any { !it.isFinite() }) return null
        val parts = listOf("fuse-policy-v$RULE_SET_VERSION") +
            doubles.map { Sha.lossless(it) } +
            listOf(
                p.iobThPercent, p.releaseHorizonMin, p.liabilityHorizonMin,
                p.driveTauMin, p.driveLowerQuantilePct, p.tailGuardEnabled,
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
