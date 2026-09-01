package app.aaps.fuse.plugin.replay

import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * DER LAUF GEGEN EINEN ECHTEN TRAIL - und der Grund, warum er so
 * umstaendlich aussieht.
 *
 * Der Trail enthaelt Gesundheitsdaten. Er darf weder im Repo liegen noch
 * in ein Ergebnis wandern, das committet wird. Deshalb:
 *   - Die Eingabe kommt aus der Umgebungsvariablen FUSE_REPLAY_TRAIL.
 *     Ist sie nicht gesetzt, wird der Lauf UEBERSPRUNGEN (assumeTrue) -
 *     in der normalen Suite passiert also nichts.
 *   - Ausgegeben wird auf die Konsole des lokalen Laufs, nichts wird
 *     geschrieben.
 *   - Im Repo steht nur die generische Rechnung ([NullphasenReplay])
 *     und dieser Leser.
 *
 * Aufruf (lokal, Git-Bash):
 *   FUSE_REPLAY_TRAIL=/pfad/nacht.jsonl ./gradlew :fuse:plugin:testFullDebugUnitTest \
 *     --tests "app.aaps.fuse.plugin.replay.NullphasenReplayLauf" -i
 *
 * WAS DIE AUSGABE IST: ein Entscheidungsvergleich unter FESTGEHALTENEM
 * Signal. Sie sagt, wann eine Variante anders entschieden haette und
 * wieviel Menge bzw. Zeit betroffen ist. Sie sagt NICHTS ueber den
 * Glukoseverlauf, ueber Sicherheit oder ueber TIR.
 */
class NullphasenReplayLauf {

    private val uhr = SimpleDateFormat("HH:mm")
    private fun hm(ts: Long) = uhr.format(Date(ts))

    /**
     * Der Leser leitet `q1NotFalling` aus ZWEI aufeinanderfolgenden
     * q1-Werten ab (Produktionsregel: q1 >= vorher - 0,01) und uebergibt
     * nur das Boolean - im Analyzer landet kein Glukosewert.
     *
     * FAIL-CLOSED bei fehlender Angabe: `measuredLow`/`descentRiskActive`
     * gelten dann als true (Gefahr angenommen), `q1NotFalling` als false.
     */
    private fun lies(datei: File): List<NullphasenReplay.Zyklus> {
        var vorigesQ1: Double? = null
        return datei.readLines().mapNotNull { zeile ->
            if (!zeile.trimStart().startsWith("{")) return@mapNotNull null
            runCatching {
                val o = JSONObject(zeile)
                val lt = o.optJSONObject("lowThreat")
                val s = o.optJSONObject("signal")
                val smb = o.optJSONObject("smb")
                val dc = o.optJSONObject("dosingContext")
                val bg = o.optJSONObject("basalGap")
                val st = o.optJSONObject("state")
                val verdikt = lt?.optString("verdict")?.takeIf { it.isNotBlank() && it != "null" }
                val q1 = s?.takeIf { it.has("q1") }?.optDouble("q1")?.takeIf { it.isFinite() }
                NullphasenReplay.Zyklus(
                    tsMs = o.getLong("computeTs"),
                    zeroActive = o.optJSONObject("zeroLatch")?.optBoolean("active") == true,
                    schutzgrund = verdikt != null && verdikt != "NONE",
                    ukfRatePerMin = s?.takeIf { it.has("ukfRatePerMin") }?.optDouble("ukfRatePerMin")
                        ?.takeIf { it.isFinite() },
                    signalHealthy = (st?.optString("health") ?: "READY") == "READY",
                    // Das laufende Profilbasal gibt es erst ab rs48; aeltere
                    // Trails liefern null, dann waechst nur die Zeit.
                    scheduledBasalUph = bg?.takeIf { it.has("scheduledBasalUph") }
                        ?.optDouble("scheduledBasalUph")?.takeIf { it.isFinite() && it > 0.0 },
                    publishedU = smb?.optDouble("publishedU", 0.0)?.takeIf { it.isFinite() } ?: 0.0,
                    mealAuthorized = dc?.optString("profile") == "MEAL",
                    measuredLow = lt?.optString("verdict") == "MEASURED_LOW" ||
                        (lt == null),   // fail-closed: ohne Urteil Gefahr annehmen
                    descentRiskActive = o.optBoolean("descentRiskActive", true),
                    q1NotFalling = q1?.let { jetzt ->
                        vorigesQ1?.let { jetzt >= it - 0.01 } ?: true
                    } ?: false,         // fail-closed: ohne q1 keine Erholung
                ).also { if (q1 != null) vorigesQ1 = q1 }
            }.getOrNull()
        }
    }

    @Test
    fun `Entscheidungsvergleich der Nullphasen-Varianten`() {
        val pfad = System.getenv("FUSE_REPLAY_TRAIL")
        assumeTrue(pfad != null) { "FUSE_REPLAY_TRAIL nicht gesetzt - Lauf uebersprungen" }
        val datei = File(pfad!!)
        assumeTrue(datei.isFile) { "Trail nicht lesbar: $pfad" }
        val zyklen = lies(datei).sortedBy { it.tsMs }
        println("== Zyklen: ${zyklen.size} ==")
        println("HINWEIS: Entscheidungsvergleich unter festgehaltenem Signal.")
        println("Keine Aussage ueber Glukoseverlauf, Sicherheit oder TIR.")

        val ph = NullphasenReplay.phasen(zyklen)
        println("\n-- Basislinie: ${ph.size} Nullphasen --")
        ph.forEach { p ->
            val basal = p.zyklen.zipWithNext().sumOf { (a, b) ->
                val dt = ((b.tsMs - a.tsMs) / 60_000.0).coerceIn(0.0, 3.0)
                (a.scheduledBasalUph ?: 0.0) * dt / 60.0
            }
            println("   %s-%s  %5.1f min  %.3f U".format(hm(p.vonMs), hm(p.bisMs), p.dauerMin, basal))
        }

        println("\n-- VARIANTE 1 --")
        for (n in listOf(2, 3, 5)) {
            val r = NullphasenReplay.variante1(zyklen, n)
            println(
                "N=%d: %d/%d Phasen betroffen | weggefallen %.0f min / %.3f U | ".format(
                    n, r.betroffenePhasen, ph.size, r.weggefalleneMin, r.weggefallenesBasalU,
                ) + "potenzielle Aktuationskanten %d | laengste Ruhe bis erneutem Grund %s".format(
                    r.potenzielleAktuationskanten,
                    r.laengsteRuheMin?.let { "%.0f min".format(it) } ?: "kein erneuter Grund",
                )
            )
            r.phasen.filter { it.ausgangMs != null }.forEach { p ->
                println(
                    "     %s-%s -> Ausgang %s, -%.0f min/-%.3f U, erneuter Grund nach %s".format(
                        hm(p.vonMs), hm(p.bisMs), hm(p.ausgangMs!!), p.weggefalleneMin,
                        p.weggefallenesBasalU,
                        p.erneuterGrundNachMin?.let { "%.0f min".format(it) } ?: "-",
                    )
                )
            }
        }

        println("\n-- VARIANTE 2: erst die Verteilung, dann Deckelkandidaten --")
        val fenster = listOf(15, 20, 30, 45)
        fenster.forEach { f ->
            val v = NullphasenReplay.verteilung(zyklen, f)
            println(
                "Fenster %2d min: max %.2f U (bei %s) | p90 %.2f | p50 %.2f".format(
                    f, v.maxU, v.maxTsMs?.let { hm(it) } ?: "-", v.p90U, v.p50U,
                )
            )
        }
        println("   (ein Deckel OBERHALB des Maximums kann im jeweiligen Fenster nie binden)")
        val ser = NullphasenReplay.serien(zyklen)
        println("")
        println("   Serien (Ruhetrennung %d min, publizierte Mengen): %d Stueck".format(
            NullphasenReplay.RUHE_TRENNUNG_MIN, ser.size))
        ser.sortedByDescending { it.summeU }.take(8).forEach {
            println("     %s-%s  %2d Dosen  %.2f U".format(hm(it.vonMs), hm(it.bisMs), it.dosen, it.summeU))
        }
        // WO LIEGT EINE BESTIMMTE SERIE? Optional per FUSE_REPLAY_MARK
        // als "HH:mm-HH:mm" (lokale Zeit des Trails). Liegt sie im
        // Mittelfeld, trifft jeder Deckel, der sie erwischt, sehr viel
        // anderes mit - und einer knapp darueber laesst sie ganz durch.
        System.getenv("FUSE_REPLAY_MARK")?.let { mark ->
            val teile = mark.split("-")
            if (teile.size == 2 && zyklen.isNotEmpty()) {
                val tag = SimpleDateFormat("yyyy-MM-dd").format(Date(zyklen.first().tsMs))
                val voll = SimpleDateFormat("yyyy-MM-dd HH:mm")
                val von = voll.parse("$tag ${teile[0]}")?.time
                val bis = voll.parse("$tag ${teile[1]}")?.time
                if (von != null && bis != null) {
                    println("")
                    println("   Einordnung der markierten Serie $mark:")
                    fenster.forEach { f ->
                        val e = NullphasenReplay.einordnung(zyklen, f, von, bis)
                        println(
                            "   Fenster %2d min: Serie max %.2f U = Rang %.0f%% unter dosisbeendeten Rollfenstern (Gesamtmax %.2f U)".format(
                                f, e.serieMaxU, e.rangUnterRollfenstern * 100, e.gesamtMaxU,
                            )
                        )
                    }
                }
            }
        }
        fenster.forEach { f ->
            val v = NullphasenReplay.verteilung(zyklen, f)
            listOf(0.6, 0.8, 1.0, 1.5).filter { it <= v.maxU + 0.5 }.forEach { d ->
                val r = NullphasenReplay.variante2(zyklen, d, f)
                println(
                    "Fenster %2d / Deckel %.1f U: gekappt %.2f U, %d Dosen, erste Bindung %s, groesster Kantensprung %.2f U".format(
                        f, d, r.gekapptU, r.betroffeneDosen,
                        r.ersteBindungMs?.let { hm(it) } ?: "-", r.groessterSprungU,
                    )
                )
            }
        }
    }
}
