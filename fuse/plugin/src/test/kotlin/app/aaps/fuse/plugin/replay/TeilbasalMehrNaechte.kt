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
import java.util.TimeZone

/**
 * MEHRNAECHTE-AUSWERTUNG DER TEILBASAL-RUECKKEHR.
 *
 * `FUSE_RIG_DIR` = Verzeichnis(se) mit Trails, durch `;` getrennt.
 * Ohne die Variable uebersprungen; im Repo liegen keine Daten.
 *
 * ALLE Zahlen sind KONSERVATIVE UNTERGRENZEN aus flachgelegter Bahn -
 * siehe Kopf von [TeilbasalRig]. Kein exakter Runner-Replay und keine
 * erwartete Live-Dosis. Ueber Glukoseverlaeufe wird nichts behauptet.
 *
 * DIE DREI KLASSEN, getrennt ausgewiesen statt zusammengeworfen:
 *  - **A** Rate UND Menge auswertbar: `basalGap.preMarkerScheduledBasalUph`
 *    vorhanden (erst ab rs47).
 *  - **B** nur Tor und Guard auswertbar: `zeroLatch.active` vorhanden, aber
 *    kein Profilbasal. Minuten und Kanten sind belastbar, MENGEN NICHT.
 *  - **C** nicht auswertbar: `zeloLatch.active` fehlt (bis rs24) - der
 *    Zustand, um den es geht, ist gar nicht aufgezeichnet.
 * Zusaetzlich getrennt: Naechte, in denen der Regelstand WECHSELT.
 */
class TeilbasalMehrNaechte : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction

    private val tag = SimpleDateFormat("dd.MM", Locale.ROOT)
    private val uhr = SimpleDateFormat("HH:mm", Locale.ROOT)

    @BeforeEach
    fun setup() {
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

    // ---- LESEN ------------------------------------------------------------

    /** Ein Zyklus samt der Kennzeichen, die ueber Vergleichbarkeit entscheiden. */
    private data class Roh(
        val z: TeilbasalRig.RigZyklus,
        val rs: Int?,
        val guardFloor: Double?,
        val liab: Int?,
        val theilSen: Int?,
        val insulin: String,
        val dia: Double,
        val hatZeroLatchFeld: Boolean,
        val hatProfil: Boolean,
        val datei: String,
    )

    private fun o(j: JSONObject?, k: String) = j?.optJSONObject(k)
    private fun d(j: JSONObject?, k: String) =
        j?.takeIf { it.has(k) && !it.isNull(k) }?.optDouble(k)?.takeIf { it.isFinite() }
    private fun i(j: JSONObject?, k: String) = j?.takeIf { it.has(k) && !it.isNull(k) }?.optInt(k)

    private fun lies(datei: File): List<Roh> = datei.useLines { zeilen ->
        zeilen.filter { it.trimStart().startsWith("{") }.mapNotNull { zeile ->
            runCatching {
                val j = JSONObject(zeile)
                val dec = o(j, "decision"); val sig = o(j, "signal"); val st = o(j, "state")
                val pol = o(j, "policy"); val pv = o(pol, "values"); val bg = o(j, "basalGap")
                val safety = o(j, "observer")?.optJSONArray("safetyReasons")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                val zl = o(j, "zeroLatch")
                val im = o(j, "insulinModel")
                Roh(
                    z = TeilbasalRig.RigZyklus(
                        computeTs = j.getLong("computeTs"),
                        sourceTs = j.optLong("sourceTs", 0L).takeIf { it > 0L } ?: 0L,
                        zeroActive = zl?.optBoolean("active") == true,
                        verdictNone = o(j, "lowThreat")?.optString("verdict") == "NONE",
                        signalHealthy = st?.optString("health") == "READY",
                        measuredLow = safety?.let { it == setOf("LOW") } ?: true,
                        descentRiskActive = j.optBoolean("descentRiskActive", true),
                        ukfRatePerMin = d(sig, "ukfRatePerMin"),
                        minLowerMgdl = d(dec, "minLowerMgdl"),
                        baselineBindenderOffsetMin = i(dec, "timeToMinSafetyLowerCombinedMin"),
                        timeToFloorMin = i(dec, "timeToFloorMin"),
                        guardFloorMgdl = d(pv, "guardFloorMgdl"),
                        isfMgdlPerU = d(st, "isfMgdlPerU"),
                        liabilityHorizonMin = i(pv, "liabilityHorizonMin"),
                        profilbasalUph = d(bg, "preMarkerScheduledBasalUph"),
                        // In allen Regelstaenden vorhanden - anders als
                        // `smb.publishedU`, das es erst spaet gibt.
                        smbPublishedU = d(dec, "smbU") ?: 0.0,
                    ),
                    rs = i(pol, "ruleSetVersion"),
                    guardFloor = d(pv, "guardFloorMgdl"),
                    liab = i(pv, "liabilityHorizonMin"),
                    theilSen = i(pv, "theilSenWindowMin"),
                    insulin = im?.optString("insulinType") ?: "?",
                    dia = im?.optDouble("diaHours") ?: 0.0,
                    hatZeroLatchFeld = zl != null && zl.has("active"),
                    hatProfil = d(bg, "preMarkerScheduledBasalUph") != null,
                    datei = datei.name,
                )
            }.getOrNull()
        }.toList()
    }

    // ---- NAECHTE ----------------------------------------------------------

    /** Nacht = 20:00 bis 10:00 des Folgetags, benannt nach dem Morgen. */
    private fun nachtSchluessel(ts: Long): String? {
        val kal = java.util.Calendar.getInstance(TimeZone.getDefault())
        kal.timeInMillis = ts
        val h = kal.get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h >= 20 -> { kal.add(java.util.Calendar.DAY_OF_YEAR, 1); tag.format(kal.time) }
            h < 10  -> tag.format(kal.time)
            else    -> null
        }
    }

    private class Nacht(val name: String) {
        val zyklen = mutableListOf<Roh>()
        val rs get() = zyklen.mapNotNull { it.rs }.toSortedSet()
        val dateien get() = zyklen.map { it.datei }.toSortedSet()
        val nullZyklen get() = zyklen.count { it.z.zeroActive }
        val hatZeroLatch get() = zyklen.any { it.hatZeroLatchFeld }
        val hatProfil get() = zyklen.any { it.hatProfil }
        val klasse get() = when {
            !hatZeroLatch -> "C"
            hatProfil     -> "A"
            else          -> "B"
        }
    }

    // ---- LAUF -------------------------------------------------------------

    @Test
    fun `Mehrnaechte-Matrix der Teilbasal-Rueckkehr`() {
        val dirs = System.getenv("FUSE_RIG_DIR")?.split(";")?.map { File(it.trim()) }
            ?.filter { it.isDirectory } ?: emptyList()
        assumeTrue(dirs.isNotEmpty(), "FUSE_RIG_DIR nicht gesetzt - uebersprungen")
        val schritt = System.getenv("FUSE_RIG_STEP")?.toDoubleOrNull() ?: 0.05
        val dauer = System.getenv("FUSE_RIG_DAUER")?.toIntOrNull() ?: 30
        val ersatz = System.getenv("FUSE_RIG_ERSATZDECKEL")?.toDoubleOrNull() ?: 3.0

        // Vereinigung ueber alle Dateien, Schluessel = computeTs. Ueberlappende
        // Exporte enthalten denselben Zyklus; gewinnt der Datensatz mit den
        // MEISTEN belegten Pflichtfeldern, damit ein aermerer Teilexport einen
        // reicheren nicht verdraengt.
        val union = HashMap<Long, Roh>()
        var dateien = 0
        dirs.flatMap { it.listFiles { f: File -> f.name.endsWith(".jsonl") }?.toList() ?: emptyList() }
            .sortedBy { it.name }
            .forEach { f ->
                if (f.length() < 2000) return@forEach
                dateien++
                lies(f).forEach { r ->
                    fun reich(x: Roh) = listOf(
                        x.z.minLowerMgdl, x.z.isfMgdlPerU, x.z.guardFloorMgdl,
                        x.z.profilbasalUph, x.z.ukfRatePerMin, x.rs,
                    ).count { it != null }
                    val alt = union[r.z.computeTs]
                    if (alt == null || reich(r) > reich(alt)) union[r.z.computeTs] = r
                }
            }
        val alle = union.values.sortedBy { it.z.computeTs }
        assumeTrue(alle.isNotEmpty(), "keine Zyklen")

        val naechte = LinkedHashMap<String, Nacht>()
        alle.forEach { r ->
            nachtSchluessel(r.z.computeTs)?.let { k ->
                naechte.getOrPut(k) { Nacht(k) }.zyklen += r
            }
        }
        val sortiert = naechte.values.sortedBy { it.zyklen.first().z.computeTs }

        println("=".repeat(112))
        println("MEHRNAECHTE-RIG   Dateien=$dateien  Zyklen(vereinigt)=${alle.size}  Naechte=${sortiert.size}")
        println("KONSERVATIVE UNTERGRENZE aus flachgelegter Bahn. Kein Runner-Replay,")
        println("keine erwartete Live-Dosis, keine Aussage ueber Glukoseverlaeufe.")
        println("=".repeat(112))

        // ---- INVENTUR ----------------------------------------------------
        println()
        println("INVENTUR JE NACHT (Nacht = 20:00 bis 10:00, benannt nach dem Morgen)")
        println("%-7s %-3s %5s %5s %-14s %-9s %-6s %-9s %-22s %s"
            .format("Nacht", "Kl", "Zykl", "Null", "ruleSet", "guardFl", "liab", "theilSen", "Insulin", "Quelldateien"))
        sortiert.forEach { n ->
            val gf = n.zyklen.mapNotNull { it.guardFloor }.toSortedSet()
            val lb = n.zyklen.mapNotNull { it.liab }.toSortedSet()
            val ts = n.zyklen.mapNotNull { it.theilSen }.toSortedSet()
            val ins = n.zyklen.map { "${it.insulin}/${it.dia}h" }.toSortedSet()
            println("%-7s %-3s %5d %5d %-14s %-9s %-6s %-9s %-22s %s".format(
                n.name, n.klasse, n.zyklen.size, n.nullZyklen,
                n.rs.joinToString(","), gf.joinToString(","), lb.joinToString(","),
                ts.joinToString(",").ifEmpty { "-" }, ins.joinToString(","),
                n.dateien.joinToString(",").take(46)))
        }
        println()
        println("KLASSEN  A = Rate UND Menge auswertbar (Profilbasal im Trail, ab rs47)")
        println("         B = nur Tor/Guard auswertbar (zeroLatch da, KEIN Profilbasal) - Minuten ja, MENGEN NEIN")
        println("         C = NICHT auswertbar (zeroLatch.active fehlt) - ausgeschlossen")
        val wechsler = sortiert.filter { it.rs.size > 1 }
        if (wechsler.isNotEmpty()) {
            println()
            println("REGELSTAND WECHSELT INNERHALB DER NACHT - getrennt ausgewiesen, nicht mit gemittelt:")
            wechsler.forEach { println("   ${it.name}: rs=${it.rs.joinToString(",")}") }
        }

        val brauchbar = sortiert.filter { it.klasse != "C" && it.nullZyklen >= 10 }
        val verworfen = sortiert - brauchbar.toSet()
        if (verworfen.isNotEmpty()) {
            println()
            println("AUSGESCHLOSSEN: " + verworfen.joinToString(", ") {
                "${it.name}(Kl.${it.klasse},${it.nullZyklen} Nullzyklen)"
            })
        }
        assumeTrue(brauchbar.isNotEmpty(), "keine auswertbare Nacht")

        // ---- SCHRITT 1+2: UKF-TOR BEI FESTEM EINTRITT 3 ------------------
        val kern = kernelBauer(
            alle.firstOrNull { it.insulin != "?" }?.insulin ?: "OREF_LYUMJEV",
            alle.firstOrNull { it.dia > 0.0 }?.dia ?: 9.0,
        )
        // Die BASISZEILE: ein Tor, das nie aufgeht. Ohne sie sind die
        // Aktuationskanten nicht lesbar - die allermeisten stammen aus der
        // Erneuerung der laufenden NULL alle 20 min, nicht aus der
        // Teilstufe. Erst die Differenz zu dieser Zeile ist der Preis.
        val basis = listOf<Pair<String, Double?>>("BASIS ohne Stufe" to Double.MAX_VALUE)
        val tore = listOf<Pair<String, Double?>>(
            "-0.03 (gebaut)" to -0.03, "-0.10" to -0.10, "-0.20" to -0.20, "ohne UKF-Tor" to null,
        )
        val dok = listOf<Pair<String, Double?>>("-0.40 (nur Doku)" to -0.40)

        fun auswerten(n: Nacht, ukf: Double?, eintritt: Int): TeilbasalRig.Bilanz {
            val ers = if (n.hatProfil) null else ersatz
            val l = TeilbasalRig.lauf(
                n.zyklen.map { it.z }, kern, schritt, dauer, null, ukf, eintritt, ers)
            return TeilbasalRig.bilanz(l, schritt, dauer)
        }

        fun kopf(titel: String) {
            println()
            println("=".repeat(112))
            println(titel)
            println("=".repeat(112))
            println("%-7s %-3s %-15s %7s %8s %8s %8s %7s %7s %6s %8s %8s %7s"
                .format("Nacht", "Kl", "UKF-Tor", "Phasen", "ZERO", "PARTIAL", "Basal U",
                        "Eintr", "Rueckf", "Kant", "Guard-Z", "Profil-Z", "SMB-weg"))
        }

        fun zeile(n: Nacht, was: String, b: TeilbasalRig.Bilanz) {
            println("%-7s %-3s %-15s %7d %8.1f %8.1f %8s %7d %7d %6d %8d %8d %7.3f".format(
                n.name, n.klasse, was, b.nullphasen.size, b.minZero, b.minPartial,
                b.basalU?.let { "%.3f".format(it) } ?: "n.a.",
                b.eintritte, b.rueckfaelle, b.kanten,
                b.zyklenGuardbegrenzt, b.zyklenProfilbegrenzt, b.smbUnterdruecktU))
        }

        kopf("SCHRITT 1+2  EIN-VARIABLEN-VERGLEICH DES UKF-TORS, Eintritt FEST bei 3 Zyklen")
        val summe = LinkedHashMap<String, MutableList<TeilbasalRig.Bilanz>>()
        brauchbar.forEach { n ->
            (basis + tore + dok).forEach { (was, ukf) ->
                val b = auswerten(n, ukf, 3)
                zeile(n, was, b)
                summe.getOrPut(was) { mutableListOf() } += b
            }
            println("-".repeat(112))
        }

        fun gesamt(titel: String, m: Map<String, List<TeilbasalRig.Bilanz>>, klasseA: Boolean) {
            println()
            println(titel)
            println("%-15s %7s %8s %8s %9s %7s %7s %6s %9s %9s %9s"
                .format("UKF-Tor", "Phasen", "ZERO", "PARTIAL", "Basal U", "Eintr", "Rueckf", "Kant", "Guard-Z", "Profil-Z", "SMB-weg"))
            m.forEach { (was, bs) ->
                val menge = if (klasseA) bs.mapNotNull { it.basalU }.sum().let { "%.3f".format(it) } else "n.a."
                println("%-15s %7d %8.1f %8.1f %9s %7d %7d %6d %9d %9d %9.3f".format(
                    was, bs.sumOf { it.nullphasen.size }, bs.sumOf { it.minZero }, bs.sumOf { it.minPartial },
                    menge, bs.sumOf { it.eintritte }, bs.sumOf { it.rueckfaelle }, bs.sumOf { it.kanten },
                    bs.sumOf { it.zyklenGuardbegrenzt }, bs.sumOf { it.zyklenProfilbegrenzt },
                    bs.sumOf { it.smbUnterdruecktU }))
            }
        }
        gesamt("GESAMT ueber alle auswertbaren Naechte (Menge NUR aus Klasse A):", summe, false)
        val basisKanten = summe["BASIS ohne Stufe"]?.sumOf { it.kanten } ?: 0
        println()
        println("ZUSAETZLICHE AKTUATIONSKANTEN gegenueber der Basis ($basisKanten Kommandos, fast alles")
        println("Erneuerung der laufenden Null alle 20 min) - das ist der eigentliche Preis:")
        println("%-15s %10s %10s %14s".format("UKF-Tor", "Kanten", "zusaetzl", "je PARTIAL-min"))
        summe.forEach { (was, bs) ->
            val k = bs.sumOf { it.kanten }; val mp = bs.sumOf { it.minPartial }
            println("%-15s %10d %10d %14s".format(was, k, k - basisKanten,
                if (mp > 0.0) "%.2f".format((k - basisKanten) / mp) else "-"))
        }
        // ROBUSTHEITSPROBE: dieselbe Rechnung ohne die Naechte, in denen
        // der Regelstand WECHSELT. Kippt die Ordnung hier, ist der Befund
        // ein Artefakt der Vermischung und keine Eigenschaft des Tors.
        val rein = brauchbar.filter { it.rs.size == 1 }
        if (rein.isNotEmpty() && rein.size < brauchbar.size) {
            val mR = LinkedHashMap<String, List<TeilbasalRig.Bilanz>>()
            (basis + tore + dok).forEach { (was, ukf) -> mR[was] = rein.map { auswerten(it, ukf, 3) } }
            gesamt("ROBUSTHEIT: nur Naechte mit EINEM Regelstand (${rein.joinToString(",") { "${it.name}/rs${it.rs.first()}" }}):", mR, false)
        }

        // Naechte, in denen KEIN Tor etwas oeffnet - der Grund gehoert benannt.
        println()
        brauchbar.forEach { n ->
            val b0 = auswerten(n, null, 3)
            if (b0.minPartial <= 0.0) {
                val nz = n.zyklen.map { it.z }.filter { it.zeroActive }
                println("OHNE JEDE TEILSTUFE: ${n.name} (auch ohne UKF-Tor 0 min). Gruende in den ${nz.size} Nullzyklen: " +
                    "Schutzgrund=%d  Tief=%d  Abwaerts=%d  nicht READY=%d  Bahn unter Boden=%d".format(
                        nz.count { !it.verdictNone }, nz.count { it.measuredLow },
                        nz.count { it.descentRiskActive }, nz.count { !it.signalHealthy },
                        nz.count { z -> (z.minLowerMgdl ?: -999.0) < (z.guardFloorMgdl ?: 70.0) }))
            }
        }

        val nurA = brauchbar.filter { it.klasse == "A" }
        if (nurA.isNotEmpty()) {
            val mA = LinkedHashMap<String, List<TeilbasalRig.Bilanz>>()
            (basis + tore + dok).forEach { (was, ukf) -> mA[was] = nurA.map { auswerten(it, ukf, 3) } }
            gesamt("GESAMT nur Klasse A (${nurA.joinToString(",") { it.name }}) - hier ist die Menge belegt:", mA, true)
            println()
            println("RATENVERTEILUNG NUR KLASSE A - nur hier sind es echte Raten (Profilbasal bekannt).")
            println("In Klasse B laeuft die Suche gegen einen ERSATZDECKEL; ihre Zahlen ueber dem")
            println("echten Profilbasal sind KEINE Raten, sondern nur 'der Guard haette mehr getragen'.")
            (tore + dok).forEach { (was, ukf) ->
                val r = nurA.flatMap { auswerten(it, ukf, 3).raten }
                if (r.isNotEmpty()) println("  %-15s n=%3d  %s".format(was, r.size,
                    r.groupingBy { it }.eachCount().toSortedMap().map { (k, v) -> "%.2f:%d".format(k, v) }.joinToString(" ")))
            }
        }

        // ---- SCHRITT 3: EINTRITT 3 GEGEN 5 -------------------------------
        println()
        println("=".repeat(112))
        println("SCHRITT 3  EINTRITT 3 GEGEN 5 ZYKLEN, je UKF-Kandidat")
        println("=".repeat(112))
        println("%-15s %-9s %8s %9s %7s %7s %6s %11s"
            .format("UKF-Tor", "Eintritt", "PARTIAL", "Basal U", "Eintr", "Rueckf", "Kant", "min/Eintritt"))
        (basis + tore).forEach { (was, ukf) ->
            listOf(3, 5).forEach { e ->
                val bs = brauchbar.map { auswerten(it, ukf, e) }
                val bsA = nurA.map { auswerten(it, ukf, e) }
                val mp = bs.sumOf { it.minPartial }; val ei = bs.sumOf { it.eintritte }
                println("%-15s %-9d %8.1f %9s %7d %7d %6d %11s".format(
                    was, e, mp,
                    bsA.mapNotNull { it.basalU }.sum().let { "%.3f".format(it) },
                    ei, bs.sumOf { it.rueckfaelle }, bs.sumOf { it.kanten },
                    if (ei > 0) "%.1f".format(mp / ei) else "-"))
            }
        }

        // ---- BINDENDER HORIZONT UND ABLEHNUNGEN --------------------------
        println()
        println("=".repeat(112))
        println("BINDENDER HORIZONT UND ABLEHNUNGEN (ueber alle auswertbaren Naechte, Eintritt 3)")
        println("=".repeat(112))
        tore.forEach { (was, ukf) ->
            val bs = brauchbar.map { auswerten(it, ukf, 3) }
            val off = bs.flatMap { it.bindendeOffsets.entries }
                .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }.toSortedMap()
            val abl = bs.flatMap { it.ablehnungen.entries }
                .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
            val raten = bs.flatMap { it.raten }
            println("%-15s bindend[min]=%s".format(was, off.toString().take(70)))
            println("%-15s Ablehnungen=%s  ohne Suche=%d".format(
                "", abl.ifEmpty { "keine" }, bs.sumOf { it.ohneSuche }))
            if (raten.isNotEmpty()) println("%-15s Raten n=%d min=%.2f median=%.2f max=%.2f".format(
                "", raten.size, raten.min(), raten.sorted()[raten.size / 2], raten.max()))
        }

        // ---- DAS TOR SELBST ----------------------------------------------
        println()
        println("=".repeat(112))
        println("WORAN DAS TOR SCHEITERT - ueber alle Nullzyklen der auswertbaren Naechte")
        println("=".repeat(112))
        val nz = brauchbar.flatMap { it.zyklen }.map { it.z }.filter { it.zeroActive }
        val gruende = listOf<Pair<String, (TeilbasalRig.RigZyklus) -> Boolean>>(
            "UKF < -0,03 / fehlt" to { z -> !(z.ukfRatePerMin?.isFinite() == true && z.ukfRatePerMin >= -0.03) },
            "Schutzgrund liegt an" to { z -> !z.verdictNone },
            "Signal nicht READY" to { z -> !z.signalHealthy },
            "gemessenes Tief" to { z -> z.measuredLow },
            "Abwaertsrisiko" to { z -> z.descentRiskActive },
        )
        gruende.forEach { (was, f) ->
            val n = nz.count(f)
            val allein = nz.count { z -> gruende.count { it.second(z) } == 1 && f(z) }
            println("  %-22s %5d von %d (%2.0f%%)   ALLEINIGER Grund: %d"
                .format(was, n, nz.size, 100.0 * n / nz.size, allein))
        }
        println("  UKF-Verteilung in Nullzyklen: " + listOf(-0.03, -0.10, -0.20, -0.40).joinToString("  ") { s ->
            "%.2f:%d".format(s, nz.count { it.ukfRatePerMin?.let { u -> u >= s } == true })
        } + "  von ${nz.size}")
        println("=".repeat(112))
    }
}
