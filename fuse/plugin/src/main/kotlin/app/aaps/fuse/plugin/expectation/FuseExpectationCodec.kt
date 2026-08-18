package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import org.json.JSONArray
import org.json.JSONObject

/**
 * LESEN UND SCHREIBEN DES ERWARTUNGS-ZUSTANDS - fail-closed.
 *
 * Tonis Persistenzauflage: "Schema-/Ladefehler duerfen weiterhin keinen
 * lambda-Nachweis erzeugen." Daraus folgt die tragende Eigenschaft dieses
 * Codecs - [decode] gibt bei JEDEM Zweifel den LEEREN Zustand zurueck, nie
 * einen teilweise gelesenen.
 *
 * WARUM DAS DIE GEFAEHRLICHE RICHTUNG IST: eine halb gelesene Generation
 * koennte offene Prognosen gegen bereits verbrauchte Messwerte pruefen
 * (weil `consumed` fehlt) oder eine Strecke fortschreiben, deren Anfang
 * nicht mehr da ist. Beides erfindet einen Nachweis. Ein leerer Zustand
 * verzoegert ihn nur - der Ledger fuellt sich in Minuten wieder.
 *
 * KEIN "SO VIEL WIE MOEGLICH RETTEN". Ein einzelner unlesbarer Eintrag macht
 * die ganze Generation ungueltig, weil die drei Teile nur zusammen stimmig
 * sind. Teilrettung waere hier die bequeme und falsche Loesung.
 */
object FuseExpectationCodec {

    /** Schemastand. Aendert er sich, ist eine aeltere Datei nicht lesbar -
     *  und `decode` liefert den leeren Zustand statt zu raten. */
    const val SCHEMA = 1

    fun encode(state: ExpectationLedger.State): String =
        JSONObject()
            .put("schema", SCHEMA)
            .put("entries", JSONArray().apply { state.entries.forEach { put(entryJson(it)) } })
            .put(
                "consumed",
                JSONArray().apply {
                    state.consumed.sortedWith(compareBy({ it.segmentId }, { it.ts })).forEach {
                        put(JSONObject().put("seg", it.segmentId).put("ts", it.ts))
                    }
                },
            )
            .put("outcomes", JSONArray().apply { state.outcomes.forEach { put(outcomeJson(it)) } })
            .toString()

    /**
     * @return der gelesene Zustand, oder der LEERE bei jedem Fehler - Schema,
     *   Syntax, fehlendes Pflichtfeld, unbekanntes Verdikt, unbrauchbare Zahl.
     */
    fun decode(text: String?): ExpectationLedger.State {
        if (text.isNullOrBlank()) return ExpectationLedger.State()
        return runCatching {
            val o = JSONObject(text)
            if (o.optInt("schema", -1) != SCHEMA) return ExpectationLedger.State()
            val entries = o.getJSONArray("entries").let { a ->
                (0 until a.length()).map { entryOf(a.getJSONObject(it)) }
            }
            val consumed = o.getJSONArray("consumed").let { a ->
                (0 until a.length()).map {
                    val e = a.getJSONObject(it)
                    ExpectationLedger.SampleId(e.getLong("seg"), e.getLong("ts"))
                }.toSet()
            }
            val outcomes = o.getJSONArray("outcomes").let { a ->
                (0 until a.length()).map { outcomeOf(a.getJSONObject(it)) }
            }
            ExpectationLedger.State(entries, consumed, outcomes)
            // GETOR-DEFAULT AUF DEN LEEREN ZUSTAND, nicht auf einen Teil davon.
        }.getOrDefault(ExpectationLedger.State())
    }

    // ---- Eintrag ----------------------------------------------------------

    private fun entryJson(e: ExpectationLedger.Entry) = JSONObject()
        .put("sourceTs", e.sourceTs)
        .put("dueTs", e.dueTs)
        .put("seg", e.segmentId)
        .put("anchor", e.anchorMgdl)
        .put("mean", e.meanPredictedMgdl)
        .put("cfg", e.configGeneration)
        .put("rev", e.interventionRevision)
        .putOpt("safetyLower", e.safetyLowerPredictedMgdl)
        .putOpt("lambda", e.lambda)
        .putOpt("discount", e.discountMgdl)
        .putOpt("bgi", e.bgiMgdl)

    /** Wirft bei jedem fehlenden Pflichtfeld - der Aufrufer faengt das und
     *  verwirft die GANZE Generation. */
    private fun entryOf(o: JSONObject) = ExpectationLedger.Entry(
        sourceTs = o.getLong("sourceTs"),
        dueTs = o.getLong("dueTs"),
        segmentId = o.getLong("seg"),
        anchorMgdl = endlich(o.getDouble("anchor")),
        meanPredictedMgdl = endlich(o.getDouble("mean")),
        configGeneration = o.getString("cfg").also { require(it.isNotBlank()) },
        interventionRevision = o.getLong("rev"),
        safetyLowerPredictedMgdl = optEndlich(o, "safetyLower"),
        lambda = optEndlich(o, "lambda"),
        discountMgdl = optEndlich(o, "discount"),
        bgiMgdl = optEndlich(o, "bgi"),
    )

    // ---- Ergebnis ---------------------------------------------------------

    private fun outcomeJson(x: ExpectationLedger.Outcome) = JSONObject()
        .put("entry", entryJson(x.entry))
        .put("verdict", x.verdict.name)
        .putOpt("actualTs", x.actualTs)
        .putOpt("actualMgdl", x.actualMgdl)

    private fun outcomeOf(o: JSONObject) = ExpectationLedger.Outcome(
        entry = entryOf(o.getJSONObject("entry")),
        // valueOf wirft bei einem unbekannten Namen - ein Verdikt, das diese
        // Fassung nicht kennt, darf nicht still zu etwas Harmlosem werden.
        verdict = ExpectationLedger.Verdict.valueOf(o.getString("verdict")),
        actualTs = if (o.has("actualTs")) o.getLong("actualTs") else null,
        actualMgdl = if (o.has("actualMgdl")) endlich(o.getDouble("actualMgdl")) else null,
    )

    // ---- Zahlen -----------------------------------------------------------

    /** NaN und Unendlich sind keine Messwerte. Sie durchzulassen hiesse,
     *  spaeter mit ihnen zu rechnen. */
    private fun endlich(d: Double): Double {
        require(d.isFinite()) { "nicht endlich: $d" }
        return d
    }

    private fun optEndlich(o: JSONObject, k: String): Double? =
        if (!o.has(k) || o.isNull(k)) null else endlich(o.getDouble(k))
}
