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
 *   - `CommandSMBBolus.cancel`: nur, solange der Befehl NICHT GESTARTET ist.
 *     v4 bricht wartende Befehle ab (removeAll, drain - beide nur ueber die
 *     Warteschlange, nie `performing`), aber der CommandExecutor bricht auch
 *     den LAUFENDEN ab, wenn sein Treiber geworfen hat. Dort ist der Ausgang
 *     unbekannt - deshalb das Merkmal `started`, gesetzt als erste Anweisung
 *     von execute();
 *   - `CommandQueueImplementation.bolus`: die SMB-Ablehnungen vor dem
 *     Einreihen (Betriebsart-Tor, Bolus in der Schlange, aelterer letzter
 *     Bolus, "executing right now");
 *   - `LoopPlugin.applySMBRequest`: die Tore vor `commandQueue.bolus`;
 * und solange `LoopPlugin` die Ablage mit der RT-Identitaet der Anforderung
 * nur fuer markierte Fehlschlaege und fuer den TBR-Fehlschlag schreibt, nach
 * dem die SMB gar nicht erst angefordert wird.
 *
 * Bricht eine davon (etwa bei einem AAPS-Merge), entlastet FUSE womoeglich
 * eine Menge, die doch geflossen ist - es rechnet mit ZU WENIG Insulin und
 * dosiert ZU VIEL. Deshalb dieser statische Waechter, gebaut wie
 * [LoopPluginAnnahmeWaechterTest]. Die Verhaltenstests stehen daneben in
 * `CommandSMBBolusTest` und `CommandQueueImplementationTest` (Modul implementation).
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
        quelle("implementation/src/commonMain/kotlin/app/aaps/implementation/queue/commands/CommandSMBBolus.kt")
    }
    private val warteschlange by lazy {
        quelle("implementation/src/commonMain/kotlin/app/aaps/implementation/queue/CommandQueueImplementation.kt")
    }
    private val ausfuehrer by lazy {
        quelle("implementation/src/commonMain/kotlin/app/aaps/implementation/queue/CommandExecutor.kt")
    }
    private val befehl by lazy {
        quelle("core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/queue/Command.kt")
    }
    private val loop by lazy {
        quelle("plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt")
    }

    /** Der Rumpf ab [start] bis zur ersten Funktion danach - grob, aber ohne Parser. */
    private fun funktion(code: String, start: String): String {
        val a = code.indexOf(start)
        assertTrue(a >= 0) { "'$start' nicht gefunden" }
        val b = FUN.find(code, a + start.length)?.range?.first ?: code.length
        return code.substring(a, b)
    }

    /** Der Block ab der ersten `{` nach [ab], bis zur passenden `}` (Zeichenketten ohne Klammern vorausgesetzt). */
    private fun block(code: String, ab: Int): IntRange {
        val auf = code.indexOf('{', ab)
        assertTrue(auf >= 0) { "kein Block nach Position $ab" }
        var tiefe = 0
        for (i in auf until code.length) {
            when (code[i]) {
                '{' -> tiefe++
                '}' -> if (--tiefe == 0) return auf..i
            }
        }
        error("Block ab $auf nicht geschlossen")
    }

    @Test
    fun `CommandSMBBolus markiert nur Zweige ohne Pumpenaufruf`() {
        val execute = funktion(smbBolus, "override suspend fun execute()")
        assertTrue(START_ZUERST.containsMatchIn(execute)) {
            "started = true ist nicht mehr die erste Anweisung von execute() - ein Wurf davor liesse cancel() markieren"
        }
        assertEquals(2, MARKE.findAll(execute).count()) {
            "Erwartet genau zwei markierte Zweige (Intervall, too old) in execute()"
        }
        val lieferung = DELIVER.find(execute)?.range
        assertTrue(lieferung != null) { "deliverTreatment-Aufruf in execute() nicht gefunden" }
        // Die Anweisung mit deliverTreatment endet am Zeilenende - dort darf keine Marke stehen.
        val zeile = execute.substring(lieferung!!.first).substringBefore("\n")
        assertTrue(!zeile.contains("notSentToPump")) { "der Pumpenzweig darf nie als nie gesendet gelten" }

        val abbruch = funktion(smbBolus, "override fun cancel(")
        assertEquals(1, MARKE.findAll(abbruch).count()) { "cancel() markiert genau einmal - nur den nie gestarteten Befehl" }
        assertTrue(ABBRUCH_NUR_UNGESTARTET.containsMatchIn(abbruch)) {
            "die Marke in cancel() haengt nicht mehr am else-Zweig von if (started)"
        }
        assertEquals(3, MARKE.findAll(smbBolus).count()) { "keine weitere Marke in CommandSMBBolus" }
        // Das Merkmal wird nur in execute() gesetzt und nie zurueckgesetzt.
        assertEquals(1, Regex("""started\s*=\s*true""").findAll(smbBolus).count()) { "started wird ausserhalb von execute() gesetzt" }
        assertEquals(1, Regex("""started\s*=\s*false""").findAll(smbBolus).count()) { "started wird zurueckgesetzt" }
    }

    /**
     * DIE INVARIANTE, AUF DER DIE MARKE IN cancel() RUHT: die Warteschlange
     * bricht nur Befehle ab, die noch in ihr warten, und gibt einen Befehl
     * erst aus ihr heraus, wenn er ausgefuehrt wird. Der einzige Abbruch eines
     * laufenden Befehls ist der des CommandExecutors nach einem Treiberwurf -
     * und der kommt erst nach executeAndComplete(), also nach `started = true`.
     */
    @Test
    fun `nur wartende Befehle werden ohne Start abgebrochen`() {
        val alle = ABBRUCH.findAll(warteschlange).count()
        val wartende = ABBRUCH_WARTEND.findAll(warteschlange).count()
        assertEquals(2, wartende) { "removeAll und drain brechen nicht mehr ueber queue[i] ab" }
        assertEquals(wartende, alle) { "die Warteschlange bricht einen Befehl ausserhalb der Schlange ab (etwa performing)" }
        assertTrue(!Regex("""performing\s*[?!]*\s*\.\s*cancel\s*\(""").containsMatchIn(warteschlange)) {
            "die Warteschlange bricht den laufenden Befehl ab - die Marke in CommandSMBBolus.cancel() ist dann falsch"
        }
        assertTrue(ABHOLEN.containsMatchIn(funktion(warteschlange, "override fun pickup()"))) {
            "pickup() nimmt den Befehl nicht mehr in einem Schritt aus der Schlange und setzt performing"
        }

        // Der CommandExecutor: genau ein Abbruch, und der folgt dem Ausfuehrungsversuch im catch.
        val imAusfuehrer = ABBRUCH.findAll(ausfuehrer).map { it.range.first }.toList()
        assertEquals(1, imAusfuehrer.size) { "der CommandExecutor bricht an mehr als einer Stelle ab" }
        val versuch = ausfuehrer.indexOf("cmd.executeAndComplete()")
        assertTrue(versuch in 0 until imAusfuehrer.single()) { "der Abbruch im CommandExecutor steht nicht nach executeAndComplete()" }
        assertTrue(ausfuehrer.substring(versuch, imAusfuehrer.single()).contains("catch")) {
            "der Abbruch im CommandExecutor ist nicht mehr der catch-Zweig des Ausfuehrungsversuchs"
        }
        // executeAndComplete() ruft execute() direkt - nichts davor, das werfen koennte.
        assertTrue(AUSFUEHREN_DIREKT.containsMatchIn(befehl)) {
            "Command.executeAndComplete() ruft execute() nicht mehr als erstes"
        }
    }

    @Test
    fun `die Warteschlange markiert nur SMB-Ablehnungen vor dem Einreihen`() {
        val bolus = funktion(warteschlange, "override suspend fun bolus(")
        val einreihen = bolus.indexOf("add(command)")
        assertTrue(einreihen >= 0) { "Einreihen in bolus() nicht gefunden" }
        assertTrue(bolus.indexOf("CommandSMBBolus(") in 0 until einreihen) { "der SMB-Befehl entsteht nicht vor dem Einreihen" }
        val marken = MARKE.findAll(bolus).map { it.range.first }.toList()
        assertEquals(4, marken.size) { "Erwartet vier markierte Ablehnungen in bolus(), gefunden ${marken.size}" }
        assertTrue(marken.all { it < einreihen }) { "eine Marke steht nach dem Einreihen" }
        // Tor und "executing right now" gelten fuer jeden Bolus - markiert wird dort nur die SMB.
        assertEquals(2, NUR_SMB.findAll(bolus).count()) { "Tor oder executingNowError markieren nicht mehr nur die SMB" }
        val ausserhalb = MARKE.findAll(warteschlange.replace(bolus, "")).count()
        assertEquals(0, ausserhalb) { "notSentToPump ausserhalb von bolus() in der Warteschlange ist nicht belegbar" }
    }

    @Test
    fun `der Loop markiert nur seine Tore und legt nur belegte Nichtsendungen ab`() {
        val apply = funktion(loop, "private suspend fun applySMBRequest(")
        val bolus = BOLUS.find(apply)?.range?.first ?: -1
        assertTrue(bolus >= 0) { "commandQueue.bolus in applySMBRequest nicht gefunden" }
        val marken = MARKE.findAll(apply).map { it.range.first }.toList()
        assertEquals(3, marken.size) { "Erwartet drei markierte Tore in applySMBRequest" }
        assertTrue(marken.all { it < bolus }) { "eine Marke steht nach commandQueue.bolus" }
        assertEquals(3, MARKE.findAll(loop).count()) { "eine Marke im Loop ausserhalb von applySMBRequest" }

        val ablagen = ABLAGE.findAll(loop).map { it.range.first }.toList()
        assertEquals(2, ablagen.size) { "genau zwei Ablagestellen erwartet (SMB-Fehlschlag, TBR-Fehlschlag)" }
        for (a in ablagen) {
            val danach = loop.substring(a, minOf(loop.length, a + 400))
            assertTrue(Regex("""request\s*=\s*resultAfterConstraints\s*\.\s*rawData\s*\(\s*\)""").containsMatchIn(danach)) {
                "eine Ablage traegt nicht mehr die RT-Identitaet der Anforderung"
            }
        }

        // SMB-Fehlschlag: die Ablage folgt direkt auf `if (smbResult.notSentToPump)`.
        val smb = ablagen.count { SMB_FEHLSCHLAG.containsMatchIn(loop.substring(maxOf(0, it - 300), it)) }
        assertEquals(1, smb) { "die Ablage im SMB-Fehlschlag haengt nicht mehr an smbResult.notSentToPump" }

        // TBR-Fehlschlag: der else-Zweig GENAU des TBR-Erfolgstests, bewacht nur von isBolusRequested.
        val tbrTest = TBR_ERFOLG.find(loop)?.range?.last ?: -1
        assertTrue(tbrTest >= 0) { "der TBR-Erfolgstest im Loop fehlt" }
        val erfolg = block(loop, tbrTest)
        val rest = loop.substring(erfolg.last + 1)
        val sonst = TBR_SONST.find(rest)
        assertTrue(sonst != null && sonst.range.first == 0) {
            "auf den TBR-Erfolgsblock folgt nicht mehr direkt else if (resultAfterConstraints.isBolusRequested)"
        }
        val sonstBlock = block(loop, erfolg.last + 1 + sonst!!.range.last)
        val imSonst = ablagen.count { it in sonstBlock }
        assertEquals(1, imSonst) { "die Ablage im TBR-Fehlschlag steht nicht mehr im else-Zweig des TBR-Erfolgstests" }
        // Im Erfolgsblock steht nur die SMB-Ablage - und die nur unter ihrer Bedingung (oben).
        assertEquals(1, ablagen.count { it in erfolg }) { "eine Ablage im TBR-Erfolgsblock ausser der des SMB-Fehlschlags" }
    }

    /** SELBSTPRUEFUNG: jedes Muster muss im fremden Quelltext wirklich greifen. */
    @Test
    fun `der Waechter greift ueberhaupt`() {
        assertTrue(MARKE.containsMatchIn(smbBolus)) { "Marken-Muster findet in CommandSMBBolus nichts" }
        assertTrue(DELIVER.containsMatchIn(smbBolus)) { "deliverTreatment-Muster findet nichts" }
        assertTrue(START_ZUERST.containsMatchIn(smbBolus)) { "started-Muster findet nichts" }
        assertTrue(MARKE.containsMatchIn(warteschlange)) { "Marken-Muster findet in der Warteschlange nichts" }
        assertTrue(ABBRUCH_WARTEND.containsMatchIn(warteschlange)) { "Abbruch-Muster findet in der Warteschlange nichts" }
        assertTrue(ausfuehrer.contains("queue.pickup()")) { "CommandExecutor-Quelle ist nicht die erwartete Datei" }
        assertTrue(BOLUS.containsMatchIn(loop)) { "Bolus-Muster findet im Loop nichts" }
        assertTrue(ABLAGE.containsMatchIn(loop)) { "Ablage-Muster findet im Loop nichts" }
        assertTrue(SMB_FEHLSCHLAG.containsMatchIn(loop.substring(0, ABLAGE.find(loop)!!.range.first))) { "SMB-Bedingung fehlt" }
        assertTrue(TBR_ERFOLG.containsMatchIn(loop)) { "TBR-Erfolgstest fehlt" }
    }

    private companion object {

        val FUN = Regex("""\n\s*(override\s+|private\s+|internal\s+|public\s+|suspend\s+)*fun\s""")
        val MARKE = Regex("""\.\s*notSentToPump\s*\(\s*true\s*\)""")
        val DELIVER = Regex("""deliverTreatment\s*\(""")
        val START_ZUERST = Regex("""override\s+suspend\s+fun\s+execute\s*\(\s*\)\s*:\s*PumpEnactResult\s*\{\s*started\s*=\s*true\b""")
        val ABBRUCH_NUR_UNGESTARTET = Regex(
            """if\s*\(\s*started\s*\)\s*super\s*\.\s*cancel\s*\([^)]*\)\s*else\s+completion\s*\.\s*complete\s*\([^\n]*\.\s*notSentToPump\s*\(\s*true\s*\)"""
        )
        val ABBRUCH = Regex("""\.\s*cancel\s*\(""")
        val ABBRUCH_WARTEND = Regex("""queue\s*\[\s*i\s*]\s*\.\s*cancel\s*\(""")
        val ABHOLEN = Regex("""queue\s*\.\s*removeFirstOrNull\s*\(\s*\)\s*\.\s*also\s*\{\s*performing\s*=\s*it\s*}""")
        val AUSFUEHREN_DIREKT = Regex("""suspend\s+fun\s+executeAndComplete\s*\(\s*\)\s*\{\s*completion\s*\.\s*complete\s*\(\s*execute\s*\(\s*\)\s*\)\s*}""")
        val NUR_SMB = Regex("""if\s*\(\s*type\s*==\s*CommandType\s*\.\s*SMB_BOLUS\s*\)\s*\w+\s*\.\s*notSentToPump\s*\(\s*true\s*\)""")
        val BOLUS = Regex("""commandQueue\s*\.\s*bolus\s*\(""")
        val ABLAGE = Regex("""recordSmbNotSent\s*\(""")

        /** Die Ablage im SMB-Fehlschlag folgt direkt auf `if (smbResult.notSentToPump)`. */
        val SMB_FEHLSCHLAG = Regex("""if\s*\(\s*smbResult\s*\.\s*notSentToPump\s*\)\s*lastRun\s*\.\s*$""")
        val TBR_ERFOLG = Regex("""if\s*\(\s*tbrResult\s*\.\s*enacted\s*\|\|\s*tbrResult\s*\.\s*success\s*\)""")
        val TBR_SONST = Regex("""^\s*else\s+if\s*\(\s*resultAfterConstraints\s*\.\s*isBolusRequested\s*\)""")
    }
}
