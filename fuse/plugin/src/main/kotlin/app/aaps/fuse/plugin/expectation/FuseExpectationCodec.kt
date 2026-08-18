package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.InterventionStamp
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
    /**
     * Schemastand 2: der Eingriffsstempel loeste die blosse Zahl ab (Toni
     * 18.08.). Eine v1-Datei hat es nie gegeben - dieser Baustein hatte
     * damals noch keinen Aufrufer -, der Bump ist also gefahrlos und dient
     * nur der Klarheit im Trail.
     */
    const val SCHEMA = 2

    /**
     * @param revision die MONOTONE Generationsnummer. Sie entscheidet beim
     *   Laden, welcher der drei Kandidaten (.tmp, Ziel, .bak) der juengste
     *   ist - ohne sie waere nach einem Absturz zwischen den beiden Renames
     *   nicht feststellbar, welche Datei die neuere Wahrheit traegt.
     */
    fun encode(
        state: ExpectationLedger.State,
        revision: Long,
        lastObservationGapTs: Long,
        droppedOutcomesTotal: Long,
    ): String =
        JSONObject()
            .put("schema", SCHEMA)
            .put("revision", revision)
            // DIE LUECKENMARKE MUSS UEBERLEBEN (P0-2). Ohne sie waere nach
            // einem Neustart nicht mehr erkennbar, dass zwischen den
            // gespeicherten Ergebnissen eine unbeobachtete Minute lag.
            .put("gapTs", lastObservationGapTs)
            // DER TRUNKIERUNGSZAEHLER MUSS MIT (Toni 18.08.). Als reine
            // Prozessgroesse meldete eine laengst gekappte Generation nach
            // jedem Neustart wieder "vollstaendig" - und mehrtaegige Messdaten
            // saehen genau dann komplett aus, wenn sie es am wenigsten sind.
            // Er steht in DERSELBEN Generation, die erstmals kappt, nicht erst
            // im Folgezyklus.
            .put("droppedTotal", droppedOutcomesTotal)
            // KEIN EIGENER KOPFSTAND MEHR (Toni 18.08.): "Der Expectation-Store
            // speichert die Revision an seinen Eintraegen; die aktuelle
            // Autoritaet kommt aus dem Publikationsledger." Zwei Dateien mit
            // je eigenem Anspruch auf denselben Stand koennen auseinander-
            // laufen, und keine von beiden koennte sagen, welche recht hat.
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
     * Das Ergebnis eines Ladeversuchs - DREI Faelle, nicht zwei.
     *
     * Der erste Wurf gab immer den leeren Zustand zurueck und machte damit
     * "Datei fehlt" (Erststart, voellig normal) von "Datei beschaedigt"
     * (Datenverlust, die `.bak`-Generation muss her) ununterscheidbar. Der
     * Store haette eine kaputte Zieldatei als gueltigen Leerstand
     * akzeptiert, statt die Sicherung zu ziehen oder einen Hold auszuloesen.
     */
    sealed interface Decoded {

        data class Valid(
            val state: ExpectationLedger.State,
            val revision: Long,
            val lastObservationGapTs: Long,
            val droppedOutcomesTotal: Long,
        ) : Decoded

        /** Nichts da - beim Erststart der Normalfall. */
        data object Missing : Decoded

        /** Unlesbar oder semantisch unmoeglich. Der Grund ist benannt; die
         *  ENTSCHEIDUNG (Sicherung ziehen, Hold, leer weiterlaufen) trifft
         *  ausschliesslich der Store. */
        data class Invalid(val reason: String) : Decoded
    }

    /**
     * @param kopfstand der AKTUELLE Eingriffsstempel aus dem
     *   Publikationsledger. Pflicht und nicht nullbar: faende sich dort
     *   keiner, muss der Aufrufer eine FRISCHE Epoche eroeffnet haben, bevor
     *   er hier hereinkommt - das ist die sichere Aussage.
     */
    fun decode(text: String?, kopfstand: InterventionStamp): Decoded {
        // NUR `null` HEISST "DATEI FEHLT" (Toni, P1). Eine VORHANDENE Datei
        // aus Whitespace oder Null-Bytes ist beschaedigt - sie als Missing zu
        // melden liesse den Store leer weiterlaufen, statt die
        // .bak-Generation zu ziehen. Genau dieser Fall entsteht bei einem
        // abgebrochenen Schreibvorgang.
        if (text == null) return Decoded.Missing
        if (text.isBlank()) return Decoded.Invalid("Datei vorhanden, aber leer oder nur Leerraum")
        val roh = runCatching {
            val o = JSONObject(text)
            val schema = o.optInt("schema", -1)
            if (schema != SCHEMA) return Decoded.Invalid("Schemastand $schema, erwartet $SCHEMA")
            val revision = o.getLong("revision")
            // Eine negative Generation kann nicht aus einem Schreibvorgang
            // stammen - sie waere ein Rueckwaertssprung in der Reihenfolge.
            require(revision >= 0L) { "negative Revision $revision" }
            Roh(
                revision,
                o.optLong("gapTs", 0L),
                o.optLong("droppedTotal", 0L),
                o.getJSONArray("entries").let { a -> (0 until a.length()).map { entryOf(a.getJSONObject(it)) } },
                o.getJSONArray("consumed").let { a ->
                    (0 until a.length()).map {
                        val e = a.getJSONObject(it)
                        ExpectationLedger.SampleId(e.getLong("seg"), e.getLong("ts"))
                    }.toSet()
                },
                o.getJSONArray("outcomes").let { a -> (0 until a.length()).map { outcomeOf(a.getJSONObject(it)) } },
            )
        }.getOrElse { return Decoded.Invalid("unlesbar: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }

        // DIE SEMANTIK PRUEFT DER KERN, nicht dieser Codec. Zwei Stellen mit
        // je eigener Vorstellung davon, was moeglich ist, liefen mit dem
        // naechsten Feld auseinander.
        return when (
            val r = ExpectationLedger.restore(
                roh.entries, roh.consumed, roh.outcomes,
                kopfstand = kopfstand,
            )
        ) {
            is ExpectationLedger.Restored.Valid   -> Decoded.Valid(r.state, roh.revision, roh.gapTs, roh.droppedTotal)
            is ExpectationLedger.Restored.Invalid -> Decoded.Invalid(r.reason)
        }
    }

    /** Was der Rohparser herausholt - benannt, weil ein namenloses Tupel mit
     *  ZWEI Long-Feldern an der Aufrufstelle vertauschbar waere. */
    private data class Roh(
        val revision: Long,
        val gapTs: Long,
        val droppedTotal: Long,
        val entries: List<ExpectationLedger.Entry>,
        val consumed: Set<ExpectationLedger.SampleId>,
        val outcomes: List<ExpectationLedger.Outcome>,
    )

    // ---- Eintrag ----------------------------------------------------------

    private fun entryJson(e: ExpectationLedger.Entry) = JSONObject()
        .put("sourceTs", e.sourceTs)
        .put("dueTs", e.dueTs)
        .put("seg", e.segmentId)
        .put("anchor", e.anchorMgdl)
        .put("mean", e.meanPredictedMgdl)
        .put("cfg", e.configGeneration)
        .put("epo", e.interventionStamp.epochId)
        .put("seq", e.interventionStamp.sequence)
        .put("ctx", e.context.name)
        .put("ctxReason", e.contextReason.name)
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
        interventionStamp = InterventionStamp(o.getString("epo"), o.getLong("seq")),
        // PFLICHTFELD. `getString` wirft bei Fehlen, `valueOf` bei einem
        // unbekannten Namen - beides verwirft die ganze Generation. Ein
        // Rueckfall auf CORRECTION waere die gefaehrliche Richtung: er
        // machte jeden unlesbaren Eintrag lambda-tauglich.
        context = ExpectationLedger.ExpectationContext.valueOf(o.getString("ctx")),
        // Der GRUND ebenso Pflicht: ohne ihn zeigt der Export nur, DASS
        // etwas ausgeschlossen wurde, nicht weshalb.
        contextReason = ExpectationLedger.ContextReason.valueOf(o.getString("ctxReason")),
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
