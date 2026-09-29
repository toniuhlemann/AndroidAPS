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
 *   - `CommandSMBBolus.cancel`: nur, weil die Warteschlange `cancel()`
 *     ausschliesslich fuer noch WARTENDE Befehle ruft und den laufenden nie
 *     abbricht (Fork-Aenderung, QueueWorker/Codex B0b). Upstream-AAPS ruft
 *     `performing?.cancel()` - kommt das per Merge zurueck, ist die Marke in
 *     cancel() falsch;
 *   - `CommandQueueImplementation.bolus`: die SMB-Ablehnungen vor dem
 *     Einreihen;
 *   - `LoopPlugin.applySMBRequest`: die Tore vor `commandQueue.bolus`;
 * und solange `LoopPlugin` die Ablage mit der RT-Identitaet der Anforderung
 * nur fuer markierte Fehlschlaege und fuer den TBR-Fehlschlag schreibt, nach
 * dem die SMB gar nicht erst angefordert wird.
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
    private val arbeiter by lazy {
        quelle("implementation/src/main/kotlin/app/aaps/implementation/queue/QueueWorker.kt")
    }

    /** Der Rumpf ab [start] bis zur ersten Funktion danach - grob, aber ohne Parser. */
    private fun funktion(code: String, start: String): String {
        val a = code.indexOf(start)
        assertTrue(a >= 0) { "'$start' nicht gefunden" }
        val b = FUN.find(code, a + start.length)?.range?.first ?: code.length
        return code.substring(a, b)
    }

    @Test
    fun `CommandSMBBolus markiert nur Zweige ohne Pumpenaufruf`() {
        val execute = funktion(smbBolus, "override fun execute()")
        assertEquals(2, MARKE.findAll(execute).count()) {
            "Erwartet genau zwei markierte Zweige (Intervall, too old) in execute()"
        }
        val lieferung = DELIVER.find(execute)?.range
        assertTrue(lieferung != null) { "deliverTreatment-Aufruf in execute() nicht gefunden" }
        // Die Anweisung mit deliverTreatment endet am Zeilenende - dort darf keine Marke stehen.
        val zeile = execute.substring(lieferung!!.first).substringBefore("\n")
        assertTrue(!zeile.contains("notSentToPump")) { "der Pumpenzweig darf nie als nie gesendet gelten" }
        // cancel() ist markiert - das traegt nur mit der Warteschlangen-Invariante unten.
        val abbruch = funktion(smbBolus, "override fun cancel()")
        assertEquals(1, MARKE.findAll(abbruch).count()) { "cancel() einer wartenden SMB ist nie gesendet und traegt die Marke" }
        assertEquals(3, MARKE.findAll(smbBolus).count()) { "keine weitere Marke in CommandSMBBolus" }
    }

    /**
     * DIE INVARIANTE, AUF DER DIE MARKE IN cancel() RUHT: die Warteschlange
     * ruft cancel() nur in clear(), nur fuer WARTENDE Befehle, und bricht den
     * laufenden nie ab. Faellt diese Pruefung, darf cancel() nicht markieren.
     */
    @Test
    fun `die Warteschlange bricht nur wartende Befehle ab`() {
        val clear = funktion(warteschlange, "override fun clear()")
        assertTrue(!Regex("""performing\s*[?!]*\s*\.\s*cancel\s*\(""").containsMatchIn(clear)) {
            "clear() bricht den laufenden Befehl ab (Upstream-Form) - die Marke in CommandSMBBolus.cancel() ist dann falsch"
        }
        assertEquals(1, ABBRUCH.findAll(warteschlange).count()) {
            "cancel() wird ausserhalb von clear() gerufen - nicht mehr belegbar, dass nur wartende Befehle abgebrochen werden"
        }
        assertTrue(Regex("""queue\s*\[\s*i\s*]\s*\.\s*cancel\s*\(""").containsMatchIn(clear)) {
            "clear() bricht die wartenden Befehle nicht mehr einzeln ab"
        }
        assertEquals(0, ABBRUCH.findAll(arbeiter).count()) {
            "der QueueWorker ruft cancel() - womoeglich fuer den laufenden Befehl, dessen Ausgang UNBEKANNT ist"
        }
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
        assertEquals(0, ausserhalb) { "notSentToPump ausserhalb von bolus() in der Warteschlange ist nicht belegbar" }
    }

    @Test
    fun `der Loop markiert nur seine Tore und legt nur belegte Nichtsendungen ab`() {
        val apply = funktion(loop, "private fun applySMBRequest(")
        val bolus = BOLUS.find(apply)?.range?.first ?: -1
        assertTrue(bolus >= 0) { "commandQueue.bolus in applySMBRequest nicht gefunden" }
        val marken = MARKE.findAll(apply).map { it.range.first }.toList()
        assertEquals(3, marken.size) { "Erwartet drei markierte Tore in applySMBRequest" }
        assertTrue(marken.all { it < bolus }) { "eine Marke steht nach commandQueue.bolus" }

        val ablagen = ABLAGE.findAll(loop).map { it.range.first }.toList()
        assertEquals(2, ablagen.size) { "genau zwei Ablagestellen erwartet (SMB-Fehlschlag, TBR-Fehlschlag)" }
        var smb = 0
        var tbr = 0
        for (a in ablagen) {
            // Unmittelbar vor der Ablage steht - bis auf Leerraum und entfernte
            // Kommentare - genau ihre Bedingung.
            val davor = loop.substring(maxOf(0, a - 1500), a)
            when {
                SMB_FEHLSCHLAG.containsMatchIn(davor) -> smb++
                TBR_FEHLSCHLAG.containsMatchIn(davor) -> tbr++
            }
            val danach = loop.substring(a, minOf(loop.length, a + 400))
            assertTrue(Regex("""request\s*=\s*resultAfterConstraints\s*\.\s*rawData\s*\(\s*\)""").containsMatchIn(danach)) {
                "eine Ablage traegt nicht mehr die RT-Identitaet der Anforderung"
            }
        }
        assertEquals(1, smb) { "die Ablage im SMB-Fehlschlag haengt nicht mehr an result.notSentToPump" }
        assertEquals(1, tbr) { "die Ablage im TBR-Fehlschlag steht nicht mehr direkt im Fehlerzweig der TBR" }
    }

    /** SELBSTPRUEFUNG: jedes Muster muss im fremden Quelltext wirklich greifen. */
    @Test
    fun `der Waechter greift ueberhaupt`() {
        assertTrue(MARKE.containsMatchIn(smbBolus)) { "Marken-Muster findet in CommandSMBBolus nichts" }
        assertTrue(DELIVER.containsMatchIn(smbBolus)) { "deliverTreatment-Muster findet nichts" }
        assertTrue(MARKE_ALLE.containsMatchIn(warteschlange)) { "Marken-Muster findet in der Warteschlange nichts" }
        assertTrue(BOLUS.containsMatchIn(loop)) { "Bolus-Muster findet im Loop nichts" }
        assertTrue(ABLAGE.containsMatchIn(loop)) { "Ablage-Muster findet im Loop nichts" }
        assertTrue(ABBRUCH.containsMatchIn(warteschlange)) { "Abbruch-Muster findet in der Warteschlange nichts" }
        assertTrue(arbeiter.contains("queue.clear()")) { "QueueWorker-Quelle ist nicht die erwartete Datei" }
        // Die Bausteine der verankerten Ablage-Muster muessen im Loop vorkommen.
        assertTrue(Regex("""if\s*\(\s*result\s*\.\s*notSentToPump\s*\)""").containsMatchIn(loop)) { "SMB-Bedingung fehlt" }
        assertTrue(Regex("""}\s*else\s*\{\s*lastRun\s*\.\s*tbrSetByPump\s*=\s*result""").containsMatchIn(loop)) { "TBR-Fehlerzweig fehlt" }
    }

    private companion object {

        val FUN = Regex("""\n\s*(override\s+|private\s+|internal\s+|public\s+)*fun\s""")
        val MARKE = Regex("""\.\s*notSentToPump\s*\(\s*true\s*\)""")
        /** Builder oder Zuweisung - die Warteschlange setzt beim isRunning-Zweig direkt. */
        val MARKE_ALLE = Regex("""\.\s*notSentToPump\s*\(\s*true\s*\)|notSentToPump\s*=\s*true""")
        val DELIVER = Regex("""deliverTreatment\s*\(""")
        val BOLUS = Regex("""commandQueue\s*\.\s*bolus\s*\(""")
        val ABLAGE = Regex("""recordSmbNotSent\s*\(""")
        val ABBRUCH = Regex("""\.\s*cancel\s*\(\s*\)""")
        /** Die Ablage im SMB-Callback folgt direkt auf `if (result.notSentToPump)`. */
        val SMB_FEHLSCHLAG = Regex("""if\s*\(\s*result\s*\.\s*notSentToPump\s*\)\s*lastRun\s*\.\s*$""")
        /**
         * Die Ablage im TBR-Callback ist die erste Anweisung nach den beiden
         * Zuweisungen des Fehlerzweigs, bewacht nur von isBolusRequested - dort
         * wird applySMBRequest nie gerufen.
         */
        val TBR_FEHLSCHLAG = Regex(
            """}\s*else\s*\{\s*lastRun\s*\.\s*tbrSetByPump\s*=\s*result\s+""" +
                """lastRun\s*\.\s*lastTBRRequest\s*=\s*lastRun\s*\.\s*lastAPSRun\s+""" +
                """if\s*\(\s*resultAfterConstraints\s*\.\s*isBolusRequested\s*\)\s*lastRun\s*\.\s*$"""
        )
    }
}
