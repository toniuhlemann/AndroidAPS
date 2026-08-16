package app.aaps.fuse.plugin.ledger

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER WAECHTER UEBER DIE EINZIGE FREMDANNAHME VON [app.aaps.fuse.core.ledger.NotSentProof].
 *
 * Regel C entlastet eine Ledger-Zeile, wenn `lastRun.smbSetByPump == null`
 * geblieben ist. Dieser Schluss - "der Apply-Block wurde nie betreten, also
 * ging kein Bolus-Kommando hinaus" - traegt NUR, solange in AAPS' LoopPlugin
 * die Zuweisung des Platzhalters VOR jedem erreichbaren `commandQueue.bolus()`
 * liegt.
 *
 * Das ist eine Annahme ueber FREMDEN Code, und sie wird bei jedem Upstream-
 * Merge neu gewuerfelt. Bricht sie, entlastet FUSE eine Menge, die doch
 * geflossen ist - es rechnet dann mit ZU WENIG Insulin und dosiert ZU VIEL.
 * Das ist die gefaehrliche Richtung, und sie waere still: kein Fehler, kein
 * Alarm, nur eine Zeile weniger Haftung.
 *
 * Deshalb dieser statische Waechter. Er ersetzt kein Verhaltenstest, er
 * beantwortet nur die eine Frage, die ein Merge beantworten muss: liegt die
 * Zuweisung noch vor dem Bolusaufruf, und gibt es weiterhin genau einen?
 *
 * SCHLAEGT ER FEHL, ist die richtige Reaktion NICHT, ihn anzupassen, sondern
 * NotSentProof.reasonFor Regel C zu pruefen (und im Zweifel abzuschalten).
 */
class LoopPluginAnnahmeWaechterTest {

    private fun loopPluginQuelle(): String {
        // Vom Modulverzeichnis (fuse/plugin) aus zum Schwestermodul.
        val kandidaten = listOf(
            "../../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt",
            "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt",
        )
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "LoopPlugin.kt nicht gefunden - Waechter kann die Annahme nicht pruefen" }
        return f.readText()
    }

    @Test
    fun `der Platzhalter wird vor jedem Bolusaufruf gesetzt`() {
        val code = ohneKommentare()

        val platzhalter = PLATZHALTER.find(code)?.range?.first ?: -1
        assertTrue(platzhalter >= 0) {
            "Die Zuweisung eines NICHT-null-Platzhalters an lastRun.smbSetByPump fehlt. " +
                "NotSentProof Regel C (BOLUS_IN_QUEUE) haengt daran - bitte Regel C pruefen."
        }

        val bolusAufrufe = BOLUS.findAll(code).map { it.range.first }.toList()
        assertTrue(bolusAufrufe.size == 1) {
            "Erwartet genau EIN commandQueue.bolus() in LoopPlugin, gefunden ${bolusAufrufe.size}. " +
                "Ein zweiter Bolusweg koennte den Platzhalter umgehen und NotSentProof Regel C " +
                "still entwerten."
        }
        assertTrue(platzhalter < bolusAufrufe.first()) {
            "Der Platzhalter liegt NICHT vor dem Bolusaufruf - NotSentProof Regel C traegt nicht mehr."
        }
    }

    /**
     * Der zweite Teil der Annahme: die Zuweisung haengt an `isBolusRequested`.
     * Wuerde sie unbedingt erfolgen, waere ein `null` kein Beweis mehr fuer
     * "nichts angefordert", sondern nur noch fuer "Block nicht betreten" -
     * die Aussage von Regel C bliebe zwar richtig, aber der Test haelt fest,
     * worauf sie sich stuetzt.
     */
    @Test
    fun `der Platzhalter haengt an der Bolusanforderung`() {
        val code = ohneKommentare()
        val treffer = PLATZHALTER.find(code)?.range?.first ?: -1
        assertTrue(treffer >= 0) { "Platzhalter-Zuweisung nicht gefunden" }
        // Zeichenfenster statt Zeilenfenster: die Bedingung darf ueber mehrere
        // Zeilen vor der Zuweisung stehen, ohne dass der Waechter sie verliert.
        val davor = code.substring(maxOf(0, treffer - 400), minOf(code.length, treffer + 120))
        assertTrue(davor.contains("isBolusRequested")) {
            "Die Platzhalter-Zuweisung steht nicht mehr im isBolusRequested-Zweig - " +
                "die Grundlage von NotSentProof Regel C hat sich geaendert."
        }
    }

    /**
     * SELBSTPRUEFUNG DES WAECHTERS. Ein statischer Test, dessen Muster ins
     * Leere greift, meldet fuer immer gruen - das ist schlimmer als kein Test.
     * Beide Anker muessen im fremden Quelltext wirklich vorkommen.
     */
    @Test
    fun `der Waechter greift ueberhaupt`() {
        val code = ohneKommentare()
        assertTrue(PLATZHALTER.containsMatchIn(code)) { "Platzhalter-Muster findet nichts" }
        assertTrue(BOLUS.containsMatchIn(code)) { "Bolus-Muster findet nichts" }
        assertTrue(code.contains("lastRun")) { "liest der Test ueberhaupt LoopPlugin?" }
    }

    /**
     * Kommentare raus, bevor gesucht wird - sonst zaehlt ein Kommentar, der
     * `commandQueue.bolus(` bloss ERWAEHNT, als zweiter Bolusweg.
     */
    private fun ohneKommentare(): String =
        loopPluginQuelle()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    private companion object {

        /**
         * WHITESPACE- UND ZEILENTOLERANT - Auditbefund P1-6 (16.08.2026).
         *
         * Die erste Fassung suchte zeilenweise mit `contains("commandQueue.bolus(")`.
         * Eine ausgefuehrte Mutationsprobe des Gesamtaudits hat das als blind
         * nachgewiesen: ein ZWEITER Bolusweg, ueber zwei Zeilen geschrieben,
         * wurde nicht mitgezaehlt - der Waechter blieb gruen, obwohl genau der
         * Fall eingetreten war, gegen den er gebaut wurde (ein Bolusweg, der
         * den Platzhalter umgeht und NotSentProof Regel C entwertet).
         *
         * Deshalb jetzt Regex ueber den ganzen Quelltext, mit `\s*` an jeder
         * Fuge, und Positionsvergleich ueber ZEICHEN statt Zeilennummern.
         */
        val BOLUS = Regex("""commandQueue\s*\.\s*bolus\s*\(""")

        /**
         * Die Zuweisung eines NICHT-null-Platzhalters, GEKOPPELT an
         * `isBolusRequested`.
         *
         * Die Kopplung ist nicht Kosmetik, sondern traegt die Aussage. Im
         * Bestand gibt es VIER Zuweisungen an `smbSetByPump`: zwei
         * Ruecksetzungen auf null, den Platzhalter im isBolusRequested-Zweig
         * und eine im Erfolgs-Callback. Nur der Platzhalter beweist "ein Bolus
         * wurde angefordert, bevor das Kommando hinausging" - genau darauf
         * ruht NotSentProof Regel C.
         *
         * Ohne die Kopplung wuerde der Waechter die Callback-Zuweisung als
         * Platzhalter durchgehen lassen: entwertet jemand die echte Stelle,
         * faende der Test die spaetere und bliebe gruen, obwohl `null` dann
         * nicht mehr "nichts angefordert" heisst, sondern nur noch "der
         * Callback kam noch nicht" - und das ist die gefaehrliche Richtung
         * (FUSE entlastet eine Menge, die doch geflossen ist).
         *
         * `(?!\s*null\b)` schliesst die Ruecksetzungen aus, `\s*` an jeder
         * Fuge macht es zeilentolerant.
         */
        val PLATZHALTER = Regex(
            """isBolusRequested\s*\)\s*lastRun\s*\.\s*smbSetByPump\s*=\s*(?!\s*null\b)"""
        )
    }
}
