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

    /**
     * DIE TELEMETRIE IST EINE BERECHNETE SICHT, KEIN GESPEICHERTER ZUSTAND
     * (Toni 18.08.).
     *
     * Der erste Wurf hielt ein `@Volatile`-Feld und ersetzte es mit
     * `telemetry.copy(...)`. Das ist ein Lese-Aendern-Schreiben ueber mehrere
     * Felder - Producer (submit, im Loop-Thread) und Worker taten es
     * gleichzeitig, und dabei gingen Schreibdauer, `asOfTs` oder Drop-Zaehler
     * verloren. Ausgerechnet die LASTMESSUNG waere damit unzuverlaessig
     * gewesen, also genau die Zahl, wegen der es den Schalter gibt.
     *
     * Jetzt haelt jedes Feld seinen eigenen atomaren Zaehler, und diese Sicht
     * liest sie zusammen. Ein einzelner Aufruf kann noch einen Moment
     * zwischen zwei Aktualisierungen erwischen - das ist eine leicht
     * gemischte Momentaufnahme, aber kein verlorener Wert.
     */
    val telemetry: Telemetry
        get() = Telemetry(
            queueDepth = queue.size,
            dropped = verworfen.get(),
            asOfTs = asOfTsAtomic.get(),
            bytes = bytesAtomic.get(),
            durationMs = durationMsAtomic.get(),
            lastResult = lastResultAtomic.get(),
        )

    private val asOfTsAtomic = AtomicLong(0L)
    private val bytesAtomic = java.util.concurrent.atomic.AtomicInteger(0)
    private val durationMsAtomic = AtomicLong(0L)
    private val lastResultAtomic = java.util.concurrent.atomic.AtomicReference("NOT_LOADED")

    /**
     * ES GIBT KEINEN ZWISCHENSTAND MEHR (Toni 18.08., P0-1).
     *
     * Der erste Wurf hielt einen `workerState`, der VOR dem Schreiben
     * fortgeschrieben wurde. Scheiterte der Persist, blieb dieser
     * unbestaetigte Stand im Worker - und landete beim naechsten
     * erfolgreichen Zyklus doch auf Platte. Ein Ergebnis, das nie
     * nachgewiesen geschrieben wurde, waere so nachtraeglich Teil einer
     * Nachweisstrecke geworden.
     *
     * Jetzt ist [persistedState] die EINZIGE Basis: jeder Kandidat entsteht
     * daraus, und nur ein nachgewiesener Schreibvorgang ersetzt sie. Ein
     * gescheiterter Zyklus ist damit vollstaendig folgenlos - er hinterlaesst
     * keine Spur, die spaeter wiederauflebt.
     */
    private var revision: Long = 0L
    private var geladen = false

    /**
     * KLEBENDE SPERRE nach einer beschaedigten Generation (Toni 18.08., P0-3).
     *
     * Ohne sie liefe der Worker mit leerem Zustand weiter und schriebe die
     * naechste Generation ueber die beschaedigte - der Datenverlust waere
     * danach nicht mehr nachweisbar. Aufgehoben wird sie nur durch eine
     * ausdrueckliche Reparatur (heute: Loeschen der Datei), nicht durch
     * Zeitablauf und nicht durch einen Neustart.
     */
    private var blockiert: String? = null

    /**
     * WANN ZULETZT EIN ZYKLUS UNBEOBACHTET BLIEB (P0-2).
     *
     * Wird beim Verwerfen gesetzt und mit dem naechsten erfolgreichen
     * Schreibvorgang persistiert. Ergebnisse von VOR dieser Marke gehoeren
     * nicht mehr zur aktuellen Strecke - eine fehlende Minute faellt der
     * Abstandspruefung sonst gar nicht auf (zwei statt einer Minute Abstand,
     * erlaubt sind fuenf).
     */
    /**
     * ATOMAR, nicht `@Volatile` (Toni 18.08.).
     *
     * `@Volatile` macht `maxOf(lesen, neu)` nicht unteilbar: ein Queue-Drop im
     * Loop-Thread und das Laden im Worker koennen sich gegenseitig
     * ueberschreiben - und ausgerechnet die Lueckenmarke duerfte nie
     * zurueckfallen.
     */
    private val lastObservationGapTs = AtomicLong(0L)

    /** Nur fuer Tests: die Luecke muss NACHWEISBAR markiert sein, nicht nur
     *  gezaehlt - sonst laeuft die Strecke ueber sie hinweg. */
    internal val lastObservationGapTsForTest: Long get() = lastObservationGapTs.get()

    /**
     * WIE VIELE ERGEBNISSE DIE KAPPUNG SEIT PROZESSSTART ENTFERNT HAT.
     *
     * Monoton. Ohne diese Zahl saehe eine gekappte Strecke aus wie eine
     * vollstaendig beobachtete - `size >= MAX_OUTCOMES` allein meldet auch
     * bei exakt vollem, aber ungekuerztem Bestand faelschlich "gekuerzt".
     */
    /** Gesamtstand der Kappungsverluste - wird beim Laden aus der Datei
     *  uebernommen und mit jedem Schreibvorgang fortgeschrieben. */
    private val entfernteErgebnisse = AtomicLong(0L)

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
        if (angenommen) {
            angenommenZaehler.incrementAndGet()
        } else {
            verworfen.incrementAndGet()
            // DIE LUECKE MARKIEREN, nicht nur zaehlen. Der Zeitpunkt dieses
            // Zyklus ist der spaeteste, ab dem wieder lueckenlos beobachtet
            // wurde.
            lastObservationGapTs.accumulateAndGet(snapshot.nowTs, ::maxOf)
        }
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
        // OHNE GUELTIGEN KOPFSTAND WIRD NICHT GELADEN (P0-3). `geladen` darf
        // hier NICHT gesetzt werden - sonst laeuft der Worker fuer den Rest
        // des Prozesses mit leerem Zustand weiter und ueberschreibt eine
        // vorhandene Generation, nur weil der erste Schnappschuss unbrauchbar
        // war.
        if (!s.stamp.valid || s.configGeneration.isBlank()) {
            melde("SKIPPED:keine gueltige Herkunft", 0, 0L)
            return
        }
        if (!geladen) {
            geladen = true
            ladeVonPlatte(s.dir, s.stamp, s.nowTs)
        }
        blockiert?.let {
            melde("BLOCKED:$it", 0, 0L)
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
        // DER KANDIDAT ENTSTEHT AUS DEM PERSISTIERTEN STAND, nie aus einem
        // Zwischenstand. Scheitert der Schreibvorgang, wird er verworfen.
        val basis = persistedState
        val kandidat = ExpectationLedger.advance(basis, s.nowTs, neu, s.samples)
        val abgerechnet = (kandidat.outcomes.size - basis.outcomes.size).coerceAtLeast(0)
        val stats = store.saveWithStats(
            s.dir, kandidat, ++revision, s.stamp,
            lastObservationGapTs.get(), entfernteErgebnisse.get(),
        )
        stats.written?.let { geschrieben ->
            // ERST JETZT ist der Stand auswertbar UND die Basis des naechsten
            // Zyklus - und zwar GENAU DER, der auf Platte steht. Den eigenen
            // Kandidaten zu uebernehmen hiesse, ab der Kappungsgrenze
            // Ergebnisse auszuwerten, die nie versiegelt wurden.
            persistedState = geschrieben
            entfernteErgebnisse.set(stats.droppedOutcomesTotal)
            asOfTsAtomic.set(s.nowTs)
        }
        melde(
            "RECORDED:issued=${neu != null},settled=$abgerechnet,persisted=${stats.ok}",
            stats.bytes, stats.durationMs,
        )
    }

    private fun ladeVonPlatte(dir: File, kopfstand: InterventionStamp, nowTsBeimLaden: Long) {
        if (!kopfstand.valid) {
            melde("FAILED:ungueltiger Kopfstand", 0, 0L)
            return
        }
        when (val g = runCatching { store.load(dir, kopfstand) }.getOrNull()) {
            is FuseExpectationStore.Loaded.Ok      -> {
                persistedState = g.state
                revision = g.revision
                // EIN PROZESSNEUSTART IST SELBST EINE BEOBACHTUNGSLUECKE
                // (Toni 18.08., P0). Zwischen dem letzten geschriebenen
                // Zyklus und diesem Start hat niemand hingesehen - und wie
                // lange, weiss niemand. Ohne diese Marke koennte eine Strecke
                // ueberleben, deren Luecke nur deshalb nicht persistiert
                // wurde, weil der Prozess unmittelbar nach dem Verwerfen
                // starb. Die geladenen Ergebnisse bleiben fuer den HISTORISCHEN
                // Export erhalten; aktuell sind sie nicht mehr.
                lastObservationGapTs.accumulateAndGet(g.lastObservationGapTs, ::maxOf)
                lastObservationGapTs.accumulateAndGet(nowTsBeimLaden, ::maxOf)
                // Der Trunkierungsstand kommt AUS DER DATEI - sonst meldete
                // eine laengst gekappte Generation nach jedem Neustart wieder
                // "vollstaendig".
                entfernteErgebnisse.accumulateAndGet(g.droppedOutcomesTotal, ::maxOf)
                // Die Marke ueberlebt den Neustart - sonst waere nach einem
                // Prozesswechsel nicht mehr erkennbar, dass zwischen den
                // gespeicherten Ergebnissen eine unbeobachtete Minute lag.
            }

            FuseExpectationStore.Loaded.Fresh      -> Unit

            is FuseExpectationStore.Loaded.Corrupt -> {
                // NICHT LEER WEITERLAUFEN (P0-3). Der erste Wurf tat das - und
                // schrieb die naechste Generation ueber die beschaedigte,
                // womit der Datenverlust unbeweisbar wurde. Jetzt klebt die
                // Sperre, bis jemand ausdruecklich repariert.
                blockiert = "Generation beschaedigt: ${g.reason}"
                melde("BLOCKED:$blockiert", 0, 0L)
            }

            null                                   -> {
                blockiert = "Laden warf"
                melde("BLOCKED:Laden warf", 0, 0L)
            }
        }
    }

    private fun melde(ergebnis: String, bytes: Int, dauerMs: Long) {
        bytesAtomic.set(bytes)
        durationMsAtomic.set(dauerMs)
        lastResultAtomic.set(ergebnis)
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
            persistedState.outcomes, nowTs, stamp, configGeneration, segmentId, klasse,
            minSafetyMarginMgdl, lastObservationGapTs.get(),
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
            // TRUNKIERUNG SICHTBAR MACHEN (Toni 18.08.). Eine gekappte
            // Strecke darf nie wie eine vollstaendig beobachtete aussehen -
            // sie ist "mindestens so lang", nicht "genau so lang".
            // AUS DEM ZAEHLER, nicht aus der Groesse: `size >= MAX` meldete
            // auch bei exakt vollem, aber ungekuerztem Bestand "gekuerzt".
            historyTruncated = entfernteErgebnisse.get() > 0L,
            droppedOutcomesTotal = entfernteErgebnisse.get(),
            // Aus dem PERSISTIERTEN Zustand - er ist seit dem Store-Umbau
            // derselbe, der auf Platte steht.
            oldestRetainedDueTs = z.outcomes.minOfOrNull { it.entry.dueTs } ?: 0L,
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
