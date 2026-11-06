package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WAECHTER: FUSE liest kein COB.
 *
 * Die Entscheidung und ihre vollstaendige Begruendung stehen in
 * `TrajectoryCore` an der Zeile, an der man den Term addieren wuerde. Dieser
 * Test haelt sie fest — denn eine Begruendung im Kommentar verhindert nichts.
 * `rSigned` ist die gemessene Netto-Stoerung inklusive laufender Mahlzeit; AAPS
 * baut COB aus derselben `deviation` ab. Wer COB einliest und addiert, zaehlt
 * dieselbe Mahlzeit zweimal, und zwar unbemerkt: die Zahl sieht plausibel aus.
 *
 * WARUM EIN EIGENER TEST UND KEIN EINTRAG IN `Kc2CoverageTest`: jene Datei ist
 * die Institution fuer benannte KC2-Luecken und mit `assertEquals(65, ...)` und
 * einer festen Sperrliste hart zugenagelt. Die COB-Entscheidung ist keine
 * KC2-Nummer; sie dort einzubauen braeche zwei bestehende Zusicherungen.
 *
 * WARUM NUR `fuse/plugin`: `fuse/core` hat laut seiner build.gradle.kts
 * ueberhaupt keine AAPS-Abhaengigkeit — `CobInfo` und `AutosensData` sind dort
 * nicht einmal compilierbar. Der Modulschnitt ist der staerkere Waechter; ihn
 * zusaetzlich zu durchsuchen waere tote Last.
 */
class NoCobDependencyTest {

    /**
     * Bewusst knapp und am Modulschnitt ausgerichtet. `getCobInfo` ist
     * Teilstring von `CobInfo` und damit redundant; `displayCob` und
     * `activeCarbsList` sind Felder von `AutosensData` und ohne dieses ohnehin
     * unerreichbar.
     */
    private val verboten = listOf("CobInfo", "getCarbsFromTime", "AutosensData", "getLastAutosensData")

    /**
     * DIE EINE AUSNAHME (Entwurf B, Toni 05.11.2026: die gemeinsame Bilanz der Rechner-Mahlzeit): der eCarbs-Adapter
     * liest die ANGEKUENDIGTEN eCarbs - KH-Eintraege mit Dauer - und zerlegt sie wie AAPS. Das ist kein COB: keine
     * Aufnahme aus der deviation, kein Abbau, nichts in der Bahn. Die Aufnahme ordnet FUSE selbst zu, aus der
     * beobachteten Stoerung (`ECarbs.CobAttribution` in :fuse:core). Erlaubt ist ihm genau die eine Lesung; fuer alles
     * andere gilt die Liste auch dort.
     */
    private val ausnahmeDatei = "ECarbsQuelle.kt"
    private val ausnahmeLesung = "getCarbsFromTimeNotExpanded"

    @Test
    fun `der Adapter liest nirgends COB oder die Autosens-Tabelle`() {
        val quellen = mainSources()
        // Ohne diese Untergrenze waere "Wurzel gefunden, aber nichts gescannt"
        // still gruen — also genau der Ausfall, den der Test verhindern soll.
        assertTrue(quellen.size >= 8) { "nur ${quellen.size} Quelldateien gefunden - der Scan greift ins Leere" }

        val treffer = buildList {
            for (f in quellen) {
                val roh = ohneKommentare(f.readText())
                val code = if (f.name == ausnahmeDatei) roh.replace(ausnahmeLesung, "") else roh
                for (t in verboten) if (code.contains(t)) add("${f.name}: $t")
            }
        }
        assertTrue(treffer.isEmpty()) {
            "FUSE darf COB nicht lesen - rSigned enthaelt die Mahlzeit bereits. " +
                "Begruendung in TrajectoryCore an der meanBg-Zeile. Gefunden: $treffer"
        }
        // Die Ausnahme haengt an genau einer Datei, die die erlaubte Lesung auch wirklich macht - sonst waere sie tot oder
        // ginge mit einer Umbenennung still an eine andere Stelle.
        val adapter = quellen.filter { it.name == ausnahmeDatei }
        assertTrue(adapter.size == 1 && ohneKommentare(adapter.single().readText()).contains(ausnahmeLesung)) {
            "die Ausnahme fuer $ausnahmeDatei passt nicht mehr: $adapter"
        }
    }

    private fun mainSources(): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists())
            dir = dir.parentFile
        if (dir == null) fail<Unit>("Projektwurzel nicht gefunden")
        val src = File(dir, "fuse/plugin/src/main/kotlin")
        if (!src.isDirectory) fail<Unit>("Quellverzeichnis fehlt: $src")
        return src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * ZEILENWEISE, nicht auf Zeichenebene. Ein String-Literal mit `//` — etwa
     * eine URL — wuerde bei zeichenweiser Behandlung den Rest der Zeile
     * verschlucken und falsche Negative erzeugen. Fuer den Zweck (die eigenen
     * Begruendungskommentare ausblenden) reicht die Zeilenform vollstaendig.
     */
    private fun ohneKommentare(src: String): String {
        var imBlock = false
        return src.lineSequence().filter { zeile ->
            val t = zeile.trim()
            when {
                imBlock                              -> { if (t.contains("*/")) imBlock = false; false }
                t.startsWith("/*")                   -> { if (!t.contains("*/")) imBlock = true; false }
                t.startsWith("//") || t.startsWith("*") -> false
                else                                 -> true
            }
        }.joinToString("\n")
    }
}
