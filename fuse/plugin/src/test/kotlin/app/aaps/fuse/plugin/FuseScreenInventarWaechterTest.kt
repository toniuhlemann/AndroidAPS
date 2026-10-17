package app.aaps.fuse.plugin

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.PreferenceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER WAECHTER UEBER DAS EINSTELLUNGS-INVENTAR DES BILDSCHIRMS.
 *
 * WARUM ES IHN GIBT (Geraetefund von Toni im Fork): `PrimeWindowMin` war
 * vollstaendig verdrahtet - Key, Runner, Bericht, put/store, Strings - nur die
 * eine Zeile im Bildschirm fehlte. Die Inventar-Wache im Plugin warf erst, als
 * jemand am Geraet die FUSE-Einstellungen OEFFNETE - und dann stuerzte die App
 * ab. Auf einem Regler, der produktiv Insulin dosiert, ist "der Nutzer findet
 * den Fehler durch einen Absturz" die falsche Reihenfolge.
 *
 * v4: der Bildschirm ist DEKLARATIV ([FuseSettings.abschnitte]) statt ein
 * Stueck Bau-Code. Der Fork musste deshalb den Quelltext lesen; hier wird der
 * Baum selbst geprueft, mit denselben Fragen:
 *  - steht jeder Vertragsschluessel ([fuseEinstellbareKeys]) im Bildschirm,
 *    und nichts darueber hinaus?
 *  - steht jeder Wert genau einmal da (ein doppelter Eintrag zeigte zwei
 *    Regler fuer einen Wert)?
 *  - hat jeder Bereich einen eigenen Schluessel und mindestens einen Wert?
 *  - ist jeder FUSE-Wert im Bildschirm registriert (`ownPreferences`)? Ein
 *    nicht registrierter Schluessel fiele aus Sicherung UND Import.
 * Die SummaryProvider-Falle des Forks gibt es im Compose-Bildschirm nicht mehr.
 *
 * SCHLAEGT ER FEHL, ist die Reaktion NICHT, die erwartete Menge anzupassen,
 * sondern den Bildschirm zu ergaenzen (oder den Key aus `fuseEinstellbareKeys`
 * zu nehmen, falls er gar nicht einstellbar sein soll).
 */
class FuseScreenInventarWaechterTest {

    private val abschnitte = FuseSettings.abschnitte()

    /** Die Werte des Baums - ohne eigene Seiten, wie in [FuseSettings.einstellbareKeys]. */
    private val werte: List<PreferenceKey> = abschnitte.flatMap { it.items }.filterIsInstance<PreferenceKey>()

    @Test
    fun `jeder einstellbare Key hat eine Zeile im Bildschirm`() {
        val fehlend = fuseEinstellbareKeys - FuseSettings.einstellbareKeys().toSet()
        assertTrue(fehlend.isEmpty()) {
            "Diese Keys stehen in fuseEinstellbareKeys, werden im Bildschirm aber nicht gezeigt: $fehlend"
        }
    }

    @Test
    fun `der Bildschirm zeigt keinen Key ausserhalb des Vertrags`() {
        val ueberzaehlig = FuseSettings.einstellbareKeys().toSet() - fuseEinstellbareKeys
        assertTrue(ueberzaehlig.isEmpty()) {
            "Diese Keys zeigt der Bildschirm, ohne dass sie im Vertrag (und damit im Bericht des Reiters) stehen: $ueberzaehlig"
        }
    }

    @Test
    fun `jeder Wert steht genau einmal im Bildschirm`() {
        val keys = FuseSettings.einstellbareKeys()
        val doppelt = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(doppelt.isEmpty()) { "doppelt im Bildschirm: $doppelt" }
    }

    @Test
    fun `jeder Bereich hat einen eigenen Schluessel und mindestens einen Wert`() {
        val bereiche = abschnitte.map { it.key }
        assertEquals(bereiche.size, bereiche.toSet().size, "Bereich doppelt: $bereiche")
        val seiten = FuseIntentKey.entries.map { it.key }
        assertEquals(seiten.size, seiten.toSet().size, "eigene Seite doppelt: $seiten")
        val alleNamen = bereiche + seiten
        assertTrue(alleNamen.none { it in FuseSettings.einstellbareKeys() }) { "ein Bereich oder eine Seite heisst wie ein Wert" }
        assertTrue(bereiche.none { it in seiten }) { "ein Bereich heisst wie eine eigene Seite" }
        for (b in abschnitte) assertTrue(b.items.isNotEmpty()) { "Bereich ${b.key} zeigt keinen Wert" }
    }

    /** Ein FUSE-Wert, der nicht registriert ist, fiele aus Sicherung und Import - die geteilte maxIOB-Grenze gehoert AAPS. */
    @Test
    fun `jeder FUSE-Wert im Bildschirm ist registriert`() {
        val registriert = fuseSchluessel.map { it.key }.toSet()
        val fremd = setOf(DoubleKey.ApsSmbMaxIob.key)
        val nichtRegistriert = werte.map { it.key }.filter { it !in fremd && it !in registriert }
        assertTrue(nichtRegistriert.isEmpty()) { "im Bildschirm, aber nicht registriert: $nichtRegistriert" }
    }

    @Test
    fun `der Waechter greift ueberhaupt`() {
        val gebaut = FuseSettings.einstellbareKeys()
        assertTrue(gebaut.size >= 20) {
            "Nur ${gebaut.size} Bildschirmeintraege erkannt - der Waechter waere blind. Erkannt: $gebaut"
        }
        assertTrue(FuseIntKey.LivenessMealPowerMin.key in gebaut) {
            "Ein bekannter Eintrag (Mahlzeiten-Frist) wird nicht erkannt"
        }
        assertTrue(fuseEinstellbareKeys.size >= 20) { "der Vertrag ist leer - der Waechter waere blind" }
    }
}
