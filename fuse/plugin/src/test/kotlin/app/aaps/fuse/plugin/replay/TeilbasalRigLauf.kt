package app.aaps.fuse.plugin.replay

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.plugin.AapsUnitInsulinSampler
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.plugins.insulin.InsulinOrefRapidActingPlugin
import app.aaps.plugins.insulin.InsulinOrefUltraRapidActingPlugin
import app.aaps.shared.tests.TestBase
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DER LAUF DES TEILBASAL-RIGS UEBER EINEN AUFGEZEICHNETEN TRAIL.
 *
 * Ohne `FUSE_RIG_TRAIL` uebersprungen - es liegen keine Daten im Repo.
 * Optional `FUSE_RIG_STEP` (Pumpen-Basalschritt, Standard 0,05) und
 * `FUSE_RIG_DAUER` (TBR-Dauer in Minuten, Standard 30).
 *
 * Die Einschraenkung der Methode steht im Kopf von [TeilbasalRig] und
 * gilt fuer JEDE Zahl, die hier gedruckt wird: das Ergebnis ist eine
 * UNTERE SCHRANKE der Rate, keine Vorhersage eines Verlaufs.
 */
class TeilbasalRigLauf : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction

    private val uhr = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private fun hm(ts: Long) = uhr.format(Date(ts))

    @BeforeEach
    fun setup() {
        whenever(rh.gs(org.mockito.kotlin.any<Int>())).thenReturn("")
    }

    /** Der Kern des im Trail benannten Plugins - die echte Klasse. */
    private fun kernelBauer(typ: String, dia: Double): (Long) -> UnitInsulinKernel? {
        val plugin = when (typ) {
            "OREF_LYUMJEV"              -> InsulinLyumjevPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
            "OREF_ULTRA_RAPID_ACTING"   -> InsulinOrefUltraRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
            "OREF_RAPID_ACTING"         -> InsulinOrefRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
            else                        -> return { null }
        }
        val model = InsulinModelProvenance(typ, dia, plugin.peak, plugin.javaClass.simpleName)
        return { ts ->
            (UnitInsulinKernelBuilder.build(AapsUnitInsulinSampler(plugin, dia, ts), ts, model, typ)
                as? KernelOutcome.Ok)?.kernel
        }
    }

    private fun o(j: JSONObject?, k: String) = j?.optJSONObject(k)
    private fun d(j: JSONObject?, k: String) = j?.takeIf { it.has(k) && !it.isNull(k) }
        ?.optDouble(k)?.takeIf { it.isFinite() }

    private fun i(j: JSONObject?, k: String) = j?.takeIf { it.has(k) && !it.isNull(k) }?.optInt(k)

    private fun lies(datei: File): List<TeilbasalRig.RigZyklus> =
        datei.useLines { zeilen ->
            zeilen.filter { it.trimStart().startsWith("{") }.mapNotNull { z ->
                runCatching {
                    val j = JSONObject(z)
                    val dec = o(j, "decision"); val sig = o(j, "signal"); val st = o(j, "state")
                    val obs = o(j, "observer"); val pol = o(o(j, "policy"), "values")
                    val safety = obs?.optJSONArray("safetyReasons")
                        ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                    val verdikt = o(j, "lowThreat")?.optString("verdict")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    TeilbasalRig.RigZyklus(
                        computeTs = j.getLong("computeTs"),
                        sourceTs = j.optLong("sourceTs", 0L).takeIf { it > 0L }
                            ?: sig?.optLong("sourceTs", 0L)?.takeIf { it > 0L } ?: 0L,
                        zeroActive = o(j, "zeroLatch")?.optBoolean("active") == true,
                        // FAIL-CLOSED: ein fehlendes Verdikt gilt als anliegend.
                        verdictNone = verdikt == "NONE",
                        signalHealthy = st?.optString("health") == "READY",
                        measuredLow = safety?.let { it == setOf("LOW") } ?: true,
                        descentRiskActive = j.optBoolean("descentRiskActive", true),
                        ukfRatePerMin = d(sig, "ukfRatePerMin"),
                        q1Mgdl = d(sig, "q1"),
                        positiveDescentHorizonMin = d(pol, "positiveDescentHorizonMin"),
                        minLowerMgdl = d(dec, "minLowerMgdl"),
                        baselineBindenderOffsetMin = i(dec, "timeToMinSafetyLowerCombinedMin"),
                        timeToFloorMin = i(dec, "timeToFloorMin"),
                        guardFloorMgdl = d(pol, "guardFloorMgdl"),
                        isfMgdlPerU = d(st, "isfMgdlPerU"),
                        liabilityHorizonMin = i(pol, "liabilityHorizonMin"),
                        // Das LAUFENDE Profilbasal (rs47+); der Marker-Schnappschuss
                        // ist Stunden alt und nur Rueckfall fuer aeltere Trails.
                        profilbasalUph = d(o(j, "basalGap"), "scheduledBasalUph")
                            ?: d(o(j, "basalGap"), "preMarkerScheduledBasalUph"),
                        smbPublishedU = d(o(j, "smb"), "publishedU") ?: 0.0,
                    )
                }.getOrNull()
            }.toList()
        }

    @Test
    fun `Teilbasal-Rueckkehr ueber die aufgezeichneten Nullphasen`() {
        val pfad = System.getenv("FUSE_RIG_TRAIL")
        assumeTrue(pfad != null && File(pfad).isFile, "FUSE_RIG_TRAIL nicht gesetzt - uebersprungen")
        val schritt = System.getenv("FUSE_RIG_STEP")?.toDoubleOrNull() ?: 0.05
        val dauer = System.getenv("FUSE_RIG_DAUER")?.toIntOrNull() ?: 30
        // AUSWERTUNGSABSCHNITT eindeutig: ohne Fenster laeuft das Rig ueber
        // den GANZEN Trail (alle darin liegenden Nullphasen). Mit
        // `FUSE_RIG_VON` / `FUSE_RIG_BIS` (Epoch-ms) nur ueber den Abschnitt -
        // sonst sind Zaehlungen aus zwei Laeufen nicht vergleichbar.
        val von = System.getenv("FUSE_RIG_VON")?.toLongOrNull() ?: Long.MIN_VALUE
        val bis = System.getenv("FUSE_RIG_BIS")?.toLongOrNull() ?: Long.MAX_VALUE
        val zyklen = lies(File(pfad!!)).sortedBy { it.computeTs }
            .filter { it.computeTs in von..bis }
        assumeTrue(zyklen.isNotEmpty(), "keine lesbaren Zyklen")

        // Insulinmodell aus dem Trail - eine Zeile reicht, es ist Konfiguration.
        val (typ, dia) = File(pfad).useLines { zs ->
            zs.filter { it.trimStart().startsWith("{") }.mapNotNull {
                runCatching { JSONObject(it).optJSONObject("insulinModel") }.getOrNull()
            }.firstOrNull()?.let { it.optString("insulinType") to it.optDouble("diaHours") }
        } ?: ("?" to 0.0)
        val kern = kernelBauer(typ, dia)

        val lauf = TeilbasalRig.lauf(zyklen, kern, schritt, dauer)
        // ---- VOR FIX / NACH FIX (Zeitachsen-Fehler) -----------------------
        // Dieselben Zyklen, einmal mit Profil-Slots ab Rechenzeit (so lief
        // die Produktion), einmal korrigiert. Ausgewiesen wird NUR, was die
        // Suche liefert - keine Aussage ueber einen anderen BG-Verlauf.
        val vorFix = TeilbasalRig.lauf(zyklen, kern, schritt, dauer, slotsAbRechenzeit = true)
        fun bilanz(name: String, l: List<Pair<TeilbasalRig.RigZyklus, TeilbasalRig.RigErgebnis>>) {
            val berechtigt = l.filter { it.second.torOffen && it.second.streak >= TeilbasalRig.EINTRITT_ZYKLEN }
            val rejects = berechtigt.mapNotNull { it.second.suche?.reject?.name }.groupingBy { it }.eachCount()
            val aktiv = berechtigt.count { it.second.zustand == TeilbasalRig.Zustand.PARTIAL }
            val keineRate = berechtigt.count { it.second.suche?.let { s -> s.reject == null && s.rateUPerH <= 0.0 } == true }
            println("%-9s eintrittsberechtigt %3d | aktiv %3d | Guard erlaubt keine Rate %3d | Datenluecke %s"
                .format(name, berechtigt.size, aktiv, keineRate, rejects.ifEmpty { mapOf("-" to 0) }))
        }
        println("=".repeat(70))
        println("ZEITACHSEN-VERGLEICH (Anker = Sensorzeit, wie in der Produktion)")
        println("Abschnitt: " + (if (von == Long.MIN_VALUE && bis == Long.MAX_VALUE) "GANZER TRAIL"
            else "${hm(zyklen.first().computeTs)}-${hm(zyklen.last().computeTs)}") +
            "  (${zyklen.size} Zyklen)")
        println("VEREINFACHTE SUCHERGEBNISSE: flachgelegte Bahn, untere Schranke -")
        println("keine nachgewiesene Pumpenabgabe, keine Aussage ueber einen anderen BG-Verlauf.")
        bilanz("VOR FIX", vorFix)
        bilanz("NACH FIX", lauf)

        println("=".repeat(70))
        println("TEILBASAL-RIG  Zyklen=${zyklen.size}  Modell=$typ DIA=$dia  Schritt=$schritt  TBR=${dauer}min")
        println("UNTERE SCHRANKE der Rate (flachgelegte Bahn) - keine Verlaufsaussage.")
        println("=".repeat(70))

        // ---- ZEITBILANZ --------------------------------------------------
        fun dauerMin(idx: Int): Double {
            val bis = lauf.getOrNull(idx + 1)?.first?.computeTs ?: return 0.0
            val dt = (bis - lauf[idx].first.computeTs) / 60_000.0
            return if (dt in 0.0..5.0) dt else 0.0   // Luecken zaehlen nicht als Zeit
        }
        var minZero = 0.0; var minPartial = 0.0; var minReleased = 0.0
        var basalU = 0.0; var smbInPartial = 0.0
        lauf.forEachIndexed { idx, (z, e) ->
            val m = dauerMin(idx)
            when (e.zustand) {
                TeilbasalRig.Zustand.ZERO       -> minZero += m
                TeilbasalRig.Zustand.PARTIAL    -> { minPartial += m; basalU += e.rateUPerH * m / 60.0; smbInPartial += z.smbPublishedU }
                TeilbasalRig.Zustand.KEINE_NULL -> minReleased += m
            }
        }
        println("ZEIT  ZERO %.1f min | PARTIAL %.1f min | RELEASED (Null nicht aktiv) %.1f min"
            .format(minZero, minPartial, minReleased))
        println("WIEDERHERGESTELLTES BASAL  %.3f U".format(basalU))

        // ---- SMB: WAS DIE TEILSTUFE UNTERDRUECKT HAETTE -----------------
        //
        // KEINE Zusicherung auf 0: eine laufende Schutz-Null sperrt den
        // schnellen Kanal NICHT von sich aus. Was hier steht, ist die
        // Menge, die die AUFZEICHNUNG in genau diesen Zyklen abgab und die
        // die Teilstufe aufgeben wuerde - eine Kostengroesse.
        println("SMB, den die Teilstufe unterdrueckt haette: %.4f U".format(smbInPartial))

        // ---- EINTRITTE, RUECKFAELLE, RATENWECHSEL ------------------------
        var vorher = TeilbasalRig.Zustand.KEINE_NULL
        var letzteRate = -1.0
        var wechsel = 0
        val ereignisse = mutableListOf<String>()
        lauf.forEach { (z, e) ->
            if (e.zustand == TeilbasalRig.Zustand.PARTIAL && vorher != TeilbasalRig.Zustand.PARTIAL)
                ereignisse += "%s  EINTRITT  Rate %.2f U/h  (bindend @%d min, %s)"
                    .format(hm(z.computeTs), e.rateUPerH, e.suche?.bindenderOffsetMin ?: -1, e.suche?.begrenzung)
            if (vorher == TeilbasalRig.Zustand.PARTIAL && e.zustand != TeilbasalRig.Zustand.PARTIAL)
                ereignisse += "%s  RUECKFALL auf %s".format(hm(z.computeTs), e.zustand)
            if (e.zustand == TeilbasalRig.Zustand.PARTIAL) {
                if (letzteRate >= 0.0 && kotlin.math.abs(e.rateUPerH - letzteRate) > 1e-9) wechsel++
                letzteRate = e.rateUPerH
            } else letzteRate = -1.0
            vorher = e.zustand
        }
        println("RATENWECHSEL innerhalb laufender Teilstufen: $wechsel  (jeder ist ein Pumpenkommando)")
        println("-".repeat(70))
        ereignisse.take(60).forEach { println("  $it") }
        if (ereignisse.size > 60) println("  ... ${ereignisse.size - 60} weitere")

        // ---- RATEN JE ZYKLUS ---------------------------------------------
        val raten = lauf.filter { it.second.zustand == TeilbasalRig.Zustand.PARTIAL }.map { it.second.rateUPerH }
        if (raten.isNotEmpty()) {
            println("-".repeat(70))
            println("RATEN  n=%d  min=%.2f  median=%.2f  max=%.2f".format(
                raten.size, raten.min(), raten.sorted()[raten.size / 2], raten.max()))
            println("  Verteilung: " + raten.groupingBy { it }.eachCount().toSortedMap()
                .map { (r, n) -> "%.2f:%d".format(r, n) }.joinToString(" "))
        }

        // ---- ABLEHNUNGEN UND BINDENDE PUNKTE -----------------------------
        val torOffen = lauf.filter { it.second.torOffen && it.first.zeroActive }
        val mitSuche = torOffen.mapNotNull { it.second.suche }
        println("-".repeat(70))
        println("TOR OFFEN in %d von %d Nullzyklen; Suche lief in %d"
            .format(torOffen.size, zyklen.count { it.zeroActive }, mitSuche.size))
        println("  Trail unvollstaendig (keine Suche moeglich): %d"
            .format(torOffen.count { it.second.streak >= TeilbasalRig.EINTRITT_ZYKLEN && it.second.suche == null }))
        println("  Ablehnungen: " + (mitSuche.mapNotNull { it.reject?.name }.groupingBy { it }.eachCount()
            .takeIf { it.isNotEmpty() }?.toString() ?: "keine"))
        println("  Begrenzung:  " + mitSuche.filter { it.reject == null }
            .groupingBy { it.begrenzung.name }.eachCount())
        println("  bindender Bahnpunkt [min]: " + mitSuche.filter { it.reject == null }
            .groupingBy { it.bindenderOffsetMin }.eachCount().toSortedMap())

        // ---- DIE HORIZONTFRAGE, GEMESSEN ---------------------------------
        val null_ = zyklen.filter { it.zeroActive }
        println("-".repeat(70))
        println("HORIZONT (Review-P1), gemessen am aufgezeichneten Baseline-Minimum:")
        println("  bindender Offset der Baseline: " + null_.groupingBy { it.baselineBindenderOffsetMin }
            .eachCount().toSortedMap(compareBy { it ?: -1 }))
        val h = null_.firstOrNull()?.liabilityHorizonMin ?: 120
        listOf(30, 45, 60, 90, h).distinct().sorted().forEach { hh ->
            // Guard bestanden bei Horizont hh  <=>  Boden im Fenster nie unterschritten
            val ok = null_.count { z -> z.timeToFloorMin?.let { it > hh } ?: true }
            println("    Horizont %3d min: Guard bestanden in %3d von %d Nullzyklen".format(hh, ok, null_.size))
        }
        println("  (Nur BESTANDEN/NICHT - die RATE bei kuerzerem Horizont braucht das")
        println("   Bahnniveau dort, und der Trail traegt nur das Minimum.)")

        // ---- WORAN DAS TOR SCHEITERT -------------------------------------
        println("-".repeat(70))
        println("TOR GESCHLOSSEN, Grund (Mehrfachnennung moeglich):")
        val gruende = listOf<Pair<String, (TeilbasalRig.RigZyklus) -> Boolean>>(
            "UKF < %.2f oder fehlt".format(TeilbasalRig.UKF_SCHWELLE) to
                { z -> !(z.ukfRatePerMin?.isFinite() == true && z.ukfRatePerMin >= TeilbasalRig.UKF_SCHWELLE) },
            "Schutzgrund liegt an" to { z -> !z.verdictNone },
            "Signal nicht READY" to { z -> !z.signalHealthy },
            "gemessenes Tief" to { z -> z.measuredLow },
            "Abwaertsrisiko" to { z -> z.descentRiskActive },
        )
        gruende.forEach { (was, f) ->
            val n = null_.count(f)
            val allein = null_.count { z -> gruende.count { it.second(z) } == 1 && f(z) }
            if (n > 0) println("  %-24s %4d von %d   davon ALLEINIGER Grund: %d".format(was, n, null_.size, allein))
        }

        // ---- DIE MATRIX: WAS DAS TOR KOSTET ------------------------------
        //
        // AUSDRUECKLICH KEINE EMPFEHLUNG. Jede Zeile ausser der ersten misst
        // eine ANDERE REGEL als die gebaute; die Zahlen sagen, was die
        // Torbedingung kostet, nicht, dass sie falsch ist.
        println("-".repeat(70))
        println("MATRIX  (Zeile 1 = die GEBAUTE Regel; alle anderen sind andere Regeln)")
        println("  %-10s %-8s %8s %10s %9s %8s".format("UKF>=", "Eintritt", "PARTIAL", "Basal U", "Eintritte", "Wechsel"))
        for (schwelle in listOf(-0.03, -0.10, -0.20, -0.40)) {
            for (eintritt in listOf(3, 5)) {
                val l2 = TeilbasalRig.lauf(zyklen, kern, schritt, dauer, null, schwelle, eintritt)
                var mp = 0.0; var bu = 0.0; var ein = 0; var wx = 0
                var vor = TeilbasalRig.Zustand.KEINE_NULL; var lr = -1.0
                l2.forEachIndexed { idx, (_, e) ->
                    val m = run {
                        val bis = l2.getOrNull(idx + 1)?.first?.computeTs ?: return@run 0.0
                        val dt = (bis - l2[idx].first.computeTs) / 60_000.0
                        if (dt in 0.0..5.0) dt else 0.0
                    }
                    if (e.zustand == TeilbasalRig.Zustand.PARTIAL) {
                        mp += m; bu += e.rateUPerH * m / 60.0
                        if (vor != TeilbasalRig.Zustand.PARTIAL) ein++
                        if (lr >= 0.0 && kotlin.math.abs(e.rateUPerH - lr) > 1e-9) wx++
                        lr = e.rateUPerH
                    } else lr = -1.0
                    vor = e.zustand
                }
                println("  %-10.2f %-8d %8.1f %10.3f %9d %8d".format(schwelle, eintritt, mp, bu, ein, wx))
            }
        }
        println("=".repeat(70))
    }
}
