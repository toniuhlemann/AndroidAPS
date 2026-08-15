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
        val quelle = loopPluginQuelle()
        val zeilen = quelle.lines()

        val platzhalter = zeilen.indexOfFirst {
            it.contains("lastRun.smbSetByPump =") && !it.contains("null")
        }
        assertTrue(platzhalter >= 0) {
            "Die Zuweisung eines NICHT-null-Platzhalters an lastRun.smbSetByPump fehlt. " +
                "NotSentProof Regel C (BOLUS_IN_QUEUE) haengt daran - bitte Regel C pruefen."
        }

        val bolusAufrufe = zeilen.mapIndexedNotNull { i, z ->
            i.takeIf { z.contains("commandQueue.bolus(") }
        }
        assertTrue(bolusAufrufe.size == 1) {
            "Erwartet genau EIN commandQueue.bolus() in LoopPlugin, gefunden ${bolusAufrufe.size} " +
                "(Zeilen ${bolusAufrufe.map { it + 1 }}). Ein zweiter Bolusweg koennte den " +
                "Platzhalter umgehen und NotSentProof Regel C still entwerten."
        }
        assertTrue(platzhalter < bolusAufrufe.first()) {
            "Der Platzhalter (Zeile ${platzhalter + 1}) liegt NICHT vor dem Bolusaufruf " +
                "(Zeile ${bolusAufrufe.first() + 1}) - NotSentProof Regel C traegt nicht mehr."
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
        val quelle = loopPluginQuelle()
        // Das ERSTE Vorkommen ist die null-Ruecksetzung - gesucht ist die
        // Zuweisung des Platzhalters, also die erste NICHT-null-Stelle.
        val zeilen = quelle.lines()
        val treffer = zeilen.indexOfFirst {
            it.contains("lastRun.smbSetByPump =") && !it.contains("null")
        }
        assertTrue(treffer >= 0) { "Platzhalter-Zuweisung nicht gefunden" }
        // Die Bedingung steht im Bestand in DERSELBEN Zeile wie die Zuweisung,
        // deshalb die Trefferzeile einschliessen.
        val davor = zeilen.subList(maxOf(0, treffer - 8), treffer + 1).joinToString(" ")
        assertTrue(davor.contains("isBolusRequested")) {
            "Die Platzhalter-Zuweisung steht nicht mehr im isBolusRequested-Zweig - " +
                "die Grundlage von NotSentProof Regel C hat sich geaendert."
        }
    }
}
