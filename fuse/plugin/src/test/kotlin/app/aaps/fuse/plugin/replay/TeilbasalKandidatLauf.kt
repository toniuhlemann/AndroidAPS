package app.aaps.fuse.plugin.replay

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.fuse.core.controller.BasalRecoverySearch
import app.aaps.fuse.core.controller.CandidateSearch
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.profile.BasalSlot
import app.aaps.fuse.plugin.AapsUnitInsulinSampler
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.plugins.insulin.InsulinOrefRapidActingPlugin
import app.aaps.plugins.insulin.InsulinOrefUltraRapidActingPlugin
import app.aaps.shared.tests.TestBase
import org.json.JSONObject
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
 * DER KANDIDAT DURCH DIE ECHTE SUCHKETTE - je Episode, nicht als Mittelwert.
 *
 * ===================================================================
 * WAS HIER GEPRUEFT WIRD, UND WAS NICHT
 * ===================================================================
 * Geprueft wird EIN Kandidat fuer den Bolus-Deckungs-Abschlag:
 *
 *     term_eff = lambda * min(a_b * ISF, max(0, r))
 *
 * gegen den heutigen Term `lambda * max(0, a_b * ISF)`. Der Kandidat
 * begrenzt den Abschlag auf die GEMESSENE Stoerung r: er darf sie auf
 * null druecken, aber keine negative Stoerung erfinden. Im SMB-Sturm
 * (r ~ a_b*ISF) ist er identisch mit heute, im nuechternen Fastenfall
 * (r ~ 0) faellt er weg.
 *
 * NICHT geprueft wird "Abschlag entfernen". Die vollstaendige Entfernung
 * ist eine ANDERE Groesse und zeigt hoechstens Entlastungspotenzial.
 *
 * ===================================================================
 * DREI GRENZEN, DIE DAS ERGEBNIS TRAEGT
 * ===================================================================
 * 1. DIE NULL-AUSLOESUNG WIRD HIER NICHT BERUEHRT. `LowThreatGate` und
 *    `DescentRecoveryLatch` bekommen `signal.ukfRatePerMin`, nicht den
 *    abgeschlagenen Antrieb; und der GUARD_FLOOR-Zweig liefert seit dem
 *    17.08. KEEP_CURRENT, nicht ZERO_TEMP. Der Abschlag kann die Null
 *    also strukturell nicht ausloesen. Diese Datei misst deshalb nur den
 *    Ort, an dem er nachweislich wirkt: die TEILRATENSUCHE.
 *
 * 2. DIE BAHN IST FLACHGELEGT (wie im uebrigen Rig): alle Minuten auf
 *    der Basisbahn L0. Weil `s[i]` monoton ist, ist die gefundene Rate
 *    eine UNTERE SCHRANKE der Rate, die die echte Bahn getragen haette.
 *    Die Suche selbst ist die echte: sie prueft JEDE Minute, rechnet die
 *    Wirkung der gewuenschten Rate mit und rastert auf den Pumpenschritt.
 *
 * 3. DIE ANHEBUNG IST AM HORIZONT GERECHNET: dL = (nachKand - nachHeute)
 *    * decayWeightSumNegative. Der Abschlagsbeitrag waechst mit dem
 *    Offset, an frueheren Punkten ist er kleiner. Auf der flachen Bahn
 *    wird er hier ueberall gleich angesetzt - die Anhebung ist damit
 *    eine OBERE Schranke, die gefundene Kandidatenrate entsprechend
 *    optimistisch. Beide Schranken zeigen in verschiedene Richtungen;
 *    deshalb steht in der Ausgabe auch die vom GERAET tatsaechlich
 *    gefundene Rate als Eichpunkt daneben.
 *
 * Und in keinem Fall ist irgendetwas davon eine Aussage ueber einen
 * besseren oder sichereren spaeteren BG-Verlauf.
 *
 * Lauf: `FUSE_RIG_TRAIL=<pfad>` (kein Trail im Repo), optional
 * `FUSE_RIG_VON`/`FUSE_RIG_BIS` (Epoch-ms) und `FUSE_RIG_LAMBDA`.
 */
class TeilbasalKandidatLauf : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction

    @BeforeEach fun setup() {
        whenever(rh.gs(org.mockito.kotlin.any<Int>())).thenReturn("")
    }

    private fun kernelBauer(typ: String, dia: Double): (Long) -> UnitInsulinKernel? {
        val plugin = when (typ) {
            "OREF_LYUMJEV"            -> InsulinLyumjevPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
            "OREF_ULTRA_RAPID_ACTING" -> InsulinOrefUltraRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
            "OREF_RAPID_ACTING"       -> InsulinOrefRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
            else                      -> return { null }
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
    private fun s(j: JSONObject?, k: String) = j?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
    private fun hm(ts: Long) = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(ts))

    /** Ein Zyklus, soweit er fuer den Kandidatenvergleich gebraucht wird. */
    private data class Z(
        val computeTs: Long, val sourceTs: Long,
        val q1: Double?, val isf: Double?, val floor: Double?, val horizont: Int?,
        val profilbasal: Double?, val lambda: Double?,
        val driveMean: Double?, val vor: Double?, val nach: Double?, val term: Double?,
        val wNeg: Double?, val minLower: Double?,
        val streak: Int, val aktiv: Boolean,
        val basis: Double?, val rate: Double?, val limit: String?, val reject: String?,
        val ltVerdict: String?, val zeroLatch: Boolean, val zeroTbr: Boolean,
    )

    private fun lies(f: File): List<Z> = f.useLines { zs ->
        zs.mapNotNull { l ->
            if (!l.trimStart().startsWith("{")) null else runCatching {
                val j = JSONObject(l)
                val sig = o(j, "signal"); val st = o(j, "state"); val pol = o(j, "policy")?.optJSONObject("values")
                val dec = o(j, "decision"); val dr = o(j, "drive"); val disc = o(dr, "discount")
                val bg = o(j, "basalGap")
                val hub = o(o(j, "hub"), "main")?.optJSONObject("atHorizon")
                Z(
                    computeTs = j.optLong("computeTs"),
                    sourceTs = j.optLong("sourceTs", 0L).takeIf { it > 0L } ?: sig?.optLong("sourceTs", 0L) ?: 0L,
                    q1 = d(sig, "q1"), isf = d(st, "isfMgdlPerU"),
                    floor = d(pol, "guardFloorMgdl"), horizont = i(pol, "liabilityHorizonMin"),
                    profilbasal = d(bg, "scheduledBasalUph") ?: d(bg, "preMarkerScheduledBasalUph"),
                    lambda = d(pol, "bolusShareLambda"),
                    driveMean = d(dr, "mean"), vor = d(disc, "lowerBefore"),
                    nach = d(disc, "lowerAfter"), term = d(disc, "termMgdlPerMin"),
                    wNeg = d(hub, "decayWeightSumNegative"), minLower = d(dec, "minLowerMgdl"),
                    streak = i(bg, "partialRecoveryStreak") ?: 0,
                    aktiv = bg?.optBoolean("partialRecoveryActive") == true,
                    basis = d(bg, "partialRecoveryBaselineMinLowerMgdl"),
                    rate = d(bg, "partialRecoverySearchRateUPerH"),
                    limit = s(bg, "partialRecoverySearchLimit"),
                    reject = s(bg, "partialRecoverySearchReject"),
                    ltVerdict = s(o(j, "lowThreat"), "verdict"),
                    zeroLatch = o(j, "zeroLatch")?.optBoolean("active") == true,
                    zeroTbr = bg?.optBoolean("currentZeroTbrActive") == true,
                )
            }.getOrNull()
        }.filter { it.computeTs > 0L }.toList()
    }

    /** Die ECHTE Suche auf einer flachgelegten Bahn mit Basis [l0]. */
    private fun suche(
        z: Z, l0: Double, kernelFuer: (Long) -> UnitInsulinKernel?, schritt: Double, dauer: Int,
    ): BasalRecoverySearch.Ergebnis? {
        val floor = z.floor ?: return null
        val isf = z.isf?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val h = z.horizont ?: return null
        val profil = z.profilbasal?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val anker = if (z.sourceTs > 0L) z.sourceTs else z.computeTs
        val kernel = kernelFuer(z.computeTs) ?: return null
        val spanne = 24 * 3_600_000L
        return BasalRecoverySearch.hoechsteSichereRate(
            prediction = TeilbasalRig.flacheBahn(l0, anker, h),
            kernel = kernel,
            isfSlots = listOf(IsfSlot(anker - spanne, anker + spanne, isf)),
            band = CandidateSearch.Band(
                releaseTargetLowMgdl = 100.0, releaseTargetHighMgdl = 140.0,
                demandDeadbandMgdl = 10.0, guardFloorMgdl = floor,
                releaseHorizonMin = 30, liabilityHorizonMin = h,
            ),
            basalSlots = listOf(BasalSlot(anker - spanne, anker + spanne, profil)),
            basalStepUPerH = schritt, tbrDurationMin = dauer, pruefHorizontMin = h,
        )
    }

    @Test
    fun `Kandidat term_eff durch die echte Teilratensuche, je Episode`() {
        val pfad = System.getenv("FUSE_RIG_TRAIL")
        assumeTrue(pfad != null && File(pfad).isFile, "FUSE_RIG_TRAIL nicht gesetzt - uebersprungen")
        val von = System.getenv("FUSE_RIG_VON")?.toLongOrNull() ?: Long.MIN_VALUE
        val bis = System.getenv("FUSE_RIG_BIS")?.toLongOrNull() ?: Long.MAX_VALUE
        val schritt = System.getenv("FUSE_RIG_STEP")?.toDoubleOrNull() ?: 0.05
        val dauer = System.getenv("FUSE_RIG_DAUER")?.toIntOrNull() ?: 30
        val zyklen = lies(File(pfad!!)).filter { it.computeTs in von..bis }.sortedBy { it.computeTs }
        assumeTrue(zyklen.isNotEmpty(), "keine lesbaren Zyklen")

        val (typ, dia) = File(pfad).useLines { zs ->
            zs.filter { it.trimStart().startsWith("{") }.mapNotNull {
                runCatching { JSONObject(it).optJSONObject("insulinModel") }.getOrNull()
            }.firstOrNull()?.let { it.optString("insulinType") to it.optDouble("diaHours") }
        } ?: ("?" to 0.0)
        val kern = kernelBauer(typ, dia)

        println("=".repeat(96))
        println("KANDIDAT  term_eff = lambda * min(a_b*ISF, max(0, r))   gegen heute  lambda * max(0, a_b*ISF)")
        println("Abschnitt ${hm(zyklen.first().computeTs)}-${hm(zyklen.last().computeTs)}  (${zyklen.size} Zyklen)  Modell $typ DIA $dia")
        println("Bahn flachgelegt -> Rate ist untere Schranke; Anhebung am Horizont -> Kandidatenrate optimistisch.")
        println("KEINE Aussage ueber einen anderen BG-Verlauf. Null-Ausloesung unberuehrt (siehe KDoc).")
        println("=".repeat(96))

        var gleich = 0; var groesser = 0; var immerNoch0 = 0
        var summeHeute = 0.0; var summeKand = 0.0
        val zeilen = mutableListOf<String>()
        val berechtigt = zyklen.filter { it.streak >= TeilbasalRig.EINTRITT_ZYKLEN }

        berechtigt.forEach { z ->
            val lam = z.lambda ?: 1.0
            val vor = z.vor; val nach = z.nach; val term = z.term; val w = z.wNeg
            val basis = z.basis ?: z.minLower
            if (vor == null || nach == null || term == null || w == null || basis == null) return@forEach
            // a_b*ISF aus dem exportierten Term: term = lambda * max(0, a_b*ISF)
            val abIsf = if (lam > 0.0) term / lam else 0.0
            val r = z.driveMean ?: 0.0
            val termKand = lam * minOf(abIsf, maxOf(0.0, r))
            val nachKand = minOf(vor, r - termKand)
            val dL = (nachKand - nach) * w
            val heute = suche(z, basis, kern, schritt, dauer)
            val kand = suche(z, basis + dL, kern, schritt, dauer)
            val rH = heute?.rateUPerH ?: 0.0
            val rK = kand?.rateUPerH ?: 0.0
            summeHeute += rH; summeKand += rK
            when {
                rK > rH + 1e-9 -> groesser++
                rK <= 1e-9     -> immerNoch0++
                else           -> gleich++
            }
            zeilen += ("%s  Geraet %s/%-10s | Rig heute %.2f/%-10s | KANDIDAT %.2f/%-10s | " +
                "r %+.2f  a_b*ISF %.2f  term %.2f->%.2f  dL %+.1f  Basis %.1f->%.1f (Boden %.0f)")
                .format(
                    hm(z.computeTs),
                    z.rate?.let { "%.2f".format(it) } ?: "  - ", z.limit ?: z.reject ?: "-",
                    rH, heute?.begrenzung?.name ?: (heute?.reject?.name ?: "-"),
                    rK, kand?.begrenzung?.name ?: (kand?.reject?.name ?: "-"),
                    r, abIsf, term, termKand, dL, basis, basis + dL, z.floor ?: 0.0,
                )
        }

        println("EINTRITTSBERECHTIGTE ZYKLEN (streak >= ${TeilbasalRig.EINTRITT_ZYKLEN}): ${berechtigt.size}, davon auswertbar ${zeilen.size}")
        zeilen.take(40).forEach { println("  $it") }
        if (zeilen.size > 40) println("  ... ${zeilen.size - 40} weitere")
        println("-".repeat(96))
        println("KANDIDAT gegen heute: Rate groesser in $groesser | unveraendert in $gleich | weiterhin 0 in $immerNoch0")
        println("Summe der Raten (untere Schranke, KEINE abgegebene Menge): heute %.2f U/h-Summe, Kandidat %.2f".format(summeHeute, summeKand))

        // Der Ort, den der Kandidat NICHT anfasst - als Zaehlung, nicht als Behauptung.
        val zl = zyklen.count { it.zeroLatch }
        val lt = zyklen.count { it.ltVerdict == "FALLING_WITH_BOLUS_OVERCOVERAGE" || it.ltVerdict == "MEASURED_LOW" }
        println("UNBERUEHRT: $zl Zyklen mit laufendem Zero-Latch, $lt mit zuendendem LowThreat-Verdikt -")
        println("beide speisen sich aus signal.ukfRatePerMin, nicht aus dem abgeschlagenen Antrieb.")
        println("=".repeat(96))
    }
}
