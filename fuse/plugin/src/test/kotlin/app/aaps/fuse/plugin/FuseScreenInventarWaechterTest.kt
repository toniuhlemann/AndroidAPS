package app.aaps.fuse.plugin

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER WAECHTER UEBER DAS EINSTELLUNGS-INVENTAR DES BILDSCHIRMS.
 *
 * WARUM ES IHN GIBT (16.08.2026, Geraetefund von Toni): `PrimeWindowMin` war
 * vollstaendig verdrahtet - Key, Runner, Bericht, put/store, Strings - nur die
 * eine `addPreference`-Zeile im Bildschirm fehlte. Die Inventar-Wache in
 * `FusePlugin.addPreferenceScreen` hat das korrekt bemerkt und geworfen:
 *
 *     IllegalArgumentException: FUSE-Einstellungsinventar unvollstaendig:
 *     fehlt=[fuse_prime_window_min]
 *
 * Nur feuert diese Wache erst, wenn ein Mensch am Geraet die FUSE-
 * Einstellungen OEFFNET - und dann stuerzt die App ab. Auf einem Regler, der
 * produktiv Insulin dosiert, ist "der Nutzer findet den Fehler durch einen
 * Absturz" die falsche Reihenfolge. [FuseSettingsReportTest] deckt genau diese
 * Luecke fuer den BERICHT ab und sagt in seinem Kommentar selbst, dass der
 * BILDSCHIRM ungeprueft bleibt. Dieser Test schliesst sie.
 *
 * ES WAR DER VIERTE UI-FEHLER, DEN TONI AM GERAET FAND STATT EIN TEST. Die
 * Bauform ist deshalb bewusst dieselbe wie beim [ledger.LoopPluginAnnahmeWaechterTest]:
 * statischer Blick in den Quelltext, kein Robolectric. Der Bildschirm laesst
 * sich ohne Android-Laufzeit nicht bauen, aber die Frage, die hier zaehlt, ist
 * gar keine Laufzeitfrage - sie lautet: STEHT FUER JEDEN VERTRAGSSCHLUESSEL
 * EINE ZEILE IM QUELLTEXT?
 *
 * SCHLAEGT ER FEHL, ist die Reaktion NICHT, die erwartete Menge anzupassen,
 * sondern die fehlende `addPreference`-Zeile zu ergaenzen (oder den Key aus
 * `fuseEinstellbareKeys` zu nehmen, falls er gar nicht einstellbar sein soll).
 */
class FuseScreenInventarWaechterTest {

    private fun pluginQuelle(): String {
        val kandidaten = listOf(
            "src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
            "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
            "../../fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
        )
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "FusePlugin.kt nicht gefunden - Waechter kann das Inventar nicht pruefen" }
        return f.readText()
    }

    /**
     * Der Bauabschnitt des Bildschirms, sauber begrenzt: von der Funktion bis
     * zur Inventar-Wache. Alles danach ist die Wache selbst und wuerde die
     * Schluesselnamen ein zweites Mal einfangen.
     */
    private fun bildschirmBlock(): String {
        val quelle = pluginQuelle()
        val start = quelle.indexOf("fun addPreferenceScreen")
        val ende = quelle.indexOf("FUSE-Einstellungsinventar unvollstaendig")
        assertTrue(start >= 0) { "addPreferenceScreen nicht gefunden - wurde die Funktion umbenannt?" }
        assertTrue(ende > start) { "Inventar-Wache nicht gefunden - wurde sie entfernt? Dann traegt NUR noch dieser Test." }
        return quelle.substring(start, ende)
    }

    /** Loest `FuseIntKey.PrimeWindowMin` zum tatsaechlichen Schluessel auf.
     *  Ueber die Enums statt ueber Textvergleich, damit ein umbenannter
     *  Preference-String den Test nicht still an uns vorbeilaufen laesst. */
    private fun aufloesen(typ: String, name: String): String? = when (typ) {
        "FuseIntKey"     -> FuseIntKey.entries.firstOrNull { it.name == name }?.key
        "FuseDoubleKey"  -> FuseDoubleKey.entries.firstOrNull { it.name == name }?.key
        "FuseBooleanKey" -> FuseBooleanKey.entries.firstOrNull { it.name == name }?.key
        "IntKey"         -> IntKey.entries.firstOrNull { it.name == name }?.key
        "DoubleKey"      -> DoubleKey.entries.firstOrNull { it.name == name }?.key
        "BooleanKey"     -> BooleanKey.entries.firstOrNull { it.name == name }?.key
        else             -> null
    }

    /**
     * TYPGEBUNDEN, nicht bloss namenssuchend - Auditbefund P1-6 (16.08.2026).
     *
     * Die erste Fassung suchte nur nach dem Vorkommen eines Schluesselnamens
     * im Bauabschnitt. Eine ausgefuehrte Mutationsprobe des Gesamtaudits hat
     * das als blind nachgewiesen: ersetzt man eine Preference-Zeile durch eine
     * `info()`-Zeile, die denselben Schluessel nennt, bleibt der Waechter
     * gruen - obwohl der Wert dann NICHT MEHR BEDIENBAR ist. Genau die Klasse
     * des Absturzes vom 16.08., nur umgekehrt.
     *
     * Deshalb wird jetzt die BAUFORM verlangt: der Schluessel muss als
     * `intKey =` / `doubleKey =` / `booleanKey =` an einer Adaptive-Preference
     * haengen oder ueber die Hilfsfunktion `timeOfDay(...)` gebaut werden.
     * Eine `info()`-Zeile erfuellt das nicht - sie baut ein
     * `Preference(context)` mit `isPersistent = false`.
     *
     * WHITESPACE- UND ZEILENTOLERANT (dieselbe Lehre): `intKey=X` und
     * `intKey  =  X` sind dieselbe Bauform, und der Schluessel darf in einer
     * anderen Zeile stehen als der Aufruf. Der Bauabschnitt wird deshalb vor
     * der Suche zu einer Zeile normalisiert.
     *
     * FEHLERRICHTUNG: kommt eine NEUE Hilfsfunktion dazu, die hier nicht
     * steht, meldet der Waechter den Schluessel faelschlich als fehlend. Das
     * ist die sichere Richtung - ein Fehlalarm kostet eine Minute, ein blinder
     * Waechter kostet einen Absturz am Geraet.
     */
    private val muster = Regex(
        """(?:intKey|doubleKey|booleanKey)\s*=\s*(FuseIntKey|FuseDoubleKey|FuseBooleanKey|IntKey|DoubleKey|BooleanKey)\s*\.\s*(\w+)""" +
            """|timeOfDay\s*\(\s*(FuseIntKey|IntKey)\s*\.\s*(\w+)"""
    )

    /**
     * Die Schluessel, die der Bildschirm laut Quelltext tatsaechlich baut.
     *
     * BEWUSST NICHT auf Zeilen mit `addPreference(` gefiltert - dieser erste
     * Anlauf hatte drei Falsch-Positive, weil der Bildschirm zwei weitere
     * Bauformen kennt, die beide legitim sind:
     *   - mehrzeilige Aufrufe, bei denen `addPreference(` und der Schluessel
     *     in verschiedenen Zeilen stehen (ApsSmbMaxIob),
     *   - Hilfsfunktionen, die selbst addPreference rufen und in ihrer
     *     Aufrufzeile gar kein `addPreference(` tragen (`timeOfDay(...)` fuer
     *     Nacht-Beginn und Nacht-Ende).
     * Gezaehlt wird deshalb jede Schluesselnennung im Bauabschnitt; nur
     * Kommentare werden vorher entfernt, weil dort Schluessel erklaert und
     * nicht gebaut werden.
     */
    private fun gebauteKeys(): Set<String> {
        // Kommentare raus, dann zu EINER Zeile - so treffen die Muster auch
        // mehrzeilige Aufrufe (ApsSmbMaxIob wird ueber vier Zeilen gebaut).
        val block = bildschirmBlock().lines()
            .map { it.substringBefore("//").trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") }
            .joinToString(" ")
        return muster.findAll(block)
            .mapNotNull { m ->
                // Gruppe 1/2 = benannte Argumentform, Gruppe 3/4 = timeOfDay.
                val typ = m.groupValues[1].ifEmpty { m.groupValues[3] }
                val name = m.groupValues[2].ifEmpty { m.groupValues[4] }
                if (typ.isEmpty() || name.isEmpty()) null else aufloesen(typ, name)
            }
            .toSet()
    }

    /**
     * DER FALL VOM 16.08. Ein Key im Vertrag ohne Zeile im Bildschirm laesst
     * die App beim Oeffnen der Einstellungen abstuerzen.
     */
    @Test
    fun `jeder einstellbare Key hat eine Zeile im Bildschirm`() {
        val fehlend = fuseEinstellbareKeys - gebauteKeys()
        assertTrue(fehlend.isEmpty()) {
            "Diese Keys stehen in fuseEinstellbareKeys, werden im Bildschirm aber nicht gebaut - " +
                "die FUSE-Einstellungen wuerden beim Oeffnen ABSTUERZEN: $fehlend"
        }
    }

    /**
     * Die Gegenrichtung. Die Wache am Geraet prueft auf GLEICHHEIT, also
     * stuerzt auch ein ueberzaehliger Bildschirmeintrag die App ab - und ein
     * Wert, den der Nutzer verstellen kann, der aber im Bericht des Reiters
     * fehlt, waere ohnehin ein blinder Fleck.
     */
    @Test
    fun `der Bildschirm baut keinen Key ausserhalb des Vertrags`() {
        val ueberzaehlig = gebauteKeys() - fuseEinstellbareKeys
        assertTrue(ueberzaehlig.isEmpty()) {
            "Diese Keys baut der Bildschirm, ohne dass sie im Vertrag stehen: $ueberzaehlig"
        }
    }

    /**
     * SELBSTPRUEFUNG DES WAECHTERS. Ein statischer Test, der nichts findet,
     * weil sein Muster ins Leere greift, ist schlimmer als keiner - er meldet
     * dann fuer immer "gruen". Ein Vertrag mit ueber zwanzig Eintraegen muss
     * sich in ebenso vielen erkannten Zeilen wiederfinden.
     */
    @Test
    fun `der Waechter greift ueberhaupt`() {
        val gebaut = gebauteKeys()
        assertTrue(gebaut.size >= 20) {
            "Nur ${gebaut.size} Bildschirmeintraege erkannt - das Muster greift nicht mehr, " +
                "der Waechter waere blind. Erkannt: $gebaut"
        }
        assertTrue(FuseIntKey.PrimeWindowMin.key in gebaut) {
            "Der Anlassfall vom 16.08. (Freigabe-Fenster) wird nicht erkannt"
        }
    }
}
