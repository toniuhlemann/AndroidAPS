package app.aaps.workflow

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventIobCalculationProgress
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class PrepareBasalDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus
    private var ctx: Context = rh.getThemedCtx(context)

    class PrepareBasalData(
        val iobCobCalculator: IobCobCalculator, // cannot be injected : HistoryBrowser uses different instance
        val overviewData: OverviewData
    )

    override suspend fun doWorkAndLog(): Result {

        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PrepareBasalData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_BASAL_DATA, 0, null))
        val baseBasalArray: MutableList<ScaledDataPoint> = ArrayList()
        val tempBasalArray: MutableList<ScaledDataPoint> = ArrayList()
        val basalLineArray: MutableList<ScaledDataPoint> = ArrayList()
        val absoluteBasalLineArray: MutableList<ScaledDataPoint> = ArrayList()
        var lastLineBasal = 0.0
        var lastAbsoluteLineBasal = -1.0
        var lastBaseBasal = 0.0
        var lastTempBasal = 0.0
        val endTime = data.overviewData.endTime
        val fromTime = data.overviewData.fromTime
        var time = fromTime
        // GEMESSEN, NICHT GERATEN (16.08., komodo). Der eingebaute Zaehler hat
        // die Ursache dieses Workers eindeutig festgenagelt:
        //
        //   gesamt=16608ms schritte=360 rechenzeit=16586ms
        //   davon getBasalData=16584ms getProfile=0ms
        //
        // Der Worker WARTET also nicht auf einen belegten Thread-Pool, er
        // RECHNET - und 99,9 % davon stecken in einem einzigen Aufruf:
        // 46 ms je Minutenschritt fuer getBasalData. Der Profilaufruf, den ich
        // im ersten Anlauf gecacht hatte, kostete gemessene 0 ms; jener Fix war
        // wirkungslos, genau wie die Pruefung vorhergesagt hatte.
        //
        // WARUM 46 ms: getBasalData ruft je Schritt
        // getTempBasalIncludingConvertedExtended(t) und das geht jedes Mal an
        // die Datenbank. Der interne basalDataTable-Cache faengt das nicht ab,
        // weil LoadBgDataWorker zu Beginn jedes Zyklus clearCache() ruft - bei
        // 1-Minuten-CGM also jede Minute. Nicht die MENGE der Eintraege ist das
        // Problem (Toni hat zu Recht eingewandt, dass FUSE nur ~6 TBRs je
        // Stunde schreibt), sondern die ANZAHL DER ABFRAGEN.
        //
        // DER FIX: eine Abfrage fuer das ganze Fenster statt 360 einzelne.
        // getTempBasalIncludingConvertedExtendedForRange existiert dafuer
        // bereits im Bestand, holt mit EINEM getTemporaryBasalsActiveBetween-
        // TimeAndTime alle Eintraege und schneidet die Schritte im Speicher.
        // Die Semantik je Schritt ist unveraendert - was hier steht, ist Zeile
        // fuer Zeile dasselbe wie in getBasalData, nur ohne den DB-Weg.
        //
        // WAS DAS ANSTOESST: solange dieser Worker 33-60 s lief, wurde er von
        // der naechsten BG-Minute fast immer abgeschossen, und weil `.then()`
        // eine harte Abhaengigkeit ist, starben die NEUN Folgeglieder gleich
        // mit, die zusammen nur ~2 s brauchen. Sichtbar war das als dauerhaft
        // leere Untergraphen (IOB, DEV, FUSE-Serien) bei stehendem BG-Graphen.
        //
        // NACHGEPRUEFT, WEIL DIE BATCH-VARIANTE BISHER NIRGENDS BENUTZT WURDE
        // (sie lag unverdrahtet im Bestand, war also nicht erprobt):
        //   - Die Filter beider Abfragen sind identisch (referenceId IS NULL,
        //     isValid = 1).
        //   - Die Einzelabfrage nimmt bei Ueberlappung ORDER BY timestamp DESC
        //     LIMIT 1; die Range-Abfrage liefert ebenfalls timestamp DESC, und
        //     firstOrNull darauf ist genau dasselbe Element.
        //   - Die Treffermenge deckt jeden Rasterpunkt ab: wer bei t aktiv ist,
        //     erfuellt auch timestamp <= to AND timestamp + duration > from.
        //   - roundUpTime ist reines Minutenraster, also gilt
        //     roundUpTime(x + 60s) = roundUpTime(x) + 60s. Karte und Schleife
        //     treffen sich daher exakt; `rueckfaelle` im Log muss 0 bleiben.
        //
        // WO DER FIX NICHT GREIFT: getConvertedExtended() laeuft weiterhin je
        // Schritt. Es kehrt aber sofort zurueck, solange die Pumpe keine Temps
        // ueber Extended-Boli faelscht - Medtrum, Dana RS, Omnipod, Combo und
        // Medtronic stehen alle auf false. Nur bei einer Pumpe mit true bliebe
        // dieser Zweig teuer.
        //
        // SICHERHEITSNETZ: fehlt ein Schluessel in der Karte (ein Raster, das
        // nicht zu roundUpTime passt), faellt der Schritt auf den alten
        // getBasalData-Weg zurueck. Schlimmstenfalls ist er dann so langsam wie
        // vorher - ein FALSCHER Graph kann daraus nicht entstehen.
        //
        // ZWEITE, KLEINERE KOSTENSTELLE, die schon vorher weg ist: der
        // Fortschritt wurde je Schritt gesendet - bei 6 h Anzeige 360 mal, bei
        // 24 h 1440 mal, fuer 100 unterscheidbare Prozentwerte, jedes Mal ueber
        // den RxBus in die UI. Jetzt nur noch bei Aenderung des ganzzahligen
        // Prozentwerts.
        //
        // Was BEWUSST bleibt: die Schrittweite von einer Minute (die
        // Stufenkanten des Basalgraphen sollen minutengenau bleiben), die
        // isStopped-Pruefung (ein laufender Abbruch muss weiter sofort greifen)
        // und der Profilaufruf je Schritt. Letzteren hatte ich zwischenzeitlich
        // gecacht; die Messung weist ihm 0 ms zu, und ein Profilwechsel liegt
        // nicht zwingend auf einer halben Stunde - der Cache brachte also
        // nichts und konnte einen Wechsel verschlucken.
        val tStart = System.currentTimeMillis()
        var stepNanos = 0L
        var profileNanos = 0L
        var basalNanos = 0L
        var steps = 0
        var rueckfaelle = 0
        var lastProgress = -1
        val ads = data.iobCobCalculator.ads
        val tBatch = System.nanoTime()
        // Ein Aufruf fuer das ganze Fenster. Das Raster muss exakt das sein,
        // das die Schleife nachher nachschlaegt: roundUpTime(fromTime) plus
        // Vielfache einer Minute. Das obere Ende bekommt eine Minute Zugabe,
        // weil die Karte bis ausschliesslich endTime laeuft.
        val tempBasals = processedTbrEbData.getTempBasalIncludingConvertedExtendedForRange(
            ads.roundUpTime(fromTime), ads.roundUpTime(endTime) + 60_000L, 60_000L
        )
        val batchNanos = System.nanoTime() - tBatch
        while (time < endTime) {
            if (isStopped) return Result.failure(workDataOf("Error" to "stopped"))
            val progress = ((time - fromTime).toDouble() / (endTime - fromTime) * 100.0).toInt()
            if (progress != lastProgress) {
                lastProgress = progress
                rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_BASAL_DATA, progress, null))
            }
            val tStep = System.nanoTime()
            steps++
            val tP = System.nanoTime()
            val profile = profileFunction.getProfile(time)
            profileNanos += System.nanoTime() - tP
            if (profile == null) {
                time += 60 * 1000L
                continue
            }
            // Derselbe Rasterpunkt, den getBasalData intern benutzt.
            val t = ads.roundUpTime(time)
            val tB = System.nanoTime()
            val baseBasalValue: Double
            val isTempBasalRunning: Boolean
            val tempBasalAbsolute: Double
            if (tempBasals.containsKey(t)) {
                // containsKey statt Nullpruefung: ein FEHLENDER Schluessel und
                // ein Schluessel MIT Wert null sehen beim Lesen gleich aus, und
                // "kein Temp-Basal" ist genau der zweite Fall.
                val tb = tempBasals[t]
                baseBasalValue = profile.getBasal(t)
                isTempBasalRunning = tb != null
                tempBasalAbsolute = tb?.convertedToAbsolute(t, profile) ?: baseBasalValue
            } else {
                rueckfaelle++
                val basalData = data.iobCobCalculator.getBasalData(profile, time)
                baseBasalValue = basalData.basal
                isTempBasalRunning = basalData.isTempBasalRunning
                tempBasalAbsolute = basalData.tempBasalAbsolute
            }
            basalNanos += System.nanoTime() - tB
            var absoluteLineValue = baseBasalValue
            var tempBasalValue = 0.0
            var basal = 0.0
            if (isTempBasalRunning) {
                tempBasalValue = tempBasalAbsolute
                absoluteLineValue = tempBasalValue
                if (tempBasalValue != lastTempBasal) {
                    tempBasalArray.add(ScaledDataPoint(time, lastTempBasal, data.overviewData.basalScale))
                    tempBasalArray.add(ScaledDataPoint(time, tempBasalValue.also { basal = it }, data.overviewData.basalScale))
                }
                if (lastBaseBasal != 0.0) {
                    baseBasalArray.add(ScaledDataPoint(time, lastBaseBasal, data.overviewData.basalScale))
                    baseBasalArray.add(ScaledDataPoint(time, 0.0, data.overviewData.basalScale))
                    lastBaseBasal = 0.0
                }
            } else {
                if (baseBasalValue != lastBaseBasal) {
                    baseBasalArray.add(ScaledDataPoint(time, lastBaseBasal, data.overviewData.basalScale))
                    baseBasalArray.add(ScaledDataPoint(time, baseBasalValue.also { basal = it }, data.overviewData.basalScale))
                    lastBaseBasal = baseBasalValue
                }
                if (lastTempBasal != 0.0) {
                    tempBasalArray.add(ScaledDataPoint(time, lastTempBasal, data.overviewData.basalScale))
                    tempBasalArray.add(ScaledDataPoint(time, 0.0, data.overviewData.basalScale))
                }
            }
            if (baseBasalValue != lastLineBasal) {
                basalLineArray.add(ScaledDataPoint(time, lastLineBasal, data.overviewData.basalScale))
                basalLineArray.add(ScaledDataPoint(time, baseBasalValue, data.overviewData.basalScale))
            }
            if (absoluteLineValue != lastAbsoluteLineBasal) {
                absoluteBasalLineArray.add(ScaledDataPoint(time, lastAbsoluteLineBasal, data.overviewData.basalScale))
                absoluteBasalLineArray.add(ScaledDataPoint(time, basal, data.overviewData.basalScale))
            }
            lastAbsoluteLineBasal = absoluteLineValue
            lastLineBasal = baseBasalValue
            lastTempBasal = tempBasalValue
            time += 60 * 1000L
            stepNanos += System.nanoTime() - tStep
        }
        aapsLogger.debug(
            app.aaps.core.interfaces.logging.LTag.CORE,
            "PrepareBasalData MESSUNG: gesamt=${System.currentTimeMillis() - tStart}ms " +
                "schritte=$steps rechenzeit=${stepNanos / 1_000_000}ms " +
                "davon basal=${basalNanos / 1_000_000}ms getProfile=${profileNanos / 1_000_000}ms " +
                "batch=${batchNanos / 1_000_000}ms/${tempBasals.size}eintraege rueckfaelle=$rueckfaelle"
        )

        // final points
        basalLineArray.add(ScaledDataPoint(endTime, lastLineBasal, data.overviewData.basalScale))
        baseBasalArray.add(ScaledDataPoint(endTime, lastBaseBasal, data.overviewData.basalScale))
        tempBasalArray.add(ScaledDataPoint(endTime, lastTempBasal, data.overviewData.basalScale))
        absoluteBasalLineArray.add(ScaledDataPoint(endTime, lastAbsoluteLineBasal, data.overviewData.basalScale))

        // create series
        data.overviewData.baseBasalGraphSeries = LineGraphSeries(Array(baseBasalArray.size) { i -> baseBasalArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = rh.gac(ctx, app.aaps.core.ui.R.attr.baseBasalColor)
            it.thickness = 0
        }
        data.overviewData.tempBasalGraphSeries = LineGraphSeries(Array(tempBasalArray.size) { i -> tempBasalArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = rh.gac(ctx, app.aaps.core.ui.R.attr.tempBasalColor)
            it.thickness = 0
        }
        data.overviewData.basalLineGraphSeries = LineGraphSeries(Array(basalLineArray.size) { i -> basalLineArray[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                @Suppress("DEPRECATION")
                paint.strokeWidth = rh.getDisplayMetrics().scaledDensity * 2
                paint.pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.basal)
            })
        }
        data.overviewData.absoluteBasalGraphSeries = LineGraphSeries(Array(absoluteBasalLineArray.size) { i -> absoluteBasalLineArray[i] }).also {
            it.setCustomPaint(Paint().also { absolutePaint ->
                absolutePaint.style = Paint.Style.STROKE
                @Suppress("DEPRECATION")
                absolutePaint.strokeWidth = rh.getDisplayMetrics().scaledDensity * 2
                absolutePaint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.basal)
            })
        }
        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_BASAL_DATA, 100, null))
        return Result.success()
    }
}