package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER WAECHTER UEBER DIE LAUFSPERRE VON [FusePlugin.invoke].
 *
 * Zwei FUSE-Laeufe duerfen sich nie ueberlappen: sie teilen Ledger,
 * Publikationen und die Felder des Vorzyklus. Der Loop serialisiert seine
 * eigenen Laeufe, aber nicht jeden Aufrufer von `activeAPS.invoke`. Deshalb
 * traegt FusePlugin eine eigene Sperre - und dieser Waechter haelt fest, dass
 * der Lauf nur darunter erreichbar ist.
 *
 * v4: die Sperre ist eine Coroutine-`Mutex` statt `synchronized`. Sie ist NICHT
 * wiedereintrittsfaehig: wer sie haelt und eine sperrende Fassung ruft, haengt
 * fuer immer. Deshalb gibt es zu jedem gesperrten Vorgang eine `...UnterSperre`-
 * Fassung, und dieser Waechter prueft beide Richtungen: der Vorgang ist nur
 * unter der Sperre erreichbar, und unter der Sperre wird nie die sperrende
 * Fassung gerufen.
 *
 * Ein Verhaltenstest braeuchte das ganze Plugin mit allen Abhaengigkeiten. Die
 * Frage, die ein Umbau oder Merge beantworten muss, ist aber strukturell.
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

    /** Der Rumpf der Funktion, deren Deklaration [deklaration] trifft, bis zur naechsten Funktion auf Klassenebene. */
    private fun rumpf(code: String, deklaration: Regex): String {
        val start = deklaration.find(code)?.range?.first ?: -1
        assertTrue(start >= 0) { "Deklaration ${deklaration.pattern} nicht gefunden" }
        val ende = NAECHSTE_FUNKTION.find(code, start + 1)?.range?.first ?: code.length
        return code.substring(start, ende)
    }

    @Test
    fun `invoke fuehrt den Lauf nur unter der Laufsperre aus`() {
        assertTrue(INVOKE_UNTER_SPERRE.containsMatchIn(quelle())) {
            "FusePlugin.invoke ruft den Lauf nicht mehr unter laufSperre.withLock auf - " +
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
     *
     * v4: zwei Aufrufer des Umschaltvorgangs - der Knopf ([FusePlugin.toggleMealMarker],
     * sperrend) und die faellige Ruecknahme (`faelligeRuecknahmeUnterSperre`, die
     * selbst nur unter der Sperre laeuft, siehe unten).
     */
    @Test
    fun `der Marker-Druck laeuft ganz unter der Laufsperre`() {
        val code = quelle()
        val huelle = rumpf(code, MARKER_OEFFENTLICH)
        val sperre = huelle.indexOf("laufSperre.withLock")
        val aufruf = MARKER_AUFRUF.find(huelle)?.range?.first ?: -1
        assertTrue(sperre >= 0 && aufruf > sperre) {
            "toggleMealMarker ruft den Umschaltvorgang nicht mehr unter laufSperre.withLock auf."
        }
        val ruecknahme = rumpf(code, RUECKNAHME_UNTER_SPERRE_DEKLARATION)
        assertEquals(1, MARKER_AUFRUF.findAll(huelle).count()) { "toggleMealMarker ruft den Umschaltvorgang mehrfach" }
        assertEquals(2, MARKER_AUFRUF.findAll(code).count()) {
            "toggleMealMarkerUnterSperre darf genau zweimal aufgerufen werden - in toggleMealMarker und in der faelligen Ruecknahme."
        }
        assertEquals(1, MARKER_AUFRUF.findAll(ruecknahme).count()) { "die faellige Ruecknahme ruft den Umschaltvorgang nicht (mehr) genau einmal" }
    }

    /** Die faellige Ruecknahme laeuft nur unter der Sperre: ueber ihre sperrende Huelle oder aus dem Lauf. */
    @Test
    fun `die faellige Ruecknahme laeuft nur unter der Laufsperre`() {
        val code = quelle()
        assertTrue(RUECKNAHME_HUELLE.containsMatchIn(code)) {
            "faelligeRuecknahme ist nicht mehr die sperrende Huelle um faelligeRuecknahmeUnterSperre"
        }
        val aufrufe = RUECKNAHME_UNTER_SPERRE_AUFRUF.findAll(code).map { it.range.first }.toList()
        assertEquals(2, aufrufe.size) { "faelligeRuecknahmeUnterSperre: erwartet zwei Aufrufer (Huelle, Lauf), gefunden ${aufrufe.size}" }
        val lauf = rumpf(code, DEKLARATION)
        assertEquals(1, RUECKNAHME_UNTER_SPERRE_AUFRUF.findAll(lauf).count()) { "der Lauf ruft die faellige Ruecknahme nicht unter der Sperre" }
    }

    /**
     * DIE KEHRSEITE DER NICHT WIEDEREINTRITTSFAEHIGEN SPERRE: in den Rumpfen,
     * die unter ihr laufen, steht keine sperrende Fassung und kein zweites
     * `withLock`. Sonst haengt der Zyklus (oder der Marker-Druck) fuer immer.
     */
    @Test
    fun `unter der Sperre wird nie wieder gesperrt`() {
        val code = quelle()
        for (d in listOf(DEKLARATION, MARKER_DEKLARATION, RUECKNAHME_UNTER_SPERRE_DEKLARATION)) {
            val r = rumpf(code, d)
            assertFalse(r.contains("laufSperre.")) { "${d.pattern}: greift unter der Sperre erneut auf laufSperre zu" }
            for (sperrend in SPERRENDE_FASSUNGEN) {
                assertFalse(sperrend.containsMatchIn(r)) { "${d.pattern}: ruft die sperrende Fassung ${sperrend.pattern} - Deadlock" }
            }
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
        assertTrue(RUECKNAHME_UNTER_SPERRE_DEKLARATION.containsMatchIn(code)) { "faelligeRuecknahmeUnterSperre nicht gefunden" }
        assertTrue(PERSIST_AUFRUF.containsMatchIn(code)) { "persistiereMarkerZustand-Aufruf nicht gefunden" }
        assertTrue(SPERRENDE_FASSUNGEN.all { it.containsMatchIn(code) }) { "ein Muster der sperrenden Fassungen findet nichts" }
    }

    private companion object {

        /** `invoke(...) { laufSperre.withLock { withContext(Dispatchers.IO) { invokeUnterSperre(...) } } }`, zeilentolerant. */
        val INVOKE_UNTER_SPERRE = Regex(
            """override\s+suspend\s+fun\s+invoke\s*\(\s*initiator\s*:\s*String\s*,\s*tempBasalFallback\s*:\s*Boolean\s*\)\s*\{\s*""" +
                """laufSperre\s*\.\s*withLock\s*\{\s*withContext\s*\(\s*Dispatchers\s*\.\s*IO\s*\)\s*\{\s*""" +
                """invokeUnterSperre\s*\(\s*initiator\s*,\s*tempBasalFallback\s*\)\s*}\s*}\s*}""",
        )

        /** Aufrufe, nicht die Deklaration: davor steht kein `fun`. */
        val AUFRUF = Regex("""(?<!fun\s)\binvokeUnterSperre\s*\(""")

        val DEKLARATION = Regex("""private\s+suspend\s+fun\s+invokeUnterSperre\s*\(""")

        val SPERROBJEKT = Regex("""private\s+val\s+laufSperre\s*=\s*Mutex\s*\(\s*\)""")

        /** Die oeffentliche Huelle des Marker-Drucks (nicht die private Funktion). */
        val MARKER_OEFFENTLICH = Regex("""(?<!private\s)\bsuspend\s+fun\s+toggleMealMarker\s*\(""")

        val MARKER_DEKLARATION = Regex("""private\s+fun\s+toggleMealMarkerUnterSperre\s*\(""")

        val MARKER_AUFRUF = Regex("""(?<!fun\s)\btoggleMealMarkerUnterSperre\s*\(""")

        val RUECKNAHME_HUELLE = Regex(
            """private\s+suspend\s+fun\s+faelligeRuecknahme\s*\(\s*now\s*:\s*Long\s*\)\s*:\s*Boolean\s*=\s*""" +
                """laufSperre\s*\.\s*withLock\s*\{\s*faelligeRuecknahmeUnterSperre\s*\(\s*now\s*\)\s*}""",
        )

        val RUECKNAHME_UNTER_SPERRE_DEKLARATION = Regex("""private\s+suspend\s+fun\s+faelligeRuecknahmeUnterSperre\s*\(""")

        val RUECKNAHME_UNTER_SPERRE_AUFRUF = Regex("""(?<!fun\s)\bfaelligeRuecknahmeUnterSperre\s*\(""")

        val PERSIST_AUFRUF = Regex("""(?<!fun\s)\bpersistiereMarkerZustand\s*\(""")

        /** Die sperrenden Fassungen - unter der Sperre verboten. */
        val SPERRENDE_FASSUNGEN = listOf(
            Regex("""(?<!fun\s)\btoggleMealMarker\s*\("""),
            Regex("""(?<!fun\s)\bfaelligeRuecknahme\s*\("""),
            Regex("""(?<!fun\s)\bmarkerRuecknahmeVormerken\s*\("""),
            Regex("""(?<!fun\s)\bmarkerRuecknahmeRueckgaengig\s*\("""),
        )

        /** Beginn der naechsten Funktion auf Klassenebene (vier Leerzeichen eingerueckt). */
        val NAECHSTE_FUNKTION = Regex("""\n {4}(?:(?:private|override|internal|public)\s+)?(?:suspend\s+)?fun\s""")
    }
}
