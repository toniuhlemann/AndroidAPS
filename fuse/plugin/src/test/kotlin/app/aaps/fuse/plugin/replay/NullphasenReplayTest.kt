package app.aaps.fuse.plugin.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der Analyzer gegen SYNTHETISCHE Zyklen - keine Trails, keine echten
 * Zeitstempel, keine Messwerte im Repo. Geprueft wird die Rechnung,
 * nicht eine Nacht.
 */
class NullphasenReplayTest {

    private val t0 = 0L
    private fun min(m: Double) = (m * 60_000).toLong()

    private fun z(
        minute: Double,
        zero: Boolean = true,
        grund: Boolean = false,
        rate: Double? = 0.1,
        basal: Double? = 0.6,
        pub: Double = 0.0,
        meal: Boolean = false,
    ) = NullphasenReplay.Zyklus(
        tsMs = t0 + min(minute), zeroActive = zero, schutzgrund = grund,
        ukfRatePerMin = rate, signalHealthy = true, scheduledBasalUph = basal,
        publishedU = pub, mealAuthorized = meal,
    )

    // ---- PHASEN ----------------------------------------------------------

    @Test
    fun `Phasen sind zusammenhaengende Nullstrecken, kurze werden verworfen`() {
        val zyklen = (0..9).map { z(it.toDouble(), zero = it in 2..6) } +
            listOf(z(10.0, zero = true), z(11.0, zero = false))   // 1-Zyklus-Rauschen
        val p = NullphasenReplay.phasen(zyklen)
        assertEquals(1, p.size)
        assertEquals(min(2.0), p[0].vonMs)
        assertEquals(min(6.0), p[0].bisMs)
    }

    // ---- VARIANTE 1 ------------------------------------------------------

    @Test
    fun `der Ausgang feuert nach N zusammenhaengenden ruhigen Zyklen ohne Grund`() {
        // 0-3 mit Grund, ab 4 ohne Grund und ruhig; N=3 -> Ausgang bei 6.
        val zyklen = (0..19).map { z(it.toDouble(), grund = it <= 3) }
        val r = NullphasenReplay.variante1(zyklen, n = 3)
        val p = r.phasen.single()
        assertEquals(min(6.0), p.ausgangMs)
        assertEquals(13.0, p.weggefalleneMin, 1e-9, "von T+6 bis T+19 lief die Null im IST weiter")
        assertEquals(0.6 * 13.0 / 60.0, p.weggefallenesBasalU, 1e-9)
        assertEquals(1, r.betroffenePhasen)
    }

    @Test
    fun `ein fallender Zyklus setzt den Zaehler zurueck`() {
        // ruhig bei 4,5 - dann ein Fall bei 6 - dann wieder ruhig.
        val zyklen = (0..19).map {
            z(it.toDouble(), grund = it <= 3, rate = if (it == 6) -0.5 else 0.1)
        }
        val r = NullphasenReplay.variante1(zyklen, n = 3)
        assertEquals(min(9.0), r.phasen.single().ausgangMs) {
            "erst 7,8,9 sind wieder drei zusammenhaengende"
        }
    }

    @Test
    fun `eine unbekannte Rate zaehlt nicht als Erholung`() {
        val zyklen = (0..19).map { z(it.toDouble(), grund = it <= 3, rate = null) }
        assertNull(NullphasenReplay.variante1(zyklen, n = 3).phasen.single().ausgangMs)
    }

    @Test
    fun `hoeheres N greift spaeter und seltener`() {
        val zyklen = (0..19).map { z(it.toDouble(), grund = it <= 3) }
        val n3 = NullphasenReplay.variante1(zyklen, 3)
        val n5 = NullphasenReplay.variante1(zyklen, 5)
        assertTrue(n5.phasen.single().ausgangMs!! > n3.phasen.single().ausgangMs!!)
        assertTrue(n5.weggefalleneMin < n3.weggefalleneMin)
    }

    @Test
    fun `erneuter Schutzgrund wird als Flatterpotenzial ausgewiesen, nicht als Sicherheit`() {
        // Ausgang bei 6, ab 11 liegt im UNVERAENDERTEN Signal wieder ein
        // Grund an. Das ist eine Aussage ueber die Aufzeichnung - nicht
        // darueber, was unter der Variante geschehen waere.
        val zyklen = (0..19).map { z(it.toDouble(), grund = it <= 3 || it >= 11) }
        val p = NullphasenReplay.variante1(zyklen, 3).phasen.single()
        assertEquals(min(6.0), p.ausgangMs)
        assertEquals(5.0, p.erneuterGrundNachMin!!, 1e-9)
    }

    @Test
    fun `zusaetzliche Kommandos zaehlen Ausgang und erneutes Zuenden`() {
        val mitWieder = (0..19).map { z(it.toDouble(), grund = it <= 3 || it >= 11) }
        val ohneWieder = (0..19).map { z(it.toDouble(), grund = it <= 3) }
        assertEquals(2, NullphasenReplay.variante1(mitWieder, 3).zusaetzlicheKommandos) {
            "ein Abbruch plus ein erneutes Null-Kommando"
        }
        assertEquals(1, NullphasenReplay.variante1(ohneWieder, 3).zusaetzlicheKommandos)
        assertEquals(5.0, NullphasenReplay.variante1(mitWieder, 3).laengsteRuheMin!!, 1e-9)
    }

    @Test
    fun `ohne Ausgang bleibt alles bei null`() {
        val zyklen = (0..19).map { z(it.toDouble(), grund = true) }
        val r = NullphasenReplay.variante1(zyklen, 3)
        assertEquals(0, r.betroffenePhasen)
        assertEquals(0.0, r.weggefalleneMin, 1e-12)
        assertEquals(0.0, r.weggefallenesBasalU, 1e-12)
        assertNull(r.laengsteRuheMin)
    }

    // ---- VARIANTE 2 ------------------------------------------------------

    @Test
    fun `die Verteilung zeigt das Maximum der rollierenden Menge`() {
        // Vier Dosen a 0,25 innerhalb von 10 min, danach Pause.
        val zyklen = listOf(
            z(0.0, zero = false, pub = 0.25), z(3.0, zero = false, pub = 0.25),
            z(6.0, zero = false, pub = 0.25), z(9.0, zero = false, pub = 0.25),
            z(60.0, zero = false, pub = 0.25),
        )
        val v30 = NullphasenReplay.verteilung(zyklen, 30)
        assertEquals(1.00, v30.maxU, 1e-9, "alle vier liegen im 30-min-Fenster")
        val v5 = NullphasenReplay.verteilung(zyklen, 5)
        assertEquals(0.50, v5.maxU, 1e-9, "im 5-min-Fenster sind es hoechstens zwei")
    }

    @Test
    fun `ein Deckel oberhalb des Maximums kann nie binden`() {
        val zyklen = listOf(
            z(0.0, zero = false, pub = 0.25), z(3.0, zero = false, pub = 0.25),
            z(6.0, zero = false, pub = 0.25), z(9.0, zero = false, pub = 0.25),
        )
        val max = NullphasenReplay.verteilung(zyklen, 30).maxU
        val r = NullphasenReplay.variante2(zyklen, deckelU = max + 0.01, fensterMin = 30)
        assertEquals(0.0, r.gekapptU, 1e-12)
        assertNull(r.ersteBindungMs)
        assertEquals(0, r.betroffeneDosen)
    }

    @Test
    fun `der Deckel kappt und weist die erste Bindung aus`() {
        val zyklen = listOf(
            z(0.0, zero = false, pub = 0.30), z(3.0, zero = false, pub = 0.30),
            z(6.0, zero = false, pub = 0.30),
        )
        val r = NullphasenReplay.variante2(zyklen, deckelU = 0.50, fensterMin = 30)
        assertEquals(0.50, r.geflossenU, 1e-9)
        assertEquals(0.40, r.gekapptU, 1e-9)
        assertEquals(min(3.0), r.ersteBindungMs)
        assertEquals(2, r.betroffeneDosen)
    }

    @Test
    fun `MEAL-Dosen belasten den Deckel nicht`() {
        val zyklen = listOf(
            z(0.0, zero = false, pub = 0.30, meal = true),
            z(3.0, zero = false, pub = 0.30),
        )
        val r = NullphasenReplay.variante2(zyklen, deckelU = 0.30, fensterMin = 30)
        assertEquals(0.0, r.gekapptU, 1e-12, "die markerlose 0,30 passt allein in den Deckel")
    }

    @Test
    fun `die Fensterkanten zeigen den Saegezahn - Zeitpunkt und Sprunghoehe`() {
        // Zwei Dosen, Fenster 30: der Headroom springt 30 min nach jeder.
        val zyklen = listOf(z(0.0, zero = false, pub = 0.30), z(5.0, zero = false, pub = 0.20))
        val r = NullphasenReplay.variante2(zyklen, deckelU = 1.0, fensterMin = 30)
        assertEquals(2, r.kanten.size)
        assertEquals(min(30.0), r.kanten[0].tsMs)
        assertEquals(0.30, r.kanten[0].sprungU, 1e-9)
        assertEquals(min(35.0), r.kanten[1].tsMs)
        assertEquals(0.20, r.kanten[1].sprungU, 1e-9)
        assertEquals(0.30, r.groessterSprungU, 1e-9) {
            "der groesste Sprung ist die Menge, die schlagartig wieder moeglich wird"
        }
    }

    @Test
    fun `eine gekappte Dose erzeugt nur in Hoehe des Geflossenen eine Kante`() {
        // Die gekappte Menge floss nie - sie kann auch nichts freigeben.
        val zyklen = listOf(z(0.0, zero = false, pub = 0.30), z(3.0, zero = false, pub = 0.30))
        val r = NullphasenReplay.variante2(zyklen, deckelU = 0.40, fensterMin = 30)
        assertEquals(0.30, r.kanten[0].sprungU, 1e-9)
        assertEquals(0.10, r.kanten[1].sprungU, 1e-9, "von 0,30 floss nur 0,10")
    }
}
