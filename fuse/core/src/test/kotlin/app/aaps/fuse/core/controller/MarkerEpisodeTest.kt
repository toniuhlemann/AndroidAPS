package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die Ruecknahme darf die Huelle nicht zurueckgeben.
 *
 * Der Ablauf, um den es geht: versehentlich druecken, zuruecknehmen, richtig
 * druecken. Vorher gab das eine NEUE Episode mit voller Huelle - obendrauf auf
 * das bereits Abgegebene.
 */
class MarkerEpisodeTest {

    private val t0 = 1_786_000_000_000L
    private val fenster = 45
    private fun min(n: Int) = t0 + n * 60_000L

    /** DER FALL: zuruecknehmen und innerhalb des Fensters erneut armen. */
    @Test
    fun `erneutes Armen im Fenster ist KEINE neue Episode`() {
        assertFalse(
            MarkerEpisode.startsNewEpisode(
                armedTs = min(10), episodeTs = min(2), nowMs = min(10), windowMin = fenster
            ),
            "der Verbrauch der laufenden Episode muss stehenbleiben"
        )
    }

    /** Die Ruecknahme selbst beendet gar nichts - sie setzt nur die Aktivitaet. */
    @Test
    fun `die Ruecknahme allein startet keine Episode`() {
        assertFalse(
            MarkerEpisode.startsNewEpisode(
                armedTs = 0L, episodeTs = min(2), nowMs = min(5), windowMin = fenster
            )
        )
    }

    /** Nach ABLAUF ist es wirklich eine andere Mahlzeit - volle Huelle. */
    @Test
    fun `nach Ablauf des Fensters beginnt eine neue Episode`() {
        assertTrue(
            MarkerEpisode.startsNewEpisode(
                armedTs = min(50), episodeTs = min(2), nowMs = min(50), windowMin = fenster
            ),
            "sonst hungert jede spaetere Mahlzeit aus"
        )
    }

    /** Genau auf der Kante: das Fenster ist zu Ende, also neue Episode. */
    @Test
    fun `exakt am Fensterende beginnt eine neue Episode`() {
        assertTrue(
            MarkerEpisode.startsNewEpisode(
                armedTs = min(47), episodeTs = min(2), nowMs = min(2 + fenster), windowMin = fenster
            )
        )
    }

    /** Derselbe Marker noch einmal gesehen: nichts passiert. */
    @Test
    fun `derselbe Marker startet nichts neu`() {
        assertFalse(
            MarkerEpisode.startsNewEpisode(
                armedTs = min(2), episodeTs = min(2), nowMs = min(20), windowMin = fenster
            )
        )
    }

    /** Der allererste Marker ueberhaupt - es gibt keine laufende Episode. */
    @Test
    fun `der erste Marker beginnt eine Episode`() {
        assertTrue(
            MarkerEpisode.startsNewEpisode(
                armedTs = min(2), episodeTs = 0L, nowMs = min(2), windowMin = fenster
            )
        )
    }
}
