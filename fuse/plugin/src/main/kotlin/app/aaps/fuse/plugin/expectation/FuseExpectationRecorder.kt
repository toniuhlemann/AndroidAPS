package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.InterventionStamp
import java.io.File

/**
 * DIE BUCHFUEHRUNG DES ERWARTUNGS-LEDGERS - rein beobachtend.
 *
 * Sie reiht die Prognose dieses Zyklus ein, rechnet faellige Erwartungen
 * gegen den passenden Messpunkt ab und schreibt den vollstaendigen Zustand
 * atomar fort. Sie beruehrt KEINE Dosierung: es gibt keinen Rueckgabewert,
 * den irgendein Regelpfad liest, und keinen Weg von hier zu lambda.
 *
 * WARUM EIN EIGENER BAUSTEIN und kein Block in `FusePlugin.invoke`: dort
 * stuende er zwischen Ledger-Ereignissen und Publikation, also im
 * sicherheitsrelevantesten Abschnitt des Zyklus. Ein Messbaustein, der dort
 * werfen oder blockieren kann, waere ein Risiko fuer die Dosierung - und
 * genau deshalb faengt [record] ausnahmslos alles ab und meldet den Fehler,
 * statt ihn nach oben zu geben.
 *
 * REIHENFOLGE IM ZYKLUS: [record] laeuft NACH dem Publikations-Gate. Das ist
 * keine Bequemlichkeit, sondern der Kern der Semantik - erst danach steht der
 * Eingriffsstempel fest, unter dem die Prognose dieses Zyklus gilt und unter
 * dem die Messwerte dieses Zyklus gesehen wurden.
 */
class FuseExpectationRecorder(
    private val store: FuseExpectationStore = FuseExpectationStore(),
) {

    /**
     * Der Zustand im Speicher - die Wahrheit steht in der Datei, das hier ist
     * die Arbeitskopie eines Laufs.
     */
    var state: ExpectationLedger.State = ExpectationLedger.State.empty()
        private set

    /** Generationsnummer der Datei, monoton je Schreibvorgang. */
    var revision: Long = 0L
        private set

    private var geladen = false

    /** Was beim letzten [record] geschah - fuer Trail und Export. */
    var lastResult: Result = Result.NotLoaded
        private set

    sealed interface Result {

        /** Noch nie geladen - vor dem ersten Zyklus der Normalzustand. */
        data object NotLoaded : Result

        /** Dieser Zyklus hat gebucht. [issued] = eine neue Erwartung wurde
         *  eingereiht, [settled] = so viele wurden abgerechnet. */
        data class Recorded(val issued: Boolean, val settled: Int, val persisted: Boolean) : Result

        /** Bewusst uebersprungen - mit Grund. */
        data class Skipped(val reason: String) : Result

        /** Etwas ging schief. Der Zyklus laeuft weiter; nur die Messung
         *  dieses Zyklus fehlt. */
        data class Failed(val reason: String) : Result
    }

    /**
     * EINMAL JE PROZESS LADEN.
     *
     * @param kopfstand der aktuelle Eingriffsstempel aus dem
     *   Publikationsledger - die Autoritaet liegt dort. Fehlt sie oder ist sie
     *   ungueltig, laedt gar nichts: ohne bekannte Herkunft ist jeder
     *   gespeicherte Eintrag unbewertbar.
     * @return `false`, wenn ein DATENVERLUST vorlag (der Aufrufer soll das
     *   melden koennen) - nicht bei einem gewoehnlichen Erststart.
     */
    fun loadOnce(dir: File, kopfstand: InterventionStamp): Boolean {
        if (geladen) return true
        geladen = true
        if (!kopfstand.valid) {
            lastResult = Result.Failed("ungueltiger Kopfstand beim Laden")
            return false
        }
        return when (val g = runCatching { store.load(dir, kopfstand) }.getOrNull()) {
            is FuseExpectationStore.Loaded.Ok      -> {
                state = g.state
                revision = g.revision
                true
            }

            FuseExpectationStore.Loaded.Fresh      -> true

            is FuseExpectationStore.Loaded.Corrupt -> {
                // LEER WEITERLAUFEN, ABER NICHT SCHWEIGEN. Der Streak beginnt
                // neu; das ist verkraftbar. Unbemerkt zu bleiben waere es
                // nicht - dann saehe eine kurze Strecke spaeter aus wie eine
                // ehrliche und nicht wie der Rest einer verlorenen.
                lastResult = Result.Failed("Generation beschaedigt: ${g.reason}")
                false
            }

            null                                   -> {
                lastResult = Result.Failed("Laden warf")
                false
            }
        }
    }

    /**
     * EINEN ZYKLUS BUCHEN.
     *
     * @param anchorMgdl der aktuelle Messwert.
     * @param meanPredictedMgdl die Mittelbahn am Horizont - das Versprechen.
     *   `null`, wenn dieser Zyklus keine Bahn hat; dann wird nur abgerechnet.
     * @param samples die Messpunkte, die dieser Zyklus gesehen hat. Sie
     *   tragen den Stempel, der JETZT gilt - nach dem Gate.
     */
    fun record(
        dir: File,
        nowTs: Long,
        situation: ExpectationLedger.Situation?,
        stamp: InterventionStamp,
        configGeneration: String,
        segmentId: Long,
        sourceTs: Long,
        anchorMgdl: Double?,
        meanPredictedMgdl: Double?,
        horizonMin: Int,
        safetyLowerPredictedMgdl: Double?,
        lambda: Double?,
        samples: List<ExpectationLedger.Sample>,
    ): Result {
        val ergebnis = runCatching {
            if (!geladen) return@runCatching Result.Skipped("noch nicht geladen")
            if (!stamp.valid) return@runCatching Result.Skipped("kein gueltiger Eingriffsstempel")
            if (configGeneration.isBlank()) return@runCatching Result.Skipped("keine Konfigurationskennung")

            // OHNE LAGE WIRD NICHTS EINGEREIHT. `null` heisst hier nicht
            // "egal", sondern "unbekannt" - und eine Behauptung ohne bekannte
            // Lage waere spaeter nicht einzuordnen.
            val klasse = situation?.let { ExpectationLedger.classify(it) }
            val neu = if (klasse == null || anchorMgdl == null || meanPredictedMgdl == null) null
            else ExpectationLedger.issue(
                sourceTs = sourceTs,
                segmentId = segmentId,
                anchorMgdl = anchorMgdl,
                meanPredictedMgdl = meanPredictedMgdl,
                horizonMin = horizonMin,
                configGeneration = configGeneration,
                interventionStamp = stamp,
                classification = klasse,
                safetyLowerPredictedMgdl = safetyLowerPredictedMgdl,
                lambda = lambda,
            )

            val vorher = state.outcomes.size
            state = ExpectationLedger.advance(state, nowTs, neu, samples)
            val abgerechnet = (state.outcomes.size - vorher).coerceAtLeast(0)
            val geschrieben = store.save(dir, state, ++revision, stamp)
            Result.Recorded(issued = neu != null, settled = abgerechnet, persisted = geschrieben)
        }.getOrElse { Result.Failed("record warf: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
        lastResult = ergebnis
        return ergebnis
    }

    /**
     * Die aktuelle, zeit- und lagegebundene Strecke - das EINZIGE, was je eine
     * Adaption tragen duerfte.
     */
    fun currentEvidence(
        nowTs: Long,
        stamp: InterventionStamp,
        configGeneration: String,
        segmentId: Long,
        situation: ExpectationLedger.Situation?,
        minSafetyMarginMgdl: Double,
    ): ExpectationLedger.LambdaEvidence {
        val klasse = situation?.let { ExpectationLedger.classify(it) }
            ?: return ExpectationLedger.LambdaEvidence.denied(
                ExpectationLedger.Denial.CONTEXT_NOT_CORRECTION,
            )
        return ExpectationLedger.currentLambdaEvidence(
            state.outcomes, nowTs, stamp, configGeneration, segmentId, klasse, minSafetyMarginMgdl,
        )
    }

    /** Was IRGENDWANN belegt war - fuer den Export, nie als Dosiernachweis. */
    fun historicalStreakMin(segmentId: Long, minSafetyMarginMgdl: Double): Int =
        ExpectationLedger.historicalLambdaStreakMin(state.outcomes, segmentId, minSafetyMarginMgdl)

    /**
     * DER EXPORTSCHNAPPSCHUSS - eine Stelle, die alle Zahlen zusammenstellt.
     *
     * Sie liegt hier und nicht im Plugin, weil hier der Zustand liegt. Ein
     * Aufrufer, der sich die Zaehlungen selbst aus `state` zusammensucht,
     * baute dieselbe Auswertung ein zweites Mal - und die beiden liefen mit
     * dem naechsten Feld auseinander.
     */
    fun exportSnapshot(
        nowTs: Long,
        stamp: InterventionStamp,
        configGeneration: String,
        segmentId: Long,
        situation: ExpectationLedger.Situation?,
        minSafetyMarginMgdl: Double,
    ) = app.aaps.fuse.plugin.export.FuseStateJson.Expectation(
        lastResult = lastResult.toString(),
        openEntries = state.entries.size,
        byContext = state.outcomes.groupingBy { it.entry.context.name }.eachCount(),
        byVerdict = state.outcomes.groupingBy { it.verdict.name }.eachCount(),
        historicalStreakMin = historicalStreakMin(segmentId, minSafetyMarginMgdl),
        current = currentEvidence(nowTs, stamp, configGeneration, segmentId, situation, minSafetyMarginMgdl),
        stampEpochId = stamp.epochId,
        stampSequence = stamp.sequence,
    )
}
