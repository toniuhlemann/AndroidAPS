package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.round
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.controller.MarkerReauthorization
import app.aaps.fuse.plugin.export.FuseStateJson
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.LedgerPublicationGate
import app.aaps.fuse.plugin.ledger.OpenTransportItem
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * FUSE 2.0, STUFE 1: DER PRUEFSTAND DER GLEICHWERTIGKEITSNACHWEISE GN-A UND GN-B (Toni 10.10.2026).
 *
 * Gleichwertigkeit gilt ausdruecklich fuer die FESTGELEGTE KONFIGURATION: die am Geraet wirksamen FUSE-Einstellungen. Sie kommen
 * aus einer Konfigurationsdatei im privaten Bereich ([KONFIG]), nie aus dem Repository; ohne sie wird der Test uebersprungen.
 * Profil, Insulin, Pumpe und die CGM-Folgen sind synthetisch (offene Schleife wie die Treueprobe T1 der Forschung: die Reihe ist
 * fest, Abgaben wirken nur ueber das IOB).
 *
 * - GN-A: je Zyklus die Spalten [KOPF] (Entscheidung, TBR, SMB, Bindung und die Groessen, an denen die Stufe-1-Aenderungen haengen)
 *   ueber die Folgen [FOLGEN]. Verglichen wird byte-genau zwischen dem Ausgangsstand und dem geaenderten Stand.
 * - Abdeckung ([abdeckung]): geprueft werden die WIRKSAMEN Funktionen der Konfiguration, nicht unerreichbare AUS-Zweige. Nacht- und
 *   Rebound-Totband binden, die schnelle Bremsbahn bindet, die bedingte Schwanzkante entsteht, Korrektur-SMB laufen als Serie ohne
 *   Serien-Deckel, und die Wende-Klassifikation meldet Auf- und Abwaertswenden. Fehlt ein Punkt: "Probe unvollstaendig".
 * - GN-B: der Export je Zyklus ([FuseStateJson.record]) in [DATEI_EXPORT]; der Vergleich normalisiert nur Wanduhr-Messwerte und
 *   nennt jedes abweichende Feld.
 *
 * Alle Folgen und Testwerte sind synthetisch und ohne Bezug auf einen realen Tag.
 */
object Fuse20Folgen {

    /** Umgebungsvariable (oder Systemeigenschaft fuse.gn.konfig): Pfad der Konfigurationsdatei im privaten Bereich. */
    const val KONFIG = "FUSE_GN_KONFIG"

    /** Umgebungsvariable (oder Systemeigenschaft fuse.gn.ausgabe): frisches Ausgabeverzeichnis des Laufs. */
    const val AUSGABE = "FUSE_GN_AUSGABE"

    const val DATEI_ZEILEN = "gn_zeilen.csv"
    const val DATEI_ABDECKUNG = "gn_abdeckung.txt"
    const val DATEI_UMGEBUNG = "gn_umgebung.txt"
    const val DATEI_POLITIK = "gn_politik.txt"
    const val DATEI_EXPORT = "gn_export.jsonl.gz"

    /** Seriennummer der Pruefpumpe, wie im Gestell der Forschung. */
    const val PUMPE_SERIAL = "replay-pumpe"
    const val BASAL_SCHRITT = 0.05

    /** Eine feste Folge: Minute 0 bei [nullMs], ein Zyklus je Minute ab [vonMin] + 1 bis [bisMin], die CGM-Reihe aus [stuetzen]
     *  linear verbunden. [markerMin]: Markerdruck in dieser Minute, sonst keiner. */
    class Folge(
        val name: String,
        val nullMs: Long,
        val vonMin: Int,
        val bisMin: Int,
        val stuetzen: List<Pair<Int, Double>>,
        val markerMin: Int? = null,
    ) {

        fun ts(m: Int): Long = nullMs + m * 60_000L

        fun minute(t: Long): Int = Math.floorDiv(t - nullMs, 60_000L).toInt()

        fun kurve(m: Int): Double {
            require(m in vonMin..bisMin) { "Minute $m ausserhalb der Folge $name" }
            val i = stuetzen.indexOfLast { it.first <= m }
            val (m0, g0) = stuetzen[i]
            if (m0 == m || i == stuetzen.lastIndex) return g0
            val (m1, g1) = stuetzen[i + 1]
            return g0 + (g1 - g0) * (m - m0) / (m1 - m0)
        }

        val cgm: List<Pair<Long, Double>> by lazy { (vonMin..bisMin).map { ts(it) to kurve(it) } }
    }

    /** F1 Mahlzeit am Tag, Markerdruck in Minute 0; Minute 0 = 2023-11-15 11:30 UTC. Form wie die Treueprobe T1. */
    val F1 = Folge(
        "F1", 1_700_047_800_000L, -90, 300,
        listOf(
            -90 to 120.0, -60 to 120.0, -35 to 90.0, -10 to 90.0, 0 to 100.0, 10 to 108.0, 55 to 220.5, 135 to 100.5, 150 to 93.0,
            300 to 108.0,
        ),
        markerMin = 0,
    )

    /** F2 Korrektur in der Nacht ohne Marker; Minute 0 = 2023-11-15 22:30 UTC, in Mitteleuropa Nacht ab Minute -30. */
    val F2 = Folge(
        "F2", 1_700_087_400_000L, -60, 360,
        listOf(-60 to 135.0, -20 to 125.0, 0 to 125.0, 90 to 210.0, 120 to 210.0, 240 to 130.0, 360 to 130.0),
    )

    /** F3 Tief und Rebound am Tag ohne Marker; Minute 0 = 2023-11-16 10:00 UTC. */
    val F3 = Folge(
        "F3", 1_700_128_800_000L, -60, 240,
        listOf(-60 to 115.0, 0 to 100.0, 30 to 62.0, 40 to 62.0, 70 to 135.0, 110 to 145.0, 170 to 180.0, 240 to 120.0),
    )

    val FOLGEN: List<Folge> = listOf(F1, F2, F3)

    /**
     * DIE IDEALE PUMPE nach den AAPS-Regeln, dieselbe Regel wie in der Treueprobe T1: eine TBR endet mit ihrer Dauer, dem naechsten
     * Beginn oder einem Abbruch, so wie zum Zeitpunkt "jetzt" bekannt.
     */
    class Pumpe(private val basalSchritt: Double) {

        class Tbr(val start: Long, val dauerMs: Long, val rate: Double)

        val tbrs = ArrayList<Tbr>()
        val abbrueche = ArrayList<Long>()

        fun ende(i: Int, jetzt: Long): Long {
            val t = tbrs[i]
            var e = t.start + t.dauerMs
            tbrs.getOrNull(i + 1)?.takeIf { it.start <= jetzt }?.let { e = minOf(e, it.start) }
            abbrueche.firstOrNull { it > t.start && it <= jetzt }?.let { e = minOf(e, it) }
            return e
        }

        fun laufende(t: Long, jetzt: Long = t): Tbr? {
            val i = tbrs.indexOfLast { it.start <= t && it.start <= jetzt }
            if (i < 0) return null
            return tbrs[i].takeIf { t < ende(i, jetzt) }
        }

        fun restMin(t: Tbr, jetzt: Long): Int = max(round((t.start + t.dauerMs - jetzt) / 1000.0 / 60).toInt(), 0)

        /** Abbruch bei (Rate 0, Dauer 0) oder |Rate - Basis| < Basalschritt; Weiterlaufen bei Restzeit > 5 min, Dauer - Rest < 30 min
         *  und gleicher Rate; sonst eine neue TBR. Rueckgabe: GESETZT, WEITERLAUFEN, ABGEBROCHEN oder NICHTS. */
        fun anwenden(t: Long, rate: Double, dauerMin: Int, pumpenBasis: Double, profilBasal: Double): String {
            val basis = if (pumpenBasis.isFinite() && pumpenBasis > 0.0) pumpenBasis else profilBasal
            val laufend = laufende(t)
            if (dauerMin <= 0 || abs(rate - basis) < basalSchritt) {
                if (laufend == null) return "NICHTS"
                abbrueche += t
                return "ABGEBROCHEN"
            }
            if (laufend != null) {
                val rest = restMin(laufend, t)
                if (rest > 5 && dauerMin - rest < 30 && abs(rate - laufend.rate) < basalSchritt) return "WEITERLAUFEN"
            }
            require(tbrs.isEmpty() || t > tbrs.last().start) { "eine TBR beginnt nach der vorigen" }
            tbrs += Tbr(t, dauerMin * 60_000L, rate)
            return "GESETZT"
        }

        /** Die TBR-Sicht des Runners, Zeitstempel um (Wanduhr - [t]) verschoben, weil `TB.plannedRemainingMinutes` mit der Wanduhr
         *  rechnet (wie T1). */
        fun sicht(t: Long, jetzt: Long): TB? {
            val x = laufende(t, jetzt) ?: return null
            val versatz = System.currentTimeMillis() - t
            return TB(timestamp = x.start + versatz, duration = x.dauerMs, rate = x.rate, isAbsolute = true, type = TB.Type.NORMAL)
        }
    }

    /** Was das Rig zu einem Zyklus weiss: die offenen Posten vor dem Lauf, die Publikation, die Lieferung und die Pumpe. */
    class Rig(
        val postenVorLauf: List<OpenTransportItem>,
        val publiziertU: Double?,
        val gateErlaubt: Boolean,
        val geliefertU: Double?,
        val pumpe: String,
        val tbrLaeuftRate: Double?,
    )

    fun ungebucht(p: OpenTransportItem): Boolean = p.accountedAmountU <= 0.0 && p.temporaryId == null && p.pumpId == null
    fun gebucht(p: OpenTransportItem): Boolean = p.accountedAmountU > 0.0 || p.temporaryId != null || p.pumpId != null

    val KOPF: List<String> = listOf(
        "folge", "t", "abbruch", "smbU", "block", "bindung", "tbrAktion", "tbrRate", "tbrDauer", "tbrGeaendert", "tbrGrund",
        "tbrSicht", "markerU",
        "bg", "ziel", "isf", "iob", "q1", "ukf", "r", "praediktor", "fallback", "lowThreat", "abwaertsRisiko", "abwaertsRiegel",
        "lvAktiv", "lvStreak", "lvKandidat", "lvLift", "lvBindung", "lvVerweigerung", "lvAusgang", "lvSperreBis", "lvProfil",
        "lvBedarf", "kontext", "kontextGrund", "vollmachtId", "vollmachtBis", "pinFuer", "pinBis", "prime", "upfrontU",
        "fundamentLiftU", "transportU", "aufgenommenU", "aufnahmeGrund", "aufnahmeKandidaten", "expoQuelle", "expoBindung",
        "smbAktuiertU", "postenOffen", "postenUngebucht", "postenGebucht", "publiziertU", "gateErlaubt", "geliefertU", "pumpe",
        "tbrLaeuft",
        // Stufe 1: die Groessen, an denen K1 bis K3 haengen.
        "nacht", "reboundFenster", "bremse", "bremseBindet", "bedingtMgdl", "wende",
    )

    /** Volle Genauigkeit (kuerzeste rundlauffeste Darstellung), leer fuer null und nicht endlich. */
    fun zahl(x: Double?): String = x?.takeIf { it.isFinite() }?.toString() ?: ""

    fun bit(b: Boolean?): String = when (b) {
        null  -> ""
        true  -> "1"
        false -> "0"
    }

    fun zeile(f: Folge, o: FuseCycleRunner.Outcome, r: Rig): List<String> {
        fun minuteVon(t: Long?): String = t?.takeIf { it > 0L }?.let { f.minute(it).toString() } ?: ""
        return listOf(
            f.name,
            f.minute(o.computeTs).toString(), o.abortReason ?: "", zahl(o.decision.smbU), o.decision.block.name, o.decision.bindingLimit,
            o.decision.tbr.name, zahl(o.tbr?.rateUPerH), o.tbr?.durationMin?.toString() ?: "", bit(o.tbrChanged), o.reason,
            zahl(o.partialRecoveryObservedUPerH), zahl(o.decision.markerAuthorizedU),
            zahl(o.bgMgdl), zahl(o.targetMgdl), zahl(o.isfMgdlPerU), zahl(o.iobU), zahl(o.signal?.q1), zahl(o.signal?.ukfRatePerMin),
            zahl(o.signal?.rSigned), if (o.predictorRejected) o.predictorReason ?: "?" else "", bit(o.markerFallbackUsed),
            o.lowThreat?.verdict?.name ?: "", bit(o.descentRiskActive), bit(o.descentLatchActive),
            bit(o.livenessActive), o.livenessStreak.toString(), zahl(o.livenessCandidateU), zahl(o.livenessLiftU),
            o.livenessBinding ?: "", o.livenessDenial ?: "", o.livenessExit ?: "", minuteVon(o.livenessReArmUntilTs),
            o.livenessProfile ?: "", zahl(o.livenessNeedU),
            o.dosingContextProfile ?: "", o.dosingContextReason ?: "", minuteVon(o.dosingContextAuthorizationId),
            minuteVon(o.dosingContextAuthorizationExpiresAt), minuteVon(o.markerPowerPinnedFor), minuteVon(o.markerPowerDeadlineTs),
            bit(o.prime?.active), zahl(o.phaseAUpfrontRequestedU), zahl(o.foundationLiftU),
            zahl(o.transportSicht?.modelliertU), zahl(o.transportSicht?.aufnahme?.belegtU), o.transportSicht?.aufnahme?.grund ?: "",
            o.transportSicht?.aufnahme?.kandidaten?.toString() ?: "", o.exposureFinalSource ?: "", o.exposureGateBinding ?: "",
            zahl(o.smbActuatedU),
            r.postenVorLauf.size.toString(), r.postenVorLauf.count(::ungebucht).toString(), r.postenVorLauf.count(::gebucht).toString(),
            zahl(r.publiziertU), bit(r.gateErlaubt), zahl(r.geliefertU), r.pumpe, zahl(r.tbrLaeuftRate),
            bit(o.state?.nightWindow), bit(o.state?.reboundWindow), bit(o.restraint != null), bit(o.decision.restraintBound),
            zahl(o.tailLowerConditionalMgdl), o.turnResponseShadow?.classification?.phase?.name ?: "",
        ).map { it.replace(';', ',').replace('\n', ' ') }.also { check(it.size == KOPF.size) { "Zeile mit ${it.size} statt ${KOPF.size} Spalten" } }
    }

    /** Uebergang Transport -> Lieferung je Vorschlag: erster Ort ungebucht offen, erster Ort gebucht (Lesung vor dem Lauf). */
    class Uebergaenge {

        val ungebuchtAb = LinkedHashMap<String, Pair<String, Int>>()
        val gebuchtAb = LinkedHashMap<String, Pair<String, Int>>()

        fun lesen(folge: String, m: Int, posten: List<OpenTransportItem>) {
            posten.forEach { p ->
                if (ungebucht(p)) ungebuchtAb.putIfAbsent(p.proposalId, folge to m)
                if (gebucht(p)) gebuchtAb.putIfAbsent(p.proposalId, folge to m)
            }
        }

        /** Orte ("Folge:Minute") der Buchung von Vorschlaegen, die erst ungebucht offen und spaeter gebucht waren. */
        fun vollzogen(): List<String> = ungebuchtAb.mapNotNull { (id, a) ->
            gebuchtAb[id]?.takeIf { it.first == a.first && it.second > a.second }?.let { "${it.first}:${it.second}" }
        }
    }

    class Punkt(val name: String, val erfuellt: Boolean, val ort: String?)

    /** Die Abdeckung ueber alle Folgen aus ihren Zeilen ([KOPF]) und den Uebergaengen. */
    fun abdeckung(zeilen: List<List<String>>, uebergaenge: Uebergaenge): List<Punkt> {
        fun spalte(z: List<String>, name: String) = z[KOPF.indexOf(name)]
        fun ort(z: List<String>) = spalte(z, "folge") + ":" + spalte(z, "t")
        fun geliefert(z: List<String>) = (spalte(z, "geliefertU").toDoubleOrNull() ?: 0.0) > 0.0
        fun erste(name: String, wenn: (List<String>) -> Boolean): Punkt {
            val z = zeilen.firstOrNull(wenn)
            return Punkt(name, z != null, z?.let(::ort))
        }
        // K2: zwei gelieferte SMB im Korrekturkontext derselben Folge, hoechstens 30 Minuten auseinander.
        val serie = zeilen.filter { spalte(it, "kontext") == "CORRECTION" && geliefert(it) }
            .groupBy { spalte(it, "folge") }.values
            .firstNotNullOfOrNull { zs -> zs.zipWithNext().firstOrNull { (a, b) -> spalte(b, "t").toInt() - spalte(a, "t").toInt() <= 30 }?.second }
        val vollzogen = uebergaenge.vollzogen()
        return listOf(
            erste("smbAbgabe") { geliefert(it) },
            erste("tbrGesetzt") { spalte(it, "pumpe") == "GESETZT" },
            erste("tbrBeendet") { spalte(it, "pumpe") == "ABGEBROCHEN" },
            Punkt("transportLieferung", vollzogen.isNotEmpty(), vollzogen.firstOrNull()),
            erste("nachtTotband") { spalte(it, "bindung") == "nightDeadband" },
            erste("reboundTotband") { spalte(it, "bindung") == "reboundDeadband" },
            erste("schnelleBremsbahnBindet") { spalte(it, "bremseBindet") == "1" },
            erste("bedingteSchwanzkante") { spalte(it, "bedingtMgdl").isNotEmpty() },
            Punkt("korrekturserieOhneDeckel", serie != null, serie?.let(::ort)),
            erste("wendeAuf") { spalte(it, "wende") == "TURNING_UP" },
            erste("wendeAb") { spalte(it, "wende") == "TURNING_DOWN" },
        )
    }

    /** Zeilen, Abdeckung und Umgebung in das frische Ausgabeverzeichnis [out]; LF, UTF-8, ohne Zeitstempel und ohne Stand. */
    fun schreiben(out: File, zeilen: List<List<String>>, abdeckung: List<Punkt>, ohneWert: List<String>) {
        File(out, DATEI_UMGEBUNG).writeText(
            (
                listOf(
                    "java.version" to System.getProperty("java.version"), "java.vendor" to System.getProperty("java.vendor"),
                    "zeitzone" to java.util.TimeZone.getDefault().id, "file.encoding" to System.getProperty("file.encoding"),
                ) + listOf("ohneWert" to ohneWert.sorted().joinToString(","))
                ).joinToString("") { (k, v) -> "$k;${v ?: ""}\n" },
            Charsets.UTF_8,
        )
        val text = StringBuilder()
        text.append(KOPF.joinToString(";")).append('\n')
        zeilen.forEach { text.append(it.joinToString(";")).append('\n') }
        File(out, DATEI_ZEILEN).writeText(text.toString(), Charsets.UTF_8)
        val ab = StringBuilder()
        abdeckung.forEach { p -> ab.append(p.name).append(';').append(bit(p.erfuellt)).append(';').append(p.ort ?: "").append('\n') }
        ab.append("vollstaendig;").append(bit(abdeckung.all { it.erfuellt })).append(";\n")
        File(out, DATEI_ABDECKUNG).writeText(ab.toString(), Charsets.UTF_8)
    }
}

/**
 * FUSE 2.0, STUFE 1: GN-A und GN-B ueber [Fuse20Folgen.FOLGEN] mit der festgelegten Konfiguration.
 *
 * Das Rig folgt der Treueprobe T1 der Forschung (Mocks, AAPS-nahes IOB, ideale Pumpe; Zyklus wie die Plugin-Schicht: Runner,
 * Publikationsgate mit Buchung, Siegel, Reservierung, Lieferung, Pumpe). Der Runner wird wie in der Produktion gebaut
 * (FusePlugin.cycleRunner: nur der Wiedereinstieg mit Parametern, alles andere mit den Produktionsvorgaben).
 *
 * Nur mit [Fuse20Folgen.KONFIG] und [Fuse20Folgen.AUSGABE], sonst uebersprungen. Die Konfigurationsdatei traegt unter "werte" die
 * Einstellungen nach Schluesselnamen (FuseDoubleKey, FuseIntKey, FuseBooleanKey) und "ApsSmbMaxIob". Ein fehlender Schluessel faellt
 * auf seinen Standard zurueck und steht in der Umgebungsdatei unter "ohneWert".
 *
 * DURCHSICHT: schreibt nur die Dateien in das frische Ausgabeverzeichnis und den Ledger in ein @TempDir; liest nur die
 * Konfigurationsdatei, Umgebungsvariablen, Systemeigenschaften und die Wanduhr; kein Prozess, kein Netz, nichts geloescht.
 */
class Fuse20GleichwertigkeitTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var ads: AutosensDataStore
    @Mock lateinit var insulinProfileFunction: ProfileFunction
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: Insulin
    private lateinit var ledger: FuseLedgerAdapter
    private lateinit var runner: FuseCycleRunner

    private var folge: Fuse20Folgen.Folge = Fuse20Folgen.F1
    private var clock = 0L
    private var markerPress = 0L

    /** SETZEN IST DRUECKEN, wie im Rig der Treueprobe: der Setter setzt auch die Prozessbeobachtung. */
    private var markerAt: Long
        get() = markerAtIntern
        set(v) {
            markerAtIntern = v
            markerPress = v
        }
    private var markerAtIntern = 0L

    private var boluses: List<BS> = emptyList()
    private var bolusId = 0L
    private var pumpe = Fuse20Folgen.Pumpe(Fuse20Folgen.BASAL_SCHRITT)

    private val aapsCache = HashMap<Long, IobTotal>()

    private fun roundUp(t: Long) = if (t % 60_000L == 0L) t else (t / 60_000L + 1) * 60_000L

    private fun aapsNahIob(atTs: Long, zwischenspeichern: Boolean = true): IobTotal {
        val key = if (zwischenspeichern) roundUp(atTs) else atTs
        if (zwischenspeichern) aapsCache[key]?.let { return it }
        val dia = validProfile.dia
        var iob = 0.0
        var akt = 0.0
        for (b in boluses) if (b.isValid && b.timestamp < key && b.timestamp >= key - (dia * 3_600_000.0).toLong()) {
            val r = insulin.iobCalcForTreatment(b, key, dia)
            iob += r.iobContrib
            akt += r.activityContrib
        }
        val bolus = IobTotal(key).also { it.iob = iob; it.activity = akt }.round()
        val ergebnis = IobTotal.combine(bolus, IobTotal(key).round()).round().also { it.valid = true }
        if (zwischenspeichern && key <= clock) aapsCache[key] = ergebnis
        return ergebnis
    }

    private fun boliSetzen(neu: List<BS>) {
        val betroffen = (boluses.filter { it !in neu } + neu.filter { it !in boluses }).minOfOrNull { it.timestamp }
        boluses = neu
        if (betroffen != null) aapsCache.keys.removeAll { it > betroffen }
    }

    private fun reihe(bis: Long): List<GV> = folge.cgm.filter { it.first <= bis }.map { (t, v) ->
        GV(timestamp = t, value = v, raw = v, noise = 0.0, sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT)
    }

    private fun testPumpe() = FuseActivePump(
        "GENERIC_AAPS", virtualPump = true, bolusStepU = 0.05, basalStepUPerH = Fuse20Folgen.BASAL_SCHRITT,
        gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "TestPump"),
    )

    @BeforeEach
    fun setup() {
        insulin = InsulinLyumjevPlugin(rh, insulinProfileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        testPumpPlugin.pumpDescription.bolusStep = 0.05

        whenever(dateUtil.now()).thenAnswer { clock }
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfile(any())).thenReturn(validProfile)
        whenever(profileFunction.getProfileName()).thenReturn(TESTPROFILENAME)

        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.roundUpTime(any())).thenAnswer { inv -> roundUp(inv.getArgument(0)) }
        whenever(ads.getBgReadingsDataTableCopy()).thenAnswer { reihe(clock) }
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any())).thenAnswer { inv -> aapsNahIob(inv.getArgument(0)) }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenAnswer { aapsNahIob(clock, zwischenspeichern = false) }
        // Kein Basal-IOB im Rig (die TBR wirkt nicht auf das IOB), wie in der Treueprobe.
        whenever(iobCobCalculator.calculateIobToTimeFromTempBasalsIncludingConvertedExtended(any()))
            .thenAnswer { inv -> IobTotal(inv.getArgument(0)) }
        whenever(persistenceLayer.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())

        whenever(commandQueue.bolusInQueue()).thenReturn(false)
        whenever(commandQueue.isRunning(any())).thenReturn(false)

        whenever(persistenceLayer.getLastTherapyRecordUpToNow(any())).thenReturn(null)
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenAnswer { inv ->
            val from = inv.getArgument<Long>(0)
            val to = inv.getArgument<Long>(1)
            boluses.filter { it.timestamp in from..to && it.isValid }
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenAnswer { inv -> pumpe.sicht(inv.getArgument(0), clock) }
    }

    /** Die festgelegte Konfiguration, Schluessel fuer Schluessel; Rueckgabe: die Schluessel ohne Wert (Standard). */
    private fun stubKonfiguration(werte: JSONObject): List<String> {
        val ohneWert = ArrayList<String>()
        whenever(preferences.get(anyOrNull<BooleanKey>())).thenReturn(false)
        FuseDoubleKey.entries.forEach { k ->
            val v = if (werte.has(k.name)) werte.getDouble(k.name) else k.defaultValue.also { ohneWert += k.name }
            whenever(preferences.get(k)).thenReturn(v)
        }
        FuseIntKey.entries.forEach { k ->
            val v = if (werte.has(k.name)) werte.getInt(k.name) else k.defaultValue.also { ohneWert += k.name }
            whenever(preferences.get(k)).thenReturn(v)
        }
        FuseBooleanKey.entries.forEach { k ->
            val v = if (werte.has(k.name)) werte.getBoolean(k.name) else k.defaultValue.also { ohneWert += k.name }
            whenever(preferences.get(k)).thenReturn(v)
        }
        val maxIob = werte.getDouble("ApsSmbMaxIob")
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenReturn(maxIob)
        whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(maxIob, aapsLogger) }
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(-1L)
        FuseLongKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(0L) }
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
        return ohneWert
    }

    /** Der Markerdruck wie im Plugin und in der Treueprobe: Prozessbeobachtung, Preference und die neue Autorisierung im Ledger. */
    private fun druecken(t: Long) {
        markerAt = t
        ledger.episodes.let { e ->
            val seq = e.markerAuthSeq + 1L
            val neu = MarkerReauthorization.autorisiere(seq, t, e.markerRevocation)
            e.markerAuthSeq = seq
            e.markerAuth = neu.auth
            neu.revocation?.let { e.markerRevocation = it }
        }
    }

    /** Eine Folge von vorn: frischer Ledger, frischer Runner, leere Bolusliste, neue Pumpe. */
    private fun lauf(
        f: Fuse20Folgen.Folge, dir: File, zeilen: MutableList<List<String>>, uebergaenge: Fuse20Folgen.Uebergaenge,
        export: java.io.Writer, hashes: MutableSet<String>,
    ) {
        folge = f
        clock = f.ts(f.vonMin)
        boluses = emptyList()
        aapsCache.clear()
        bolusId = 0L
        pumpe = Fuse20Folgen.Pumpe(Fuse20Folgen.BASAL_SCHRITT)
        markerAtIntern = 0L
        markerPress = 0L
        val ledgerDir = File(dir, "ledger_${f.name}").also(File::mkdirs)
        ledger = FuseLedgerAdapter().also { it.loadOnce(ledgerDir, "gn-epoch", clock) }
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "gn-session", { markerPress },
            rejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.enabled(),
        )
        val serialHash = LedgerFacts.serialHashOf(Fuse20Folgen.PUMPE_SERIAL, PumpType.GENERIC_AAPS.name)
        while (clock + 60_000L <= f.ts(f.bisMin)) {
            if (f.markerMin != null && clock + 60_000L == f.ts(f.markerMin)) druecken(clock + 60_000L)
            clock += 60_000L
            val evidenzVorLauf = ledger.episodes.evidenceState
            val posten = ledger.openTransportItems()
            val o = runner.run(false, testPumpe())
            val units = o.decision.smbU.takeIf { it > 0.0 }
            val cycleId = "e023#${o.computeTs}"
            val rt = RT(
                algorithm = APSResult.Algorithm.FUSE, timestamp = o.computeTs,
                rate = null, duration = null, units = units, deliverAt = units?.let { o.computeTs },
            )
            val expected = LedgerPublicationGate.commitmentOf(
                units = rt.units, treatmentViewPresent = o.treatmentView != null, proposalId = cycleId,
            )
            val publication = LedgerPublicationGate.publish(
                rt = rt, adapter = ledger, dir = ledgerDir, expected = expected,
                published = InterventionStamp.Published(smbU = rt.units, tbrChanged = o.tbrChanged),
                events = {
                    val v = o.treatmentView
                    if (expected is LedgerPublicationGate.Commitment.Proposal && rt.units != null && v != null) {
                        ledger.onPublished(
                            proposalId = cycleId, unitsU = rt.units!!, decisionTs = o.computeTs,
                            latestBolusTs = v.latestBolusTs, bolusStepU = o.state?.pumpIncrementU ?: Double.NaN,
                            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = serialHash, virtualPump = false,
                        )
                    }
                    if (v != null) {
                        ledger.bindIdentities(v.boluses)
                        ledger.onCycleSnapshot(v.facts, v.snapshotHash, o.computeTs)
                        ledger.prune(o.computeTs, v.diaHours)
                    }
                },
            )
            ledger.sealCycleState(publication.sealed, evidenzVorLauf)
            ledger.resolveReservation(o.computeTs, publication.rt.units ?: 0.0, proposalId = cycleId)
            val geliefert = publication.rt.units?.takeIf { publication.sealed && it > 0.0 }
            if (geliefert != null) {
                boliSetzen(
                    boluses + BS(
                        id = ++bolusId, timestamp = o.computeTs, amount = geliefert, type = BS.Type.SMB,
                        ids = IDs(pumpId = o.computeTs, pumpType = PumpType.GENERIC_AAPS, pumpSerial = Fuse20Folgen.PUMPE_SERIAL),
                    ),
                )
            }
            val aktion = o.tbr?.takeIf { o.gate.allowed }?.let { req ->
                pumpe.anwenden(o.computeTs, req.rateUPerH, req.durationMin, testPumpe().baseBasalRateUPerH, validProfile.getBasal(o.computeTs))
            }
            uebergaenge.lesen(f.name, f.minute(o.computeTs), posten)
            zeilen += Fuse20Folgen.zeile(
                f, o,
                Fuse20Folgen.Rig(
                    postenVorLauf = posten, publiziertU = publication.rt.units, gateErlaubt = publication.allowed,
                    geliefertU = geliefert, pumpe = aktion ?: "", tbrLaeuftRate = pumpe.laufende(o.computeTs)?.rate,
                ),
            )
            // GN-B: der Export dieses Zyklus mit fester Messuhr; verglichen wird ausserhalb.
            val rec = FuseStateJson.record(cycleId, o, publication.rt, o.policy, BUILD, 0L, null) { 5_000_000L }
            export.write(JSONObject().put("folge", f.name).put("t", f.minute(o.computeTs)).put("rec", rec).toString())
            export.write("\n")
            o.policy?.let { p -> FuseStateJson.hashOf(p)?.let { hashes += it } }
        }
    }

    @Test
    fun `FUSE 2,0 Stufe 1 - GN-A und GN-B ueber feste Folgen mit der festgelegten Konfiguration`(@TempDir dir: File) {
        val konfigPfad = (System.getenv(Fuse20Folgen.KONFIG) ?: System.getProperty("fuse.gn.konfig"))?.takeIf { it.isNotBlank() }
        val ausgabePfad = (System.getenv(Fuse20Folgen.AUSGABE) ?: System.getProperty("fuse.gn.ausgabe"))?.takeIf { it.isNotBlank() }
        assumeTrue(
            konfigPfad != null && ausgabePfad != null,
            "nur mit ${Fuse20Folgen.KONFIG} und ${Fuse20Folgen.AUSGABE} (Konfiguration im privaten Bereich)",
        )
        val konfig = JSONObject(File(konfigPfad!!).readText().removePrefix("﻿"))
        val out = File(ausgabePfad!!)
        if (out.exists()) {
            require(out.isDirectory) { "Ausgabeziel ist kein Verzeichnis: ${out.name}" }
            check((out.listFiles()?.size ?: 0) == 0) { "Ausgabeverzeichnis ${out.name} ist nicht frisch" }
        }
        out.mkdirs()
        val ohneWert = stubKonfiguration(konfig.getJSONObject("werte"))
        val zeilen = ArrayList<List<String>>()
        val uebergaenge = Fuse20Folgen.Uebergaenge()
        val hashes = LinkedHashSet<String>()
        java.util.zip.GZIPOutputStream(java.io.FileOutputStream(File(out, Fuse20Folgen.DATEI_EXPORT))).bufferedWriter(Charsets.UTF_8).use { export ->
            Fuse20Folgen.FOLGEN.forEach { f -> lauf(f, dir, zeilen, uebergaenge, export, hashes) }
        }
        val abdeckung = Fuse20Folgen.abdeckung(zeilen, uebergaenge)
        Fuse20Folgen.schreiben(out, zeilen, abdeckung, ohneWert)
        File(out, Fuse20Folgen.DATEI_POLITIK).writeText(hashes.joinToString("") { "$it\n" }, Charsets.UTF_8)
        val fehlt = abdeckung.filter { !it.erfuellt }.map { it.name }
        check(fehlt.isEmpty()) { "[AUFBAU] GN-A: Probe unvollstaendig, es fehlt $fehlt" }
    }

    private companion object {

        val BUILD = FuseStateJson.Build("gn", "gn", true)
    }
}
