package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.InterventionStamp
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * DIE BUCHFUEHRUNG DES ERWARTUNGS-LEDGERS - rein beobachtend, und ASYNCHRON.
 *
 * Sie reiht die Prognose eines Zyklus ein, rechnet faellige Erwartungen gegen
 * den passenden Messpunkt ab und schreibt den Zustand atomar fort. Kein
 * Regelpfad liest ihren Zustand, und es gibt keinen Weg von hier zu lambda.
 *
 * WARUM SIE NICHT IM LOOP-THREAD SCHREIBT (Toni 18.08.). Der erste Wurf tat
 * das. Ein Verschieben hinter `lastAPSResult` und
 * `EventAPSCalculationFinished` half NICHT: `LoopPlugin` ruft
 * `usedAPS.invoke(...)` SYNCHRON auf und liest das Ergebnis erst nach dessen
 * Rueckkehr (LoopPlugin.kt:483-485), wendet dann Constraints an und aktuiert.
 * Das Ereignis ist eine Benachrichtigung, keine Uebergabe an den
 * Aktuationspfad. Alles, was innerhalb von `invoke` Zeit kostet, verzoegert
 * die Pumpenansteuerung - auch wenn es die Menge laengst nicht mehr aendern
 * kann. Bei fsync sind das auf Android leicht 100-300 ms, in jedem Zyklus.
 *
 * DESHALB: [submit] baut einen unveraenderlichen Schnappschuss, legt ihn in
 * eine BEGRENZTE Warteschlange und kehrt sofort zurueck. Ist die Schlange
 * voll, gilt der Zyklus als VERLOREN - der Loop wartet nie, unter keinen
 * Umstaenden.
 *
 * EIN VERLORENER ZYKLUS KOSTET NACHWEIS, NIEMALS INSULIN. Er hinterlaesst
 * eine sichtbare Luecke ([Telemetry.dropped]) und unterbricht damit jede
 * laufende Strecke - die richtige Folge, denn ueber eine nicht beobachtete
 * Minute laesst sich nichts behaupten.
 *
 * DIE AUSWERTUNG LIEST NUR, WAS AUF PLATTE STEHT. [persistedState] wird erst
 * nach einem NACHGEWIESENEN Schreibvorgang gesetzt; der Stand im Worker ist
 * Zwischenstand. Eine Strecke aus ungeschriebenen Ergebnissen saehe nach
 * einem Prozesstod anders aus als vorher - genau solche Nachweise sollen hier
 * nicht entstehen.
 */
class FuseExpectationRecorder(
    private val store: FuseExpectationStore = FuseExpectationStore(),
    /**
     * Wie viele Zyklen hoechstens warten duerfen.
     *
     * Klein gewaehlt, und das ist Absicht: eine grosse Schlange verschleppt
     * den Rueckstand, statt ihn zu zeigen. Vier Zyklen sind vier Minuten -
     * laenger darf die Persistenz nicht hinterherhinken, ohne dass es im
     * Export auffaellt.
     */
    private val queueCapacity: Int = 4,
) {

    /**
     * WAS EIN ZYKLUS ZU BUCHEN GIBT - unveraenderlich, damit der Worker nichts
     * liest, was sich unter ihm noch aendert.
     */
    data class Snapshot(
        val dir: File,
        val nowTs: Long,
        val situation: ExpectationLedger.Situation?,
        val stamp: InterventionStamp,
        val configGeneration: String,
        val segmentId: Long,
        val sourceTs: Long,
        val anchorMgdl: Double?,
        val meanPredictedMgdl: Double?,
        val horizonMin: Int,
        val safetyLowerPredictedMgdl: Double?,
        val lambda: Double?,
        val samples: List<ExpectationLedger.Sample>,
    )

    /** Was der Export ueber die Buchfuehrung selbst zeigen muss. */
    data class Telemetry(
        val queueDepth: Int = 0,
        /** Seit Prozessstart verworfene Zyklen - jeder davon ist eine Luecke. */
        val dropped: Long = 0L,
        /** Stand des zuletzt NACHGEWIESEN geschriebenen Zustands. 0 = keiner. */
        val asOfTs: Long = 0L,
        val bytes: Int = 0,
        val durationMs: Long = 0L,
        val lastResult: String = "NOT_LOADED",
    )

    /**
     * DER ZULETZT NACHGEWIESEN GESCHRIEBENE ZUSTAND - die einzige Quelle fuer
     * jede Auswertung.
     */
    @Volatile
    var persistedState: ExpectationLedger.State = ExpectationLedger.State.empty()
        private set

    @Volatile
    var telemetry: Telemetry = Telemetry()
        private set

    /** Nur vom Worker beruehrt - kein anderer Thread liest oder schreibt sie. */
    private var workerState: ExpectationLedger.State = ExpectationLedger.State.empty()
    private var revision: Long = 0L
    private var geladen = false

    private val queue = ArrayBlockingQueue<Snapshot>(queueCapacity)
    private val verworfen = AtomicLong(0)
    private val angenommenZaehler = AtomicLong(0)
    private val verarbeitetZaehler = AtomicLong(0)
    @Volatile private var worker: Thread? = null

    /**
     * EINEN ZYKLUS ZUR BUCHUNG UEBERGEBEN - kehrt sofort zurueck.
     *
     * @return `false`, wenn die Schlange voll war und dieser Zyklus verworfen
     *   wurde. Der Aufrufer darf das melden, muss aber nichts tun -
     *   insbesondere nicht warten.
     */
    fun submit(snapshot: Snapshot): Boolean {
        starteWorker()
        val angenommen = queue.offer(snapshot)
        if (angenommen) angenommenZaehler.incrementAndGet() else verworfen.incrementAndGet()
        telemetry = telemetry.copy(queueDepth = queue.size, dropped = verworfen.get())
        return angenommen
    }

    private fun starteWorker() {
        if (worker != null) return
        synchronized(this) {
            if (worker != null) return
            // DAEMON: der Prozess darf jederzeit enden, ohne auf die Messung zu
            // warten. Ein Beobachter haelt nichts am Leben. MIN_PRIORITY, damit
            // die Buchfuehrung dem Loop keine Rechenzeit wegnimmt, wenn es eng
            // wird.
            worker = Thread({ workerLoop() }, "fuse-expectation").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun workerLoop() {
        while (true) {
            val s = runCatching { queue.poll(5, TimeUnit.SECONDS) }.getOrNull() ?: continue
            runCatching { verarbeite(s) }.onFailure {
                melde("FAILED:${it.javaClass.simpleName}", 0, 0L)
            }
            // ERST NACH der Verarbeitung hochzaehlen - sonst gilt ein
            // Schnappschuss als fertig, sobald er die Schlange verlassen hat.
            verarbeitetZaehler.incrementAndGet()
        }
    }

    /** Laeuft AUSSCHLIESSLICH im Worker-Thread. */
    private fun verarbeite(s: Snapshot) {
        if (!geladen) {
            geladen = true
            ladeVonPlatte(s.dir, s.stamp)
        }
        if (!s.stamp.valid || s.configGeneration.isBlank()) {
            melde("SKIPPED:keine gueltige Herkunft", 0, 0L)
            return
        }
        // OHNE LAGE WIRD NICHTS EINGEREIHT. `null` heisst nicht "egal",
        // sondern "unbekannt" - eine Behauptung ohne bekannte Lage waere
        // spaeter nicht einzuordnen.
        val klasse = s.situation?.let { ExpectationLedger.classify(it) }
        val neu = if (klasse == null || s.anchorMgdl == null || s.meanPredictedMgdl == null) null
        else ExpectationLedger.issue(
            sourceTs = s.sourceTs, segmentId = s.segmentId, anchorMgdl = s.anchorMgdl,
            meanPredictedMgdl = s.meanPredictedMgdl, horizonMin = s.horizonMin,
            configGeneration = s.configGeneration, interventionStamp = s.stamp,
            classification = klasse, safetyLowerPredictedMgdl = s.safetyLowerPredictedMgdl,
            lambda = s.lambda,
        )
        val vorher = workerState.outcomes.size
        workerState = ExpectationLedger.advance(workerState, s.nowTs, neu, s.samples)
        val abgerechnet = (workerState.outcomes.size - vorher).coerceAtLeast(0)
        val stats = store.saveWithStats(s.dir, workerState, ++revision, s.stamp)
        if (stats.ok) {
            // ERST JETZT ist der Stand auswertbar.
            persistedState = workerState
            telemetry = telemetry.copy(asOfTs = s.nowTs)
        }
        melde(
            "RECORDED:issued=${neu != null},settled=$abgerechnet,persisted=${stats.ok}",
            stats.bytes, stats.durationMs,
        )
    }

    private fun ladeVonPlatte(dir: File, kopfstand: InterventionStamp) {
        if (!kopfstand.valid) {
            melde("FAILED:ungueltiger Kopfstand", 0, 0L)
            return
        }
        when (val g = runCatching { store.load(dir, kopfstand) }.getOrNull()) {
            is FuseExpectationStore.Loaded.Ok      -> {
                workerState = g.state
                persistedState = g.state
                revision = g.revision
            }

            FuseExpectationStore.Loaded.Fresh      -> Unit

            is FuseExpectationStore.Loaded.Corrupt ->
                // LEER WEITERLAUFEN, ABER NICHT SCHWEIGEN. Der Streak beginnt
                // neu; unbemerkt zu bleiben waere schlimmer, denn dann saehe
                // eine kurze Strecke spaeter aus wie eine ehrliche.
                melde("FAILED:Generation beschaedigt", 0, 0L)

            null                                   -> melde("FAILED:Laden warf", 0, 0L)
        }
    }

    private fun melde(ergebnis: String, bytes: Int, dauerMs: Long) {
        telemetry = telemetry.copy(
            queueDepth = queue.size, dropped = verworfen.get(),
            bytes = bytes, durationMs = dauerMs, lastResult = ergebnis,
        )
    }

    /**
     * Die aktuelle, zeit- und lagegebundene Strecke - aus dem PERSISTIERTEN
     * Zustand, nie aus dem Zwischenstand des Workers.
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
            persistedState.outcomes, nowTs, stamp, configGeneration, segmentId, klasse, minSafetyMarginMgdl,
        )
    }

    /** Was IRGENDWANN belegt war - fuer den Export, nie als Dosiernachweis. */
    fun historicalStreakMin(segmentId: Long, minSafetyMarginMgdl: Double): Int =
        ExpectationLedger.historicalLambdaStreakMin(persistedState.outcomes, segmentId, minSafetyMarginMgdl)

    /**
     * DER EXPORTSCHNAPPSCHUSS - eine Stelle, die alle Zahlen zusammenstellt.
     *
     * Sie liegt hier, weil hier der Zustand liegt. Ein Aufrufer, der sich die
     * Zaehlungen selbst zusammensucht, baute dieselbe Auswertung ein zweites
     * Mal - und die beiden liefen mit dem naechsten Feld auseinander.
     */
    fun exportSnapshot(
        nowTs: Long,
        stamp: InterventionStamp,
        configGeneration: String,
        segmentId: Long,
        situation: ExpectationLedger.Situation?,
        minSafetyMarginMgdl: Double,
    ): app.aaps.fuse.plugin.export.FuseStateJson.Expectation {
        val z = persistedState
        val t = telemetry
        return app.aaps.fuse.plugin.export.FuseStateJson.Expectation(
            lastResult = t.lastResult,
            openEntries = z.entries.size,
            byContext = z.outcomes.groupingBy { it.entry.context.name }.eachCount(),
            byVerdict = z.outcomes.groupingBy { it.verdict.name }.eachCount(),
            historicalStreakMin = historicalStreakMin(segmentId, minSafetyMarginMgdl),
            current = currentEvidence(nowTs, stamp, configGeneration, segmentId, situation, minSafetyMarginMgdl),
            stampEpochId = stamp.epochId,
            stampSequence = stamp.sequence,
            // ROHGROESSEN, unbeeinflusst von EXPORT_SAFETY_MARGIN_MGDL (Toni
            // 18.08.): "sonst praegt diese vorlaeufige Marge bereits die
            // Datenauswertung". Ein spaeterer Sweep ueber verschiedene Margen
            // braucht die Abstaende selbst, nicht das Ergebnis einer Schwelle,
            // die zum Zeitpunkt der Messung geraten war.
            samples = z.outcomes.takeLast(RAW_EXPORT_LIMIT).map { o ->
                app.aaps.fuse.plugin.export.FuseStateJson.ExpectationSample(
                    dueTs = o.entry.dueTs,
                    context = o.entry.context.name,
                    verdict = o.verdict.name,
                    meanErrorMgdl = o.meanErrorMgdl,
                    distanceFromSafetyLowerMgdl = o.distanceFromSafetyLowerMgdl,
                    lambda = o.entry.lambda,
                )
            },
            writeBytes = t.bytes,
            writeDurationMs = t.durationMs,
            queueDepth = t.queueDepth,
            droppedCycles = t.dropped,
            // WIE ALT DER AUSGEWERTETE STAND IST. Ohne diese Zahl saehe ein
            // Rueckstau aus wie ein ruhiger Zyklus - die Strecke stuende still,
            // und niemand wuesste warum.
            asOfTs = t.asOfTs,
        )
    }

    /**
     * NUR FUER TESTS: wartet, bis die Schlange abgearbeitet ist.
     *
     * Im Betrieb wartet auf diesen Baustein ausdruecklich niemand - das ist
     * sein ganzer Sinn.
     */
    internal fun awaitIdleForTest(timeoutMs: Long = 5_000L): Boolean {
        val ende = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < ende) {
            if (verarbeitetZaehler.get() >= angenommenZaehler.get()) return true
            Thread.sleep(5)
        }
        return false
    }

    companion object {

        /**
         * Wie viele Rohergebnisse in den Export gehen.
         *
         * Genug fuer eine Nachtstrecke, wenig genug, dass der Zyklusexport
         * nicht aufgeblaeht wird - er wird jede Minute geschrieben. Die
         * vollstaendige Reihe steht ohnehin in der Ledgerdatei.
         */
        const val RAW_EXPORT_LIMIT = 60
    }
}
