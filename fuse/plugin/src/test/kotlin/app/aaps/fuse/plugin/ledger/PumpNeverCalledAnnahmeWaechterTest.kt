package app.aaps.fuse.plugin.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER WAECHTER UEBER DIE FREMDANNAHMEN VON NotSentProof BELEG D (KI-171).
 *
 * Beleg D entlastet eine Ledger-Zeile, wenn AAPS die zugehoerige SMB mit
 * `notSentToPump` verworfen hat. Das traegt NUR, solange diese Marke
 * ausschliesslich in Zweigen gesetzt wird, die VOR dem Pumpentreiber enden:
 *   - `CommandSMBBolus.execute`: Intervall und "too old", nie der
 *     `deliverTreatment`-Zweig;
 *   - `CommandQueueImplementation.bolus`: die SMB-Ablehnungen vor dem
 *     Einreihen, nie `cancel()` oder `clear()`;
 *   - `LoopPlugin.applySMBRequest`: die Tore vor `commandQueue.bolus`;
 * und solange `LoopPlugin` die Ablage mit der RT-Identitaet der Anforderung
 * nur fuer markierte Fehlschlaege schreibt.
 *
 * Bricht eine davon (etwa bei einem AAPS-Merge), entlastet FUSE womoeglich
 * eine Menge, die doch geflossen ist - es rechnet mit ZU WENIG Insulin und
 * dosiert ZU VIEL. Deshalb dieser statische Waechter, gebaut wie
 * [LoopPluginAnnahmeWaechterTest].
 *
 * SCHLAEGT ER FEHL, ist die Reaktion NICHT, ihn anzupassen, sondern Beleg D
 * zu pruefen, bevor der Stand gebaut wird.
 */
class PumpNeverCalledAnnahmeWaechterTest {

    private fun quelle(rel: String): String {
        val kandidaten = listOf("../../$rel", rel)
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "$rel nicht gefunden - Waechter kann die Annahme nicht pruefen" }
        return f.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }
    }

    private val smbBolus by lazy {
        quelle("implementation/src/main/kotlin/app/aaps/implementation/queue/commands/CommandSMBBolus.kt")
    }
    private val warteschlange by lazy {
        quelle("implementation/src/main/kotlin/app/aaps/implementation/queue/CommandQueueImplementation.kt")
    }
    private val loop by lazy {
        quelle("plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt")
    }

    /** Der Rumpf ab [start] bis zur ersten Funktion danach - grob, aber ohne Parser. */
    private fun funktion(code: String, start: String): String {
        val a = code.indexOf(start)
        assertTrue(a >= 0) { "'$start' nicht gefunden" }
        val b = FUN.find(code, a + start.length)?.range?.first ?: code.length
        return code.substring(a, b)
    }

    @Test
    fun `CommandSMBBolus markiert genau die zwei Zweige ohne Pumpenaufruf`() {
        val execute = funktion(smbBolus, "override fun execute()")
        assertEquals(2, MARKE.findAll(execute).count()) {
            "Erwartet genau zwei markierte Zweige (Intervall, too old) in execute()"
        }
        val lieferung = DELIVER.find(execute)?.range
        assertTrue(lieferung != null) { "deliverTreatment-Aufruf in execute() nicht gefunden" }
        // Die Anweisung mit deliverTreatment endet am Zeilenende - dort darf keine Marke stehen.
        val zeile = execute.substring(lieferung!!.first).substringBefore("\n")
        assertTrue(!zeile.contains("notSentToPump")) { "der Pumpenzweig darf nie als nie gesendet gelten" }
        val abbruch = funktion(smbBolus, "override fun cancel()")
        assertTrue(!abbruch.contains("notSentToPump")) { "cancel() kann nach einem Pumpenaufruf kommen - nie markieren" }
    }

    @Test
    fun `die Warteschlange markiert nur SMB-Ablehnungen vor dem Einreihen`() {
        val bolus = funktion(warteschlange, "override fun bolus(")
        val einreihen = bolus.indexOf("add(CommandSMBBolus(")
        assertTrue(einreihen >= 0) { "Einreihen der SMB in bolus() nicht gefunden" }
        val marken = MARKE_ALLE.findAll(bolus).map { it.range.first }.toList()
        assertEquals(3, marken.size) { "Erwartet drei markierte Ablehnungen in bolus(), gefunden ${marken.size}" }
        assertTrue(marken.all { it < einreihen }) { "eine Marke steht nach dem Einreihen" }
        val ausserhalb = MARKE_ALLE.findAll(warteschlange.replace(bolus, "")).count()
        assertEquals(0, ausserhalb) { "notSentToPump ausserhalb von bolus() - etwa in cancel/clear - ist nicht belegbar" }
    }

    @Test
    fun `der Loop markiert nur seine Tore und legt nur markierte Fehlschlaege ab`() {
        val apply = funktion(loop, "private fun applySMBRequest(")
        val bolus = BOLUS.find(apply)?.range?.first ?: -1
        assertTrue(bolus >= 0) { "commandQueue.bolus in applySMBRequest nicht gefunden" }
        val marken = MARKE.findAll(apply).map { it.range.first }.toList()
        assertEquals(3, marken.size) { "Erwartet drei markierte Tore in applySMBRequest" }
        assertTrue(marken.all { it < bolus }) { "eine Marke steht nach commandQueue.bolus" }

        val ablage = ABLAGE.find(loop)?.range?.first ?: -1
        assertTrue(ablage >= 0) { "recordSmbNotSent fehlt im Loop" }
        val davor = loop.substring(maxOf(0, ablage - 200), ablage)
        assertTrue(Regex("""if\s*\(\s*result\s*\.\s*notSentToPump\s*\)""").containsMatchIn(davor)) {
            "die Ablage haengt nicht mehr an result.notSentToPump"
        }
        val danach = loop.substring(ablage, minOf(loop.length, ablage + 400))
        assertTrue(Regex("""request\s*=\s*resultAfterConstraints\s*\.\s*rawData\s*\(\s*\)""").containsMatchIn(danach)) {
            "die Ablage traegt nicht mehr die RT-Identitaet der Anforderung"
        }
        assertEquals(1, ABLAGE.findAll(loop).count()) { "genau eine Ablagestelle erwartet" }
    }

    /** SELBSTPRUEFUNG: jedes Muster muss im fremden Quelltext wirklich greifen. */
    @Test
    fun `der Waechter greift ueberhaupt`() {
        assertTrue(MARKE.containsMatchIn(smbBolus)) { "Marken-Muster findet in CommandSMBBolus nichts" }
        assertTrue(DELIVER.containsMatchIn(smbBolus)) { "deliverTreatment-Muster findet nichts" }
        assertTrue(MARKE_ALLE.containsMatchIn(warteschlange)) { "Marken-Muster findet in der Warteschlange nichts" }
        assertTrue(BOLUS.containsMatchIn(loop)) { "Bolus-Muster findet im Loop nichts" }
        assertTrue(ABLAGE.containsMatchIn(loop)) { "Ablage-Muster findet im Loop nichts" }
    }

    private companion object {

        val FUN = Regex("""\n\s*(override\s+|private\s+|internal\s+|public\s+)*fun\s""")
        val MARKE = Regex("""\.\s*notSentToPump\s*\(\s*true\s*\)""")
        /** Builder oder Zuweisung - die Warteschlange setzt beim isRunning-Zweig direkt. */
        val MARKE_ALLE = Regex("""\.\s*notSentToPump\s*\(\s*true\s*\)|notSentToPump\s*=\s*true""")
        val DELIVER = Regex("""deliverTreatment\s*\(""")
        val BOLUS = Regex("""commandQueue\s*\.\s*bolus\s*\(""")
        val ABLAGE = Regex("""recordSmbNotSent\s*\(""")
    }
}
