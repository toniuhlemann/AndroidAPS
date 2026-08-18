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
    // v9 (11.08.): EIN Mahlzeitenmarker - S/M/L und die zwei Stufenhuellen
    // sind weg, es gilt nur noch primeEnvelopeU.
    // v10 (15.08.): eine laufende Schutz-Null endet, sobald ihr Grund weg ist
    // (endZeroWhenReasonGone). Aendert die Aktuation auf der TBR-Achse - ein
    // Lauf davor und danach ist NICHT vergleichbar, deshalb eigene Version.
    const val RULE_SET_VERSION = 10

    /** Schema des Trail-Datensatzes - s. die Notiz an der Schreibstelle. */
    const val SCHEMA_VERSION = 4

    /** Gruende fuer fehlende Felder. Benannt statt weggelassen. */
    const val GAP_NO_LEDGER = "LEDGER_NOT_WIRED"
    const val GAP_POLICY_NOT_READ = "POLICY_NOT_READ_THIS_CYCLE"
    const val GAP_HASH_NOT_FINITE = "HASH_INPUT_NOT_FINITE"
    const val GAP_METRICS_LAG = "EXPORT_METRICS_LAG_BY_ONE"

    /** Der Kern ist verdrahtet, wurde in diesem fruehen Abbruchzyklus aber
     *  mangels auswertbarem Signal nicht erreicht. */
    const val GAP_EVIDENCE_NOT_EVALUATED = "EVIDENCE_NOT_EVALUATED_THIS_CYCLE"

    /** Messwerte des VORIGEN Schreibvorgangs. Sie koennen nicht im eigenen
     *  Datensatz stehen — die Dauer des Schreibens ist erst danach bekannt. */
    data class PrevWrite(val writeMs: Long, val bytes: Int)

    /** Woher der laufende Build stammt. `committed = false` heisst: es lag
     *  Unversioniertes im Baum — der Hash allein identifiziert den Stand dann
     *  NICHT, und genau das muss im Datensatz stehen. */
    data class Build(val versionName: String, val head: String, val committed: Boolean)

    /** Die Ledger-Sicht NACH den Buchungen des Zyklus. [revision] ist die
     *  monotone Aenderungszaehlung des Adapters (R89 §360). */
    data class LedgerSnapshot(
        val revision: Long,
        val state: app.aaps.fuse.core.ledger.LedgerState,
        /** Messwerte des letzten Schreibvorgangs; `null` = in diesem
         *  Prozess noch nicht geschrieben. */
        val persist: app.aaps.fuse.plugin.ledger.FuseLedgerStore.PersistStats? = null,
    )

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

    /**
     * B3: die PATCH-EPOCHE dieses Zyklus.
     *
     * Der SPERRGRUND steht schon im Publikationsgate
     * (`REAL_PUMP_EPOCH_UNKNOWN`) - was dort fehlt, ist die DIAGNOSE: WARUM
     * ist die Epoche unbekannt? Ohne sie sieht man im Trail, dass gesperrt
     * wurde, aber nicht, ob es an einem fehlenden Datensatz, einem
     * Handeintrag, einer fremden Pumpe oder einer nicht lesbaren aktiven
     * Pumpe lag. Das sind vier verschiedene Ursachen mit vier verschiedenen
     * Massnahmen.
     *
     * @param epochTs `null` heisst UNBEKANNT, nicht "keine Epoche" - siehe
     *   [reason].
     * @param reason der Name aus `FusePatchEpoch.Reason`. Er sagt bewusst
     *   MATCHING_PUMP_IDENTITY und nicht "PUMP_ORIGIN": bewiesen ist eine
     *   passende Identitaet, nicht die Herkunft.
     * @param applicable ist die Patch-Epoche fuer die AKTIVE Pumpe ueberhaupt
     *   eine Kategorie? `null` heisst "aktive Pumpe nicht lesbar".
     *
     *   OHNE dieses Feld ist der Export irrefuehrend: an der VirtualPump steht
     *   dort `known=false, reason=NO_EVENT`, und das SIEHT aus wie eine
     *   fehlende Epoche, obwohl es dort gar keine geben kann. Wer den Trail
     *   liest, wuerde einen Defekt suchen, den es nicht gibt - und schlimmer,
     *   er koennte den umgekehrten Schluss ziehen und die Sperre fuer wirksam
     *   halten, wo sie gar nicht greift.
     */
    data class PatchEpoch(
        val epochTs: Long?,
        val known: Boolean,
        val reason: String,
        val applicable: Boolean? = null,
    )

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
        patchEpoch: PatchEpoch? = null,
        /**
         * Die LETZTE Ledger-Reparatur, falls es je eine gab.
         *
         * Sie steht in JEDEM Zyklus danach, nicht nur in dem, in dem sie
         * passiert ist. Ein frisch zurueckgesetzter Ledger sieht sonst exakt
         * aus wie ein unbenutzter, und "keine Haftung" waere von "hier wurde
         * Haftung verworfen" nicht zu unterscheiden.
         */
        ledgerReset: app.aaps.fuse.plugin.ledger.FuseLedgerRepair.ResetRecord? = null,
        /**
         * Was AAPS mit dem SMB des VORIGEN Zyklus gemacht hat (Scheibe 1).
         * Reine Messung - keine Zahl davon wird gelesen.
         */
        priorActuation: app.aaps.fuse.plugin.FusePlugin.PriorActuation? = null,
        /**
         * DER ERWARTUNGS-LEDGER - reine Beobachtung, keine Zahl davon wird
         * gelesen.
         */
        expectation: Expectation? = null,
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
            // Die Evidenz-Episode: Identitaet UND der Grund, falls keine
            // eroeffnet wurde. Beides, weil "0 ohne Grund" (kein Marker) etwas
            // anderes ist als "0 mit Grund" (Druck nicht durabel).
            // DER GRUND STEHT AUSSERHALB DES BLOCKS, und das ist kein Versehen:
            // er erklaert, warum es KEINE Episode gibt - im Block waere er
            // unerreichbar, weil der dann `null` ist. Id und Widerruf standen
            // dagegen DOPPELT (Toni 12.08.) und sind hier entfallen: ein Block,
            // eine Wahrheit.
            .put("evidenceEpisodeDenial", outcome.evidenceEpisodeDenial ?: JSONObject.NULL)
            // ---- Die Evidenz-Episode als EIN Block ----------------------
            //
            // Vorher standen Menge, Alter und Deckel einzeln nebeneinander -
            // mit der Folge, dass nach dem Ablauf eine alte `committedU` neben
            // einem fehlenden Anker und einem `null`-Alter stand (Toni 12.08.).
            // Das liest sich wie eine laufende Episode.
            //
            // Jetzt: entweder es gibt eine Episode, dann ist der Block
            // vollstaendig - oder es gibt keine, dann ist er `null`. Kein
            // Zwischending.
            //
            // `phase`, `stockMgdl` und `reason` stehen als benannte LUECKE,
            // wenn ein frueher Abbruch den verdrahteten EvidenceStock in
            // diesem Zyklus nicht erreicht hat. Sie hier mit 0 oder
            // "DORMANT" zu fuellen waere eine erfundene Auswertung.
            .put("evidenceEpisode", outcome.evidenceEpisodeId.takeIf { it > 0L }?.let { id ->
                JSONObject()
                    .put("id", id)
                    .put("ageMin", outcome.evidenceEpisodeMin ?: JSONObject.NULL)
                    .put("capMin", outcome.evidenceEpisodeCapMin)
                    .put("committedU", fin(outcome.evidenceCommittedU))
                    .put("creditRevoked", outcome.evidenceCreditRevoked)
                    .put("phase", outcome.evidencePhase ?: JSONObject.NULL)
                    .put("stockMgdl", outcome.evidenceStockMgdl?.let { fin(it) } ?: JSONObject.NULL)
                    .put("reason", outcome.evidenceReason ?: JSONObject.NULL)
                    .put("creditMgdlPerMin", outcome.evidenceCreditMgdlPerMin?.let { fin(it) } ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
        if (outcome.evidenceEpisodeId > 0L && outcome.evidencePhase == null)
            gap("evidenceEpisode.phase", GAP_EVIDENCE_NOT_EVALUATED)
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
                // S0: main/combined getrennt wie bei der unteren Familie drei
                // Zeilen tiefer. `predAtReleaseMgdl` und `minLowerMgdl` sind
                // ueber BEIDE Bahnen minimiert, `minMeanMgdl` war es nie - als
                // unqualifizierter Nachbar zweier kombinierter Felder gelesen,
                // ergab das wieder eine Bahn, die es nicht gibt.
                .put("minMeanMainMgdl", fin(outcome.prediction?.minMeanBg))
                .put(
                    "minMeanCombinedMgdl",
                    fin(listOfNotNull(outcome.prediction?.minMeanBg, outcome.restraint?.minMeanBg).minOrNull())
                )
                // Hat die SCHNELLE Bahn gebremst? Ohne dieses Feld ist im
                // Nachhinein nicht unterscheidbar, ob eine Zurueckhaltung aus
                // dem traegen Antrieb kam oder aus der Bremse.
                .put("restraintBound", d.restraintBound)
                // S0: und WELCHE der beiden Wirkungen es war. Die Bremse kann
                // die Sicherheitsbahn senken (blockieren) oder den Bedarf
                // kuerzen - bis hierher war beides dasselbe Bit.
                .put("restraintBoundGuard", d.restraintBoundGuard)
                .put("restraintBoundDemand", d.restraintBoundDemand)
                // Das Minimum der HAUPTbahn allein: erst zusammen mit
                // `minLowerMgdl` ist das AUSMASS der Bremswirkung ablesbar,
                // nicht nur ihr Vorhandensein.
                .put("minLowerMainMgdl", fin(d.minLowerMainMgdl))
                // S0 (I16): WIE TIEF und WIE BALD unter dem Guard-Boden. Der
                // Guard ist ein Schwellentest - eine Bahn bei 69 war im Trail
                // von einer bei -382 nicht zu unterscheiden.
                .put("floorDeficitMgdl", fin(d.floorDeficitMgdl))
                .put("timeToFloorMin", d.timeToFloorMin ?: JSONObject.NULL)
                // S0 (I16): ZWEI Zeitindizes, und sie gehoeren zu verschiedenen
                // Bahnen. `...Main` ist der der Hauptbahn, `...Combined` der
                // der Bahn, gegen die der Regler ENTSCHEIDET (Haupt UND Bremse)
                // - also der Partner von `minLowerMgdl`.
                //
                // Ein einzelnes Feld war widerspruechlich: am 10.08. stand live
                // minLower 71,17 bei Anker ~90,61 und Index 0. Beide Zahlen
                // waren richtig - die Hauptbahn hatte ihr Minimum wirklich am
                // Anker, die 71,17 kamen aus der Bremsbahn -, aber nebeneinander
                // ergaben sie eine unmoegliche Bahn.
                .put("timeToMinSafetyLowerMainMin", outcome.prediction?.timeToMinSafetyLowerMin ?: JSONObject.NULL)
                .put("timeToMinSafetyLowerCombinedMin", d.timeToMinCombinedMin ?: JSONObject.NULL)
                // S0 (K2): ALLE Mengengrenzen, nicht nur die bindende.
                // `bindingLimit` nennt bei Gleichstand die erste der Liste -
                // und mit IobThPercent = 100 sind iobTh- und maxIob-Spielraum
                // bitgenau gleich, der Gleichstand ist also der Normalfall.
                // Die Stufe, zu der die Liste gehoert. Ohne sie konnte eine
                // Basisliste neben einer Menge stehen, die eine SPAETERE Stufe
                // bestimmt hat - bei gleichen Namen unbemerkt.
                .put("capsStage", d.capsStage)
                .put("caps", JSONArray().apply {
                    d.caps.forEach {
                        put(
                            JSONObject()
                                .put("name", it.name)
                                .put("valueU", fin(it.valueU))
                                .put("active", it.active)
                        )
                    }
                })
                // FEHLTE bis 08.08. - der Schirm zeigte die Schwanz-Kosten,
                // der Trail nicht (18 bindende Zyklen der Nacht alle "0").
                .put("tailCostU", fin(d.tailCostU))
                // `reason` traegt die TBR-AKTION im Klartext - SAFETY_ZERO_NEW,
                // SAFETY_ZERO_RENEW, SAFETY_ZERO_ALREADY_RUNNING,
                // KEEP_CANCEL_STALE_ZERO, NO_POSITIVE_KEEP_NON_POSITIVE. Ohne
                // sie waere "neu, erneuert, behalten oder beendet" im Trail
                // nicht unterscheidbar (Tonis Auflage 17.08.).
                .put("reason", outcome.reason)
                .put("alarm", outcome.alarm)
        )

        // ---- DAS LOW-TOR, mit voller Rechenspur (Toni 17.08.) ---------------
        //
        // Seit dem 17.08. ist dies der EINZIGE Weg zu einer Zero-TBR. Der Block
        // steht auch bei ABGELEHNTEM Tor da, und das ist sein Zweck: eine Null,
        // die nicht kam, ist sonst von einem Zyklus ohne Befund nicht zu
        // unterscheiden. Fehlt der Block ganz, wurde das Tor in diesem Zyklus
        // nicht ausgewertet (Abbruchpfad) - auch das ist eine Aussage.
        // ---- DER ERWARTUNGS-LEDGER (Toni 18.08., Punkt 4) -------------------
        //
        // Getrennt nach den drei Kontexten, und die AKTUELLE Strecke getrennt
        // von der historischen. Nur `current.eligible` duerfte je eine
        // Adaption tragen - deshalb steht der Ablehnungsgrund daneben und
        // nicht bloss eine Null: eine 0 ohne Begruendung liesse spaeter nicht
        // unterscheiden, ob nie etwas belegt war, ob eingegriffen wurde oder
        // ob nur die Uhr weiterlief.
        expectation?.let { e ->
            o.put(
                "expectation", JSONObject()
                    .put("result", e.lastResult)
                    .put("open", e.openEntries)
                    .put("byContext", JSONObject().apply { e.byContext.forEach { (k, v) -> put(k, v) } })
                    .put("byVerdict", JSONObject().apply { e.byVerdict.forEach { (k, v) -> put(k, v) } })
                    .put("historicalStreakMin", e.historicalStreakMin)
                    .put(
                        "current", JSONObject()
                            .put("minutes", e.current.minutes)
                            .put("eligible", e.current.eligible)
                            .put("freshThroughTs", e.current.freshThroughTs ?: JSONObject.NULL)
                            .put("denial", e.current.denialReason?.name ?: JSONObject.NULL)
                            .put("currentContextReason", e.current.currentContextReason?.name ?: JSONObject.NULL),
                    )
                    .put("epoch", e.stampEpochId)
                    .put("sequence", e.stampSequence),
            )
        }
        outcome.lowThreat?.let { lt ->
            o.put(
                "lowThreat", JSONObject()
                    .put("verdict", lt.verdict.name)
                    .put("denial", lt.denial ?: JSONObject.NULL)
                    .put("fallRatePerMin", fin(lt.fallRatePerMin))
                    // AUSDRUECKLICH der Bolusanteil - das Netto-IOB steht
                    // unter state.iobU und ist eine andere Groesse.
                    .put("bolusIobU", fin(lt.bolusIobU))
                    .put("distanceToFloorMgdl", fin(lt.distanceToFloorMgdl))
                    .put("minutesToFloor", fin(lt.minutesToFloor))
                    // Was eine ab jetzt laufende Null bis zum Bodenkontakt
                    // verhindert haette. Unter der Schwelle ist sie keine
                    // Massnahme, sondern nur ein Basalverlust.
                    .put("benefitMgdl", fin(lt.benefitMgdl)),
            )
        }
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
        // ---- Patch-Epoche (B3) ---------------------------------------------
        if (patchEpoch == null) gap("patchEpoch", "NOT_REPORTED")
        else o.put(
            "patchEpoch", JSONObject()
                .put("known", patchEpoch.known)
                .put("reason", patchEpoch.reason)
                .putOpt("epochTs", patchEpoch.epochTs)
                // Ausdruecklich `JSONObject.NULL` statt Weglassen: "aktive
                // Pumpe nicht lesbar" ist eine Aussage und muss im Trail von
                // "Feld gibt es in diesem Build noch nicht" unterscheidbar
                // bleiben.
                .put("applicable", patchEpoch.applicable ?: JSONObject.NULL)
        )

        // KEINE Luecke, wenn nichts dasteht: "nie repariert" ist der Normalfall
        // und keine fehlende Angabe. Eine Luecke hier wuerde die echten
        // Luecken im Rauschen ertraenken.
        ledgerReset?.let { o.put("ledgerReset", app.aaps.fuse.plugin.ledger.FuseLedgerRepair.encode(it)) }

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
                // POST-GAP-TELEMETRIE (11.08.). Sechs Zahlen, die EINE Frage
                // beantworten, die keine von ihnen allein beantwortet: war der
                // erste Punkt nach einer Luecke fragwuerdig und wurde er kurz
                // darauf stark revidiert?
                //
                // Am 10.08. stand nach 37 min Luecke eine 90 mit FRISCHEM
                // Zeitstempel im Datensatz, drei Minuten spaeter 105 - FUSE las
                // +4,21 mg/dl/min und gab 0,85 U in ein Nicht-Ereignis. Eine
                // reine Ratenpruefung sieht das nicht: der erste Punkt hat
                // (90-105)/35 = -0,43, voellig unauffaellig. Erst
                // `postGapIndex` ZUSAMMEN mit `stepFromLastMgdl` zeigt es.
                .put("gapBeforeMin", fin(s.gapBeforeMin))
                .put("stepFromLastMgdl", fin(s.stepFromLastMgdl))
                .put("stepRateActualMgdlPerMin", fin(s.stepRateActualMgdlPerMin))
                .put("postGapIndex", s.postGapIndex)
                // Alter des Rohwerts: trennt "der Wert ist alt" von "der Wert
                // ist falsch". Aus computeTs - sourceTs, damit die Signalquelle
                // ohne Uhr auskommt.
                .put("sourceAgeMin", fin((outcome.computeTs - s.sourceTs) / 60_000.0))
                .put("q1Outlier", s.q1Outlier)
                .put("boundedBy", s.boundedBy.name)
                .put("windowFromTs", s.windowFromTs)
        )

        // ---- S0: die Bahnhub-Zerlegung -------------------------------------
        // WARUM BEIDE BAHNEN: der Guard entscheidet gegen das Minimum ueber
        // Haupt- UND Bremsbahn. Stammt es aus der Bremse, erklaert der Hub der
        // Hauptbahn den falschen Ort - derselbe Fehler, den die getrennten
        // Zeitindizes oben gerade beheben. Ein Hub allein waere hier also nicht
        // "die Haelfte", sondern irrefuehrend.
        //
        // NICHTS hiervon wird gelesen: keine Grenze, kein Zweig, keine Dosis.
        fun hub(h: app.aaps.fuse.core.predictor.TrajectoryHub?): Any =
            h?.let {
                JSONObject()
                    .put("driveMeanMgdl", fin(it.driveMeanMgdl))
                    .put("driveLowerMgdl", fin(it.driveLowerMgdl))
                    .put("driveSafetyLowerMgdl", fin(it.driveSafetyLowerMgdl))
                    .put("bgiMgdl", fin(it.bgiMgdl))
                    .put("transportMgdl", fin(it.transportMgdl))
                    // Die beiden Gewichtssummen sind der Schluessel: mit ihnen
                    // rechnet ein Auswerter den Hub eines GEDACHTEN Antriebs
                    // nach, ohne den Zerfall nachbauen zu muessen.
                    .put("decayWeightSumPositive", fin(it.decayWeightSumPositive))
                    .put("decayWeightSumNegative", fin(it.decayWeightSumNegative))
            } ?: JSONObject.NULL

        fun hubsOf(p: app.aaps.fuse.core.predictor.PredictorResult?): Any =
            p?.let {
                JSONObject()
                    .put("atHorizon", hub(it.hubAtHorizon))
                    .put("atMinSafetyLower", hub(it.hubAtMinSafetyLower))
                    .put("timeToMinSafetyLowerMin", it.timeToMinSafetyLowerMin)
            } ?: JSONObject.NULL

        // Benannt statt weggelassen (Kopfregel dieser Datei): ein blankes
        // `null` verwechselt "Abbruch vor der Bahn" mit "Bahn vorhanden".
        if (outcome.prediction == null) gap("hub", "NO_TRAJECTORY_THIS_CYCLE")
        else o.put(
            "hub", JSONObject()
                .put("main", hubsOf(outcome.prediction))
                .put("restraint", hubsOf(outcome.restraint))
        )

        val b = outcome.band
        if (b == null) gap("drive", "NO_BAND_THIS_CYCLE")
        else o.put(
            "drive", JSONObject()
                .put("mean", fin(b.mean))
                .put("lower", fin(b.lower))
                .put("spread", fin(b.spread))
                .put("pairCount", b.pairCount)
                // S0: WIRKT das Band ueberhaupt? `spread == 0` war der einzige
                // Hinweis auf ein abgeschaltetes Band und ist mehrdeutig - auch
                // ein aktives Band liefert bei entarteter Paarverteilung 0.
                // Das Flag kommt aus DEMSELBEN Praedikat wie der Zweig.
                .put("quantilePct", b.quantilePct)
                .put("bandActive", b.bandActive)
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
                // RUECKFALL AUF DIE OUTCOME-FELDER (Toni 17.08.): im
                // Abbruchzyklus ist `state` null, aber abort() kennt beide
                // Grenzen und legt sie in outcome.iobThU/maxIobU - der
                // AAPS-Tab faellt darauf zurueck (FuseDashboardModel), der
                // Export tat es nicht. Damit galt "iobTH nie verstecken" am
                // Geraet, aber nicht in der Datei, die der Viewer liest.
                .put("iobThU", fin(outcome.state?.iobThU ?: outcome.iobThU))
                .put("maxIobU", fin(outcome.state?.maxIobU ?: outcome.maxIobU))
                // Der WIRKSAME Anteil, nicht beide Rohwerte: welche Zahl gegolten hat,
                // haengt an der Phase, und im Nachhinein soll niemand die falsche
                // von zweien lesen. Die Rohwerte stehen ohnehin unter policy.values.
                .put("reboundWindow", outcome.state?.reboundWindow ?: JSONObject.NULL)
                // Rest des Rebound-Fensters [min]. Additiv, kein Schemabruch:
                // aeltere Leser sehen das Feld nicht, neuere behandeln sein
                // Fehlen als UNBEKANNT - nicht als "Fenster zu Ende".
                .put("reboundRestMin", outcome.state?.reboundRestMin ?: JSONObject.NULL)
                // NACHTFENSTER, bisher unsichtbar (Toni 15.08. 23:14): der
                // Runner setzt es korrekt, aber im Trail stand es nirgends -
                // ob das Nacht-Totband scharf war, liess sich nur aus der Uhr
                // und der Konfiguration herleiten. Genau diese Herleitung will
                // niemand nachts machen. Die uebrigen Fenster (rebound,
                // marker, meal) stehen laengst da; dieses fehlte als einziges.
                .put("nightWindow", outcome.state?.nightWindow ?: JSONObject.NULL)
                .put("reboundSuppressedByMarker", outcome.state?.reboundSuppressedByMarker ?: JSONObject.NULL)
                // DER DRUCKZEITPUNKT, nicht nur "Marker aktiv".
                //
                // Bisher stand im Trail ausschliesslich das Boolean
                // `drive.onset.mealMarker`. Damit ist die wichtigste Groesse im
                // FCL gar nicht messbar: die LATENZ vom Knopfdruck bis zur
                // ersten Freigabe. Ob nach 2 oder nach 25 Minuten das erste
                // Insulin kommt, entscheidet ueber Peak oder kein Peak - und
                // ohne t0 laesst sich das im Nachhinein nicht ausrechnen.
                // 0 = kein Marker.
                .put("markerArmedTs", outcome.state?.markerArmedTs ?: JSONObject.NULL)
                .put("markerNoPrime", outcome.state?.markerNoPrime ?: JSONObject.NULL)
                .put("markerBoost", outcome.state?.markerBoost ?: JSONObject.NULL)
                .put("mealWindow", outcome.state?.mealWindow ?: JSONObject.NULL)
                .put("smbRatioEffective", fin(outcome.state?.effectiveSmbRatio))
                // S0: der EINGANG der Rampe, nicht das Signal. Beide sind
                // meistens dieselbe Zahl - aber wenn der OnsetChannel aktiv
                // ist, gilt `max(rSigned, onsetDrive)`, und dann war der
                // Rampeneingang ein anderer als das exportierte `signal.rSigned`.
                // Ohne dieses Feld ist der wirksame Anteil im Nachhinein nicht
                // herleitbar; die Differenz zu `signal.rSigned` ist genau der
                // Beitrag des Onset-Kanals.
                .put("rampInputMgdlPerMin", fin(outcome.state?.rSignedMgdlPerMin))
                .put("context", outcome.decision.context?.name ?: JSONObject.NULL)
        )

        // ---- Schwanz -------------------------------------------------------
        // DIE KOSTEN DER ZIRKULARITAET, beziffert (11.08.).
        //
        // `unconditional` ist die Kante ohne Kohlenhydrate - gegen sie hat der
        // Schwanz bisher immer gerechnet und am 10.08. 25 Minuten am Stueck
        // gesperrt. `conditional` ist dieselbe Kante MIT der Ankuendigung.
        // Ihre Differenz geteilt durch den Schwanz-ISF ist das Budget, das die
        // Ankuendigung verschafft - die Zahl, an der sich die bedingte Bahn
        // messen lassen muss.
        //
        // `conditional` null heisst: kein Kredit lief oder der Schalter ist aus.
        o.put(
            "tailLower", JSONObject()
                .put("unconditionalMgdl", fin(outcome.tailLowerUnconditionalMgdl))
                .put("conditionalMgdl", fin(outcome.tailLowerConditionalMgdl))
                // JE BAHN EINZELN (Livebefund 11.08.). Nur die kombinierten
                // Werte zu zeigen war zu wenig: die Hebung der Hauptbahn wurde
                // von der unbedingten Bremsbahn kassiert, und im Export sahen
                // beide Zahlen einfach gleich aus - ohne Hinweis darauf, WO die
                // Hebung verlorenging.
                .put("mainUncondMgdl", fin(outcome.tailLowerMainUncondMgdl))
                .put("mainCondMgdl", fin(outcome.tailLowerMainCondMgdl))
                .put("restraintUncondMgdl", fin(outcome.tailLowerRestraintUncondMgdl))
                .put("restraintCondMgdl", fin(outcome.tailLowerRestraintCondMgdl))
        )

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
                            // WARUM eine Zeile entlastet wurde - ohne diese
                            // Angabe waere der Entlastungsweg am Geraet
                            // unsichtbar, und ein falsch feuernder Beleg
                            // fiele erst an der Dosis auf (s. NotSentProof).
                            .put("queueReject", e.queueReject?.name ?: JSONObject.NULL)
                    }))
                    // Wieviele Zeilen OHNE IOB-Nachweis freigegeben wurden.
                    .put("provenNotSentCount", ls.entries.values.count { it.debtFreeingReject })
                    // DIE DURABILITAET ALS ZAHLEN. Ohne sie ist am Geraet nicht
                    // zu sehen, ob der fsync ueberhaupt laeuft und was er kostet -
                    // und ein FEHLGESCHLAGENER Persist waere voellig unsichtbar.
                    .put("persist", ledger.persist?.let { p ->
                        JSONObject()
                            .put("outcome", p.outcome.name)
                            .put("bytes", p.bytes)
                            .put("totalMs", p.totalMs)
                            .put("fileSyncMs", p.fileSyncMs)
                            .put("dirSyncMs", p.dirSyncMs)
                    } ?: JSONObject.NULL)
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
        // SCHEMAVERSION. Sie kommt, weil in dieser Serie wirklich ein
        // Schluessel umbenannt wurde (`timeToMinSafetyLowerMin` ->
        // `...MainMin`): EINE Trail-Datei traegt beide Schreibweisen, und
        // ohne Marke ist einer Zeile nicht anzusehen, welche gilt.
        //  2 = S0-A/B/C (10.08.), Schluessel `timeToMinSafetyLowerMin`
        //  3 = ab 11.08.: MainMin/CombinedMin, minMean* getrennt, capsStage
        //  4 = ab 11.08.: priorActuation (fusePublished/afterBolusConstraints/
        //      aapsConstrained des VORIGEN Zyklus) + Post-Gap-Felder
        o.put("schemaVersion", SCHEMA_VERSION)
        // SCHEIBE 1: der Ausgang des VORIGEN Zyklus.
        //
        // `ofComputeTs` steht ganz vorn und ist nicht optional: diese Zahlen
        // beschreiben NICHT diesen Datensatz. FUSEs invoke kehrt zurueck, bevor
        // AAPS seine Constraints anwendet - lesbar ist der eigene Ausgang
        // deshalb erst einen Zyklus spaeter. Ohne die Herkunft daneben waere
        // das genau die Fehlbenennung, gegen die die ganze Mengenachse gebaut
        // wird.
        //
        // DIE ACHSE IST FUENFTEILIG:
        //   certified -> fusePublished -> aapsConstrained -> queueRequested -> enacted
        // Hier sind die ersten drei. `queueRequestedU` ist heute NICHT
        // beobachtbar: `constraintsProcessed` entsteht VOR Suspend-, Loop- und
        // Queue-Pruefungen, TBR-Ausfuehrung, applySMBRequest, dem zweiten
        // Intervalltor und CommandSMBBolus.
        o.put("priorActuation", priorActuation?.let { p ->
            JSONObject()
                .put("ofComputeTs", p.ofComputeTs)
                // Identitaet der RT-INSTANZ, nicht der APSResult-Huelle.
                // false heisst: fremder oder veralteter lastRun, die Zahlen
                // sind dann bewusst null statt irgendetwas.
                .put("correlated", p.correlated)
                .put("fusePublishedU", fin(p.fusePublishedU))
                .put("afterBolusConstraintsU", fin(p.afterBolusConstraintsU))
                .put("aapsConstrainedU", fin(p.aapsConstrainedU))
        } ?: JSONObject.NULL)
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
        .put("primeWindowMin", p.primeWindowMin)
        // Ohne diese Zeile waere hinterher nicht belegbar, OB der Schalter in
        // einem Lauf an war - genau die Luecke, die heute schon zweimal
        // aufgefallen ist (basalIobU, MarkerAuthorisesRelease). Ein Schalter,
        // der das Aktuationsverhalten aendert, gehoert in den Trail.
        .put("endZeroWhenReasonGone", p.endZeroWhenReasonGone)

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
                p.iobThPercent, p.releaseHorizonMin, p.liabilityHorizonMin, p.primeWindowMin,
                p.driveTauMin, p.driveLowerQuantilePct, p.tailGuardEnabled, p.fastRestraintEnabled, p.onsetChannelEnabled, p.primeReleaseEnabled,
                // v3: der Null-Ausgang aendert das Aktuationsverhalten - zwei
                // Laeufe mit verschiedener Stellung duerfen nicht denselben
                // Hash tragen.
                p.endZeroWhenReasonGone,
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

    /**
     * WAS VOM ERWARTUNGS-LEDGER IN DEN EXPORT GEHT.
     *
     * Als eigene Struktur statt vieler Einzelparameter, damit der Aufrufer
     * sie an EINER Stelle baut und nicht Zahl fuer Zahl durchreicht - dabei
     * gehen erfahrungsgemaess einzelne verloren, und ein fehlendes Feld sieht
     * im Export aus wie eine Aussage.
     */
    data class Expectation(
        val lastResult: String,
        val openEntries: Int,
        /** Wie viele abgerechnete Ergebnisse je Kontext - CORRECTION, MEAL,
         *  EXCLUDED. Die Trennung ist der ganze Zweck des Kontexts. */
        val byContext: Map<String, Int>,
        val byVerdict: Map<String, Int>,
        /** Was IRGENDWANN belegt war. NIE als Dosiernachweis lesen. */
        val historicalStreakMin: Int,
        /** Was JETZT gilt - das einzige, was je eine Adaption tragen duerfte. */
        val current: app.aaps.fuse.core.controller.ExpectationLedger.LambdaEvidence,
        val stampEpochId: String,
        val stampSequence: Long,
    )
}
