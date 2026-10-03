package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WAECHTER C3-02: der Zeuge wird VOR jeder IOB-Lesung des Zyklus gelesen.
 *
 * WARUM ALS QUELLTEXT-WAECHTER UND NICHT ALS SEMANTIKTEST: die Invariante IST
 * eine Reihenfolge im Quelltext. Sie laesst sich nicht aus dem Ergebnis
 * ablesen - ein Zyklus, in dem zufaellig kein Bolus zwischen den beiden
 * Lesungen faellt, ist von einem korrekten nicht unterscheidbar. Genau so ist
 * der Fehler ueberhaupt entstanden: der Vertrag BEHAUPTETE die Reihenfolge
 * (TransportInclusion, "der Zeuge wird VOR dem ersten
 * calculateFromTreatmentsAndTemps gelesen"), und niemand hat gemerkt, dass
 * die Signalstufe dazwischenlag.
 *
 * DIE MECHANIK, gegen die dieser Test steht:
 * `FuseSignalSource.read()` ruft je Rohpunkt des Fensters
 * `calculateFromTreatmentsAndTemps(point.tsMs, profile)` auf. Der letzte Punkt
 * IST `sourceTs`; sein iobTable-Schluessel `roundUpTime(sourceTs)` ist exakt
 * der Schluessel, aus dem der spaetere Arraybau Punkt 0 liest. Die Signalstufe
 * SCHREIBT diesen Eintrag also selbst. Faellt zwischen ihr und einem spaeter
 * gelesenen Zeugen ein Bolus, gibt der Zeuge die Menge aus der
 * Transport-Modellierung frei, waehrend der bereits geschriebene Eintrag sie
 * nicht traegt - die Menge steckt in keiner der beiden Sichten.
 *
 * Das Muster (Projektwurzel suchen, Kommentare strippen) folgt
 * [NoCobDependencyTest].
 */
class WitnessBeforeIobReadTest {

    private fun runnerSource(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists())
            dir = dir.parentFile
        if (dir == null) fail<Unit>("Projektwurzel nicht gefunden")
        val f = File(dir, "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FuseCycleRunner.kt")
        if (!f.isFile) fail<Unit>("Runner nicht gefunden: $f")
        return f
    }

    /** Kommentare raus - sonst wuerde die Begruendung selbst den Test bestehen. */
    private fun ohneKommentare(src: String): String {
        var imBlock = false
        return src.lineSequence().filter { zeile ->
            val t = zeile.trim()
            when {
                imBlock && t.contains("*/") -> { imBlock = false; false }
                imBlock                     -> false
                t.startsWith("/*")          -> { imBlock = !t.contains("*/"); false }
                t.startsWith("//")          -> false
                t.startsWith("*")           -> false
                else                        -> true
            }
        }.joinToString("\n")
    }

    @Test
    fun `der Zeuge wird vor der Signalstufe gelesen`() {
        val code = ohneKommentare(runnerSource().readText())
        val zeuge = code.indexOf("zeugenLesung(computeTs")
        val signal = code.indexOf("signalSource.read(")
        assertTrue(zeuge >= 0) { "Zeugen-Lesung nicht gefunden - wurde sie umbenannt?" }
        assertTrue(signal >= 0) { "Signalstufe nicht gefunden - wurde sie umbenannt?" }
        assertTrue(zeuge < signal) {
            "C3-02: signalSource.read() setzt je Rohpunkt ein " +
                "calculateFromTreatmentsAndTemps ab; der letzte laeuft auf sourceTs und " +
                "SCHREIBT damit den iobTable-Eintrag an roundUpTime(sourceTs) - denselben, " +
                "aus dem Punkt 0 des IOB-Arrays kommt. Steht der Zeuge danach, kann er eine " +
                "Menge freigeben, die dieser Eintrag nicht traegt."
        }
    }

    @Test
    fun `die Postenliste wird zusammen mit dem Zeugen gelesen`() {
        // Beide muessen aus DERSELBEN Momentaufnahme stammen: eine spaeter
        // gelesene Liste koennte Posten enthalten, ueber die der Zeuge nichts
        // aussagt - und inSnapshot wuerde sie als "nicht entscheidbar" fuehren,
        // also unnoetig konservativ. Umgekehrt waere eine frueher gelesene
        // Liste mit spaeterem Zeugen der gefaehrliche Fall.
        val code = ohneKommentare(runnerSource().readText())
        val liste = code.indexOf("ledger.openTransportItems()")
        val zeuge = code.indexOf("zeugenLesung(computeTs")
        val signal = code.indexOf("signalSource.read(")
        assertTrue(liste in 0..<signal) { "openTransportItems() muss vor der Signalstufe stehen" }
        assertTrue(liste < zeuge) { "die Liste entscheidet, OB der Zeuge gelesen wird" }
    }

    /**
     * AUFNAHMENACHWEIS (03.10.2026): Lesung 2 steht NACH allen IOB-Abfragen,
     * deren Werte die Transportverbraucher benutzen, und die Transportmenge
     * entsteht erst danach. Stuende eine dieser Abfragen hinter Lesung 2,
     * koennte ein Bolus zwischen Lesung 2 und ihr ungueltig werden - und die
     * entlastete Menge fehlte in beiden Sichten.
     */
    @Test
    fun `Lesung 2 steht nach allen benutzten IOB-Abfragen und vor der Transportmenge`() {
        val code = ohneKommentare(runnerSource().readText())
        val arrays = code.indexOf("CoreInputGuard.build { fetchIobArrays(")
        val gesamt = code.indexOf("iobTotalVorLesung2 = runCatching { iobCobCalculator.calculateFromTreatmentsAndTemps(computeTs, profile) }")
        val nachweis = code.indexOf("transportAufnahme(vorlaeufig,")
        val transport = code.indexOf("transportDoses(transportItems,")
        val bahn = code.indexOf("buildPredictorInput(signal,")
        val liste = listOf(arrays, gesamt, nachweis, transport, bahn)
        assertTrue(liste.all { it >= 0 }) { "Stelle nicht gefunden (umbenannt?): $liste" }
        assertTrue(arrays < gesamt && gesamt < nachweis && nachweis < transport && transport < bahn) {
            "Reihenfolge verletzt (Arrays < Gesamt-IOB < Nachweis < Transport < Bahn): $liste"
        }
    }

    /**
     * STOLPERDRAHT: jede IOB-Abfrage im Runner ist bekannt. Eine neue muss VOR
     * Lesung 2 stehen und in die benutzten Werte des Nachweises, sonst kann eine
     * entlastete Menge in ihr fehlen. Die vier heutigen: der Abbruchpfad, die
     * Arrays, das Gesamt-IOB vor Lesung 2 und dessen Rueckfall (der nur greift,
     * wenn die erste Lesung scheiterte - dann entlastet der Nachweis nichts).
     */
    @Test
    fun `jede IOB-Abfrage im Runner ist bekannt`() {
        val code = ohneKommentare(runnerSource().readText())
        val n = Regex("calculateFromTreatmentsAndTemps\\(").findAll(code).count()
        assertTrue(n == 4) {
            "$n statt 4 IOB-Abfragen im Runner - eine neue muss vor Lesung 2 stehen und " +
                "in den Aufnahmenachweis (TransportAufnahme), sonst darf sie keine Transportgroesse speisen"
        }
        val rueckfall = code.indexOf("iobTotalVorLesung2 ?: iobCobCalculator.calculateFromTreatmentsAndTemps(")
        assertTrue(rueckfall >= 0) { "der Rueckfall des Gesamt-IOB muss an der ersten Lesung haengen" }
    }
}
