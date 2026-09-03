package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WANN DAS HISTORISCHE REBOUND-FENSTER DIE DIREKTDOSIS NICHT MEHR SPERRT.
 *
 * Der Vertrag ist eng: eine gueltige, ausdrueckliche, belastbar
 * zugeordnete Autorisierung MIT gewaehlter Direktdosis. Jede fehlende
 * Bedingung laesst den Rebound-Schutz unveraendert wirken.
 *
 * Alle Werte sind synthetisch.
 */
class MealUpfrontAuthorityTest {

    private val t0 = 1_700_000_000_000L
    private val id = "auth-3"

    private fun auth(
        markerTs: Long = t0,
        upfrontShare: Double = 1.0,
        autorisiert: Boolean = true,
        beobachtet: Boolean = true,
        ohneVorschuss: Boolean = false,
    ) = MealFoundation.arm(
        markerTs = markerTs, foundationEnabled = true, totalBudgetU = 4.0,
        phaseAShare = 0.8, phaseAUpfrontShare = upfrontShare,
        primeWindowMin = 15, wallCeilingMin = 45, phaseBUntilMin = 25,
        pressObservedInThisProcess = beobachtet, primeDeclinedByUser = ohneVorschuss,
        markerAuthorized = autorisiert,
    )

    private fun holds(
        a: MealFoundation.Authorization = auth(),
        markerTs: Long = t0,
        aktiv: Boolean = true,
        armedBy: String? = id,
        aktuelleKennung: String? = id,
    ) = MealUpfrontAuthority.holds(
        auth = a, activeMarkerTs = markerTs, markerActive = aktiv,
        foundationArmedByAuthId = armedBy, currentAuthId = aktuelleKennung,
    )

    /** DER FALL, UM DEN ES GEHT. */
    @Test
    fun `die vollstaendige Autorisierung traegt die Direktdosis`() {
        assertTrue(holds())
        assertFalse(MealUpfrontAuthority.reboundBlocks(reboundRaw = true, authorityHolds = true)) {
            "das historische Fenster darf diese Direktdosis nicht allein verwerfen"
        }
    }

    /** Ohne Rebound gibt es ohnehin nichts zu entsperren - und mit Rebound
     *  ohne Autorisierung bleibt der Schutz. */
    @Test
    fun `ohne Autorisierung bleibt der Rebound-Schutz wirksam`() {
        assertTrue(MealUpfrontAuthority.reboundBlocks(reboundRaw = true, authorityHolds = false))
        assertFalse(MealUpfrontAuthority.reboundBlocks(reboundRaw = false, authorityHolds = false))
    }

    /**
     * MARKER OHNE GEWAEHLTE DIREKTDOSIS - beide Wege, wie er entsteht:
     * Sofortanteil 0 und die ausdrueckliche Abwahl im Dialog.
     */
    @Test
    fun `ein Marker ohne gewaehlte Direktdosis bekommt keine`() {
        assertFalse(holds(auth(upfrontShare = 0.0))) { "Sofortanteil 0" }
        assertFalse(holds(auth(ohneVorschuss = true))) { "im Dialog abgewaehlt" }
    }

    @Test
    fun `ohne gepinnte Marker-Zusage traegt sie nicht`() {
        assertFalse(holds(auth(autorisiert = false)))
    }

    @Test
    fun `ein abgelaufener Marker traegt nicht`() {
        assertFalse(holds(aktiv = false))
    }

    /** Eine ANDERE, frueher armierte Mahlzeit darf nicht entsperren. */
    @Test
    fun `eine fremde Markeridentitaet traegt nicht`() {
        assertFalse(holds(auth(markerTs = t0 - 3_600_000L))) { "armedTs passt nicht" }
    }

    /**
     * DIE ZUORDNUNG IST DER FORTFUEHRUNGSNACHWEIS - und der einzige.
     *
     * Der Live-Druckmerker wird hier ABSICHTLICH nicht gelesen: er ist
     * fluechtig und stuende nach einem AAPS-Neustart wieder auf 0. Die
     * Druckpflicht liegt beim erstmaligen [MealFoundation.arm], das ohne
     * beobachteten Druck `none()` liefert - deshalb genuegt hier die
     * persistierte Zuordnung, und deshalb hat [MealUpfrontAuthority.holds]
     * gar keinen Druckparameter mehr.
     *
     * Den ECHTEN Neustart - schreiben, frisch laden, neuer Runner mit
     * `markerPress = 0` - prueft `TransportWiringTest`; hier steht der
     * Vertrag, dort die Verdrahtung.
     *
     * FEHLENDE KENNUNG IST KEINE ZUSTIMMUNG, dieselbe Wahl wie in der
     * Rueckbuchung: Altbestand traegt keine, und unbewiesen darf hier
     * nicht dosieren duerfen.
     */
    @Test
    fun `ohne belastbare Zuordnung traegt sie nicht`() {
        assertFalse(holds(armedBy = null, aktuelleKennung = null)) { "beide leer" }
        assertFalse(holds(armedBy = "auth-2")) { "unter einer anderen Kennung armiert" }
        assertFalse(holds(aktuelleKennung = null)) { "keine laufende Autorisierung" }
        assertFalse(holds(armedBy = "", aktuelleKennung = "")) { "leere Zeichenkette" }
    }

    @Test
    fun `ohne Marker traegt sie nicht`() {
        assertFalse(holds(markerTs = 0L))
    }
}
