package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER WAECHTER UEBER DIE LAUFSPERRE VON [FusePlugin.invoke].
 *
 * Zwei FUSE-Laeufe duerfen sich nie ueberlappen: sie teilen Ledger,
 * Publikationen und die Felder des Vorzyklus. `LoopPlugin.invoke` ist zwar
 * `@Synchronized`, aber `OpenAPSFragment` ruft `activeAPS.invoke` direkt auf,
 * am Lock des Loop vorbei. Deshalb traegt FusePlugin eine eigene Sperre - und
 * dieser Waechter haelt fest, dass der Lauf nur darunter erreichbar ist.
 *
 * Ein Verhaltenstest braeuchte das ganze Plugin mit allen Abhaengigkeiten. Die
 * Frage, die ein Umbau oder Merge beantworten muss, ist aber strukturell: ruft
 * `invoke` den Lauf unter der Sperre auf, und fuehrt kein zweiter Weg daran
 * vorbei zum Lauf?
 */
class FusePluginLaufSperreWaechterTest {

    private fun quelle(): String {
        // Vom Modulverzeichnis (fuse/plugin) aus; der zweite Kandidat fuer einen
        // Lauf aus dem Wurzelverzeichnis.
        val kandidaten = listOf(
            "src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
            "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
        )
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "FusePlugin.kt nicht gefunden - der Waechter kann die Sperre nicht pruefen" }
        // Kommentare raus, damit eine blosse Erwaehnung nicht als Aufruf zaehlt.
        return f.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `invoke fuehrt den Lauf nur unter der Laufsperre aus`() {
        assertTrue(INVOKE_UNTER_SPERRE.containsMatchIn(quelle())) {
            "FusePlugin.invoke ruft den Lauf nicht mehr unter synchronized(laufSperre) auf - " +
                "zwei Laeufe koennten sich ueberlappen."
        }
    }

    @Test
    fun `der Lauf hat keinen zweiten Aufrufer an der Sperre vorbei`() {
        val aufrufe = AUFRUF.findAll(quelle()).count()
        assertEquals(1, aufrufe) {
            "invokeUnterSperre wird $aufrufe-mal aufgerufen; erlaubt ist genau der Aufruf in invoke."
        }
    }

    /**
     * KI-177: DER MARKER-DRUCK schreibt den Ledger ausserhalb des Zyklus. Er
     * muss den GANZEN Umschaltvorgang unter derselben Sperre fuehren - nicht
     * nur das Speichern, denn die Episodenfelder aendert er vorher.
     */
    @Test
    fun `der Marker-Druck laeuft ganz unter der Laufsperre`() {
        val code = quelle()
        val oeffentlich = MARKER_OEFFENTLICH.find(code)?.range?.first ?: -1
        val privat = MARKER_DEKLARATION.find(code)?.range?.first ?: -1
        assertTrue(oeffentlich >= 0 && privat > oeffentlich) { "toggleMealMarker und toggleMealMarkerUnterSperre nicht gefunden" }
        val huelle = code.substring(oeffentlich, privat)
        val sperre = huelle.indexOf("synchronized(laufSperre)")
        val aufruf = MARKER_AUFRUF.find(huelle)?.range?.first ?: -1
        assertTrue(sperre >= 0 && aufruf > sperre) {
            "toggleMealMarker ruft den Umschaltvorgang nicht mehr unter synchronized(laufSperre) auf."
        }
        assertEquals(1, MARKER_AUFRUF.findAll(code).count()) {
            "toggleMealMarkerUnterSperre darf genau einmal aufgerufen werden - in toggleMealMarker."
        }
    }

    /**
     * Das Speichern des Markerzustands darf nur INNERHALB des gesperrten
     * Umschaltvorgangs stehen. Ein zweiter Aufrufer von persistiereMarkerZustand
     * waere wieder ein Schreiber an der Sperre vorbei.
     */
    @Test
    fun `der Markerzustand wird nur im gesperrten Umschaltvorgang gespeichert`() {
        val code = quelle()
        val start = MARKER_DEKLARATION.find(code)?.range?.first ?: -1
        assertTrue(start >= 0) { "toggleMealMarkerUnterSperre nicht gefunden" }
        val ende = NAECHSTE_FUNKTION.find(code, start + 1)?.range?.first ?: code.length
        val aufrufe = PERSIST_AUFRUF.findAll(code).map { it.range.first }.toList()
        assertTrue(aufrufe.isNotEmpty()) { "persistiereMarkerZustand wird gar nicht aufgerufen - greift der Waechter?" }
        assertTrue(aufrufe.all { it in start until ende }) {
            "persistiereMarkerZustand wird ausserhalb von toggleMealMarkerUnterSperre aufgerufen."
        }
    }

    /**
     * SELBSTPRUEFUNG. Ein statischer Test, dessen Muster ins Leere greift,
     * meldet fuer immer gruen.
     */
    @Test
    fun `der Waechter greift ueberhaupt`() {
        val code = quelle()
        assertTrue(SPERROBJEKT.containsMatchIn(code)) { "Sperrobjekt laufSperre nicht gefunden" }
        assertTrue(DEKLARATION.containsMatchIn(code)) { "Lauf-Funktion invokeUnterSperre nicht gefunden" }
        assertTrue(AUFRUF.containsMatchIn(code)) { "Aufruf-Muster findet nichts" }
        assertTrue(MARKER_OEFFENTLICH.containsMatchIn(code)) { "toggleMealMarker nicht gefunden" }
        assertTrue(MARKER_DEKLARATION.containsMatchIn(code)) { "toggleMealMarkerUnterSperre nicht gefunden" }
        assertTrue(PERSIST_AUFRUF.containsMatchIn(code)) { "persistiereMarkerZustand-Aufruf nicht gefunden" }
    }

    private companion object {

        /** `invoke(...) { synchronized(laufSperre) { invokeUnterSperre(...) } }`, zeilentolerant. */
        val INVOKE_UNTER_SPERRE = Regex(
            """override\s+fun\s+invoke\s*\(\s*initiator\s*:\s*String\s*,\s*tempBasalFallback\s*:\s*Boolean\s*\)\s*\{\s*""" +
                """synchronized\s*\(\s*laufSperre\s*\)\s*\{\s*invokeUnterSperre\s*\(\s*initiator\s*,\s*tempBasalFallback\s*\)\s*\}\s*\}""",
        )

        /** Aufrufe, nicht die Deklaration: davor steht kein `fun`. */
        val AUFRUF = Regex("""(?<!fun\s)\binvokeUnterSperre\s*\(""")

        val DEKLARATION = Regex("""private\s+fun\s+invokeUnterSperre\s*\(""")

        val SPERROBJEKT = Regex("""private\s+val\s+laufSperre\s*=\s*Any\s*\(\s*\)""")

        /** Die oeffentliche Huelle des Marker-Drucks (nicht die private Funktion). */
        val MARKER_OEFFENTLICH = Regex("""(?<!private\s)\bfun\s+toggleMealMarker\s*\(""")

        val MARKER_DEKLARATION = Regex("""private\s+fun\s+toggleMealMarkerUnterSperre\s*\(""")

        val MARKER_AUFRUF = Regex("""(?<!fun\s)\btoggleMealMarkerUnterSperre\s*\(""")

        val PERSIST_AUFRUF = Regex("""(?<!fun\s)\bpersistiereMarkerZustand\s*\(""")

        /** Beginn der naechsten Funktion auf Klassenebene (vier Leerzeichen eingerueckt). */
        val NAECHSTE_FUNKTION = Regex("""\n {4}(?:(?:private|override|internal|public)\s+)?fun\s""")
    }
}
