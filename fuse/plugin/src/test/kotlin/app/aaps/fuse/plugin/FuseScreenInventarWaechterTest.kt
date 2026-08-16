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

    private val muster =
        Regex("""\b(FuseIntKey|FuseDoubleKey|FuseBooleanKey|IntKey|DoubleKey|BooleanKey)\.(\w+)""")

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
    private fun gebauteKeys(): Set<String> =
        bildschirmBlock().lines()
            .map { it.substringBefore("//").trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") }
            .flatMap { zeile -> muster.findAll(zeile).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            .mapNotNull { (typ, name) -> aufloesen(typ, name) }
            .toSet()

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
